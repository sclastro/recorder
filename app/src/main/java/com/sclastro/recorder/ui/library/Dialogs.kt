package com.sclastro.recorder.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sclastro.recorder.audio.ExportEngine
import com.sclastro.recorder.data.Recording
import com.sclastro.recorder.util.formatSize
import com.sclastro.recorder.ui.record.NewFolderDialog

@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (value.isNotBlank()) onConfirm(value.trim()) }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun FolderPickerDialog(
    folders: List<Pair<String, String>>,
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Move to folder",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                folders.forEach { (name, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(name) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = name == current, onClick = { onPick(name) })
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/**
 * Picks a bitrate for a smaller copy. This is the one place in the app that
 * throws quality away on purpose, so the dialog says what the trade is and
 * roughly how big the result will be.
 */
@Composable
fun ExportDialog(
    recording: Recording,
    onExport: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export smaller") },
        text = {
            Column {
                Text(
                    text = "Writes an AAC copy alongside the original, which is left alone. " +
                        "64 kbps is plenty for speech.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                ExportEngine.BITRATES.forEach { kbps ->
                    val estimate = recording.durationMs / 1000 * kbps * 1000 / 8
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onExport(kbps) }
                            .padding(vertical = 10.dp),
                    ) {
                        Text("$kbps kbps", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(
                            text = "about ${formatSize(estimate)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Folders could be created but never renamed or removed, so a typo was
 * permanent. Deleting a folder keeps its recordings — they move back to
 * Unsorted rather than disappearing with it.
 */
@Composable
fun FolderManagerDialog(
    folders: List<Pair<String, String>>,
    onCreate: (String) -> Unit,
    onRename: (from: String, to: String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var renaming by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manage folders") },
        text = {
            if (folders.isEmpty()) {
                Text(
                    text = "No folders yet. Create one from the folder icon, or when saving a recording.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(
                    Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    folders.forEach { (name, label) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { renaming = name }) {
                                Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = "Rename folder")
                            }
                            IconButton(onClick = { deleting = name }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete folder")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { TextButton(onClick = { creating = true }) { Text("New folder") } },
    )

    if (creating) {
        NewFolderDialog(
            onConfirm = {
                onCreate(it)
                creating = false
            },
            onDismiss = { creating = false },
        )
    }

    renaming?.let { from ->
        TextInputDialog(
            title = "Rename folder",
            initial = from,
            confirmLabel = "Rename",
            onConfirm = {
                onRename(from, it)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }

    deleting?.let { name ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"$name\"?") },
            text = { Text("Recordings inside it move to Unsorted. Nothing is deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(name)
                    deleting = null
                }) { Text("Delete folder") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}
