package com.jarvis.assistant.automation.studio

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.EditText
import android.widget.FrameLayout
import android.graphics.Color
import android.view.ViewGroup

/**
 * Standalone additive screen. Launch explicitly from a new settings entry or button;
 * it does not replace MainActivity or alter existing navigation automatically.
 */
class AutomationStudioActivity : AppCompatActivity() {
    private lateinit var repository: RuleRepository
    private lateinit var content: LinearLayout
    private lateinit var statusText: TextView
    private var selectedTab = Tab.SYSTEM

    private enum class Tab { SYSTEM, CUSTOM }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = RuleRepository(applicationContext)
        buildScreen()
        renderRules()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setBackgroundColor(Color.rgb(9, 16, 31))
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        titleBlock.addView(label("AUTOMATION STUDIO", 11, Color.rgb(90, 190, 255), true))
        titleBlock.addView(label("Your rules. Your control.", 23, Color.WHITE, true))
        topRow.addView(titleBlock)
        val close = MaterialButton(this).apply {
            text = "CLOSE"
            setOnClickListener { finish() }
        }
        topRow.addView(close)
        root.addView(topRow)

        val masterCard = card()
        val masterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(10), dp(8))
        }
        val masterCopy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        masterCopy.addView(label("Automation engine", 16, Color.WHITE, true))
        masterCopy.addView(label("Turn local rules on or off", 12, Color.LTGRAY, false))
        val masterSwitch = Switch(this).apply {
            isChecked = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ENABLED, true)
            setOnCheckedChangeListener { _, checked ->
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, checked).apply()
                statusText.text = if (checked) "Local rules enabled" else "Local rules paused"
                renderRules()
            }
        }
        masterRow.addView(masterCopy)
        masterRow.addView(masterSwitch)
        masterCard.addView(masterRow)
        root.addSpaced(masterCard, 16)

        statusText = label("Local rules enabled", 12, Color.rgb(120, 210, 170), false)
        root.addSpaced(statusText, 8)

        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        tabRow.addView(tabButton("SYSTEM RULES") { selectedTab = Tab.SYSTEM; renderRules() }, LinearLayout.LayoutParams(0, dp(46), 1f))
        tabRow.addView(tabButton("CUSTOM RULES") { selectedTab = Tab.CUSTOM; renderRules() }, LinearLayout.LayoutParams(0, dp(46), 1f))
        root.addSpaced(tabRow, 12)

        val scroll = ScrollView(this)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })

        val footer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val export = MaterialButton(this).apply { text = "EXPORT"; setOnClickListener { exportRules() } }
        val import = MaterialButton(this).apply { text = "IMPORT JSON"; setOnClickListener { showImportDialog() } }
        val add = MaterialButton(this).apply { text = "+ CREATE RULE"; setOnClickListener { showRuleEditor(null) } }
        footer.addView(export, LinearLayout.LayoutParams(0, dp(48), 1f))
        footer.addView(import, LinearLayout.LayoutParams(0, dp(48), 1f))
        footer.addView(add, LinearLayout.LayoutParams(0, dp(48), 1.2f))
        root.addSpaced(footer, 10)
        setContentView(root)
    }

    private fun renderRules() {
        if (!::content.isInitialized) return
        content.removeAllViews()
        val all = repository.getAll()
        val visible = if (selectedTab == Tab.SYSTEM) all.filter { it.protected } else all.filterNot { it.protected }
        if (visible.isEmpty()) {
            val empty = card()
            empty.addView(label("No custom rules yet", 17, Color.WHITE, true))
            empty.addSpaced(label("Tap CREATE RULE to add your first automation.", 13, Color.LTGRAY, false), 6)
            content.addView(empty)
            return
        }
        visible.forEach { rule ->
            val c = card()
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val textColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
            textColumn.addView(label(rule.name, 16, Color.WHITE, true))
            textColumn.addSpaced(label(rule.triggerPattern, 13, Color.rgb(120, 190, 230), false), 4)
            textColumn.addSpaced(label("${rule.triggerType.name.replace('_', ' ')}  •  ${rule.actions.joinToString { it.type.name.replace('_', ' ') }}", 11, Color.LTGRAY, false), 4)
            row.addView(textColumn)
            val enabled = Switch(this).apply {
                isChecked = rule.enabled
                setOnCheckedChangeListener { _, value ->
                    try {
                        repository.setEnabled(rule.id, value)
                        renderRules()
                    } catch (e: Exception) {
                        Toast.makeText(this@AutomationStudioActivity, e.message ?: "Could not update rule", Toast.LENGTH_LONG).show()
                    }
                }
            }
            row.addView(enabled)
            c.addView(row)
            val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
            if (!rule.protected) {
                buttons.addView(MaterialButton(this).apply { text = "EDIT"; setOnClickListener { showRuleEditor(rule) } })
                buttons.addView(MaterialButton(this).apply {
                    text = "DELETE"
                    setOnClickListener {
                        AlertDialog.Builder(this@AutomationStudioActivity)
                            .setTitle("Delete rule?")
                            .setMessage("Delete “${rule.name}”? This cannot be undone.")
                            .setNegativeButton("CANCEL", null)
                            .setPositiveButton("DELETE") { _, _ ->
                                try { repository.delete(rule.id); renderRules() }
                                catch (e: Exception) { Toast.makeText(this@AutomationStudioActivity, e.message ?: "Delete failed", Toast.LENGTH_LONG).show() }
                            }.show()
                    }
                })
            } else {
                buttons.addView(label("PROTECTED DEFAULT", 10, Color.rgb(255, 193, 90), true).apply { gravity = Gravity.CENTER_VERTICAL })
            }
            c.addSpaced(buttons, 4)
            content.addSpaced(c, 10)
        }
    }

    private fun showRuleEditor(existing: AutomationRule?) {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        fun edit(hint: String, value: String = ""): EditText = EditText(this).apply { this.hint = hint; setText(value); setSingleLine(hint != "Voice template") }
        val name = edit("Rule name", existing?.name.orEmpty())
        val pattern = edit("Voice template", existing?.triggerPattern.orEmpty())
        val triggerSpinner = Spinner(this)
        val triggers = listOf(RuleTriggerType.VOICE_COMMAND, RuleTriggerType.BATTERY_BELOW, RuleTriggerType.BATTERY_FULL, RuleTriggerType.CHARGER_CONNECTED, RuleTriggerType.CHARGER_DISCONNECTED, RuleTriggerType.NOTIFICATION_MATCH, RuleTriggerType.DEVICE_BOOT, RuleTriggerType.WIFI_CONNECTED, RuleTriggerType.WIFI_DISCONNECTED)
        triggerSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, triggers.map { it.name.replace('_', ' ') })
        triggerSpinner.setSelection(triggers.indexOf(existing?.triggerType ?: RuleTriggerType.VOICE_COMMAND).coerceAtLeast(0))
        val actionSpinner = Spinner(this)
        val actions = listOf(RuleActionType.OPEN_APP, RuleActionType.SPEAK, RuleActionType.PLACE_CALL, RuleActionType.WHATSAPP_MESSAGE, RuleActionType.SET_VOLUME, RuleActionType.SET_BRIGHTNESS, RuleActionType.SET_FLASHLIGHT, RuleActionType.SET_RINGER_MODE, RuleActionType.MEDIA_PLAY, RuleActionType.MEDIA_PAUSE, RuleActionType.MEDIA_NEXT, RuleActionType.MEDIA_PREVIOUS, RuleActionType.OPEN_WIFI_SETTINGS, RuleActionType.OPEN_BLUETOOTH_SETTINGS)
        actionSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, actions.map { it.name.replace('_', ' ') })
        actionSpinner.setSelection(actions.indexOf(existing?.actions?.firstOrNull()?.type ?: RuleActionType.SPEAK).coerceAtLeast(0))
        val parameter = edit("Action value (e.g. text, 70, {contact})", existing?.actions?.firstOrNull()?.parameters?.values?.firstOrNull().orEmpty())
        panel.addView(name); panel.addView(pattern)
        panel.addView(label("Trigger", 12, Color.DKGRAY, true)); panel.addView(triggerSpinner)
        panel.addView(label("Action", 12, Color.DKGRAY, true)); panel.addView(actionSpinner)
        panel.addView(parameter)
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Create automation rule" else "Edit automation rule")
            .setView(panel)
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("SAVE", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val cleanName = name.text.toString().trim()
                        val cleanPattern = pattern.text.toString().trim()
                        if (cleanName.isBlank() || cleanPattern.isBlank()) {
                            Toast.makeText(this, "Enter a rule name and trigger pattern.", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        val trigger = triggers[triggerSpinner.selectedItemPosition]
                        val actionType = actions[actionSpinner.selectedItemPosition]
                        val param = parameter.text.toString().trim()
                        val params = when (actionType) {
                            RuleActionType.OPEN_APP -> mapOf("packageName" to param)
                            RuleActionType.SPEAK -> mapOf("text" to param.ifBlank { "Automation triggered." })
                            RuleActionType.PLACE_CALL -> mapOf("contact" to param.ifBlank { "{contact}" })
                            RuleActionType.WHATSAPP_MESSAGE -> mapOf("contact" to "{contact}", "message" to "{message}")
                            RuleActionType.SET_VOLUME, RuleActionType.SET_BRIGHTNESS -> mapOf("level" to param.ifBlank { "{level}" })
                            RuleActionType.SET_FLASHLIGHT -> mapOf("enabled" to param.ifBlank { "true" })
                            RuleActionType.SET_RINGER_MODE -> mapOf("mode" to param.ifBlank { "normal" })
                            else -> emptyMap()
                        }
                        try {
                            val rule = AutomationRule(
                                id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                                name = cleanName,
                                enabled = existing?.enabled ?: true,
                                triggerType = trigger,
                                triggerPattern = cleanPattern,
                                actions = listOf(AutomationAction(actionType, params)),
                                protected = false,
                                priority = existing?.priority ?: 100,
                                createdAtEpochMs = existing?.createdAtEpochMs ?: System.currentTimeMillis(),
                                updatedAtEpochMs = System.currentTimeMillis()
                            )
                            repository.upsert(rule)
                            dialog.dismiss()
                            renderRules()
                        } catch (e: Exception) {
                            Toast.makeText(this, e.message ?: "Could not save rule", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                dialog.show()
            }
    }

    private data class InstalledApp(val label: String, val packageName: String)

    /** Lists launchable apps visible to Android's package-visibility policy. */
    private fun showInstalledAppPicker(onChosen: (InstalledApp) -> Unit) {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == packageName) return@mapNotNull null
                InstalledApp(info.loadLabel(pm).toString(), pkg)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
        if (apps.isEmpty()) {
            Toast.makeText(this, "No launchable apps found.", Toast.LENGTH_LONG).show()
            return
        }
        val picker = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(8))
        }
        val search = EditText(this).apply {
            hint = "Search installed apps"
            setSingleLine(true)
        }
        val list = android.widget.ListView(this)
        picker.addView(search, LinearLayout.LayoutParams(-1, -2))
        picker.addView(list, LinearLayout.LayoutParams(-1, dp(420)))
        val dialog = AlertDialog.Builder(this)
            .setTitle("Choose installed app")
            .setView(picker)
            .setNegativeButton("CLOSE", null)
            .create()
        var filtered = apps
        fun refresh(query: String) {
            filtered = apps.filter { it.label.contains(query, true) || it.packageName.contains(query, true) }
            list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_2, android.R.id.text1,
                filtered.map { "${it.label}\n${it.packageName}" })
        }
        refresh("")
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { refresh(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        list.setOnItemClickListener { _, _, position, _ ->
            filtered.getOrNull(position)?.let(onChosen)
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun showImportDialog() {
        val input = EditText(this).apply { hint = "Paste exported Automation Studio JSON"; minLines = 5; gravity = Gravity.TOP }
        AlertDialog.Builder(this).setTitle("Import custom rules").setView(input)
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("IMPORT") { _, _ ->
                try {
                    val count = repository.importCustomRules(input.text.toString())
                    Toast.makeText(this, "Imported $count rule(s).", Toast.LENGTH_LONG).show()
                    renderRules()
                } catch (e: Exception) {
                    Toast.makeText(this, e.message ?: "Import failed", Toast.LENGTH_LONG).show()
                }
            }.show()
    }

    private fun exportRules() {
        val json = repository.exportCustomRules()
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_SUBJECT, "JARVIS Automation Studio rules")
            putExtra(Intent.EXTRA_TEXT, json)
        }
        startActivity(Intent.createChooser(share, "Export custom rules"))
    }

    private fun card(): MaterialCardView = MaterialCardView(this).apply {
        radius = dp(18).toFloat()
        cardElevation = dp(2).toFloat()
        setCardBackgroundColor(Color.rgb(19, 30, 51))
        strokeWidth = dp(1)
        strokeColor = Color.rgb(43, 65, 94)
        val lp = LinearLayout.LayoutParams(-1, -2)
        layoutParams = lp
        setContentPadding(dp(14), dp(12), dp(14), dp(12))
    }

    private fun label(text: String, size: Int, color: Int, bold: Boolean): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun tabButton(text: String, action: () -> Unit) = MaterialButton(this).apply {
        this.text = text
        setOnClickListener { action() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun LinearLayout.addSpaced(view: View, marginTop: Int) {
        addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(marginTop) })
    }

    private fun MaterialCardView.addSpaced(view: View, marginTop: Int) {
        addView(view, FrameLayout.LayoutParams(-1, -2).apply { topMargin = dp(marginTop) })
    }

    companion object {
        private const val PREFS = "automation_studio_settings"
        private const val KEY_ENABLED = "enabled"
    }
}
