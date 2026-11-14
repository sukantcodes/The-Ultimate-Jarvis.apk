package com.jarvis.assistant.ui.chat

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.jarvis.assistant.R

/**
 * Enterprise-grade footer adapter for the scrollable Claude mobile working interface presentation.
 * Bundles:
 * 1. Contemplating / Reasoning indicator (amber dots, live duration, chevron)
 * 2. Large bordered Task Plan Panel (stacked expandable task rows)
 * 3. Context Compaction presentation (smooth progress track + percentage)
 */
class ClaudeTaskFooterAdapter(
    private val workStepAdapter: WorkStepAdapter
) : RecyclerView.Adapter<ClaudeTaskFooterAdapter.FooterViewHolder>() {

    data class FooterState(
        val isContemplating: Boolean = false,
        val contemplationStartTime: Long = 0L,
        val contemplationLabel: String = "Contemplating",
        val hasTaskSteps: Boolean = false,
        val isCompacting: Boolean = false,
        val compactionProgress: Int = 0
    ) {
        val isVisible: Boolean
            get() = isContemplating || hasTaskSteps || isCompacting
    }

    private var state = FooterState()
    private val uiHandler = Handler(Looper.getMainLooper())

    fun updateState(newState: FooterState) {
        val oldVisible = state.isVisible
        state = newState
        val newVisible = state.isVisible
        if (oldVisible != newVisible) {
            notifyDataSetChanged()
        } else if (newVisible) {
            notifyItemChanged(0)
        }
    }

    override fun getItemCount(): Int = if (state.isVisible) 1 else 0

    inner class FooterViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val layoutContemplating: LinearLayout = itemView.findViewById(R.id.layoutContemplating)
        val tvContemplatingText: TextView = itemView.findViewById(R.id.tvContemplatingText)
        val tvContemplatingDuration: TextView = itemView.findViewById(R.id.tvContemplatingDuration)
        val ivContemplatingChevron: ImageView = itemView.findViewById(R.id.ivContemplatingChevron)

        val cardTaskPlanPanel: FrameLayout = itemView.findViewById(R.id.cardTaskPlanPanel)
        val rvWorkSteps: RecyclerView = itemView.findViewById(R.id.rvWorkSteps)

        val layoutContextCompaction: LinearLayout = itemView.findViewById(R.id.layoutContextCompaction)
        val pbCompactionProgress: ProgressBar = itemView.findViewById(R.id.pbCompactionProgress)
        val tvCompactionPercent: TextView = itemView.findViewById(R.id.tvCompactionPercent)

        private var durationTicker: Runnable? = null
        private var compactionAnimator: ValueAnimator? = null

        init {
            rvWorkSteps.layoutManager = LinearLayoutManager(itemView.context)
            rvWorkSteps.adapter = workStepAdapter
        }

        fun bind(footerState: FooterState) {
            stopDurationTicker()

            // 1. Contemplating Bar
            if (footerState.isContemplating) {
                layoutContemplating.visibility = View.VISIBLE
                tvContemplatingText.text = footerState.contemplationLabel
                startDurationTicker(footerState.contemplationStartTime)
            } else {
                layoutContemplating.visibility = View.GONE
            }

            // 2. Large Bordered Task Plan Panel
            if (footerState.hasTaskSteps) {
                cardTaskPlanPanel.visibility = View.VISIBLE
            } else {
                cardTaskPlanPanel.visibility = View.GONE
            }

            // 3. Context Compaction Presentation
            if (footerState.isCompacting) {
                layoutContextCompaction.visibility = View.VISIBLE
                val targetPct = footerState.compactionProgress.coerceIn(0, 100)
                animateCompactionProgress(targetPct)
            } else {
                layoutContextCompaction.visibility = View.GONE
                compactionAnimator?.cancel()
            }
        }

        private fun startDurationTicker(startTime: Long) {
            val t0 = if (startTime > 0L) startTime else System.currentTimeMillis()
            val ticker = object : Runnable {
                override fun run() {
                    val elapsedSec = ((System.currentTimeMillis() - t0) / 1000L).coerceAtLeast(0)
                    val minutes = elapsedSec / 60
                    val seconds = elapsedSec % 60
                    tvContemplatingDuration.text = if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
                    uiHandler.postDelayed(this, 1000L)
                }
            }
            durationTicker = ticker
            ticker.run()
        }

        fun stopDurationTicker() {
            durationTicker?.let { uiHandler.removeCallbacks(it) }
            durationTicker = null
        }

        private fun animateCompactionProgress(targetPct: Int) {
            val current = pbCompactionProgress.progress
            if (current == targetPct) {
                tvCompactionPercent.text = "$targetPct%"
                return
            }
            compactionAnimator?.cancel()
            val anim = ValueAnimator.ofInt(current, targetPct).apply {
                duration = 300
                interpolator = DecelerateInterpolator()
                addUpdateListener { va ->
                    val v = va.animatedValue as Int
                    pbCompactionProgress.progress = v
                    tvCompactionPercent.text = "$v%"
                }
            }
            compactionAnimator = anim
            anim.start()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FooterViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.layout_claude_task_footer, parent, false)
        return FooterViewHolder(view)
    }

    override fun onBindViewHolder(holder: FooterViewHolder, position: Int) {
        holder.bind(state)
    }

    override fun onViewRecycled(holder: FooterViewHolder) {
        super.onViewRecycled(holder)
        holder.stopDurationTicker()
    }
}
