package com.jarvis.assistant.automation.studio.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.jarvis.assistant.automation.studio.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Calendar

class AutomationRuleRepository(private val context: Context) {

    companion object {
        private const val PREF_NAME = "jarvis_automation_rules_prefs"
        private const val KEY_RULES_JSON = "key_rules_json"
        private const val KEY_INITIALIZED = "key_rules_initialized_v1"
        private const val TAG = "AutomationRuleRepo"

        @Volatile
        private var instance: AutomationRuleRepository? = null

        fun getInstance(context: Context): AutomationRuleRepository {
            return instance ?: synchronized(this) {
                instance ?: AutomationRuleRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val _rulesFlow = MutableStateFlow<List<AutomationRule>>(emptyList())
    val rulesFlow: StateFlow<List<AutomationRule>> = _rulesFlow.asStateFlow()

    init {
        loadRules()
    }

    @Synchronized
    private fun loadRules() {
        val json = prefs.getString(KEY_RULES_JSON, null)
        val hasInitialized = prefs.getBoolean(KEY_INITIALIZED, false)

        if (json.isNullOrBlank()) {
            if (!hasInitialized) {
                val defaults = createDefaultTemplateRules()
                saveRulesInternal(defaults)
                prefs.edit().putBoolean(KEY_INITIALIZED, true).apply()
                _rulesFlow.value = defaults
            } else {
                _rulesFlow.value = emptyList()
            }
        } else {
            try {
                val type = object : TypeToken<List<AutomationRule>>() {}.type
                val parsed: List<AutomationRule> = gson.fromJson(json, type) ?: emptyList()
                _rulesFlow.value = parsed
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing automation rules JSON", e)
                _rulesFlow.value = emptyList()
            }
        }
    }

    private fun saveRulesInternal(list: List<AutomationRule>): Boolean {
        return try {
            val json = gson.toJson(list)
            prefs.edit().putString(KEY_RULES_JSON, json).commit()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save rules JSON", e)
            false
        }
    }

    @Synchronized
    fun getAllRules(): List<AutomationRule> {
        return _rulesFlow.value
    }

    @Synchronized
    fun getRuleById(id: String): AutomationRule? {
        return _rulesFlow.value.find { it.id == id }
    }

    @Synchronized
    fun saveRule(rule: AutomationRule) {
        val current = _rulesFlow.value.toMutableList()
        val index = current.indexOfFirst { it.id == rule.id }
        if (index >= 0) {
            current[index] = rule
        } else {
            current.add(0, rule)
        }
        _rulesFlow.value = current
        saveRulesInternal(current)
    }

    @Synchronized
    fun deleteRule(id: String): Boolean {
        val current = _rulesFlow.value.toMutableList()
        val removed = current.removeAll { it.id == id }
        if (removed) {
            _rulesFlow.value = current
            saveRulesInternal(current)
        }
        return removed
    }

    @Synchronized
    fun toggleRule(id: String, isEnabled: Boolean) {
        val current = _rulesFlow.value.toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index >= 0) {
            val updated = current[index].copy(isEnabled = isEnabled)
            current[index] = updated
            _rulesFlow.value = current
            saveRulesInternal(current)
        }
    }

    @Synchronized
    fun recordExecution(id: String, timestamp: Long = System.currentTimeMillis()) {
        val current = _rulesFlow.value.toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index >= 0) {
            val rule = current[index]
            val updated = rule.copy(
                lastExecutedAt = timestamp,
                executionCount = rule.executionCount + 1,
                // If it was a one-time schedule, automatically disable it after execution
                isEnabled = if (rule.trigger.type == TriggerType.SCHEDULED && rule.trigger.scheduleType == ScheduleType.ONE_TIME) false else rule.isEnabled
            )
            current[index] = updated
            _rulesFlow.value = current
            saveRulesInternal(current)
        }
    }

    fun exportRulesJson(): String {
        return gson.toJson(_rulesFlow.value)
    }

    @Synchronized
    fun importRulesJson(json: String): Pair<Int, String> {
        if (json.isBlank()) {
            return Pair(0, "Import payload is empty")
        }
        return try {
            val type = object : TypeToken<List<AutomationRule>>() {}.type
            val imported: List<AutomationRule> = gson.fromJson(json, type) ?: emptyList()
            if (imported.isEmpty()) {
                return Pair(0, "No valid automation rules found in payload")
            }

            val current = _rulesFlow.value.toMutableList()
            var addedCount = 0
            for (rule in imported) {
                val existingIndex = current.indexOfFirst { it.id == rule.id }
                if (existingIndex >= 0) {
                    current[existingIndex] = rule
                } else {
                    current.add(rule)
                    addedCount++
                }
            }
            _rulesFlow.value = current
            saveRulesInternal(current)
            Pair(imported.size, "Successfully imported ${imported.size} automation rules ($addedCount new)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import rules", e)
            Pair(0, "Import failed: ${e.localizedMessage ?: "Invalid JSON format"}")
        }
    }

    private fun createDefaultTemplateRules(): List<AutomationRule> {
        return listOf(
            AutomationRule(
                id = "default_rule_morning",
                title = "Morning Briefing",
                description = "Daily 8:00 AM briefing with audio greeting and system status.",
                isEnabled = true,
                trigger = AutomationTrigger(
                    type = TriggerType.SCHEDULED,
                    scheduleType = ScheduleType.DAILY,
                    scheduledHour = 8,
                    scheduledMinute = 0
                ),
                actions = listOf(
                    AutomationAction(
                        type = ActionType.SPEAK_TTS,
                        ttsMessage = "Good morning Sir. All JARVIS subsystems are fully operational. Have a productive day."
                    ),
                    AutomationAction(
                        type = ActionType.SHOW_NOTIFICATION,
                        notificationTitle = "JARVIS Daily Briefing",
                        notificationBody = "Good morning Sir. System readiness: 100%."
                    )
                )
            ),
            AutomationRule(
                id = "default_rule_charger",
                title = "Fast Charger Connected",
                description = "Acoustic announcement when device is plugged into AC power.",
                isEnabled = true,
                trigger = AutomationTrigger(
                    type = TriggerType.DEVICE_EVENT,
                    deviceEventType = DeviceEventType.POWER_CONNECTED
                ),
                actions = listOf(
                    AutomationAction(
                        type = ActionType.SPEAK_TTS,
                        ttsMessage = "Power grid coupled. Charging initiated."
                    )
                )
            ),
            AutomationRule(
                id = "default_rule_hindi_wake",
                title = "Hindi Voice Wake & Greeting",
                description = "Triggered via voice command in Hindi to activate assistant response.",
                isEnabled = true,
                trigger = AutomationTrigger(
                    type = TriggerType.VOICE_COMMAND,
                    voicePhrases = listOf("नमस्ते जार्विस", "जार्विस शुरू करो", "जार्विस सुनिए"),
                    voiceMatchExact = false
                ),
                actions = listOf(
                    AutomationAction(
                        type = ActionType.SPEAK_TTS,
                        ttsMessage = "नमस्ते सर, जार्विस आपकी सेवा में तत्पर है। आज मैं आपकी क्या सहायता कर सकता हूँ?"
                    ),
                    AutomationAction(
                        type = ActionType.SHOW_NOTIFICATION,
                        notificationTitle = "JARVIS Voice Trigger",
                        notificationBody = "नमस्ते सर, सिस्टम तैयार है।"
                    )
                )
            ),
            AutomationRule(
                id = "default_rule_night",
                title = "Bedtime Voice Macro",
                description = "Lowers brightness and volume when saying 'Good night Jarvis' or 'सोने का समय'.",
                isEnabled = true,
                trigger = AutomationTrigger(
                    type = TriggerType.VOICE_COMMAND,
                    voicePhrases = listOf("good night jarvis", "सोने का समय", "स्लीप मोड"),
                    voiceMatchExact = false
                ),
                actions = listOf(
                    AutomationAction(
                        type = ActionType.CONTROL_FEATURE,
                        featureType = FeatureType.SET_BRIGHTNESS,
                        featureValue = 15
                    ),
                    AutomationAction(
                        type = ActionType.CONTROL_FEATURE,
                        featureType = FeatureType.SET_VOLUME,
                        featureValue = 25
                    ),
                    AutomationAction(
                        type = ActionType.SPEAK_TTS,
                        ttsMessage = "Good night Sir. Disengaging non-essential protocols."
                    )
                )
            )
        )
    }
}
