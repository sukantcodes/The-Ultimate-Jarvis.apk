package com.jarvis.assistant.util

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Application
import android.graphics.Outline
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.jarvis.assistant.R

/**
 * Predictive back for every screen that was opened from another screen (the same feel as the Claude app).
 *
 * While the person holds a back swipe from the left or right edge, the screen follows the finger: it shrinks, gains
 * rounded corners and leans toward the swipe direction. Letting go commits the back and the screen leaves toward the
 * previous one; moving the finger back (cancelling) springs the screen back to exactly how it was.
 * The back button and 3-button navigation get the same exit animation without the hold phase.
 *
 * Android 15+ draws its own real cross-activity animation for apps that opt in (manifest
 * `enableOnBackInvokedCallback`), so this only installs on older versions. The task's root screen is left alone:
 * pressing back there should hand the app to the system (back to home).
 */
object PredictiveBack {

    fun install(app: Application) {
        if (Build.VERSION.SDK_INT >= 35) return
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                // Registered first = lowest priority, so any screen that handles back itself (dialogs, drawers,
                // "press again to exit") keeps priority and only falls through to this when it lets go of back.
                if (activity is ComponentActivity && !activity.isTaskRoot) {
                    activity.onBackPressedDispatcher.addCallback(activity, BackAnimation(activity))
                }
            }

            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private class BackAnimation(private val activity: ComponentActivity) : OnBackPressedCallback(true) {

        private val density = activity.resources.displayMetrics.density
        private val interpolator = DecelerateInterpolator()
        private var corner = 0f
        private var edgeSign = 1f
        private var current = 0f
        private var settle: ValueAnimator? = null

        private val cornerOutline = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, corner)
            }
        }

        private fun content(): View? = activity.findViewById(android.R.id.content)

        override fun handleOnBackStarted(backEvent: BackEventCompat) {
            val v = content() ?: return
            settle?.cancel()
            v.pivotX = v.width / 2f
            v.pivotY = v.height / 2f
            v.outlineProvider = cornerOutline
            v.clipToOutline = true
            edgeSign = if (backEvent.swipeEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f
        }

        override fun handleOnBackProgressed(backEvent: BackEventCompat) {
            val v = content() ?: return
            current = backEvent.progress
            pose(v, interpolator.getInterpolation(current))
        }

        override fun handleOnBackCancelled() {
            val v = content() ?: return
            settle?.cancel()
            val from = interpolator.getInterpolation(current)
            // Spring the screen back to exactly how it was (scale, position, corners and opacity).
            settle = ValueAnimator.ofFloat(from, 0f).apply {
                duration = 240L
                this.interpolator = DecelerateInterpolator(1.6f)
                addUpdateListener { pose(v, it.animatedValue as Float) }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        pose(v, 0f)
                        v.clipToOutline = false
                        v.outlineProvider = ViewOutlineProvider.BACKGROUND
                    }
                })
                start()
            }
            current = 0f
        }

        override fun handleOnBackPressed() {
            // Commit: the exit animation continues from wherever the swipe left the screen.
            settle?.cancel()
            if (Build.VERSION.SDK_INT >= 34) {
                activity.overrideActivityTransition(
                    Activity.OVERRIDE_TRANSITION_CLOSE, R.anim.jarvis_close_enter, R.anim.jarvis_close_exit
                )
                activity.finish()
            } else {
                activity.finish()
                @Suppress("DEPRECATION")
                activity.overridePendingTransition(R.anim.jarvis_close_enter, R.anim.jarvis_close_exit)
            }
        }

        /** progress 0 = untouched screen, 1 = fully pulled (smaller, rounded, leaning toward the swipe). */
        private fun pose(v: View, p: Float) {
            val s = 1f - 0.10f * p
            v.scaleX = s
            v.scaleY = s
            v.translationX = edgeSign * 16f * density * p
            v.alpha = 1f - 0.10f * p
            corner = 28f * density * p
            v.invalidateOutline()
        }
    }
}
