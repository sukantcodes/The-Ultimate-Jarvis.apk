package com.jarvis.assistant.ui.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * Animated voice waveform view showing oscillating audio bars with amplitude reactive
 * to speech decibel levels (like ChatGPT / Claude / Gemini voice modes).
 */
class VoiceWaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val barCount = 5
    private val barRect = RectF()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#00E5FF")
    }

    private var rmsLevel: Float = 0f
    private var isAnimating = false
    private var phase: Float = 0f

    private val frameRunnable = object : Runnable {
        override fun run() {
            if (!isAnimating) return
            phase += 0.18f
            // Decay rms smoothly back to baseline
            rmsLevel = (rmsLevel * 0.85f).coerceAtLeast(0f)
            invalidate()
            postOnAnimation(this)
        }
    }

    fun start() {
        if (!isAnimating) {
            isAnimating = true
            postOnAnimation(frameRunnable)
        }
    }

    fun stop() {
        isAnimating = false
        rmsLevel = 0f
        removeCallbacks(frameRunnable)
        invalidate()
    }

    fun updateRms(rmsdB: Float) {
        // rmsdB typically ranges from -2 to 10
        val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        if (normalized > rmsLevel) {
            rmsLevel = normalized
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) start() else stop()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val barWidth = 3f * density
        val spacing = (w - (barCount * barWidth)) / (barCount + 1).coerceAtLeast(1)
        val minHeight = 4f * density
        val maxHeight = h * 0.85f

        val colors = intArrayOf(
            Color.parseColor("#00E5FF"),
            Color.parseColor("#29B6F6"),
            Color.parseColor("#448AFF"),
            Color.parseColor("#2979FF"),
            Color.parseColor("#00E5FF")
        )

        for (i in 0 until barCount) {
            val cx = spacing + i * (barWidth + spacing)
            // Sine modulation per bar + audio energy
            val sine = (sin(phase + i * 1.1) * 0.5 + 0.5).toFloat()
            val extraEnergy = rmsLevel * 0.7f
            val barH = minHeight + (maxHeight - minHeight) * (sine * 0.35f + extraEnergy).coerceIn(0f, 1f)

            val top = (h - barH) / 2f
            val bottom = top + barH

            barRect.set(cx, top, cx + barWidth, bottom)
            paint.color = colors[i % colors.size]
            canvas.drawRoundRect(barRect, barWidth / 2f, barWidth / 2f, paint)
        }
    }
}
