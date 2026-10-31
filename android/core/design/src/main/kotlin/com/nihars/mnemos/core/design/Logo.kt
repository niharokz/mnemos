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
 * The Mnemos mark on a 24-unit grid, in the same family as Natlas and Median: a note page
 * outline with a folded corner, a bullet and three written lines. The launcher icons
 * (scripts/make-launcher-icons.py) and web icons (scripts/make-web-icons.py) use these shapes.
 */
object MnemosLogo {
    const val PAGE = "M7.31 4.31H13.88L17.44 7.88V19.13Q17.44 19.69 16.88 19.69H7.88Q7.31 19.69 7.31 19.13Z"
    const val FOLD = "M13.88 4.31L17.44 7.88H14.44Q13.88 7.88 13.88 7.31Z"
    const val LINE1 = "M12.28 11.63H14.81"
    const val LINE2 = "M10.22 14.34H14.81"
    const val LINE3 = "M10.22 17.06H12.75"
    const val DOT_X = 10.22f
    const val DOT_Y = 11.63f
    const val DOT_R = 0.84f
    const val STROKE = 1.31f

    /** Colours for one rendering of the mark. */
    data class Colors(val page: Color, val fold: Color, val dot: Color, val lines: Color)

    // nss palette, as on the Natlas and Median icons
    private val TEAL = Color(0xFF3A807A)
    private val MINT = Color(0xFF66BDB3)
    private val LILAC = Color(0xFFA9B8FF)
    private val INDIGO = Color(0xFF5B6FD6)

    /**
     * Mark colours for a badge: the nss palette on dark or transparent badges, deeper
     * tones on white, and white on every coloured badge (where teal would vanish).
     */
    fun colorsFor(badge: Color?): Colors {
        val argb = badge?.takeIf { it.alpha > 0.5f }?.toArgb()?.toLong()?.and(0xFFFFFFFFL)
            ?: return Colors(TEAL, LILAC, LILAC, MINT)
        return when {
            NssTokens.contrast(argb, 0xFF000000) < 2.0 -> Colors(TEAL, LILAC, LILAC, MINT) // dark
            NssTokens.contrast(argb, 0xFFFFFFFF) < 1.6 -> Colors(TEAL, INDIGO, INDIGO, TEAL) // white / very light
            else -> Colors(Color.White, Color.White.copy(alpha = 0.85f), Color.White, Color.White.copy(alpha = 0.92f))
        }
    }

    /** The page and fold; [lines] = false leaves the writing out (it is animated separately). */
    fun vector(c: Colors, lines: Boolean = true): ImageVector {
        val b = ImageVector.Builder(name = "mnemos", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        b.addPath(addPathNodes(PAGE), stroke = SolidColor(c.page), strokeLineWidth = STROKE, strokeLineJoin = StrokeJoin.Round)
        b.addPath(addPathNodes(FOLD), fill = SolidColor(c.fold), stroke = SolidColor(c.fold), strokeLineWidth = 0.56f, strokeLineJoin = StrokeJoin.Round)
        if (lines) addLines(b, c)
        return b.build()
    }

    /** Just the bullet and lines, for the writing animation. */
    fun linesVector(c: Colors): ImageVector {
        val b = ImageVector.Builder(name = "lines", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        addLines(b, c)
        return b.build()
    }

    private fun addLines(b: ImageVector.Builder, c: Colors) {
        val dot = "M${DOT_X - DOT_R} ${DOT_Y}a$DOT_R $DOT_R 0 1 0 ${2 * DOT_R} 0a$DOT_R $DOT_R 0 1 0 ${-2 * DOT_R} 0Z"
        b.addPath(addPathNodes(dot), fill = SolidColor(c.dot))
        for (d in listOf(LINE1, LINE2, LINE3)) {
            b.addPath(addPathNodes(d), stroke = SolidColor(c.lines), strokeLineWidth = STROKE, strokeLineCap = StrokeCap.Round)
        }
    }
}

/**
 * The logo in its badge. The badge colour is Settings → Appearance → Logo colour (the same
 * choice sets the launcher icon); "transparent" shows the bare page. With [animate] the page
 * settles in and the lines are written onto it.
 */
@Composable
fun LogoBadge(size: Dp, modifier: Modifier = Modifier, animate: Boolean = false) {
    val motion = Nss.motion
    val play = animate && !motion.off
    val badge = Nss.colors.logo
    val colors = remember(badge) { MnemosLogo.colorsFor(badge) }
    val page = remember(colors) { MnemosLogo.vector(colors, lines = false) }
    val lines = remember(colors) { MnemosLogo.linesVector(colors) }
    val pageIn = remember { Animatable(if (play) 0f else 1f) }
    val linesIn = remember { Animatable(if (play) 0f else 1f) }
    LaunchedEffect(play) {
        if (!play) return@LaunchedEffect
        pageIn.animateTo(1f, if (motion.full) tween(420, easing = MotionTokens.EmphasizedDecelerate) else tween(MotionTokens.SHORT))
        linesIn.animateTo(1f, if (motion.full) tween(420, easing = MotionTokens.EmphasizedDecelerate) else tween(MotionTokens.SHORT))
    }
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.22f))
            .background(badge)
            .semantics { contentDescription = "Mnemos" },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            page,
            contentDescription = null,
            modifier = Modifier.size(size).graphicsLayer {
                alpha = pageIn.value
                val s = 0.9f + 0.1f * pageIn.value
                scaleX = s
                scaleY = s
            },
        )
        Image(
            lines,
            contentDescription = null,
            modifier = Modifier.size(size).graphicsLayer {
                // The writing slides in from the left, like a line being written.
                translationX = -(1f - linesIn.value) * size.toPx() * 0.08f
                alpha = linesIn.value
            },
        )
    }
}
