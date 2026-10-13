package com.nihars.mnemos.core.design

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** nss colours that Material's ColorScheme has no slot for. */
@Immutable
data class NssColors(
    val ink: Color,
    val muted: Color,
    val line: Color,
    val ok: Color,
    val warn: Color,
    val error: Color,
    val grid: Color,
    val glow: Boolean,
    val isDark: Boolean,
    // Background of the logo badge (user setting; defaults to the accent).
    val logo: Color = Color(NssTokens.TEAL),
)

val LocalNssColors = staticCompositionLocalOf { nssColors(NssTokens.Dark, isDark = true) }

private fun Long.c() = Color(this)

private fun nssColors(p: NssPalette, isDark: Boolean) = NssColors(
    ink = p.ink.c(), muted = p.muted.c(), line = p.line.c(), ok = p.ok.c(), warn = p.warn.c(),
    error = p.error.c(), grid = p.grid.c(), glow = p.glow, isDark = isDark,
)

/** Maps nss tokens into Material 3; `primary` uses the text-safe ink so links and text buttons stay readable. */
private fun nssScheme(p: NssPalette, dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = p.ink.c(),
        onPrimary = Color(bestOn(p.ink)),
        primaryContainer = p.accent.c(),
        onPrimaryContainer = p.onAccent.c(),
        inversePrimary = p.accent.c(),
        secondary = p.accent2.c(),
        onSecondary = Color(bestOn(p.accent2)),
        secondaryContainer = p.surface2.c(),
        onSecondaryContainer = p.text.c(),
        tertiary = p.warn.c(),
        background = p.background.c(),
        onBackground = p.text.c(),
        surface = p.surface.c(),
        onSurface = p.text.c(),
        surfaceVariant = p.surface2.c(),
        onSurfaceVariant = p.muted.c(),
        surfaceTint = p.accent.c(),
        surfaceBright = p.surface2.c(),
        surfaceDim = p.background.c(),
        surfaceContainerLowest = p.background.c(),
        surfaceContainerLow = p.surface.c(),
        surfaceContainer = p.surface.c(),
        surfaceContainerHigh = p.surface2.c(),
        surfaceContainerHighest = p.surface2.c(),
        outline = p.muted.c(),
        outlineVariant = p.line.c(),
        error = p.error.c(),
        onError = if (dark) Color(0xFF2B0000) else Color.White,
    )
}

// Black or white, whichever reads better on [c].
private fun bestOn(c: Long): Long = if (NssTokens.contrast(0xFFFFFFFF, c) >= NssTokens.contrast(0xFF000000, c)) 0xFFFFFFFF else 0xFF000000

/** True when the user switched off animations system-wide (Developer options / Accessibility). */
fun systemAnimationsOff(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

@Composable
fun MnemosTheme(appearance: Appearance = Appearance(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val dark = when (appearance.theme) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    // Each theme keeps its own colour choices.
    val palette = remember(appearance, dark) {
        if (dark) NssTokens.custom(NssTokens.Dark, appearance.dark) else NssTokens.custom(NssTokens.Light, appearance.light)
    }
    val scheme = remember(palette, dark, appearance.dynamicAccent) {
        val nss = nssScheme(palette, dark)
        if (!appearance.dynamicAccent) {
            nss
        } else {
            // Material You: accent from the wallpaper, surfaces and text stay the user's.
            val dyn = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            nss.copy(
                primary = dyn.primary, onPrimary = dyn.onPrimary,
                primaryContainer = dyn.primaryContainer, onPrimaryContainer = dyn.onPrimaryContainer,
                secondary = dyn.secondary, onSecondary = dyn.onSecondary,
            )
        }
    }
    val motion = remember(appearance.motion) {
        Motion(if (systemAnimationsOff(context) && appearance.motion == MotionPref.FULL) MotionPref.REDUCED else appearance.motion)
    }
    // Same preset as the launcher icon; "transparent" shows the bare note page.
    val preset = LogoColors.presetFor(appearance.logoColor)
    val logo = if (preset.hex == LogoColors.TRANSPARENT) Color.Transparent else Color(NssTokens.parseHex(preset.hex) ?: NssTokens.TEAL)
    CompositionLocalProvider(
        LocalNssColors provides nssColors(palette, dark).copy(logo = logo),
        LocalMotion provides motion,
        LocalAppearance provides appearance,
    ) {
        MaterialTheme(colorScheme = scheme, typography = NssTypography, shapes = NssShapes) {
            // Screens drawn straight on the grid background (not inside a Surface) would otherwise
            // get Compose's default black text, unreadable in dark mode.
            CompositionLocalProvider(LocalContentColor provides scheme.onBackground, content = content)
        }
    }
}

val LocalAppearance = staticCompositionLocalOf { Appearance() }

object Nss {
    val colors: NssColors
        @Composable get() = LocalNssColors.current
    val motion: Motion
        @Composable get() = LocalMotion.current
}
