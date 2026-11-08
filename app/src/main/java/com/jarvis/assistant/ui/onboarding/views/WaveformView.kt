package com.jarvis.assistant.ui.onboarding.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import com.jarvis.assistant.ui.onboarding.Palette
import com.jarvis.assistant.ui.onboarding.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Decorative voice waveform. It is an animation, not a live microphone reading. */
class WaveformView(context: Context) : LoopingView(context, 2_400L) {

    /** 0 = calm conversation, 1 = sharp wake-word bursts, 2 = quiet, flat on-device listening. */
    var style: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    var active: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bar = RectF()
    private val bars = 30

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        paint.shader = LinearGradient(0f, 0f, w.toFloat(), 0f, Palette.CYAN, Palette.VIOLET, Shader.TileMode.CLAMP)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = resolveSize(dp(64), heightMeasureSpec)
        setMeasuredDimension(resolveSize(dp(240), widthMeasureSpec), h)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return
        val t = phase * TWO_PI
        val slot = w / bars
        val barWidth = slot * 0.5f
        for (i in 0 until bars) {
            val envelope = sin(PI.toFloat() * (i + 0.5f) / bars)
            val level = if (!active) 0.06f else when (style) {
                1 -> 0.15f + 0.85f * abs(sin(t * 5 + i * 0.9f)) * max(0f, cos(t * 2 + i * 0.2f))
                2 -> 0.10f + 0.18f * abs(sin(t * 2 + i * 0.5f))
                else -> 0.2f + 0.8f * abs(sin(t * 3 + i * 0.55f) * cos(t * 2 + i * 0.31f))
            }
            val barHeight = max(dp(3).toFloat(), level * envelope * h * 0.92f)
            val cx = slot * (i + 0.5f)
            bar.set(cx - barWidth / 2, (h - barHeight) / 2, cx + barWidth / 2, (h + barHeight) / 2)
            canvas.drawRoundRect(bar, barWidth / 2, barWidth / 2, paint)
        }
    }
}
