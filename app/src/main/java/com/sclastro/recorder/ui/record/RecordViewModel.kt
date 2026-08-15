package com.sclastro.recorder.ui.record

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sclastro.recorder.AppContainer
import com.sclastro.recorder.audio.Preset
import com.sclastro.recorder.audio.RecordingConfig
import com.sclastro.recorder.data.FileNaming
import com.sclastro.recorder.data.FolderInfo
import com.sclastro.recorder.data.Recording
import com.sclastro.recorder.data.prefs.AppSettings
import com.sclastro.recorder.service.RecordingService
import com.sclastro.recorder.ui.containerViewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class RecordViewModel(
    private val container: AppContainer,
    application: Application,
) : AndroidViewModel(application) {

    val engineState = container.engine.state
    val levels = container.engine.levels
    val justSaved: MutableStateFlow<Recording?> = container.justSaved

    val settings: StateFlow<AppSettings> = container.settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val folders: StateFlow<List<FolderInfo>> = container.repository.observeFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Folder the next recording lands in; defaults to the saved preference. */
    private val _targetFolder = MutableStateFlow<String?>(null)
    val targetFolder: StateFlow<String?> = _targetFolder

    fun setTargetFolder(folder: String) {
        _targetFolder.value = folder
    }

    fun dismissError() = container.engine.clearError()

    fun applyPreset(preset: Preset) {
        viewModelScope.launch {
            if (preset != Preset.CUSTOM) {
                container.settings.setConfig(preset.config)
            }
            container.settings.setPresetName(preset.label)
        }
    }

    fun updateConfig(config: RecordingConfig) {
        viewModelScope.launch {
            container.settings.setConfig(config.normalised())
            container.settings.setPresetName(Preset.CUSTOM.label)
        }
    }

    fun toggleRecording(context: Context) {
        if (container.engine.state.value.isActive) {
            RecordingService.send(context, RecordingService.ACTION_STOP)
        } else {
            startRecording(context)
        }
    }

    fun togglePause(context: Context) {
        val paused = container.engine.state.value.status == com.sclastro.recorder.audio.RecorderEngine.Status.PAUSED
        RecordingService.send(
            context,
            if (paused) RecordingService.ACTION_RESUME else RecordingService.ACTION_PAUSE,
        )
    }

    fun addBookmark() = container.engine.addBookmark()

    /** Stops without saving; the partial file is deleted. */
    fun discardRecording(context: Context) {
        if (container.engine.state.value.isActive) {
            RecordingService.send(context, RecordingService.ACTION_DISCARD)
        }
    }

    private fun startRecording(context: Context) {
        viewModelScope.launch {
            val current = settings.value
            val folder = _targetFolder.value ?: current.defaultFolder
            val sequence = container.settings.nextSequence()
            val name = FileNaming.expand(
                template = current.filenameTemplate,
                sequence = sequence,
                preset = current.presetName,
                folder = folder,
            )
            val config = current.config.normalised()
            val pending = File(
                container.storage.pendingDir,
                "cap_${System.currentTimeMillis()}.${config.container.ext}",
            )
            container.startRequest = AppContainer.StartRequest(config, pending, folder, name)
            RecordingService.send(context, RecordingService.ACTION_START)
        }
    }

    // ---- Post-save naming ---------------------------------------------------

    fun renameJustSaved(newName: String, folder: String) {
        val saved = justSaved.value ?: return
        viewModelScope.launch {
            if (newName.isNotBlank() && newName != saved.displayName) {
                container.repository.rename(saved.id, newName)
            }
            if (folder != saved.folder) {
                container.repository.moveToFolder(saved.id, folder)
            }
            justSaved.value = null
        }
    }

    fun dismissJustSaved() {
        justSaved.value = null
    }

    fun createFolder(name: String) {
        viewModelScope.launch { container.repository.createFolder(name) }
    }

    companion object {
        val Factory = containerViewModelFactory { container, app -> RecordViewModel(container, app) }
    }
}
