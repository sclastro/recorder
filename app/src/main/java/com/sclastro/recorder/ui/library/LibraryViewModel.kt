package com.sclastro.recorder.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sclastro.recorder.AppContainer
import com.sclastro.recorder.audio.Peaks
import com.sclastro.recorder.data.FolderInfo
import com.sclastro.recorder.data.Recording
import com.sclastro.recorder.ui.containerViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SortOrder(val label: String) {
    NEWEST("Newest"),
    OLDEST("Oldest"),
    LONGEST("Longest"),
    NAME("Name"),
}

data class LibraryUiState(
    val recordings: List<Recording> = emptyList(),
    val folders: List<FolderInfo> = emptyList(),
    val query: String = "",
    val folderFilter: String? = null,
    val favouritesOnly: Boolean = false,
    val sort: SortOrder = SortOrder.NEWEST,
    val trashCount: Int = 0,
)

class LibraryViewModel(
    private val container: AppContainer,
    application: Application,
) : AndroidViewModel(application) {

    private val query = MutableStateFlow("")
    private val folderFilter = MutableStateFlow<String?>(null)
    private val favouritesOnly = MutableStateFlow(false)
    private val sort = MutableStateFlow(SortOrder.NEWEST)

    private data class Filters(
        val query: String,
        val folder: String?,
        val favouritesOnly: Boolean,
        val sort: SortOrder,
    )

    private val filters = combine(query, folderFilter, favouritesOnly, sort, ::Filters)

    val uiState: StateFlow<LibraryUiState> = combine(
        container.repository.observeRecordings(),
        container.repository.observeFolders(),
        container.repository.observeTrashCount(),
        filters,
    ) { recordings, folders, trashCount, active ->
        val q = active.query
        val folder = active.folder
        val fav = active.favouritesOnly
        val order = active.sort

        val filtered = recordings
            .asSequence()
            .filter { folder == null || it.folder == folder }
            .filter { !fav || it.favorite }
            .filter {
                q.isBlank() ||
                    it.displayName.contains(q, ignoreCase = true) ||
                    it.note.contains(q, ignoreCase = true) ||
                    it.folder.contains(q, ignoreCase = true)
            }
            .toList()
            .let { list ->
                when (order) {
                    SortOrder.NEWEST -> list.sortedByDescending { it.createdAt }
                    SortOrder.OLDEST -> list.sortedBy { it.createdAt }
                    SortOrder.LONGEST -> list.sortedByDescending { it.durationMs }
                    SortOrder.NAME -> list.sortedBy { it.displayName }
                }
            }

        LibraryUiState(filtered, folders, q, folder, fav, order, trashCount)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    val trash: StateFlow<List<Recording>> = container.repository.observeTrash()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(value: String) { query.value = value }
    fun setFolderFilter(value: String?) { folderFilter.value = value }
    fun toggleFavouritesOnly() { favouritesOnly.value = !favouritesOnly.value }
    fun setSort(value: SortOrder) { sort.value = value }

    fun rename(id: Long, name: String) = viewModelScope.launch { container.repository.rename(id, name) }
    fun move(id: Long, folder: String) = viewModelScope.launch { container.repository.moveToFolder(id, folder) }
    fun toggleFavourite(recording: Recording) = viewModelScope.launch {
        container.repository.setFavorite(recording.id, !recording.favorite)
    }

    fun moveToTrash(id: Long) = viewModelScope.launch { container.repository.moveToTrash(id) }
    fun restore(id: Long) = viewModelScope.launch { container.repository.restoreFromTrash(id) }
    fun deleteForever(id: Long) = viewModelScope.launch { container.repository.deleteForever(id) }
    fun emptyTrash() = viewModelScope.launch { container.repository.emptyTrash() }

    fun createFolder(name: String) = viewModelScope.launch { container.repository.createFolder(name) }
    fun deleteFolder(name: String) = viewModelScope.launch {
        container.repository.deleteFolder(name)
        if (folderFilter.value == name) folderFilter.value = null
    }
    fun renameFolder(from: String, to: String) = viewModelScope.launch {
        container.repository.renameFolder(from, to)
        if (folderFilter.value == from) folderFilter.value = to
    }

    fun refresh() = viewModelScope.launch { container.repository.reconcile() }

    suspend fun peaksFor(recording: Recording): ByteArray? = withContext(Dispatchers.IO) {
        Peaks.load(recording.file)
    }

    companion object {
        val Factory = containerViewModelFactory { container, app -> LibraryViewModel(container, app) }
    }
}
