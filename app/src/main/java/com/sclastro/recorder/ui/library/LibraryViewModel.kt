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
    LARGEST("Largest"),
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
                    SortOrder.LARGEST -> list.sortedByDescending { it.sizeBytes }
                }
            }

        LibraryUiState(filtered, folders, q, folder, fav, order, trashCount)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    /** Ids picked out by long-press, for acting on several recordings at once. */
    private val _selected = MutableStateFlow<Set<Long>>(emptySet())
    val selected: StateFlow<Set<Long>> = _selected

    fun toggleSelected(id: Long) {
        _selected.value = _selected.value.let { if (id in it) it - id else it + id }
    }

    fun clearSelection() { _selected.value = emptySet() }

    fun selectedRecordings(): List<Recording> {
        val ids = _selected.value
        return uiState.value.recordings.filter { it.id in ids }
    }

    /** Returns the ids that went to the bin, so the caller can offer an undo. */
    fun trashSelected(): List<Long> {
        val ids = _selected.value.toList()
        _selected.value = emptySet()
        viewModelScope.launch { ids.forEach { container.repository.moveToTrash(it) } }
        return ids
    }

    fun moveSelected(folder: String) {
        val ids = _selected.value.toList()
        _selected.value = emptySet()
        viewModelScope.launch { ids.forEach { container.repository.moveToFolder(it, folder) } }
    }

    fun restoreAll(ids: List<Long>) = viewModelScope.launch {
        ids.forEach { container.repository.restoreFromTrash(it) }
    }

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

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    /**
     * Rescans the directory tree. This used to run on every visit to the tab,
     * which meant a full disk walk — and a decode of any unrecognised file —
     * each time the user switched tabs. It now runs at app start and whenever
     * the user pulls to refresh.
     */
    fun refresh() = viewModelScope.launch {
        _refreshing.value = true
        container.repository.reconcile()
        _refreshing.value = false
    }

    suspend fun peaksFor(recording: Recording): ByteArray? = withContext(Dispatchers.IO) {
        Peaks.load(recording.file)
    }

    companion object {
        val Factory = containerViewModelFactory { container, app -> LibraryViewModel(container, app) }
    }
}
