package com.sclastro.recorder.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** Accents that Material's own scheme has no slot for. */
data class RecorderAccents(
    val record: Color,
    val recordBright: Color,
    val recordDeep: Color,
    val recordWash: Color,
    val playback: Color,
    val playbackWash: Color,
    val warning: Color,
    val danger: Color,
    val waveform: Color,
    val waveformIdle: Color,
    val isLight: Boolean,
)

val LocalAccents = staticCompositionLocalOf {
    RecorderAccents(
        record = Palette.Stone,
        recordBright = Palette.StoneLight,
        recordDeep = Palette.StoneDeep,
        recordWash = Palette.StoneWash,
        playback = Palette.Sage,
        playbackWash = Palette.SageWash,
        warning = Palette.Ochre,
        danger = Palette.Brick,
        waveform = Palette.Stone,
        waveformIdle = Palette.OutlineFirm,
        isLight = true,
    )
}

private val LightScheme = lightColorScheme(
    primary = Palette.Stone,
    onPrimary = Color.White,
    primaryContainer = Palette.StoneWash,
    onPrimaryContainer = Palette.StoneDeep,
    secondary = Palette.Sage,
    onSecondary = Color.White,
    secondaryContainer = Palette.SageWash,
    onSecondaryContainer = Palette.Sage,
    background = Palette.Cream,
    onBackground = Palette.Ink,
    surface = Palette.Cream,
    onSurface = Palette.Ink,
    surfaceContainerLowest = Palette.Paper,
    surfaceContainerLow = Palette.Paper,
    surfaceContainer = Palette.PaperAlt,
    surfaceContainerHigh = Palette.CreamSunk,
    surfaceContainerHighest = Palette.CreamSunk,
    surfaceVariant = Palette.PaperAlt,
    onSurfaceVariant = Palette.InkMuted,
    outline = Palette.OutlineFirm,
    outlineVariant = Palette.OutlineSoft,
    error = Palette.Brick,
    onError = Color.White,
    errorContainer = Palette.BrickWash,
    onErrorContainer = Palette.Brick,
)

private val DarkScheme = darkColorScheme(
    primary = Palette.StoneNight,
    onPrimary = Color(0xFF2A1A12),
    primaryContainer = Palette.StoneNightWash,
    onPrimaryContainer = Palette.StoneNightLight,
    secondary = Palette.SageNight,
    onSecondary = Color(0xFF141C18),
    secondaryContainer = Palette.SageNightWash,
    onSecondaryContainer = Palette.SageNight,
    background = Palette.Night,
    onBackground = Palette.Chalk,
    surface = Palette.Night,
    onSurface = Palette.Chalk,
    surfaceContainerLowest = Palette.NightSunk,
    surfaceContainerLow = Palette.NightPaper,
    surfaceContainer = Palette.NightPaper,
    surfaceContainerHigh = Palette.NightPaperAlt,
    surfaceContainerHighest = Palette.NightPaperAlt,
    surfaceVariant = Palette.NightPaperAlt,
    onSurfaceVariant = Palette.ChalkMuted,
    outline = Palette.NightOutlineFirm,
    outlineVariant = Palette.NightOutline,
    error = Palette.BrickNight,
    onError = Color(0xFF33130F),
)

@Composable
fun RecorderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) DarkScheme else LightScheme
    val accents = if (darkTheme) {
        RecorderAccents(
            record = Palette.StoneNight,
            recordBright = Palette.StoneNightLight,
            recordDeep = Palette.StoneNightDeep,
            recordWash = Palette.StoneNightWash,
            playback = Palette.SageNight,
            playbackWash = Palette.SageNightWash,
            warning = Palette.OchreNight,
            danger = Palette.BrickNight,
            waveform = Palette.StoneNight,
            waveformIdle = Palette.NightOutlineFirm,
            isLight = false,
        )
    } else {
        RecorderAccents(
            record = Palette.Stone,
            recordBright = Palette.StoneLight,
            recordDeep = Palette.StoneDeep,
            recordWash = Palette.StoneWash,
            playback = Palette.Sage,
            playbackWash = Palette.SageWash,
            warning = Palette.Ochre,
            danger = Palette.Brick,
            waveform = Palette.Stone,
            waveformIdle = Palette.OutlineFirm,
            isLight = true,
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalAccents provides accents) {
        MaterialTheme(colorScheme = scheme, typography = RecorderTypography, content = content)
    }
}

private fun Context.findActivity(): Activity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
