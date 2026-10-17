package com.jarvis.assistant.automation.studio

/**
 * Pure trigger matching, separated from Android broadcasts so the rules can be unit-tested.
 * `triggerPattern` for BATTERY_BELOW accepts a bare integer (e.g. "20") or
 * "below 20". Notification events expect `title` and/or `text` in the payload.
 */
class AutomationTriggerMatcher {
    fun matches(rule: AutomationRule, trigger: RuleTriggerType, payload: Map<String, String>): Boolean {
        if (!rule.enabled || rule.triggerType != trigger) return false
        return when (trigger) {
            RuleTriggerType.VOICE_COMMAND -> false // Voice commands use RuleMatcher.
            RuleTriggerType.BATTERY_BELOW -> {
                val threshold = Regex("(?:below\\s*)?(\\d{1,3})", RegexOption.IGNORE_CASE)
                    .find(rule.triggerPattern.trim())?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: return false
                val level = payload["level"]?.toIntOrNull() ?: return false
                threshold in 0..100 && level in 0..100 && level < threshold
            }
            RuleTriggerType.BATTERY_FULL -> payload["level"]?.toIntOrNull()?.let { it >= 100 } ?: true
            RuleTriggerType.CHARGER_CONNECTED -> payload["plugged"]?.let { it != "0" && it.isNotBlank() } ?: true
            RuleTriggerType.CHARGER_DISCONNECTED -> payload["plugged"]?.let { it == "0" } ?: true
            RuleTriggerType.DEVICE_BOOT -> true
            RuleTriggerType.WIFI_CONNECTED -> payload["connected"]?.toBooleanStrictOrNull() ?: true
            RuleTriggerType.WIFI_DISCONNECTED -> !(payload["connected"]?.toBooleanStrictOrNull() ?: false)
            RuleTriggerType.NOTIFICATION_MATCH -> {
                val haystack = listOf(payload["title"].orEmpty(), payload["text"].orEmpty())
                    .joinToString(" ").trim()
                haystack.isNotEmpty() && haystack.contains(rule.triggerPattern.trim(), ignoreCase = true)
            }
        }
    }
}
