package com.jarvis.assistant.ui.glass

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import com.jarvis.assistant.ui.chat.GlassMotion

/**
 * Bubble glow, liquid press and bubble entrance for list-style screens such as Settings.
 *
 * - [bubbleRows] gives every row its own glowing 3D bubble (in the section's colour). Rows are decorated a few per frame, so
 *   opening a long screen never hitches.
 * - [pressSquish] makes a row squish under the finger and spring back like liquid, interruptible and frame-rate independent.
 * - [enter] pops blocks in one after another with a light sweep across each as it lands.
 * All of it is skipped when Liquid Glass is off.
 */
object LiquidRows {

    private const val SQUISH_TAG = 0x4A4C5251 // "JLRQ"
    private const val PER_FRAME = 6

    fun bubbleRows(root: ViewGroup, accent: Int, cornerDp: Float = 20f, intensity: Float = 0.5f) {
        if (GlassMode.plain()) return
        val d = root.resources.displayMetrics.density
        val rows = ArrayList<View>()
        fun walk(v: View) {
            if (v is LinearLayout && v.isClickable && v.minimumHeight >= (56f * d).toInt() &&
                v.getTag(LiquidTouch.SKIP_TAG_KEY) != true) rows.add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        var next = 0
        val step = object : Runnable {
            override fun run() {
                var n = 0
                while (next < rows.size && n < PER_FRAME) {
                    val row = rows[next++]
                    n++
                    LiquidTouch.decorate(row, cornerDp * d, intensity, accent)
                    pressSquish(row)
                }
                if (next < rows.size) root.postOnAnimation(this)
            }
        }
        root.postOnAnimation(step)
    }

    @SuppressLint("ClickableViewAccessibility")
    fun pressSquish(v: View) {
        if (v.getTag(SQUISH_TAG) != null) return
        v.setTag(SQUISH_TAG, true)
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> ViewSprings.pop(view, view.scaleX, 0.968f, 720f, 0.7f)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> ViewSprings.pop(view, view.scaleX, 1f, 380f, 0.45f)
            }
            false // never consumes: clicks and scrolling are untouched
        }
    }

    fun enter(views: List<View>, startDelay: Long = 0L, step: Long = 48L, sweepRadiusDp: Float = 28f) {
        if (GlassMode.plain()) return
        views.forEachIndexed { i, v ->
            if (v.visibility != View.VISIBLE) return@forEachIndexed
            val d = v.resources.displayMetrics.density
            val delay = startDelay + i * step
            v.animate().cancel()
            v.alpha = 0f; v.scaleX = 0.94f; v.scaleY = 0.94f; v.translationY = 30f * d
            v.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                .setStartDelay(delay).setDuration(440L)
                .setInterpolator(OvershootInterpolator(0.85f))
                .start()
            // the glow of the transition: a light band sweeps across each block as it lands
            GlassMotion.sweep(v, sweepRadiusDp, delay + 240L)
        }
    }
}
