package com.jarvis.assistant.udn

/**
 * Capability catalog for the Universal Device Network. The identifiers and sensitivity levels mirror
 * supabase/functions/udn-api/catalog.ts; the server is the authority and rejects anything not listed there.
 */
enum class Sensitivity { LOW, MEDIUM, HIGH }

object UdnCapabilities {
    const val DEVICE_INFO = "device.info"
    const val DEVICE_BATTERY = "device.battery"
    const val DEVICE_CONNECTIVITY = "device.connectivity"
    const val DEVICE_LOCK = "device.lock"
    const val APP_LAUNCH = "app.launch"
    const val MODULE_OPEN = "module.open"
    const val MODULE_LIST = "module.list"
    const val NOTIFY_SHOW = "notify.show"
    const val SETTINGS_VOLUME = "settings.volume"
    const val SETTINGS_BRIGHTNESS = "settings.brightness"
    const val SETTINGS_FLASHLIGHT = "settings.flashlight"
    const val LISTENING_STOP = "listening.stop"
    const val CHAT_ASK = "chat.ask"
    const val ROUTINE_RUN = "routine.run"
    const val LOCATION_GET = "location.get"
    const val CAMERA_OPEN = "camera.open"

    private val sensitivity = mapOf(
        DEVICE_INFO to Sensitivity.LOW, DEVICE_BATTERY to Sensitivity.LOW, DEVICE_CONNECTIVITY to Sensitivity.LOW,
        DEVICE_LOCK to Sensitivity.MEDIUM, APP_LAUNCH to Sensitivity.LOW, MODULE_OPEN to Sensitivity.LOW,
        MODULE_LIST to Sensitivity.LOW, NOTIFY_SHOW to Sensitivity.LOW, SETTINGS_VOLUME to Sensitivity.MEDIUM,
        SETTINGS_BRIGHTNESS to Sensitivity.MEDIUM, SETTINGS_FLASHLIGHT to Sensitivity.MEDIUM,
        LISTENING_STOP to Sensitivity.LOW, CHAT_ASK to Sensitivity.MEDIUM, ROUTINE_RUN to Sensitivity.MEDIUM,
        LOCATION_GET to Sensitivity.HIGH, CAMERA_OPEN to Sensitivity.HIGH
    )

    val all: Set<String> get() = sensitivity.keys

    fun sensitivityOf(capability: String): Sensitivity = sensitivity[capability] ?: Sensitivity.HIGH

    /** Medium and high commands must be confirmed by the sending parent before the server accepts them. */
    fun needsSourceConfirmation(capability: String): Boolean = sensitivityOf(capability) != Sensitivity.LOW

    fun label(capability: String): String = when (capability) {
        DEVICE_INFO -> "device information"
        DEVICE_BATTERY -> "battery status"
        DEVICE_CONNECTIVITY -> "connectivity status"
        DEVICE_LOCK -> "lock the screen"
        APP_LAUNCH -> "launch an app"
        MODULE_OPEN -> "open a JARVIS module"
        MODULE_LIST -> "list JARVIS modules"
        NOTIFY_SHOW -> "show a notification"
        SETTINGS_VOLUME -> "change the volume"
        SETTINGS_BRIGHTNESS -> "change the brightness"
        SETTINGS_FLASHLIGHT -> "control the flashlight"
        LISTENING_STOP -> "stop background listening"
        CHAT_ASK -> "ask JARVIS a question"
        ROUTINE_RUN -> "run a routine"
        LOCATION_GET -> "share the location"
        CAMERA_OPEN -> "open the camera"
        else -> capability
    }
}

/** JARVIS screens that can be opened remotely. Only modules with a real Activity are listed. */
data class UdnModule(val id: String, val label: String, val aliases: List<String>)

object UdnModules {
    val all: List<UdnModule> = listOf(
        UdnModule("chatbox", "Chatbox", listOf("chatbox", "chat box", "chat", "ai chat", "conversation")),
        UdnModule("home", "JARVIS Home", listOf("jarvis home", "smart home", "home dashboard", "home")),
        UdnModule("settings", "Settings", listOf("settings", "setting")),
        UdnModule("automation_studio", "Automation Studio", listOf("automation studio", "automation", "automations")),
        UdnModule("camera_scan", "Camera Scan", listOf("camera scan", "scanner", "scan")),
        UdnModule("memories", "Memories", listOf("memories", "memory", "notes")),
        UdnModule("terminal", "Jarvis Terminal", listOf("terminal", "jarvis terminal")),
        UdnModule("alarms", "Alarms", listOf("alarms", "alarm", "clock")),
        UdnModule("skill_store", "Skill Store", listOf("skill store", "skills")),
        UdnModule("device_network", "Device Network", listOf("device network", "network", "devices"))
    )

    fun byId(id: String): UdnModule? = all.firstOrNull { it.id == id }

    /** Longest alias wins so "jarvis home" is not read as "home" + noise. */
    fun fromSpoken(text: String): UdnModule? {
        val t = text.lowercase()
        return all.flatMap { m -> m.aliases.map { it to m } }
            .filter { (alias, _) -> Regex("(^|\\W)" + Regex.escape(alias) + "(\\W|$)").containsMatchIn(t) }
            .maxByOrNull { it.first.length }?.second
    }
}
