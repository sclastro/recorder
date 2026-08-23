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
import com.sclastro.recorder.service.PlaybackCommands
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
    /** Minutes until playback pauses itself, or null when no timer is set. */
    val sleepTimerMinutes: Int? = null,
    val sleepTimerRemainingMs: Long = 0,
    val skipSilence: Boolean = false,
    val gainDb: Int = 0,
    /** Start of an A-B loop; set before [loopEndMs] can be. */
    val loopStartMs: Long? = null,
    val loopEndMs: Long? = null,
) {
    val progress: Float
        get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    /** True once both ends are set and playback is being held between them. */
    val looping: Boolean get() = loopStartMs != null && loopEndMs != null

    fun fractionOf(positionMs: Long): Float =
        if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

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
    private var sleepJob: Job? = null
    private var positionSaveJob: Job? = null

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
                // Speed, boost and skip-silence belong to the session, not to
                // this screen. Opening a second recording builds a fresh
                // ViewModel, and without reading them back the sheet claimed
                // everything was off while the audio was still boosted.
                _state.value = _state.value.copy(
                    playing = ready.isPlaying,
                    speed = ready.playbackParameters.speed,
                    skipSilence = PlaybackCommands.skipSilenceFrom(ready.sessionExtras),
                    gainDb = PlaybackCommands.gainFrom(ready.sessionExtras),
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
        // A loop belongs to the recording it was drawn on.
        _state.value = _state.value.copy(loopStartMs = null, loopEndMs = null)
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

        // Pick up where this recording was left, unless that was effectively
        // the very start or the very end.
        val resume = recording.lastPositionMs
        val duration = recording.durationMs
        if (resume > RESUME_THRESHOLD_MS && (duration <= 0 || resume < duration - RESUME_THRESHOLD_MS)) {
            current.seekTo(resume)
            _state.value = _state.value.copy(positionMs = resume)
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (isActive) {
                controller?.let { player ->
                    if (player.isPlaying) {
                        val position = player.currentPosition.coerceAtLeast(0)
                        val current = _state.value

                        // A-B repeat is enforced here rather than by the player:
                        // ExoPlayer can loop a whole item but not a range of one.
                        val loopEnd = current.loopEndMs
                        val loopStart = current.loopStartMs
                        if (loopEnd != null && loopStart != null && position >= loopEnd) {
                            player.seekTo(loopStart)
                            _state.value = current.copy(positionMs = loopStart)
                        } else {
                            _state.value = current.copy(
                                positionMs = position,
                                durationMs = player.duration.takeIf { it > 0 } ?: current.durationMs,
                            )
                            schedulePositionSave()
                        }
                    }
                }
                delay(60)
            }
        }
    }

    // ---- A-B repeat ---------------------------------------------------------

    /**
     * Cycles through the three states one button can express: set A here, set
     * B here, clear. Nothing is enforced until both ends exist, and B has to be
     * after A or the tap just moves A instead.
     */
    fun cycleLoopPoint() {
        val current = _state.value
        val here = current.positionMs
        _state.value = when {
            current.loopStartMs == null -> current.copy(loopStartMs = here)
            current.loopEndMs == null && here > current.loopStartMs + MIN_LOOP_MS ->
                current.copy(loopEndMs = here)
            current.loopEndMs == null -> current.copy(loopStartMs = here)
            else -> current.copy(loopStartMs = null, loopEndMs = null)
        }
    }

    fun clearLoop() {
        _state.value = _state.value.copy(loopStartMs = null, loopEndMs = null)
    }

    // ---- Session-only settings ----------------------------------------------

    /**
     * Both of these are ExoPlayer properties with no equivalent on the Player
     * interface, so they travel to the service as custom session commands.
     */
    fun setSkipSilence(enabled: Boolean) {
        _state.value = _state.value.copy(skipSilence = enabled)
        controller?.sendCustomCommand(
            PlaybackCommands.skipSilence,
            PlaybackCommands.skipSilenceArgs(enabled),
        )
    }

    fun setGainDb(db: Int) {
        val clamped = db.coerceIn(0, PlaybackCommands.MAX_GAIN_DB)
        _state.value = _state.value.copy(gainDb = clamped)
        controller?.sendCustomCommand(PlaybackCommands.setGain, PlaybackCommands.gainArgs(clamped))
    }

    /** Writes the resume point at most once every few seconds. */
    private fun schedulePositionSave() {
        if (positionSaveJob?.isActive == true) return
        positionSaveJob = viewModelScope.launch {
            delay(POSITION_SAVE_INTERVAL_MS)
            persistPosition()
        }
    }

    private fun persistPosition() {
        val recording = _state.value.recording ?: return
        val position = _state.value.positionMs
        container.appScope.launch { container.repository.setPlaybackPosition(recording.id, position) }
    }

    // ---- Sleep timer --------------------------------------------------------

    fun setSleepTimer(minutes: Int?) {
        sleepJob?.cancel()
        if (minutes == null) {
            _state.value = _state.value.copy(sleepTimerMinutes = null, sleepTimerRemainingMs = 0)
            return
        }
        val totalMs = minutes * 60_000L
        _state.value = _state.value.copy(sleepTimerMinutes = minutes, sleepTimerRemainingMs = totalMs)
        sleepJob = viewModelScope.launch {
            var remaining = totalMs
            while (remaining > 0) {
                delay(1_000)
                remaining -= 1_000
                _state.value = _state.value.copy(sleepTimerRemainingMs = remaining.coerceAtLeast(0))
            }
            controller?.pause()
            _state.value = _state.value.copy(sleepTimerMinutes = null, sleepTimerRemainingMs = 0)
        }
    }

    // ---- Bookmarks ----------------------------------------------------------

    fun addBookmarkHere() {
        val recording = _state.value.recording ?: return
        val at = _state.value.positionMs
        if (recording.bookmarks.any { kotlin.math.abs(it - at) < BOOKMARK_MERGE_MS }) return
        updateBookmarks(recording.bookmarks + at)
    }

    fun removeBookmark(positionMs: Long) {
        val recording = _state.value.recording ?: return
        updateBookmarks(recording.bookmarks - positionMs)
    }

    private fun updateBookmarks(bookmarks: List<Long>) {
        val recording = _state.value.recording ?: return
        val sorted = bookmarks.sorted()
        _state.value = _state.value.copy(recording = recording.copy(bookmarks = sorted))
        viewModelScope.launch { container.repository.setBookmarks(recording.id, sorted) }
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
        sleepJob?.cancel()
        positionSaveJob?.cancel()
        flushNote()
        persistPosition()
        // Release the controller, not the player: the session keeps playing.
        controller?.removeListener(listener)
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onCleared()
    }

    companion object {
        private const val NOTE_WRITE_DELAY_MS = 600L
        private const val POSITION_SAVE_INTERVAL_MS = 5_000L
        private const val RESUME_THRESHOLD_MS = 3_000L
        private const val BOOKMARK_MERGE_MS = 500L
        private const val MIN_LOOP_MS = 500L

        val Factory = containerViewModelFactory { container, app -> PlayerViewModel(container, app) }
    }
}
