package com.jarvis.assistant.util

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
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
 * Predictive back everywhere (the same feel as the Claude app).
 *
 * While the person holds a back swipe from the left or right edge, the screen follows the finger: it shrinks, gains
 * rounded corners and leans toward the swipe direction. Letting go commits; moving back (cancelling) springs the
 * screen back to exactly how it was. The back button and 3-button navigation get the exit animation without the hold.
 *
 * Three ways it is used:
 *  - [install] gives every screen that was opened from another screen the animation automatically (Android 15+ draws
 *    its own real cross-activity animation for opted-in apps, so it is skipped there).
 *  - [MotionCallback] is for screens that handle back themselves (terminal, sign-in, onboarding, the main tabs...):
 *    they extend it, say when back will really leave, and keep their own logic in handleOnBackPressed.
 *  - [Motion] is the animation itself, if a screen needs to drive a specific view.
 */
object PredictiveBack {

    fun install(app: Application) {
        if (Build.VERSION.SDK_INT >= 35) return
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                // Registered first = lowest priority, so any screen that handles back itself keeps priority and
                // only falls through to this when it lets go of back. The task's root screen is left to the system.
                if (activity is ComponentActivity && !activity.isTaskRoot) {
                    activity.onBackPressedDispatcher.addCallback(activity, LeaveScreen(activity))
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

    /** Finishes [activity] with the shared "leave toward the previous screen" transition. */
    fun finishWithTransition(activity: Activity) {
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

    /** The follow-the-finger animation on one view (the whole screen by default). */
    class Motion(activity: Activity, private val target: () -> View?) {
        private val density = activity.resources.displayMetrics.density
        private val interpolator = DecelerateInterpolator()
        private var corner = 0f
        private var edgeSign = 1f
        private var progress = 0f
        private var settle: ValueAnimator? = null

        private val cornerOutline = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, corner)
            }
        }

        fun start(event: BackEventCompat) {
            val v = target() ?: return
            settle?.cancel()
            v.pivotX = v.width / 2f
            v.pivotY = v.height / 2f
            v.outlineProvider = cornerOutline
            v.clipToOutline = true
            edgeSign = if (event.swipeEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f
        }

        fun progress(event: BackEventCompat) {
            val v = target() ?: return
            progress = event.progress
            pose(v, interpolator.getInterpolation(progress))
        }

        /** The swipe was cancelled: spring back to exactly how it was. */
        fun cancel() {
            val v = target() ?: return
            settle?.cancel()
            val from = interpolator.getInterpolation(progress)
            progress = 0f
            settle = ValueAnimator.ofFloat(from, 0f).apply {
                duration = 240L
                this.interpolator = DecelerateInterpolator(1.6f)
                addUpdateListener { pose(v, it.animatedValue as Float) }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) = release(v)
                })
                start()
            }
        }

        /** Puts the view back instantly (used when back is committed but the screen stays, e.g. tab to Home). */
        fun clear() {
            val v = target() ?: return
            settle?.cancel()
            progress = 0f
            release(v)
        }

        private fun release(v: View) {
            pose(v, 0f)
            v.clipToOutline = false
            v.outlineProvider = ViewOutlineProvider.BACKGROUND
        }

        /** 0 = untouched, 1 = fully pulled (smaller, rounded, leaning toward the swipe, slightly faded). */
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

    /**
     * Base for a back callback that animates while the finger is down. [shouldAnimate] answers, at the start of the
     * swipe, whether back will really leave this screen (closing a drawer or stepping inside a flow should not
     * shrink the screen).
     */
    abstract class MotionCallback(
        activity: Activity,
        target: () -> View? = { activity.findViewById(android.R.id.content) },
        private val shouldAnimate: () -> Boolean = { true }
    ) : OnBackPressedCallback(true) {
        private val motion = Motion(activity, target)
        private var active = false

        override fun handleOnBackStarted(backEvent: BackEventCompat) {
            active = shouldAnimate()
            if (active) motion.start(backEvent)
        }

        override fun handleOnBackProgressed(backEvent: BackEventCompat) {
            if (active) motion.progress(backEvent)
        }

        override fun handleOnBackCancelled() {
            if (active) motion.cancel()
            active = false
        }

        /** Call at the start of handleOnBackPressed when the screen stays (the swipe committed but nothing closes). */
        protected fun endMotion() {
            if (active) motion.clear()
            active = false
        }
    }

    private class LeaveScreen(private val activity: ComponentActivity) : MotionCallback(activity) {
        override fun handleOnBackPressed() = finishWithTransition(activity)
    }
}
