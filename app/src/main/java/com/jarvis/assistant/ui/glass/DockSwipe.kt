package com.jarvis.assistant.ui.glass

import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * Swipe a finger along the dock and the glass bubble moves with it. It trails the finger with a liquid lag, the option under it
 * lights up as it passes, and when you let go it keeps the momentum of your swipe and settles on the nearest option
 * (a flick carries it one option further). If that option is a screen it opens; if it is an action (voice, camera) the bubble
 * returns to where it was, so a swipe never starts something by accident.
 *
 * It only watches the touches of the dock options and takes over once the finger has clearly moved sideways,
 * so taps and long-presses work exactly as before.
 */
class DockSwipe(
    private val bubble: GlassBubbleView,
    private val tabs: List<View>,
    private val screens: Set<View>
) {
    private val loc = IntArray(2)
    private var startX = 0f
    private var dragging = false
    private var tracker: VelocityTracker? = null

    fun onTouch(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = false
                startX = e.rawX
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(e) }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(e)
                if (!dragging) {
                    val slop = ViewConfiguration.get(v.context).scaledTouchSlop * 1.4f
                    if (abs(e.rawX - startX) <= slop) return false
                    dragging = true
                    bubble.beginDrag()
                    v.cancelLongPress()
                    v.isPressed = false
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                follow(e.rawX)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasDragging = dragging
                if (wasDragging) finish(e)
                dragging = false
                tracker?.recycle()
                tracker = null
                return wasDragging
            }
        }
        return dragging
    }

    private fun centerX(t: View): Float {
        t.getLocationOnScreen(loc)
        return loc[0] + t.width / 2f
    }

    private fun nearest(rawX: Float): View? = tabs.minByOrNull { abs(centerX(it) - rawX) }

    private fun follow(rawX: Float) {
        val first = tabs.minOfOrNull { centerX(it) } ?: return
        val last = tabs.maxOfOrNull { centerX(it) } ?: return
        bubble.getLocationOnScreen(loc)
        bubble.dragTo(rawX.coerceIn(first, last) - loc[0])
        DockPods.setActive(nearest(rawX))
    }

    private fun finish(e: MotionEvent) {
        val t = tracker
        t?.addMovement(e)
        t?.computeCurrentVelocity(1000)
        val vx = t?.xVelocity ?: 0f
        val target = nearest(e.rawX + vx * 0.12f)
        bubble.endDrag()
        val current = bubble.currentTarget()
        if (target != null && target in screens && target !== current) {
            target.performClick() // opens the screen; the bubble keeps its momentum and glides onto it
        } else {
            bubble.settle()
            DockPods.setActive(current)
        }
    }
}
