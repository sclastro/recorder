package com.sclastro.recorder.ui.settings

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sclastro.recorder.AppContainer
import com.sclastro.recorder.BuildConfig
import com.sclastro.recorder.data.FileNaming
import com.sclastro.recorder.data.FolderMirror
import com.sclastro.recorder.data.prefs.AppSettings
import com.sclastro.recorder.data.prefs.ThemeMode
import com.sclastro.recorder.ui.containerViewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val container: AppContainer,
    application: Application,
) : AndroidViewModel(application) {

    val settings: StateFlow<AppSettings> = container.settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val storageRoot: String = container.storage.root.absolutePath

    suspend fun describeMirror(uri: String) = container.mirror.describe(uri)

    /** Persists the SAF grant before storing it, or the copy fails after a reboot. */
    fun setMirrorTree(uri: String) = viewModelScope.launch {
        if (uri.isNotBlank()) container.mirror.persist(Uri.parse(uri))
        container.settings.setMirrorTreeUri(uri)
    }

    fun setTemplate(value: String) = viewModelScope.launch { container.settings.setTemplate(value) }
    fun setAskName(value: Boolean) = viewModelScope.launch { container.settings.setAskName(value) }
    fun setKeepScreenOn(value: Boolean) = viewModelScope.launch { container.settings.setKeepScreenOn(value) }
    fun setRetention(days: Int) = viewModelScope.launch { container.settings.setTrashRetentionDays(days) }
    fun setTheme(mode: ThemeMode) = viewModelScope.launch { container.settings.setThemeMode(mode) }
    fun setSplitMinutes(value: Int) = viewModelScope.launch { container.settings.setSplitMinutes(value) }
    fun setSplitMegabytes(value: Int) = viewModelScope.launch { container.settings.setSplitMegabytes(value) }
    fun setVoxEnabled(value: Boolean) = viewModelScope.launch { container.settings.setVoxEnabled(value) }
    fun setVoxThreshold(db: Int) = viewModelScope.launch { container.settings.setVoxThresholdDb(db) }

    companion object {
        val Factory = containerViewModelFactory { container, app -> SettingsViewModel(container, app) }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    // A TextFieldValue rather than a String so a token chip can be inserted
    // where the cursor is instead of only at the end.
    var template by remember(settings.filenameTemplate) {
        mutableStateOf(
            TextFieldValue(
                text = settings.filenameTemplate,
                selection = TextRange(settings.filenameTemplate.length),
            ),
        )
    }

    val treeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) viewModel.setMirrorTree(uri.toString())
    }
    // Off the main thread: describing the folder is a content-resolver query,
    // and this used to run on every recomposition.
    val mirror by produceState(FolderMirror.Info(null, false), settings.mirrorTreeUri) {
        value = viewModel.describeMirror(settings.mirrorTreeUri)
    }

    fun insertToken(token: String) {
        val current = template
        val start = current.selection.min
        val end = current.selection.max
        val next = current.text.replaceRange(start, end, token)
        template = TextFieldValue(next, TextRange(start + token.length))
        viewModel.setTemplate(next)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            SectionTitle("File naming")
            OutlinedTextField(
                value = template,
                onValueChange = {
                    template = it
                    viewModel.setTemplate(it.text)
                },
                label = { Text("Name template") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Preview: " + FileNaming.expand(template.text, preset = settings.presetName) +
                    ".${settings.config.container.ext}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))
            // Six lines of grey "{token} — meaning" was documentation you had to
            // retype by hand. Tapping one inserts it at the cursor instead.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FileNaming.TOKENS.forEach { (token, description) ->
                    AssistChip(
                        onClick = { insertToken(token) },
                        label = { Text(token) },
                        modifier = Modifier.semantics { contentDescription = "Insert $token — $description" },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                // What each one means is easier to see than to read: tapping a
                // chip changes the preview line above straight away.
                text = "Tap to insert at the cursor.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            ToggleRow(
                title = "Ask me to name it after recording",
                subtitle = "Off: the template name is used and nothing pops up",
                checked = settings.askNameAfterRecording,
                onChange = viewModel::setAskName,
            )
            ToggleRow(
                title = "Keep the screen on while recording",
                subtitle = "Not required — recording continues with the screen off",
                checked = settings.keepScreenOn,
                onChange = viewModel::setKeepScreenOn,
            )

            HorizontalDivider(Modifier.padding(vertical = 16.dp))

            SectionTitle("Long recordings")
            Text(
                text = "Auto-split closes the current file and starts the next one, so an " +
                    "all-day recording is a set of manageable files.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text("Every", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = settings.splitMinutes == 0,
                    onClick = { viewModel.setSplitMinutes(0) },
                    label = { Text("Off") },
                )
                listOf(15, 30, 60, 120).forEach { minutes ->
                    FilterChip(
                        selected = settings.splitMinutes == minutes,
                        onClick = { viewModel.setSplitMinutes(minutes) },
                        label = { Text(if (minutes < 60) "$minutes min" else "${minutes / 60} h") },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text("Or every", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = settings.splitMegabytes == 0,
                    onClick = { viewModel.setSplitMegabytes(0) },
                    label = { Text("Off") },
                )
                listOf(100, 250, 500, 1024).forEach { mb ->
                    FilterChip(
                        selected = settings.splitMegabytes == mb,
                        onClick = { viewModel.setSplitMegabytes(mb) },
                        label = { Text(if (mb < 1024) "$mb MB" else "1 GB") },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                // Otherwise a two-hour WAV quietly becoming two files reads as
                // a bug rather than as the format's own limit.
                text = "WAV recordings always split near 4 GB whatever is set here — a WAV " +
                    "cannot describe more of itself than that, and one file past the limit " +
                    "would not open properly elsewhere. That is about 2 hours at 96 kHz " +
                    "24-bit stereo, or 13 hours at 44.1 kHz 16-bit mono.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            ToggleRow(
                title = "Voice activation (VOX)",
                subtitle = "Stops writing while it is quiet and resumes the moment sound returns",
                checked = settings.voxEnabled,
                onChange = viewModel::setVoxEnabled,
            )
            if (settings.voxEnabled) {
                Text(
                    text = "Threshold ${settings.voxThresholdDb} dB — quieter than this counts as silence.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(-50, -45, -40, -35, -30).forEach { db ->
                        FilterChip(
                            selected = settings.voxThresholdDb == db,
                            onClick = { viewModel.setVoxThreshold(db) },
                            label = { Text("$db dB") },
                        )
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 16.dp))

            SectionTitle("Appearance")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.themeMode == mode,
                        onClick = { viewModel.setTheme(mode) },
                        label = { Text(mode.label) },
                    )
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 16.dp))

            SectionTitle("Recycle bin")
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(7, 14, 30, 60, 90).forEach { days ->
                    FilterChip(
                        selected = settings.trashRetentionDays == days,
                        onClick = { viewModel.setRetention(days) },
                        label = { Text("$days days") },
                    )
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 16.dp))

            SectionTitle("Storage location")
            Text(
                text = viewModel.storageRoot,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Folders are real directories. Android hides this location from other apps on the phone, but connecting to a computer over USB shows the same structure.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            Text("Also copy to a folder", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Recordings stay where they are — capture, trimming and waveforms all " +
                    "need a real path. This puts a copy somewhere your file manager can see, " +
                    "at the cost of using the space twice.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = { treeLauncher.launch(null) }) {
                    Text(if (mirror.chosen) "Change" else "Choose a folder")
                }
                if (mirror.chosen) {
                    TextButton(onClick = { viewModel.setMirrorTree("") }) { Text("Turn off") }
                }
            }
            mirror.label?.let { label ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (mirror.usable) {
                        "Copying to $label"
                    } else {
                        "$label is no longer reachable — choose it again"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (mirror.usable) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 16.dp))

            SectionTitle("About")
            Text(
                text = "Recorder ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
    )
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
