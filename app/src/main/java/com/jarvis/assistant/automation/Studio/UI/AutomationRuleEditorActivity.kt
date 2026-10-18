package com.jarvis.assistant.automation.studio.ui

import android.app.TimePickerDialog
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.automation.studio.engine.AutomationEngine
import com.jarvis.assistant.automation.studio.model.*
import com.jarvis.assistant.automation.studio.repository.AutomationRuleRepository
import com.jarvis.assistant.databinding.ActivityAutomationRuleEditorBinding
import com.jarvis.assistant.util.ThemeManager
import java.util.Calendar
import java.util.Locale
import java.util.UUID

class AutomationRuleEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RULE_ID = "extra_editor_rule_id"
    }

    private lateinit var binding: ActivityAutomationRuleEditorBinding
    private val repository by lazy { AutomationRuleRepository.getInstance(applicationContext) }
    private val engine by lazy { AutomationEngine.getInstance(applicationContext) }
    private val preferences by lazy { JarvisApp.instance.preferences }

    private var editingRuleId: String? = null
    private var selectedHour: Int = 8
    private var selectedMinute: Int = 0
    private var selectedFeatureType: FeatureType? = null
    private var selectedFeatureValue: Int = 50
    private var selectedAppPackage: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.applyTheme(this)
        binding = ActivityAutomationRuleEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        editingRuleId = intent.getStringExtra(EXTRA_RULE_ID)

        setupLiquidGlassSurfaces()
        setupListeners()
        loadExistingOrDefaults()
    }

    private fun setupLiquidGlassSurfaces() {
        ThemeManager.applyLiquidGlassOrbs(binding.glowOrbTopRight, binding.glowOrbBottom)
        ThemeManager.applyLiquidGlassCard(binding.cardDetails, 20f)
        ThemeManager.applyLiquidGlassCard(binding.cardTrigger, 20f)
        ThemeManager.applyLiquidGlassCard(binding.cardActions, 20f)
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnSaveRule.setOnClickListener {
            saveRule()
        }

        // Trigger Category switching
        binding.rgTriggerType.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                binding.rbTriggerScheduled.id -> {
                    binding.panelScheduledTrigger.visibility = View.VISIBLE
                    binding.panelVoiceTrigger.visibility = View.GONE
                    binding.panelDeviceTrigger.visibility = View.GONE
                }
                binding.rbTriggerVoice.id -> {
                    binding.panelScheduledTrigger.visibility = View.GONE
                    binding.panelVoiceTrigger.visibility = View.VISIBLE
                    binding.panelDeviceTrigger.visibility = View.GONE
                }
                binding.rbTriggerDevice.id -> {
                    binding.panelScheduledTrigger.visibility = View.GONE
                    binding.panelVoiceTrigger.visibility = View.GONE
                    binding.panelDeviceTrigger.visibility = View.VISIBLE
                }
            }
        }

        // Time Picker Button
        binding.btnPickTime.setOnClickListener {
            val picker = TimePickerDialog(
                this,
                { _, hourOfDay, minute ->
                    selectedHour = hourOfDay
                    selectedMinute = minute
                    updateTimeDisplay()
                },
                selectedHour,
                selectedMinute,
                false
            )
            picker.show()
        }

        // Voice preset chips
        binding.chipPhraseHindi1.setOnClickListener { binding.etVoicePhrase.setText("नमस्ते जार्विस") }
        binding.chipPhraseHindi2.setOnClickListener { binding.etVoicePhrase.setText("चार्जिंग स्थिति बताओ") }
        binding.chipPhraseEng1.setOnClickListener { binding.etVoicePhrase.setText("Good morning Jarvis") }
        binding.chipPhraseEng2.setOnClickListener { binding.etVoicePhrase.setText("Good night Jarvis") }

        // Action feature buttons
        binding.btnActionFlashlight.setOnClickListener {
            selectedFeatureType = FeatureType.FLASHLIGHT_ON
            selectedAppPackage = ""
            binding.tvActiveFeatureAction.text = "Selected feature: Flashlight ON"
        }

        binding.btnActionVolume.setOnClickListener {
            selectedFeatureType = FeatureType.SET_VOLUME
            selectedFeatureValue = 80
            selectedAppPackage = ""
            binding.tvActiveFeatureAction.text = "Selected feature: Set Volume to 80%"
        }

        binding.btnActionYouTube.setOnClickListener {
            selectedFeatureType = null
            selectedAppPackage = "com.google.android.youtube"
            binding.tvActiveFeatureAction.text = "Selected feature: Open YouTube"
        }

        // Test run action sequence
        binding.btnTestActions.setOnClickListener {
            val draft = buildRuleFromInputs()
            Toast.makeText(this, "Testing automation actions...", Toast.LENGTH_SHORT).show()
            for (action in draft.actions) {
                try {
                    when (action.type) {
                        ActionType.SPEAK_TTS -> {
                            com.jarvis.assistant.util.SpeechAnnouncer(applicationContext).say(action.ttsMessage)
                        }
                        ActionType.SHOW_NOTIFICATION -> {
                            engine.executeRule(draft.id, "Test Preview")
                        }
                        else -> {
                            engine.executeRule(draft.id, "Test Preview")
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun updateTimeDisplay() {
        val timeStr = String.format(Locale.getDefault(), "%02d:%02d", selectedHour, selectedMinute)
        binding.btnPickTime.text = "⏰ Set Trigger Time: $timeStr"
    }

    private fun loadExistingOrDefaults() {
        val ruleId = editingRuleId
        if (ruleId != null) {
            val rule = repository.getRuleById(ruleId)
            if (rule != null) {
                binding.tvEditorTitle.text = "EDIT AUTOMATION RULE"
                binding.etRuleTitle.setText(rule.title)
                binding.etRuleDescription.setText(rule.description)

                when (rule.trigger.type) {
                    TriggerType.SCHEDULED -> {
                        binding.rbTriggerScheduled.isChecked = true
                        selectedHour = rule.trigger.scheduledHour
                        selectedMinute = rule.trigger.scheduledMinute
                        when (rule.trigger.scheduleType) {
                            ScheduleType.ONE_TIME -> binding.rbOneTime.isChecked = true
                            ScheduleType.DAILY -> binding.rbDaily.isChecked = true
                            ScheduleType.WEEKDAYS -> binding.rbWeekdays.isChecked = true
                        }
                    }
                    TriggerType.VOICE_COMMAND -> {
                        binding.rbTriggerVoice.isChecked = true
                        binding.etVoicePhrase.setText(rule.trigger.voicePhrases.firstOrNull() ?: "")
                    }
                    TriggerType.DEVICE_EVENT -> {
                        binding.rbTriggerDevice.isChecked = true
                        when (rule.trigger.deviceEventType) {
                            DeviceEventType.POWER_CONNECTED -> binding.rbPowerConnected.isChecked = true
                            DeviceEventType.POWER_DISCONNECTED -> binding.rbPowerDisconnected.isChecked = true
                            DeviceEventType.BATTERY_LOW -> binding.rbBatteryLow.isChecked = true
                            DeviceEventType.BATTERY_FULL -> binding.rbBatteryFull.isChecked = true
                        }
                    }
                }

                // Populate actions
                val tts = rule.actions.find { it.type == ActionType.SPEAK_TTS }
                if (tts != null) binding.etTtsMessage.setText(tts.ttsMessage)

                val notif = rule.actions.find { it.type == ActionType.SHOW_NOTIFICATION }
                if (notif != null) binding.etNotificationText.setText(notif.notificationTitle)

                val feature = rule.actions.find { it.type == ActionType.CONTROL_FEATURE }
                if (feature != null) {
                    selectedFeatureType = feature.featureType
                    selectedFeatureValue = feature.featureValue
                    binding.tvActiveFeatureAction.text = "Selected feature: ${feature.featureType}"
                }

                val app = rule.actions.find { it.type == ActionType.OPEN_APP }
                if (app != null) {
                    selectedAppPackage = app.appPackageName
                    binding.tvActiveFeatureAction.text = "Selected app: ${app.appName.ifBlank { app.appPackageName }}"
                }

                updateTimeDisplay()
                return
            }
        }

        // Defaults for fresh rule
        binding.tvEditorTitle.text = "NEW AUTOMATION RULE"
        selectedHour = 8
        selectedMinute = 0
        updateTimeDisplay()
    }

    private fun buildRuleFromInputs(): AutomationRule {
        val title = binding.etRuleTitle.text.toString().trim().ifBlank { "Untitled Automation" }
        val description = binding.etRuleDescription.text.toString().trim()

        val trigger = when {
            binding.rbTriggerScheduled.isChecked -> {
                val scheduleType = when {
                    binding.rbOneTime.isChecked -> ScheduleType.ONE_TIME
                    binding.rbWeekdays.isChecked -> ScheduleType.WEEKDAYS
                    else -> ScheduleType.DAILY
                }
                AutomationTrigger(
                    type = TriggerType.SCHEDULED,
                    scheduleType = scheduleType,
                    scheduledHour = selectedHour,
                    scheduledMinute = selectedMinute
                )
            }
            binding.rbTriggerVoice.isChecked -> {
                val phrase = binding.etVoicePhrase.text.toString().trim().ifBlank { "hey jarvis" }
                AutomationTrigger(
                    type = TriggerType.VOICE_COMMAND,
                    voicePhrases = listOf(phrase),
                    voiceMatchExact = false
                )
            }
            else -> {
                val eventType = when {
                    binding.rbPowerDisconnected.isChecked -> DeviceEventType.POWER_DISCONNECTED
                    binding.rbBatteryLow.isChecked -> DeviceEventType.BATTERY_LOW
                    binding.rbBatteryFull.isChecked -> DeviceEventType.BATTERY_FULL
                    else -> DeviceEventType.POWER_CONNECTED
                }
                AutomationTrigger(
                    type = TriggerType.DEVICE_EVENT,
                    deviceEventType = eventType,
                    batteryLevelThreshold = if (eventType == DeviceEventType.BATTERY_LOW) 20 else 100
                )
            }
        }

        val actions = mutableListOf<AutomationAction>()

        val ttsText = binding.etTtsMessage.text.toString().trim()
        if (ttsText.isNotBlank()) {
            actions.add(AutomationAction(type = ActionType.SPEAK_TTS, ttsMessage = ttsText))
        }

        val notifText = binding.etNotificationText.text.toString().trim()
        if (notifText.isNotBlank()) {
            actions.add(
                AutomationAction(
                    type = ActionType.SHOW_NOTIFICATION,
                    notificationTitle = notifText,
                    notificationBody = "Automated routine executed."
                )
            )
        }

        val feature = selectedFeatureType
        if (feature != null) {
            actions.add(
                AutomationAction(
                    type = ActionType.CONTROL_FEATURE,
                    featureType = feature,
                    featureValue = selectedFeatureValue
                )
            )
        }

        if (selectedAppPackage.isNotBlank()) {
            actions.add(
                AutomationAction(
                    type = ActionType.OPEN_APP,
                    appPackageName = selectedAppPackage,
                    appName = selectedAppPackage.substringAfterLast('.')
                )
            )
        }

        if (actions.isEmpty()) {
            actions.add(
                AutomationAction(
                    type = ActionType.SPEAK_TTS,
                    ttsMessage = "Automation rule executed successfully, Sir."
                )
            )
        }

        return AutomationRule(
            id = editingRuleId ?: UUID.randomUUID().toString(),
            title = title,
            description = description,
            isEnabled = true,
            trigger = trigger,
            actions = actions
        )
    }

    private fun saveRule() {
        val rule = buildRuleFromInputs()

        if (binding.etRuleTitle.text.toString().trim().isBlank()) {
            Toast.makeText(this, "Please enter a rule name", Toast.LENGTH_SHORT).show()
            return
        }

        repository.saveRule(rule)

        if (rule.trigger.type == TriggerType.SCHEDULED) {
            engine.scheduleRule(rule)
        }

        Toast.makeText(this, "Automation Rule '${rule.title}' saved!", Toast.LENGTH_SHORT).show()
        finish()
    }
}
