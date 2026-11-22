package com.jarvis.assistant.ui.chat

import android.animation.Animator
import android.view.View
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView
import com.jarvis.assistant.R
import com.jarvis.assistant.ui.chat.liquid.LiquidMotion
import com.jarvis.assistant.ui.chat.liquid.LiquidPane

/**
 * The chat list's item animator. A new message lands like a drop of liquid: it rises with a damped-spring settle, grows
 * from its bottom edge and comes into focus out of a soft blur (Android 12+), then a band of light crosses its glass.
 * The messages above glide up while it does. Respects the system animation scale.
 */
class ChatItemAnimator : DefaultItemAnimator() {

    init {
        addDuration = 420L
        moveDuration = 380L
        removeDuration = 180L
        supportsChangeAnimations = false
    }

    override fun animateAdd(holder: RecyclerView.ViewHolder): Boolean {
        // DefaultItemAnimator fades the item in itself (alpha 0 -> 1), so the liquid arrival leaves alpha alone.
        val v = holder.itemView
        v.alpha = 0f
        return super.animateAdd(holder)
    }

    override fun onAddStarting(item: RecyclerView.ViewHolder) {
        val v = item.itemView
        LiquidMotion.arrive(v, fade = false)
        // Once the bubble has landed, a band of light slides across its glass.
        val glass = v.findViewById<View>(R.id.layoutAssistantGlass)?.takeIf { it.visibility == View.VISIBLE }
        val user = v.findViewById<View>(R.id.tvUserMessage)?.takeIf { it.visibility == View.VISIBLE }
        (glass ?: user)?.let { LiquidPane.glint(it, delayMs = 220L) }
    }

    override fun onAddFinished(item: RecyclerView.ViewHolder) {
        LiquidMotion.settle(item.itemView)
    }

    override fun endAnimation(item: RecyclerView.ViewHolder) {
        super.endAnimation(item)
        LiquidMotion.settle(item.itemView)
    }
}
