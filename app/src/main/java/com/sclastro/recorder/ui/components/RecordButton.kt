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
 * The record control: a lit orb that morphs from a circle into a rounded square
 * once capture starts.
 *
 * The depth is drawn rather than shaded by elevation alone — an off-centre
 * radial gradient for the body, a specular highlight above it, a darkened lower
 * rim and a lit upper rim. A halo behind the orb tracks the input level so you
 * can see signal without looking at the meter.
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

    val ringWidth = 3.dp
    val haloAlpha = if (recording) 0.10f + smoothedLevel * 0.5f else 0f

    Box(
        modifier = modifier
            .size(diameter)
            .semantics { contentDescription = if (recording) "停止錄音" else "開始錄音" },
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
            val breathAlpha = if (recording) 0.35f + 0.25f * breathe else 0.5f
            drawCircle(
                color = accents.record.copy(alpha = if (enabled) breathAlpha * 0.45f else 0.15f),
                radius = radius - ringWidth.toPx() / 2f,
                style = Stroke(width = ringWidth.toPx()),
            )
        }

        Box(
            modifier = Modifier
                .size(orbSize)
                .scale(if (pressed) 0.94f else 1f)
                .shadow(
                    elevation = if (pressed) 4.dp else 16.dp,
                    shape = shape,
                    spotColor = accents.recordDeep.copy(alpha = 0.75f),
                    ambientColor = accents.recordDeep.copy(alpha = 0.45f),
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

/** Body gradient, specular highlight, lit top rim and shaded bottom rim. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawOrb(
    bright: Color,
    base: Color,
    deep: Color,
    cornerPx: Float,
) {
    val w = size.width
    val h = size.height

    drawRoundRect(
        brush = Brush.radialGradient(
            colors = listOf(bright, base, deep),
            center = Offset(w * 0.34f, h * 0.26f),
            radius = min(w, h) * 1.05f,
        ),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerPx, cornerPx),
    )

    // Specular: a soft ellipse sitting just under the top-left rim.
    val specW = w * 0.62f
    val specH = h * 0.40f
    drawOval(
        brush = Brush.verticalGradient(
            colors = listOf(Color.White.copy(alpha = 0.42f), Color.White.copy(alpha = 0f)),
            startY = h * 0.06f,
            endY = h * 0.06f + specH,
        ),
        topLeft = Offset((w - specW) / 2f, h * 0.06f),
        size = Size(specW, specH),
    )

    // Rim: light above, shade below, so the edge reads as curved.
    val stroke = min(w, h) * 0.045f
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.55f),
                Color.White.copy(alpha = 0f),
                Color.Black.copy(alpha = 0.22f),
            ),
        ),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerPx, cornerPx),
        style = Stroke(width = stroke),
        topLeft = Offset(stroke / 2f, stroke / 2f),
        size = Size(w - stroke, h - stroke),
    )
}
