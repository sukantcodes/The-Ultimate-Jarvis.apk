package com.jarvis.assistant.ui.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import com.jarvis.assistant.util.ThemeManager
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The listening animation: a liquid glass bubble that breathes with your voice, in the app's colours.
 *
 * - a glass body with a wobbling liquid edge whose ripple grows with the microphone level,
 * - a second, counter-turning liquid blob inside it for depth,
 * - rings that bloom outward and fade each time you speak up,
 * - a soft halo, a bright crescent highlight and a pooled caustic, like a real glass bubble.
 *
 * Colours come from the active theme (accent and secondary) every time it starts, so it matches every theme including
 * Crystal Mirror. It only animates while visible, allocates nothing per frame, and is a small view, so it is cheap.
 * The public API is unchanged: [start], [stop], [updateRms].
 */
class VoiceWaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val blob = Path()
    private val arcRect = RectF()

    private var accent = Color.parseColor("#00E5FF")
    private var secondary = Color.parseColor("#A78BFA")
    private var cx = 0f
    private var cy = 0f
    private var r = 0f
    private var halo: Shader? = null
    private var body: Shader? = null
    private var core: Shader? = null

    private var target = 0f
    private var level = 0f
    private var phase = 0f
    private var animating = false
    private var lastNs = 0L

    // rings: progress 0..1 (negative = idle slot)
    private val ring = FloatArray(3) { -1f }
    private var sinceRing = 0f

    private val frame = object : Runnable {
        override fun run() {
            if (!animating) return
            val now = System.nanoTime()
            val dt = if (lastNs == 0L) 0.016f else ((now - lastNs) / 1e9f).coerceIn(0.001f, 0.05f)
            lastNs = now
            target *= (1f - 2.6f * dt).coerceAtLeast(0f)                       // the level falls back smoothly
            level += (target - level) * (1f - Math.exp((-14f * dt).toDouble()).toFloat())
            phase += dt * (1.4f + 3.2f * level)
            sinceRing += dt
            if (sinceRing > (if (level > 0.35f) 0.45f else 1.1f)) {            // louder voice, quicker rings
                val slot = ring.indexOfFirst { it < 0f }
                if (slot >= 0) { ring[slot] = 0f; sinceRing = 0f }
            }
            for (i in ring.indices) if (ring[i] >= 0f) { ring[i] += dt * 0.75f; if (ring[i] > 1f) ring[i] = -1f }
            invalidate()
            postOnAnimation(this)
        }
    }

    fun start() {
        refreshColors()
        if (!animating) {
            animating = true
            lastNs = 0L
            postOnAnimation(frame)
        }
    }

    fun stop() {
        animating = false
        target = 0f
        level = 0f
        ring.fill(-1f)
        removeCallbacks(frame)
        invalidate()
    }

    fun updateRms(rmsdB: Float) {
        // rmsdB typically ranges from -2 to 10
        val n = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        if (n > target) target = n
    }

    /** Re-reads the app colours (call when the theme may have changed). */
    fun refreshColors() {
        try {
            accent = ThemeManager.getThemeAccentColor()
            secondary = ThemeManager.getThemeSecondaryColor()
        } catch (_: Throwable) {}
        buildShaders()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f
        cy = h / 2f
        r = min(w, h) / 2f * 0.60f
        buildShaders()
    }

    private fun buildShaders() {
        if (r <= 0f) return
        halo = RadialGradient(cx, cy, r * 1.9f,
            intArrayOf(ColorUtils.setAlphaComponent(accent, 150), ColorUtils.setAlphaComponent(secondary, 56), Color.TRANSPARENT),
            floatArrayOf(0.3f, 0.65f, 1f), Shader.TileMode.CLAMP)
        // glass body: bright upper-left, accent through the middle, secondary pooled at the far edge
        body = RadialGradient(cx - r * 0.32f, cy - r * 0.38f, r * 1.5f,
            intArrayOf(
                ColorUtils.setAlphaComponent(ColorUtils.blendARGB(accent, Color.WHITE, 0.72f), 235),
                ColorUtils.setAlphaComponent(accent, 190),
                ColorUtils.setAlphaComponent(secondary, 225)
            ),
            floatArrayOf(0f, 0.52f, 1f), Shader.TileMode.CLAMP)
        core = RadialGradient(cx, cy, r * 0.8f,
            intArrayOf(ColorUtils.setAlphaComponent(Color.WHITE, 190), ColorUtils.setAlphaComponent(secondary, 90), Color.TRANSPARENT),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
    }

    private fun liquid(radius: Float, wob: Float, ph: Float, lobes1: Int, lobes2: Int) {
        blob.rewind()
        val steps = 56
        for (i in 0..steps) {
            val a = (i.toFloat() / steps) * 2f * PI.toFloat()
            val k = 1f + wob * (0.62f * sin(lobes1 * a + ph) + 0.38f * sin(lobes2 * a - ph * 1.45f))
            val x = cx + cos(a) * radius * k
            val y = cy + sin(a) * radius * k
            if (i == 0) blob.moveTo(x, y) else blob.lineTo(x, y)
        }
        blob.close()
    }

    override fun onDraw(canvas: Canvas) {
        if (r <= 0f) return
        val lv = level
        val breathe = 1f + 0.025f * sin(phase * 0.9f)
        val rr = r * (0.94f + 0.16f * lv) * breathe

        // halo
        fill.shader = halo
        fill.alpha = (110 + 145 * lv).toInt().coerceAtMost(255)
        canvas.drawCircle(cx, cy, r * 1.9f, fill)
        fill.shader = null
        fill.alpha = 255

        // rings that bloom outward and fade
        stroke.strokeWidth = 1.6f * density
        for (p in ring) {
            if (p < 0f) continue
            val e = 1f - (1f - p) * (1f - p)
            stroke.color = ColorUtils.setAlphaComponent(ColorUtils.blendARGB(accent, secondary, p), ((1f - p) * 150f).toInt())
            canvas.drawCircle(cx, cy, rr * (1.05f + 0.75f * e), stroke)
        }

        // liquid body
        val wob = 0.035f + 0.10f * lv
        liquid(rr, wob, phase, 3, 5)
        fill.shader = body
        canvas.drawPath(blob, fill)
        fill.shader = null

        // inner blob turning the other way
        liquid(rr * 0.58f, wob * 1.4f, -phase * 1.2f, 4, 2)
        fill.color = ColorUtils.setAlphaComponent(ColorUtils.blendARGB(secondary, Color.WHITE, 0.25f), (70 + 90 * lv).toInt())
        canvas.drawPath(blob, fill)

        // glowing core
        fill.shader = core
        fill.alpha = (120 + 135 * lv).toInt().coerceAtMost(255)
        canvas.drawCircle(cx, cy, rr * 0.8f, fill)
        fill.shader = null
        fill.alpha = 255

        // glass rim
        liquid(rr, wob, phase, 3, 5)
        stroke.strokeWidth = 1.3f * density
        stroke.color = Color.argb(150, 255, 255, 255)
        canvas.drawPath(blob, stroke)

        // crescent highlight (upper left) and caustic (lower right)
        stroke.strokeWidth = 2.4f * density
        stroke.color = Color.argb(235, 255, 255, 255)
        val h = rr * 0.80f
        arcRect.set(cx - h, cy - h, cx + h, cy + h)
        canvas.drawArc(arcRect, 200f, 62f, false, stroke)
        stroke.strokeWidth = 1.6f * density
        stroke.color = ColorUtils.setAlphaComponent(ColorUtils.blendARGB(accent, Color.WHITE, 0.55f), 150)
        canvas.drawArc(arcRect, 25f, 44f, false, stroke)
        fill.color = Color.argb(240, 255, 255, 255)
        canvas.drawCircle(cx - rr * 0.38f, cy - rr * 0.42f, max(1.6f * density, rr * 0.075f), fill)
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
}
