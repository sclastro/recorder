package com.sclastro.recorder.ui.player

import android.app.Application
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.sclastro.recorder.AppContainer
import com.sclastro.recorder.audio.PeakGenerator
import com.sclastro.recorder.audio.Peaks
import com.sclastro.recorder.data.Recording
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

data class PlayerUiState(
    val recording: Recording? = null,
    val peaks: ByteArray? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val skipSilence: Boolean = false,
) {
    val progress: Float
        get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

@OptIn(UnstableApi::class)
class PlayerViewModel(
    private val container: AppContainer,
    application: Application,
) : AndroidViewModel(application) {

    private val player: ExoPlayer = ExoPlayer.Builder(application).build().apply {
        addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.value = _state.value.copy(playing = isPlaying)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    _state.value = _state.value.copy(durationMs = duration.coerceAtLeast(0))
                }
            }
        })
    }

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var ticker: Job? = null
    private var noteJob: Job? = null
    private var pendingNote: String? = null

    fun load(id: Long) {
        viewModelScope.launch {
            val recording = container.repository.byId(id) ?: return@launch
            val peaks = withContext(Dispatchers.IO) {
                Peaks.load(recording.file) ?: PeakGenerator.generate(recording.file)?.also {
                    Peaks.save(recording.file, it)
                }
            }
            _state.value = _state.value.copy(
                recording = recording,
                peaks = peaks,
                durationMs = recording.durationMs,
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
                if (player.isPlaying) {
                    _state.value = _state.value.copy(
                        positionMs = player.currentPosition.coerceAtLeast(0),
                        durationMs = player.duration.takeIf { it > 0 } ?: _state.value.durationMs,
                    )
                }
                delay(60)
            }
        }
    }

    fun togglePlay() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun seekToFraction(fraction: Float) {
        val duration = _state.value.durationMs
        if (duration > 0) {
            val target = (duration * fraction).toLong().coerceIn(0, duration)
            player.seekTo(target)
            _state.value = _state.value.copy(positionMs = target)
        }
    }

    fun skip(deltaMs: Long) {
        val duration = _state.value.durationMs
        val target = (player.currentPosition + deltaMs).coerceIn(0, duration.coerceAtLeast(0))
        player.seekTo(target)
        _state.value = _state.value.copy(positionMs = target)
    }

    fun setSpeed(speed: Float) {
        player.playbackParameters = PlaybackParameters(speed)
        _state.value = _state.value.copy(speed = speed)
    }

    fun toggleSkipSilence() {
        val next = !_state.value.skipSilence
        player.skipSilenceEnabled = next
        _state.value = _state.value.copy(skipSilence = next)
    }

    fun jumpToBookmark(positionMs: Long) {
        player.seekTo(positionMs)
        _state.value = _state.value.copy(positionMs = positionMs)
    }

    /**
     * Persisting on every keystroke meant a database write per character. The
     * text lives in state immediately; the write lands once typing pauses, and
     * is flushed on an application-scoped coroutine if the screen goes away
     * first.
     */
    fun setNote(note: String) {
        val recording = _state.value.recording ?: return
        _state.value = _state.value.copy(recording = recording.copy(note = note))
        pendingNote = note
        noteJob?.cancel()
        noteJob = viewModelScope.launch {
            delay(NOTE_WRITE_DELAY_MS)
            container.repository.setNote(recording.id, note)
            pendingNote = null
        }
    }

    private fun flushNote() {
        val recording = _state.value.recording ?: return
        val note = pendingNote ?: return
        pendingNote = null
        container.appScope.launch { container.repository.setNote(recording.id, note) }
    }

    override fun onCleared() {
        ticker?.cancel()
        noteJob?.cancel()
        flushNote()
        player.release()
        super.onCleared()
    }

    companion object {
        private const val NOTE_WRITE_DELAY_MS = 600L

        val Factory = containerViewModelFactory { container, app -> PlayerViewModel(container, app) }
    }
}
