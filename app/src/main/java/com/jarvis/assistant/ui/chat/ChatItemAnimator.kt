package com.jarvis.assistant.ui.chat

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.view.View
import android.view.animation.PathInterpolator
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView

/**
 * The chat list's item animator. Messages still fade in as before, but now they also rise from below with a soft
 * spring-like settle (a short overshoot, then rest) and a gentle scale-up from the bottom edge, and the messages
 * above glide up over a slightly longer move when a new one arrives. Respects the system animation scale.
 */
class ChatItemAnimator : DefaultItemAnimator() {

    private val settle = PathInterpolator(0.18f, 0.9f, 0.22f, 1.06f)   // quick start, slight overshoot, soft landing

    init {
        addDuration = 420L
        moveDuration = 340L
        removeDuration = 180L
        supportsChangeAnimations = false
    }

    override fun onAddStarting(item: RecyclerView.ViewHolder) {
        val v = item.itemView
        val d = v.resources.displayMetrics.density
        (v.getTag(TAG) as? Animator)?.cancel()
        v.pivotX = v.width / 2f
        v.pivotY = v.height.toFloat()
        v.translationY = 30f * d
        v.scaleX = 0.965f
        v.scaleY = 0.965f
        val a = ObjectAnimator.ofPropertyValuesHolder(
            v,
            PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 30f * d, 0f),
            PropertyValuesHolder.ofFloat(View.SCALE_X, 0.965f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.965f, 1f)
        ).apply {
            duration = 460L
            interpolator = settle
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { clear(v) }
                override fun onAnimationCancel(animation: Animator) { clear(v) }
            })
        }
        v.setTag(TAG, a)
        a.start()
    }

    override fun onAddFinished(item: RecyclerView.ViewHolder) {
        (item.itemView.getTag(TAG) as? Animator)?.cancel()
        clear(item.itemView)
    }

    private fun clear(v: View) {
        v.setTag(TAG, null)
        v.translationY = 0f
        v.scaleX = 1f
        v.scaleY = 1f
    }

    private companion object { val TAG = "chat_item_enter".hashCode() }
}
