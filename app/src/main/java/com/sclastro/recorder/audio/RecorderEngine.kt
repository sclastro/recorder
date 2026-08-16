package com.sclastro.recorder.audio

import android.annotation.SuppressLint
import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The capture loop. Reads raw PCM off [AudioRecord] on a dedicated audio-priority
 * thread and hands it to an [AudioSink], while publishing level and elapsed-time
 * state for the UI.
 *
 * Raw PCM (rather than MediaRecorder) is what makes selectable sample rate and
 * bit depth possible at all, and it gives the level meter and live waveform for
 * free — the same buffers feed both.
 */
class RecorderEngine {

    enum class Status { IDLE, RECORDING, PAUSED }

    /**
     * Behaviour that shapes the capture without changing the audio format.
     *
     * Auto-split closes the current file and opens the next one at a boundary,
     * so a day-long recording is a set of manageable files rather than one
     * enormous one. VOX stops *writing* while the input is quiet — it keeps
     * reading, so it can start again the instant sound returns.
     */
    data class Policy(
        val splitMinutes: Int = 0,
        val splitMegabytes: Int = 0,
        val voxEnabled: Boolean = false,
        val voxThresholdDb: Float = -40f,
    ) {
        val splits: Boolean get() = splitMinutes > 0 || splitMegabytes > 0

        /**
         * Whether the current file has reached a boundary. An empty segment
         * never splits, so a misconfigured zero-length limit cannot spin out
         * a stream of empty files.
         */
        fun shouldSplit(segmentFrames: Long, sampleRate: Int, fileBytes: Long): Boolean {
            if (!splits || segmentFrames <= 0 || sampleRate <= 0) return false
            if (splitMinutes > 0 && segmentFrames >= splitMinutes.toLong() * 60 * sampleRate) return true
            return splitMegabytes > 0 && fileBytes >= splitMegabytes.toLong() * 1024 * 1024
        }
    }

    data class State(
        val status: Status = Status.IDLE,
        val elapsedMs: Long = 0,
        val peakDb: Float = SILENCE_DB,
        val rmsDb: Float = SILENCE_DB,
        val clipping: Boolean = false,
        val config: RecordingConfig = RecordingConfig(),
        val pendingFile: File? = null,
        val bookmarksMs: List<Long> = emptyList(),
        val bytesWritten: Long = 0,
        val error: String? = null,
        /** 1-based file number when auto-split is on, else 1. */
        val partNumber: Int = 1,
        /** True while VOX is armed but the input is below the threshold. */
        val voxIdle: Boolean = false,
    ) {
        val isActive: Boolean get() = status != Status.IDLE
    }

