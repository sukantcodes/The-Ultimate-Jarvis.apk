package com.jarvis.assistant.ui.glass

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.view.Choreographer
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * The Crystal Mirror window: a clear, see-through mirror. A light frosted-blue veil lets the wallpaper show through, and
 * broad polished reflections cross it on a diagonal, with a bright caustic in the top corner, an ice-violet bloom low down
 * and a soft dark edge that gives the pane depth. Everything is built once per size from cached shaders, so drawing it is
 * a handful of rectangles and it never animates (no battery or frame cost).
 */
class CrystalMirrorDrawable : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true }
    private var veil: Shader? = null
    private var bands: List<Shader> = emptyList()
    private var blooms: List<Shader> = emptyList()
    private var edge: Shader? = null
    private var lensShader: Shader? = null

    // Slow shimmer: each reflection band glides along the diagonal at its own pace and the blooms breathe, like light moving
    // across a polished mirror. It runs at ~30 fps, only while the window is visible, pauses while anything scrolls, and
    // stops entirely if the system animation scale is off.
    private val matrix = Matrix()
    private var diag = 1f
    private var phase = 0f
    private var running = false
    private var lastFrameNs = 0L
    private var bloomAlpha = 255
    private val periods = floatArrayOf(17f, 11f, 23f, 29f) // seconds per band cycle
    private val amps = floatArrayOf(0.10f, 0.07f, 0.12f, 0.09f)
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            Choreographer.getInstance().postFrameCallback(this)
            if (frameTimeNanos - lastFrameNs < 33_000_000L) return
            val dt = if (lastFrameNs == 0L) 0f else (frameTimeNanos - lastFrameNs) / 1e9f
            lastFrameNs = frameTimeNanos
            if (AuroraClock.paused) return
            phase += dt
            update()
            invalidateSelf()
        }
    }

    private fun update() {
        val tau = (2.0 * Math.PI).toFloat()
        val dx = Math.cos(-0.43).toFloat()
        val dy = Math.sin(-0.43).toFloat()
        bands.forEachIndexed { i, shader ->
            val k = Math.sin((phase / periods[i % periods.size] * tau + i * 1.3f).toDouble()).toFloat() * amps[i % amps.size] * diag
            matrix.setTranslate(dx * k, dy * k * 0.6f)
            shader.setLocalMatrix(matrix)
        }
        bloomAlpha = (205 + 50 * Math.sin((phase / 9f * tau).toDouble())).toInt().coerceIn(0, 255)
    }

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (visible && !running && animationsAllowed()) {
            running = true
            lastFrameNs = 0L
            Choreographer.getInstance().postFrameCallback(frame)
        } else if (!visible && running) {
            running = false
            Choreographer.getInstance().removeFrameCallback(frame)
        }
        return changed
    }

    private fun animationsAllowed(): Boolean = try {
        android.provider.Settings.Global.getFloat(
            com.jarvis.assistant.JarvisApp.instance.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        ) > 0f
    } catch (_: Throwable) { true }

    override fun onBoundsChange(b: Rect) {
        super.onBoundsChange(b)
        if (b.isEmpty) return
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        val l = b.left.toFloat()
        val t = b.top.toFloat()
        val big = maxOf(w, h)
        diag = Math.hypot(w.toDouble(), h.toDouble()).toFloat()

        veil = LinearGradient(
            l, t, l, t + h,
            intArrayOf(Color.argb(0xA2, 0x0B, 0x16, 0x26), Color.argb(0x86, 0x0A, 0x14, 0x24), Color.argb(0xA6, 0x06, 0x0E, 0x1B)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )

        // polished reflections: bands that run along a diagonal across the whole pane
        fun band(from: Float, to: Float, peak: Int, tint: Int = Color.WHITE): Shader {
            val x0 = l - w * 0.1f
            val y0 = t
            val x1 = l + w * 1.1f
            val y1 = t + h * 0.62f
            val mid = (from + to) / 2f
            return LinearGradient(
                x0, y0, x1, y1,
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.argb(0, Color.red(tint), Color.green(tint), Color.blue(tint)),
                    Color.argb(peak, Color.red(tint), Color.green(tint), Color.blue(tint)),
                    Color.argb(peak / 3, Color.red(tint), Color.green(tint), Color.blue(tint)),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, from, mid, to, minOf(to + 0.001f, 1f)), Shader.TileMode.CLAMP
            )
        }
        bands = listOf(
            band(0.16f, 0.44f, 0x30),
            band(0.47f, 0.52f, 0x4A),
            band(0.55f, 0.60f, 0x1C, Color.rgb(0xDF, 0xF3, 0xFF)),
            band(0.70f, 0.94f, 0x16, Color.rgb(0xDF, 0xF3, 0xFF))
        )

        blooms = listOf(
            RadialGradient(l + w * 0.10f, t + h * 0.03f, big * 0.62f,
                intArrayOf(Color.argb(0x52, 0xCF, 0xEF, 0xFF), Color.argb(0x1E, 0xCF, 0xEF, 0xFF), Color.TRANSPARENT),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP),
            RadialGradient(l + w * 0.95f, t + h * 0.92f, big * 0.58f,
                intArrayOf(Color.argb(0x36, 0xC4, 0xB5, 0xFD), Color.argb(0x14, 0xC4, 0xB5, 0xFD), Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        )

        // Clear lens: a bright convex centre, then a fine cyan and magenta dispersion ring near the edge, like glass seen
        // through a lens.
        lensShader = RadialGradient(l + w / 2f, t + h * 0.46f, big * 0.80f,
            intArrayOf(Color.argb(0x1E, 0xFF, 0xFF, 0xFF), Color.argb(0x0A, 0xFF, 0xFF, 0xFF), Color.TRANSPARENT,
                Color.argb(0x26, 0x6F, 0xE3, 0xFF), Color.argb(0x1C, 0xFF, 0x7A, 0xE0), Color.TRANSPARENT),
            floatArrayOf(0f, 0.34f, 0.70f, 0.90f, 0.96f, 1f), Shader.TileMode.CLAMP)

        edge = RadialGradient(l + w / 2f, t + h / 2f, big * 0.78f,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(0x66, 0x00, 0x08, 0x14)),
            floatArrayOf(0f, 0.62f, 1f), Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        veil?.let { paint.shader = it; canvas.drawRect(b, paint) }
        bands.forEach { paint.shader = it; canvas.drawRect(b, paint) }
        val keep = paint.alpha
        paint.alpha = bloomAlpha
        blooms.forEach { paint.shader = it; canvas.drawRect(b, paint) }
        paint.alpha = keep
        lensShader?.let { paint.shader = it; canvas.drawRect(b, paint) }
        edge?.let { paint.shader = it; canvas.drawRect(b, paint) }
        paint.shader = null
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha.coerceIn(0, 255); invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
