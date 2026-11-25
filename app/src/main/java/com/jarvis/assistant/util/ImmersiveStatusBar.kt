package com.jarvis.assistant.util

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.view.Window
import android.view.WindowManager
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager

/**
 * Keeps the system status bar (clock, battery, signal, Wi-Fi, notification icons) out of the way in every Jarvis screen.
 * It only shows when you swipe down from the top edge, then hides itself again. The picture or theme is painted behind
 * the whole screen, including the camera cut-out area, so nothing is left as a black strip at the top.
 */
object ImmersiveStatusBar {

    private var installed = false

    fun install(app: Application) {
        if (installed) return
        installed = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                (activity as? FragmentActivity)?.supportFragmentManager?.registerFragmentLifecycleCallbacks(
                    object : FragmentManager.FragmentLifecycleCallbacks() {
                        override fun onFragmentStarted(fm: FragmentManager, f: Fragment) {
                            // Bottom sheets and dialogs are their own windows and would bring the bar back while they are open.
                            (f as? DialogFragment)?.dialog?.window?.let { apply(it) }
                        }
                    }, true
                )
            }
            override fun onActivityStarted(activity: Activity) {
                com.jarvis.assistant.ui.glass.TransitionQuiet.begin()
                apply(activity.window); edgeToEdge(activity)
            }
            override fun onActivityResumed(activity: Activity) { apply(activity.window); edgeToEdge(activity) }
            override fun onActivityPaused(activity: Activity) { com.jarvis.assistant.ui.glass.TransitionQuiet.begin() }
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /**
     * Draws the app behind the (hidden) status bar and the camera cut-out, then pads the content so nothing is covered:
     * sides and bottom follow the cut-out, navigation bar and keyboard; the top follows the cut-out unless the screen
     * draws its own [JarvisStatusStrip] there. Works the same on phones, foldables and tablets.
     */
    private fun edgeToEdge(activity: Activity) {
        try {
            val window = activity.window
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val content = window.decorView.findViewById<ViewGroup>(android.R.id.content) ?: return
            if (com.jarvis.assistant.ui.glass.SettingsGlassKit.isBackdropOnly(activity)) {
                com.jarvis.assistant.ui.glass.SettingsGlassKit.addBackdrop(activity)
                return
            }
            if (activity is JarvisHandlesInsets || com.jarvis.assistant.ui.glass.SettingsGlassKit.isTarget(activity)) {
                com.jarvis.assistant.ui.glass.SettingsGlassKit.upgrade(activity)
                return
            }
            if (content.getTag(TAG_KEY) == "e2e") return
            content.setTag(TAG_KEY, "e2e")
            // Padding goes on the screen's own root view, not on the window content: the root's background then also
            // paints the strips behind the camera, bars and keyboard, so no window colour can leak through at the edges.
            ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
                val child = (v as ViewGroup).getChildAt(0)
                if (child != null) {
                    val cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
                    val bars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
                    val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
                    @Suppress("UNCHECKED_CAST")
                    val orig = (child.getTag(com.jarvis.assistant.R.id.jarvis_orig_padding) as? IntArray)
                        ?: intArrayOf(child.paddingLeft, child.paddingTop, child.paddingRight, child.paddingBottom)
                            .also { child.setTag(com.jarvis.assistant.R.id.jarvis_orig_padding, it) }
                    child.setPadding(
                        orig[0] + maxOf(cut.left, bars.left),
                        orig[1] + cut.top,
                        orig[2] + maxOf(cut.right, bars.right),
                        orig[3] + maxOf(bars.bottom, cut.bottom, ime.bottom)
                    )
                }
                insets
            }
            ViewCompat.requestApplyInsets(content)
        } catch (_: Throwable) { }
    }

    fun apply(window: Window) {
        hideNow(window)
        try {
            val decor = window.decorView
            decor.post { hideNow(window) }
            if (decor.getTag(TAG_KEY) == null) {
                decor.setTag(TAG_KEY, true)
                // The bar can come back when the window regains focus (after the keyboard, a dialog, or the shade); hide it again.
                decor.viewTreeObserver.addOnWindowFocusChangeListener { hasFocus -> if (hasFocus) hideNow(window) }
            }
        } catch (_: Throwable) { }
    }

    private fun hideNow(window: Window) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val lp = window.attributes
                if (lp.layoutInDisplayCutoutMode != WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES) {
                    lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    window.attributes = lp
                }
            }
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } catch (_: Throwable) { }
    }

    private val TAG_KEY = com.jarvis.assistant.R.id.immersive_status_bar_tag
}
