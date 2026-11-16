package com.jarvis.assistant.ui.glass

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import androidx.core.graphics.drawable.DrawableCompat

/**
 * A menu icon that sits inside its own 3D glass bubble ([DockPodDrawable]). It reacts to the row's state:
 * pressed => the bubble sinks, then wobbles back with a ripple inside; checked (selected row) => the bubble glows.
 */
internal class GlassIconDrawable(
    host: View,
    icon: Drawable,
    private val sizePx: Int,
    tint: Int
) : Drawable() {

    private val density = host.resources.displayMetrics.density
    private val pod = DockPodDrawable(host, (sizePx / density) - 6f, (sizePx / density) - 6f, true, 0f, 0f)
    private val glyph: Drawable = (icon.constantState?.newDrawable()?.mutate() ?: icon.mutate()).also { DrawableCompat.setTint(it, tint) }
    private var wasChecked = false
    private var wasPressed = false

    init {
        pod.callback = object : Callback {
            override fun invalidateDrawable(who: Drawable) { invalidateSelf() }
            override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) { scheduleSelf(what, `when`) }
            override fun unscheduleDrawable(who: Drawable, what: Runnable) { unscheduleSelf(what) }
        }
    }

    override fun getIntrinsicWidth(): Int = sizePx
    override fun getIntrinsicHeight(): Int = sizePx

    override fun onBoundsChange(b: Rect) {
        super.onBoundsChange(b)
        pod.setBounds(b.left, b.top, b.right, b.bottom)
        val inset = (b.width() * 0.28f).toInt()
        glyph.setBounds(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset)
    }

    override fun isStateful(): Boolean = true

    override fun onStateChange(state: IntArray): Boolean {
        val checked = state.contains(android.R.attr.state_checked)
        val pressed = state.contains(android.R.attr.state_pressed)
        if (checked != wasChecked) { wasChecked = checked; pod.setActive(checked) }
        if (pressed != wasPressed) {
            wasPressed = pressed
            if (pressed) pod.press(bounds.exactCenterX(), bounds.exactCenterY()) else pod.release()
        }
        return true
    }

    override fun draw(canvas: Canvas) {
        pod.draw(canvas)
        glyph.draw(canvas)
    }

    override fun setAlpha(alpha: Int) { glyph.alpha = alpha }

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
