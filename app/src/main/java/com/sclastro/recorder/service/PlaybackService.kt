package com.sclastro.recorder.service

import android.content.Intent
import android.media.audiofx.LoudnessEnhancer
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Owns the playback engine so listening survives leaving the screen.
 *
 * Previously the player lived in the player ViewModel, which meant navigating
 * back released it mid-sentence and there was nothing for the notification
 * shade, lock screen, headset buttons or a car head unit to talk to. Media3
 * builds all of that from the session.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null

    /**
     * Gain above unity, which `Player.setVolume` cannot do — it only attenuates.
     * The enhancer is bound to an audio session id, so it is rebuilt whenever
     * ExoPlayer hands out a new one.
     */
    private var enhancer: LoudnessEnhancer? = null
    private var gainDb = 0

    private val audioSessionListener = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            attachEnhancer(audioSessionId)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            // Pause instead of blaring out of the speaker when headphones are pulled.
            .setHandleAudioBecomingNoisy(true)
            .build()
        exo.addListener(audioSessionListener)
        player = exo
        mediaSession = MediaSession.Builder(this, exo)
            .setCallback(SessionCallback())
            .build()
        attachEnhancer(exo.audioSessionId)
    }

    private inner class SessionCallback : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val available = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(PlaybackCommands.skipSilence)
                .add(PlaybackCommands.setGain)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(available)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                PlaybackCommands.ACTION_SKIP_SILENCE -> {
                    player?.skipSilenceEnabled = args.getBoolean(PlaybackCommands.EXTRA_ENABLED)
                }
                PlaybackCommands.ACTION_SET_GAIN -> {
                    gainDb = args.getInt(PlaybackCommands.EXTRA_GAIN_DB)
                        .coerceIn(0, PlaybackCommands.MAX_GAIN_DB)
                    applyGain()
                }
                else -> return Futures.immediateFuture(
                    SessionResult(SessionError.ERROR_NOT_SUPPORTED),
                )
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    private fun attachEnhancer(audioSessionId: Int) {
        releaseEnhancer()
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET) return
        enhancer = runCatching { LoudnessEnhancer(audioSessionId) }.getOrNull()
        applyGain()
    }

    private fun applyGain() {
        val effect = enhancer ?: return
        runCatching {
            effect.setTargetGain(gainDb * 100)
            effect.enabled = gainDb > 0
        }
    }

    private fun releaseEnhancer() {
        enhancer?.let { runCatching { it.release() } }
        enhancer = null
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away should not strand a paused session in the shade.
        val current = mediaSession?.player
        if (current == null || !current.playWhenReady || current.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        releaseEnhancer()
        player?.removeListener(audioSessionListener)
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        player = null
        super.onDestroy()
    }
}
