package com.sclastro.recorder.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sclastro.recorder.AppContainer
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var template by remember(settings.filenameTemplate) { mutableStateOf(settings.filenameTemplate) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
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
            SectionTitle("檔案命名")
            OutlinedTextField(
                value = template,
                onValueChange = {
                    template = it
                    viewModel.setTemplate(it)
                },
                label = { Text("檔名範本") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "預覽：" + FileNaming.expand(template, preset = settings.presetName) + ".${settings.config.container.ext}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            FileNaming.TOKENS.forEach { (token, description) ->
                Text(
                    text = "$token — $description",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))
            ToggleRow(
                title = "錄完之後問我改名",
                subtitle = "關咗就直接用範本命名，唔會彈視窗",
                checked = settings.askNameAfterRecording,
                onChange = viewModel::setAskName,
            )
            ToggleRow(
                title = "錄音時保持螢幕唔熄",
                subtitle = "唔想熄螢幕都繼續錄嘅話唔使開，後台一樣會錄",
                checked = settings.keepScreenOn,
                onChange = viewModel::setKeepScreenOn,
            )

            HorizontalDivider(Modifier.padding(vertical = 16.dp))

            SectionTitle("外觀")
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

            SectionTitle("回收桶")
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(7, 14, 30, 60, 90).forEach { days ->
                    FilterChip(
                        selected = settings.trashRetentionDays == days,
                        onClick = { viewModel.setRetention(days) },
                        label = { Text("$days 日") },
                    )
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 16.dp))

            SectionTitle("儲存位置")
            Text(
                text = viewModel.storageRoot,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "資料夾就係真實嘅目錄，插上電腦可以直接睇到同一個結構。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(40.dp))
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
