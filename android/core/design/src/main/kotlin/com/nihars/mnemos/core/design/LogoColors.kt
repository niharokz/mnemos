package com.nihars.mnemos.core.design

/**
 * The logo colours on offer. Each one has a matching launcher icon (an activity-alias), so
 * changing the logo colour changes the home-screen icon too. Keep in step with
 * scripts/make-launcher-icons.py.
 */
object LogoColors {
    const val TRANSPARENT = "transparent"

    data class Preset(val key: String, val label: String, val hex: String)

    val presets = listOf(
        Preset("teal", "Teal", "#3a807a"),
        Preset("blue", "Blue", "#1d4ed8"),
        Preset("indigo", "Indigo", "#4f46e5"),
        Preset("violet", "Violet", "#7c3aed"),
        Preset("rose", "Rose", "#be185d"),
        Preset("red", "Red", "#dc2626"),
        Preset("orange", "Orange", "#ea580c"),
        Preset("green", "Green", "#2e7d32"),
        Preset("slate", "Slate", "#475569"),
        Preset("black", "Black", "#111827"),
        Preset("white", "White", "#ffffff"),
        Preset("transparent", "Transparent", TRANSPARENT),
    )

    /** The preset for a stored setting; empty or unknown values mean teal. */
    fun presetFor(value: String): Preset =
        presets.firstOrNull { it.hex.equals(value, ignoreCase = true) || it.key == value } ?: presets.first()
}
