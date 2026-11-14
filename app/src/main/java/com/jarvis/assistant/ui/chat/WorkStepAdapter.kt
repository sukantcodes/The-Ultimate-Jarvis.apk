package com.jarvis.assistant.ui.chat

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.AnimationSet
import android.view.animation.DecelerateInterpolator
import android.view.animation.TranslateAnimation
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.jarvis.assistant.R

/**
 * Enterprise-grade adapter for replicating Claude mobile working interface task rows.
 * Features:
 * - Large bordered panel compatibility
 * - Inline smooth expand/collapse animations
 * - 90-degree chevron rotation
 * - Request/Response code syntax highlighting via ClaudeCodeHighlighter
 * - Full-width dividers, hidden on the final row
 * - Zero static hex colors, completely dynamic theme synchronization
 */
class WorkStepAdapter : ListAdapter<WorkStepCard, WorkStepAdapter.ViewHolder>(DIFF) {

    private val expandedIds = mutableSetOf<String>()

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<WorkStepCard>() {
            override fun areItemsTheSame(a: WorkStepCard, b: WorkStepCard) = a.id == b.id
            override fun areContentsTheSame(a: WorkStepCard, b: WorkStepCard) =
                a.id == b.id && a.title == b.title && a.status == b.status &&
                a.requestCode == b.requestCode && a.responseCode == b.responseCode &&
                a.codeSnippet == b.codeSnippet && a.isExpanded == b.isExpanded
        }
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val layoutRowHeader: LinearLayout = itemView.findViewById(R.id.layoutRowHeader)
        val tvTaskDescription: TextView = itemView.findViewById(R.id.tvTaskDescription)
        val tvTaskStatusBadge: TextView = itemView.findViewById(R.id.tvTaskStatusBadge)
        val ivTaskChevron: ImageView = itemView.findViewById(R.id.ivTaskChevron)
        val layoutRowExpandedContent: LinearLayout = itemView.findViewById(R.id.layoutRowExpandedContent)
        val layoutRequestBlock: LinearLayout = itemView.findViewById(R.id.layoutRequestBlock)
        val tvRequestCode: TextView = itemView.findViewById(R.id.tvRequestCode)
        val layoutResponseBlock: LinearLayout = itemView.findViewById(R.id.layoutResponseBlock)
        val tvResponseCode: TextView = itemView.findViewById(R.id.tvResponseCode)
        val viewRowDivider: View = itemView.findViewById(R.id.viewRowDivider)

        private var activeAnimator: ValueAnimator? = null

        fun bind(card: WorkStepCard, isLastItem: Boolean) {
            val context = itemView.context
            tvTaskDescription.text = card.title

            // Failed status badge
            if (card.status == WorkStepStatus.ERROR) {
                tvTaskStatusBadge.visibility = View.VISIBLE
                tvTaskStatusBadge.text = "Failed"
            } else {
                tvTaskStatusBadge.visibility = View.GONE
            }

            // Divider: visible for all rows except the last one in the panel
            viewRowDivider.visibility = if (isLastItem) View.GONE else View.VISIBLE

            // Code content preparation
            val req = card.requestCode?.trim()
            val resp = (card.responseCode ?: card.codeSnippet)?.trim()
            val hasContent = !req.isNullOrBlank() || !resp.isNullOrBlank()

            if (!req.isNullOrBlank()) {
                layoutRequestBlock.visibility = View.VISIBLE
                tvRequestCode.text = ClaudeCodeHighlighter.highlight(context, req)
            } else {
                layoutRequestBlock.visibility = View.GONE
            }

            if (!resp.isNullOrBlank()) {
                layoutResponseBlock.visibility = View.VISIBLE
                tvResponseCode.text = ClaudeCodeHighlighter.highlight(context, resp)
            } else {
                layoutResponseBlock.visibility = View.GONE
            }

            val isExpanded = expandedIds.contains(card.id) || card.isExpanded
            if (isExpanded && hasContent) {
                layoutRowExpandedContent.visibility = View.VISIBLE
                ivTaskChevron.rotation = 90f
            } else {
                layoutRowExpandedContent.visibility = View.GONE
                ivTaskChevron.rotation = 0f
            }

            if (hasContent) {
                layoutRowHeader.setOnClickListener {
                    toggleExpansion(card)
                }
            } else {
                layoutRowHeader.setOnClickListener(null)
            }
        }

        private fun toggleExpansion(card: WorkStepCard) {
            val willExpand = !expandedIds.contains(card.id) && !card.isExpanded
            if (willExpand) {
                expandedIds.add(card.id)
                card.isExpanded = true
                expandInline()
            } else {
                expandedIds.remove(card.id)
                card.isExpanded = false
                collapseInline()
            }
        }

        private fun expandInline() {
            activeAnimator?.cancel()
            layoutRowExpandedContent.visibility = View.VISIBLE
            layoutRowExpandedContent.measure(
                View.MeasureSpec.makeMeasureSpec(itemView.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val targetHeight = layoutRowExpandedContent.measuredHeight.coerceAtLeast(1)

            val anim = ValueAnimator.ofInt(0, targetHeight).apply {
                duration = 220
                interpolator = DecelerateInterpolator()
                addUpdateListener { va ->
                    val lp = layoutRowExpandedContent.layoutParams
                    lp.height = va.animatedValue as Int
                    layoutRowExpandedContent.layoutParams = lp
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        val lp = layoutRowExpandedContent.layoutParams
                        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        layoutRowExpandedContent.layoutParams = lp
                    }
                })
            }
            activeAnimator = anim
            anim.start()

            // Smooth 90-degree chevron rotation
            ivTaskChevron.animate().rotation(90f).setDuration(200).start()
        }

        private fun collapseInline() {
            activeAnimator?.cancel()
            val initialHeight = layoutRowExpandedContent.height
            val anim = ValueAnimator.ofInt(initialHeight, 0).apply {
                duration = 200
                interpolator = DecelerateInterpolator()
                addUpdateListener { va ->
                    val lp = layoutRowExpandedContent.layoutParams
                    lp.height = va.animatedValue as Int
                    layoutRowExpandedContent.layoutParams = lp
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        layoutRowExpandedContent.visibility = View.GONE
                        val lp = layoutRowExpandedContent.layoutParams
                        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        layoutRowExpandedContent.layoutParams = lp
                    }
                })
            }
            activeAnimator = anim
            anim.start()

            // Smooth chevron rotation back to 0 degrees
            ivTaskChevron.animate().rotation(0f).setDuration(200).start()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_claude_task_row, parent, false)
        // Subtle slide-up + fade-in entrance animation
        val slideUp = TranslateAnimation(
            Animation.RELATIVE_TO_SELF, 0f, Animation.RELATIVE_TO_SELF, 0f,
            Animation.RELATIVE_TO_SELF, 0.15f, Animation.RELATIVE_TO_SELF, 0f
        ).apply { duration = 200 }
        val fadeIn = AlphaAnimation(0f, 1f).apply { duration = 200 }
        view.startAnimation(AnimationSet(false).apply {
            addAnimation(slideUp)
            addAnimation(fadeIn)
        })
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val isLast = position == itemCount - 1
        holder.bind(getItem(position), isLast)
    }
}
