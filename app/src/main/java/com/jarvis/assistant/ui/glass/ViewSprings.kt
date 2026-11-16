package com.jarvis.assistant.ui.glass

import android.os.SystemClock
import android.view.View

/**
 * Spring-driven scale for a view: interruptible (a new pop starts from the current value and speed, so quick
 * taps never jump) and frame-rate independent. Replaces fixed-duration interpolator animations for the dock icons.
 */
object ViewSprings {

    private const val KEY = 0x4A4C5350 // "JLSP"

    private class Runner(val view: View, from: Float, to: Float, k: Float, ratio: Float) : Runnable {
        val spring = SpringF(from, to, k, SpringF.damping(k, ratio))
        var last = SystemClock.uptimeMillis()

        override fun run() {
            val now = SystemClock.uptimeMillis()
            val dt = ((now - last) / 1000f).coerceIn(0.001f, 0.033f)
            last = now
            spring.step(dt)
            if (spring.atRest(0.0015f)) {
                view.scaleX = spring.target
                view.scaleY = spring.target
                view.setTag(KEY, null)
            } else {
                view.scaleX = spring.value
                view.scaleY = spring.value
                view.postOnAnimation(this)
            }
        }
    }

    /** Starts at [from] (or continues from the running spring's value) and settles on [to] with a soft overshoot. */
    fun pop(view: View, from: Float, to: Float, stiffness: Float = 420f, ratio: Float = 0.42f) {
        val running = view.getTag(KEY) as? Runner
        val start = running?.spring?.value ?: from
        val velocity = running?.spring?.velocity ?: 0f
        running?.let { view.removeCallbacks(it) }
        val runner = Runner(view, start, to, stiffness, ratio)
        runner.spring.velocity = velocity
        view.setTag(KEY, runner)
        view.scaleX = start
        view.scaleY = start
        view.postOnAnimation(runner)
    }

    fun cancel(view: View) {
        (view.getTag(KEY) as? Runner)?.let { view.removeCallbacks(it) }
        view.setTag(KEY, null)
    }
}
