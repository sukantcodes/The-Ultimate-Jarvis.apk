package com.jarvis.assistant.ui.glass

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import java.util.WeakHashMap

/**
 * Gives every dock option its own 3D glass bubble (see [DockPodDrawable]) inside the dock's bubble, with its own
 * spring-physics press, liquid ripple and selection glow. The touch listener only watches and never consumes,
 * so clicks and long-presses behave exactly as before.
 */
object DockPods {

    private val pods = WeakHashMap<View, DockPodDrawable>()

    /** [circle] makes a round pod of [heightDp]; otherwise a pill up to [maxWidthDp] wide. [offsetYDp] lifts the pod (hero button). */
    @SuppressLint("ClickableViewAccessibility")
    fun install(tab: View, heightDp: Float = 46f, maxWidthDp: Float = 56f, circle: Boolean = false, offsetYDp: Float = 0f) {
        val pod = DockPodDrawable(tab, heightDp, maxWidthDp, circle, offsetYDp)
        tab.background = pod
        tab.setTag(LiquidTouch.SKIP_TAG_KEY, true) // the pod does its own press; no second squish on top
        tab.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> pod.press(e.x, e.y)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pod.release()
            }
            false
        }
        pods[tab] = pod
    }

    /** Lights the pod of the selected option and eases the others back. */
    fun setActive(selected: View?) {
        for ((tab, pod) in pods) pod.setActive(tab === selected)
    }
}
