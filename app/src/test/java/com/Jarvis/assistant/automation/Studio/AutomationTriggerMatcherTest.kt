package com.jarvis.assistant.automation.studio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationTriggerMatcherTest {
    private val matcher = AutomationTriggerMatcher()

    private fun rule(type: RuleTriggerType, pattern: String = "event") = AutomationRule(
        name = "test", triggerType = type, triggerPattern = pattern,
        actions = listOf(AutomationAction(RuleActionType.SPEAK))
    )

    @Test fun batteryBelowUsesStrictThreshold() {
        val r = rule(RuleTriggerType.BATTERY_BELOW, "below 20")
        assertTrue(matcher.matches(r, RuleTriggerType.BATTERY_BELOW, mapOf("level" to "19")))
        assertFalse(matcher.matches(r, RuleTriggerType.BATTERY_BELOW, mapOf("level" to "20")))
        assertFalse(matcher.matches(r, RuleTriggerType.BATTERY_BELOW, mapOf("level" to "-1")))
    }

    @Test fun notificationMatchIsCaseInsensitive() {
        val r = rule(RuleTriggerType.NOTIFICATION_MATCH, "urgent")
        assertTrue(matcher.matches(r, RuleTriggerType.NOTIFICATION_MATCH, mapOf("title" to "URGENT alert")))
        assertFalse(matcher.matches(r, RuleTriggerType.NOTIFICATION_MATCH, mapOf("title" to "ordinary")))
    }

    @Test fun disabledOrWrongTriggerNeverMatches() {
        val r = rule(RuleTriggerType.DEVICE_BOOT).copy(enabled = false)
        assertFalse(matcher.matches(r, RuleTriggerType.DEVICE_BOOT, emptyMap()))
        assertFalse(matcher.matches(r, RuleTriggerType.CHARGER_CONNECTED, emptyMap()))
    }

    @Test fun wifiEventsRespectExplicitPayload() {
        val connected = rule(RuleTriggerType.WIFI_CONNECTED)
        assertTrue(matcher.matches(connected, RuleTriggerType.WIFI_CONNECTED, mapOf("connected" to "true")))
        assertFalse(matcher.matches(connected, RuleTriggerType.WIFI_CONNECTED, mapOf("connected" to "false")))
    }
}
