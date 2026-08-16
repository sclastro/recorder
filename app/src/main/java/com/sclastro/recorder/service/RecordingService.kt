package com.sclastro.recorder.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.sclastro.recorder.MainActivity
import com.sclastro.recorder.R
import com.sclastro.recorder.audio.RecorderEngine
import com.sclastro.recorder.container
import com.sclastro.recorder.util.formatDuration
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

/**
 * Keeps capture alive when the app is backgrounded or the screen is off, which
 * is the whole reason recording lives in a service rather than a ViewModel.
 */
class RecordingService : LifecycleService() {

    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Set when a phone call paused us, so the resume is automatic and we never
     * un-pause a recording the user paused by hand.
     *
     * Audio mode is the permission-free way to notice a call — TelephonyManager
     * would need READ_PHONE_STATE, which is a lot to ask of a voice recorder.
     */
    private var pausedByCall = false
    private var modeListener: AudioManager.OnModeChangedListener? = null

    /**
     * Watches engine state to refresh the notification. Started only once
     * capture is actually running: [lifecycleScope] dispatches on
     * `Main.immediate`, so a collector launched in `onCreate` would run
     * synchronously, see the still-idle engine and call `stopSelf()` before
     * `onStartCommand` ever got a chance to begin recording.
     */
    private var notificationJob: Job? = null

    /** Collects files closed by auto-split so each one is filed as it lands. */
    private var segmentJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val engine = container.engine
        val action = intent?.action

        if (action == ACTION_START) {
            beginCapture()
            return START_NOT_STICKY
        }

        // Every other action only makes sense mid-capture. If the process was
        // restarted the engine is idle and there is nothing left to control —
        // bail out rather than sit here having been started but not foregrounded.
        if (!engine.state.value.isActive) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Idempotent, and covers the case where this is a fresh service
        // instance reached through a notification action.
        startForegroundNow()

