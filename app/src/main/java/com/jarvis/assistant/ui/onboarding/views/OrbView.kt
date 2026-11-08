package com.jarvis.assistant.ui.onboarding.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.jarvis.assistant.ui.onboarding.Palette
import com.jarvis.assistant.ui.onboarding.dp
import com.jarvis.assistant.ui.onboarding.withAlpha
import kotlin.math.cos
import kotlin.math.sin

/** The JARVIS orb: glowing core, tilted orbit rings and orbiting sparks. [mode] changes how energetic it looks. */
class OrbView(context: Context) : LoopingView(context, 9_000L) {

    enum class Mode { IDLE, LISTENING, SPEAKING }

    var mode: Mode = Mode.IDLE
        set(value) {
            field = value
            invalidate()
        }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()

    private var radius = 0f

    /** rx, ry as fractions of the radius, tilt in degrees, revolutions per loop, colour. */
    private class Ring(val rx: Float, val ry: Float, val tilt: Float, val turns: Int, val color: Int)

    private val rings = listOf(
        Ring(1.55f, 0.52f, 20f, 1, Palette.CYAN),
        Ring(1.45f, 0.46f, -38f, -2, Palette.VIOLET),
        Ring(1.30f, 0.40f, 72f, 3, Palette.BLUE)
    )

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        radius = minOf(w, h) / 2f * 0.40f
        if (radius <= 0f) return
        glowPaint.shader = RadialGradient(
            0f, 0f, radius * 2.4f,
            intArrayOf(withAlpha(Palette.CYAN, 120), withAlpha(Palette.VIOLET, 50), withAlpha(Palette.BLUE, 0)),
            floatArrayOf(0.2f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        corePaint.shader = RadialGradient(
            -radius * 0.3f, -radius * 0.35f, radius * 1.4f,
            intArrayOf(0xFFE9FCFF.toInt(), Palette.CYAN, Palette.BLUE, Palette.VIOLET, 0xFF0B1330.toInt()),
            floatArrayOf(0f, 0.25f, 0.55f, 0.8f, 1f), Shader.TileMode.CLAMP
        )
        ringPaint.strokeWidth = dp(1).toFloat() * 1.4f
    }

    override fun onDraw(canvas: Canvas) {
        if (radius <= 0f) return
        val t = phase * TWO_PI
        val energy = when (mode) {
            Mode.IDLE -> 0.3f
            Mode.LISTENING -> 0.6f + 0.2f * sin(t * 9)
            Mode.SPEAKING -> 0.85f + 0.15f * sin(t * 14)
        }
        val breath = 1f + 0.03f * sin(t * 3) + 0.05f * energy * sin(t * 7)

        canvas.save()
        canvas.translate(width / 2f, height / 2f)

        glowPaint.alpha = (110 + 145 * energy).toInt().coerceIn(0, 255)
        canvas.drawCircle(0f, 0f, radius * 2.4f * (0.92f + 0.08f * energy), glowPaint)

        canvas.save()
        canvas.scale(breath, breath)
        canvas.drawCircle(0f, 0f, radius, corePaint)
        canvas.restore()

        for (ring in rings) {
            canvas.save()
            canvas.rotate(ring.tilt)
            oval.set(-radius * ring.rx, -radius * ring.ry, radius * ring.rx, radius * ring.ry)
            ringPaint.color = withAlpha(ring.color, (70 + 130 * energy).toInt().coerceIn(0, 255))
            canvas.drawOval(oval, ringPaint)
            val angle = t * ring.turns
            val sx = radius * ring.rx * cos(angle)
            val sy = radius * ring.ry * sin(angle)
            sparkPaint.color = withAlpha(ring.color, 255)
            canvas.drawCircle(sx, sy, dp(3).toFloat() * (0.8f + 0.5f * energy), sparkPaint)
            canvas.restore()
        }
        canvas.restore()
    }
}
