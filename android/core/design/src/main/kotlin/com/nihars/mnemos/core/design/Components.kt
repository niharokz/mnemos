package com.nihars.mnemos.core.design

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Thin wrapper so every screen uses the same haptic vocabulary. */
class Haptics(private val perform: (HapticFeedbackType) -> Unit) {
    fun tick() = perform(HapticFeedbackType.SegmentTick)
    fun toggle() = perform(HapticFeedbackType.ToggleOn)
    fun confirm() = perform(HapticFeedbackType.Confirm)
    fun reject() = perform(HapticFeedbackType.Reject)
    fun threshold() = perform(HapticFeedbackType.GestureThresholdActivate)
}

@Composable
fun rememberHaptics(): Haptics {
    val h = LocalHapticFeedback.current
    return remember(h) { Haptics { h.performHapticFeedback(it) } }
}

/** The header mark: the logo badge and the word "Mnemos". [animate] plays the logo's writing animation once. */
@Composable
fun BrandMark(
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.headlineMedium,
    animate: Boolean = true,
) {
    val badge = with(LocalDensity.current) { (style.fontSize * 1.7f).toDp() }
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = "Mnemos" }, verticalAlignment = Alignment.CenterVertically) {
        LogoBadge(badge, animate = animate)
        Spacer(Modifier.width(badge * 0.36f))
        Text("Mnemos", style = style.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onBackground)
    }
}

/**
 * The status dot: amber when edited, pulsing while saving, a teal check that draws its stroke
 * when saved, red with one shake on conflict.
 */
@Composable
fun SaveDot(status: SaveStatus, modifier: Modifier = Modifier) {
    val c = Nss.colors
    val motion = Nss.motion
    val color by animateColorAsState(
        when (status) {
            SaveStatus.SAVED -> MaterialTheme.colorScheme.primary
            SaveStatus.EDITED, SaveStatus.SAVING -> c.warn
            SaveStatus.CONFLICT -> c.error
            SaveStatus.READ_ONLY -> c.muted
        },
        motion.enter(),
        label = "saveDotColor",
    )
    val pulse = rememberInfiniteTransition(label = "pulse")
    val pulseScale by pulse.animateFloat(
        1f, if (motion.full) 1.35f else 1f,
        infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulseScale",
    )
    val shake = remember { Animatable(0f) }
    LaunchedEffect(status) {
        if (status == SaveStatus.CONFLICT && motion.full) {
            for (x in listOf(4f, -4f, 3f, -3f, 0f)) shake.animateTo(x, tween(45))
        }
    }
    val check = remember { Animatable(0f) }
    LaunchedEffect(status) {
        if (status == SaveStatus.SAVED) {
            check.snapTo(if (motion.full) 0f else 1f)
            check.animateTo(1f, motion.enter(MotionTokens.MEDIUM))
        }
    }
    Box(
        modifier
            .size(18.dp)
            .graphicsLayer { translationX = shake.value * density }
            .semantics { contentDescription = status.label },
        contentAlignment = Alignment.Center,
    ) {
        if (status == SaveStatus.SAVED) {
            Canvas(Modifier.size(14.dp)) {
                val p = Path().apply {
                    moveTo(size.width * 0.15f, size.height * 0.55f)
                    lineTo(size.width * 0.42f, size.height * 0.8f)
                    lineTo(size.width * 0.88f, size.height * 0.22f)
                }
                // Draw only the first `check` fraction of the stroke.
                val measure = PathMeasure().apply { setPath(p, false) }
                val part = Path()
                measure.getSegment(0f, measure.length * check.value, part, true)
                drawPath(part, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
            }
        } else {
            Box(
                Modifier
                    .size(8.dp)
                    .scale(if (status == SaveStatus.SAVING) pulseScale else 1f)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

/** A Material banner that slides down with a spring; actions are TextButtons supplied by the caller. */
@Composable
fun NssBanner(
    visible: Boolean,
    icon: ImageVector,
    message: String,
    tone: Color,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit,
) {
    val motion = Nss.motion
    AnimatedVisibility(
        visible,
        enter = expandVertically(motion.spatial()) + motion.riseIn(),
        exit = shrinkVertically(motion.exit()) + motion.fadeOutQuick(),
        modifier = modifier,
    ) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, contentDescription = null, tint = tone)
                    Spacer(Modifier.width(12.dp))
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End, content = actions)
            }
        }
    }
}

