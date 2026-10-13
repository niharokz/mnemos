package com.nihars.mnemos.core.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** JetBrains Mono, bundled (OFL) so the editor font works offline. */
val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

// Tabular figures everywhere so counts and times don't jiggle.
private const val TNUM = "tnum"

private fun style(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, fontFeatureSettings = TNUM)

/** Spec type scale: display 36/44, headline 24/32, title 18/24, body 15/22, label 12/16. */
val NssTypography = Typography(
    displayLarge = style(36, 44, FontWeight.SemiBold),
    displayMedium = style(36, 44, FontWeight.SemiBold),
    displaySmall = style(36, 44),
    headlineLarge = style(24, 32, FontWeight.SemiBold),
    headlineMedium = style(24, 32, FontWeight.SemiBold),
    headlineSmall = style(24, 32),
    titleLarge = style(18, 24, FontWeight.SemiBold),
    titleMedium = style(18, 24, FontWeight.Medium),
    titleSmall = style(15, 22, FontWeight.Medium),
    bodyLarge = style(15, 22),
    bodyMedium = style(15, 22),
    bodySmall = style(13, 18),
    labelLarge = style(14, 20, FontWeight.Medium),
    labelMedium = style(12, 16, FontWeight.Medium),
    labelSmall = style(12, 16),
)

/** 12 dp chips, 16 dp cards, 28 dp sheets and FAB. */
val NssShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
