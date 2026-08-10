package com.sclastro.recorder.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sclastro.recorder.data.Recording
import com.sclastro.recorder.ui.components.MiniWaveform
import com.sclastro.recorder.ui.record.NewFolderDialog
import com.sclastro.recorder.ui.theme.LocalAccents
import com.sclastro.recorder.util.formatDuration
import com.sclastro.recorder.util.formatSize
import com.sclastro.recorder.util.formatTimestamp

@Composable
fun LibraryScreen(
    onOpen: (Recording) -> Unit,
    onEdit: (Recording) -> Unit,
    onShare: (Recording) -> Unit,
    onOpenTrash: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var renameTarget by remember { mutableStateOf<Recording?>(null) }
    var moveTarget by remember { mutableStateOf<Recording?>(null) }
    var showNewFolder by remember { mutableStateOf(false) }
    var showSort by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refresh() }

    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            placeholder = { Text("搵錄音、備註或資料夾") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

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
                    label = { Text("全部") },
                )
                FilterChip(
                    selected = state.favouritesOnly,
                    onClick = { viewModel.toggleFavouritesOnly() },
                    label = { Text("我的最愛") },
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

            IconButton(onClick = { showNewFolder = true }) {
                Icon(Icons.Filled.CreateNewFolder, contentDescription = "新增資料夾")
            }
            Box {
                IconButton(onClick = { showSort = true }) {
                    Icon(Icons.Filled.Sort, contentDescription = "排序")
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
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("回收桶（${state.trashCount}）") },
                        onClick = {
                            showSort = false
                            onOpenTrash()
                        },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                    )
                }
            }
        }

        if (state.recordings.isEmpty()) {
            EmptyState(
                text = if (state.query.isBlank()) "重未有錄音，去「錄音」撳個掣開始啦" else "搵唔到相符嘅錄音",
            )
        } else {
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
                    RecordingRow(
                        recording = recording,
                        peaksProvider = { viewModel.peaksFor(recording) },
                        onClick = { onOpen(recording) },
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

    renameTarget?.let { target ->
        TextInputDialog(
            title = "重新命名",
            initial = target.displayName,
            confirmLabel = "改名",
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

    if (showNewFolder) {
        NewFolderDialog(
            onConfirm = {
                viewModel.createFolder(it)
                showNewFolder = false
            },
            onDismiss = { showNewFolder = false },
        )
    }
}

@Composable
private fun RecordingRow(
    recording: Recording,
    peaksProvider: suspend () -> ByteArray?,
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

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                    progress = 0f,
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
                contentDescription = "我的最愛",
                tint = if (recording.favorite) accents.warning else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "更多")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("剪輯") },
                    leadingIcon = { Icon(Icons.Filled.ContentCut, contentDescription = null) },
                    onClick = { menuOpen = false; onEdit() },
                )
                DropdownMenuItem(
                    text = { Text("重新命名") },
                    leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = null) },
                    onClick = { menuOpen = false; onRename() },
                )
                DropdownMenuItem(
                    text = { Text("移去資料夾") },
                    leadingIcon = { Icon(Icons.Filled.DriveFileMove, contentDescription = null) },
                    onClick = { menuOpen = false; onMove() },
                )
                DropdownMenuItem(
                    text = { Text("分享") },
                    leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                    onClick = { menuOpen = false; onShare() },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("刪除") },
                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
    }
}

@Composable
fun EmptyState(text: String) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Normal,
        )
    }
}