/** PIN progress dots: springy fill, error tint, horizontal shake on a wrong PIN. */
@Composable
fun PinDots(length: Int, filled: Int, error: Boolean, shakeKey: Int, modifier: Modifier = Modifier) {
    val motion = Nss.motion
    val shake = remember { Animatable(0f) }
    LaunchedEffect(shakeKey) {
        if (shakeKey > 0 && motion.full) {
            // 3 cycles, 6 dp, 360 ms.
            repeat(3) {
                shake.animateTo(6f, tween(30))
                shake.animateTo(-6f, tween(60))
                shake.animateTo(0f, tween(30))
            }
        }
    }
    val tint = if (error) Nss.colors.error else MaterialTheme.colorScheme.primary
    Row(
        modifier.graphicsLayer { translationX = shake.value * density },
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        repeat(length) { i ->
            val on = i < filled
            val scale = remember { Animatable(1f) }
            LaunchedEffect(on) {
                if (on && motion.full) {
                    scale.snapTo(0.6f)
                    scale.animateTo(1.15f, tween(90))
                    scale.animateTo(1f, motion.playful())
                }
            }
            Box(
                Modifier
                    .size(14.dp)
                    .scale(scale.value)
                    .clip(CircleShape)
                    .background(if (on) tint else Color.Transparent)
                    .then(if (!on) Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape) else Modifier),
            )
        }
    }
}

@Composable
fun NssBadge(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 6.dp, vertical = 1.dp),
        color = color,
        style = MaterialTheme.typography.labelSmall,
    )
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: @Composable () -> Unit = {}) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, tint = Nss.colors.muted, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Nss.colors.muted)
        Spacer(Modifier.height(16.dp))
        action()
    }
}

/**
 * nss pull-to-refresh indicator: the `_` cursor stretches into a line as you pull, then spins.
 * [fraction] is the pull distance fraction (0..1+).
 */
@Composable
fun CursorRefreshIndicator(fraction: Float, refreshing: Boolean, modifier: Modifier = Modifier) {
    val ink = Nss.colors.ink
    val motion = Nss.motion
    val spin = rememberInfiniteTransition(label = "spin")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "angle")
    val visible = refreshing || fraction > 0f
    if (!visible) return
    Box(modifier.size(40.dp).padding(8.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(24.dp).rotate(if (refreshing && motion.full) angle else 0f)) {
            val w = if (refreshing) size.width else size.width * (0.25f + 0.75f * fraction.coerceIn(0f, 1f))
            val y = if (refreshing) size.height / 2 else size.height * 0.8f
            drawLine(ink, Offset((size.width - w) / 2, y), Offset((size.width + w) / 2, y), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

/**
 * Circular reveal from [origin] (fraction of the box, e.g. top-centre logo) over 420 ms.
 * Reduced motion: plain fade.
 */
@Composable
fun CircularReveal(origin: Offset = Offset(0.5f, 0.2f), modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val motion = Nss.motion
    val progress = remember { Animatable(if (motion.full) 0f else 1f) }
    val fade = remember { Animatable(if (motion.full || motion.off) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (motion.full) progress.animateTo(1f, tween(MotionTokens.LONG, easing = MotionTokens.EmphasizedDecelerate))
        if (!motion.full && !motion.off) fade.animateTo(1f, tween(MotionTokens.SHORT))
    }
    Box(
        modifier
            .graphicsLayer { alpha = fade.value }
            .drawWithContent {
                if (progress.value >= 1f) {
                    drawContent()
                } else {
                    val c = Offset(size.width * origin.x, size.height * origin.y)
                    val maxR = kotlin.math.hypot(maxOf(c.x, size.width - c.x), maxOf(c.y, size.height - c.y))
                    val path = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, maxR * progress.value)) }
                    clipPath(path) { this@drawWithContent.drawContent() }
                }
            },
    ) { content() }
}
