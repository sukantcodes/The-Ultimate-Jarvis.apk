package com.jarvis.assistant.util

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.view.Window
import android.view.WindowManager
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
            override fun onActivityStarted(activity: Activity) { apply(activity.window) }
            override fun onActivityResumed(activity: Activity) { apply(activity.window) }
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
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
