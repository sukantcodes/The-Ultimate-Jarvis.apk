package com.jarvis.assistant.ui.chat.liquid

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import androidx.annotation.RequiresApi
import com.jarvis.assistant.ui.chat.GlassMotion

/**
 * Motion for the ChatBox liquid glass. Messages land like drops of liquid (a springy rise that settles) and come into
 * focus out of a soft blur; chips and controls squish under a finger and spring back. Every effect is skipped when the
 * system animation scale is zero, and none of it is used by the message composer.
 */
object LiquidMotion {

    private val tagArrive = "liquid_arrive".hashCode()
    private val spring = TimeInterpolator { LiquidMath.spring(it) }
    private val decelerate = PathInterpolator(0.2f, 0f, 0f, 1f)

    /**
     * A message arrives: it rises with a damped-spring settle, scales up from its bottom edge and, on phones that can blur a
     * view (Android 12+), sharpens out of a soft blur. [fade] is off when the list's own animator is already fading the item.
     */
    fun arrive(view: View, fade: Boolean, riseDp: Float = 26f): Animator? {
        if (!GlassMotion.animationsOn(view)) return null
        val d = view.resources.displayMetrics.density
        (view.getTag(tagArrive) as? Animator)?.cancel()
        view.pivotX = view.width / 2f
        view.pivotY = view.height.toFloat()
        view.translationY = riseDp * d
        view.scaleX = 0.955f
        view.scaleY = 0.955f

        val parts = ArrayList<Animator>()
        parts += ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, riseDp * d, 0f).apply { duration = 640L; interpolator = spring }
        parts += ObjectAnimator.ofFloat(view, View.SCALE_X, 0.955f, 1f).apply { duration = 640L; interpolator = spring }
        parts += ObjectAnimator.ofFloat(view, View.SCALE_Y, 0.955f, 1f).apply { duration = 640L; interpolator = spring }
        if (fade) parts += ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f).apply { duration = 280L; interpolator = decelerate }
        val tallest = view.resources.displayMetrics.heightPixels
        if (Build.VERSION.SDK_INT >= 31 && view.height in 1 until tallest) parts += focusBlur(view, 9f * d, 460L)

        val set = AnimatorSet()
        set.playTogether(parts)
        set.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) { settle(view) }
            override fun onAnimationCancel(animation: Animator) { settle(view) }
        })
        view.setTag(tagArrive, set)
        set.start()
        return set
    }

    /** Puts a view back to rest after [arrive], whichever way the animation ended. */
    fun settle(view: View) {
        view.setTag(tagArrive, null)
        view.translationY = 0f
        view.scaleX = 1f
        view.scaleY = 1f
        if (Build.VERSION.SDK_INT >= 31) clearBlur(view)
    }

    @RequiresApi(31)
    private fun focusBlur(view: View, maxRadiusPx: Float, durationMs: Long): Animator =
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                val r = LiquidMath.focusBlur(it.animatedValue as Float, maxRadiusPx)
                view.setRenderEffect(if (r < 0.5f) null else RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP))
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { clearBlur(view) }
                override fun onAnimationCancel(animation: Animator) { clearBlur(view) }
            })
        }

    @RequiresApi(31)
    private fun clearBlur(view: View) {
        view.setRenderEffect(null)
    }

    /** Brings a block of text into focus: it rises a little while its alpha comes up. Used for the first words of an answer. */
    fun focusIn(view: View, riseDp: Float = 10f) {
        if (!GlassMotion.animationsOn(view)) return
        val d = view.resources.displayMetrics.density
        view.animate().cancel()
        view.alpha = 0f
        view.translationY = riseDp * d
        view.animate().alpha(1f).translationY(0f).setDuration(420L).setInterpolator(decelerate).start()
    }

    /** Children come in one after another, each rising and settling with a spring. Used for the welcome screen. */
    fun staggerIn(views: List<View>, startDelayMs: Long = 90L, stepMs: Long = 75L, riseDp: Float = 20f) {
        views.forEachIndexed { i, v ->
            v.animate().cancel()
            if (!GlassMotion.animationsOn(v)) {
                v.alpha = 1f; v.translationY = 0f; v.scaleX = 1f; v.scaleY = 1f
                return@forEachIndexed
            }
            val d = v.resources.displayMetrics.density
            val delay = startDelayMs + stepMs * i
            v.alpha = 0f
            v.translationY = riseDp * d
            v.scaleX = 0.96f
            v.scaleY = 0.96f
            ObjectAnimator.ofFloat(v, View.ALPHA, 0f, 1f).apply {
                startDelay = delay
                duration = 320L
                interpolator = decelerate
                start()
            }
            v.animate().translationY(0f).scaleX(1f).scaleY(1f)
                .setStartDelay(delay).setDuration(620L).setInterpolator(spring).start()
        }
    }

    /** The top bar floats down into place with a springy settle when the chat opens. */
    fun headerIn(view: View, dropDp: Float = 22f) {
        if (!GlassMotion.animationsOn(view)) return
        val d = view.resources.displayMetrics.density
        view.animate().cancel()
        view.alpha = 0f
        view.translationY = -dropDp * d
        view.animate().alpha(1f).translationY(0f).setStartDelay(40L).setDuration(720L).setInterpolator(spring)
            .withEndAction { view.alpha = 1f; view.translationY = 0f }.start()
    }

    /** A finger on the view presses the glass in; letting go springs it back. Never consumes the touch, so clicks still work. */
    @SuppressLint("ClickableViewAccessibility")
    fun pressFeel(view: View) {
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(0.965f).scaleY(0.965f).setStartDelay(0L).setDuration(120L).setInterpolator(decelerate).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setStartDelay(0L).setDuration(460L).setInterpolator(spring).start()
            }
            false
        }
    }
}
