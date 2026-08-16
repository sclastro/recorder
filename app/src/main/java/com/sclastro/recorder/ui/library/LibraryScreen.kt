package com.sclastro.recorder.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sclastro.recorder.data.Recording
import com.sclastro.recorder.ui.components.MiniWaveform
import com.sclastro.recorder.ui.player.MiniPlayerState
import com.sclastro.recorder.ui.theme.LocalAccents
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import com.sclastro.recorder.util.formatDuration
import com.sclastro.recorder.util.formatSize
import com.sclastro.recorder.util.formatTimestamp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpen: (Recording) -> Unit,
    onEdit: (Recording) -> Unit,
    onShare: (Recording) -> Unit,
    onShareMany: (List<Recording>) -> Unit,
    onOpenTrash: () -> Unit,
    onStartRecording: () -> Unit,
    nowPlaying: MiniPlayerState,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var moveSelection by remember { mutableStateOf(false) }

    // Deleting is recoverable, but only if the user is told how.
    fun announceTrashed(ids: List<Long>) {
        if (ids.isEmpty()) return
        scope.launch {
            val label = if (ids.size == 1) "Moved to the bin" else "${ids.size} moved to the bin"
            val result = snackbars.showSnackbar(message = label, actionLabel = "Undo")
            if (result == SnackbarResult.ActionPerformed) viewModel.restoreAll(ids)
        }
    }
    var renameTarget by remember { mutableStateOf<Recording?>(null) }
    var moveTarget by remember { mutableStateOf<Recording?>(null) }
    var showFolders by remember { mutableStateOf(false) }
    var showSort by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            placeholder = { Text("Search recordings, notes or folders") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.setQuery("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        if (selected.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = viewModel::clearSelection) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear selection")
                }
                Text(
                    text = "${selected.size} selected",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onShareMany(viewModel.selectedRecordings()) }) {
                    Icon(Icons.Filled.Share, contentDescription = "Share selected")
                }
                IconButton(onClick = { moveSelection = true }) {
                    Icon(Icons.Filled.DriveFileMove, contentDescription = "Move selected")
                }
                IconButton(onClick = { announceTrashed(viewModel.trashSelected()) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete selected")
                }
            }
        } else {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.folderFilter == null && !state.favouritesOnly,
                    onClick = { viewModel.setFolderFilter(null) },
                    label = { Text("All") },
                )
                FilterChip(
                    selected = state.favouritesOnly,
                    onClick = { viewModel.toggleFavouritesOnly() },
                    label = { Text("Favourites") },
                )
                state.folders.forEach { folder ->
                    FilterChip(
                        selected = state.folderFilter == folder.name,
                        onClick = {
                            viewModel.setFolderFilter(if (state.folderFilter == folder.name) null else folder.name)
                        },
                        label = { Text("${folder.label} ${folder.count}") },
                    )
                }
            }

            IconButton(onClick = { showFolders = true }) {
                Icon(Icons.Filled.FolderOpen, contentDescription = "Manage folders")
            }
            IconButton(onClick = onOpenTrash) {
                BadgedBox(
                    badge = {
                        if (state.trashCount > 0) Badge { Text(state.trashCount.toString()) }
                    },
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = "Recycle bin")
                }
            }
            Box {
                IconButton(onClick = { showSort = true }) {
                    Icon(Icons.Filled.Sort, contentDescription = "Sort")
                }
                DropdownMenu(expanded = showSort, onDismissRequest = { showSort = false }) {
                    SortOrder.entries.forEach { order ->
                        DropdownMenuItem(
                            text = { Text(order.label) },
                            onClick = {
                                viewModel.setSort(order)
                                showSort = false
                            },
                        )
                    }
                }
            }
        }
        }

        if (state.recordings.isEmpty()) {
            when {
                state.query.isNotBlank() -> EmptyState(
                    icon = Icons.Filled.SearchOff,
                    title = "Nothing matches that search",
                    body = "Searching looks at names, notes and folders.",
                    actionLabel = "Clear search",
                    onAction = { viewModel.setQuery("") },
                )

                state.favouritesOnly -> EmptyState(
                    icon = Icons.Outlined.StarBorder,
                    title = "No favourites yet",
                    body = "Tap the star on a recording to keep it here.",
                    actionLabel = "Show all",
                    onAction = { viewModel.setFolderFilter(null) },
                )

                state.folderFilter != null -> EmptyState(
                    icon = Icons.Filled.FolderOpen,
                    title = "This folder is empty",
                    body = "Move recordings in from the ⋮ menu, or record straight into it.",
                    actionLabel = "Show all",
                    onAction = { viewModel.setFolderFilter(null) },
                )

                else -> EmptyState(
                    icon = Icons.Filled.Mic,
                    title = "No recordings yet",
                    body = "Everything you record lands here, sorted into folders.",
                    actionLabel = "Start recording",
                    onAction = onStartRecording,
                )
            }
        } else {
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.recordings, key = { it.id }) { recording ->
                    val isCurrent = nowPlaying.recordingId == recording.id
                    RecordingRow(
                        recording = recording,
                        peaksProvider = { viewModel.peaksFor(recording) },
                        selected = recording.id in selected,
                        selectionMode = selected.isNotEmpty(),
                        // The row that is loaded in the session tracks live;
                        // everything else shows where it was left off.
                        progress = if (isCurrent) nowPlaying.progress else recording.listenedFraction,
                        playing = isCurrent && nowPlaying.playing,
                        loaded = isCurrent,
                        onSwipedAway = {
                            viewModel.moveToTrash(recording.id)
                            announceTrashed(listOf(recording.id))
                        },
                        onLongClick = { viewModel.toggleSelected(recording.id) },
                        onClick = {
                            if (selected.isNotEmpty()) {
                                viewModel.toggleSelected(recording.id)
                            } else {
                                onOpen(recording)
                            }
                        },
                        onFavourite = { viewModel.toggleFavourite(recording) },
                        onRename = { renameTarget = recording },
                        onMove = { moveTarget = recording },
                        onEdit = { onEdit(recording) },
                        onShare = { onShare(recording) },
                        onDelete = { viewModel.moveToTrash(recording.id) },
                    )
                }
            }
            }
        }
    }

    renameTarget?.let { target ->
        TextInputDialog(
            title = "Rename",
            initial = target.displayName,
            confirmLabel = "Rename",
            onConfirm = {
                viewModel.rename(target.id, it)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    moveTarget?.let { target ->
        FolderPickerDialog(
            folders = state.folders.map { it.name to it.label },
            current = target.folder,
            onPick = {
                viewModel.move(target.id, it)
                moveTarget = null
            },
            onDismiss = { moveTarget = null },
        )
    }

    if (showFolders) {
        FolderManagerDialog(
            folders = state.folders.filterNot { it.isRoot }.map { it.name to it.label },
            onCreate = viewModel::createFolder,
            onRename = viewModel::renameFolder,
            onDelete = viewModel::deleteFolder,
            onDismiss = { showFolders = false },
        )
    }

}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun RecordingRow(
    recording: Recording,
    peaksProvider: suspend () -> ByteArray?,
    selected: Boolean,
    selectionMode: Boolean,
    progress: Float,
    playing: Boolean,
    loaded: Boolean,
    onSwipedAway: () -> Unit,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
    onFavourite: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val accents = LocalAccents.current
    var menuOpen by remember { mutableStateOf(false) }
    val peaks by produceState<ByteArray?>(initialValue = null, recording.relPath) {
        value = peaksProvider()
    }

    // Swiping a row bins it, which is reversible from the snackbar. Disabled
    // while picking rows out, where a sideways drag is easy to do by accident.
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (!selectionMode && value != SwipeToDismissBoxValue.Settled) {
                onSwipedAway()
                true
            } else {
                false
            }
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        gesturesEnabled = !selectionMode,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
                    .background(accents.danger.copy(alpha = 0.12f))
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null, tint = accents.danger)
            }
        },
    ) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLowest
                },
            )
            .border(
                1.dp,
                when {
                    selected -> MaterialTheme.colorScheme.primary
                    loaded -> accents.playback
                    else -> MaterialTheme.colorScheme.outlineVariant
                },
                RoundedCornerShape(16.dp),
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (loaded) {
                    Icon(
                        imageVector = if (playing) Icons.Filled.VolumeUp else Icons.Filled.Pause,
                        contentDescription = if (playing) "Playing" else "Paused",
                        tint = accents.playback,
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .size(16.dp),
                    )
                }
                Text(
                    text = recording.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (recording.favorite) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = null,
                        tint = accents.warning,
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .size(16.dp),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                MiniWaveform(
                    peaks = peaks,
                    progress = progress,
                    modifier = Modifier
                        .width(84.dp)
                        .height(22.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = buildString {
                        append(formatDuration(recording.durationMs))
                        append(" · ")
                        append(recording.format)
                        append(" · ")
                        append(formatSize(recording.sizeBytes))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Only worth saying when it is genuinely part-way and not the
                // row already being tracked live by the mini player.
                if (!loaded && progress > 0f) {
                    Text(
                        text = " · ${(progress * 100).roundToInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = accents.playback,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = formatTimestamp(recording.createdAt) +
                    if (recording.folder.isNotEmpty()) " · ${recording.folder}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        IconButton(onClick = onFavourite) {
            Icon(
                imageVector = if (recording.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                contentDescription = "Favourite",
                tint = if (recording.favorite) accents.warning else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Trim") },
                    leadingIcon = { Icon(Icons.Filled.ContentCut, contentDescription = null) },
                    onClick = { menuOpen = false; onEdit() },
                )
                DropdownMenuItem(
                    text = { Text("Rename") },
                    leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = null) },
                    onClick = { menuOpen = false; onRename() },
                )
                DropdownMenuItem(
                    text = { Text("Move to folder") },
                    leadingIcon = { Icon(Icons.Filled.DriveFileMove, contentDescription = null) },
                    onClick = { menuOpen = false; onMove() },
                )
                DropdownMenuItem(
                    text = { Text("Share") },
                    leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                    onClick = { menuOpen = false; onShare() },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Delete") },
                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
    }
    }
}

/**
 * An empty list should say what would fill it and offer the one action that
 * does, rather than leaving a single grey sentence in the middle of the screen.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(20.dp))
                Button(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}
