package com.nihars.mnemos.core.design

/** nss colour tokens as plain ARGB values (no Compose here, so the contrast test runs on the JVM). */
data class NssPalette(
    val background: Long,
    val surface: Long,
    val surface2: Long,
    val line: Long,
    val text: Long,
    val muted: Long,
    val accent: Long,
    val ink: Long,
    val accent2: Long,
    val ok: Long,
    val warn: Long,
    val error: Long,
    val onAccent: Long,
    val grid: Long,
    val glow: Boolean,
)

object NssTokens {
    const val TEAL = 0xFF3A807AL

    val Dark = NssPalette(
        background = 0xFF070B0F, surface = 0xFF0F161B, surface2 = 0xFF152027, line = 0xFF1F2C33,
        text = 0xFFE6ECEC, muted = 0xFF8B979A, accent = TEAL, ink = 0xFF66BDB3, accent2 = 0xFFA9B8FF,
        ok = 0xFF3FB27F, warn = 0xFFE0A63A, error = 0xFFFF6B6B, onAccent = 0xFFFFFFFF,
        // rgba(58,128,122,.09)
        grid = 0x173A807A, glow = true,
    )

    val Light = NssPalette(
        background = 0xFFF6F8F8, surface = 0xFFFFFFFF, surface2 = 0xFFEAF1F0, line = 0xFFD7E2E0,
        text = 0xFF14201F, muted = 0xFF5C6B6A, accent = TEAL, ink = 0xFF2C6A64, accent2 = 0xFF4B5BB5,
        ok = 0xFF1F8A5A, warn = 0xFFA86A00, error = 0xFFC93434, onAccent = 0xFFFFFFFF,
        // error darkened from nss #d63c3c (4.0:1 on surface-2) to reach 4.5:1 for text.
        // rgba(58,128,122,.10)
        grid = 0x1A3A807A, glow = false,
    )


    /** WCAG relative luminance of an opaque ARGB colour. */
    fun luminance(argb: Long): Double {
        fun ch(v: Long): Double {
            val c = (v and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch(argb shr 16) + 0.7152 * ch(argb shr 8) + 0.0722 * ch(argb)
    }

    fun contrast(a: Long, b: Long): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /**
     * The nss [base] palette with the user's colour choices applied. Every derived colour is
     * checked for contrast, so no pick can make text unreadable: a text colour that fails 4.5:1 on
     * the background falls back to black or white, and the accent is darkened or lightened for text.
     */
    fun custom(base: NssPalette, colors: ThemeColors): NssPalette {
        if (colors.isDefault) return base
        val bg = parseHex(colors.background) ?: base.background
        val wantSurface = parseHex(colors.surface) ?: if (colors.background.isEmpty()) base.surface else mix(bg, base.text, 0.05)
        val dark = luminance(bg) < 0.18
        // Text must read on the background; if the pick doesn't, use black or white, whichever reads better.
        val wantText = parseHex(colors.text) ?: base.text
        val text = when {
            contrast(wantText, bg) >= 4.5 -> wantText
            contrast(0xFFFFFFFF, bg) >= contrast(0xFF000000, bg) -> 0xFFFFFFFF
            else -> 0xFF000000
        }
        // Cards must carry the same text, so a clashing surface is pulled toward the background.
        val surface = readableBackground(wantSurface, text, bg)
        val surface2 = readableBackground(mix(surface, text, 0.07), text, bg)
        val accent = parseHex(colors.accent) ?: base.accent
        val ink = readable(accent, listOf(bg, surface, surface2), toward = text)
        val muted = readable(mix(text, bg, 0.42), listOf(bg, surface, surface2), toward = text)
        val onAccent = if (contrast(0xFFFFFFFF, accent) >= contrast(0xFF000000, accent)) 0xFFFFFFFF else 0xFF000000
        return base.copy(
            background = bg,
            surface = surface,
            surface2 = surface2,
            line = mix(surface, text, 0.14),
            text = text,
            muted = muted,
            accent = accent,
            ink = ink,
            onAccent = onAccent,
            error = readable(base.error, listOf(bg, surface, surface2), toward = text),
            grid = (accent and 0x00FFFFFF) or (if (dark) 0x17000000 else 0x1A000000),
            glow = base.glow && colors.background.isEmpty(),
        )
    }

    /** Moves a background colour toward [fallback] until [text] reaches 4.5:1 on it. */
    private fun readableBackground(color: Long, text: Long, fallback: Long): Long {
        var c = color
        var t = 0.0
        while (contrast(text, c) < 4.5 && t < 1.0) {
            t += 0.08
            c = mix(color, fallback, minOf(t, 1.0))
        }
        return c
    }

    /** Moves [color] toward [toward] in small steps until it reaches 4.5:1 on every background. */
    fun readable(color: Long, backgrounds: List<Long>, toward: Long): Long {
        var c = color
        var t = 0.0
        while (backgrounds.any { contrast(c, it) < 4.5 } && t < 1.0) {
            t += 0.08
            c = mix(color, toward, minOf(t, 1.0))
        }
        return c
    }

    /** Linear blend of two opaque colours; [t] = 0 gives [a], 1 gives [b]. */
    fun mix(a: Long, b: Long, t: Double): Long {
        fun ch(v: Long, shift: Int) = ((v shr shift) and 0xFF).toDouble()
        fun blend(shift: Int) = (ch(a, shift) * (1 - t) + ch(b, shift) * t).toLong().coerceIn(0, 255)
        return 0xFF000000L or (blend(16) shl 16) or (blend(8) shl 8) or blend(0)
    }

    fun toHex(argb: Long): String = "#%06x".format(argb and 0xFFFFFF)

    /** Parses "#3a807a" / "3a807a"; null when invalid. */
    fun parseHex(hex: String): Long? {
        val h = hex.trim().removePrefix("#")
        if (h.length != 6 || h.any { it !in "0123456789abcdefABCDEF" }) return null
        return 0xFF000000L or h.toLong(16)
    }
}
