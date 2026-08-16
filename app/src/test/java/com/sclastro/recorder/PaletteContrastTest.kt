package com.sclastro.recorder

import com.sclastro.recorder.ui.theme.Palette
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The dark palette was written but never seen on a device, and there is no
 * emulator here to look at it on. Contrast is the part of "does dark mode
 * work" that can be checked without eyes, so it is checked here: every
 * foreground colour against the ground it actually sits on.
 *
 * WCAG AA is 4.5:1 for body text and 3:1 for large text and UI shapes. Faint
 * colours are held to the 3:1 line because they are only ever used for
 * hairlines and disabled labels, never for something that has to be read.
 */
class PaletteContrastTest {

    private fun luminance(argb: Long): Double {
        fun channel(shift: Int): Double {
            val v = ((argb shr shift) and 0xFF).toDouble() / 255.0
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    private fun contrast(foreground: Long, background: Long): Double {
        val a = luminance(foreground)
        val b = luminance(background)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    private fun assertContrast(name: String, foreground: Long, background: Long, minimum: Double) {
        val ratio = contrast(foreground, background)
        assertTrue(
            "$name is %.2f:1, needs %.1f:1".format(ratio, minimum),
            ratio >= minimum,
        )
    }

    private val body = 4.5
    private val large = 3.0

    // Colour objects hold an unsigned long; the low 32 bits are the ARGB value.
    private fun rgb(color: androidx.compose.ui.graphics.Color): Long = color.value.toLong() ushr 32

    @Test
    fun `light text on its grounds`() {
        assertContrast("Ink on Cream", rgb(Palette.Ink), rgb(Palette.Cream), body)
        assertContrast("Ink on Paper", rgb(Palette.Ink), rgb(Palette.Paper), body)
        assertContrast("InkMuted on Cream", rgb(Palette.InkMuted), rgb(Palette.Cream), body)
        assertContrast("InkMuted on Paper", rgb(Palette.InkMuted), rgb(Palette.Paper), body)
    }

    @Test
    fun `dark text on its grounds`() {
        assertContrast("Chalk on Night", rgb(Palette.Chalk), rgb(Palette.Night), body)
        assertContrast("Chalk on NightPaper", rgb(Palette.Chalk), rgb(Palette.NightPaper), body)
        assertContrast("ChalkMuted on Night", rgb(Palette.ChalkMuted), rgb(Palette.Night), body)
        assertContrast("ChalkMuted on NightPaper", rgb(Palette.ChalkMuted), rgb(Palette.NightPaper), body)
    }

    @Test
    fun `accents read as shapes in light`() {
        assertContrast("Ember on Cream", rgb(Palette.Ember), rgb(Palette.Cream), large)
        assertContrast("Sage on Cream", rgb(Palette.Sage), rgb(Palette.Cream), large)
        assertContrast("Ochre on Cream", rgb(Palette.Ochre), rgb(Palette.Cream), large)
        assertContrast("Crimson on Cream", rgb(Palette.Crimson), rgb(Palette.Cream), large)
    }

    @Test
    fun `accents read as shapes in dark`() {
        assertContrast("EmberNight on Night", rgb(Palette.EmberNight), rgb(Palette.Night), large)
        assertContrast("SageNight on Night", rgb(Palette.SageNight), rgb(Palette.Night), large)
        assertContrast("OchreNight on Night", rgb(Palette.OchreNight), rgb(Palette.Night), large)
        assertContrast("CrimsonNight on Night", rgb(Palette.CrimsonNight), rgb(Palette.Night), large)
        assertContrast("EmberNight on NightPaper", rgb(Palette.EmberNight), rgb(Palette.NightPaper), large)
        assertContrast("SageNight on NightPaper", rgb(Palette.SageNight), rgb(Palette.NightPaper), large)
    }

    @Test
    fun `white on the record button is legible in both themes`() {
        val white = 0xFFFFFFL
        assertContrast("white on Ember", white, rgb(Palette.Ember), large)
        assertContrast("white on Sage", white, rgb(Palette.Sage), large)
        // The dark accents are light enough that white would vanish on them —
        // the button uses dark ink there instead, so that is what is checked.
        assertContrast("Night on EmberNight", rgb(Palette.Night), rgb(Palette.EmberNight), body)
        assertContrast("Night on SageNight", rgb(Palette.Night), rgb(Palette.SageNight), body)
    }

    @Test
    fun `clipping stays an escalation, not more of the same`() {
        // The whole point of the crimson is that it reads as a step beyond the
        // record red rather than a shade of it.
        assertTrue(
            "Crimson must be clearly darker than Ember",
            luminance(rgb(Palette.Crimson)) < luminance(rgb(Palette.Ember)) * 0.6,
        )
    }

    @Test
    fun `hairlines are visible without shouting`() {
        // Not text, so no AA floor applies; they only have to be distinguishable.
        assertTrue(
            "light hairline must differ from its ground",
            contrast(rgb(Palette.OutlineSoft), rgb(Palette.Cream)) > 1.05,
        )
        assertTrue(
            "dark hairline must differ from its ground",
            contrast(rgb(Palette.NightOutline), rgb(Palette.Night)) > 1.05,
        )
    }
}
