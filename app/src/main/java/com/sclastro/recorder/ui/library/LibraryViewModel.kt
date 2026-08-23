package com.sclastro.recorder.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sclastro.recorder.AppContainer
import com.sclastro.recorder.audio.ExportEngine
import com.sclastro.recorder.audio.PeakGenerator
import com.sclastro.recorder.audio.Peaks
import com.sclastro.recorder.data.FolderInfo
import com.sclastro.recorder.data.MediaProbe
import com.sclastro.recorder.data.Recording
import com.sclastro.recorder.data.RecordingStorage
import com.sclastro.recorder.ui.containerViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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

    // ---- Import and export --------------------------------------------------

    /** Set while a long-running import or export is happening. */
    private val _working = MutableStateFlow<String?>(null)
    val working: StateFlow<String?> = _working

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun consumeMessage() { _message.value = null }

    /**
     * Copies an audio file chosen elsewhere on the device into the library so
     * it can be trimmed and filed like anything recorded here. The source is
     * only ever read — this is a copy, not a move.
     */
    fun importFrom(uri: Uri, displayName: String?) {
        if (_working.value != null) return
        _working.value = "Importing…"
        viewModelScope.launch {
            val resolver = getApplication<Application>().contentResolver
            val imported = withContext(Dispatchers.IO) {
                runCatching {
                    val base = RecordingStorage.sanitiseName(
                        displayName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "Imported",
                    )
                    val extension = displayName?.substringAfterLast('.', "")
                        ?.takeIf { it.isNotBlank() && it.length <= 5 }
                        ?: "m4a"
                    val target = container.storage.uniqueFile(container.storage.root, base, extension)
                    resolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    } ?: return@runCatching null
                    target
                }.getOrNull()
            }

            if (imported == null) {
                _working.value = null
                _message.value = "Could not read that file"
                return@launch
            }

            val probe = withContext(Dispatchers.IO) { MediaProbe.probe(imported) }
            if (probe.durationMs <= 0) {
                // Nothing decodable: better to remove it than leave a file the
                // library shows but cannot play.
                withContext(Dispatchers.IO) { imported.delete() }
                _working.value = null
                _message.value = "That file does not contain audio this app can read"
                return@launch
            }

            withContext(Dispatchers.IO) {
                PeakGenerator.generate(imported)?.let { Peaks.save(imported, it) }
            }
            container.repository.registerFile(
                file = imported,
                folder = "",
                durationMs = probe.durationMs,
                sampleRate = probe.sampleRate,
                bitDepth = probe.bitDepth,
                channels = probe.channels,
                format = imported.extension.uppercase(),
            )
            _working.value = null
            _message.value = "Imported ${imported.nameWithoutExtension}"
        }
    }

    /**
     * Writes a smaller AAC copy next to the original. Sharing sends the real
     * file, which is right until it is an hour of WAV and nothing will accept
     * it. Returns through [message]; the export lands in the library.
     */
    fun exportSmaller(recording: Recording, bitrateKbps: Int) {
        if (_working.value != null) return
        _working.value = "Exporting…"
        viewModelScope.launch {
            val destination = withContext(Dispatchers.IO) {
                container.storage.uniqueFile(
                    container.storage.folderDir(recording.folder),
                    RecordingStorage.sanitiseName("${recording.displayName}_${bitrateKbps}k"),
                    "m4a",
                )
            }
            when (val outcome = withContext(Dispatchers.IO) {
                ExportEngine.export(recording.file, destination, bitrateKbps)
            }) {
                is ExportEngine.Outcome.Success -> {
                    val probe = withContext(Dispatchers.IO) { MediaProbe.probe(outcome.file) }
                    container.repository.registerFile(
                        file = outcome.file,
                        folder = recording.folder,
                        durationMs = outcome.durationMs.takeIf { it > 0 } ?: probe.durationMs,
                        sampleRate = probe.sampleRate.takeIf { it > 0 } ?: recording.sampleRate,
                        bitDepth = 16,
                        channels = probe.channels.takeIf { it > 0 } ?: recording.channels,
                        format = "M4A",
                    )
                    _working.value = null
                    _message.value = "Exported at $bitrateKbps kbps"
                }
                is ExportEngine.Outcome.Failure -> {
                    _working.value = null
                    _message.value = outcome.message
                }
            }
        }
    }

    /** True when a mirror folder is set up and reachable. */
    val mirrorReady: StateFlow<Boolean> = container.settings.settings
        .map { it.mirrorTreeUri }
        .distinctUntilChanged()
        .map { container.mirror.isUsable(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Copies everything already in the library into the mirror folder. The
     * per-recording copy only fires on new recordings, so an existing library
     * needs this once after the folder is chosen.
     */
    fun copyAllToMirror() {
        if (_working.value != null) return
        viewModelScope.launch {
            val tree = container.settings.settings.first().mirrorTreeUri
            if (tree.isBlank()) {
                _message.value = "Choose a folder in Settings first"
                return@launch
            }
            val all = uiState.value.recordings
            var copied = 0
            all.forEachIndexed { index, recording ->
                _working.value = "Copying ${index + 1} of ${all.size}…"
                if (container.mirror.copy(recording.file, tree)) copied++
            }
            _working.value = null
            _message.value = if (copied == all.size) {
                "Copied $copied recordings"
            } else {
                "Copied $copied of ${all.size} — the rest could not be written"
            }
        }
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
