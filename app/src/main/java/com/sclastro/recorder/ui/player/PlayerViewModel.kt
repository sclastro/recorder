package com.sclastro.recorder.ui.player

import android.app.Application
import android.content.ComponentName
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.sclastro.recorder.AppContainer
import com.sclastro.recorder.audio.PeakGenerator
import com.sclastro.recorder.audio.Peaks
import com.sclastro.recorder.data.Recording
import com.sclastro.recorder.service.PlaybackService
import com.sclastro.recorder.ui.containerViewModelFactory
import com.google.common.util.concurrent.ListenableFuture
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
    val loadingPeaks: Boolean = false,
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

/**
 * Drives playback through a [MediaController] bound to [PlaybackService], so
 * audio keeps going once this screen is gone and the system transport controls
 * work.
 */
@OptIn(UnstableApi::class)
class PlayerViewModel(
    private val container: AppContainer,
    application: Application,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    /** Set when load() runs before the controller has finished connecting. */
    private var pendingRecording: Recording? = null

    private var ticker: Job? = null
    private var noteJob: Job? = null
    private var pendingNote: String? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.value = _state.value.copy(playing = isPlaying)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                val duration = controller?.duration ?: 0
                if (duration > 0) _state.value = _state.value.copy(durationMs = duration)
            }
        }
    }

    init {
        val token = SessionToken(application, ComponentName(application, PlaybackService::class.java))
        val future = MediaController.Builder(application, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                val ready = runCatching { future.get() }.getOrNull() ?: return@addListener
                controller = ready
                ready.addListener(listener)
                _state.value = _state.value.copy(
                    playing = ready.isPlaying,
                    speed = ready.playbackParameters.speed,
                )
                pendingRecording?.let { attach(it) }
                pendingRecording = null
                startTicker()
            },
            ContextCompat.getMainExecutor(application),
        )
    }

    fun load(id: Long) {
        viewModelScope.launch {
            val recording = container.repository.byId(id) ?: return@launch
            _state.value = _state.value.copy(
                recording = recording,
                durationMs = recording.durationMs,
                peaks = null,
                loadingPeaks = true,
            )

            val current = controller
            if (current == null) {
                pendingRecording = recording
            } else {
                attach(recording)
            }

            // Decoding a long file to build a waveform takes a moment; the
            // transport is usable while this runs.
            val peaks = withContext(Dispatchers.IO) {
                Peaks.load(recording.file) ?: PeakGenerator.generate(recording.file)?.also {
                    Peaks.save(recording.file, it)
                }
            }
            _state.value = _state.value.copy(peaks = peaks, loadingPeaks = false)
        }
    }

    /** Points the session at this recording, unless it is already the one loaded. */
    private fun attach(recording: Recording) {
        val current = controller ?: return
        val alreadyLoaded = current.currentMediaItem?.mediaId == recording.id.toString()
        if (alreadyLoaded) {
            _state.value = _state.value.copy(
                positionMs = current.currentPosition.coerceAtLeast(0),
                durationMs = current.duration.takeIf { it > 0 } ?: recording.durationMs,
                playing = current.isPlaying,
            )
            return
        }
        current.setMediaItem(
            MediaItem.Builder()
                .setMediaId(recording.id.toString())
                .setUri(recording.file.toURI().toString())
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(recording.displayName)
                        .setArtist(if (recording.folder.isEmpty()) "Unsorted" else recording.folder)
                        .build(),
                )
                .build(),
        )
        current.prepare()
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (isActive) {
                controller?.let { player ->
                    if (player.isPlaying) {
                        _state.value = _state.value.copy(
                            positionMs = player.currentPosition.coerceAtLeast(0),
                            durationMs = player.duration.takeIf { it > 0 } ?: _state.value.durationMs,
                        )
                    }
                }
                delay(60)
            }
        }
    }

    fun togglePlay() {
        val player = controller ?: return
        if (player.isPlaying) player.pause() else player.play()
    }

    fun seekToFraction(fraction: Float) {
        val player = controller ?: return
        val duration = _state.value.durationMs
        if (duration > 0) {
            val target = (duration * fraction).toLong().coerceIn(0, duration)
            player.seekTo(target)
            _state.value = _state.value.copy(positionMs = target)
        }
    }

    fun skip(deltaMs: Long) {
        val player = controller ?: return
        val duration = _state.value.durationMs
        val target = (player.currentPosition + deltaMs).coerceIn(0, duration.coerceAtLeast(0))
        player.seekTo(target)
        _state.value = _state.value.copy(positionMs = target)
    }

    fun setSpeed(speed: Float) {
        controller?.playbackParameters = PlaybackParameters(speed)
        _state.value = _state.value.copy(speed = speed)
    }

    fun toggleSkipSilence() {
        // Only an ExoPlayer knows this one; through a controller it is a no-op,
        // so keep the flag purely visual until the session exposes a command.
        val next = !_state.value.skipSilence
        _state.value = _state.value.copy(skipSilence = next)
    }

    fun jumpToBookmark(positionMs: Long) {
        controller?.seekTo(positionMs)
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
        // Release the controller, not the player: the session keeps playing.
        controller?.removeListener(listener)
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onCleared()
    }

    companion object {
        private const val NOTE_WRITE_DELAY_MS = 600L

        val Factory = containerViewModelFactory { container, app -> PlayerViewModel(container, app) }
    }
}
