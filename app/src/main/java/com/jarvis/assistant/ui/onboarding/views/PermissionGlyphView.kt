package com.jarvis.assistant.ui.onboarding.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.jarvis.assistant.onboarding.PermissionKey
import com.jarvis.assistant.ui.onboarding.Palette
import com.jarvis.assistant.ui.onboarding.dp
import com.jarvis.assistant.ui.onboarding.withAlpha
import kotlin.math.abs
import kotlin.math.sin

/** A small looping illustration of what a permission is for. One drawing routine per permission. */
class PermissionGlyphView(context: Context, private val key: PermissionKey) : LoopingView(context, 2_600L) {

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(dp(64), dp(64))
    }

    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height).toFloat()
        if (s <= 0f) return
        stroke.strokeWidth = s * 0.05f
        canvas.save()
        canvas.translate((width - s) / 2f, (height - s) / 2f)
        when (key) {
            PermissionKey.MICROPHONE -> microphone(canvas, s)
            PermissionKey.NOTIFICATIONS -> bell(canvas, s)
            PermissionKey.CONTACTS -> contacts(canvas, s)
            PermissionKey.CAMERA -> camera(canvas, s)
            PermissionKey.PHONE -> phone(canvas, s)
            PermissionKey.LOCATION -> location(canvas, s)
            PermissionKey.MEDIA -> media(canvas, s)
        }
        canvas.restore()
    }

    private fun microphone(c: Canvas, s: Float) {
        for (k in 0..2) {
            val p = (phase + k / 3f) % 1f
            stroke.color = withAlpha(Palette.CYAN, ((1f - p) * 150).toInt())
            c.drawCircle(s / 2, s / 2, s * (0.22f + 0.28f * p), stroke)
        }
        fill.color = Palette.CYAN
        rect.set(s * 0.41f, s * 0.24f, s * 0.59f, s * 0.58f)
        c.drawRoundRect(rect, s * 0.09f, s * 0.09f, fill)
        stroke.color = Palette.CYAN
        rect.set(s * 0.33f, s * 0.38f, s * 0.67f, s * 0.68f)
        c.drawArc(rect, 0f, 180f, false, stroke)
        c.drawLine(s / 2, s * 0.68f, s / 2, s * 0.78f, stroke)
        c.drawLine(s * 0.4f, s * 0.78f, s * 0.6f, s * 0.78f, stroke)
    }

    private fun bell(c: Canvas, s: Float) {
        val swing = 12f * sin(phase * TWO_PI * 2)
        c.save()
        c.rotate(swing, s / 2, s * 0.22f)
        path.reset()
        path.moveTo(s * 0.30f, s * 0.64f)
        path.cubicTo(s * 0.34f, s * 0.56f, s * 0.34f, s * 0.46f, s * 0.36f, s * 0.40f)
        path.cubicTo(s * 0.40f, s * 0.24f, s * 0.60f, s * 0.24f, s * 0.64f, s * 0.40f)
        path.cubicTo(s * 0.66f, s * 0.46f, s * 0.66f, s * 0.56f, s * 0.70f, s * 0.64f)
        path.close()
        fill.color = withAlpha(Palette.VIOLET, 210)
        c.drawPath(path, fill)
        fill.color = Palette.VIOLET
        c.drawCircle(s / 2, s * 0.70f, s * 0.05f, fill)
        c.restore()
        val pulse = 0.6f + 0.4f * abs(sin(phase * TWO_PI))
        fill.color = withAlpha(Palette.ROSE, 255)
        c.drawCircle(s * 0.68f, s * 0.30f, s * 0.07f * pulse + s * 0.02f, fill)
    }

    private fun contacts(c: Canvas, s: Float) {
        val active = ((phase * 3).toInt()) % 3
        for (i in 0..2) {
            val y = s * (0.26f + i * 0.2f)
            val hot = i == active
            rect.set(s * 0.14f, y, s * 0.86f, y + s * 0.15f)
            fill.color = if (hot) withAlpha(Palette.CYAN, 70) else withAlpha(Palette.TEXT, 22)
            c.drawRoundRect(rect, s * 0.07f, s * 0.07f, fill)
            fill.color = if (hot) Palette.CYAN else withAlpha(Palette.TEXT_DIM, 200)
            c.drawCircle(s * 0.23f, y + s * 0.075f, s * 0.045f, fill)
            rect.set(s * 0.33f, y + s * 0.055f, s * (0.55f + 0.08f * i), y + s * 0.095f)
            c.drawRoundRect(rect, s * 0.02f, s * 0.02f, fill)
        }
    }

    private fun camera(c: Canvas, s: Float) {
        val flash = 0.5f + 0.5f * sin(phase * TWO_PI * 2)
        path.reset()
        path.moveTo(s * 0.50f, s * 0.46f)
        path.lineTo(s * 0.86f, s * 0.20f)
        path.lineTo(s * 0.86f, s * 0.72f)
        path.close()
        fill.color = withAlpha(Palette.AMBER, (flash * 80).toInt())
        c.drawPath(path, fill)
        rect.set(s * 0.14f, s * 0.34f, s * 0.60f, s * 0.68f)
        stroke.color = Palette.CYAN
        c.drawRoundRect(rect, s * 0.07f, s * 0.07f, stroke)
        fill.color = withAlpha(Palette.CYAN, 90 + (flash * 120).toInt())
        c.drawCircle(s * 0.37f, s * 0.51f, s * 0.09f, fill)
    }

    private fun phone(c: Canvas, s: Float) {
        for (k in 0..1) {
            val p = (phase + k * 0.5f) % 1f
            stroke.color = withAlpha(Palette.MINT, ((1f - p) * 160).toInt())
            rect.set(s * (0.62f - 0.12f * p), s * (0.16f - 0.12f * p), s * (0.86f + 0.12f * p), s * (0.40f + 0.12f * p))
            c.drawArc(rect, -90f, 90f, false, stroke)
        }
        c.save()
        c.rotate(-30f, s / 2, s / 2)
        rect.set(s * 0.40f, s * 0.22f, s * 0.60f, s * 0.78f)
        fill.color = Palette.MINT
        c.drawRoundRect(rect, s * 0.09f, s * 0.09f, fill)
        c.restore()
    }

    private fun location(c: Canvas, s: Float) {
        val bob = -s * 0.04f * abs(sin(phase * TWO_PI))
        val ripple = phase
        stroke.color = withAlpha(Palette.BLUE, ((1f - ripple) * 170).toInt())
        rect.set(s * (0.50f - 0.28f * ripple), s * (0.78f - 0.07f * ripple), s * (0.50f + 0.28f * ripple), s * (0.78f + 0.07f * ripple))
        c.drawOval(rect, stroke)
        c.save()
        c.translate(0f, bob)
        path.reset()
        path.moveTo(s * 0.50f, s * 0.78f)
        path.cubicTo(s * 0.26f, s * 0.54f, s * 0.30f, s * 0.20f, s * 0.50f, s * 0.20f)
        path.cubicTo(s * 0.70f, s * 0.20f, s * 0.74f, s * 0.54f, s * 0.50f, s * 0.78f)
        path.close()
        fill.color = Palette.ROSE
        c.drawPath(path, fill)
        fill.color = Palette.BG
        c.drawCircle(s * 0.50f, s * 0.42f, s * 0.07f, fill)
        c.restore()
    }

    private fun media(c: Canvas, s: Float) {
        val hot = ((phase * 4).toInt()) % 4
        val colors = intArrayOf(Palette.CYAN, Palette.VIOLET, Palette.BLUE, Palette.MINT)
        for (i in 0..3) {
            val col = i % 2
            val row = i / 2
            val left = s * (0.18f + col * 0.34f)
            val top = s * (0.18f + row * 0.34f)
            rect.set(left, top, left + s * 0.30f, top + s * 0.30f)
            fill.color = withAlpha(colors[i], if (i == hot) 255 else 110)
            c.drawRoundRect(rect, s * 0.06f, s * 0.06f, fill)
        }
    }
}
