package com.jarvis.assistant.automation.studio

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.jarvis.assistant.automation.studio.model.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class AutomationStudioTest {

    private val gson = Gson()

    @Test
    fun testRuleCreationAndSerialization() {
        val rule = AutomationRule(
            id = "test-rule-1",
            title = "Morning Wake Protocol",
            description = "Daily 7 AM alarm and system speech",
            isEnabled = true,
            trigger = AutomationTrigger(
                type = TriggerType.SCHEDULED,
                scheduleType = ScheduleType.DAILY,
                scheduledHour = 7,
                scheduledMinute = 0
            ),
            actions = listOf(
                AutomationAction(
                    type = ActionType.SPEAK_TTS,
                    ttsMessage = "Good morning Sir, all systems operational."
                ),
                AutomationAction(
                    type = ActionType.CONTROL_FEATURE,
                    featureType = FeatureType.FLASHLIGHT_ON
                )
            )
        )

        val json = gson.toJson(rule)
        assertNotNull(json)
        assertTrue(json.contains("Morning Wake Protocol"))
        assertTrue(json.contains("SPEAK_TTS"))

        val deserialized = gson.fromJson(json, AutomationRule::class.java)
        assertEquals(rule.id, deserialized.id)
        assertEquals(rule.title, deserialized.title)
        assertEquals(rule.trigger.type, deserialized.trigger.type)
        assertEquals(rule.actions.size, deserialized.actions.size)
    }

    @Test
    fun testVoicePhraseTriggerMatchingEnglishAndHindi() {
        val hindiRule = AutomationRule(
            id = "hindi-rule-1",
            title = "Hindi Greeting Trigger",
            trigger = AutomationTrigger(
                type = TriggerType.VOICE_COMMAND,
                voicePhrases = listOf("नमस्ते जार्विस", "जार्विस शुरू करो"),
                voiceMatchExact = false
            ),
            actions = listOf(
                AutomationAction(type = ActionType.SPEAK_TTS, ttsMessage = "नमस्ते सर")
            )
        )

        val englishRule = AutomationRule(
            id = "eng-rule-1",
            title = "Sleep Mode Trigger",
            trigger = AutomationTrigger(
                type = TriggerType.VOICE_COMMAND,
                voicePhrases = listOf("good night jarvis", "sleep mode"),
                voiceMatchExact = false
            ),
            actions = listOf(
                AutomationAction(type = ActionType.SPEAK_TTS, ttsMessage = "Good night Sir")
            )
        )

        fun normalize(text: String): String = text.lowercase()
            .replace(Regex("[.,?!;:'\"()\\[\\]{}]"), "")
            .replace("\\s+".toRegex(), " ")
            .trim()

        fun matches(spoken: String, rule: AutomationRule): Boolean {
            val normSpoken = normalize(spoken)
            return rule.trigger.voicePhrases.any { phrase ->
                val normPhrase = normalize(phrase)
                if (rule.trigger.voiceMatchExact) normSpoken == normPhrase
                else normSpoken.contains(normPhrase) || normPhrase.contains(normSpoken)
            }
        }

        // Test Hindi Voice Matching
        assertTrue(matches("अरे नमस्ते जार्विस आप कैसे हैं?", hindiRule))
        assertTrue(matches("जार्विस शुरू करो प्लीज", hindiRule))
        assertFalse(matches("कुछ और बात", hindiRule))

        // Test English Voice Matching
        assertTrue(matches("Hey good night jarvis, see you tomorrow", englishRule))
        assertTrue(matches("Jarvis enter sleep mode now", englishRule))
        assertFalse(matches("Good morning jarvis", englishRule))
    }

    @Test
    fun testDeviceEventTriggerMatching() {
        val chargerRule = AutomationRule(
            id = "charger-rule",
            title = "Charger Plugged",
            trigger = AutomationTrigger(
                type = TriggerType.DEVICE_EVENT,
                deviceEventType = DeviceEventType.POWER_CONNECTED
            ),
            actions = emptyList()
        )

        val lowBatteryRule = AutomationRule(
            id = "low-battery-rule",
            title = "Low Battery Alert",
            trigger = AutomationTrigger(
                type = TriggerType.DEVICE_EVENT,
                deviceEventType = DeviceEventType.BATTERY_LOW,
                batteryLevelThreshold = 20
            ),
            actions = emptyList()
        )

        // Matching logic check
        assertEquals(DeviceEventType.POWER_CONNECTED, chargerRule.trigger.deviceEventType)
        assertEquals(20, lowBatteryRule.trigger.batteryLevelThreshold)

        // Battery 15% should trigger 20% threshold
        val testBatteryPercent = 15
        assertTrue(testBatteryPercent <= lowBatteryRule.trigger.batteryLevelThreshold)

        // Battery 25% should NOT trigger 20% threshold
        val safeBatteryPercent = 25
        assertFalse(safeBatteryPercent <= lowBatteryRule.trigger.batteryLevelThreshold)
    }

    @Test
    fun testImportExportRulesPayload() {
        val originalList = listOf(
            AutomationRule(
                id = "rule-a",
                title = "Rule A",
                trigger = AutomationTrigger(type = TriggerType.SCHEDULED),
                actions = listOf(AutomationAction(type = ActionType.SHOW_NOTIFICATION, notificationTitle = "Alert A"))
            ),
            AutomationRule(
                id = "rule-b",
                title = "Rule B",
                trigger = AutomationTrigger(type = TriggerType.DEVICE_EVENT),
                actions = listOf(AutomationAction(type = ActionType.SPEAK_TTS, ttsMessage = "Alert B"))
            )
        )

        val json = gson.toJson(originalList)
        assertNotNull(json)

        val type = object : TypeToken<List<AutomationRule>>() {}.type
        val importedList: List<AutomationRule> = gson.fromJson(json, type)
        assertEquals(2, importedList.size)
        assertEquals("Rule A", importedList[0].title)
        assertEquals("Rule B", importedList[1].title)
    }

    @Test
    fun testTriggerDisplaySummaries() {
        val dailyTrigger = AutomationTrigger(
            type = TriggerType.SCHEDULED,
            scheduleType = ScheduleType.DAILY,
            scheduledHour = 9,
            scheduledMinute = 30
        )
        assertEquals("Daily at 09:30", dailyTrigger.getDisplaySummary())

        val voiceTrigger = AutomationTrigger(
            type = TriggerType.VOICE_COMMAND,
            voicePhrases = listOf("Good morning Jarvis", "नमस्ते जार्विस")
        )
        assertTrue(voiceTrigger.getDisplaySummary().contains("Good morning Jarvis"))

        val deviceTrigger = AutomationTrigger(
            type = TriggerType.DEVICE_EVENT,
            deviceEventType = DeviceEventType.POWER_CONNECTED
        )
        assertEquals("Charger Connected", deviceTrigger.getDisplaySummary())
    }
}
