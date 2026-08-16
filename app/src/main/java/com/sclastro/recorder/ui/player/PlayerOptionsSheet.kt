package com.sclastro.recorder.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sclastro.recorder.ui.theme.LocalAccents
import com.sclastro.recorder.ui.theme.MonoSmall
import com.sclastro.recorder.util.formatDuration

/**
 * Speed, sleep timer and bookmarks used to sit on the player as three separate
 * horizontally scrolling rows. They fought with the page's own scrolling and
 * pushed the notes field off the bottom, so they live here instead, behind one
 * row of three buttons.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PlayerOptionsSheet(
    tab: OptionsTab,
    state: PlayerUiState,
    bookmarks: List<Long>,
    onSpeed: (Float) -> Unit,
    onSleep: (Int?) -> Unit,
    onAddBookmark: () -> Unit,
    onJumpBookmark: (Long) -> Unit,
    onRemoveBookmark: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val accents = LocalAccents.current

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            when (tab) {
                OptionsTab.SPEED -> {
                    SheetTitle("Playback speed")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SPEEDS.forEach { speed ->
                            FilterChip(
                                selected = state.speed == speed,
                                onClick = { onSpeed(speed) },
                                label = { Text(speedLabel(speed)) },
                            )
                        }
                    }
                }

                OptionsTab.SLEEP -> {
                    SheetTitle("Sleep timer")
                    Text(
                        text = state.sleepTimerMinutes
                            ?.let { "Pausing in ${formatDuration(state.sleepTimerRemainingMs)}" }
                            ?: "Playback pauses itself when the timer runs out.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SLEEP_MINUTES.forEach { minutes ->
                            FilterChip(
                                selected = state.sleepTimerMinutes == minutes,
                                onClick = { onSleep(minutes) },
                                label = { Text("$minutes min") },
                            )
                        }
                        FilterChip(
                            selected = state.sleepTimerMinutes == null,
                            onClick = { onSleep(null) },
                            label = { Text("Off") },
                        )
                    }
                }

                OptionsTab.BOOKMARKS -> {
                    SheetTitle("Bookmarks")
                    OutlinedButton(
                        onClick = onAddBookmark,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Bookmark at ${formatDuration(state.positionMs)}")
                    }
                    Spacer(Modifier.height(8.dp))
                    if (bookmarks.isEmpty()) {
                        Text(
                            text = "No bookmarks yet. Add one here, or tap the bookmark button while recording.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Column(
                            Modifier
                                .heightIn(max = 280.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            bookmarks.forEach { position ->
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    TextButton(
                                        onClick = {
                                            onJumpBookmark(position)
                                            onDismiss()
                                        },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(
                                            Icons.Filled.Bookmark,
                                            contentDescription = null,
                                            tint = accents.warning,
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = formatDuration(position),
                                            style = MonoSmall,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    IconButton(onClick = { onRemoveBookmark(position) }) {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = "Delete bookmark at ${formatDuration(position)}",
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 10.dp),
    )
}

internal fun speedLabel(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}.0×" else "$speed×"
