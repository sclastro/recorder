package com.sclastro.recorder.service

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.SessionCommand

/**
 * Two playback settings live on ExoPlayer rather than on the Player interface,
 * so a [androidx.media3.session.MediaController] cannot reach them: skipping
 * silence, and gain above unity. Moving playback into a session dropped both.
 * These commands carry them across the boundary instead.
 */
@OptIn(UnstableApi::class)
object PlaybackCommands {

    const val ACTION_SKIP_SILENCE = "com.sclastro.recorder.SKIP_SILENCE"
    const val ACTION_SET_GAIN = "com.sclastro.recorder.SET_GAIN"

    const val EXTRA_ENABLED = "enabled"
    const val EXTRA_GAIN_DB = "gain_db"

    /** Above this the enhancer is on; the UI offers 0 to [MAX_GAIN_DB]. */
    const val MAX_GAIN_DB = 20

    val skipSilence = SessionCommand(ACTION_SKIP_SILENCE, Bundle.EMPTY)
    val setGain = SessionCommand(ACTION_SET_GAIN, Bundle.EMPTY)

    fun skipSilenceArgs(enabled: Boolean) = Bundle().apply { putBoolean(EXTRA_ENABLED, enabled) }

    fun gainArgs(db: Int) = Bundle().apply { putInt(EXTRA_GAIN_DB, db) }

    /**
     * The same two values published back on the session, so a controller that
     * connects later can show what is actually in effect.
     *
     * They belong to the session, not to a screen: opening a second recording
     * builds a fresh ViewModel whose state starts at zero, and without this the
     * sheet said "Off" while the audio was still boosted by the last one.
     */
    fun stateBundle(skipSilence: Boolean, gainDb: Int) = Bundle().apply {
        putBoolean(EXTRA_ENABLED, skipSilence)
        putInt(EXTRA_GAIN_DB, gainDb)
    }

    fun skipSilenceFrom(extras: Bundle) = extras.getBoolean(EXTRA_ENABLED, false)

    fun gainFrom(extras: Bundle) = extras.getInt(EXTRA_GAIN_DB, 0)
}
