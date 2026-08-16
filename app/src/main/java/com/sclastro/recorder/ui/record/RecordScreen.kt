package com.sclastro.recorder.ui.record

import android.Manifest
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sclastro.recorder.audio.PcmMath
import com.sclastro.recorder.audio.Preset
import com.sclastro.recorder.audio.RecorderEngine
import com.sclastro.recorder.ui.components.BookmarkStrip
import com.sclastro.recorder.ui.components.LevelMeter
import com.sclastro.recorder.ui.components.LiveWaveform
import com.sclastro.recorder.ui.components.RecordButton
import com.sclastro.recorder.ui.library.FolderPickerDialog
import com.sclastro.recorder.ui.theme.LocalAccents
import com.sclastro.recorder.ui.theme.TimerLarge
import com.sclastro.recorder.util.formatDuration
import com.sclastro.recorder.util.formatSize

@Composable
fun RecordScreen(
    modifier: Modifier = Modifier,
    viewModel: RecordViewModel = viewModel(factory = RecordViewModel.Factory),
) {
    val context = LocalContext.current
    val accents = LocalAccents.current
    val state by viewModel.engineState.collectAsStateWithLifecycle()
    val levels by viewModel.levels.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val justSaved by viewModel.justSaved.collectAsStateWithLifecycle()
    val targetFolder by viewModel.targetFolder.collectAsStateWithLifecycle()

    var showQuality by remember { mutableStateOf(false) }
    var showFolderPicker by remember { mutableStateOf(false) }
    var pendingStart by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    val requiredPermissions = remember {
        buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true && pendingStart) {
            viewModel.toggleRecording(context)
        }
        pendingStart = false
    }

    fun onRecordTapped() {
        val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (hasMic) {
            viewModel.toggleRecording(context)
        } else {
            pendingStart = true
            permissionLauncher.launch(requiredPermissions)
        }
    }

    val currentFolder = targetFolder ?: settings.defaultFolder
    val active = state.isActive
    val paused = state.status == RecorderEngine.Status.PAUSED
    val levelFraction = PcmMath.dbToFraction(state.peakDb)

    // Landscape is short and wide: stacked, the capture panel eats the height
    // and pushes the record button off the bottom. Side by side, each half
    // gets a full column and the button stays put.
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val capture: @Composable ColumnScope.() -> Unit = {
        // Presets — one tap to a whole capture configuration.
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Preset.entries.filter { it != Preset.CUSTOM }.forEach { preset ->
                FilterChip(
                    selected = settings.presetName == preset.label,
                    onClick = { viewModel.applyPreset(preset) },
                    enabled = !active,
                    label = { Text(preset.label) },
                )
            }
            AssistChip(
                onClick = { showQuality = true },
                enabled = !active,
                label = { Text("Custom") },
                leadingIcon = { Icon(Icons.Filled.Tune, contentDescription = null, Modifier.size(18.dp)) },
                colors = AssistChipDefaults.assistChipColors(),
            )
        }

        Spacer(Modifier.height(4.dp))

        // The capture panel: timer, live waveform, level.
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(24.dp))
                .padding(horizontal = 18.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = formatDuration(state.elapsedMs),
                style = TimerLarge,
                color = if (paused) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = settings.config.normalised().summary(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            LiveWaveform(levels = levels, active = active && !paused)
            Spacer(Modifier.height(16.dp))
            LevelMeter(peakDb = state.peakDb, rmsDb = state.rmsDb, clipping = state.clipping)

            // Where the bookmarks are, not just how many. The live waveform
            // only holds about two seconds, so it could never show this.
            if (active && state.bookmarksMs.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                BookmarkStrip(bookmarksMs = state.bookmarksMs, elapsedMs = state.elapsedMs)
            }

            if (active) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = buildString {
                        append(formatSize(state.bytesWritten))
                        append(" written")
                        if (settings.splitMinutes > 0) append(" · part ${state.partNumber}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Where it lands. This used to be a row of folder chips, but the save
        // sheet asks the same question again after recording, and nine times
        // out of ten the answer is last time's answer — so it is one quiet
        // line that opens a picker rather than a permanent row of choices.
        Row(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(enabled = !active) { showFolderPicker = true }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.FolderOpen,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Saving to " + (folders.firstOrNull { it.name == currentFolder }?.label ?: "Unsorted"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(24.dp))
    }

    val transport: @Composable ColumnScope.() -> Unit = {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(
                onClick = { viewModel.addBookmark() },
                enabled = active,
                modifier = Modifier.size(52.dp),
            ) {
                Icon(Icons.Filled.Bookmark, contentDescription = "Add bookmark")
            }

            RecordButton(
                recording = active,
                level = levelFraction,
                onClick = ::onRecordTapped,
            )

            IconButton(
                onClick = { viewModel.togglePause(context) },
                enabled = active,
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape),
                colors = IconButtonDefaults.filledTonalIconButtonColors(),
            ) {
                Icon(
                    imageVector = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = if (paused) "Resume" else "Pause",
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = when {
                paused -> "Paused"
                // VOX means the mic is open but nothing is being written, and
                // saying "Recording" through a silence would be a lie.
                state.voxIdle -> "Waiting for sound"
                active -> "Recording"
                settings.voxEnabled -> "Tap to start — recording begins when it hears you"
                else -> "Tap the button to start recording"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                state.voxIdle -> MaterialTheme.colorScheme.onSurfaceVariant
                active -> accents.record
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (active && !state.voxIdle) FontWeight.Medium else FontWeight.Normal,
        )

        AnimatedVisibility(visible = active) {
            TextButton(
                onClick = { confirmDiscard = true },
                colors = ButtonDefaults.textButtonColors(contentColor = accents.danger),
            ) {
                Text("Discard recording")
            }
        }

        AnimatedVisibility(visible = state.error != null) {
            Box(Modifier.padding(bottom = 12.dp)) {
                Snackbar { Text(state.error.orEmpty()) }
            }
        }

        Spacer(Modifier.height(8.dp))
    }

    if (landscape) {
        Row(modifier.fillMaxSize()) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                content = capture,
            )
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                content = transport,
            )
        }
    } else {
        Column(modifier.fillMaxSize()) {
            // Everything above the transport scrolls, so a large system font
            // or a short screen can never push the record button out of reach.
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                content = capture,
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                content = transport,
            )
        }
    }

    LaunchedEffect(state.error) {
        if (state.error != null) {
            kotlinx.coroutines.delay(4000)
            viewModel.dismissError()
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard this recording?") },
            text = { Text("The audio captured so far will be deleted and nothing will be saved.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    viewModel.discardRecording(context)
                }) { Text("Discard") }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep recording") } },
        )
    }

    if (showFolderPicker) {
        FolderPickerDialog(
            title = "Save recordings to",
            folders = folders.map { it.name to it.label },
            current = currentFolder,
            onPick = {
                viewModel.setTargetFolder(it)
                showFolderPicker = false
            },
            onDismiss = { showFolderPicker = false },
        )
    }

    if (showQuality) {
        QualitySheet(
            config = settings.config,
            onChange = viewModel::updateConfig,
            onDismiss = { showQuality = false },
        )
    }

    justSaved?.let { saved ->
        if (!settings.askNameAfterRecording) {
            LaunchedEffect(saved.id) { viewModel.dismissJustSaved() }
            return@let
        }
        SaveSheet(
            recording = saved,
            folders = folders,
            onConfirm = viewModel::renameJustSaved,
            onCreateFolder = viewModel::createFolder,
            onDismiss = viewModel::dismissJustSaved,
        )
    }
}
