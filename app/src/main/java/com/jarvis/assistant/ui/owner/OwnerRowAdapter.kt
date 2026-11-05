package com.jarvis.assistant.ui.owner

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.jarvis.assistant.R
import com.jarvis.assistant.owner.OwnerRow

/** Shows one page of devices / IPs / events. Only the current page is ever held in memory. */
class OwnerRowAdapter(private val onClick: (OwnerRow) -> Unit) : RecyclerView.Adapter<OwnerRowAdapter.Holder>() {
    private var rows: List<OwnerRow> = emptyList()

    fun submit(newRows: List<OwnerRow>) { rows = newRows; notifyDataSetChanged() }

    class Holder(view: View) : RecyclerView.ViewHolder(view)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_owner_row, parent, false))

    override fun onBindViewHolder(h: Holder, position: Int) {
        val row = rows[position]
        bindRow(h.itemView, row)
        h.itemView.setOnClickListener { onClick(row) }
    }

    override fun getItemCount() = rows.size

    companion object {
        fun bindRow(view: View, row: OwnerRow) {
            val title = view.findViewById<TextView>(R.id.tvRowTitle)
            val badge = view.findViewById<TextView>(R.id.tvRowBadge)
            title.text = row.title
            view.findViewById<TextView>(R.id.tvRowLine2).text = row.line2
            view.findViewById<TextView>(R.id.tvRowLine3).text = row.line3
            badge.text = row.badge
            badge.visibility = if (row.badge.isEmpty()) View.GONE else View.VISIBLE
            if (row.tone != 0) {
                badge.setTextColor(row.tone)
                badge.background = GradientDrawable().apply {
                    cornerRadius = 40f
                    setColor(Color.argb(40, Color.red(row.tone), Color.green(row.tone), Color.blue(row.tone)))
                    setStroke(2, Color.argb(120, Color.red(row.tone), Color.green(row.tone), Color.blue(row.tone)))
                }
                // A coloured strip on the card's left edge ties the row to its status or event type.
                view.background = GradientDrawable().apply {
                    cornerRadius = 36f
                    setColor(Color.argb(22, Color.red(row.tone), Color.green(row.tone), Color.blue(row.tone)))
                    setStroke(2, Color.argb(70, Color.red(row.tone), Color.green(row.tone), Color.blue(row.tone)))
                }
            }
        }
    }
}
