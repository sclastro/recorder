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
        record = Palette.Ember,
        recordBright = Palette.EmberLight,
        recordDeep = Palette.EmberDeep,
        recordWash = Palette.EmberWash,
        playback = Palette.Sage,
        playbackWash = Palette.SageWash,
        warning = Palette.Ochre,
        danger = Palette.Crimson,
        waveform = Palette.Ember,
        waveformIdle = Palette.OutlineFirm,
        isLight = true,
    )
}

private val LightScheme = lightColorScheme(
    primary = Palette.Ember,
    onPrimary = Color.White,
    primaryContainer = Palette.EmberWash,
    onPrimaryContainer = Palette.EmberDeep,
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
    error = Palette.Crimson,
    onError = Color.White,
    errorContainer = Palette.CrimsonWash,
    onErrorContainer = Palette.Crimson,
)

private val DarkScheme = darkColorScheme(
    primary = Palette.EmberNight,
    onPrimary = Color(0xFF2A1A12),
    primaryContainer = Palette.EmberNightWash,
    onPrimaryContainer = Palette.EmberNightLight,
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
    error = Palette.CrimsonNight,
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
            record = Palette.EmberNight,
            recordBright = Palette.EmberNightLight,
            recordDeep = Palette.EmberNightDeep,
            recordWash = Palette.EmberNightWash,
            playback = Palette.SageNight,
            playbackWash = Palette.SageNightWash,
            warning = Palette.OchreNight,
            danger = Palette.CrimsonNight,
            waveform = Palette.EmberNight,
            waveformIdle = Palette.NightOutlineFirm,
            isLight = false,
        )
    } else {
        RecorderAccents(
            record = Palette.Ember,
            recordBright = Palette.EmberLight,
            recordDeep = Palette.EmberDeep,
            recordWash = Palette.EmberWash,
            playback = Palette.Sage,
            playbackWash = Palette.SageWash,
            warning = Palette.Ochre,
            danger = Palette.Crimson,
            waveform = Palette.Ember,
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
