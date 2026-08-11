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

    fun nudgeStart(deltaMs: Long) {
        val s = _state.value
        _state.value = s.copy(startMs = (s.startMs + deltaMs).coerceIn(0, s.endMs - 100))
    }

    fun nudgeEnd(deltaMs: Long) {
        val s = _state.value
        _state.value = s.copy(endMs = (s.endMs + deltaMs).coerceIn(s.startMs + 100, s.durationMs))
    }

    fun setStartHere() {
        val s = _state.value
        _state.value = s.copy(startMs = s.positionMs.coerceIn(0, s.endMs - 100))
    }

    fun setEndHere() {
        val s = _state.value
        _state.value = s.copy(endMs = s.positionMs.coerceIn(s.startMs + 100, s.durationMs))
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
            val outcome = withContext(Dispatchers.IO) {
                val safe = RecordingStorage.sanitiseName(name)
                val destination = container.storage.uniqueFile(
                    container.storage.folderDir(recording.folder),
                    safe,
                    recording.file.extension,
                )
                TrimEngine.trim(recording.file, destination, current.startMs, current.endMs)
            }
            when (outcome) {
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

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null)
    }

    override fun onCleared() {
        ticker?.cancel()
        player.release()
        super.onCleared()
    }

    companion object {
        val Factory = containerViewModelFactory { container, app -> EditorViewModel(container, app) }
    }
}
