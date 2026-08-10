package com.sclastro.recorder.ui.record

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState as rememberVerticalScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sclastro.recorder.audio.AudioContainer
import com.sclastro.recorder.audio.BITRATES_KBPS
import com.sclastro.recorder.audio.BitDepth
import com.sclastro.recorder.audio.Channels
import com.sclastro.recorder.audio.MicSource
import com.sclastro.recorder.audio.RecordingConfig
import com.sclastro.recorder.audio.SAMPLE_RATES
import com.sclastro.recorder.util.formatSize

/**
 * Everything about how the audio is captured, in one sheet. Options the device
 * cannot honour are hidden rather than shown and then rejected.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QualitySheet(
    config: RecordingConfig,
    onChange: (RecordingConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .verticalScroll(rememberVerticalScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
        ) {
            Text("錄音質素", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "每分鐘約 ${formatSize(config.bytesPerSecond() * 60)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )

            Section("儲存格式") {
                AudioContainer.entries.filter { it.isSupported }.forEach { container ->
                    Chip(
                        label = container.label,
                        selected = config.container == container,
                        onClick = { onChange(config.copy(container = container).normalised()) },
                    )
                }
            }

            Section("取樣率") {
                SAMPLE_RATES.forEach { rate ->
                    Chip(
                        label = if (rate % 1000 == 0) "${rate / 1000}kHz" else "%.1fkHz".format(rate / 1000f),
                        selected = config.sampleRate == rate,
                        onClick = { onChange(config.copy(sampleRate = rate)) },
                    )
                }
            }

            if (config.container == AudioContainer.WAV) {
                Section("位元深度") {
                    BitDepth.entries.filter { it.isSupported }.forEach { depth ->
                        Chip(
                            label = depth.label,
                            selected = config.bitDepth == depth,
                            onClick = { onChange(config.copy(bitDepth = depth)) },
                        )
                    }
                }
            } else {
                Section("位元率") {
                    BITRATES_KBPS.forEach { rate ->
                        Chip(
                            label = "$rate k",
                            selected = config.bitrateKbps == rate,
                            onClick = { onChange(config.copy(bitrateKbps = rate)) },
                        )
                    }
                }
            }

            Section("聲道") {
                Channels.entries.forEach { channels ->
                    Chip(
                        label = channels.label,
                        selected = config.channels == channels,
                        onClick = { onChange(config.copy(channels = channels)) },
                    )
                }
            }

            Section("音源") {
                MicSource.entries.forEach { source ->
                    Chip(
                        label = source.label,
                        selected = config.source == source,
                        onClick = { onChange(config.copy(source = source)) },
                    )
                }
            }
            Text(
                text = config.source.hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))
            ToggleRow("回音消除 (AEC)", config.echoCancel) { onChange(config.copy(echoCancel = it)) }
            ToggleRow("降噪 (NS)", config.noiseSuppress) { onChange(config.copy(noiseSuppress = it)) }
            ToggleRow("自動增益 (AGC)", config.autoGain) { onChange(config.copy(autoGain = it)) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
    )
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
