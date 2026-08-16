package com.sclastro.recorder

import android.content.Context
import com.sclastro.recorder.audio.RecorderEngine
import com.sclastro.recorder.audio.RecordingConfig
import com.sclastro.recorder.data.Recording
import com.sclastro.recorder.data.RecordingRepository
import com.sclastro.recorder.data.RecordingStorage
import com.sclastro.recorder.data.db.RecorderDatabase
import com.sclastro.recorder.data.prefs.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * Hand-rolled dependency graph. The app is small enough that a DI framework
 * would cost more in build time and indirection than it saves.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val database: RecorderDatabase = RecorderDatabase.build(appContext)
    val storage = RecordingStorage(appContext)
    val repository = RecordingRepository(database.recordingDao(), database.folderDao(), storage)
    val settings = SettingsStore(appContext)
    val engine = RecorderEngine()

    /** Handed from the UI to the foreground service when a capture starts. */
    data class StartRequest(
        val config: RecordingConfig,
        val pendingFile: File,
        val folder: String,
        val name: String,
        val policy: RecorderEngine.Policy = RecorderEngine.Policy(),
    )

    /** Set by the UI, consumed by the service when it comes up. */
    @Volatile var startRequest: StartRequest? = null

    /** Retained for the lifetime of the capture so the service knows where to file it. */
    @Volatile var activeRequest: StartRequest? = null

    /** Outlives any single screen or service, for work that must not be cancelled. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The recording that was just saved, so the UI can offer to rename it. */
    val justSaved = MutableStateFlow<Recording?>(null)
}
