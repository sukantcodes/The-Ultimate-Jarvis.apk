package com.jarvis.assistant.ui.chat

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import com.jarvis.assistant.util.ThemeManager
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * Motion that belongs to the liquid-glass material of the ChatBox: a specular light sweep that glides across a glass
 * surface (the way light slides over a curved pane), a breathing glow for "live" badges, and a height reveal that
 * opens and closes a glass panel without popping. Every effect is a no-op when system animations are turned off.
 */
object GlassMotion {

    private val tagBreathe = "glass_breathe".hashCode()
    private val tagReveal = "glass_reveal".hashCode()
    private val tagRevealTarget = "glass_reveal_target".hashCode()
    private val emphasized = PathInterpolator(0.2f, 0f, 0f, 1f)

    fun animationsOn(view: View): Boolean = try {
        Settings.Global.getFloat(view.context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    } catch (_: Exception) { true }

    private fun sheenColor(): Int = try {
        if (ThemeManager.isCurrentThemeLight()) ThemeManager.getThemeAccentColor() else Color.WHITE
    } catch (_: Throwable) { Color.WHITE }

    /**
     * A band of light that travels diagonally across [host], clipped to its rounded outline. [loop] repeats it with a
     * pause between passes (used while something is being written); otherwise it plays once.
     */
    class Sheen(private val host: View, private val radiusDp: Float, private val loop: Boolean = false) {
        private val density = host.resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val clip = Path()
        private val rect = RectF()
        private val matrix = Matrix()
        private var gradient: LinearGradient? = null
        private var gradientColor = 0
        private var bandW = 0f
        private var progress = 0f
        private var anim: ValueAnimator? = null

        private val drawable = object : Drawable() {
            override fun draw(canvas: Canvas) {
                val w = bounds.width().toFloat()
                val h = bounds.height().toFloat()
                if (w <= 0f || h <= 0f) return
                // The light is only on the glass during the first part of each cycle; the rest is the pause.
                val p = if (loop) (progress / 0.62f) else progress
                if (p <= 0f || p >= 1f) return
                val color = sheenColor()
                val bw = max(w * 0.42f, 56f * density)
                if (gradient == null || gradientColor != color || bandW != bw) {
                    gradientColor = color; bandW = bw
                    val peak = (color and 0x00FFFFFF) or (0x66 shl 24)
                    val none = color and 0x00FFFFFF
                    gradient = LinearGradient(0f, 0f, bw, 0f, intArrayOf(none, peak, none), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
                    paint.shader = gradient
                }
                val fade = sin(p * PI.toFloat()).coerceIn(0f, 1f)
                paint.alpha = (255 * (0.35f + 0.65f * fade) * fade.coerceAtLeast(0.2f)).toInt().coerceIn(0, 255)
                rect.set(0f, 0f, w, h)
                clip.reset()
                val r = radiusDp * density
                clip.addRoundRect(rect, r, r, Path.Direction.CW)
                val e = emphasized.getInterpolation(p)
                val save = canvas.save()
                canvas.clipPath(clip)
                canvas.rotate(-16f, w / 2f, h / 2f)
                matrix.setTranslate(-bw + (w + bw * 2f) * e, 0f)
                gradient?.setLocalMatrix(matrix)
                canvas.drawRect(-w * 0.3f, -h * 0.6f, w * 1.3f, h * 1.6f, paint)
                canvas.restoreToCount(save)
            }
            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(colorFilter: ColorFilter?) {}
            @Deprecated("Required by Drawable") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }

        fun start() {
            if (!animationsOn(host) || host.width <= 0 || host.height <= 0) return
            stop()
            drawable.setBounds(0, 0, host.width, host.height)
            host.overlay.add(drawable)
            anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = if (loop) 2300L else 900L
                interpolator = if (loop) LinearInterpolator() else LinearInterpolator()
                if (loop) { repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.RESTART }
                addUpdateListener {
                    if (!host.isAttachedToWindow) { stop(); return@addUpdateListener }
                    progress = it.animatedValue as Float
                    drawable.setBounds(0, 0, host.width, host.height)
                    drawable.invalidateSelf()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) { if (!loop) stop() }
                })
                start()
            }
        }

        fun stop() {
            anim?.removeAllListeners()
            anim?.cancel()
            anim = null
            try { host.overlay.remove(drawable) } catch (_: Exception) { }
        }
    }

    /** One light sweep over [host] (a glass bubble, card or the composer). */
    fun sweep(host: View, radiusDp: Float, delayMs: Long = 0L) {
        if (delayMs <= 0L) Sheen(host, radiusDp).start()
        else host.postDelayed({ if (host.isAttachedToWindow) Sheen(host, radiusDp).start() }, delayMs)
    }

    /** A soft pulsing glow (alpha and a hint of scale) for a "live" badge. Safe to call on every bind. */
    fun breathe(v: View) {
        if (v.getTag(tagBreathe) != null || !animationsOn(v)) return
        val a = ObjectAnimator.ofPropertyValuesHolder(
            v,
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.55f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_X, 0.97f, 1.03f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.97f, 1.03f)
        ).apply {
            duration = 820L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        v.setTag(tagBreathe, a)
    }

    fun stopBreathe(v: View) {
        (v.getTag(tagBreathe) as? Animator)?.cancel()
        v.setTag(tagBreathe, null)
        v.alpha = 1f; v.scaleX = 1f; v.scaleY = 1f
    }

    /** The badge pops with a small overshoot, e.g. when "Writing…" turns into "Done". */
    fun pop(v: View) {
        if (!animationsOn(v)) return
        v.scaleX = 0.82f; v.scaleY = 0.82f
        v.animate().cancel()
        v.animate().scaleX(1f).scaleY(1f).setDuration(380L).setInterpolator(OvershootInterpolator(2.2f)).start()
    }

    /** Opens or closes a panel by sliding its height and fading it, rather than snapping it in or out. */
    fun setExpanded(view: View, show: Boolean, animate: Boolean) {
        val running = view.getTag(tagReveal) as? Animator
        if (running != null) {
            if (view.getTag(tagRevealTarget) == show) return
            running.cancel()
        }
        val parentW = (view.parent as? View)?.width ?: 0
        val shown = view.visibility == View.VISIBLE
        if (!animate || parentW <= 0 || shown == show || !animationsOn(view) || !view.isAttachedToWindow) {
            view.visibility = if (show) View.VISIBLE else View.GONE
            view.layoutParams?.let { it.height = ViewGroup.LayoutParams.WRAP_CONTENT; view.layoutParams = it }
            view.alpha = 1f
            return
        }
        val lp = view.layoutParams ?: return
        val from: Int
        val to: Int
        if (show) {
            view.visibility = View.VISIBLE
            view.measure(
                View.MeasureSpec.makeMeasureSpec(parentW - 0, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            from = 0; to = view.measuredHeight
        } else {
            from = view.height; to = 0
        }
        lp.height = from
        view.layoutParams = lp
        view.alpha = if (show) 0f else 1f
        val a = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (show) 360L else 280L
            interpolator = emphasized
            addUpdateListener {
                val f = it.animatedValue as Float
                lp.height = (from + (to - from) * f).toInt()
                view.layoutParams = lp
                view.alpha = if (show) f else 1f - f
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    view.setTag(tagReveal, null)
                    if (cancelled) return
                    lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    view.layoutParams = lp
                    view.alpha = 1f
                    if (!show) view.visibility = View.GONE
                }
            })
        }
        view.setTag(tagReveal, a)
        view.setTag(tagRevealTarget, show)
        a.start()
    }
}
