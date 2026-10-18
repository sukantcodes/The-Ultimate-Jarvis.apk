package com.jarvis.assistant.automation.studio.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.jarvis.assistant.R
import com.jarvis.assistant.automation.studio.model.AutomationRule
import com.jarvis.assistant.automation.studio.model.TriggerType
import com.jarvis.assistant.databinding.ItemAutomationRuleBinding
import com.jarvis.assistant.util.ThemeManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AutomationRuleAdapter(
    private val onToggleEnabled: (rule: AutomationRule, isEnabled: Boolean) -> Unit,
    private val onRunNow: (rule: AutomationRule) -> Unit,
    private val onEditRule: (rule: AutomationRule) -> Unit,
    private val onDeleteRule: (rule: AutomationRule) -> Unit
) : ListAdapter<AutomationRule, AutomationRuleAdapter.RuleViewHolder>(RuleDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RuleViewHolder {
        val binding = ItemAutomationRuleBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return RuleViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RuleViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class RuleViewHolder(val binding: ItemAutomationRuleBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(rule: AutomationRule) {
            // Apply Liquid Glass or Accessible Solid Surface dynamically
            ThemeManager.applyLiquidGlassCard(binding.cardRuleContainer, 18f)

            binding.tvRuleTitle.text = rule.title
            binding.tvRuleDescription.text = rule.description.ifBlank { "No description set" }

            // Trigger Icon and Badge
            when (rule.trigger.type) {
                TriggerType.SCHEDULED -> {
                    binding.ivTriggerIcon.setImageResource(R.drawable.ic_checklist_tasks)
                    binding.tvTriggerBadge.text = "⏰ ${rule.trigger.getDisplaySummary()}"
                }
                TriggerType.VOICE_COMMAND -> {
                    binding.ivTriggerIcon.setImageResource(R.drawable.ic_mic_jarvis)
                    binding.tvTriggerBadge.text = "🎙️ ${rule.trigger.getDisplaySummary()}"
                }
                TriggerType.DEVICE_EVENT -> {
                    binding.ivTriggerIcon.setImageResource(R.drawable.ic_sparkle)
                    binding.tvTriggerBadge.text = "⚡ ${rule.trigger.getDisplaySummary()}"
                }
            }

            // Actions Summary
            binding.tvActionsSummary.text = rule.getActionsDisplay()

            // Execution Metadata
            val metaText = if (rule.lastExecutedAt > 0) {
                val sdf = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
                "Run ${rule.executionCount}x • Last: ${sdf.format(Date(rule.lastExecutedAt))}"
            } else {
                "Never executed"
            }
            binding.tvExecutionMeta.text = metaText

            // Switch Enabled listener
            binding.switchEnabled.setOnCheckedChangeListener(null)
            binding.switchEnabled.isChecked = rule.isEnabled
            binding.switchEnabled.setOnCheckedChangeListener { _, isChecked ->
                onToggleEnabled(rule, isChecked)
            }

            // Action Buttons
            binding.btnRunNow.setOnClickListener { onRunNow(rule) }
            binding.btnEditRule.setOnClickListener { onEditRule(rule) }
            binding.btnDeleteRule.setOnClickListener { onDeleteRule(rule) }
        }
    }

    class RuleDiffCallback : DiffUtil.ItemCallback<AutomationRule>() {
        override fun areItemsTheSame(oldItem: AutomationRule, newItem: AutomationRule): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: AutomationRule, newItem: AutomationRule): Boolean {
            return oldItem == newItem
        }
    }
}
