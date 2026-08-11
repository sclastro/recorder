package com.sclastro.recorder.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sclastro.recorder.ui.theme.LocalAccents
import kotlin.math.min

/**
 * The record control: a disc that morphs from a circle into a rounded square
 * once capture starts.
 *
 * Depth is kept deliberately shallow — a gentle top-to-bottom gradient, a
 * one-pixel lit edge and a low soft shadow. Enough to read as a physical
 * button, not enough to look like a glass bead. A halo behind it tracks the
 * input level so signal is visible without watching the meter.
 */
@Composable
fun RecordButton(
    recording: Boolean,
    level: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 116.dp,
    enabled: Boolean = true,
) {
    val accents = LocalAccents.current
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val smoothedLevel by animateFloatAsState(
        targetValue = if (recording) level.coerceIn(0f, 1f) else 0f,
        animationSpec = tween(90, easing = LinearEasing),
        label = "level",
    )
    val breathe by rememberInfiniteTransition(label = "breathe").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Reverse),
        label = "breathePhase",
    )

    val orbSize by animateDpAsState(
        targetValue = if (recording) diameter * 0.52f else diameter * 0.82f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 420f),
        label = "orbSize",
    )
    val cornerFraction by animateFloatAsState(
        targetValue = if (recording) 0.28f else 0.5f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 380f),
        label = "corner",
    )
    val corner = orbSize * cornerFraction
    val shape = RoundedCornerShape(corner)

    val ringWidth = 2.dp
    val haloAlpha = if (recording) 0.08f + smoothedLevel * 0.34f else 0f

    Box(
        modifier = modifier
            .size(diameter)
            .semantics { contentDescription = if (recording) "Stop recording" else "Start recording" },
        contentAlignment = Alignment.Center,
    ) {
        // Level halo + the static outer ring.
        Canvas(Modifier.size(diameter)) {
            val radius = size.minDimension / 2f
            if (haloAlpha > 0.01f) {
                val reach = radius * (0.62f + smoothedLevel * 0.38f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            accents.record.copy(alpha = haloAlpha * 0.55f),
                            accents.record.copy(alpha = 0f),
                        ),
                        center = center,
                        radius = reach,
                    ),
                    radius = reach,
                )
            }
            val breathAlpha = if (recording) 0.30f + 0.18f * breathe else 0.38f
            drawCircle(
                color = accents.record.copy(alpha = if (enabled) breathAlpha * 0.55f else 0.14f),
                radius = radius - ringWidth.toPx() / 2f,
                style = Stroke(width = ringWidth.toPx()),
            )
        }

        Box(
            modifier = Modifier
                .size(orbSize)
                .scale(if (pressed) 0.96f else 1f)
                .shadow(
                    elevation = if (pressed) 1.dp else 5.dp,
                    shape = shape,
                    spotColor = accents.recordDeep.copy(alpha = 0.34f),
                    ambientColor = accents.recordDeep.copy(alpha = 0.18f),
                )
                .clip(shape)
                .drawBehind { drawOrb(accents.recordBright, accents.record, accents.recordDeep, corner.toPx()) }
                .clickable(
                    interactionSource = interaction,
                    indication = LocalIndication.current,
                    enabled = enabled,
                ) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                },
            contentAlignment = Alignment.Center,
        ) {
            if (!recording) {
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.95f),
                    modifier = Modifier.size(orbSize * 0.42f),
                )
            }
        }
    }
}

/** A shallow top-to-bottom gradient with a single lit top edge. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawOrb(
    bright: Color,
    base: Color,
    deep: Color,
    cornerPx: Float,
) {
    val corner = androidx.compose.ui.geometry.CornerRadius(cornerPx, cornerPx)

    // Only halfway to the light tone: a hint of curvature, not a sphere.
    drawRoundRect(
        brush = Brush.verticalGradient(colors = listOf(lerp(base, bright, 0.5f), base)),
        cornerRadius = corner,
    )

    // One hairline of light along the top edge — the whole of the relief.
    val stroke = min(size.width, size.height) * 0.018f
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.30f),
                Color.White.copy(alpha = 0f),
                deep.copy(alpha = 0.18f),
            ),
        ),
        cornerRadius = corner,
        style = Stroke(width = stroke),
        topLeft = Offset(stroke / 2f, stroke / 2f),
        size = Size(size.width - stroke, size.height - stroke),
    )
}
