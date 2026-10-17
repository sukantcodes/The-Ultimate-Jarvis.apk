package com.jarvis.assistant.automation.studio

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Executes matching non-voice rules and persists edge/latch state to avoid repeated side effects. */
class AutomationTriggerRuleEngine(
    context: Context,
    private val repository: RuleRepository,
    private val dispatcher: StudioActionDispatcher
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("automation_studio_trigger_state", Context.MODE_PRIVATE)
    private val settings = appContext.getSharedPreferences("automation_studio_settings", Context.MODE_PRIVATE)
    private val matcher = AutomationTriggerMatcher()

    suspend fun onTrigger(trigger: RuleTriggerType, payload: Map<String, String>) = withContext(Dispatchers.IO) {
        if (!contextEnabled()) return@withContext
        val allRules = repository.getAll()
        if (trigger == RuleTriggerType.BATTERY_BELOW && (payload["level"]?.toIntOrNull() ?: 100) < 100) {
            allRules.filter { it.triggerType == RuleTriggerType.BATTERY_FULL }.forEach {
                prefs.edit().remove("edge_${it.id}").apply()
            }
        }
        val candidates = allRules
            .asSequence()
            .filter { it.enabled && it.triggerType == trigger }
            .sortedWith(compareBy<AutomationRule> { it.priority }.thenBy { it.name })
            .toList()

        for (rule in candidates) {
            // Background events must never initiate calls or messaging side effects.
            if (rule.actions.any { it.type == RuleActionType.PLACE_CALL || it.type == RuleActionType.WHATSAPP_MESSAGE }) continue
            val matches = matcher.matches(rule, trigger, payload)
            if (trigger == RuleTriggerType.BATTERY_BELOW) {
                val level = payload["level"]?.toIntOrNull() ?: continue
                val key = "battery_below_${rule.id}"
                if (!matches) {
                    if (level in 0..100) prefs.edit().putBoolean(key, false).apply()
                    continue
                }
                if (prefs.getBoolean(key, false)) continue
                prefs.edit().putBoolean(key, true).apply()
            } else if (trigger == RuleTriggerType.NOTIFICATION_MATCH) {
                if (!matches) continue
                val fingerprint = payload["key"] ?: (payload["title"].orEmpty() + "\u0000" + payload["text"].orEmpty()).take(512)
                val key = "notification_${rule.id}"
                if (prefs.getString(key, null) == fingerprint) continue
                prefs.edit().putString(key, fingerprint).apply()
            } else if (trigger == RuleTriggerType.BATTERY_FULL || trigger == RuleTriggerType.CHARGER_CONNECTED ||
                trigger == RuleTriggerType.CHARGER_DISCONNECTED || trigger == RuleTriggerType.WIFI_CONNECTED ||
                trigger == RuleTriggerType.WIFI_DISCONNECTED) {
                if (!matches) continue
                val state = when (trigger) {
                    RuleTriggerType.BATTERY_FULL -> "full"
                    RuleTriggerType.CHARGER_CONNECTED -> "plugged"
                    RuleTriggerType.CHARGER_DISCONNECTED -> "unplugged"
                    RuleTriggerType.WIFI_CONNECTED -> "wifi_on"
                    RuleTriggerType.WIFI_DISCONNECTED -> "wifi_off"
                    else -> trigger.name
                }
                val key = "edge_${rule.id}"
                if (prefs.getString(key, null) == state) continue
                prefs.edit().putString(key, state).apply()
            } else if (!matches) {
                continue
            }

            val syntheticMatch = RuleMatch(rule, payload, "${trigger.name}:${payload.entries.joinToString { "${it.key}=${it.value}" }}")
            // Dispatcher executes Android side effects on main, but persistence/matching stay on IO.
            withContext(Dispatchers.Main.immediate) { dispatcher.execute(syntheticMatch) }
        }
    }

    private fun contextEnabled(): Boolean = settings.getBoolean("enabled", true)

    fun resetEdgeState() {
        prefs.edit().clear().apply()
    }
}
