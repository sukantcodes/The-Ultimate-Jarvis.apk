package com.jarvis.assistant.studio.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.databinding.ActivityAutomationStudioBinding
import com.jarvis.assistant.studio.AutomationRule
import com.jarvis.assistant.studio.AutomationStore
import com.jarvis.assistant.util.ThemeManager

/**
 * The Automation Studio: Default automations (built in, editable, never deletable) and Custom automations
 * (created by you, with no limit on how many).
 */
class AutomationStudioActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAutomationStudioBinding
    private val store by lazy { AutomationStore.get(applicationContext) }

    private val adapter by lazy {
        AutomationAdapter(
            onOpen = { rule -> openEditor(rule.id) },
            onToggle = { rule, enabled -> store.setEnabled(rule.id, enabled) },
            onDelete = { rule -> confirmDelete(rule) }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityAutomationStudioBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ThemeManager.applyDynamicBackground(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.rvAutomations.layoutManager = LinearLayoutManager(this)
        binding.rvAutomations.adapter = adapter
        binding.btnAddAutomation.setOnClickListener { openEditor(null) }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val all = store.all()
        val defaults = all.filter { it.isDefault }
        val custom = all.filter { !it.isDefault }
        val rows = ArrayList<StudioRow>()
        rows.add(
            StudioRow.Header(
                "Default automations",
                "Built into Jarvis. You can edit them or switch them off, but they cannot be deleted."
            )
        )
        defaults.forEach { rows.add(StudioRow.Rule(it)) }
        rows.add(
            StudioRow.Header(
                "Custom automations (" + custom.size + ")",
                if (custom.isEmpty()) "You have not made any yet. Tap \"New automation\" to create your first one." else ""
            )
        )
        custom.forEach { rows.add(StudioRow.Rule(it)) }
        adapter.submit(rows)
    }

    private fun openEditor(ruleId: String?) {
        val intent = Intent(this, AutomationEditorActivity::class.java)
        if (ruleId != null) intent.putExtra(AutomationEditorActivity.EXTRA_RULE_ID, ruleId)
        startActivity(intent)
    }

    private fun confirmDelete(rule: AutomationRule) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete \"" + rule.name + "\"?")
            .setMessage("This automation will be removed for good.")
            .setPositiveButton("Delete") { _, _ ->
                if (store.delete(rule.id)) refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
