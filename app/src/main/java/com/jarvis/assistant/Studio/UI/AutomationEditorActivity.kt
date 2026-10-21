package com.jarvis.assistant.studio.ui

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.jarvis.assistant.R
import com.jarvis.assistant.databinding.ActivityAutomationEditorBinding
import com.jarvis.assistant.databinding.ItemAutomationActionBinding
import com.jarvis.assistant.studio.ActionCatalog
import com.jarvis.assistant.studio.ActionSpec
import com.jarvis.assistant.studio.AutomationAction
import com.jarvis.assistant.studio.AutomationRule
import com.jarvis.assistant.studio.AutomationStore
import com.jarvis.assistant.studio.AutomationTrigger
import com.jarvis.assistant.studio.ParamKind
import com.jarvis.assistant.studio.PhraseMatcher
import com.jarvis.assistant.studio.TriggerType
import com.jarvis.assistant.util.ThemeManager

/** Create or edit one automation: its name, what starts it, and the action blocks it runs. */
class AutomationEditorActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAutomationEditorBinding
    private val store by lazy { AutomationStore.get(applicationContext) }
    private var original: AutomationRule? = null
    private val actionRows = ArrayList<ActionRowHolder>()

    private val triggers = TriggerType.values()
    private val appKeys = listOf("", "whatsapp", "instagram", "instazen")
    private val appLabels = listOf("Any app", "WhatsApp", "Instagram", "Instazen")

    private class ActionRowHolder(val binding: ItemAutomationActionBinding, var spec: ActionSpec) {
        val fields = LinkedHashMap<String, () -> String>()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityAutomationEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ThemeManager.applyDynamicBackground(binding.root)

        val ruleId = intent.getStringExtra(EXTRA_RULE_ID)
        original = if (ruleId != null) store.get(ruleId) else null
        if (ruleId != null && original == null) {
            finish()
            return
        }

        binding.btnEditorBack.setOnClickListener { finish() }

        binding.spTrigger.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, triggers.map { it.label })
        binding.spAppFilter.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, appLabels)
        binding.spTrigger.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateTriggerGroups()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.btnAddAction.setOnClickListener { addActionRow(null) }
        binding.btnSave.setOnClickListener { save() }
        binding.btnResetDefault.setOnClickListener { confirmReset() }
        binding.btnDelete.setOnClickListener { confirmDelete() }

        loadRule(original)
    }

    private fun loadRule(rule: AutomationRule?) {
        val isDefault = rule?.isDefault == true
        binding.tvEditorTitle.text = when {
            rule == null -> "New automation"
            isDefault -> "Edit default automation"
            else -> "Edit automation"
        }
        binding.etName.setText(rule?.name ?: "")
        binding.switchEnabled.isChecked = rule?.enabled ?: true

        val trigger = rule?.trigger ?: AutomationTrigger(TriggerType.VOICE)
        binding.spTrigger.setSelection(triggers.indexOf(trigger.type).coerceAtLeast(0))
        binding.etPhrases.setText(trigger.phrases.joinToString("\n"))
        binding.etThreshold.setText(trigger.threshold.toString())
        binding.spAppFilter.setSelection(appKeys.indexOf(trigger.appFilter.lowercase()).coerceAtLeast(0))
        binding.etSenderFilter.setText(trigger.senderFilter)

        binding.llActions.removeAllViews()
        actionRows.clear()
        val actions = rule?.actions ?: emptyList()
        if (actions.isEmpty()) addActionRow(null) else actions.forEach { addActionRow(it) }

        binding.btnResetDefault.visibility = if (isDefault) View.VISIBLE else View.GONE
        // Default automations can never be deleted, so the button is not even shown for them.
        binding.btnDelete.visibility = if (rule != null && !isDefault) View.VISIBLE else View.GONE
        updateTriggerGroups()
    }

    private fun selectedTrigger(): TriggerType =
        triggers.getOrElse(binding.spTrigger.selectedItemPosition) { TriggerType.VOICE }

    private fun updateTriggerGroups() {
        val type = selectedTrigger()
        binding.groupVoice.visibility = if (type == TriggerType.VOICE) View.VISIBLE else View.GONE
        binding.groupBattery.visibility = if (type == TriggerType.BATTERY_LOW) View.VISIBLE else View.GONE
        binding.groupMessage.visibility = if (type == TriggerType.MESSAGE_RECEIVED) View.VISIBLE else View.GONE
        binding.tvVariablesHint.text = when (type) {
            TriggerType.VOICE ->
                "Variables: write {contact}, {message}, {query}, {level} or any {name} in a phrase, for example \"play {query}\". " +
                    "Then use the same {name} in an action."
            TriggerType.MESSAGE_RECEIVED -> "Variables you can use in actions: {sender} and {app_name}."
            else -> "Variable you can use in actions: {battery} (the battery percentage)."
        }
    }

    private fun addActionRow(initial: AutomationAction?) {
        val spec = ActionCatalog.find(initial?.type ?: "") ?: ActionCatalog.all.first()
        val rowBinding = ItemAutomationActionBinding.inflate(layoutInflater, binding.llActions, false)
        val holder = ActionRowHolder(rowBinding, spec)

        rowBinding.spActionType.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, ActionCatalog.all.map { it.label })
        buildParams(holder, initial?.params ?: emptyMap())
        rowBinding.spActionType.setSelection(ActionCatalog.all.indexOfFirst { it.key == spec.key }.coerceAtLeast(0))
        rowBinding.spActionType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val chosen = ActionCatalog.all.getOrNull(position) ?: return
                if (chosen.key != holder.spec.key) {
                    holder.spec = chosen
                    buildParams(holder, emptyMap())
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        rowBinding.btnMoveUp.setOnClickListener { moveRow(holder, -1) }
        rowBinding.btnMoveDown.setOnClickListener { moveRow(holder, 1) }
        rowBinding.btnRemoveAction.setOnClickListener {
            actionRows.remove(holder)
            binding.llActions.removeView(rowBinding.root)
        }

        actionRows.add(holder)
        binding.llActions.addView(rowBinding.root)
    }

    private fun moveRow(holder: ActionRowHolder, delta: Int) {
        val from = actionRows.indexOf(holder)
        val to = from + delta
        if (from < 0 || to !in actionRows.indices) return
        actionRows.removeAt(from)
        actionRows.add(to, holder)
        binding.llActions.removeView(holder.binding.root)
        binding.llActions.addView(holder.binding.root, to)
    }

    /** Builds the input fields for an action block's settings. */
    private fun buildParams(holder: ActionRowHolder, initial: Map<String, String>) {
        holder.binding.llParams.removeAllViews()
        holder.fields.clear()
        for (param in holder.spec.params) {
            val value = initial[param.key] ?: param.default
            when (param.kind) {
                ParamKind.TOGGLE -> {
                    val view = layoutInflater.inflate(R.layout.item_param_toggle, holder.binding.llParams, false)
                    view.findViewById<TextView>(R.id.tvParamLabel).text = param.label
                    val toggle = view.findViewById<MaterialSwitch>(R.id.switchParam)
                    toggle.isChecked = value != "false"
                    holder.fields[param.key] = { if (toggle.isChecked) "true" else "false" }
                    holder.binding.llParams.addView(view)
                }
                ParamKind.CHOICE -> {
                    val view = layoutInflater.inflate(R.layout.item_param_choice, holder.binding.llParams, false)
                    view.findViewById<TextView>(R.id.tvParamLabel).text = param.label
                    val spinner = view.findViewById<Spinner>(R.id.spParamChoice)
                    val labels = param.choices.map { if (it.isEmpty()) "Default" else it }
                    spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
                    spinner.setSelection(param.choices.indexOf(value).coerceAtLeast(0))
                    holder.fields[param.key] = { param.choices.getOrElse(spinner.selectedItemPosition) { "" } }
                    holder.binding.llParams.addView(view)
                }
                else -> {
                    val view = layoutInflater.inflate(R.layout.item_param_text, holder.binding.llParams, false)
                    view.findViewById<TextView>(R.id.tvParamLabel).text = param.label
                    val edit = view.findViewById<EditText>(R.id.etParamValue)
                    edit.hint = param.hint
                    edit.setText(value)
                    holder.fields[param.key] = { edit.text.toString().trim() }
                    holder.binding.llParams.addView(view)
                }
            }
        }
    }

    private fun save() {
        val name = binding.etName.text.toString().trim().ifEmpty { "Untitled automation" }
        val type = selectedTrigger()

        var phrases: List<String> = emptyList()
        if (type == TriggerType.VOICE) {
            phrases = binding.etPhrases.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
                .filter { PhraseMatcher.compile(it) != null }
            if (phrases.isEmpty()) {
                Toast.makeText(this, "Add at least one phrase that has a real word in it.", Toast.LENGTH_LONG).show()
                return
            }
        }
        val actions = actionRows.map { holder ->
            AutomationAction(holder.spec.key, holder.fields.mapValues { (_, read) -> read() })
        }
        if (actions.isEmpty()) {
            Toast.makeText(this, "Add at least one action.", Toast.LENGTH_LONG).show()
            return
        }

        val rule = AutomationRule(
            id = original?.id ?: store.newId(),
            name = name,
            isDefault = original?.isDefault ?: false,
            enabled = binding.switchEnabled.isChecked,
            trigger = AutomationTrigger(
                type = type,
                phrases = phrases,
                threshold = binding.etThreshold.text.toString().toIntOrNull()?.coerceIn(1, 99) ?: 20,
                appFilter = appKeys.getOrElse(binding.spAppFilter.selectedItemPosition) { "" },
                senderFilter = binding.etSenderFilter.text.toString().trim()
            ),
            actions = actions
        )
        store.upsert(rule)
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun confirmReset() {
        val id = original?.id ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle("Reset to the original version?")
            .setMessage("Your changes to this default automation will be replaced.")
            .setPositiveButton("Reset") { _, _ ->
                if (store.resetDefault(id)) {
                    original = store.get(id)
                    loadRule(original)
                    Toast.makeText(this, "Reset", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete() {
        val rule = original ?: return
        if (rule.isDefault) return
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete \"" + rule.name + "\"?")
            .setMessage("This automation will be removed for good.")
            .setPositiveButton("Delete") { _, _ ->
                if (store.delete(rule.id)) finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        const val EXTRA_RULE_ID = "rule_id"
    }
}
