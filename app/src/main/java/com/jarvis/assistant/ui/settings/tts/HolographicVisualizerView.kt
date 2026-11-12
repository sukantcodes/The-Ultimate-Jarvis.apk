package com.jarvis.assistant.ui.settings.tts

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.graphics.ColorUtils
import com.jarvis.assistant.util.ThemeManager
import kotlin.math.cos
import kotlin.math.sin

/**
 * Cybernetic Holographic Visualizer Orb.
 *
 * Renders a central glowing energy core with oscillating concentric orbits and audio-reactive
 * waveform bursts. Dynamically syncs with the user's selected app theme accent.
 */
class HolographicVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var accentColor: Int = ThemeManager.getThemeAccentColor()
    private var secondaryColor: Int = ThemeManager.getThemeSecondaryColor()

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val spikePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private var currentAmplitude: Float = 0f
    private var targetAmplitude: Float = 0f
    private var rotationAngle: Float = 0f

    private var rotationAnimator: ValueAnimator? = null

    init {
        updateThemeColors()
        startRotationAnimation()
    }

    fun updateThemeColors() {
        accentColor = try { ThemeManager.getThemeAccentColor() } catch (_: Exception) { Color.parseColor("#2F6BFF") }
        secondaryColor = try { ThemeManager.getThemeSecondaryColor() } catch (_: Exception) { Color.parseColor("#8B5CF6") }
        invalidate()
    }

    fun setAmplitude(amplitude: Float) {
        targetAmplitude = amplitude.coerceIn(0f, 1f)
        invalidate()
    }

    private fun startRotationAnimation() {
        rotationAnimator?.cancel()
        rotationAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 8000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                rotationAngle = it.animatedValue as Float
                // Smooth interpolation of amplitude
                currentAmplitude += (targetAmplitude - currentAmplitude) * 0.25f
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val baseRadius = minOf(cx, cy) * 0.55f
        if (baseRadius <= 0f) return

        val dynamicRadius = baseRadius * (1f + currentAmplitude * 0.45f)
        val density = resources.displayMetrics.density

        // 1. Draw ambient outer glow
        val outerGlowAlpha = (40 + (currentAmplitude * 110).toInt()).coerceIn(0, 255)
        corePaint.shader = RadialGradient(
            cx, cy, dynamicRadius * 1.5f,
            intArrayOf(
                ColorUtils.setAlphaComponent(accentColor, outerGlowAlpha),
                ColorUtils.setAlphaComponent(secondaryColor, outerGlowAlpha / 2),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, dynamicRadius * 1.5f, corePaint)

        // 2. Draw central plasma core
        val coreAlpha = (140 + (currentAmplitude * 100).toInt()).coerceIn(0, 255)
        corePaint.shader = RadialGradient(
            cx, cy, dynamicRadius * 0.75f,
            intArrayOf(
                ColorUtils.setAlphaComponent(accentColor, coreAlpha),
                ColorUtils.setAlphaComponent(secondaryColor, coreAlpha / 2),
                ColorUtils.setAlphaComponent(accentColor, 20)
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, dynamicRadius * 0.75f, corePaint)

        // 3. Draw concentric HUD orbital rings
        ringPaint.strokeWidth = 1.5f * density
        ringPaint.color = ColorUtils.setAlphaComponent(accentColor, 120 + (currentAmplitude * 120).toInt())
        canvas.drawCircle(cx, cy, dynamicRadius, ringPaint)

        ringPaint.strokeWidth = 1f * density
        ringPaint.color = ColorUtils.setAlphaComponent(secondaryColor, 80 + (currentAmplitude * 90).toInt())
        canvas.drawCircle(cx, cy, dynamicRadius * 0.5f, ringPaint)

        // 4. Draw audio-reactive peripheral spikes/rays
        val numSpikes = 24
        val angleStep = (2 * Math.PI / numSpikes).toFloat()
        spikePaint.strokeWidth = 2f * density

        for (i in 0 until numSpikes) {
            val angle = i * angleStep + Math.toRadians(rotationAngle.toDouble()).toFloat()
            val spikeLength = (10f * density) + (currentAmplitude * 28f * density) * ((i % 3 + 1) / 3f)

            val innerX = cx + (dynamicRadius + 4f * density) * cos(angle.toDouble()).toFloat()
            val innerY = cy + (dynamicRadius + 4f * density) * sin(angle.toDouble()).toFloat()

            val outerX = cx + (dynamicRadius + 4f * density + spikeLength) * cos(angle.toDouble()).toFloat()
            val outerY = cy + (dynamicRadius + 4f * density + spikeLength) * sin(angle.toDouble()).toFloat()

            val spikeColor = if (i % 2 == 0) accentColor else secondaryColor
            spikePaint.color = ColorUtils.setAlphaComponent(spikeColor, 160 + (currentAmplitude * 80).toInt())

            canvas.drawLine(innerX, innerY, outerX, outerY, spikePaint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        rotationAnimator?.cancel()
        rotationAnimator = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (rotationAnimator == null) {
            startRotationAnimation()
        }
    }
}
