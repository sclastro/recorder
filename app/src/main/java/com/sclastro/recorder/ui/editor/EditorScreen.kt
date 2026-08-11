package com.sclastro.recorder.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sclastro.recorder.ui.components.WaveformScrubber
import com.sclastro.recorder.ui.library.TextInputDialog
import com.sclastro.recorder.ui.theme.MonoSmall
import com.sclastro.recorder.util.formatDurationPrecise

/**
 * Trim view: drag the two handles, preview, then write the selection out as a
 * new file. The original is never modified.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    recordingId: Long,
    onBack: () -> Unit,
    onSaved: (Long) -> Unit,
    viewModel: EditorViewModel = viewModel(factory = EditorViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbars = remember { SnackbarHostState() }
    var showNameDialog by remember { mutableStateOf(false) }

    LaunchedEffect(recordingId) { viewModel.load(recordingId) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbars.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    LaunchedEffect(state.savedId) {
        state.savedId?.let(onSaved)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.recording?.displayName ?: "Trim",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
        ) {
            Text(
                text = "Drag the handles to choose the part to keep. WAV is cut to the exact sample; compressed formats are not re-encoded, so the cut lands on the nearest audio frame.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(14.dp))

            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(22.dp))
                    .padding(vertical = 16.dp, horizontal = 12.dp),
            ) {
                WaveformScrubber(
                    peaks = state.peaks,
                    progress = if (state.durationMs > 0) {
                        state.positionMs.toFloat() / state.durationMs
                    } else {
                        0f
                    },
                    height = 150.dp,
                    selection = state.selection,
                    onSeek = viewModel::seekToFraction,
                    onSelectionChange = viewModel::setSelection,
                )

                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Start ${formatDurationPrecise(state.startMs)}", style = MonoSmall)
                    Text(
                        text = "Length ${formatDurationPrecise(state.endMs - state.startMs)}",
                        style = MonoSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text("End ${formatDurationPrecise(state.endMs)}", style = MonoSmall)
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = viewModel::setStartHere, modifier = Modifier.weight(1f)) {
                    Text("Set start")
                }
                IconButton(onClick = viewModel::previewSelection) {
                    Icon(
                        imageVector = if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = "Preview selection",
                    )
                }
                OutlinedButton(onClick = viewModel::setEndHere, modifier = Modifier.weight(1f)) {
                    Text("Set end")
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Nudge", style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = { viewModel.nudgeStart(-100) }) { Text("Start −0.1s") }
                TextButton(onClick = { viewModel.nudgeStart(100) }) { Text("Start +0.1s") }
                TextButton(onClick = { viewModel.nudgeEnd(-100) }) { Text("End −0.1s") }
                TextButton(onClick = { viewModel.nudgeEnd(100) }) { Text("End +0.1s") }
            }

            Spacer(Modifier.weight(1f))

            Button(
                onClick = { showNameDialog = true },
                enabled = !state.busy && state.endMs > state.startMs,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                if (state.busy) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
                } else {
                    Text("Save as new file")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showNameDialog) {
        TextInputDialog(
            title = "New file name",
            initial = (state.recording?.displayName ?: "Recording") + "_trimmed",
            confirmLabel = "Save",
            onConfirm = {
                showNameDialog = false
                viewModel.saveTrimmed(it)
            },
            onDismiss = { showNameDialog = false },
        )
    }
}
