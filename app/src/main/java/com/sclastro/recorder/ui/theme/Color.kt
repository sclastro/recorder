package com.sclastro.recorder.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Warm, paper-like light palette (the app's primary look) with a matching dark
 * set.
 *
 * The record accent is a soft red — the conventional "record" colour — kept
 * light enough to sit on the off-white ground without glaring. Clipping uses a
 * much deeper crimson so a peak warning still reads as an escalation rather
 * than more of the same colour.
 */
object Palette {
    // Light
    val Cream = Color(0xFFFAF9F5)
    val CreamSunk = Color(0xFFF2F0E9)
    val Paper = Color(0xFFFFFFFF)
    val PaperAlt = Color(0xFFF6F5EF)
    val OutlineSoft = Color(0xFFE9E6DC)
    val OutlineFirm = Color(0xFFDCD8CB)
    val Ink = Color(0xFF1F1E1D)
    val InkMuted = Color(0xFF6B6963)
    val InkFaint = Color(0xFF9A978E)

    /** Record accent. */
    val Ember = Color(0xFFD8453E)
    val EmberLight = Color(0xFFF08079)
    val EmberDeep = Color(0xFF9C2B26)
    val EmberWash = Color(0xFFFCE7E5)

    /** Playback accent — desaturated sage, so play never looks like record. */
    val Sage = Color(0xFF6D8278)
    val SageWash = Color(0xFFE7EDE9)

    val Ochre = Color(0xFF9E8154)

    /** Clipping / destructive. Deliberately darker than [Ember]. */
    val Crimson = Color(0xFF8C1D18)
    val CrimsonWash = Color(0xFFF6E2E1)

    // Dark
    val Night = Color(0xFF161614)
    val NightSunk = Color(0xFF101010)
    val NightPaper = Color(0xFF201F1D)
    val NightPaperAlt = Color(0xFF272623)
    val NightOutline = Color(0xFF35332F)
    val NightOutlineFirm = Color(0xFF474440)
    val Chalk = Color(0xFFF0EEE8)
    val ChalkMuted = Color(0xFFA8A49B)
    val ChalkFaint = Color(0xFF75726B)

    val EmberNight = Color(0xFFEE7A72)
    val EmberNightLight = Color(0xFFFBA49C)
    val EmberNightDeep = Color(0xFF96302B)
    val EmberNightWash = Color(0xFF3E1E1C)
    val SageNight = Color(0xFF8FA89C)
    val SageNightWash = Color(0xFF222B27)
    val OchreNight = Color(0xFFC4A579)
    val CrimsonNight = Color(0xFFFFB4AB)
}
