package com.sclastro.recorder.ui.record

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sclastro.recorder.audio.PcmMath
import com.sclastro.recorder.audio.Preset
import com.sclastro.recorder.audio.RecorderEngine
import com.sclastro.recorder.ui.components.LevelMeter
import com.sclastro.recorder.ui.components.LiveWaveform
import com.sclastro.recorder.ui.components.RecordButton
import com.sclastro.recorder.ui.theme.LocalAccents
import com.sclastro.recorder.ui.theme.TimerLarge
import com.sclastro.recorder.util.formatDurationPrecise
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
    var pendingStart by remember { mutableStateOf(false) }

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

    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
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
                text = formatDurationPrecise(state.elapsedMs),
                style = TimerLarge,
                color = if (paused) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = settings.config.normalised().summary(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            LiveWaveform(levels = levels, active = active && !paused)
            Spacer(Modifier.height(12.dp))
            LevelMeter(peakDb = state.peakDb, rmsDb = state.rmsDb, clipping = state.clipping)

            if (active) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "${formatSize(state.bytesWritten)} written",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // Destination folder.
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Save to",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            folders.forEach { folder ->
                FilterChip(
                    selected = currentFolder == folder.name,
                    onClick = { viewModel.setTargetFolder(folder.name) },
                    enabled = !active,
                    label = { Text(folder.label) },
                )
            }
        }

        Spacer(Modifier.weight(1f))

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
                state.bookmarksMs.isNotEmpty() && active -> "${state.bookmarksMs.size} bookmark(s) added"
                paused -> "Paused"
                active -> "Recording"
                else -> "Tap the button to start recording"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (active) accents.record else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
        )

        Spacer(Modifier.height(16.dp))

        AnimatedVisibility(visible = state.error != null) {
            Box(Modifier.padding(bottom = 12.dp)) {
                Snackbar { Text(state.error.orEmpty()) }
            }
        }
    }

    LaunchedEffect(state.error) {
        if (state.error != null) {
            kotlinx.coroutines.delay(4000)
            viewModel.consumeMessage()
        }
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
