package com.sclastro.recorder.ui.record

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sclastro.recorder.data.FolderInfo
import com.sclastro.recorder.data.Recording
import com.sclastro.recorder.util.formatDuration
import com.sclastro.recorder.util.formatSize

/**
 * Shown right after a capture finishes. The file is already saved by the time
 * this appears — this only renames and re-files it, so dismissing loses nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveSheet(
    recording: Recording,
    folders: List<FolderInfo>,
    onConfirm: (name: String, folder: String) -> Unit,
    onCreateFolder: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember(recording.id) { mutableStateOf(recording.displayName) }
    var folder by remember(recording.id) { mutableStateOf(recording.folder) }
    var showNewFolder by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Text("錄音已儲存", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "${formatDuration(recording.durationMs)} · ${formatSize(recording.sizeBytes)} · ${recording.qualityLine()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("檔案名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(14.dp))
            Text("資料夾", style = MaterialTheme.typography.labelLarge)
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                folders.forEach { info ->
                    FilterChip(
                        selected = folder == info.name,
                        onClick = { folder = info.name },
                        label = { Text(info.label) },
                    )
                }
                AssistChip(
                    onClick = { showNewFolder = true },
                    label = { Text("新增") },
                    leadingIcon = { Icon(Icons.Filled.CreateNewFolder, contentDescription = null) },
                )
            }

            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("保持原樣") }
                Button(onClick = { onConfirm(name, folder) }, modifier = Modifier.weight(1f)) { Text("儲存") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showNewFolder) {
        NewFolderDialog(
            onConfirm = {
                onCreateFolder(it)
                folder = it
                showNewFolder = false
            },
            onDismiss = { showNewFolder = false },
        )
    }
}

@Composable
fun NewFolderDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增資料夾") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text("資料夾名") },
            )
        },
        confirmButton = {
            TextButton(onClick = { if (value.isNotBlank()) onConfirm(value.trim()) }) { Text("建立") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
