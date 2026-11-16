package com.jarvis.assistant.ui.glass

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A damped spring integrated with sub-stepped semi-implicit Euler (stable at any frame rate).
 * This is the physics behind the jelly squish of buttons and the springy radius of the liquid ripples:
 * a low damping ratio overshoots and wobbles like a drop of liquid, a high one settles without bouncing.
 */
internal class SpringF(
    var value: Float,
    var target: Float,
    private val stiffness: Float,
    private val damping: Float
) {
    var velocity = 0f

    fun step(dt: Float) {
        val steps = max(1, ceil(dt / MAX_SUBSTEP).toInt())
        val h = dt / steps
        repeat(steps) {
            val acceleration = -stiffness * (value - target) - damping * velocity
            velocity += acceleration * h
            value += velocity * h
        }
    }

    fun atRest(eps: Float = 0.0008f): Boolean = abs(value - target) < eps && abs(velocity) < eps * 8f

    companion object {
        private const val MAX_SUBSTEP = 0.004f

        /** Damping coefficient for a given stiffness and damping ratio (1 = critical, <1 = bouncy). */
        fun damping(stiffness: Float, ratio: Float): Float = 2f * sqrt(stiffness) * ratio
    }
}
