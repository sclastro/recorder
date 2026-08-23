package com.sclastro.recorder.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.sclastro.recorder.audio.PcmMath
import com.sclastro.recorder.ui.theme.LocalAccents
import com.sclastro.recorder.ui.theme.MonoSmall

/**
 * dBFS meter with a peak-hold marker. The last stretch turns red before 0 dB so
 * clipping is visible before it happens.
 */
@Composable
fun LevelMeter(
    peakDb: Float,
    rmsDb: Float,
    clipping: Boolean,
    modifier: Modifier = Modifier,
    /**
     * VOX threshold, in dBFS, or null when voice activation is off. Picking a
     * number in Settings with nothing to compare it against was guesswork —
     * marked on the meter you can see whether your voice actually clears it.
     */
    thresholdDb: Float? = null,
) {
    val accents = LocalAccents.current
    val rms by animateFloatAsState(PcmMath.dbToFraction(rmsDb), tween(70), label = "rms")

    // Peak-hold decays with wall-clock time, driven from a frame loop rather
    // than written during composition — a composition-time write would make the
    // decay rate depend on how often the screen happens to recompose, and could
    // keep re-triggering itself.
    val currentPeak by rememberUpdatedState(PcmMath.dbToFraction(peakDb))
    var peakHold by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var lastFrameMs = 0L
        while (true) {
            withFrameMillis { frameMs ->
                val elapsed = if (lastFrameMs == 0L) 0L else frameMs - lastFrameMs
                lastFrameMs = frameMs
                val decayed = (peakHold - PEAK_DECAY_PER_MS * elapsed).coerceAtLeast(0f)
                val next = maxOf(currentPeak, decayed)
                // Skip the write when nothing moved, so an idle meter does not
                // recompose every frame.
                if (next != peakHold) peakHold = next
            }
        }
    }

    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(10.dp),
        ) {
            val radius = CornerRadius(size.height / 2f, size.height / 2f)
            drawRoundRect(
                color = accents.waveformIdle.copy(alpha = 0.35f),
                cornerRadius = radius,
            )
            if (rms > 0.001f) {
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(accents.playback, accents.record, accents.danger),
                        startX = 0f,
                        endX = size.width,
                    ),
                    size = Size(size.width * rms, size.height),
                    cornerRadius = radius,
                )
            }
            if (peakHold > 0.001f) {
                val x = (size.width * peakHold).coerceIn(2f, size.width - 2f)
                drawRoundRect(
                    color = if (clipping) accents.danger else accents.record,
                    topLeft = Offset(x - 1.5f, 0f),
                    size = Size(3f, size.height),
                    cornerRadius = CornerRadius(1.5f, 1.5f),
                )
            }
            thresholdDb?.let { db ->
                val x = (size.width * PcmMath.dbToFraction(db)).coerceIn(1f, size.width - 1f)
                drawRoundRect(
                    color = accents.warning,
                    topLeft = Offset(x - 1f, -2f),
                    size = Size(2f, size.height + 4f),
                    cornerRadius = CornerRadius(1f, 1f),
                )
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("-60", style = MonoSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = when {
                    clipping -> "Clipping"
                    thresholdDb != null -> "Peak %.0f · VOX %.0f dB".format(peakDb, thresholdDb)
                    else -> "Peak %.0f dB".format(peakDb)
                },
                style = MonoSmall,
                color = if (clipping) accents.danger else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("0", style = MonoSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Full scale to silence in roughly four seconds. */
private const val PEAK_DECAY_PER_MS = 0.00025f
