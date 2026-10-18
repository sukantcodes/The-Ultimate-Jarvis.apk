package com.jarvis.assistant.automation.studio.model

import java.io.Serializable
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class ScheduleType : Serializable {
    ONE_TIME,
    DAILY,
    WEEKDAYS
}

enum class DeviceEventType : Serializable {
    POWER_CONNECTED,
    POWER_DISCONNECTED,
    BATTERY_LOW,
    BATTERY_FULL
}

enum class FeatureType : Serializable {
    FLASHLIGHT_ON,
    FLASHLIGHT_OFF,
    SET_VOLUME,
    SET_BRIGHTNESS,
    TOGGLE_BLUETOOTH
}

enum class TriggerType : Serializable {
    SCHEDULED,
    VOICE_COMMAND,
    DEVICE_EVENT
}

enum class ActionType : Serializable {
    SPEAK_TTS,
    SHOW_NOTIFICATION,
    OPEN_APP,
    CREATE_REMINDER,
    CONTROL_FEATURE
}

data class AutomationTrigger(
    val type: TriggerType = TriggerType.SCHEDULED,
    // Scheduled fields
    val scheduleType: ScheduleType = ScheduleType.DAILY,
    val scheduledHour: Int = 8,
    val scheduledMinute: Int = 0,
    val scheduledDaysOfWeek: List<Int> = listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY),
    val oneTimeEpochMillis: Long = 0L,

    // Voice command fields (Supports English & Hindi)
    val voicePhrases: List<String> = emptyList(),
    val voiceMatchExact: Boolean = false,

    // Device event fields
    val deviceEventType: DeviceEventType = DeviceEventType.POWER_CONNECTED,
    val batteryLevelThreshold: Int = 20
) : Serializable {

    fun getDisplaySummary(): String {
        return when (type) {
            TriggerType.SCHEDULED -> {
                val timeStr = String.format(Locale.getDefault(), "%02d:%02d", scheduledHour, scheduledMinute)
                when (scheduleType) {
                    ScheduleType.ONE_TIME -> {
                        if (oneTimeEpochMillis > 0) {
                            val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
                            "One-time at " + sdf.format(Date(oneTimeEpochMillis))
                        } else {
                            "One-time at $timeStr"
                        }
                    }
                    ScheduleType.DAILY -> "Daily at $timeStr"
                    ScheduleType.WEEKDAYS -> {
                        val dayNames = scheduledDaysOfWeek.map { dayInt ->
                            when (dayInt) {
                                Calendar.SUNDAY -> "Sun"
                                Calendar.MONDAY -> "Mon"
                                Calendar.TUESDAY -> "Tue"
                                Calendar.WEDNESDAY -> "Wed"
                                Calendar.THURSDAY -> "Thu"
                                Calendar.FRIDAY -> "Fri"
                                Calendar.SATURDAY -> "Sat"
                                else -> "Day$dayInt"
                            }
                        }
                        if (dayNames.isEmpty()) "Weekdays at $timeStr" else "${dayNames.joinToString(", ")} at $timeStr"
                    }
                }
            }
            TriggerType.VOICE_COMMAND -> {
                if (voicePhrases.isEmpty()) {
                    "Voice: (No phrases set)"
                } else {
                    "Voice: \"${voicePhrases.first()}\"" + if (voicePhrases.size > 1) " (+${voicePhrases.size - 1} phrases)" else ""
                }
            }
            TriggerType.DEVICE_EVENT -> {
                when (deviceEventType) {
                    DeviceEventType.POWER_CONNECTED -> "Charger Connected"
                    DeviceEventType.POWER_DISCONNECTED -> "Charger Disconnected"
                    DeviceEventType.BATTERY_LOW -> "Battery Low (≤ $batteryLevelThreshold%)"
                    DeviceEventType.BATTERY_FULL -> "Battery Full (≥ $batteryLevelThreshold%)"
                }
            }
        }
    }
}

data class AutomationAction(
    val id: String = UUID.randomUUID().toString(),
    val type: ActionType = ActionType.SPEAK_TTS,

    // SPEAK_TTS
    val ttsMessage: String = "",

    // SHOW_NOTIFICATION
    val notificationTitle: String = "",
    val notificationBody: String = "",

    // OPEN_APP
    val appPackageName: String = "",
    val appName: String = "",

    // CREATE_REMINDER
    val reminderTitle: String = "",
    val reminderNotes: String = "",

    // CONTROL_FEATURE
    val featureType: FeatureType = FeatureType.FLASHLIGHT_ON,
    val featureValue: Int = 50 // volume or brightness 0-100
) : Serializable {

    fun getDisplaySummary(): String {
        return when (type) {
            ActionType.SPEAK_TTS -> "Speak: \"${ttsMessage.take(30)}${if (ttsMessage.length > 30) "..." else ""}\""
            ActionType.SHOW_NOTIFICATION -> "Notify: \"${notificationTitle.take(25)}\""
            ActionType.OPEN_APP -> "Open: ${appName.ifBlank { appPackageName.substringAfterLast('.') }}"
            ActionType.CREATE_REMINDER -> "Reminder: \"${reminderTitle.take(25)}\""
            ActionType.CONTROL_FEATURE -> {
                when (featureType) {
                    FeatureType.FLASHLIGHT_ON -> "Flashlight ON"
                    FeatureType.FLASHLIGHT_OFF -> "Flashlight OFF"
                    FeatureType.SET_VOLUME -> "Set Volume: $featureValue%"
                    FeatureType.SET_BRIGHTNESS -> "Set Brightness: $featureValue%"
                    FeatureType.TOGGLE_BLUETOOTH -> "Toggle Bluetooth"
                }
            }
        }
    }
}

data class AutomationRule(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val description: String = "",
    val isEnabled: Boolean = true,
    val trigger: AutomationTrigger,
    val actions: List<AutomationAction>,
    val createdAt: Long = System.currentTimeMillis(),
    val lastExecutedAt: Long = 0L,
    val executionCount: Int = 0
) : Serializable {

    fun getTriggerDisplay(): String = trigger.getDisplaySummary()

    fun getActionsDisplay(): String {
        if (actions.isEmpty()) return "No actions configured"
        return actions.map { it.getDisplaySummary() }.joinToString(" • ")
    }
}
