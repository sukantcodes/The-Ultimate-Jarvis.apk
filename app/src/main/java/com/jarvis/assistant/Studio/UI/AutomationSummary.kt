package com.jarvis.assistant.studio.ui

import com.jarvis.assistant.studio.ActionCatalog
import com.jarvis.assistant.studio.AutomationRule
import com.jarvis.assistant.studio.TriggerType

/** Short, readable descriptions of an automation for the list. */
object AutomationSummary {
    fun trigger(rule: AutomationRule): String {
        val t = rule.trigger
        return when (t.type) {
            TriggerType.VOICE -> {
                if (t.phrases.isEmpty()) {
                    "No phrases yet"
                } else {
                    val shown = t.phrases.take(2).joinToString("  \u00B7  ") { "\"" + it + "\"" }
                    "Say: " + shown + (if (t.phrases.size > 2) "  +" + (t.phrases.size - 2) + " more" else "")
                }
            }
            TriggerType.BATTERY_LOW -> "When the battery drops to " + t.threshold + "% or less"
            TriggerType.BATTERY_FULL -> "When the battery is full"
            TriggerType.PLUGGED_IN -> "When the charger is connected"
            TriggerType.UNPLUGGED -> "When the charger is disconnected"
            TriggerType.MESSAGE_RECEIVED -> {
                val app = if (t.appFilter.isBlank()) "a message" else "a " + appLabel(t.appFilter) + " message"
                "When " + app + " arrives" + (if (t.senderFilter.isBlank()) "" else " from " + t.senderFilter)
            }
        }
    }

    fun actions(rule: AutomationRule): String =
        rule.actions.joinToString("  \u2192  ") { ActionCatalog.find(it.type)?.label ?: it.type }
            .ifEmpty { "No actions yet" }

    fun appLabel(key: String): String = when (key.lowercase()) {
        "whatsapp" -> "WhatsApp"
        "instagram" -> "Instagram"
        "instazen" -> "Instazen"
        else -> key
    }
}
