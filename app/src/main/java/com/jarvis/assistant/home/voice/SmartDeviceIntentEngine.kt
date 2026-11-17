package com.jarvis.assistant.home.voice

import com.jarvis.assistant.home.DeviceAction
import com.jarvis.assistant.home.DeviceActionType
import com.jarvis.assistant.home.DeviceCategory
import com.jarvis.assistant.home.DeviceStatus
import com.jarvis.assistant.home.HomeDevice
import com.jarvis.assistant.home.HomeGroup
import com.jarvis.assistant.home.HomeRoom
import com.jarvis.assistant.home.automation.Automation

enum class ListFilter { ALL, OFFLINE, ONLINE }

sealed class HomeIntent {
    data class Control(val action: DeviceAction, val devices: List<HomeDevice>, val description: String) : HomeIntent()
    data class RunScene(val scene: Automation) : HomeIntent()
    data class ListDevices(val filter: ListFilter) : HomeIntent()
    data class Connect(val device: HomeDevice) : HomeIntent()
    data class Clarify(val question: String) : HomeIntent()
}

/** What the engine may resolve against: the user's own registry, nothing else. */
data class HomeSnapshot(
    val devices: List<HomeDevice>,
    val rooms: List<HomeRoom>,
    val groups: List<HomeGroup>,
    val scenes: List<Automation>
)

/**
 * Turns an English, Hindi or Hinglish sentence into a smart-home intent by matching device names and aliases, rooms,
 * groups, categories, scenes and an action word. Returns null when the sentence is not about the user's smart home, so
 * other JARVIS commands (flashlight, volume, alarms) are never hijacked. Pure and deterministic.
 */
object SmartDeviceIntentEngine {

    private val ON_WORDS = listOf("turn on", "switch on", "put on", "power on", "start", "on kar", "on karo", "chalu", "chalao", "chala do", "jala", "jalao", "चालू", "चला", "जला", "ऑन", "खोल")
    private val OFF_WORDS = listOf("turn off", "switch off", "power off", "shut off", "shut down", "off", "off kar", "off karo", "band", "bandh", "bujha", "bujhao", "बंद", "बुझा", "ऑफ")
    private val TOGGLE_WORDS = listOf("toggle")
    private val CONNECT_WORDS = listOf("connect", "pair", "jod", "jodo", "कनेक्ट", "जोड़")
    private val SCENE_VERBS = listOf("activate", "run", "start", "enable", "launch", "chalao", "lagao", "laga do", "शुरू", "चालू")
    private val ALL_WORDS = listOf("everything", "all", "sab", "sabhi", "sara", "सब", "सभी", "सारे")
    private val LIST_WORDS = listOf("show", "list", "which", "what", "kaun", "konse", "kitne", "dikhao", "batao", "दिखाओ", "बताओ", "कौन")

    private val CATEGORY_WORDS: Map<DeviceCategory, List<String>> = mapOf(
        DeviceCategory.LIGHT to listOf("light", "lights", "lamp", "bulb", "bulbs", "batti", "lite", "लाइट", "बत्ती", "बल्ब", "लैंप"),
        DeviceCategory.FAN to listOf("fan", "fans", "pankha", "पंखा", "फैन"),
        DeviceCategory.AC to listOf("ac", "a c", "air conditioner", "एसी"),
        DeviceCategory.TV to listOf("tv", "television", "टीवी"),
        DeviceCategory.PLUG to listOf("plug", "socket", "switch board", "प्लग", "सॉकेट"),
        DeviceCategory.MOTOR to listOf("motor", "pump", "मोटर", "पंप"),
        DeviceCategory.SPEAKER to listOf("speaker", "speakers", "स्पीकर")
    )

    fun normalize(text: String): String = text.lowercase()
        .replace(Regex("^(hey jarvis|hi jarvis|hello jarvis|ok jarvis|jarvis)[,\\s]*"), "")
        .replace(Regex("[^\\p{L}\\p{M}\\p{N}]+"), " ").trim()

