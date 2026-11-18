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
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import com.jarvis.assistant.ui.glass.LiquidTouch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * A glass-bubble switch. The thumb is a glossy sphere that squashes and stretches as it travels, overshoots the end with a
 * spring, and sends a ring of light outward when it lands; the track fills with the hue's gradient. It can be tapped or
 * dragged, gives a light haptic tick, and exposes its state to screen readers.
 */
class BubbleSwitch(context: Context) : View(context) {

    var onChange: ((Boolean) -> Unit)? = null
    var hue: HomeTheme.Hue = HomeTheme.CYAN
        set(value) { field = value; invalidate() }
    var isOn: Boolean = false
        private set

    private val d = resources.displayMetrics.density
    private var p = 0f          // thumb position, 0 (off) .. 1 (on); overshoots a little while settling
    private var burst = 1f      // 0..1 ring animation; 1 = finished
    private var travelAnim: ValueAnimator? = null
    private var burstAnim: ValueAnimator? = null
    private var downX = 0f
    private var dragging = false
    private var pressed3d = 0f

    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * d }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.2f * d }
    private val rect = RectF()

    init {
        isClickable = true; isFocusable = true
        setTag(LiquidTouch.SKIP_TAG_KEY, true) // owns its press animation
        ViewCompat.setStateDescription(this, "Off")
    }

    fun setOn(value: Boolean, animate: Boolean = true, notify: Boolean = false) {
        if (isOn == value && travelAnim?.isRunning != true) { if (!animate) { p = if (value) 1f else 0f; invalidate() }; return }
        isOn = value
        ViewCompat.setStateDescription(this, if (value) "On" else "Off")
        travelTo(if (value) 1f else 0f, animate)
        if (notify) onChange?.invoke(value)
    }

    private fun travelTo(target: Float, animate: Boolean) {
        travelAnim?.cancel()
        if (!animate || !isAttachedToWindow || !Motion.enabled(context)) { p = target; invalidate(); return }
        travelAnim = ValueAnimator.ofFloat(p, target).apply {
            duration = 460; interpolator = OvershootInterpolator(2.1f)
            addUpdateListener { p = it.animatedValue as Float; invalidate() }
            start()
        }
        burstAnim?.cancel()
        burstAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 620; startDelay = 150; interpolator = DecelerateInterpolator()
            addUpdateListener { burst = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((62 * d).toInt(), (38 * d).toInt())

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; dragging = false; animatePress(1f); parent?.requestDisallowInterceptTouchEvent(true); return true }
            MotionEvent.ACTION_MOVE -> {
                if (abs(e.x - downX) > 6 * d) dragging = true
                if (dragging) {
                    travelAnim?.cancel()
                    val travel = width - height.toFloat()
                    p = ((e.x - height / 2f) / travel).coerceIn(0f, 1f)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                animatePress(0f)
                val next = if (dragging) p > 0.5f else !isOn
                if (!dragging) performClick()
                if (next != isOn) { isOn = next; ViewCompat.setStateDescription(this, if (next) "On" else "Off"); performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); travelTo(if (next) 1f else 0f, true); onChange?.invoke(next) }
                else travelTo(if (isOn) 1f else 0f, true)
                return true
            }
            MotionEvent.ACTION_CANCEL -> { animatePress(0f); travelTo(if (isOn) 1f else 0f, true); return true }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun animatePress(to: Float) {
        ValueAnimator.ofFloat(pressed3d, to).apply { duration = 140; addUpdateListener { pressed3d = it.animatedValue as Float; invalidate() }; start() }
    }

    override fun onDetachedFromWindow() { travelAnim?.cancel(); burstAnim?.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val k = p.coerceIn(0f, 1f)
        val inset = 2f * d
        rect.set(inset, inset + 2f * d, w - inset, h - inset)
        val rad = rect.height() / 2f

        // Track: glass when off, the hue's gradient when on, with an inner shade at the top for depth.
        val offA = ColorUtils.setAlphaComponent(Color.WHITE, 46); val offB = ColorUtils.setAlphaComponent(Color.WHITE, 22)
        track.shader = LinearGradient(rect.left, 0f, rect.right, 0f, ColorUtils.blendARGB(offA, hue.a, k), ColorUtils.blendARGB(offB, hue.b, k), Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, rad, rad, track)
        trim.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, ColorUtils.setAlphaComponent(Color.BLACK, 90), ColorUtils.setAlphaComponent(Color.WHITE, 120), Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, rad, rad, trim)

        // Thumb bubble.
        val r = rect.height() / 2f - 3f * d + pressed3d * 1.5f * d
        val travel = rect.width() - rect.height()
        val cx = rect.left + rad + (p * travel)
        val cy = rect.centerY()
        val move = sin(PI * k.toDouble()).toFloat()          // 0 at rest, 1 mid-travel
        val rx = r * (1f + 0.38f * move + 0.1f * pressed3d); val ry = r * (1f - 0.14f * move)

        shadow.color = ColorUtils.setAlphaComponent(Color.BLACK, 70)
        c.drawOval(cx - rx, cy - ry + 2.2f * d, cx + rx, cy + ry + 2.2f * d, shadow)
        val tint = ColorUtils.blendARGB(Color.WHITE, hue.a, 0.25f * k)
        thumb.shader = RadialGradient(cx - rx * 0.3f, cy - ry * 0.4f, r * 1.5f, intArrayOf(Color.WHITE, tint, ColorUtils.blendARGB(tint, hue.b, 0.4f)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, thumb)
        thumb.shader = LinearGradient(0f, cy - ry, 0f, cy, ColorUtils.setAlphaComponent(Color.WHITE, 200), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawOval(cx - rx * 0.62f, cy - ry * 0.9f, cx + rx * 0.2f, cy - ry * 0.25f, thumb)

        if (burst < 1f) {
            ring.color = ColorUtils.setAlphaComponent(hue.a, ((1f - burst) * 150).toInt())
            c.drawCircle(cx, cy, r * (1f + 1.1f * burst), ring)
        }
    }
}
