package com.jarvis.assistant.ui.onboarding.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import com.jarvis.assistant.onboarding.StepState
import com.jarvis.assistant.ui.onboarding.Palette
import com.jarvis.assistant.ui.onboarding.dp
import com.jarvis.assistant.ui.onboarding.withAlpha
import kotlin.math.sin

/** Segmented progress rail: one segment per tutorial stage, the current one pulses. */
class StepRailView(context: Context) : LoopingView(context, 2_000L) {

    private var states: List<StepState> = emptyList()
    private var current = 0

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    fun update(newStates: List<StepState>, newCurrent: Int) {
        states = newStates
        current = newCurrent
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(dp(240), widthMeasureSpec), dp(10))
    }

    override fun onDraw(canvas: Canvas) {
        val n = states.size
        if (n == 0) return
        val gap = dp(4).toFloat()
        val segment = (width - gap * (n - 1)) / n
        val barHeight = dp(4).toFloat()
        val top = (height - barHeight) / 2f
        val pulse = 0.65f + 0.35f * sin(phase * TWO_PI)
        for (i in 0 until n) {
            val left = i * (segment + gap)
            rect.set(left, top, left + segment, top + barHeight)
            paint.shader = null
            when {
                i == current -> {
                    paint.shader = LinearGradient(left, 0f, left + segment, 0f, Palette.CYAN, Palette.VIOLET, Shader.TileMode.CLAMP)
                    paint.alpha = (255 * pulse).toInt()
                }
                states[i] == StepState.COMPLETED -> {
                    paint.shader = LinearGradient(left, 0f, left + segment, 0f, Palette.CYAN, Palette.BLUE, Shader.TileMode.CLAMP)
                    paint.alpha = 220
                }
                states[i] == StepState.SKIPPED -> paint.color = withAlpha(Palette.AMBER, 150)
                else -> paint.color = withAlpha(Color.WHITE, 40)
            }
            canvas.drawRoundRect(rect, barHeight / 2, barHeight / 2, paint)
        }
    }
}
