package com.nihars.mnemos.core.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec §9.1: text ≥ 4.5:1, icons and UI marks ≥ 3:1, in every theme — including user colour picks. */
class ContrastTest {
    private fun check(name: String, fg: Long, bg: Long, min: Double) {
        val c = NssTokens.contrast(fg, bg)
        assertTrue("$name contrast %.2f < %.1f".format(c, min), c >= min)
    }

    private fun checkText(theme: String, p: NssPalette) {
        for ((bgName, bg) in listOf("background" to p.background, "surface" to p.surface, "surface2" to p.surface2)) {
            check("$theme text/$bgName", p.text, bg, 4.5)
            check("$theme muted/$bgName", p.muted, bg, 4.5)
            check("$theme ink/$bgName", p.ink, bg, 4.5)
            check("$theme error/$bgName", p.error, bg, 4.5)
        }
        check("$theme onAccent/accent", p.onAccent, p.accent, 3.0)
    }

    @Test fun defaultThemesMeetContrast() {
        for ((theme, p) in listOf("dark" to NssTokens.Dark, "light" to NssTokens.Light)) {
            checkText(theme, p)
            for (bg in listOf(p.background, p.surface, p.surface2)) {
                check("$theme ok mark", p.ok, bg, 3.0)
                check("$theme warn mark", p.warn, bg, 3.0)
            }
        }
    }

    @Test fun defaultColorsLeavePaletteUnchanged() {
        assertEquals(NssTokens.Dark, NssTokens.custom(NssTokens.Dark, ThemeColors()))
    }

    @Test fun anyUserPickStaysReadable() {
        val rnd = java.util.Random(11)
        fun hex() = NssTokens.toHex(0xFF000000L or rnd.nextInt(0xFFFFFF).toLong())
        repeat(500) {
            val picks = ThemeColors(hex(), hex(), hex(), hex())
            checkText("random", NssTokens.custom(if (it % 2 == 0) NssTokens.Dark else NssTokens.Light, picks))
        }
        // The reported bug: black text picked on a dark background is replaced with white.
        val fixed = NssTokens.custom(NssTokens.Dark, ThemeColors(text = "#000000"))
        assertEquals(0xFFFFFFFFL, fixed.text)
    }
}
