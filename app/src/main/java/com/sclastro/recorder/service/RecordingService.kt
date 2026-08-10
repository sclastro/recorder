package com.sclastro.recorder.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.sclastro.recorder.MainActivity
import com.sclastro.recorder.R
import com.sclastro.recorder.audio.RecorderEngine
import com.sclastro.recorder.container
import com.sclastro.recorder.util.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps capture alive when the app is backgrounded or the screen is off, which
 * is the whole reason recording lives in a service rather than a ViewModel.
 */
class RecordingService : LifecycleService() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val engine = container.engine

        // Mirror elapsed time into the notification, once per second.
        lifecycleScope.launch {
            engine.state
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val engine = container.engine

        when (intent?.action) {
            ACTION_START -> {
                startForegroundNow()
                val request = container.startRequest
                if (request == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                container.startRequest = null
                container.activeRequest = request
                val error = engine.start(request.config, request.pendingFile)
                if (error != null) {
                    stopSelf()
                } else {
                    acquireWakeLock()
                }
            }
            ACTION_PAUSE -> engine.pause()
            ACTION_RESUME -> engine.resume()
            ACTION_STOP -> stopAndSave()
            else -> if (!engine.state.value.isActive) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun stopAndSave() {
        val engine = container.engine
        val request = container.activeRequest
        val folder = request?.folder.orEmpty()
        val name = request?.name.orEmpty().ifBlank { "錄音" }
        lifecycleScope.launch(Dispatchers.IO) {
            val result = engine.stop()
            if (result != null) {
                val saved = container.repository.commitRecording(result, folder, name)
                container.justSaved.value = saved
            }
            container.activeRequest = null
            stopSelf()
        }
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun startForegroundNow() {
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
    }

    private fun notify(notification: Notification) {
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        }
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
            .setContentTitle(if (paused) "錄音已暫停" else "錄音中")
            .setContentText(formatDuration(elapsedMs))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openApp)
            .addAction(
                0,
                if (paused) "繼續" else "暫停",
                command(if (paused) ACTION_RESUME else ACTION_PAUSE),
            )
            .addAction(0, "停止", command(ACTION_STOP))
            .build()
    }

    private fun command(action: String): PendingIntent = PendingIntent.getService(
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
        private const val MAX_RECORDING_MS = 12L * 60 * 60 * 1000

        fun send(context: Context, action: String) {
            val intent = Intent(context, RecordingService::class.java).setAction(action)
            if (action == ACTION_START) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
