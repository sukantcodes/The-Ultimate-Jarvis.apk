package com.jarvis.assistant.ui.glass

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator

/**
 * Light glass for things that sit ON a LiquidGlassLayout
 * (camera / + / mic / send buttons, dock icons, chat bubbles, the work card).
 * No capture, so it is free to use on dozens of views and inside RecyclerView.
 */
object GlassChips {

    fun make(context: Context, radiusDp: Float = 22f, selected: Boolean = false): Drawable {
        val d = context.resources.displayMetrics.density
        val r = radiusDp * d

        val fill = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                if (selected) 0x70FFFFFF else 0x47FFFFFF,
                if (selected) 0x30FFFFFF else 0x14FFFFFF
            )
        ).apply { cornerRadius = r }

        val fringe = GradientDrawable().apply {
            cornerRadius = r
            setColor(Color.TRANSPARENT)
            setStroke((1.2f * d).toInt().coerceAtLeast(1), if (selected) 0x66FF7A45 else 0x2433E1FF)
        }
        val rim = GradientDrawable().apply {
            cornerRadius = r
            setColor(Color.TRANSPARENT)
            setStroke((1f * d).toInt().coerceAtLeast(1), 0xB3FFFFFF.toInt())
        }
        val inset = (0.8f * d).toInt()
        val layers = LayerDrawable(arrayOf(fill, fringe, rim)).apply {
            setLayerInset(1, inset, inset, 0, 0)
        }
        val mask = GradientDrawable().apply { cornerRadius = r; setColor(Color.WHITE) }
        return RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), layers, mask)
    }

    private val wobble = java.util.WeakHashMap<View, android.animation.Animator>()

    /**
     * Bubble-like press: it squashes and swells under the finger, then releases with a springy wobble.
     * Does not consume the touch, so clicks and long-presses still work.
     */
    @Suppress("ClickableViewAccessibility")
    fun magnifyOnPress(view: View, scale: Float = 1.16f) {
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    wobble.remove(v)?.cancel()
                    v.animate().cancel()
                    v.animate().scaleX(scale * 1.05f).scaleY(scale * 0.94f)
                        .setDuration(130).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().cancel()
                    val sx = v.scaleX
                    val sy = v.scaleY
                    val anim = android.animation.ObjectAnimator.ofPropertyValuesHolder(
                        v,
                        android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, sx, 0.93f, 1.05f, 0.99f, 1f),
                        android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, sy, 1.07f, 0.96f, 1.01f, 1f)
                    ).apply { duration = 480; interpolator = android.view.animation.LinearInterpolator() }
                    wobble[v] = anim
                    anim.start()
                }
            }
            false
        }
    }
}
