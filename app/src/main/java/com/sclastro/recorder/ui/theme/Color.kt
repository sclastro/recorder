package com.sclastro.recorder.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Warm, paper-like light palette (the app's primary look) with a matching dark
 * set. Everything is deliberately low-saturation: the accents read as material
 * — stone, sage — rather than as signal colours, so nothing competes with the
 * waveform for attention.
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

    /** Record accent — a muted terracotta, closer to clay than to orange. */
    val Stone = Color(0xFFA9705B)
    val StoneLight = Color(0xFFC99883)
    val StoneMid = Color(0xFFB98570)
    val StoneDeep = Color(0xFF79503F)
    val StoneWash = Color(0xFFF1E7E1)

    /** Playback accent — desaturated sage, distinct from the record colour. */
    val Sage = Color(0xFF6D8278)
    val SageWash = Color(0xFFE7EDE9)

    val Ochre = Color(0xFF9E8154)
    val Brick = Color(0xFFA2564A)
    val BrickWash = Color(0xFFF3E5E2)

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

    val StoneNight = Color(0xFFBE8B75)
    val StoneNightLight = Color(0xFFD3A794)
    val StoneNightDeep = Color(0xFF7A5140)
    val StoneNightWash = Color(0xFF322620)
    val SageNight = Color(0xFF8FA89C)
    val SageNightWash = Color(0xFF222B27)
    val OchreNight = Color(0xFFC4A579)
    val BrickNight = Color(0xFFC98D80)
}
