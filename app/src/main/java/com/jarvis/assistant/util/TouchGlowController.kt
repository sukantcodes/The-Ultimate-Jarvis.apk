package com.jarvis.assistant.util

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.util.TypedValue
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.WeakHashMap

/**
 * Lightweight Origin-inspired touch halo for every Activity window.
 * It observes Window.Callback events and never consumes or changes touch dispatch.
 */
object TouchGlowController {
    private val installed = WeakHashMap<Activity, TouchGlowView>()

    fun install(activity: Activity, enabled: Boolean) {
        if (installed.containsKey(activity)) {
            setEnabled(activity, enabled)
            return
        }
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val original = activity.window.callback ?: return
        val overlay = TouchGlowView(activity).apply {
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            this.isEnabled = enabled
        }
        content.addView(
            overlay,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        overlay.bringToFront()
        installed[activity] = overlay

        val proxy = Proxy.newProxyInstance(
            Window.Callback::class.java.classLoader,
            arrayOf(Window.Callback::class.java)
        ) { _, method, args ->
            if (method.name == "dispatchTouchEvent") {
                (args?.firstOrNull() as? MotionEvent)?.let(overlay::observe)
            }
            try {
                method.invoke(original, *(args ?: emptyArray()))
            } catch (error: InvocationTargetException) {
                throw error.targetException
            }
        } as Window.Callback
        activity.window.callback = proxy
    }

    fun setEnabled(activity: Activity, enabled: Boolean) {
        installed[activity]?.isEnabled = enabled
        if (!enabled) installed[activity]?.clearGlow()
    }

    fun uninstall(activity: Activity) {
        installed.remove(activity)?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
        }
    }
}

private class TouchGlowView(activity: Activity) : View(activity) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val location = IntArray(2)
    private val accent = resolveAccent()
    private var centerX = 0f
    private var centerY = 0f
    private var radius = 0f
    private var glowAlpha = 0f
    private var pressed = false
    private var animator: ValueAnimator? = null

    private fun resolveAccent(): Int {
        val value = TypedValue()
        return if (context.theme.resolveAttribute(
                com.jarvis.assistant.R.attr.themeAccent,
                value,
                true
            )
        ) {
            if (value.resourceId != 0) {
                try { resources.getColor(value.resourceId, context.theme) }
                catch (_: Exception) { value.data }
            } else value.data
        } else Color.CYAN
    }

    fun observe(event: MotionEvent) {
        if (!isEnabled) return
        getLocationOnScreen(location)
        val x = event.rawX - location[0]
        val y = event.rawY - location[1]
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> begin(x, y)
            MotionEvent.ACTION_MOVE -> {
                centerX = x
                centerY = y
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> end()
        }
    }

    private fun begin(x: Float, y: Float) {
        animator?.cancel()
        pressed = true
        centerX = x
        centerY = y
        radius = 0f
        glowAlpha = 0.72f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 190L
            addUpdateListener {
                radius = 58f * density * it.animatedValue as Float
                invalidate()
            }
            start()
        }
        invalidate()
    }

    private fun end() {
        if (!pressed) return
        pressed = false
        animator?.cancel()
        val startAlpha = glowAlpha
        val startRadius = radius
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420L
            addUpdateListener {
                val fraction = it.animatedValue as Float
                glowAlpha = startAlpha * (1f - fraction)
                radius = startRadius + (34f * density * fraction)
                invalidate()
            }
            start()
        }
    }

    fun clearGlow() {
        animator?.cancel()
        pressed = false
        glowAlpha = 0f
        radius = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (glowAlpha <= 0f || radius <= 0f) return
        val outer = Color.TRANSPARENT
        paint.alpha = (255f * glowAlpha).toInt().coerceIn(0, 255)
        paint.shader = RadialGradient(
            centerX,
            centerY,
            radius,
            intArrayOf(
                Color.argb(105, Color.red(accent), Color.green(accent), Color.blue(accent)),
                Color.argb(42, Color.red(accent), Color.green(accent), Color.blue(accent)),
                outer
            ),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(centerX, centerY, radius, paint)
        paint.shader = null
    }
}
