package com.jarvis.assistant.automation.studio

import java.util.UUID

enum class RuleTriggerType {
    VOICE_COMMAND,
    BATTERY_BELOW,
    BATTERY_FULL,
    CHARGER_CONNECTED,
    CHARGER_DISCONNECTED,
    NOTIFICATION_MATCH,
    DEVICE_BOOT,
    WIFI_CONNECTED,
    WIFI_DISCONNECTED
}

enum class RuleActionType {
    OPEN_APP,
    SPEAK,
    PLACE_CALL,
    WHATSAPP_MESSAGE,
    SET_VOLUME,
    SET_BRIGHTNESS,
    SET_FLASHLIGHT,
    SET_RINGER_MODE,
    MEDIA_PLAY,
    MEDIA_PAUSE,
    MEDIA_NEXT,
    MEDIA_PREVIOUS,
    OPEN_WIFI_SETTINGS,
    OPEN_BLUETOOTH_SETTINGS
}

data class AutomationAction(
    val type: RuleActionType,
    val parameters: Map<String, String> = emptyMap()
)

data class AutomationRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val enabled: Boolean = true,
    val triggerType: RuleTriggerType = RuleTriggerType.VOICE_COMMAND,
    /**
     * Voice template examples: "call {contact}", "set volume to {level}".
     * For notification rules, this is a case-insensitive text substring.
     */
    val triggerPattern: String,
    val actions: List<AutomationAction>,
    val protected: Boolean = false,
    val priority: Int = 100,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis()
) {
    init {
        require(name.isNotBlank()) { "Rule name cannot be blank." }
        require(triggerPattern.isNotBlank()) { "Trigger pattern cannot be blank." }
        require(actions.isNotEmpty()) { "A rule must contain at least one action." }
    }
}

data class RuleMatch(
    val rule: AutomationRule,
    val variables: Map<String, String>,
    val matchedText: String
)

sealed class InterceptionResult {
    data class Handled(
        val ruleId: String,
        val responseText: String,
        val actionResults: List<ActionExecutionResult>
    ) : InterceptionResult()

    data class PassThrough(val reason: String = "No enabled local rule matched.") : InterceptionResult()

    data class Failed(
        val ruleId: String?,
        val responseText: String,
        val cause: Throwable? = null
    ) : InterceptionResult()
}

data class ActionExecutionResult(
    val success: Boolean,
    val message: String,
    val shouldSpeak: Boolean = false
)
