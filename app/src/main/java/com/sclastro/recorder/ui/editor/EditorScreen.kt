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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sclastro.recorder.ui.components.WaveformScrubber
import com.sclastro.recorder.ui.library.TextInputDialog
import com.sclastro.recorder.ui.theme.LocalAccents
import com.sclastro.recorder.ui.theme.MonoSmall
import com.sclastro.recorder.util.formatDurationPrecise

/** Two seconds is long enough to hear and short enough not to eat the content. */
private const val FADE_MS = 2_000L

/**
 * Trim view: drag the two handles, preview, then write the selection out —
 * either as a new file or over the original, which is confirmed first.
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
    val accents = LocalAccents.current
    val snackbars = remember { SnackbarHostState() }
    var showNameDialog by remember { mutableStateOf(false) }
    var confirmReplace by remember { mutableStateOf(false) }

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
        // Pinned, so the action is reachable no matter how tall the controls get.
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                Column(Modifier.navigationBarsPadding()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    val ready = !state.busy && state.endMs > state.startMs
                    Button(
                        onClick = { showNameDialog = true },
                        enabled = ready,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 20.dp, top = 12.dp)
                            .height(52.dp),
                    ) {
                        if (state.busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text("Save as new file")
                        }
                    }
                    // Trimming top and tail is usually a fix to the file rather
                    // than a derivative of it, so replacing has to be reachable —
                    // but it is destructive, so it is the quieter of the two.
                    TextButton(
                        onClick = { confirmReplace = true },
                        enabled = ready,
                        colors = ButtonDefaults.textButtonColors(contentColor = accents.danger),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp),
                    ) {
                        Text("Replace the original")
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Text(
                text = "Drag the handles to choose the part to keep, then save it as a new file " +
                    "or write it over the original.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))

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
                    label = "Waveform, keeping ${formatDurationPrecise(state.startMs)} to " +
                        formatDurationPrecise(state.endMs),
                )

                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth()) {
                    Readout("Start", formatDurationPrecise(state.startMs), TextAlign.Start, Modifier.weight(1f))
                    Readout(
                        label = "Length",
                        value = formatDurationPrecise(state.endMs - state.startMs),
                        align = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                        highlight = true,
                    )
                    Readout("End", formatDurationPrecise(state.endMs), TextAlign.End, Modifier.weight(1f))
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = viewModel::setStartHere, modifier = Modifier.weight(1f)) {
                    Text("Set start")
                }
                FilledTonalIconButton(onClick = viewModel::previewSelection, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = "Preview selection",
                    )
                }
                OutlinedButton(onClick = viewModel::setEndHere, modifier = Modifier.weight(1f)) {
                    Text("Set end")
                }
            }

            Spacer(Modifier.height(16.dp))

            // Two short rows rather than one long one — five controls side by
            // side overflow on a phone and squeeze the last label into a column
            // of single characters.
            NudgeRow(
                label = "Start",
                onMinus = { viewModel.nudgeStart(-100) },
                onPlus = { viewModel.nudgeStart(100) },
            )
            Spacer(Modifier.height(8.dp))
            NudgeRow(
                label = "End",
                onMinus = { viewModel.nudgeEnd(-100) },
                onPlus = { viewModel.nudgeEnd(100) },
            )

            HorizontalDivider(Modifier.padding(vertical = 16.dp))

            Text(
                text = "Other edits",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "These ignore the handles and act on the whole recording. " +
                    "Each one writes a new file.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = viewModel::splitHere,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Split at ${formatDurationPrecise(state.positionMs)}")
            }

            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { viewModel.applyFade(FADE_MS, FADE_MS) },
                    enabled = !state.busy && state.sampleEditable,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Fade ends", maxLines = 1)
                }
                OutlinedButton(
                    onClick = viewModel::applyNormalize,
                    enabled = !state.busy && state.sampleEditable,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Normalise", maxLines = 1)
                }
            }
            if (!state.sampleEditable && state.recording != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Fading and normalising need a WAV recording — on a compressed " +
                        "file they would mean re-encoding, and quality would drop each time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmReplace) {
        val discarded = state.durationMs - (state.endMs - state.startMs)
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            title = { Text("Replace the original?") },
            text = {
                Text(
                    "${formatDurationPrecise(discarded)} will be cut from " +
                        "${state.recording?.displayName ?: "this recording"} and cannot be recovered. " +
                        "Bookmarks outside the kept part are removed.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmReplace = false
                        viewModel.replaceOriginal()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = accents.danger),
                ) { Text("Replace") }
            },
            dismissButton = {
                TextButton(onClick = { confirmReplace = false }) { Text("Cancel") }
            },
        )
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

@Composable
private fun Readout(
    label: String,
    value: String,
    align: TextAlign,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
) {
    Column(modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = align,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = value,
            style = MonoSmall,
            maxLines = 1,
            color = if (highlight) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            textAlign = align,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun NudgeRow(label: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp),
        )
        OutlinedButton(onClick = onMinus, modifier = Modifier.weight(1f)) { Text("−0.1s") }
        OutlinedButton(onClick = onPlus, modifier = Modifier.weight(1f)) { Text("+0.1s") }
    }
}
