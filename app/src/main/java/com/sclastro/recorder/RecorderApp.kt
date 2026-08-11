package com.sclastro.recorder

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.sclastro.recorder.service.RecordingService

class RecorderApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        createNotificationChannel()

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            // A file left in .pending means the process died mid-recording.
            container.repository.recoverPending()
            container.repository.reconcile()
            container.repository.purgeExpiredTrash(container.settings.settings.first().trashRetentionDays)
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            RecordingService.CHANNEL_ID,
            "Recording",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Ongoing recording and its controls"
            setShowBadge(false)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }
}

val Context.container: AppContainer
    get() = (applicationContext as RecorderApp).container
