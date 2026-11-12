package com.jarvis.assistant.ui.chat

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.AnimationSet
import android.view.animation.DecelerateInterpolator
import android.view.animation.TranslateAnimation
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.jarvis.assistant.R

/**
 * Adapter for rendering Claude-style animated Work Step Cards with collapsible code blocks.
 */
class WorkStepAdapter : ListAdapter<WorkStepCard, WorkStepAdapter.ViewHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<WorkStepCard>() {
            override fun areItemsTheSame(a: WorkStepCard, b: WorkStepCard) = a.id == b.id
            override fun areContentsTheSame(a: WorkStepCard, b: WorkStepCard) = a == b
        }
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val ivWorkIcon: ImageView = itemView.findViewById(R.id.ivWorkIcon)
        val ivWorkFrame: View = itemView.findViewById(R.id.ivWorkFrame)
        val tvWorkStepTitle: TextView = itemView.findViewById(R.id.tvWorkStepTitle)
        val tvWorkStepStatus: TextView = itemView.findViewById(R.id.tvWorkStepStatus)
        val pbWorkStep: ProgressBar = itemView.findViewById(R.id.pbWorkStep)
        val ivWorkCheck: ImageView = itemView.findViewById(R.id.ivWorkCheck)
        val layoutCodeBlock: ViewGroup = itemView.findViewById(R.id.layoutCodeBlock)
        val tvCodeToggle: TextView = itemView.findViewById(R.id.tvCodeToggle)
        val scrollCodeContent: View = itemView.findViewById(R.id.scrollCodeContent)
        val tvCodeContent: TextView = itemView.findViewById(R.id.tvCodeContent)

        private var isCodeExpanded = false
        private var pulseAnimator: ObjectAnimator? = null

        fun bind(card: WorkStepCard) {
            tvWorkStepTitle.text = card.title

            val isDone = card.status == WorkStepStatus.DONE
            val isWriting = card.status == WorkStepStatus.IN_PROGRESS

            // Animate work icon using WorkIconAnimator
            WorkIconAnimator.applyWorkAnimation(
                iconView = ivWorkIcon,
                frameView = ivWorkFrame,
                type = card.type,
                isWriting = isWriting
            )

            // Dynamic status badge text
            val badgeText = when (card.status) {
                WorkStepStatus.IN_PROGRESS -> card.type.activeBadgeText(card.title)
                WorkStepStatus.DONE        -> card.type.doneBadgeText(card.title)
                WorkStepStatus.ERROR       -> "✗ Error"
            }
            tvWorkStepStatus.text = badgeText

            // State indicators
            when (card.status) {
                WorkStepStatus.IN_PROGRESS -> {
                    pbWorkStep.visibility = View.VISIBLE
                    ivWorkCheck.visibility = View.GONE
                    startPulse()
                }
                WorkStepStatus.DONE, WorkStepStatus.ERROR -> {
                    stopPulse()
                    pbWorkStep.visibility = View.GONE
                    ivWorkCheck.visibility = View.VISIBLE
                }
            }

            // Collapsible code block
            val hasCode = !card.codeSnippet.isNullOrBlank()
            if (hasCode) {
                layoutCodeBlock.visibility = View.VISIBLE
                tvCodeContent.text = card.codeSnippet

                // Reset state to collapsed
                isCodeExpanded = false
                tvCodeToggle.text = "▶ CODE"
                scrollCodeContent.visibility = View.GONE
                val lp = scrollCodeContent.layoutParams
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                scrollCodeContent.layoutParams = lp

                tvCodeToggle.setOnClickListener {
                    if (isCodeExpanded) {
                        collapseCode()
                    } else {
                        expandCode()
                    }
                }
            } else {
                layoutCodeBlock.visibility = View.GONE
                tvCodeToggle.setOnClickListener(null)
            }
        }

        private fun startPulse() {
            if (pulseAnimator == null) {
                pulseAnimator = ObjectAnimator.ofFloat(pbWorkStep, "alpha", 0.35f, 1.0f).apply {
                    duration = 650
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    start()
                }
            }
        }

        private fun stopPulse() {
            pulseAnimator?.cancel()
            pulseAnimator = null
            pbWorkStep.alpha = 1f
        }

        private fun expandCode() {
            isCodeExpanded = true
            tvCodeToggle.text = "▼ CODE"
            scrollCodeContent.visibility = View.VISIBLE
            scrollCodeContent.measure(
                View.MeasureSpec.makeMeasureSpec(itemView.width, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val maxHeightPx = (120 * itemView.resources.displayMetrics.density).toInt()
            val measuredH = scrollCodeContent.measuredHeight
            val targetHeight = if (measuredH > 0) minOf(measuredH, maxHeightPx) else maxHeightPx

            val anim = ValueAnimator.ofInt(0, targetHeight).apply {
                duration = 200
                interpolator = DecelerateInterpolator()
                addUpdateListener { va ->
                    val lp = scrollCodeContent.layoutParams
                    lp.height = va.animatedValue as Int
                    scrollCodeContent.layoutParams = lp
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        val lp = scrollCodeContent.layoutParams
                        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        scrollCodeContent.layoutParams = lp
                    }
                })
            }
            anim.start()
        }

        private fun collapseCode() {
            isCodeExpanded = false
            tvCodeToggle.text = "▶ CODE"
            val initialHeight = scrollCodeContent.height
            val anim = ValueAnimator.ofInt(initialHeight, 0).apply {
                duration = 180
                interpolator = DecelerateInterpolator()
                addUpdateListener { va ->
                    val lp = scrollCodeContent.layoutParams
                    lp.height = va.animatedValue as Int
                    scrollCodeContent.layoutParams = lp
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        scrollCodeContent.visibility = View.GONE
                        val lp = scrollCodeContent.layoutParams
                        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        scrollCodeContent.layoutParams = lp
                    }
                })
            }
            anim.start()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_work_step_card, parent, false)
        // Slide-up + fade-in entrance animation
        val slideUp = TranslateAnimation(
            Animation.RELATIVE_TO_SELF, 0f, Animation.RELATIVE_TO_SELF, 0f,
            Animation.RELATIVE_TO_SELF, 0.25f, Animation.RELATIVE_TO_SELF, 0f
        ).apply { duration = 260 }
        val fadeIn = AlphaAnimation(0f, 1f).apply { duration = 260 }
        view.startAnimation(AnimationSet(false).apply {
            addAnimation(slideUp)
            addAnimation(fadeIn)
        })
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}
