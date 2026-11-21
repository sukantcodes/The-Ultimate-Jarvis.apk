package com.jarvis.assistant.ui.glass

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.view.MotionEvent
import android.view.View

/**
 * Lightweight iOS 26/27 Liquid Glass pills/chips for controls sitting on glass surfaces or inside
 * RecyclerViews (zero backdrop capture cost, crisp Fresnel specular rim & clean optical fill).
 */
object GlassChips {

    fun make(context: Context, radiusDp: Float = 22f, selected: Boolean = false): Drawable {
        val d = context.resources.displayMetrics.density
        val r = radiusDp * d

        val fill = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                if (selected) 0x5EFFFFFF else 0x30FFFFFF,
                if (selected) 0x2AFFFFFF else 0x12FFFFFF,
                if (selected) 0x3DFFFFFF else 0x1AFFFFFF
            )
        ).apply { cornerRadius = r }

        val shade = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x00000000, 0x00000000, if (selected) 0x1E000000 else 0x26000000)
        ).apply { cornerRadius = r }

        val gloss = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(if (selected) 0x6EFFFFFF else 0x48FFFFFF, 0x00FFFFFF)
        ).apply { cornerRadius = r * 0.85f }

        val innerRim = GradientDrawable().apply {
            cornerRadius = r
            setColor(Color.TRANSPARENT)
            setStroke((1.0f * d).toInt().coerceAtLeast(1), if (selected) 0x55FFFFFF else 0x26FFFFFF)
        }

        val rim = GradientDrawable().apply {
            cornerRadius = r
            setColor(Color.TRANSPARENT)
            setStroke((1.05f * d).toInt().coerceAtLeast(1), if (selected) 0xD9FFFFFF.toInt() else 0x99FFFFFF.toInt())
        }

        val inset = (1.1f * d).toInt()
        val layers = LayerDrawable(arrayOf(fill, shade, gloss, innerRim, rim)).apply {
            setLayerInset(2, (r * 0.32f).toInt(), (1.2f * d).toInt(), (r * 0.32f).toInt(), 0)
            setLayerGravity(2, android.view.Gravity.TOP or android.view.Gravity.FILL_HORIZONTAL)
            setLayerHeight(2, (r * 0.72f).toInt().coerceAtLeast(1))
            setLayerInset(3, inset, inset, inset, inset)
        }
        val mask = GradientDrawable().apply { cornerRadius = r; setColor(Color.WHITE) }
        return RippleDrawable(ColorStateList.valueOf(0x36FFFFFF), layers, mask)
    }

    /**
     * Fluid iOS Liquid Glass interactive swell: expands smoothly under the finger and springs back
     * using frame-rate-independent ViewSprings (never consumes the touch).
     */
    @Suppress("ClickableViewAccessibility")
    fun magnifyOnPress(view: View, scale: Float = 1.12f) {
        view.setTag(LiquidTouch.SKIP_TAG_KEY, true)
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> ViewSprings.pop(v, v.scaleX, scale, stiffness = 620f, ratio = 0.72f)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    ViewSprings.pop(v, v.scaleX, 1f, stiffness = 400f, ratio = 0.42f)
            }
            false
        }
    }
}
