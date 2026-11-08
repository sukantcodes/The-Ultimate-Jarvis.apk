package com.jarvis.assistant.ui.onboarding.views

import android.animation.ValueAnimator
import android.content.Context
import android.view.View
import android.view.animation.LinearInterpolator
import com.jarvis.assistant.ui.onboarding.Motion

/**
 * Base for the tutorial's animated canvases. A single linear animator drives [phase] from 0 to 1.
 * It runs only while the view is attached, visible and animations are enabled; otherwise the view draws one still
 * frame, which also serves as the reduced-motion presentation.
 */
abstract class LoopingView(context: Context, private var periodMs: Long) : View(context) {

    /** Progress through the current loop, 0..1. The still frame uses [STILL_PHASE]. */
    protected var phase: Float = STILL_PHASE
        private set

    private var animator: ValueAnimator? = null

    /** True while the loop is actually animating. False means a still frame is being drawn. */
    protected val isLooping: Boolean get() = animator != null

    protected fun setLoopPeriod(ms: Long) {
        if (ms == periodMs) return
        periodMs = ms
        if (animator != null) {
            stopLoop()
            refreshRunning()
        }
    }

    private fun shouldRun(): Boolean =
        isAttachedToWindow && windowVisibility == VISIBLE && visibility == VISIBLE && Motion.enabled(context)

    private fun refreshRunning() {
        if (shouldRun()) startLoop() else stopLoop()
    }

    private fun startLoop() {
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = periodMs
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                onPhase(phase)
                invalidate()
            }
            start()
        }
    }

    private fun stopLoop() {
        animator?.cancel()
        animator = null
        if (phase != STILL_PHASE) {
            phase = STILL_PHASE
            onPhase(phase)
            invalidate()
        }
    }

    /** Hook for subclasses that report progress, such as the demo scene index. */
    protected open fun onPhase(phase: Float) {}

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refreshRunning()
    }

    override fun onDetachedFromWindow() {
        stopLoop()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        refreshRunning()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        refreshRunning()
    }

    protected companion object {
        const val STILL_PHASE = 0.42f
        const val TWO_PI = (Math.PI * 2).toFloat()
    }
}
