package com.sclastro.recorder.audio

import android.annotation.SuppressLint
import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    ) {
        val isActive: Boolean get() = status != Status.IDLE
    }

    data class Result(
        val file: File,
        val durationMs: Long,
        val peaks: ByteArray,
        val config: RecordingConfig,
        val bookmarksMs: List<Long>,
    ) {
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Rolling window of recent levels for the live waveform, oldest first. */
    private val _levels = MutableStateFlow(FloatArray(LEVEL_WINDOW))
    val levels: StateFlow<FloatArray> = _levels.asStateFlow()

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
    fun start(rawConfig: RecordingConfig, pendingFile: File): String? = synchronized(lock) {
        if (thread != null) return "已經錄緊音"
        val config = rawConfig.normalised()

        val minBuffer = AudioRecord.getMinBufferSize(
            config.sampleRate,
            config.channels.inMask,
            config.bitDepth.encoding,
        )
        if (minBuffer <= 0) {
            return "呢部機唔支援 ${config.sampleRate}Hz / ${config.bitDepth.label} / ${config.channels.label}"
        }

        val chunkBytes = (config.sampleRate / CHUNKS_PER_SECOND) * config.bytesPerFrame
        val bufferBytes = maxOf(minBuffer * 2, chunkBytes * 8)

        val record = try {
            AudioRecord(config.source.value, config.sampleRate, config.channels.inMask, config.bitDepth.encoding, bufferBytes)
        } catch (e: IllegalArgumentException) {
            return "錄音參數唔啱：${e.message}"
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return "開唔到咪高峰，可能俾其他 App 佔用緊"
        }

        val effects = attachEffects(record, config)

        val sink = try {
            pendingFile.parentFile?.mkdirs()
            when (config.container) {
                AudioContainer.WAV -> WavSink(pendingFile, config.sampleRate, config.channels.count, config.bitDepth)
                else -> EncodedSink(
                    pendingFile,
                    config.sampleRate,
                    config.channels.count,
                    config.bitrateKbps,
                    config.container,
                )
            }
        } catch (e: Exception) {
            releaseEffects(effects)
            record.release()
            return "開唔到編碼器：${e.message}"
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

        thread = Thread({ captureLoop(record, sink, effects, config, pendingFile) }, "recorder-capture").also {
            it.start()
        }
        return null
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

    fun togglePause() {
        if (_state.value.status == Status.PAUSED) resume() else pause()
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

    private fun captureLoop(
        record: AudioRecord,
        sink: AudioSink,
        effects: List<Any>,
        config: RecordingConfig,
        pendingFile: File,
    ) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val chunkFrames = config.sampleRate / CHUNKS_PER_SECOND
        val chunkBytes = chunkFrames * config.bytesPerFrame
        val buffer = ByteArray(chunkBytes)
        val peakTrack = Peaks.Recorder(config.sampleRate, config.bitDepth, config.channels.count)
        val window = FloatArray(LEVEL_WINDOW)
        var windowCursor = 0
        var framesWritten = 0L
        var bytesWritten = 0L
        var emitCounter = 0
        var failure: String? = null

        try {
            record.startRecording()
            while (!stopRequested.get()) {
                val read = record.read(buffer, 0, chunkBytes)
                if (read <= 0) {
                    if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_DEAD_OBJECT) {
                        failure = "錄音中斷（咪高峰被其他 App 搶走）"
                        break
                    }
                    continue
                }
                if (paused.get()) continue

                val (peak, rms) = PcmMath.analyse(buffer, read, config.bitDepth)
                sink.write(buffer, read)
                bytesWritten += read
                framesWritten += read / config.bytesPerFrame
                peakTrack.feed(peak, read)

                window[windowCursor] = PcmMath.dbToFraction(PcmMath.toDb(peak))
                windowCursor = (windowCursor + 1) % LEVEL_WINDOW

                if (++emitCounter >= EMIT_EVERY_N_CHUNKS) {
                    emitCounter = 0
                    _levels.value = orderedWindow(window, windowCursor)
                    val elapsed = framesWritten * 1000 / config.sampleRate
                    _state.value = _state.value.copy(
                        elapsedMs = elapsed,
                        peakDb = PcmMath.toDb(peak),
                        rmsDb = PcmMath.toDb(rms),
                        clipping = peak >= CLIP_THRESHOLD,
                        bytesWritten = bytesWritten,
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "capture failed", e)
            failure = e.message ?: "錄音出錯"
        } finally {
            runCatching { record.stop() }
            record.release()
            releaseEffects(effects)
            runCatching { sink.close() }

            val durationMs = if (config.sampleRate > 0) framesWritten * 1000 / config.sampleRate else 0
            val discard = discardOnStop.get() || framesWritten == 0L
            if (discard) {
                pendingFile.delete()
                result = null
            } else {
                val peaks = peakTrack.snapshot()
                Peaks.save(pendingFile, peaks)
                result = Result(pendingFile, durationMs, peaks, config, _state.value.bookmarksMs)
            }
            synchronized(lock) { thread = null }
            _state.value = State(error = failure)
            _levels.value = FloatArray(LEVEL_WINDOW)
        }
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
    }
}
