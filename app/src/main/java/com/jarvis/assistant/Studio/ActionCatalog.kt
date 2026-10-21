package com.jarvis.assistant.studio

enum class ParamKind { TEXT, NUMBER, CHOICE, TOGGLE }

data class ParamSpec(
    val key: String,
    val label: String,
    val hint: String = "",
    val kind: ParamKind = ParamKind.TEXT,
    val choices: List<String> = emptyList(),
    val default: String = ""
)

data class ActionSpec(
    val key: String,
    val label: String,
    val description: String,
    val params: List<ParamSpec>
)

/**
 * The "action blocks" a rule can use. Each block runs a function Jarvis already has (calls, WhatsApp, media,
 * settings and so on); nothing is re-implemented here. Text fields can contain variables such as {contact},
 * {message}, {query}, {level}, {battery}, {sender} and {app_name}, which are filled in when the rule runs.
 */
object ActionCatalog {
    val settingsPages = listOf(
        "wifi", "bluetooth", "display", "sound", "location", "airplane", "apps", "accessibility", "security",
        "battery", "date", "language", "storage", "nfc", "mobile_network", "privacy", "notifications", "developer", "main"
    )

    val all: List<ActionSpec> = listOf(
        ActionSpec(
            "speak", "Speak", "Says a sentence out loud with the phone's voice.",
            listOf(ParamSpec("text", "What to say", "Charging connected. Battery is at {battery} percent."))
        ),
        ActionSpec(
            "announce", "Announce (your voice setting)",
            "Says a sentence in Jarvis's voice when \"Use Jarvis Voice\" is on, otherwise with the phone's voice.",
            listOf(ParamSpec("text", "What to say", "Sir, {sender} se {app_name} par message aaya hai."))
        ),
        ActionSpec(
            "call_contact", "Call a contact", "Phone call to a saved contact or number.",
            listOf(
                ParamSpec("contact", "Contact or number", "{contact}"),
                ParamSpec("sim", "SIM card", "Leave empty to use the default SIM", ParamKind.CHOICE, listOf("", "1", "2"))
            )
        ),
        ActionSpec(
            "whatsapp_message", "Send a WhatsApp message", "Opens WhatsApp and sends a message to a contact.",
            listOf(
                ParamSpec("contact", "Contact", "{contact}"),
                ParamSpec("message", "Message", "{message}")
            )
        ),
        ActionSpec(
            "whatsapp_call", "WhatsApp call", "Starts a WhatsApp voice or video call.",
            listOf(
                ParamSpec("contact", "Contact", "{contact}"),
                ParamSpec("call_type", "Call type", "", ParamKind.CHOICE, listOf("voice", "video"), "voice")
            )
        ),
        ActionSpec(
            "open_app", "Open an app", "Opens an installed app by name.",
            listOf(ParamSpec("app", "App name", "YouTube"))
        ),
        ActionSpec(
            "media", "Media control", "Play, pause or skip whatever is playing.",
            listOf(ParamSpec("action", "Action", "", ParamKind.CHOICE, listOf("play", "pause", "next", "previous", "stop"), "pause"))
        ),
        ActionSpec(
            "play_youtube", "Play on YouTube", "Searches YouTube and plays the first result.",
            listOf(ParamSpec("query", "What to play", "{query}"))
        ),
        ActionSpec(
            "volume", "Volume", "Sets or changes the media volume.",
            listOf(
                ParamSpec("mode", "Mode", "", ParamKind.CHOICE, listOf("set", "increase", "decrease"), "set"),
                ParamSpec("percent", "Percent (0 to 100)", "{level}", ParamKind.NUMBER, default = "50")
            )
        ),
        ActionSpec(
            "brightness", "Brightness", "Sets or changes the screen brightness.",
            listOf(
                ParamSpec("mode", "Mode", "", ParamKind.CHOICE, listOf("set", "increase", "decrease"), "set"),
                ParamSpec("percent", "Percent (0 to 100)", "{level}", ParamKind.NUMBER, default = "50")
            )
        ),
        ActionSpec(
            "flashlight", "Flashlight", "Turns the torch on or off.",
            listOf(ParamSpec("state", "State", "", ParamKind.CHOICE, listOf("on", "off"), "on"))
        ),
        ActionSpec(
            "settings_page", "Open a settings page", "Opens a page of Android Settings.",
            listOf(ParamSpec("page", "Page", "", ParamKind.CHOICE, settingsPages, "wifi"))
        ),
        ActionSpec(
            "battery_saver", "Battery Saver", "Turns Battery Saver on or off.",
            listOf(ParamSpec("state", "State", "", ParamKind.CHOICE, listOf("on", "off"), "on"))
        ),
        ActionSpec(
            "do_not_disturb", "Do Not Disturb", "Turns Do Not Disturb on or off.",
            listOf(ParamSpec("state", "State", "", ParamKind.CHOICE, listOf("on", "off"), "on"))
        ),
        ActionSpec("lock_screen", "Lock the screen", "Locks the phone.", emptyList()),
        ActionSpec("go_background", "Go to background", "Sends Jarvis back to standby.", emptyList()),
        ActionSpec(
            "run_macro", "Run a macro", "Runs one of the ready-made app macros.",
            listOf(
                ParamSpec("name", "Macro name", "chatgpt image"),
                ParamSpec("input", "Input text", "{query}")
            )
        ),
        ActionSpec(
            "wait", "Wait", "Pauses before the next step.",
            listOf(ParamSpec("seconds", "Seconds", "2", ParamKind.NUMBER, default = "2"))
        )
    )

    fun find(key: String): ActionSpec? = all.firstOrNull { it.key == key }
}
