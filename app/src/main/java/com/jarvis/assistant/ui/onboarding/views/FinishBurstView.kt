package com.jarvis.assistant.ui.onboarding.views

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.jarvis.assistant.ui.onboarding.Motion
import com.jarvis.assistant.ui.onboarding.Palette
import com.jarvis.assistant.ui.onboarding.dp
import com.jarvis.assistant.ui.onboarding.withAlpha

/** One-shot completion animation: expanding rings and a check mark that draws itself. */
class FinishBurstView(context: Context) : View(context) {

    private var progress = 0f
    private var animator: ValueAnimator? = null

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Palette.MINT
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()
    private val checkPath = Path()
    private val segment = Path()

    /** Plays once and then calls [onDone]. With animations off it shows the finished frame and finishes at once. */
    fun play(onDone: () -> Unit) {
        animator?.cancel()
        if (!Motion.enabled(context)) {
            progress = 1f
            invalidate()
            postDelayed({ onDone() }, 500L)
            return
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2_200L
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) postDelayed({ onDone() }, 350L)
                }
            })
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) * 0.22f
        if (r <= 0f) return

        glowPaint.color = withAlpha(Palette.MINT, (60 * progress).toInt())
        canvas.drawCircle(cx, cy, r * (1.2f + 0.6f * progress), glowPaint)

        ringPaint.strokeWidth = dp(3).toFloat()
        ringPaint.color = withAlpha(Palette.MINT, 255)
        oval.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(oval, -90f, 360f * minOf(1f, progress * 1.6f), false, ringPaint)

        for (k in 0..2) {
            val p = ((progress * 1.2f) - k * 0.18f).coerceIn(0f, 1f)
            if (p <= 0f || p >= 1f) continue
            ringPaint.strokeWidth = dp(2).toFloat()
            ringPaint.color = withAlpha(Palette.CYAN, ((1f - p) * 160).toInt())
            canvas.drawCircle(cx, cy, r * (1f + 1.6f * p), ringPaint)
        }

        checkPath.reset()
        checkPath.moveTo(cx - r * 0.42f, cy + r * 0.02f)
        checkPath.lineTo(cx - r * 0.1f, cy + r * 0.34f)
        checkPath.lineTo(cx + r * 0.46f, cy - r * 0.30f)
        val measure = PathMeasure(checkPath, false)
        val drawn = ((progress - 0.35f) / 0.4f).coerceIn(0f, 1f)
        if (drawn > 0f) {
            segment.reset()
            measure.getSegment(0f, measure.length * drawn, segment, true)
            checkPaint.strokeWidth = dp(5).toFloat()
            canvas.drawPath(segment, checkPaint)
        }
    }
}
