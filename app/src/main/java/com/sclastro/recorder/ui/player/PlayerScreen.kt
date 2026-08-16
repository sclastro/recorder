package com.sclastro.recorder.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sclastro.recorder.data.Recording
import com.sclastro.recorder.ui.components.WaveformScrubber
import com.sclastro.recorder.ui.theme.LocalAccents
import com.sclastro.recorder.ui.theme.TimerLarge
import com.sclastro.recorder.util.formatDuration
import com.sclastro.recorder.util.formatDurationPrecise
import com.sclastro.recorder.util.formatSize
import com.sclastro.recorder.util.formatTimestamp

internal val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f)
internal val SLEEP_MINUTES = listOf(5, 15, 30, 60)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    recordingId: Long,
    onBack: () -> Unit,
    onEdit: (Recording) -> Unit,
    onShare: (Recording) -> Unit,
    viewModel: PlayerViewModel = viewModel(factory = PlayerViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val accents = LocalAccents.current
    var sheetTab by remember { mutableStateOf<OptionsTab?>(null) }
    LaunchedEffect(recordingId) { viewModel.load(recordingId) }

    val recording = state.recording

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = recording?.displayName ?: "Playback",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    recording?.let {
                        IconButton(onClick = { onShare(it) }) {
                            Icon(Icons.Filled.Share, contentDescription = "Share")
                        }
                        IconButton(onClick = { onEdit(it) }) {
                            Icon(Icons.Filled.ContentCut, contentDescription = "Trim")
                        }
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
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            recording?.let {
                Text(
                    text = "${formatTimestamp(it.createdAt)} · ${it.qualityLine()} · ${formatSize(it.sizeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))

            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(22.dp))
                    .padding(vertical = 18.dp, horizontal = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(formatDurationPrecise(state.positionMs), style = TimerLarge)
                Text(
                    text = "/ ${formatDuration(state.durationMs)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                if (state.loadingPeaks) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(128.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                } else {
                    WaveformScrubber(
                        peaks = state.peaks,
                        progress = state.progress,
                        onSeek = viewModel::seekToFraction,
                        bookmarks = recording?.bookmarks.orEmpty().mapNotNull { bookmark ->
                            state.durationMs.takeIf { it > 0 }?.let { bookmark.toFloat() / it }
                        },
                        label = "Waveform, ${formatDuration(state.positionMs)} of " +
                            formatDuration(state.durationMs),
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { viewModel.skip(-10_000) }, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Filled.Replay10, contentDescription = "Back 10 seconds")
                }
                FilledIconButton(
                    onClick = viewModel::togglePlay,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape),
                    // Red means record everywhere else in the app; playback is sage.
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = accents.playback,
                        contentColor = Color.White,
                    ),
                ) {
                    Icon(
                        imageVector = if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.playing) "Pause" else "Play",
                        modifier = Modifier.size(34.dp),
                    )
                }
                IconButton(onClick = { viewModel.skip(10_000) }, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Filled.Forward10, contentDescription = "Forward 10 seconds")
                }
            }

            Spacer(Modifier.height(18.dp))

            // One row of three, each opening the options sheet. Three separate
            // horizontally scrolling chip rows fought with the page's own
            // scrolling and pushed the notes field off the bottom.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = { sheetTab = OptionsTab.SPEED }, modifier = Modifier.weight(1f)) {
                    Text(speedLabel(state.speed), maxLines = 1)
                }
                OutlinedButton(onClick = { sheetTab = OptionsTab.SLEEP }, modifier = Modifier.weight(1f)) {
                    Text(
                        state.sleepTimerMinutes?.let { formatDuration(state.sleepTimerRemainingMs) } ?: "Sleep",
                        maxLines = 1,
                    )
                }
                OutlinedButton(onClick = { sheetTab = OptionsTab.BOOKMARKS }, modifier = Modifier.weight(1f)) {
                    val count = recording?.bookmarks.orEmpty().size
                    Text(if (count == 0) "Marks" else "Marks $count", maxLines = 1)
                }
            }

            Spacer(Modifier.height(20.dp))

            recording?.let {
                var note by remember(it.id) { mutableStateOf(it.note) }
                OutlinedTextField(
                    value = note,
                    onValueChange = { value ->
                        note = value
                        viewModel.setNote(value)
                    },
                    label = { Text("Notes (searchable)") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp),
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    sheetTab?.let { tab ->
        PlayerOptionsSheet(
            tab = tab,
            state = state,
            bookmarks = recording?.bookmarks.orEmpty(),
            onSpeed = viewModel::setSpeed,
            onSleep = viewModel::setSleepTimer,
            onAddBookmark = viewModel::addBookmarkHere,
            onJumpBookmark = viewModel::jumpToBookmark,
            onRemoveBookmark = viewModel::removeBookmark,
            onDismiss = { sheetTab = null },
        )
    }
}

enum class OptionsTab { SPEED, SLEEP, BOOKMARKS }
