package com.jarvis.assistant.ui.chat

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.jarvis.assistant.R

/**
 * The live list of what Jarvis is doing: one compact line per step. A new line slides up and fades in, the line that
 * is running breathes, a failed one gets a red "Failed" tag, and a finished line can be tapped to open the request
 * that was sent and the response that came back (the arrow turns and the panel grows open).
 */
class WorkStepAdapter : ListAdapter<WorkStepCard, WorkStepAdapter.ViewHolder>(DIFF) {

    private val expanded = HashSet<String>()
    private val entered = HashSet<String>()

    /** Forget which rows were opened or already slid in; called when the list starts over. */
    fun resetState() {
        expanded.clear()
        entered.clear()
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<WorkStepCard>() {
            override fun areItemsTheSame(a: WorkStepCard, b: WorkStepCard) = a.id == b.id
            override fun areContentsTheSame(a: WorkStepCard, b: WorkStepCard) = a == b
        }
        private const val MAX_DETAIL_CHARS = 2500
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val rowMain: View = itemView.findViewById(R.id.layoutRowMain)
        private val title: TextView = itemView.findViewById(R.id.tvWorkStepTitle)
        private val status: TextView = itemView.findViewById(R.id.tvWorkStepStatus)
        private val chevron: TextView = itemView.findViewById(R.id.tvWorkStepChevron)
        private val detail: ViewGroup = itemView.findViewById(R.id.layoutDetail)
        private val requestBody: TextView = itemView.findViewById(R.id.tvRequestBody)
        private val responseLabel: TextView = itemView.findViewById(R.id.tvResponseLabel)
        private val responseBody: TextView = itemView.findViewById(R.id.tvResponseBody)
        private var pulse: ObjectAnimator? = null
        private var boundId: String? = null

        fun bind(card: WorkStepCard) {
            boundId = card.id
            title.text = card.title
            status.visibility = if (card.status == WorkStepStatus.ERROR) View.VISIBLE else View.GONE

            if (card.status == WorkStepStatus.IN_PROGRESS) startPulse() else stopPulse()

            val hasRequest = !card.request.isNullOrBlank()
            val hasResponse = !card.response.isNullOrBlank()
            val canOpen = (hasRequest || hasResponse) && card.status != WorkStepStatus.IN_PROGRESS
            if (hasRequest) requestBody.text = clip(card.request.orEmpty())
            requestBody.visibility = if (hasRequest) View.VISIBLE else View.GONE
            itemView.findViewById<View>(R.id.tvRequestLabel).visibility = if (hasRequest) View.VISIBLE else View.GONE
            if (hasResponse) responseBody.text = clip(card.response.orEmpty())
            responseBody.visibility = if (hasResponse) View.VISIBLE else View.GONE
            responseLabel.visibility = if (hasResponse) View.VISIBLE else View.GONE

            val open = canOpen && card.id in expanded
            chevron.visibility = if (canOpen) View.VISIBLE else View.GONE
            chevron.rotation = if (open) 90f else 0f
            detail.visibility = if (open) View.VISIBLE else View.GONE
            detail.layoutParams = detail.layoutParams.also { it.height = ViewGroup.LayoutParams.WRAP_CONTENT }

            rowMain.isClickable = canOpen
            rowMain.setOnClickListener(if (canOpen) View.OnClickListener { toggle(card.id) } else null)
            enter(card.id)
        }

        private fun clip(text: String): String = if (text.length > MAX_DETAIL_CHARS) text.take(MAX_DETAIL_CHARS) + "\n… (${text.length - MAX_DETAIL_CHARS} more characters)" else text

        private fun enter(id: String) {
            itemView.animate().cancel()
            if (entered.add(id)) {
                itemView.alpha = 0f
                itemView.translationY = 10f * itemView.resources.displayMetrics.density
                itemView.animate().alpha(1f).translationY(0f).setDuration(240).setInterpolator(DecelerateInterpolator()).start()
            } else {
                itemView.alpha = 1f
                itemView.translationY = 0f
            }
        }

        private fun startPulse() {
            if (pulse != null) return
            pulse = ObjectAnimator.ofFloat(title, "alpha", 0.5f, 1f).apply {
                duration = 800
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        }

        private fun stopPulse() {
            pulse?.cancel()
            pulse = null
            title.alpha = 1f
        }

        private fun toggle(id: String) {
            if (boundId != id) return
            if (id in expanded) { expanded.remove(id); collapse() } else { expanded.add(id); expand() }
        }

        private fun expand() {
            chevron.animate().rotation(90f).setDuration(180).start()
            detail.visibility = View.VISIBLE
            detail.measure(
                View.MeasureSpec.makeMeasureSpec(itemView.width.coerceAtLeast(1), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            animateHeight(0, detail.measuredHeight, hideAtEnd = false)
        }

        private fun collapse() {
            chevron.animate().rotation(0f).setDuration(180).start()
            animateHeight(detail.height, 0, hideAtEnd = true)
        }

        private fun animateHeight(from: Int, to: Int, hideAtEnd: Boolean) {
            ValueAnimator.ofInt(from, to).apply {
                duration = 200
                interpolator = DecelerateInterpolator()
                addUpdateListener { va ->
                    val lp = detail.layoutParams
                    lp.height = va.animatedValue as Int
                    detail.layoutParams = lp
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        val lp = detail.layoutParams
                        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        detail.layoutParams = lp
                        if (hideAtEnd) detail.visibility = View.GONE
                    }
                })
                start()
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_work_step_card, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}

/** A thin line between steps (none under the last one), drawn at paint time so adding a step never needs a rebind. */
class StepDividerDecoration(color: Int, private val thicknessPx: Int) : RecyclerView.ItemDecoration() {
    private val paint = Paint().also { it.color = color }

    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val total = parent.adapter?.itemCount ?: return
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val pos = parent.getChildAdapterPosition(child)
            if (pos == RecyclerView.NO_POSITION || pos >= total - 1) continue
            val y = child.bottom + child.translationY
            c.drawRect(0f, y - thicknessPx, parent.width.toFloat(), y, paint)
        }
    }
}
