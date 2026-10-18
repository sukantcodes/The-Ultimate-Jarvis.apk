package com.jarvis.assistant.automation.studio.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.automation.studio.engine.AutomationEngine
import com.jarvis.assistant.automation.studio.model.AutomationRule
import com.jarvis.assistant.automation.studio.model.TriggerType
import com.jarvis.assistant.automation.studio.repository.AutomationRuleRepository
import com.jarvis.assistant.databinding.ActivityAutomationStudioBinding
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AutomationStudioActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAutomationStudioBinding
    private val repository by lazy { AutomationRuleRepository.getInstance(applicationContext) }
    private val engine by lazy { AutomationEngine.getInstance(applicationContext) }
    private val preferences by lazy { JarvisApp.instance.preferences }

    private var activeFilter: TriggerType? = null
    private var allRules: List<AutomationRule> = emptyList()

    private lateinit var adapter: AutomationRuleAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.applyTheme(this)
        binding = ActivityAutomationStudioBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupLiquidGlassSurfaces()
        setupRecyclerView()
        setupListeners()
        observeRules()
    }

    override fun onResume() {
        super.onResume()
        setupLiquidGlassSurfaces()
    }

    private fun setupLiquidGlassSurfaces() {
        val glassEnabled = preferences.isLiquidGlassEnabled
        binding.switchLiquidGlass.isChecked = glassEnabled
        binding.tvLiquidGlassStatus.text = if (glassEnabled) {
            "Liquid Glass Mode: Active (Specular Refractions)"
        } else {
            "Solid Surface Mode: Active (Battery Efficient)"
        }

        ThemeManager.applyLiquidGlassOrbs(binding.glowOrbTopRight, binding.glowOrbBottom)
        ThemeManager.applyLiquidGlassCard(binding.cardStats, 22f)

        if (::adapter.isInitialized) {
            adapter.notifyDataSetChanged()
        }
    }

    private fun setupRecyclerView() {
        adapter = AutomationRuleAdapter(
            onToggleEnabled = { rule, isEnabled ->
                repository.toggleRule(rule.id, isEnabled)
                if (rule.trigger.type == TriggerType.SCHEDULED) {
                    if (isEnabled) {
                        engine.scheduleRule(rule.copy(isEnabled = true))
                    } else {
                        engine.cancelSchedule(rule.id)
                    }
                }
                Toast.makeText(
                    this,
                    if (isEnabled) "'${rule.title}' enabled" else "'${rule.title}' disabled",
                    Toast.LENGTH_SHORT
                ).show()
            },
            onRunNow = { rule ->
                Toast.makeText(this, "Executing '${rule.title}'...", Toast.LENGTH_SHORT).show()
                engine.executeRule(rule.id, "Manual User Trigger")
            },
            onEditRule = { rule ->
                val intent = Intent(this, AutomationRuleEditorActivity::class.java).apply {
                    putExtra(AutomationRuleEditorActivity.EXTRA_RULE_ID, rule.id)
                }
                startActivity(intent)
            },
            onDeleteRule = { rule ->
                MaterialAlertDialogBuilder(this)
                    .setTitle("Delete Automation Rule?")
                    .setMessage("Are you sure you want to delete '${rule.title}'? This action cannot be undone.")
                    .setPositiveButton("Delete") { _, _ ->
                        engine.cancelSchedule(rule.id)
                        repository.deleteRule(rule.id)
                        Toast.makeText(this, "Rule deleted", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        )

        binding.rvAutomationRules.layoutManager = LinearLayoutManager(this)
        binding.rvAutomationRules.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnAddRule.setOnClickListener {
            startActivity(Intent(this, AutomationRuleEditorActivity::class.java))
        }

        binding.switchLiquidGlass.setOnCheckedChangeListener { _, isChecked ->
            preferences.isLiquidGlassEnabled = isChecked
            setupLiquidGlassSurfaces()
            Toast.makeText(
                this,
                if (isChecked) "Liquid Glass UI enabled" else "Solid Surface Fallback enabled",
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.btnExportImport.setOnClickListener {
            showExportImportDialog()
        }

        binding.btnResetDefaults.setOnClickListener {
            val defaults = repository.exportRulesJson()
            if (defaults.isBlank() || defaults == "[]") {
                startActivity(Intent(this, AutomationRuleEditorActivity::class.java))
            } else {
                engine.rescheduleAllScheduledRules()
                Toast.makeText(this, "Default rules reloaded", Toast.LENGTH_SHORT).show()
            }
        }

        // Filter Strip Buttons
        binding.filterAll.setOnClickListener { applyFilter(null) }
        binding.filterScheduled.setOnClickListener { applyFilter(TriggerType.SCHEDULED) }
        binding.filterVoice.setOnClickListener { applyFilter(TriggerType.VOICE_COMMAND) }
        binding.filterDevice.setOnClickListener { applyFilter(TriggerType.DEVICE_EVENT) }
    }

    private fun applyFilter(filter: TriggerType?) {
        activeFilter = filter
        updateFilteredList()
    }

    private fun observeRules() {
        lifecycleScope.launch {
            repository.rulesFlow.collectLatest { rules ->
                allRules = rules
                val activeCount = rules.count { it.isEnabled }
                val totalExecutions = rules.sumOf { it.executionCount }

                binding.tvActiveRulesCount.text = "$activeCount of ${rules.size} Rules Active"
                binding.tvExecutionStats.text = "Total executions: $totalExecutions • Engine: Ready"

                updateFilteredList()
            }
        }
    }

    private fun updateFilteredList() {
        val filtered = if (activeFilter == null) {
            allRules
        } else {
            allRules.filter { it.trigger.type == activeFilter }
        }

        adapter.submitList(filtered)

        if (filtered.isEmpty()) {
            binding.layoutEmptyRules.visibility = View.VISIBLE
            binding.rvAutomationRules.visibility = View.GONE
        } else {
            binding.layoutEmptyRules.visibility = View.GONE
            binding.rvAutomationRules.visibility = View.VISIBLE
        }
    }

    private fun showExportImportDialog() {
        val currentJson = repository.exportRulesJson()

        val input = EditText(this).apply {
            setText(currentJson)
            setSelection(text.length)
            minLines = 6
            maxLines = 12
            textSize = 12f
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Rules Backup & Import")
            .setMessage("View, copy, or paste JSON to import/export automation rules:")
            .setView(input)
            .setPositiveButton("Import") { _, _ ->
                val text = input.text.toString().trim()
                val (count, message) = repository.importRulesJson(text)
                if (count > 0) {
                    engine.rescheduleAllScheduledRules()
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
            .setNeutralButton("Copy JSON") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("JARVIS Automation Rules", currentJson)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Rules JSON copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