    fun parse(text: String, snap: HomeSnapshot): HomeIntent? {
        val t = normalize(text)
        if (t.isEmpty()) return null
        val padded = " $t "
        fun has(word: String) = padded.contains(" $word ")
        fun hasAny(words: List<String>) = words.any { has(it) }

        // Lists: "show all offline devices", "what devices are connected"
        val mentionsDevices = has("devices") || has("device") || has("डिवाइस")
        if (mentionsDevices && hasAny(LIST_WORDS) || (mentionsDevices && (has("offline") || has("online") || has("connected")))) {
            val filter = when {
                has("offline") || has("disconnected") || has("band") -> ListFilter.OFFLINE
                has("online") || has("connected") -> ListFilter.ONLINE
                else -> ListFilter.ALL
            }
            return HomeIntent.ListDevices(filter)
        }

        val sceneMatch = snap.scenes.filter { it.name.isNotBlank() && has(normalize(it.name)) }.maxByOrNull { it.name.length }
        val deviceMatches = matchDevices(padded, snap.devices)
        // "bedroom lights on" ends with a bare "on"; a bare "on" elsewhere ("play music on …") is not a command.
        val wantsOn = hasAny(ON_WORDS) || padded.endsWith(" on ")
        val wantsOff = hasAny(OFF_WORDS)

        if (sceneMatch != null && deviceMatches.isEmpty() && (hasAny(SCENE_VERBS) || has("mode") || has("scene") || wantsOn)) return HomeIntent.RunScene(sceneMatch)

        if (hasAny(CONNECT_WORDS) && !wantsOn && !wantsOff) {
            val d = deviceMatches.firstOrNull()
                ?: snap.devices.filter { it.connection.isBluetooth || it.category == DeviceCategory.SPEAKER }.takeIf { padded.contains("bluetooth") || has("speaker") || has("स्पीकर") }?.singleOrNull()
            return when {
                d != null -> HomeIntent.Connect(d)
                deviceMatches.size > 1 -> HomeIntent.Clarify("Which device should I connect: ${deviceMatches.joinToString { it.name }}?")
                else -> null
            }
        }

        val action = when {
            hasAny(TOGGLE_WORDS) -> DeviceActionType.TOGGLE
            wantsOff && !wantsOn -> DeviceActionType.POWER_OFF
            wantsOn && !wantsOff -> DeviceActionType.POWER_ON
            wantsOn && wantsOff -> if (padded.indexOfFirst(ON_WORDS) <= padded.indexOfFirst(OFF_WORDS)) DeviceActionType.POWER_ON else DeviceActionType.POWER_OFF
            else -> return null
        }

        // 1) a specific device or alias
        if (deviceMatches.size == 1) return control(action, deviceMatches, deviceMatches[0].name)
        if (deviceMatches.size > 1) {
            val names = deviceMatches.map { it.name.lowercase() }
            if (names.distinct().size < names.size) return HomeIntent.Clarify("I found more than one device with that name. Which one: ${deviceMatches.joinToString { (snap.rooms.firstOrNull { r -> r.id == it.roomId }?.name ?: "no room") + " " + it.name }}?")
            return control(action, deviceMatches, "${deviceMatches.size} devices")
        }

        // 2) a group
        val group = snap.groups.filter { has(normalize(it.name)) }.maxByOrNull { it.name.length }
        if (group != null) return control(action, group.deviceIds.mapNotNull { id -> snap.devices.firstOrNull { it.id == id } }, group.name)

        // 3) room and/or category
        val room = snap.rooms.filter { has(normalize(it.name)) }.maxByOrNull { it.name.length }
        val category = CATEGORY_WORDS.entries.firstOrNull { (_, words) -> words.any { has(it) } }?.key
        val everything = hasAny(ALL_WORDS)
        val inMyRoom = has("my room") || has("मेरे कमरे") || has("mere room") || has("meri room")
        if (room != null) {
            val inRoom = snap.devices.filter { it.roomId == room.id && (category == null || it.category == category) }
            if (category != null || everything) return control(action, inRoom, "${room.name}${category?.let { " ${it.label.lowercase()}s" } ?: ""}")
            return null
        }
        if (inMyRoom && everything) {
            return if (snap.rooms.size == 1) control(action, snap.devices.filter { it.roomId == snap.rooms[0].id }, snap.rooms[0].name)
            else HomeIntent.Clarify("Which room do you mean?")
        }
        if (category != null) {
            val ofCategory = snap.devices.filter { it.category == category }
            if (ofCategory.isEmpty()) return null
            val rooms = ofCategory.map { it.roomId }.distinct()
            return if (ofCategory.size == 1 || everything || has(CATEGORY_WORDS.getValue(category).first { w -> has(w) }.let { w -> if (w.endsWith("s")) w else "${w}s" }))
                control(action, ofCategory, "all ${category.label.lowercase()}s")
            else HomeIntent.Clarify("You have ${ofCategory.size} ${category.label.lowercase()} devices in ${rooms.size} place(s). Which room?")
        }
        if (everything && (has("devices") || has("everything") || has("sab"))) return control(action, snap.devices, "everything")
        return null
    }

    private fun String.indexOfFirst(words: List<String>): Int = words.map { indexOf(" $it ") }.filter { it >= 0 }.minOrNull() ?: Int.MAX_VALUE

    private fun control(action: DeviceActionType, devices: List<HomeDevice>, label: String): HomeIntent =
        if (devices.isEmpty()) HomeIntent.Clarify("I could not find any matching devices in your home.")
        else HomeIntent.Control(DeviceAction(action), devices, label)

    /** Devices whose name or alias appears in the sentence. Keeps only the longest-matching phrase(s) so "light" does not beat "bedroom light". */
    private fun matchDevices(padded: String, devices: List<HomeDevice>): List<HomeDevice> {
        val scored = devices.mapNotNull { d ->
            val best = (listOf(d.name) + d.aliases).map { normalize(it) }.filter { it.isNotEmpty() && padded.contains(" $it ") }.maxOfOrNull { it.length }
            if (best == null) null else d to best
        }
        val top = scored.maxOfOrNull { it.second } ?: return emptyList()
        return scored.filter { it.second == top }.map { it.first }
    }

    /** Spoken answer for a list request, built only from the registry's recorded status. */
    fun describeList(filter: ListFilter, devices: List<HomeDevice>): String {
        val subset = when (filter) {
            ListFilter.ALL -> devices
            ListFilter.OFFLINE -> devices.filter { it.status == DeviceStatus.OFFLINE }
            ListFilter.ONLINE -> devices.filter { it.status == DeviceStatus.ONLINE }
        }
        val noun = when (filter) { ListFilter.ALL -> "registered"; ListFilter.OFFLINE -> "offline"; ListFilter.ONLINE -> "connected" }
        if (devices.isEmpty()) return "You have no devices registered in JARVIS Home yet."
        if (subset.isEmpty()) return "No $noun devices."
        val unknown = if (filter == ListFilter.ALL) 0 else devices.count { it.status == DeviceStatus.UNKNOWN }
        return "${subset.size} $noun: ${subset.joinToString { it.name }}." + if (unknown > 0) " $unknown device(s) have not been checked yet." else ""
    }

}
