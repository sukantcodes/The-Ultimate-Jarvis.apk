package com.jarvis.assistant.ui.glass

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.Choreographer
import androidx.core.graphics.ColorUtils
import com.jarvis.assistant.util.ThemeManager
import kotlin.math.max
import kotlin.math.sin

/**
 * A soft, slowly drifting field of coloured light shared by the Settings backdrop and every glass surface.
 *
 * The backdrop draws it in the open; each glass surface draws the very same field (magnified and boosted) in screen
 * coordinates, so the glass appears to bend whatever sits behind it, the way iOS liquid glass does.
 * All colours are derived from the active theme at draw time; nothing is hard-coded.
 */
object AuroraField {

    private class Blob(
        val cx: Float, val cy: Float,
        val ax: Float, val ay: Float,
        val speedX: Float, val speedY: Float,
        val phase: Float, val radius: Float,
        val hueShift: Float, val weight: Float
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    }

    private val blobs = listOf(
        Blob(0.18f, 0.10f, 0.10f, 0.05f, 0.31f, 0.23f, 0.0f, 0.62f, 0f, 1.00f),
        Blob(0.86f, 0.30f, 0.09f, 0.07f, 0.27f, 0.35f, 1.7f, 0.58f, 38f, 0.90f),
        Blob(0.22f, 0.58f, 0.12f, 0.06f, 0.21f, 0.29f, 3.1f, 0.66f, -34f, 0.85f),
        Blob(0.80f, 0.82f, 0.10f, 0.08f, 0.33f, 0.19f, 4.4f, 0.60f, 74f, 0.80f)
    )

    private var builtForAccent = 0
    private var builtForSecondary = 0
    private var builtForLight = false

    @Volatile var phase: Float = 0f

    private fun hueShifted(color: Int, shift: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[0] = (hsv[0] + shift + 360f) % 360f
        hsv[1] = (hsv[1] * 1.05f).coerceIn(0.35f, 1f)
        hsv[2] = hsv[2].coerceIn(0.55f, 1f)
        return Color.HSVToColor(hsv)
    }

    private fun rebuildIfNeeded(light: Boolean) {
        val accent = try { ThemeManager.getThemeAccentColor() } catch (_: Throwable) { 0xFF4FC3F7.toInt() }
        val secondary = try { ThemeManager.getThemeSecondaryColor() } catch (_: Throwable) { accent }
        if (accent == builtForAccent && secondary == builtForSecondary && light == builtForLight) return
        builtForAccent = accent
        builtForSecondary = secondary
        builtForLight = light
        blobs.forEachIndexed { i, blob ->
            val base = if (i % 2 == 0) accent else secondary
            val tone = hueShifted(base, blob.hueShift)
            val centerAlpha = ((if (light) 150 else 190) * blob.weight).toInt()
            blob.paint.shader = RadialGradient(
                0f, 0f, 1f,
                intArrayOf(
                    ColorUtils.setAlphaComponent(tone, centerAlpha),
                    ColorUtils.setAlphaComponent(tone, (centerAlpha * 0.42f).toInt()),
                    ColorUtils.setAlphaComponent(tone, 0)
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
        }
    }

    /**
     * Draws the field into a canvas whose origin is the top-left of a [width] x [height] area (screen sized).
     * [intensity] scales opacity so the backdrop stays calm while glass surfaces show it richer.
     */
    fun draw(canvas: Canvas, width: Float, height: Float, light: Boolean, intensity: Float) {
        rebuildIfNeeded(light)
        val extent = max(width, height)
        val t = phase
        val alpha = (255f * intensity).toInt().coerceIn(0, 255)
        blobs.forEach { b ->
            val x = width * (b.cx + b.ax * sin(t * b.speedX + b.phase))
            val y = height * (b.cy + b.ay * sin(t * b.speedY + b.phase * 1.3f))
            val r = extent * b.radius
            b.paint.alpha = alpha
            canvas.save()
            canvas.translate(x, y)
            canvas.scale(r, r)
            canvas.drawCircle(0f, 0f, 1f, b.paint)
            canvas.restore()
        }
    }
}

/**
 * One shared ~30 fps heartbeat for the aurora. It only runs while at least one glass surface or backdrop is attached,
 * and stays still when the system has animations switched off.
 */
object AuroraClock {
    private val listeners = LinkedHashSet<Runnable>()
    private var running = false
    private var last = 0L
    @Volatile var animationsEnabled: Boolean = true

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val now = SystemClock.uptimeMillis()
            if (now - last >= 33L) {
                val dt = if (last == 0L) 0.033f else (now - last) / 1000f
                last = now
                if (animationsEnabled) {
                    AuroraField.phase += dt * 0.55f
                    listeners.toList().forEach { it.run() }
                }
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun register(listener: Runnable) {
        listeners.add(listener)
        if (!running) {
            running = true
            last = 0L
            Choreographer.getInstance().postFrameCallback(callback)
        }
    }

    fun unregister(listener: Runnable) {
        listeners.remove(listener)
        if (listeners.isEmpty() && running) {
            running = false
            Choreographer.getInstance().removeFrameCallback(callback)
        }
    }
}
