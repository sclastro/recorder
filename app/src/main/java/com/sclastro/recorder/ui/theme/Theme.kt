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
        record = Palette.Clay,
        recordBright = Palette.ClayBright,
        recordDeep = Palette.ClayDeep,
        recordWash = Palette.ClayWash,
        playback = Palette.Teal,
        playbackWash = Palette.TealWash,
        warning = Palette.Amber,
        danger = Palette.Danger,
        waveform = Palette.Clay,
        waveformIdle = Palette.OutlineFirm,
        isLight = true,
    )
}

private val LightScheme = lightColorScheme(
    primary = Palette.Clay,
    onPrimary = Color.White,
    primaryContainer = Palette.ClayWash,
    onPrimaryContainer = Palette.ClayDeep,
    secondary = Palette.Teal,
    onSecondary = Color.White,
    secondaryContainer = Palette.TealWash,
    onSecondaryContainer = Palette.Teal,
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
    error = Palette.Danger,
    onError = Color.White,
    errorContainer = Palette.DangerWash,
    onErrorContainer = Palette.Danger,
)

private val DarkScheme = darkColorScheme(
    primary = Palette.ClayNight,
    onPrimary = Color(0xFF2A140C),
    primaryContainer = Palette.ClayNightWash,
    onPrimaryContainer = Palette.ClayBright,
    secondary = Palette.TealNight,
    onSecondary = Color(0xFF0C1E1A),
    secondaryContainer = Palette.TealNightWash,
    onSecondaryContainer = Palette.TealNight,
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
    error = Color(0xFFE79086),
    onError = Color(0xFF3A0F0B),
)

@Composable
fun RecorderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) DarkScheme else LightScheme
    val accents = if (darkTheme) {
        RecorderAccents(
            record = Palette.ClayNight,
            recordBright = Color(0xFFF0A183),
            recordDeep = Color(0xFF8E3F26),
            recordWash = Palette.ClayNightWash,
            playback = Palette.TealNight,
            playbackWash = Palette.TealNightWash,
            warning = Color(0xFFE0B252),
            danger = Color(0xFFE79086),
            waveform = Palette.ClayNight,
            waveformIdle = Palette.NightOutlineFirm,
            isLight = false,
        )
    } else {
        RecorderAccents(
            record = Palette.Clay,
            recordBright = Palette.ClayBright,
            recordDeep = Palette.ClayDeep,
            recordWash = Palette.ClayWash,
            playback = Palette.Teal,
            playbackWash = Palette.TealWash,
            warning = Palette.Amber,
            danger = Palette.Danger,
            waveform = Palette.Clay,
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
