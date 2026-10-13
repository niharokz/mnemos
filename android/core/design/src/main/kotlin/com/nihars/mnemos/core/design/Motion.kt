package com.nihars.mnemos.core.design

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.IntOffset

/** Spec §9.3 durations and easings. */
object MotionTokens {
    // A little longer than Material's defaults: calmer, but never sluggish.
    const val SHORT = 180
    const val MEDIUM = 340
    const val LONG = 480
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
}

/**
 * Motion for the current setting. FULL: springs, scale and slides. REDUCED: short cross-fades
 * only. OFF: no animation at all.
 */
@Immutable
class Motion(val pref: MotionPref) {
    val full: Boolean get() = pref == MotionPref.FULL
    val off: Boolean get() = pref == MotionPref.OFF

    fun <T> enter(duration: Int = MotionTokens.MEDIUM): FiniteAnimationSpec<T> = when (pref) {
        MotionPref.FULL -> tween(duration, easing = MotionTokens.EmphasizedDecelerate)
        MotionPref.REDUCED -> tween(MotionTokens.SHORT, easing = MotionTokens.Standard)
        MotionPref.OFF -> snap()
    }

    fun <T> exit(duration: Int = MotionTokens.SHORT): FiniteAnimationSpec<T> = when (pref) {
        MotionPref.FULL -> tween(duration, easing = MotionTokens.EmphasizedAccelerate)
        MotionPref.REDUCED -> tween(MotionTokens.SHORT, easing = MotionTokens.Standard)
        MotionPref.OFF -> snap()
    }

    /** No-bounce spring for UI movement; a plain fade timing when reduced. */
    fun <T> spatial(): FiniteAnimationSpec<T> = when (pref) {
        MotionPref.FULL -> spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow)
        MotionPref.REDUCED -> tween(MotionTokens.SHORT)
        MotionPref.OFF -> snap()
    }

    /** Low-bounce spring for playful moments (PIN dots, save check). */
    fun <T> playful(): FiniteAnimationSpec<T> = when (pref) {
        MotionPref.FULL -> spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium)
        MotionPref.REDUCED -> tween(MotionTokens.SHORT)
        MotionPref.OFF -> snap()
    }

    /** Stagger delay for list items (20 ms each in full motion). */
    fun stagger(index: Int): Int = if (full) minOf(index, 10) * 24 else 0

    /** Shared-axis X (Edit ↔ Preview): a short glide with a soft fade; cross-fade when reduced. */
    fun sharedAxisX(forward: Boolean): ContentTransform {
        if (!full) return fadeIn(enter()) togetherWith fadeOut(exit())
        val dir = if (forward) 1 else -1
        val glide = tween<IntOffset>(MotionTokens.LONG, easing = MotionTokens.EmphasizedDecelerate)
        return (slideInHorizontally(glide) { dir * it / 12 } + fadeIn(tween(MotionTokens.MEDIUM, delayMillis = 60, easing = MotionTokens.Standard))) togetherWith
            (slideOutHorizontally(tween(MotionTokens.MEDIUM, easing = MotionTokens.EmphasizedAccelerate)) { -dir * it / 16 } + fadeOut(tween(MotionTokens.SHORT)))
    }

    /** Fade-through (tabs, views): the old fades out quickly, the new fades and settles from 97 %. */
    fun fadeThrough(): ContentTransform {
        if (!full) return fadeIn(enter()) togetherWith fadeOut(exit())
        return (fadeIn(tween(MotionTokens.MEDIUM, delayMillis = 70, easing = MotionTokens.Standard)) +
            scaleIn(tween(MotionTokens.MEDIUM, delayMillis = 70, easing = MotionTokens.EmphasizedDecelerate), initialScale = 0.97f)) togetherWith
            fadeOut(tween(110, easing = MotionTokens.EmphasizedAccelerate))
    }

    /** Fade + gentle rise for list items and sheets. */
    fun riseIn(delay: Int = 0): EnterTransition = if (!full) {
        fadeIn(enter())
    } else {
        fadeIn(tween(MotionTokens.LONG, delay, MotionTokens.Standard)) +
            slideInVertically(tween(MotionTokens.LONG, delay, MotionTokens.EmphasizedDecelerate)) { it / 10 }
    }

    fun fadeOutQuick(): ExitTransition = fadeOut(exit())
}

val LocalMotion = staticCompositionLocalOf { Motion(MotionPref.FULL) }