    data class Result(
        val file: File,
        val durationMs: Long,
        val peaks: ByteArray,
        val config: RecordingConfig,
        val bookmarksMs: List<Long>,
        /** 1-based file number; only meaningful when auto-split produced it. */
        val partNumber: Int = 1,
    ) {
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Rolling window of recent levels for the live waveform, oldest first. */
    private val _levels = MutableStateFlow(FloatArray(LEVEL_WINDOW))
    val levels: StateFlow<FloatArray> = _levels.asStateFlow()

    /**
     * Files finished by auto-split while capture continues. The final file is
     * not published here — it comes back from [stop] as before, so a recording
     * that never splits behaves exactly as it always did.
     */
    private val _segments = MutableSharedFlow<Result>(extraBufferCapacity = 16)
    val segments: SharedFlow<Result> = _segments.asSharedFlow()

    private var thread: Thread? = null
    private val stopRequested = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val discardOnStop = AtomicBoolean(false)

    @Volatile private var result: Result? = null
    private val lock = Any()

    /**
     * Begins capture into [pendingFile]. Returns null on success, or a message
     * describing why the device refused the requested configuration.
     */
    @SuppressLint("MissingPermission")
    fun start(
        rawConfig: RecordingConfig,
        pendingFile: File,
        policy: Policy = Policy(),
        nextFile: ((Int) -> File)? = null,
    ): String? = synchronized(lock) {
        val outcome = doStart(rawConfig, pendingFile, policy, nextFile)
        // Nothing else is watching the return value once the service has been
        // handed the request, so the message has to reach the UI via state.
        if (outcome != null) _state.value = State(error = outcome)
        return outcome
    }

    @SuppressLint("MissingPermission")
    private fun doStart(
        rawConfig: RecordingConfig,
        pendingFile: File,
        policy: Policy,
        nextFile: ((Int) -> File)?,
    ): String? {
        if (thread != null) return "Already recording"
        val config = rawConfig.normalised()

        val minBuffer = AudioRecord.getMinBufferSize(
            config.sampleRate,
            config.channels.inMask,
            config.bitDepth.encoding,
        )
        if (minBuffer <= 0) {
            return "This device does not support ${config.sampleRate} Hz / ${config.bitDepth.label} / ${config.channels.label}"
        }

        val chunkBytes = (config.sampleRate / CHUNKS_PER_SECOND) * config.bytesPerFrame
        val bufferBytes = maxOf(minBuffer * 2, chunkBytes * 8)

        val record = try {
            AudioRecord(config.source.value, config.sampleRate, config.channels.inMask, config.bitDepth.encoding, bufferBytes)
        } catch (e: IllegalArgumentException) {
            return "Unsupported recording settings: ${e.message}"
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return "Could not open the microphone — another app may be using it"
        }

        val effects = attachEffects(record, config)

        val sink = try {
            openSink(pendingFile, config)
        } catch (e: Exception) {
            releaseEffects(effects)
            record.release()
            return "Could not start the encoder: ${e.message}"
        }

        stopRequested.set(false)
        paused.set(false)
        discardOnStop.set(false)
        result = null
        _levels.value = FloatArray(LEVEL_WINDOW)
        _state.value = State(
            status = Status.RECORDING,
            config = config,
            pendingFile = pendingFile,
        )

        thread = Thread(
            { captureLoop(record, sink, effects, config, pendingFile, policy, nextFile) },
            "recorder-capture",
        ).also { it.start() }
        return null
    }

    private fun openSink(file: File, config: RecordingConfig): AudioSink {
        file.parentFile?.mkdirs()
        return when (config.container) {
            AudioContainer.WAV -> WavSink(file, config.sampleRate, config.channels.count, config.bitDepth)
            else -> EncodedSink(file, config.sampleRate, config.channels.count, config.bitrateKbps, config.container)
        }
    }

    fun pause() {
        if (_state.value.status == Status.RECORDING) {
            paused.set(true)
            _state.value = _state.value.copy(status = Status.PAUSED, peakDb = SILENCE_DB, rmsDb = SILENCE_DB)
        }
    }

    fun resume() {
        if (_state.value.status == Status.PAUSED) {
            paused.set(false)
            _state.value = _state.value.copy(status = Status.RECORDING)
        }
    }

    /** Marks the current position; the timestamp is kept with the recording. */
    fun addBookmark() {
        val s = _state.value
        if (s.isActive) {
            _state.value = s.copy(bookmarksMs = s.bookmarksMs + s.elapsedMs)
        }
    }

    /** Stops and finalises. Blocks briefly while the encoder flushes. */
    fun stop(): Result? {
        val worker = synchronized(lock) { thread } ?: return null
        stopRequested.set(true)
        paused.set(false)
        worker.join(STOP_TIMEOUT_MS)
        return result
    }

    /** Stops and deletes the partial file. */
    fun cancel() {
        discardOnStop.set(true)
        stop()
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    /** Surfaces a failure that happened outside the engine, e.g. in the service. */
    fun reportError(message: String) {
        _state.value = _state.value.copy(error = message)
    }

    private fun captureLoop(
        record: AudioRecord,
        sink: AudioSink,
        effects: List<Any>,
        config: RecordingConfig,
        pendingFile: File,
        policy: Policy,
        nextFile: ((Int) -> File)?,
    ) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val chunkFrames = config.sampleRate / CHUNKS_PER_SECOND
        val chunkBytes = chunkFrames * config.bytesPerFrame
        val chunkMs = 1000L / CHUNKS_PER_SECOND
        val buffer = ByteArray(chunkBytes)
        val window = FloatArray(LEVEL_WINDOW)
        var windowCursor = 0
        var emitCounter = 0
        var failure: String? = null

        // Totals across the whole session; the timer and bookmarks use these.
        var framesWritten = 0L
        var bytesWritten = 0L

        // Reset at each auto-split boundary.
        var currentFile = pendingFile
        var currentSink = sink
        var peakTrack = Peaks.Recorder(config.sampleRate, config.bitDepth, config.channels.count)
        var partNumber = 1
        var segmentStartFrames = 0L
        var segmentBookmarkFloor = 0L

        // VOX: writing only resumes once sound returns, and keeps going for a
        // moment afterwards so the tail of a word is not clipped off.
        var quietMs = 0L
        var voxWriting = !policy.voxEnabled

        try {
            record.startRecording()
            while (!stopRequested.get()) {
                val read = record.read(buffer, 0, chunkBytes)
                if (read <= 0) {
                    if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_DEAD_OBJECT) {
                        failure = "Recording interrupted — the microphone was taken by another app"
                        break
                    }
                    continue
                }
                if (paused.get()) continue

                val (peak, rms) = PcmMath.analyse(buffer, read, config.bitDepth)
                val peakDb = PcmMath.toDb(peak)

                if (policy.voxEnabled) {
                    if (peakDb >= policy.voxThresholdDb) {
                        quietMs = 0
                        voxWriting = true
                    } else if (voxWriting) {
                        quietMs += chunkMs
                        if (quietMs >= VOX_HANGOVER_MS) voxWriting = false
                    }
                }

                if (voxWriting) {
                    currentSink.write(buffer, read)
                    bytesWritten += read
                    framesWritten += read / config.bytesPerFrame
                    peakTrack.feed(peak, read)
                }

                window[windowCursor] = PcmMath.dbToFraction(peakDb)
                windowCursor = (windowCursor + 1) % LEVEL_WINDOW

                // Only ever on a chunk boundary, so no frame is split in half.
                val segmentFrames = framesWritten - segmentStartFrames
                if (nextFile != null &&
                    policy.shouldSplit(segmentFrames, config.sampleRate, currentFile.length())
                ) {
                    val boundaryMs = framesWritten * 1000 / config.sampleRate
                    finishSegment(
                        file = currentFile,
                        sink = currentSink,
                        peaks = peakTrack,
                        config = config,
                        durationMs = segmentFrames * 1000 / config.sampleRate,
                        bookmarks = _state.value.bookmarksMs
                            .filter { it in segmentBookmarkFloor until boundaryMs }
                            .map { it - segmentBookmarkFloor },
                        partNumber = partNumber,
                    )
                    partNumber++
                    currentFile = nextFile(partNumber)
                    currentSink = openSink(currentFile, config)
                    peakTrack = Peaks.Recorder(config.sampleRate, config.bitDepth, config.channels.count)
                    segmentStartFrames = framesWritten
                    segmentBookmarkFloor = boundaryMs
                    _state.value = _state.value.copy(partNumber = partNumber, pendingFile = currentFile)
                }

                if (++emitCounter >= EMIT_EVERY_N_CHUNKS) {
                    emitCounter = 0
                    _levels.value = orderedWindow(window, windowCursor)
                    _state.value = _state.value.copy(
                        elapsedMs = framesWritten * 1000 / config.sampleRate,
                        peakDb = peakDb,
                        rmsDb = PcmMath.toDb(rms),
                        clipping = peak >= CLIP_THRESHOLD,
                        bytesWritten = bytesWritten,
                        voxIdle = policy.voxEnabled && !voxWriting,
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "capture failed", e)
            failure = e.message ?: "Recording failed"
        } finally {
            runCatching { record.stop() }
            record.release()
            releaseEffects(effects)
            runCatching { currentSink.close() }

            val segmentFrames = framesWritten - segmentStartFrames
            val durationMs = if (config.sampleRate > 0) segmentFrames * 1000 / config.sampleRate else 0
            val discard = discardOnStop.get() || segmentFrames == 0L
            if (discard) {
                currentFile.delete()
                Peaks.delete(currentFile)
                result = null
            } else {
                val peaks = peakTrack.snapshot()
                Peaks.save(currentFile, peaks)
                result = Result(
                    file = currentFile,
                    durationMs = durationMs,
                    peaks = peaks,
                    config = config,
                    bookmarksMs = _state.value.bookmarksMs
                        .filter { it >= segmentBookmarkFloor }
                        .map { it - segmentBookmarkFloor },
                    partNumber = partNumber,
                )
            }
            synchronized(lock) { thread = null }
            _state.value = State(error = failure)
            _levels.value = FloatArray(LEVEL_WINDOW)
        }
    }

    /** Closes one auto-split file and hands it to whoever is collecting. */
    private fun finishSegment(
        file: File,
        sink: AudioSink,
        peaks: Peaks.Recorder,
        config: RecordingConfig,
        durationMs: Long,
        bookmarks: List<Long>,
        partNumber: Int,
    ) {
        runCatching { sink.close() }
        val snapshot = peaks.snapshot()
        Peaks.save(file, snapshot)
        _segments.tryEmit(Result(file, durationMs, snapshot, config, bookmarks, partNumber))
    }

    private fun orderedWindow(window: FloatArray, cursor: Int): FloatArray {
        val out = FloatArray(LEVEL_WINDOW)
        for (i in 0 until LEVEL_WINDOW) {
            out[i] = window[(cursor + i) % LEVEL_WINDOW]
        }
        return out
    }

    private fun attachEffects(record: AudioRecord, config: RecordingConfig): List<Any> {
        val session = record.audioSessionId
        val attached = mutableListOf<Any>()
        if (config.echoCancel && AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(session)?.also { it.enabled = true; attached += it }
        }
        if (config.noiseSuppress && NoiseSuppressor.isAvailable()) {
            NoiseSuppressor.create(session)?.also { it.enabled = true; attached += it }
        }
        if (config.autoGain && AutomaticGainControl.isAvailable()) {
            AutomaticGainControl.create(session)?.also { it.enabled = true; attached += it }
        }
        return attached
    }

    private fun releaseEffects(effects: List<Any>) {
        effects.forEach { effect ->
            runCatching {
                when (effect) {
                    is AcousticEchoCanceler -> effect.release()
                    is NoiseSuppressor -> effect.release()
                    is AutomaticGainControl -> effect.release()
                }
            }
        }
    }

    companion object {
        const val SILENCE_DB = -60f
        const val LEVEL_WINDOW = 96
        private const val TAG = "RecorderEngine"
        private const val CHUNKS_PER_SECOND = 50
        private const val EMIT_EVERY_N_CHUNKS = 2
        private const val CLIP_THRESHOLD = 0.995f
        private const val STOP_TIMEOUT_MS = 8_000L

        /** How long VOX keeps writing after the input drops below the threshold. */
        private const val VOX_HANGOVER_MS = 1_500L
    }
}
