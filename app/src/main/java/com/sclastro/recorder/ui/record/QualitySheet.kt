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
            Text("Recording quality", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "About ${formatSize(config.bytesPerSecond() * 60)} per minute",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )

            Section("Format") {
                AudioContainer.entries.filter { it.isSupported }.forEach { container ->
                    Chip(
                        label = container.label,
                        selected = config.container == container,
                        onClick = { onChange(config.copy(container = container).normalised()) },
                    )
                }
            }

            Section("Sample rate") {
                SAMPLE_RATES.forEach { rate ->
                    Chip(
                        label = if (rate % 1000 == 0) "${rate / 1000}kHz" else "%.1fkHz".format(rate / 1000f),
                        selected = config.sampleRate == rate,
                        onClick = { onChange(config.copy(sampleRate = rate)) },
                    )
                }
            }

            if (config.container == AudioContainer.WAV) {
                Section("Bit depth") {
                    BitDepth.entries.filter { it.isSupported }.forEach { depth ->
                        Chip(
                            label = depth.label,
                            selected = config.bitDepth == depth,
                            onClick = { onChange(config.copy(bitDepth = depth)) },
                        )
                    }
                }
            } else {
                Section("Bitrate") {
                    BITRATES_KBPS.forEach { rate ->
                        Chip(
                            label = "$rate k",
                            selected = config.bitrateKbps == rate,
                            onClick = { onChange(config.copy(bitrateKbps = rate)) },
                        )
                    }
                }
            }

            Section("Channels") {
                Channels.entries.forEach { channels ->
                    Chip(
                        label = channels.label,
                        selected = config.channels == channels,
                        onClick = { onChange(config.copy(channels = channels)) },
                    )
                }
            }

            Section("Input source") {
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
            ToggleRow("Echo cancellation (AEC)", config.echoCancel) { onChange(config.copy(echoCancel = it)) }
            ToggleRow("Noise suppression (NS)", config.noiseSuppress) { onChange(config.copy(noiseSuppress = it)) }
            ToggleRow("Automatic gain (AGC)", config.autoGain) { onChange(config.copy(autoGain = it)) }
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
