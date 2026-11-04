package com.jarvis.assistant.ui.owner

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.jarvis.assistant.R
import com.jarvis.assistant.owner.OwnerRow

/** Shows one page of devices / IPs / events. Only the current page is ever held in memory. */
class OwnerRowAdapter : RecyclerView.Adapter<OwnerRowAdapter.Holder>() {
    private var rows: List<OwnerRow> = emptyList()

    fun submit(newRows: List<OwnerRow>) { rows = newRows; notifyDataSetChanged() }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.tvRowTitle)
        val badge: TextView = view.findViewById(R.id.tvRowBadge)
        val line2: TextView = view.findViewById(R.id.tvRowLine2)
        val line3: TextView = view.findViewById(R.id.tvRowLine3)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_owner_row, parent, false))

    override fun onBindViewHolder(h: Holder, position: Int) = bindRow(h.itemView, rows[position])

    override fun getItemCount() = rows.size

    companion object {
        fun bindRow(view: View, row: OwnerRow) {
            view.findViewById<TextView>(R.id.tvRowTitle).text = row.title
            view.findViewById<TextView>(R.id.tvRowLine2).text = row.line2
            view.findViewById<TextView>(R.id.tvRowLine3).text = row.line3
            view.findViewById<TextView>(R.id.tvRowBadge).apply {
                text = row.badge
                visibility = if (row.badge.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }
}
