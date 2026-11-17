package com.jarvis.assistant.ui.glass

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * Gives a control its own 3D glass bubble (see [DockPodDrawable]) with a spring-physics press, a liquid ripple
 * and a selection glow. Used for the dock options and the ChatBox top bar. The touch listener only watches and never
 * consumes, so clicks and long-presses behave exactly as before.
 */
object DockPods {

    private class Entry(pod: DockPodDrawable, val group: String) {
        val ref = WeakReference(pod)
    }

    // Weak on both sides: the drawable points at its view, so a strong value would keep every old screen alive.
    private val pods = WeakHashMap<View, Entry>()

    /**
     * [circle] makes a round pod of [heightDp]; otherwise a pill up to [maxWidthDp] wide, [padDp] narrower than the view on each side.
     * [offsetYDp] lifts the pod (hero button). [interactive] = false gives a static pod (no touch listener) for non-clickable badges.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun install(
        tab: View,
        heightDp: Float = 46f,
        maxWidthDp: Float = 56f,
        circle: Boolean = false,
        offsetYDp: Float = 0f,
        padDp: Float = 8f,
        interactive: Boolean = true,
        group: String = "dock",
        hero: Boolean = false,
        touchHook: ((View, MotionEvent) -> Boolean)? = null
    ) {
        // Only the bottom dock (group "dock") is locked to glass; every other pod follows the Liquid Glass setting.
        if (group != "dock" && GlassMode.plain()) return
        val pod = DockPodDrawable(tab, heightDp, maxWidthDp, circle, offsetYDp, padDp, hero)
        tab.background = pod
        tab.setTag(LiquidTouch.SKIP_TAG_KEY, true) // the pod does its own press; no second squish or sheen on top
        if (interactive) {
            var down = false
            tab.setOnTouchListener { v, e ->
                val consumed = touchHook?.invoke(v, e) ?: false
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { pod.press(e.x, e.y); down = true }
                    MotionEvent.ACTION_MOVE -> if (consumed && down) { pod.release(); down = false } // a swipe took over
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { if (down) pod.release(); down = false }
                }
                consumed
            }
        }
        pods[tab] = Entry(pod, group)
    }

    private const val AUTO_KEY = 0x4A4C4150 // "JLAP"

    /**
     * A pod for a plain icon button found anywhere in the app. It sets no touch listener of its own (it must not replace one the
     * screen already has): the window-level touch feed presses and releases it, see [autoPod].
     */
    fun installAuto(view: View, sizeDp: Float) {
        if (GlassMode.plain()) return
        val pod = DockPodDrawable(view, sizeDp, sizeDp, true, 0f, 0f)
        view.background = pod
        view.setTag(AUTO_KEY, pod)
    }

    internal fun autoPod(view: View): DockPodDrawable? = view.getTag(AUTO_KEY) as? DockPodDrawable

    /** Lights the pod of the selected option in [group] and eases the others of that group back. */
    fun setActive(selected: View?, group: String = "dock") {
        for ((tab, entry) in pods) if (entry.group == group) entry.ref.get()?.setActive(tab === selected)
    }

    /** Switches the glow of one pod on or off without touching the others. */
    fun setGlow(view: View, on: Boolean) {
        pods[view]?.ref?.get()?.setActive(on)
    }
}
