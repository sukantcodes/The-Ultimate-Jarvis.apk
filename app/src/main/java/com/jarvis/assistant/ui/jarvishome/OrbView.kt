package com.jarvis.assistant.ui.jarvishome

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import android.view.animation.OvershootInterpolator
import androidx.core.graphics.ColorUtils
import kotlin.math.min

/**
 * A glossy 3D sphere: radial body, glass rim, specular highlight, soft bounce light and an outer glow when lit.
 * Used for category badges, power buttons and stat badges. [setLit] blends smoothly between glass-off and glowing-on, and
 * [setBusy] shows a spinning arc while a command is really in flight (it never fakes a result).
 */
class OrbView(context: Context) : View(context) {

    private var hue = HomeTheme.BLUE
    private var glyph = ""
    private var lit = 0f
    private var busy = false
    private var spin = 0f
    private var litAnim: ValueAnimator? = null
    private var spinAnim: ValueAnimator? = null
    private val d = resources.displayMetrics.density

    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.4f * d }
    private val spec = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.6f * d; strokeCap = Paint.Cap.ROUND; color = Color.WHITE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = com.jarvis.assistant.util.JarvisFonts.bold }
    private val oval = RectF()

    fun configure(hue: HomeTheme.Hue, glyph: String): OrbView { this.hue = hue; this.glyph = glyph; invalidate(); return this }

    fun setLit(on: Boolean, animate: Boolean = true) {
        val target = if (on) 1f else 0f
        litAnim?.cancel()
        if (!animate || !isAttachedToWindow || !Motion.enabled(context)) { lit = target; invalidate(); return }
        litAnim = ValueAnimator.ofFloat(lit, target).apply {
            duration = 460; interpolator = OvershootInterpolator(1.4f)
            addUpdateListener { lit = (it.animatedValue as Float).coerceIn(0f, 1.15f); invalidate() }
            start()
        }
    }

    fun setBusy(value: Boolean) {
        if (busy == value) return
        busy = value
        spinAnim?.cancel()
        if (value && Motion.enabled(context)) {
            spinAnim = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 900; repeatCount = ValueAnimator.INFINITE; interpolator = android.view.animation.LinearInterpolator()
                addUpdateListener { spin = it.animatedValue as Float; invalidate() }
                start()
            }
        }
        invalidate()
    }

    override fun onDetachedFromWindow() { litAnim?.cancel(); spinAnim?.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2f; val cy = h / 2f
        val r = min(w, h) / 2f - 3f * d
        if (r <= 0f) return
        val k = lit.coerceIn(0f, 1f)

        if (k > 0.01f) for (i in 3 downTo 1) {
            glow.color = ColorUtils.setAlphaComponent(hue.a, (46 * k / i).toInt())
            c.drawCircle(cx, cy, r + i * 2.2f * d, glow)
        }

        val offLight = ColorUtils.setAlphaComponent(Color.WHITE, 70)
        val offDark = ColorUtils.setAlphaComponent(Color.WHITE, 22)
        val light = ColorUtils.blendARGB(offLight, ColorUtils.blendARGB(Color.WHITE, hue.a, 0.55f), k)
        val mid = ColorUtils.blendARGB(ColorUtils.setAlphaComponent(Color.WHITE, 40), hue.a, k)
        val dark = ColorUtils.blendARGB(offDark, hue.b, k)
        body.shader = RadialGradient(cx - r * 0.32f, cy - r * 0.38f, r * 1.45f, intArrayOf(light, mid, dark), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, body)

        rim.shader = LinearGradient(cx, cy - r, cx, cy + r, ColorUtils.setAlphaComponent(Color.WHITE, 170), ColorUtils.setAlphaComponent(Color.WHITE, 26), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r - rim.strokeWidth / 2f, rim)

        oval.set(cx - r * 0.66f, cy - r * 0.92f, cx + r * 0.1f, cy - r * 0.28f)
        spec.shader = LinearGradient(0f, oval.top, 0f, oval.bottom, ColorUtils.setAlphaComponent(Color.WHITE, (150 + 60 * k).toInt().coerceAtMost(220)), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawOval(oval, spec)

        oval.set(cx - r * 0.45f, cy + r * 0.52f, cx + r * 0.45f, cy + r * 0.9f)
        spec.shader = LinearGradient(0f, oval.bottom, 0f, oval.top, ColorUtils.setAlphaComponent(hue.a, (70 * k).toInt()), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawOval(oval, spec)

        if (glyph.isNotEmpty()) {
            text.textSize = r * 0.95f
            text.color = ColorUtils.setAlphaComponent(Color.WHITE, (170 + 85 * k).toInt().coerceAtMost(255))
            text.setShadowLayer(3f * d, 0f, 1.5f * d, ColorUtils.setAlphaComponent(Color.BLACK, 90))
            c.drawText(glyph, cx, cy - (text.descent() + text.ascent()) / 2f, text)
        }
        if (busy) {
            oval.set(cx - r - 1.5f * d, cy - r - 1.5f * d, cx + r + 1.5f * d, cy + r + 1.5f * d)
            c.drawArc(oval, spin, 100f, false, arc)
        }
    }
}
