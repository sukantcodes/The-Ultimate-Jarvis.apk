package com.jarvis.assistant.ui.glass

import android.animation.ValueAnimator
import android.os.SystemClock
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The glass "bubble" that marks the selected dock tab. It slides to the next tab like a drop of liquid:
 * the leading edge runs ahead and overshoots, the trailing edge follows, so the bubble stretches while it travels
 * (and gets thinner, as a real blob would) and then settles with a small wobble.
 *
 * Put it in the dock BEHIND the tab row. It only draws itself, so it never covers the icons.
 */
class GlassBubbleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val inset = 5f * density

    private var left = 0f
    private var right = 0f
    private var restWidth = 1f
    private var shown = false
    private var target: View? = null
    private var springL: SpringF? = null
    private var springR: SpringF? = null
    private var gliding = false
    private var lastFrame = 0L
    private val frame = Runnable { stepGlide() }
    private var tint = 0

    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spec = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounce = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fringeA = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fringeB = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private val m = Matrix()

    private val unitRadial = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(0xFFFFFFFF.toInt(), 0x59FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
    )

    /** Optional soft accent colour mixed into the bubble (kept very light). */
    fun setTint(color: Int) { tint = color; rebuild(); invalidate() }

    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        val t = target ?: return@OnLayoutChangeListener
        if (!gliding) snapTo(t)
    }

    /** Moves the bubble to [tab]. The first call places it without animation. */
    fun attachTo(tab: View?) {
        target?.removeOnLayoutChangeListener(layoutListener)
        target = tab
        if (tab == null) { hide(); return }
        tab.addOnLayoutChangeListener(layoutListener)
        if (tab.width == 0) return // placed by the layout listener once measured
        val l = edgeLeft(tab)
        val r = edgeRight(tab)
        if (!shown) snapTo(tab)
        else glideTo(l, r)
    }

    fun hide() {
        shown = false
        animate().alpha(0f).setDuration(140).start()
    }

    private fun edgeLeft(t: View) = ((t.parent as? View)?.left ?: 0) + t.left + 4f * density
    private fun edgeRight(t: View) = ((t.parent as? View)?.left ?: 0) + t.right - 4f * density

    private fun snapTo(t: View) {
        left = edgeLeft(t); right = edgeRight(t); restWidth = max(1f, right - left)
        if (!shown) { shown = true; animate().cancel(); alpha = 1f }
        invalidate()
    }

    private fun glideTo(newL: Float, newR: Float) {
        val toRight = (newL + newR) > (left + right)
        restWidth = max(1f, newR - newL)
        // Two springs, one per edge: the leading edge is stiff and overshoots a little, the trailing edge is soft and
        // lags behind, so the bubble stretches as it travels and then pulls itself together. A tap during a glide
        // keeps the current speed of both edges, so the motion never jerks.
        val kLead = 520f
        val kTrail = 230f
        val lead = SpringF.damping(kLead, 0.66f)
        val trail = SpringF.damping(kTrail, 0.8f)
        val oldVl = springL?.velocity ?: 0f
        val oldVr = springR?.velocity ?: 0f
        springL = SpringF(left, newL, if (toRight) kTrail else kLead, if (toRight) trail else lead).also { it.velocity = oldVl }
        springR = SpringF(right, newR, if (toRight) kLead else kTrail, if (toRight) lead else trail).also { it.velocity = oldVr }
        if (!gliding) {
            gliding = true
            lastFrame = SystemClock.uptimeMillis()
            postOnAnimation(frame)
        }
    }

    private fun stepGlide() {
        val l = springL ?: run { gliding = false; return }
        val r = springR ?: run { gliding = false; return }
        val now = SystemClock.uptimeMillis()
        val dt = ((now - lastFrame) / 1000f).coerceIn(0.001f, 0.033f)
        lastFrame = now
        l.step(dt); r.step(dt)
        if (l.atRest(0.05f) && r.atRest(0.05f)) {
            left = l.target; right = r.target
            gliding = false
        } else {
            left = l.value; right = r.value
            postOnAnimation(frame)
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        rebuild()
        target?.let { if (it.width > 0) snapTo(it) }
    }

    private fun rebuild() {
        val h = height.toFloat()
        if (h <= 0f) return
        val top = inset
        val bottom = h - inset
        val accentVeil = if (tint != 0) Color.argb(40, Color.red(tint), Color.green(tint), Color.blue(tint)) else 0
        body.shader = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(0x4DFFFFFF, 0x1FFFFFFF.toInt() or 0, 0x2EFFFFFF), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP
        )
        bounce.color = 0x1FFFFFFF
        shade.shader = LinearGradient(0f, top, 0f, bottom, intArrayOf(0x00000000, 0x00000000, 0x33000000), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        rim.strokeWidth = 1.2f * density
        rim.shader = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(0xE6FFFFFF.toInt(), 0x33FFFFFF, 0x99FFFFFF.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        fringeA.strokeWidth = 1f * density
        fringeA.color = 0x59FF4FD8
        fringeB.strokeWidth = 1f * density
        fringeB.color = 0x2633E1FF // cyan kept faint
        accentTint = accentVeil
    }

    private var accentTint = 0

    override fun onDraw(canvas: Canvas) {
        if (!shown || right <= left || height == 0) return
        val h = height - 2 * inset
        // Stretch while travelling => the blob gets thinner (volume is kept roughly constant).
        val stretch = ((right - left) / restWidth).coerceIn(0.85f, 2.0f)
        val vs = 1f / sqrt(stretch)
        val hh = h * min(vs, 1.08f)
        val cy = height / 2f
        rect.set(left, cy - hh / 2f, right, cy + hh / 2f)
        val r = hh / 2f

        canvas.drawRoundRect(rect, r, r, body)
        // convex shade + caustic so the bubble reads as a 3D dome
        val shadeH = rect.height()
        canvas.drawRoundRect(rect, r, r, shade)
        m.reset(); m.postScale(rect.width() * 0.42f, shadeH * 0.5f); m.postTranslate(rect.centerX(), rect.bottom + shadeH * 0.08f)
        unitRadial.setLocalMatrix(m); spec.shader = unitRadial; spec.alpha = 90
        canvas.drawRoundRect(rect, r, r, spec)
        if (accentTint != 0) { bounce.color = accentTint; canvas.drawRoundRect(rect, r, r, bounce); bounce.color = 0x1FFFFFFF }

        // specular highlight, top-left of the bubble
        val w = rect.width()
        m.reset(); m.postScale(w * 0.42f, hh * 0.62f); m.postTranslate(rect.left + w * 0.30f, rect.top + hh * 0.18f)
        unitRadial.setLocalMatrix(m); spec.shader = unitRadial; spec.alpha = 150
        canvas.drawRoundRect(rect, r, r, spec)
        // soft bounce light, bottom-right
        m.reset(); m.postScale(w * 0.30f, hh * 0.40f); m.postTranslate(rect.right - w * 0.22f, rect.bottom - hh * 0.10f)
        unitRadial.setLocalMatrix(m); spec.shader = unitRadial; spec.alpha = 70
        canvas.drawRoundRect(rect, r, r, spec)

        val s = 0.7f * density
        canvas.save(); canvas.translate(-s, -s); canvas.drawRoundRect(rect, r, r, fringeA); canvas.restore()
        canvas.save(); canvas.translate(s, s); canvas.drawRoundRect(rect, r, r, fringeB); canvas.restore()
        canvas.drawRoundRect(rect, r, r, rim)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frame)
        gliding = false
        target?.removeOnLayoutChangeListener(layoutListener)
        super.onDetachedFromWindow()
    }
}
