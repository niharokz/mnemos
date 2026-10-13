package com.nihars.mnemos.core.design

enum class ThemeMode { SYSTEM, DARK, LIGHT }

enum class MotionPref { FULL, REDUCED, OFF }

enum class UiDensity { COMFORTABLE, COMPACT }

/** User colour choices for one theme, as "#rrggbb"; empty means the nss default. */
data class ThemeColors(
    val background: String = "",
    val surface: String = "",
    val text: String = "",
    val accent: String = "",
) {
    val isDefault: Boolean get() = background.isEmpty() && surface.isEmpty() && text.isEmpty() && accent.isEmpty()
}

/** Everything the theme needs from Settings. Dark and light keep separate colour choices. */
data class Appearance(
    val theme: ThemeMode = ThemeMode.DARK,
    val dark: ThemeColors = ThemeColors(),
    val light: ThemeColors = ThemeColors(),
    // Material You: take the accent from the wallpaper instead of the chosen accent.
    val dynamicAccent: Boolean = false,
    // Background of the in-app logo badge; empty = the theme's accent.
    val logoColor: String = "",
    val grid: Boolean = true,
    val density: UiDensity = UiDensity.COMFORTABLE,
    val motion: MotionPref = MotionPref.FULL,
)
