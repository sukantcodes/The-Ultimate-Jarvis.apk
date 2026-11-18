package com.jarvis.assistant.ui.jarvishome

import android.animation.ValueAnimator
import android.content.Context
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import com.jarvis.assistant.ui.glass.LiquidTouch

/** Touch tilt, page transitions, count-ups and pulses for JARVIS Home. All of it is skipped when the system has animations off. */
object Motion {

    fun enabled(context: Context): Boolean = runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }.getOrDefault(true)

    /**
     * A 3D press: the view sinks and tilts toward the finger, and springs back with a small overshoot on release. Clicks are
     * untouched (the listener never consumes events), and the view is marked so the app-wide squish does not stack on top.
     */
    fun pressable(view: View, maxTiltDeg: Float = 7f) {
        view.setTag(LiquidTouch.SKIP_TAG_KEY, true)
        view.cameraDistance = 9000f * view.resources.displayMetrics.density
        view.setOnTouchListener { v, e ->
            if (!enabled(v.context)) return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val nx = ((e.x / v.width.coerceAtLeast(1)) - 0.5f).coerceIn(-0.5f, 0.5f) * 2f
                    val ny = ((e.y / v.height.coerceAtLeast(1)) - 0.5f).coerceIn(-0.5f, 0.5f) * 2f
                    v.animate().cancel()
                    v.animate().scaleX(0.955f).scaleY(0.955f).rotationY(nx * maxTiltDeg).rotationX(-ny * maxTiltDeg).translationZ(-4f * v.resources.displayMetrics.density)
                        .setDuration(if (e.actionMasked == MotionEvent.ACTION_DOWN) 110 else 60).setInterpolator(DecelerateInterpolator()).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().cancel().also {
                        v.animate().scaleX(1f).scaleY(1f).rotationX(0f).rotationY(0f).translationZ(0f).setDuration(380).setInterpolator(OvershootInterpolator(2.6f)).start()
                    }
            }
            false
        }
    }

    /** Page enter: each child rises, scales up and slides in from [direction] (-1 from the left, 1 from the right), one after another. */
    fun enter(container: ViewGroup, direction: Int) {
        if (!enabled(container.context)) return
        val d = container.resources.displayMetrics.density
        for (i in 0 until container.childCount) {
            val v = container.getChildAt(i)
            v.alpha = 0f; v.translationX = direction * 36f * d; v.translationY = 18f * d; v.scaleX = 0.94f; v.scaleY = 0.94f
            v.animate().alpha(1f).translationX(0f).translationY(0f).scaleX(1f).scaleY(1f)
                .setStartDelay((minOf(i, 10) * 55).toLong()).setDuration(520).setInterpolator(OvershootInterpolator(0.9f)).start()
        }
    }

    /** A chip that was just selected pops in. */
    fun pop(view: View) {
        if (!enabled(view.context)) return
        view.scaleX = 0.78f; view.scaleY = 0.78f
        view.animate().scaleX(1f).scaleY(1f).setDuration(480).setInterpolator(OvershootInterpolator(3f)).start()
    }

    fun countUp(tv: TextView, to: Int) {
        if (!enabled(tv.context) || to == 0) { tv.text = to.toString(); return }
        ValueAnimator.ofInt(0, to).apply {
            duration = (500 + 60 * minOf(to, 10)).toLong(); interpolator = DecelerateInterpolator()
            addUpdateListener { tv.text = (it.animatedValue as Int).toString() }
            start()
        }
    }

    /** A slow breathing glow for "live" indicators. Stops by itself when the view leaves the screen. */
    fun breathe(view: View) {
        if (!enabled(view.context)) return
        val a = ValueAnimator.ofFloat(0.45f, 1f).apply {
            duration = 1300; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
            addUpdateListener { val f = it.animatedValue as Float; view.alpha = f; view.scaleX = 0.8f + 0.3f * f; view.scaleY = 0.8f + 0.3f * f }
        }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { a.start() }
            override fun onViewDetachedFromWindow(v: View) { a.cancel() }
        })
        if (view.isAttachedToWindow) a.start()
    }

}