        when (action) {
            ACTION_PAUSE -> {
                pausedByCall = false
                engine.pause()
            }
            ACTION_RESUME -> {
                pausedByCall = false
                engine.resume()
            }
            ACTION_STOP -> stopAndSave()
            ACTION_DISCARD -> discard()
        }
        watchEngineState()
        return START_NOT_STICKY
    }

    private fun beginCapture() {
        val engine = container.engine
        val request = container.startRequest
        if (request == null) {
            stopSelf()
            return
        }
        if (!hasMicrophonePermission()) {
            // startForeground with the microphone type throws without it.
            engine.reportError("Microphone permission is required")
            stopSelf()
            return
        }

        container.startRequest = null
        container.activeRequest = request

        if (!startForegroundNow()) {
            engine.reportError("Could not start the recording service")
            stopSelf()
            return
        }

        val error = try {
            engine.start(
                rawConfig = request.config,
                pendingFile = request.pendingFile,
                policy = request.policy,
                nextFile = { part ->
                    File(
                        container.storage.pendingDir,
                        "cap_${System.currentTimeMillis()}_p$part.${request.config.container.ext}",
                    )
                },
            )
        } catch (t: Throwable) {
            Log.e(TAG, "capture failed to start", t)
            engine.reportError(t.message ?: "Could not start recording")
            "start failed"
        }

        if (error != null) {
            stopSelf()
        } else {
            acquireWakeLock()
            watchEngineState()
            watchCallState()
            watchSegments()
        }
    }

    /**
     * Auto-split hands over each finished file while capture carries on, so
     * they have to be filed as they arrive rather than at the end. Numbered
     * suffixes keep the parts in order and out of each other's way.
     */
    private fun watchSegments() {
        if (segmentJob?.isActive == true) return
        segmentJob = lifecycleScope.launch {
            container.engine.segments.collect { segment ->
                val request = container.activeRequest ?: return@collect
                val name = "${request.name.ifBlank { "Recording" }}_part${segment.partNumber}"
                // appScope, not this one: the service can go away between the
                // last split and the commit finishing.
                container.appScope.launch {
                    container.repository.commitRecording(segment, request.folder, name)
                }
            }
        }
    }

    private fun watchCallState() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || modeListener != null) return
        val audioManager = getSystemService(AudioManager::class.java) ?: return
        val listener = AudioManager.OnModeChangedListener { mode ->
            val inCall = mode == AudioManager.MODE_IN_CALL ||
                mode == AudioManager.MODE_IN_COMMUNICATION ||
                mode == AudioManager.MODE_RINGTONE
            val engine = container.engine
            when {
                inCall && engine.state.value.status == RecorderEngine.Status.RECORDING -> {
                    pausedByCall = true
                    engine.pause()
                }
                !inCall && pausedByCall -> {
                    pausedByCall = false
                    engine.resume()
                }
            }
        }
        modeListener = listener
        runCatching { audioManager.addOnModeChangedListener(mainExecutor, listener) }
    }

    private fun stopWatchingCallState() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val listener = modeListener ?: return
        modeListener = null
        pausedByCall = false
        runCatching {
            getSystemService(AudioManager::class.java)?.removeOnModeChangedListener(listener)
        }
    }

    private fun watchEngineState() {
        if (notificationJob?.isActive == true) return
        notificationJob = lifecycleScope.launch {
            container.engine.state
                .map { it.status to it.elapsedMs / 1000 }
                .distinctUntilChanged()
                .collect { (status, seconds) ->
                    if (status == RecorderEngine.Status.IDLE) {
                        stopSelf()
                    } else {
                        notify(buildNotification(status, seconds * 1000))
                    }
                }
        }
    }

    private fun stopAndSave() {
        val engine = container.engine
        val request = container.activeRequest
        val folder = request?.folder.orEmpty()
        val name = request?.name.orEmpty().ifBlank { "Recording" }
        // Deliberately not lifecycleScope: stopSelf() below tears the service
        // down, and cancelling mid-commit would strand the file in .pending.
        container.appScope.launch {
            val result = engine.stop()
            if (result != null) {
                val saved = container.repository.commitRecording(result, folder, name)
                container.justSaved.value = saved
            }
            container.activeRequest = null
            stopSelf()
        }
    }

    /** Throws the capture away: the pending file is deleted, nothing is filed. */
    private fun discard() {
        container.appScope.launch {
            container.engine.cancel()
            container.activeRequest = null
            stopSelf()
        }
    }

    override fun onDestroy() {
        notificationJob?.cancel()
        notificationJob = null
        segmentJob?.cancel()
        segmentJob = null
        stopWatchingCallState()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Returns false if the platform refused to promote us to the foreground. */
    private fun startForegroundNow(): Boolean = try {
        val notification = buildNotification(RecorderEngine.Status.RECORDING, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        true
    } catch (t: Throwable) {
        Log.e(TAG, "startForeground refused", t)
        false
    }

    /**
     * Refreshes the ongoing notification. Capture keeps running even when the
     * user has denied notifications — the timer just is not visible.
     */
    private fun notify(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification) }
    }

    private fun buildNotification(status: RecorderEngine.Status, elapsedMs: Long): Notification {
        val paused = status == RecorderEngine.Status.PAUSED
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_mic)
            .setContentTitle(if (paused) "Recording paused" else "Recording")
            .setContentText(formatDuration(elapsedMs))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openApp)
            .addAction(
                0,
                if (paused) "Resume" else "Pause",
                command(if (paused) ACTION_RESUME else ACTION_PAUSE),
            )
            .addAction(0, "Stop", command(ACTION_STOP))
            .build()
    }

    /**
     * Notification actions arrive while the app is in the background, where a
     * plain startService() is not allowed — so they go through
     * startForegroundService(), which an already-foreground service accepts.
     */
    private fun command(action: String): PendingIntent = PendingIntent.getForegroundService(
        this,
        action.hashCode(),
        Intent(this, RecordingService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "recorder:capture").apply {
            setReferenceCounted(false)
            acquire(MAX_RECORDING_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
    }

    companion object {
        const val CHANNEL_ID = "recording"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.sclastro.recorder.START"
        const val ACTION_PAUSE = "com.sclastro.recorder.PAUSE"
        const val ACTION_RESUME = "com.sclastro.recorder.RESUME"
        const val ACTION_STOP = "com.sclastro.recorder.STOP"
        const val ACTION_DISCARD = "com.sclastro.recorder.DISCARD"
        private const val TAG = "RecordingService"
        private const val MAX_RECORDING_MS = 12L * 60 * 60 * 1000

        fun send(context: Context, action: String) {
            val intent = Intent(context, RecordingService::class.java).setAction(action)
            context.startForegroundService(intent)
        }
    }
}
