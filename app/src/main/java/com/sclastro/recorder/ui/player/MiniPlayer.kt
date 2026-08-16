package com.sclastro.recorder.ui.player

import android.app.Application
import android.content.ComponentName
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.sclastro.recorder.AppContainer
import com.sclastro.recorder.service.PlaybackService
import com.sclastro.recorder.ui.containerViewModelFactory
import com.sclastro.recorder.ui.theme.LocalAccents
import com.sclastro.recorder.ui.theme.MonoSmall
import com.sclastro.recorder.util.formatDuration
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class MiniPlayerState(
    val recordingId: Long? = null,
    val title: String = "",
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
) {
    val visible: Boolean get() = recordingId != null
    val progress: Float
        get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

/**
 * A second, read-mostly view of the playback session. Media3 allows several
 * controllers on one session, so this can watch what the player screen started
 * without either owning the other.
 */
@OptIn(UnstableApi::class)
class MiniPlayerViewModel(
    container: AppContainer,
    application: Application,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(MiniPlayerState())
    val state: StateFlow<MiniPlayerState> = _state.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var ticker: Job? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = refresh()
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) = refresh()
        override fun onPlaybackStateChanged(playbackState: Int) = refresh()
    }

    init {
        val token = SessionToken(application, ComponentName(application, PlaybackService::class.java))
        val future = MediaController.Builder(application, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                val ready = runCatching { future.get() }.getOrNull() ?: return@addListener
                controller = ready
                ready.addListener(listener)
                refresh()
                ticker = viewModelScope.launch {
                    while (isActive) {
                        if (controller?.isPlaying == true) refresh()
                        delay(500)
                    }
                }
            },
            ContextCompat.getMainExecutor(application),
        )
    }

    private fun refresh() {
        val player = controller
        val item = player?.currentMediaItem
        if (player == null || item == null) {
            _state.value = MiniPlayerState()
            return
        }
        _state.value = MiniPlayerState(
            recordingId = item.mediaId.toLongOrNull(),
            title = item.mediaMetadata.title?.toString().orEmpty(),
            playing = player.isPlaying,
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = player.duration.takeIf { it > 0 } ?: 0,
        )
    }

    fun togglePlay() {
        val player = controller ?: return
        if (player.isPlaying) player.pause() else player.play()
    }

    /** Clears the session so the bar goes away. */
    fun dismiss() {
        controller?.run {
            pause()
            clearMediaItems()
        }
        _state.value = MiniPlayerState()
    }

    override fun onCleared() {
        ticker?.cancel()
        controller?.removeListener(listener)
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onCleared()
    }

    companion object {
        val Factory = containerViewModelFactory { container, app -> MiniPlayerViewModel(container, app) }
    }
}

/**
 * Shown above the navigation bar whenever something is loaded in the session.
 * Background playback without this meant audio could be running with no way to
 * stop it short of the notification shade.
 */
@Composable
fun MiniPlayerBar(
    onOpen: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MiniPlayerViewModel = viewModel(factory = MiniPlayerViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val accents = LocalAccents.current

    AnimatedVisibility(
        visible = state.visible,
        enter = expandVertically(),
        exit = shrinkVertically(),
        modifier = modifier,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLowest),
        ) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LinearProgressIndicator(
                progress = { state.progress },
                color = accents.playback,
                trackColor = accents.playbackWash,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                drawStopIndicator = {},
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { state.recordingId?.let(onOpen) }
                    .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = state.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${formatDuration(state.positionMs)} / ${formatDuration(state.durationMs)}",
                        style = MonoSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    IconButton(onClick = viewModel::togglePlay) {
                        Icon(
                            imageVector = if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (state.playing) "Pause" else "Play",
                            tint = accents.playback,
                        )
                    }
                }
                IconButton(onClick = viewModel::dismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close player")
                }
            }
        }
    }
}
