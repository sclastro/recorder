package com.sclastro.recorder.ui.editor

import android.app.Application
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.sclastro.recorder.AppContainer
import com.sclastro.recorder.audio.PeakGenerator
import com.sclastro.recorder.audio.Peaks
import com.sclastro.recorder.audio.EditEngine
import com.sclastro.recorder.audio.TrimEngine
import com.sclastro.recorder.data.MediaProbe
import com.sclastro.recorder.data.Recording
import com.sclastro.recorder.data.RecordingStorage
import com.sclastro.recorder.ui.containerViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class EditorUiState(
    val recording: Recording? = null,
    val peaks: ByteArray? = null,
    val durationMs: Long = 0,
    val startMs: Long = 0,
    val endMs: Long = 0,
    val positionMs: Long = 0,
    val playing: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val savedId: Long? = null,
) {
    /** Whether fades and normalising are available; see [EditEngine]. */
    val sampleEditable: Boolean get() = recording?.let { EditEngine.supports(it.file) } == true

    val selection: ClosedFloatingPointRange<Float>
        get() = if (durationMs <= 0) 0f..1f else {
            (startMs.toFloat() / durationMs).coerceIn(0f, 1f)..(endMs.toFloat() / durationMs).coerceIn(0f, 1f)
        }

    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

@OptIn(UnstableApi::class)
class EditorViewModel(
    private val container: AppContainer,
    application: Application,
) : AndroidViewModel(application) {

    private val player = ExoPlayer.Builder(application).build()
    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()
    private var ticker: Job? = null

    fun load(id: Long) {
        viewModelScope.launch {
            val recording = container.repository.byId(id) ?: return@launch
            val peaks = withContext(Dispatchers.IO) {
                Peaks.load(recording.file) ?: PeakGenerator.generate(recording.file)?.also {
                    Peaks.save(recording.file, it)
                }
            }
            val duration = recording.durationMs.takeIf { it > 0 }
                ?: withContext(Dispatchers.IO) { MediaProbe.probe(recording.file).durationMs }
            _state.value = EditorUiState(
                recording = recording,
                peaks = peaks,
                durationMs = duration,
                startMs = 0,
                endMs = duration,
            )
            player.setMediaItem(MediaItem.fromUri(recording.file.toURI().toString()))
            player.prepare()
            startTicker()
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (isActive) {
                val current = _state.value
                if (player.isPlaying) {
                    val position = player.currentPosition
                    // Playback preview stays inside the selection.
                    if (position >= current.endMs) {
                        player.pause()
                        player.seekTo(current.startMs)
                        _state.value = current.copy(positionMs = current.startMs, playing = false)
                    } else {
                        _state.value = current.copy(positionMs = position, playing = true)
                    }
                } else if (current.playing) {
                    _state.value = current.copy(playing = false)
                }
                delay(60)
            }
        }
    }

    fun setSelection(range: ClosedFloatingPointRange<Float>) {
        val duration = _state.value.durationMs
        if (duration <= 0) return
        _state.value = _state.value.copy(
            startMs = (range.start * duration).toLong().coerceIn(0, duration),
            endMs = (range.endInclusive * duration).toLong().coerceIn(0, duration),
        )
    }

    fun nudgeStart(deltaMs: Long) = moveStart(_state.value.startMs + deltaMs)

    fun nudgeEnd(deltaMs: Long) = moveEnd(_state.value.endMs + deltaMs)

    fun setStartHere() = moveStart(_state.value.positionMs)

    fun setEndHere() = moveEnd(_state.value.positionMs)

    /** Clamped so the two handles can never cross, whatever the clip length. */
    private fun moveStart(target: Long) {
        val s = _state.value
        val highest = (s.endMs - MIN_SELECTION_MS).coerceAtLeast(0)
        _state.value = s.copy(startMs = target.coerceIn(0, highest))
    }

    private fun moveEnd(target: Long) {
        val s = _state.value
        val lowest = (s.startMs + MIN_SELECTION_MS).coerceAtMost(s.durationMs)
        _state.value = s.copy(endMs = target.coerceIn(lowest, s.durationMs.coerceAtLeast(lowest)))
    }

    fun previewSelection() {
        val s = _state.value
        if (player.isPlaying) {
            player.pause()
        } else {
            player.seekTo(s.startMs)
            player.play()
        }
    }

    fun seekToFraction(fraction: Float) {
        val duration = _state.value.durationMs
        if (duration <= 0) return
        val target = (duration * fraction).toLong().coerceIn(0, duration)
        player.seekTo(target)
        _state.value = _state.value.copy(positionMs = target)
    }

    /** Writes the selection out as a new file, leaving the original untouched. */
    fun saveTrimmed(name: String) {
        val current = _state.value
        val recording = current.recording ?: return
        if (current.busy) return
        _state.value = current.copy(busy = true, message = null)

        viewModelScope.launch {
            val destination = withContext(Dispatchers.IO) {
                container.storage.uniqueFile(
                    container.storage.folderDir(recording.folder),
                    RecordingStorage.sanitiseName(name),
                    recording.file.extension,
                )
            }
            when (val outcome = trimTo(destination, current)) {
                is TrimEngine.Outcome.Success -> {
                    val probe = withContext(Dispatchers.IO) { MediaProbe.probe(outcome.file) }
                    val saved = container.repository.registerFile(
                        file = outcome.file,
                        folder = recording.folder,
                        durationMs = outcome.durationMs.takeIf { it > 0 } ?: probe.durationMs,
                        sampleRate = probe.sampleRate.takeIf { it > 0 } ?: recording.sampleRate,
                        bitDepth = probe.bitDepth.takeIf { it > 0 } ?: recording.bitDepth,
                        channels = probe.channels.takeIf { it > 0 } ?: recording.channels,
                        format = recording.format,
                    )
                    _state.value = _state.value.copy(
                        busy = false,
                        message = "Saved as a new file",
                        savedId = saved?.id,
                    )
                }
                is TrimEngine.Outcome.Failure -> {
                    _state.value = _state.value.copy(busy = false, message = outcome.message)
                }
            }
        }
    }

    /**
     * Overwrites the recording with the selection, keeping its name, folder,
     * note and favourite. Trimming top and tail is usually meant as a fix to
     * the file rather than a derivative of it, and the alternative was a second
     * copy every time. Destructive, so the screen confirms before calling.
     */
    fun replaceOriginal() {
        val current = _state.value
        val recording = current.recording ?: return
        if (current.busy) return
        _state.value = current.copy(busy = true, message = null)

        viewModelScope.launch {
            // Written somewhere else first: the trim reads the file it is about
            // to become, so the swap can only happen once the write is done.
            val staging = withContext(Dispatchers.IO) {
                container.storage.pendingDir.mkdirs()
                container.storage.uniqueFile(
                    container.storage.pendingDir,
                    "replace_${recording.id}",
                    recording.file.extension,
                )
            }
            when (val outcome = trimTo(staging, current)) {
                is TrimEngine.Outcome.Success -> {
                    val probe = withContext(Dispatchers.IO) { MediaProbe.probe(outcome.file) }
                    val duration = outcome.durationMs.takeIf { it > 0 } ?: probe.durationMs
                    // Bookmarks are absolute positions in the old file; the ones
                    // inside the kept range shift back, the rest are gone.
                    val bookmarks = recording.bookmarks
                        .filter { it in current.startMs..current.endMs }
                        .map { it - current.startMs }
                    val replaced = container.repository.replaceFile(
                        id = recording.id,
                        newFile = outcome.file,
                        durationMs = duration,
                        bookmarks = bookmarks,
                    )
                    if (replaced) {
                        // Reload so the waveform and handles describe the file
                        // that now exists rather than the one that used to.
                        load(recording.id)
                        _state.value = _state.value.copy(busy = false, message = "Original replaced")
                    } else {
                        withContext(Dispatchers.IO) { outcome.file.delete() }
                        _state.value = _state.value.copy(
                            busy = false,
                            message = "Could not replace the original — it is unchanged",
                        )
                    }
                }
                is TrimEngine.Outcome.Failure -> {
                    withContext(Dispatchers.IO) { staging.delete() }
                    _state.value = _state.value.copy(busy = false, message = outcome.message)
                }
            }
        }
    }

    /**
     * Cuts the recording in two at the playhead and files both halves. Both
     * are trims, so this is lossless whatever the format; the original stays
     * put so nothing is lost if only one half turns out to be wanted.
     */
    fun splitHere() {
        val current = _state.value
        val recording = current.recording ?: return
        if (current.busy) return
        val at = current.positionMs
        if (at < MIN_PART_MS || at > current.durationMs - MIN_PART_MS) {
            _state.value = current.copy(message = "Move the playhead further from the ends first")
            return
        }
        _state.value = current.copy(busy = true, message = null)

        viewModelScope.launch {
            val halves = listOf(0L to at, at to current.durationMs)
            var saved = 0
            var failure: String? = null

            halves.forEachIndexed { index, (from, to) ->
                if (failure != null) return@forEachIndexed
                val destination = withContext(Dispatchers.IO) {
                    container.storage.uniqueFile(
                        container.storage.folderDir(recording.folder),
                        RecordingStorage.sanitiseName("${recording.displayName}_${index + 1}"),
                        recording.file.extension,
                    )
                }
                when (val outcome = withContext(Dispatchers.IO) {
                    TrimEngine.trim(recording.file, destination, from, to)
                }) {
                    is TrimEngine.Outcome.Success -> {
                        register(outcome, recording)
                        saved++
                    }
                    is TrimEngine.Outcome.Failure -> failure = outcome.message
                }
            }

            _state.value = _state.value.copy(
                busy = false,
                message = failure ?: "Split into $saved files",
            )
        }
    }

    /** Ramps the first and last few seconds; WAV only, see [EditEngine]. */
    fun applyFade(fadeInMs: Long, fadeOutMs: Long) = runEdit { source, destination ->
        EditEngine.fade(source, destination, fadeInMs, fadeOutMs)
    }

    /** Lifts the whole file to just under clipping; WAV only, see [EditEngine]. */
    fun applyNormalize() = runEdit { source, destination ->
        EditEngine.normalize(source, destination)
    }

    private fun runEdit(edit: (File, File) -> EditEngine.Outcome) {
        val current = _state.value
        val recording = current.recording ?: return
        if (current.busy) return
        if (!EditEngine.supports(recording.file)) {
            _state.value = current.copy(
                message = "Fades and normalising need a WAV recording",
            )
            return
        }
        _state.value = current.copy(busy = true, message = null)

        viewModelScope.launch {
            val destination = withContext(Dispatchers.IO) {
                container.storage.uniqueFile(
                    container.storage.folderDir(recording.folder),
                    RecordingStorage.sanitiseName("${recording.displayName}_edited"),
                    recording.file.extension,
                )
            }
            when (val outcome = withContext(Dispatchers.IO) { edit(recording.file, destination) }) {
                is EditEngine.Outcome.Success -> {
                    val saved = registerEdited(outcome.file, outcome.durationMs, recording)
                    _state.value = _state.value.copy(
                        busy = false,
                        message = "Saved as a new file",
                        savedId = saved?.id,
                    )
                }
                is EditEngine.Outcome.Failure -> {
                    _state.value = _state.value.copy(busy = false, message = outcome.message)
                }
            }
        }
    }

    private suspend fun register(outcome: TrimEngine.Outcome.Success, from: Recording) =
        registerEdited(outcome.file, outcome.durationMs, from)

    private suspend fun registerEdited(file: File, durationMs: Long, from: Recording): Recording? {
        val probe = withContext(Dispatchers.IO) { MediaProbe.probe(file) }
        return container.repository.registerFile(
            file = file,
            folder = from.folder,
            durationMs = durationMs.takeIf { it > 0 } ?: probe.durationMs,
            sampleRate = probe.sampleRate.takeIf { it > 0 } ?: from.sampleRate,
            bitDepth = probe.bitDepth.takeIf { it > 0 } ?: from.bitDepth,
            channels = probe.channels.takeIf { it > 0 } ?: from.channels,
            format = from.format,
        )
    }

    private suspend fun trimTo(destination: File, from: EditorUiState): TrimEngine.Outcome {
        val recording = from.recording ?: return TrimEngine.Outcome.Failure("Nothing loaded")
        return withContext(Dispatchers.IO) {
            TrimEngine.trim(recording.file, destination, from.startMs, from.endMs)
        }
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null)
    }

    override fun onCleared() {
        ticker?.cancel()
        player.release()
        super.onCleared()
    }

    companion object {
        private const val MIN_SELECTION_MS = 100L

        /** A split has to leave something worth keeping on both sides. */
        private const val MIN_PART_MS = 1_000L

        val Factory = containerViewModelFactory { container, app -> EditorViewModel(container, app) }
    }
}
