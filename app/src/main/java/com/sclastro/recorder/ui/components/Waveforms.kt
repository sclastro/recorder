package com.sclastro.recorder.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sclastro.recorder.audio.Peaks
import com.sclastro.recorder.ui.theme.LocalAccents
import com.sclastro.recorder.util.formatDuration
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The scrolling meter shown while recording: newest sample on the right, older
 * samples fading out to the left.
 */
@Composable
fun LiveWaveform(
    levels: FloatArray,
    active: Boolean,
    modifier: Modifier = Modifier,
    barWidth: Dp = 3.dp,
    gap: Dp = 2.dp,
) {
    val accents = LocalAccents.current
    val description = if (active) "Live input waveform" else "Input waveform, idle"
    Canvas(
        modifier
            .fillMaxWidth()
            .height(96.dp)
            .semantics { contentDescription = description },
    ) {
        if (levels.isEmpty()) return@Canvas
        val barPx = barWidth.toPx()
        val gapPx = gap.toPx()
        val step = barPx + gapPx
        val visible = (size.width / step).toInt().coerceAtLeast(1)
        val centerY = size.height / 2f
        val start = max(0, levels.size - visible)

        for (i in start until levels.size) {
            val index = i - start
            val x = size.width - (levels.size - i) * step + barPx / 2f
            if (x < 0) continue
            val age = index.toFloat() / visible.coerceAtLeast(1)
            val amplitude = levels[i].coerceIn(0f, 1f)
            val half = max(barPx / 2f, amplitude * (size.height / 2f - 2f))
            val color = if (active) {
                accents.record.copy(alpha = 0.35f + 0.65f * age)
            } else {
                accents.waveformIdle.copy(alpha = 0.5f)
            }
            drawLine(
                color = color,
                start = Offset(x, centerY - half),
                end = Offset(x, centerY + half),
                strokeWidth = barPx,
                cap = StrokeCap.Round,
            )
        }

        drawLine(
            color = accents.waveformIdle.copy(alpha = 0.5f),
            start = Offset(0f, centerY),
            end = Offset(size.width, centerY),
            strokeWidth = 1f,
        )
    }
}

/**
 * Compact waveform for list rows; draws whatever peak data is already cached.
 * [progress] fills the played portion, so a row can show how far through the
 * recording the listener got. Decorative to TalkBack — the row's own text
 * already says everything this conveys.
 */
