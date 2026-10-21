package com.jarvis.assistant.studio.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.jarvis.assistant.databinding.ItemAutomationHeaderBinding
import com.jarvis.assistant.databinding.ItemAutomationRuleBinding
import com.jarvis.assistant.studio.AutomationRule

sealed class StudioRow {
    data class Header(val title: String, val note: String) : StudioRow()
    data class Rule(val rule: AutomationRule) : StudioRow()
}

class AutomationAdapter(
    private val onOpen: (AutomationRule) -> Unit,
    private val onToggle: (AutomationRule, Boolean) -> Unit,
    private val onDelete: (AutomationRule) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<StudioRow> = emptyList()

    fun submit(newRows: List<StudioRow>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = if (rows[position] is StudioRow.Header) TYPE_HEADER else TYPE_RULE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(ItemAutomationHeaderBinding.inflate(inflater, parent, false))
        } else {
            RuleHolder(ItemAutomationRuleBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is StudioRow.Header -> (holder as HeaderHolder).bind(row)
            is StudioRow.Rule -> (holder as RuleHolder).bind(row.rule)
        }
    }

    private class HeaderHolder(private val binding: ItemAutomationHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: StudioRow.Header) {
            binding.tvHeaderTitle.text = row.title
            binding.tvHeaderNote.text = row.note
            binding.tvHeaderNote.visibility = if (row.note.isBlank()) View.GONE else View.VISIBLE
        }
    }

    private inner class RuleHolder(private val binding: ItemAutomationRuleBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(rule: AutomationRule) {
            binding.tvRuleName.text = rule.name
            binding.tvRuleTrigger.text = AutomationSummary.trigger(rule)
            binding.tvRuleActions.text = AutomationSummary.actions(rule)
            binding.tvRuleBadge.visibility = if (rule.isDefault) View.VISIBLE else View.GONE

            // Default automations can be edited and switched off, but never deleted.
            binding.btnRuleDelete.visibility = if (rule.isDefault) View.GONE else View.VISIBLE
            binding.btnRuleDelete.setOnClickListener { onDelete(rule) }

            binding.switchRuleEnabled.setOnCheckedChangeListener(null)
            binding.switchRuleEnabled.isChecked = rule.enabled
            binding.switchRuleEnabled.setOnCheckedChangeListener { _, checked -> onToggle(rule, checked) }

            binding.root.setOnClickListener { onOpen(rule) }
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_RULE = 1
    }
}
