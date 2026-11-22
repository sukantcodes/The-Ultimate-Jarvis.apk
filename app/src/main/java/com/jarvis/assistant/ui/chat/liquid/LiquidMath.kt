package com.jarvis.assistant.ui.chat.liquid

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Pure maths behind the ChatBox liquid-glass motion. Nothing here touches Android, so it is covered by plain JVM unit tests.
 */
object LiquidMath {

    fun clamp01(v: Float): Float = if (v < 0f) 0f else if (v > 1f) 1f else v

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /**
     * A damped spring response over normalised time. Starts at 0, overshoots a little, and settles on exactly 1 at t = 1.
     * Used as an interpolator for the rise-and-settle of messages, so they land like a drop of liquid rather than stopping dead.
     */
    fun spring(t: Float, damping: Float = 0.62f, cycles: Float = 1.6f): Float {
        val x = clamp01(t)
        if (x >= 1f) return 1f
        val decay = exp(-damping * 9f * x)
        return 1f - decay * cos(2f * PI.toFloat() * cycles * x)
    }

    /** Eases out without overshoot (fast start, soft landing). */
    fun easeOutCubic(t: Float): Float {
        val x = 1f - clamp01(t)
        return 1f - x * x * x
    }

    /**
     * Vertical offset (-1..1) of bead [index] in the three-bead "thinking" wave at time [timeMs].
     * Each bead trails the previous one, so the row ripples like a drop travelling through liquid.
     */
    fun beadWave(timeMs: Float, index: Int, periodMs: Float = 1100f): Float {
        val phase = (timeMs / periodMs) * 2f * PI.toFloat() - index * 0.9f
        return sin(phase)
    }

    /** Bead radius multiplier (about 0.78..1.12): a bead is fullest when it is at the top of its wave. */
    fun beadScale(wave: Float): Float = 0.95f + 0.17f * wave

    /**
     * The position of a diagonal band of light as it crosses a surface, for [progress] in 0..1.
     * Returns 0..1 across the surface; eases so the glint accelerates into the pane and glides out.
     */
    fun glintPosition(progress: Float): Float {
        val p = clamp01(progress)
        return p * p * (3f - 2f * p)
    }

    /** How bright the glint is at [progress]: invisible at both ends, strongest in the middle. */
    fun glintAlpha(progress: Float): Float {
        val p = clamp01(progress)
        return sin(p * PI.toFloat()).coerceIn(0f, 1f)
    }

    /** Blur radius (px) used while a message comes into focus: starts soft and sharpens as [progress] reaches 1. */
    fun focusBlur(progress: Float, maxRadiusPx: Float): Float = maxRadiusPx * (1f - easeOutCubic(progress))
}
