package com.jarvis.assistant.studio

/**
 * The automations that ship with Jarvis. They are marked isDefault, so they can be edited or switched off in the
 * Automation Studio but never deleted. Bump [VERSION] when new defaults are added so existing installs receive them.
 */
object DefaultAutomations {
    const val VERSION = 1

    private fun voice(
        id: String,
        name: String,
        phrases: List<String>,
        actions: List<AutomationAction>,
        enabled: Boolean = true
    ) = AutomationRule(
        id = id,
        name = name,
        isDefault = true,
        enabled = enabled,
        trigger = AutomationTrigger(TriggerType.VOICE, phrases = phrases),
        actions = actions
    )

    private fun speak(text: String) = AutomationAction("speak", mapOf("text" to text))

    val rules: List<AutomationRule> = listOf(
        voice(
            "default_gaming_on", "Gaming Mode: On",
            listOf("gaming mode on", "start gaming mode", "game mode on", "gaming mode chalu karo", "gaming mode chalu"),
            listOf(
                AutomationAction("do_not_disturb", mapOf("state" to "on")),
                AutomationAction("brightness", mapOf("mode" to "set", "percent" to "100")),
                AutomationAction("volume", mapOf("mode" to "set", "percent" to "80")),
                speak("Gaming mode is on.")
            )
        ),
        voice(
            "default_gaming_off", "Gaming Mode: Off",
            listOf("gaming mode off", "stop gaming mode", "game mode off", "gaming mode band karo", "gaming mode band"),
            listOf(
                AutomationAction("do_not_disturb", mapOf("state" to "off")),
                AutomationAction("brightness", mapOf("mode" to "set", "percent" to "50")),
                speak("Gaming mode is off.")
            )
        ),
        voice(
            "default_dnd_on", "Do Not Disturb: On",
            listOf("do not disturb on", "dnd on", "dnd chalu karo", "turn on do not disturb"),
            listOf(AutomationAction("do_not_disturb", mapOf("state" to "on")), speak("Do not disturb is on."))
        ),
        voice(
            "default_dnd_off", "Do Not Disturb: Off",
            listOf("do not disturb off", "dnd off", "dnd band karo", "turn off do not disturb"),
            listOf(AutomationAction("do_not_disturb", mapOf("state" to "off")), speak("Do not disturb is off."))
        ),
        voice(
            "default_saver_on", "Battery Saver: On",
            listOf("battery saver on", "battery saver chalu karo", "power saving on", "turn on battery saver"),
            listOf(AutomationAction("battery_saver", mapOf("state" to "on")))
        ),
        voice(
            "default_saver_off", "Battery Saver: Off",
            listOf("battery saver off", "battery saver band karo", "power saving off", "turn off battery saver"),
            listOf(AutomationAction("battery_saver", mapOf("state" to "off")))
        ),
        voice(
            "default_torch_on", "Torch: On",
            listOf("torch on", "flashlight on", "torch chalu karo", "flash on", "turn on torch"),
            listOf(AutomationAction("flashlight", mapOf("state" to "on")))
        ),
        voice(
            "default_torch_off", "Torch: Off",
            listOf("torch off", "flashlight off", "torch band karo", "flash off", "turn off torch"),
            listOf(AutomationAction("flashlight", mapOf("state" to "off")))
        ),
        voice(
            "default_volume_set", "Set Volume",
            listOf("volume {level}", "set volume to {level}", "volume ko {level} karo"),
            listOf(AutomationAction("volume", mapOf("mode" to "set", "percent" to "{level}")))
        ),
        voice(
            "default_brightness_set", "Set Brightness",
            listOf("brightness {level}", "set brightness to {level}", "brightness ko {level} karo"),
            listOf(AutomationAction("brightness", mapOf("mode" to "set", "percent" to "{level}")))
        ),
        // Templates for the {contact} and {message} variables. They are off until you switch them on.
        voice(
            "default_quick_call", "Quick Call (template)",
            listOf("call {contact} with sim {sim}", "call {contact}", "{contact} ko call karo"),
            listOf(AutomationAction("call_contact", mapOf("contact" to "{contact}", "sim" to "{sim}"))),
            enabled = false
        ),
        voice(
            "default_quick_whatsapp", "Quick WhatsApp (template)",
            listOf("message {contact} {message}", "whatsapp {contact} {message}"),
            listOf(AutomationAction("whatsapp_message", mapOf("contact" to "{contact}", "message" to "{message}", "ask_first" to "true"))),
            enabled = false
        )
    )
}
