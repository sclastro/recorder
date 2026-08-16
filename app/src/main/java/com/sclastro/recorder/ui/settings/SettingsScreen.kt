package com.sclastro.recorder.ui.settings

import android.app.Application
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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

    fun setTemplate(value: String) = viewModelScope.launch { container.settings.setTemplate(value) }
    fun setAskName(value: Boolean) = viewModelScope.launch { container.settings.setAskName(value) }
    fun setKeepScreenOn(value: Boolean) = viewModelScope.launch { container.settings.setKeepScreenOn(value) }
    fun setRetention(days: Int) = viewModelScope.launch { container.settings.setTrashRetentionDays(days) }
    fun setTheme(mode: ThemeMode) = viewModelScope.launch { container.settings.setThemeMode(mode) }

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