@Composable
fun MiniWaveform(
    peaks: ByteArray?,
    modifier: Modifier = Modifier,
    columns: Int = 44,
    progress: Float = 0f,
    progressColor: Color? = null,
) {
    val accents = LocalAccents.current
    val played = progressColor ?: accents.playback
    val samples = remember(peaks, columns) {
        peaks?.let { Peaks.resample(it, columns) } ?: FloatArray(columns) { 0.18f }
    }
    Canvas(modifier.clearAndSetSemantics { }) {
        if (samples.isEmpty()) return@Canvas
        val step = size.width / samples.size
        val barWidth = (step * 0.55f).coerceAtLeast(1.2f)
        val centerY = size.height / 2f
        samples.forEachIndexed { i, value ->
            val x = i * step + step / 2f
            val half = max(barWidth / 2f, value * (size.height / 2f))
            val fraction = (i + 0.5f) / samples.size
            drawLine(
                color = if (fraction <= progress) played else accents.waveformIdle,
                start = Offset(x, centerY - half),
                end = Offset(x, centerY + half),
                strokeWidth = barWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * A thin bar spanning the recording so far, with a tick per bookmark.
 *
 * The live waveform only holds about two seconds, so it could never answer
 * "where did I put them?" — after the count in the status line said "4
 * bookmarks added" there was nowhere to see the four.
 */
@Composable
fun BookmarkStrip(
    bookmarksMs: List<Long>,
    elapsedMs: Long,
    modifier: Modifier = Modifier,
) {
    val accents = LocalAccents.current
    val sunk = MaterialTheme.colorScheme.surfaceContainerHighest
    val description = if (bookmarksMs.size == 1) {
        "1 bookmark at ${formatDuration(bookmarksMs.first())}"
    } else {
        "${bookmarksMs.size} bookmarks"
    }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(18.dp)
            .semantics { contentDescription = description },
    ) {
        val radius = size.height / 2f
        drawRoundRect(
            color = sunk,
            cornerRadius = CornerRadius(radius, radius),
        )
        if (elapsedMs <= 0) return@Canvas
        bookmarksMs.forEach { at ->
            val x = (at.toFloat() / elapsedMs).coerceIn(0f, 1f) * size.width
            drawLine(
                color = accents.warning,
                start = Offset(x.coerceIn(1f, size.width - 1f), 2f),
                end = Offset(x.coerceIn(1f, size.width - 1f), size.height - 2f),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * Full-width waveform with a scrub head, and optionally a trim selection with
 * draggable edges.
 */
@Composable
fun WaveformScrubber(
    peaks: ByteArray?,
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 128.dp,
    selection: ClosedFloatingPointRange<Float>? = null,
    onSeek: ((Float) -> Unit)? = null,
    onSelectionChange: ((ClosedFloatingPointRange<Float>) -> Unit)? = null,
    bookmarks: List<Float> = emptyList(),
    /** A-B repeat range, drawn as a tinted band with edge markers. */
    loop: ClosedFloatingPointRange<Float>? = null,
    /** What TalkBack announces; callers know the real times, this only has fractions. */
    label: String = "Waveform",
) {
    val accents = LocalAccents.current
    val animatedProgress by animateFloatAsState(progress, tween(120), label = "progress")

    // Gesture lambdas outlive the composition that created them. Without these
    // they keep reading the selection captured when the pointer input was first
    // installed, so moving one handle snaps the other back to where it started.
    val currentSelection by rememberUpdatedState(selection)
    val currentOnSeek by rememberUpdatedState(onSeek)
    val currentOnSelectionChange by rememberUpdatedState(onSelectionChange)

    // Which handle this drag grabbed; -1 start, 1 end, 0 none. Chosen once at
    // drag start so the handle does not swap mid-gesture.
    var dragTarget by remember { mutableIntStateOf(0) }

    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    currentOnSeek?.invoke((offset.x / size.width).coerceIn(0f, 1f))
                }
            }
            // Horizontal only, so a vertically scrolling parent still works.
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { start ->
                        val active = currentSelection
                        dragTarget = if (active == null) {
                            0
                        } else {
                            val fraction = (start.x / size.width).coerceIn(0f, 1f)
                            if (abs(fraction - active.start) <= abs(fraction - active.endInclusive)) -1 else 1
                        }
                    },
                    onDragEnd = { dragTarget = 0 },
                    onDragCancel = { dragTarget = 0 },
                ) { change, _ ->
                    val fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                    val active = currentSelection
                    if (active == null) {
                        currentOnSeek?.invoke(fraction)
                    } else if (dragTarget < 0) {
                        val limit = (active.endInclusive - MIN_SELECTION).coerceIn(0f, 1f)
                        currentOnSelectionChange?.invoke(fraction.coerceIn(0f, limit)..active.endInclusive)
                    } else {
                        val limit = (active.start + MIN_SELECTION).coerceIn(0f, 1f)
                        currentOnSelectionChange?.invoke(active.start..fraction.coerceIn(limit, 1f))
                    }
                    change.consume()
                }
            }
            // Dragging a canvas is unreachable with TalkBack on, so expose the
            // position as a range and let the seek action move it.
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(progress.coerceIn(0f, 1f), 0f..1f)
                if (onSeek != null && selection == null) {
                    setProgress { target ->
                        onSeek(target.coerceIn(0f, 1f))
                        true
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val columns = (size.width / 4.dp.toPx()).roundToInt().coerceIn(16, 600)
            val samples = peaks?.let { Peaks.resample(it, columns) } ?: FloatArray(columns) { 0.12f }
            val step = size.width / columns
            val barWidth = (step * 0.6f).coerceAtLeast(1.5f)
            val centerY = size.height / 2f

            samples.forEachIndexed { i, value ->
                val x = i * step + step / 2f
                val fraction = (i + 0.5f) / columns
                val half = max(barWidth / 2f, value * (size.height / 2f - 4f))
                val inSelection = selection == null || fraction in selection
                val played = fraction <= animatedProgress
                val color = when {
                    !inSelection -> accents.waveformIdle.copy(alpha = 0.35f)
                    played -> accents.playback
                    else -> accents.waveformIdle
                }
                drawLine(
                    color = color,
                    start = Offset(x, centerY - half),
                    end = Offset(x, centerY + half),
                    strokeWidth = barWidth,
                    cap = StrokeCap.Round,
                )
            }

            if (loop != null) {
                val from = loop.start.coerceIn(0f, 1f) * size.width
                val to = loop.endInclusive.coerceIn(0f, 1f) * size.width
                drawRect(
                    color = accents.playback.copy(alpha = 0.14f),
                    topLeft = Offset(from, 0f),
                    size = Size((to - from).coerceAtLeast(1f), size.height),
                )
                listOf(from, to).forEach { x ->
                    drawLine(
                        color = accents.playback,
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = 2.dp.toPx(),
                    )
                }
            }

            bookmarks.forEach { fraction ->
                val x = fraction.coerceIn(0f, 1f) * size.width
                drawLine(
                    color = accents.warning,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 1.5.dp.toPx(),
                )
            }

            if (selection != null) {
                listOf(selection.start, selection.endInclusive).forEachIndexed { index, fraction ->
                    val x = fraction * size.width
                    drawRect(
                        color = accents.record,
                        topLeft = Offset(x - 1.5.dp.toPx(), 0f),
                        size = Size(3.dp.toPx(), size.height),
                    )
                    val knobY = if (index == 0) size.height * 0.5f else size.height * 0.5f
                    drawCircle(
                        color = accents.record,
                        radius = 7.dp.toPx(),
                        center = Offset(x, knobY),
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 2.5.dp.toPx(),
                        center = Offset(x, knobY),
                    )
                }
            } else {
                val x = animatedProgress.coerceIn(0f, 1f) * size.width
                drawLine(
                    brush = Brush.verticalGradient(
                        listOf(accents.playback, accents.playback.copy(alpha = 0.6f)),
                    ),
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 2.dp.toPx(),
                )
                drawCircle(color = accents.playback, radius = 5.dp.toPx(), center = Offset(x, size.height / 2f))
            }
        }
    }
}

private const val MIN_SELECTION = 0.005f
