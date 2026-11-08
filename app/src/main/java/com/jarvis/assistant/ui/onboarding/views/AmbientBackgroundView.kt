package com.jarvis.assistant.ui.onboarding.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import com.jarvis.assistant.ui.onboarding.Palette
import com.jarvis.assistant.ui.onboarding.dp
import com.jarvis.assistant.ui.onboarding.withAlpha
import kotlin.math.cos
import kotlin.math.sin

/**
 * Deep-space backdrop: a dark gradient, three slowly drifting colour glows and a few rising particles.
 * Gradients are built once per size change; each frame only translates them, so the cost stays low.
 */
class AmbientBackgroundView(context: Context) : LoopingView(context, 24_000L) {

    private class Glow(val color: Int, val cx: Float, val cy: Float, val radius: Float, val speed: Int, val offset: Float)

    private class Particle(val x: Float, val y: Float, val size: Float, val speed: Float, val twinkle: Float)

    private val basePaint = Paint()
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var glows: List<Glow> = emptyList()
    private var shaders: List<RadialGradient> = emptyList()
    private var particles: List<Particle> = emptyList()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        basePaint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), Palette.BG, Palette.BG_SOFT, Shader.TileMode.CLAMP)
        val r = maxOf(w, h) * 0.55f
        glows = listOf(
            Glow(Palette.CYAN, w * 0.15f, h * 0.18f, r, 1, 0f),
            Glow(Palette.VIOLET, w * 0.88f, h * 0.55f, r * 1.05f, 2, 1.7f),
            Glow(Palette.BLUE, w * 0.35f, h * 0.95f, r * 0.9f, 1, 3.4f)
        )
        shaders = glows.map {
            RadialGradient(0f, 0f, it.radius, withAlpha(it.color, 70), withAlpha(it.color, 0), Shader.TileMode.CLAMP)
        }
        // Deterministic pseudo-random layout so a rotation does not reshuffle the sky.
        val count = if (w * h > 1_500_000) 34 else 26
        particles = List(count) { i ->
            val a = ((i * 7919) % 1000) / 1000f
            val b = ((i * 104729) % 1000) / 1000f
            val c = ((i * 1299709) % 1000) / 1000f
            Particle(a * w, b * h, dp(1) + c * dp(2), 0.4f + c, i * 0.9f)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return
        canvas.drawRect(0f, 0f, w, h, basePaint)

        val t = phase * TWO_PI
        for (i in glows.indices) {
            val g = glows[i]
            val dx = (sin(t * g.speed + g.offset) * w * 0.06f)
            val dy = (cos(t * g.speed + g.offset) * h * 0.05f)
            canvas.save()
            canvas.translate(g.cx + dx, g.cy + dy)
            glowPaint.shader = shaders[i]
            canvas.drawCircle(0f, 0f, g.radius, glowPaint)
            canvas.restore()
        }

        for (p in particles) {
            val rise = (p.y - phase * h * p.speed * 0.6f)
            val y = ((rise % h) + h) % h
            val alpha = (80 + 70 * sin(t * 3 + p.twinkle)).toInt().coerceIn(20, 160)
            particlePaint.color = withAlpha(Palette.CYAN, alpha)
            canvas.drawCircle(p.x, y, p.size, particlePaint)
        }
    }
}
