package com.nihars.mnemos.core.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** The nss backdrop: background colour, faint 32 dp square grid and (dark only) a soft teal glow top-right. */
fun Modifier.nssGrid(background: Color, grid: Color, glow: Boolean, showGrid: Boolean, cellPx: Float): Modifier =
    drawBehind {
        drawRect(background)
        if (showGrid) {
            var x = 0f
            while (x < size.width) {
                drawLine(grid, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                x += cellPx
            }
            var y = 0f
            while (y < size.height) {
                drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                y += cellPx
            }
        }
        if (glow) {
            val r = size.minDimension * 0.9f
            drawCircle(
                Brush.radialGradient(listOf(Color(0x333A807A), Color.Transparent), center = Offset(size.width, 0f), radius = r),
                radius = r,
                center = Offset(size.width, 0f),
            )
        }
    }

@Composable
fun GridBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val c = Nss.colors
    val showGrid = LocalAppearance.current.grid
    val cell = with(androidx.compose.ui.platform.LocalDensity.current) { 32.dp.toPx() }
    Box(
        modifier.fillMaxSize().nssGrid(MaterialTheme.colorScheme.background, c.grid, c.glow, showGrid, cell),
        content = content,
    )
}
