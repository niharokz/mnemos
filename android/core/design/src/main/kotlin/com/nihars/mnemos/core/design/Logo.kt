package com.nihars.mnemos.core.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The Mnemos mark on a 24-unit grid: a note page with a folded corner, a brain drawn on it,
 * two written lines, and a pen whose tip is writing on the brain. Brain outline adapted from
 * Lucide (ISC). The launcher icons in app/src/main/res are generated from these same shapes.
 */
object MnemosLogo {
    const val PAGE = "M6 2.5h8.6l4.4 4.4v12.6a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-15a2 2 0 0 1 2-2z"
    const val FOLD = "M14.6 2.5v3.4a1 1 0 0 0 1 1h3.4"
    const val LINES = "M7 16.6h4.6M7 18.8h3"
    val BRAIN = listOf(
        "M12 5a3 3 0 1 0-5.997.125 4 4 0 0 0-2.526 5.77 4 4 0 0 0 .556 6.588A4 4 0 1 0 12 18Z",
        "M12 5a3 3 0 1 1 5.997.125 4 4 0 0 1 2.526 5.77 4 4 0 0 1-.556 6.588A4 4 0 1 1 12 18Z",
        "M15 13a4.5 4.5 0 0 1-3-4 4.5 4.5 0 0 1-3 4",
        "M17.599 6.5a3 3 0 0 0 .399-1.375",
        "M6.003 5.125A3 3 0 0 0 6.401 6.5",
        "M3.477 10.896a4 4 0 0 1 .585-.396",
        "M19.938 10.5a4 4 0 0 1 .585.396",
    )
    const val BRAIN_X = 5f
    const val BRAIN_Y = 4.3f
    const val BRAIN_SCALE = 0.5f

    // The pen, pointing up-left at 45°, tip at (12.76, 11.96) on the brain's lower right.
    const val PEN_BODY = "M15.132 14.332L20.719 19.919"
    const val PEN_NIB = "M13.506 14.332L12.764 11.964L15.132 12.706Z"
    const val PEN_BAND = "M18.951 19.777L20.577 18.151"
    const val PEN_WIDTH = 2.3f
    const val EDGE_WIDTH = 0.7f

    /** Colours for one rendering of the mark. */
    data class Colors(val paper: Color, val ink: Color, val edge: Color)

    /**
     * Picks mark colours for a badge colour. The brain and pen take the badge colour when it
     * reads on white paper; on white, very light or transparent badges they use nss teal.
     */
    fun colorsFor(badge: Color?): Colors {
        val argb = badge?.takeIf { it.alpha > 0.5f }?.toArgb()?.toLong()?.and(0xFFFFFFFFL)
        val ink = if (argb != null && NssTokens.contrast(argb, 0xFFFFFFFF) >= 3.0) argb else NssTokens.TEAL
        return Colors(paper = Color.White, ink = Color(ink), edge = Color(NssTokens.mix(ink, 0xFF000000, 0.62)))
    }

    /** Builds the mark as a vector; [pen] = false leaves the pen out (it is animated separately). */
    fun vector(c: Colors, pen: Boolean = true, linesAlpha: Float = 0.55f): ImageVector {
        val b = ImageVector.Builder(name = "mnemos", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        val edge = SolidColor(c.edge)
        val ink = SolidColor(c.ink)
        b.addPath(addPathNodes(PAGE), fill = SolidColor(c.paper), stroke = edge, strokeLineWidth = EDGE_WIDTH, strokeLineJoin = StrokeJoin.Round)
        b.addPath(addPathNodes(FOLD), stroke = edge, strokeLineWidth = EDGE_WIDTH, strokeLineJoin = StrokeJoin.Round)
        b.addGroup(scaleX = BRAIN_SCALE, scaleY = BRAIN_SCALE, translationX = BRAIN_X, translationY = BRAIN_Y)
        BRAIN.forEach { d ->
            b.addPath(addPathNodes(d), stroke = ink, strokeLineWidth = 2.4f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }
        b.clearGroup()
        b.addPath(addPathNodes(LINES), stroke = ink, strokeAlpha = linesAlpha, strokeLineWidth = 1.1f, strokeLineCap = StrokeCap.Round)
        if (pen) addPen(b, c)
        return b.build()
    }

    /** Just the pen, for the writing animation. */
    fun penVector(c: Colors): ImageVector {
        val b = ImageVector.Builder(name = "pen", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        addPen(b, c)
        return b.build()
    }

    private fun addPen(b: ImageVector.Builder, c: Colors) {
        val edge = SolidColor(c.edge)
        // Outline first (a slightly wider stroke), then the body on top of it.
        b.addPath(addPathNodes(PEN_BODY), stroke = edge, strokeLineWidth = PEN_WIDTH + 2 * EDGE_WIDTH, strokeLineCap = StrokeCap.Round)
        b.addPath(addPathNodes(PEN_BODY), stroke = SolidColor(c.ink), strokeLineWidth = PEN_WIDTH, strokeLineCap = StrokeCap.Round)
        b.addPath(addPathNodes(PEN_NIB), fill = SolidColor(c.ink), stroke = edge, strokeLineWidth = EDGE_WIDTH, strokeLineJoin = StrokeJoin.Round)
        b.addPath(addPathNodes(PEN_BAND), stroke = edge, strokeLineWidth = EDGE_WIDTH)
    }
}

/**
 * The logo in its badge. The badge colour is Settings → Appearance → Logo colour (the same
 * choice sets the launcher icon); "transparent" shows the bare note page. With [animate] the page
 * settles in and the pen glides down onto the brain.
 */
@Composable
fun LogoBadge(size: Dp, modifier: Modifier = Modifier, animate: Boolean = false) {
    val motion = Nss.motion
    val play = animate && !motion.off
    val badge = Nss.colors.logo
    val colors = remember(badge) { MnemosLogo.colorsFor(badge) }
    val page = remember(colors) { MnemosLogo.vector(colors, pen = false) }
    val pen = remember(colors) { MnemosLogo.penVector(colors) }
    val pageIn = remember { Animatable(if (play) 0f else 1f) }
    val penIn = remember { Animatable(if (play) 0f else 1f) }
    LaunchedEffect(play) {
        if (!play) return@LaunchedEffect
        pageIn.animateTo(1f, if (motion.full) tween(480, easing = MotionTokens.EmphasizedDecelerate) else tween(MotionTokens.SHORT))
        penIn.animateTo(1f, if (motion.full) tween(520, easing = MotionTokens.EmphasizedDecelerate) else tween(MotionTokens.SHORT))
    }
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.28f))
            .background(badge)
            .semantics { contentDescription = "Mnemos" },
        contentAlignment = Alignment.Center,
    ) {
        val inner = size * 0.8f
        Image(
            page,
            contentDescription = null,
            modifier = Modifier.size(inner).graphicsLayer {
                alpha = pageIn.value
                val s = 0.9f + 0.1f * pageIn.value
                scaleX = s
                scaleY = s
            },
        )
        Image(
            pen,
            contentDescription = null,
            modifier = Modifier.size(inner).graphicsLayer {
                // Glides in along its own axis (down-right to up-left) and lands on the brain.
                val off = (1f - penIn.value) * inner.toPx() * 0.18f
                translationX = off
                translationY = off
                alpha = penIn.value
            },
        )
    }
}
