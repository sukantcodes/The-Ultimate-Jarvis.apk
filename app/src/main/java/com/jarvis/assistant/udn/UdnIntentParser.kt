package com.jarvis.assistant.udn

import java.util.Locale

/**
 * Natural-language layer for UDN voice and text commands (English, Hindi, Hinglish). Pure Kotlin with no Android
 * dependencies so it can be unit-tested on the JVM.
 *
 * A request is only treated as a network command when it names a remote target (a device name, "other device",
 * "all devices") or asks about the network itself. Anything else returns null so the existing JARVIS command
 * pipeline keeps handling it exactly as before.
 */
sealed class UdnTarget {
    object Other : UdnTarget()
    object All : UdnTarget()
    data class Named(val phrase: String) : UdnTarget()
}

enum class UdnQuery { INFO, BATTERY, CONNECTIVITY, ONLINE }

sealed class UdnIntent {
    object ListDevices : UdnIntent()
    data class Query(val kind: UdnQuery) : UdnIntent()
    data class OpenModule(val moduleId: String) : UdnIntent()
    object ListModules : UdnIntent()
    data class LaunchApp(val app: String) : UdnIntent()
    object Lock : UdnIntent()
    object StopListening : UdnIntent()
    data class Volume(val level: Int?, val delta: Int?) : UdnIntent()
    data class Brightness(val level: Int?, val delta: Int?) : UdnIntent()
    data class Flashlight(val on: Boolean) : UdnIntent()
    object Location : UdnIntent()
    object OpenCamera : UdnIntent()
    data class Notify(val text: String) : UdnIntent()
    object Disconnect : UdnIntent()
    data class Routine(val name: String) : UdnIntent()
    /** A text message from a parent to the target device(s); stored in the network and shown as a notification. */
    data class Message(val text: String) : UdnIntent()
}

data class ParsedUdn(val intent: UdnIntent, val target: UdnTarget?)

/** The minimum a device needs to expose to be matched by a spoken name. */
data class UdnDeviceRef(
    val id: String,
    val name: String,
    val manufacturer: String?,
    val model: String?,
    val platform: String,
    val isSelf: Boolean,
    val authorized: Boolean
)

sealed class UdnResolution {
    data class Single(val device: UdnDeviceRef) : UdnResolution()
    data class Many(val devices: List<UdnDeviceRef>) : UdnResolution()
    /** The named device is this one; the existing local pipeline should handle it. */
    object ThisDevice : UdnResolution()
    data class Ambiguous(val candidates: List<UdnDeviceRef>) : UdnResolution()
    data class NotFound(val phrase: String) : UdnResolution()
}

object UdnIntentParser {

    private val devanagari = listOf(
        "बैटरी" to "battery", "लॉक" to "lock", "खोलो" to "kholo", "खोल" to "khol", "दिखाओ" to "dikhao",
        "डिवाइस" to "device", "फोन" to "phone", "फ़ोन" to "phone", "ऑनलाइन" to "online", "बंद" to "band",
        "मेरे" to "mere", "मेरा" to "mera", "मेरी" to "meri", "दूसरा" to "doosra", "दूसरे" to "doosre",
        "टैबलेट" to "tablet", "लैपटॉप" to "laptop", "सभी" to "sabhi", "चलाओ" to "chalao", "पर" to "par",
        "कैमरा" to "camera", "चैटबॉक्स" to "chatbox", "बताओ" to "batao", "लोकेशन" to "location", "कहाँ" to "kahan"
    )

    fun normalize(text: String): String {
        var t = text.lowercase(Locale.ROOT)
        for ((from, to) in devanagari) t = t.replace(from, " $to ")
        t = t.replace(Regex("[\"“”‘’]"), "\"").replace("’", "'")
        return t.replace(Regex("[,;!]"), " ").replace(Regex("\\s+"), " ").trim()
    }

    private val wake = Regex("^(hey |ok |okay )?(jarvis|jarvis,)\\s+")
    private val stopWords = "and|please|now|then|for|with|that|ki|ka|ke|par|pe|se|ko|me|mein|is|are|hai|ho|online|offline|battery|kya|kitni|kitna|to|if|so|right"
    private val possessive = Regex("\\b(?:my|mere|meri|mera|apne|apni|apna)\\s+([a-z0-9][a-z0-9 \\-]{1,28}?)(?=\\s+(?:$stopWords)\\b|[?.]|$)")
    private val allTarget = Regex("\\b(?:all|every|each|sabhi|saare|sare)\\s+(?:of\\s+)?(?:my\\s+|the\\s+|mere\\s+)?(?:authorized\\s+|authorised\\s+|connected\\s+|jarvis\\s+)*(?:devices?|phones?|tablets?|gadgets?)\\b")
    private val otherTarget = Regex("\\b(?:other|another|second|doosra|dusra|doosre|dusre|doosri|dusri)\\s+(?:phone|device|mobile|tablet|handset|one|wala|wale)?")
    private val genericOnly = setOf("device", "devices", "connected devices", "jarvis devices", "network", "connected", "jarvis")

    /** Extracts the spoken target, or null if the sentence names none. */
    fun extractTarget(t: String): UdnTarget? {
        if (allTarget.containsMatchIn(t)) return UdnTarget.All
        if (otherTarget.containsMatchIn(t)) return UdnTarget.Other
        for (m in possessive.findAll(t)) {
            val phrase = m.groupValues[1].trim()
            if (phrase.isNotEmpty() && phrase !in genericOnly && !phrase.startsWith("connected")) return UdnTarget.Named(phrase)
        }
        return null
    }

    private fun stripTarget(t: String): String =
        t.replace(allTarget, " ").replace(otherTarget, " ")
            .replace(possessive, " ").replace(Regex("\\s+"), " ").trim()

    private fun has(t: String, pattern: String) = Regex(pattern).containsMatchIn(t)

    private val openVerb = "(?:open|launch|start|run|show|kholo|khol|chalao|chala|shuru)"

    fun parse(raw: String): ParsedUdn? {
        var t = normalize(raw).replace(wake, "").trim()
        if (t.length < 4) return null
        t = t.replace(Regex("^(can you|could you|please|will you|kindly|jarvis)\\s+"), "").trim()

        // ---- messages: "send a message to my tablet saying dinner is ready" ----
        parseMessage(raw)?.let { return it }

        val target = extractTarget(t)

        // ---- network-level requests (no target needed) ----
        if (target == null || target is UdnTarget.All) {
            val listVerb = has(t, "\\b(?:show|list|display|dikhao|dikha|batao|what are|which|konse|kaun\\s*kaun|kitne)\\b")
            if ((listVerb && has(t, "\\bdevices?\\b")) || has(t, "\\bconnected devices\\b") || has(t, "\\bdevice network\\b")) {
                if (!has(t, "\\b(?:modules?|features?)\\b")) return ParsedUdn(UdnIntent.ListDevices, null)
            }
        }
        if (target == null) return null

        val rest = stripTarget(t)

        // ---- device lifecycle ----
        if (has(t, "\\b(?:disconnect|remove|revoke|unpair|unlink|hatao|hata|nikalo)\\b") && has(t, "\\b(?:network|device|jarvis|phone|tablet|laptop)\\b")) {
            return ParsedUdn(UdnIntent.Disconnect, target)
        }
        if (has(t, "\\b(?:stop|disable|turn off|band|off)\\b") && has(t, "\\b(?:listening|listener|wake word|hey jarvis|background voice|mic)\\b")) {
            return ParsedUdn(UdnIntent.StopListening, target)
        }
        if (has(t, "\\block\\b") && !has(t, "\\bunlock\\b")) return ParsedUdn(UdnIntent.Lock, target)

        // ---- routines ----
        Regex("\\b(?:run|execute|start|chalao|chala)\\s+(?:my\\s+|the\\s+|mera\\s+)?(.{2,30}?)\\s+routine\\b").find(t)?.let {
            return ParsedUdn(UdnIntent.Routine(it.groupValues[1].trim()), target)
        }

        // ---- settings ----
        if (has(t, "\\b(?:flashlight|torch)\\b")) {
            val off = has(t, "\\b(?:off|band|disable|turn off)\\b")
            return ParsedUdn(UdnIntent.Flashlight(!off), target)
        }
        if (has(t, "\\bvolume\\b")) return ParsedUdn(percentIntent(t, volume = true), target)
        if (has(t, "\\bbrightness\\b")) return ParsedUdn(percentIntent(t, volume = false), target)

        // ---- notifications ----
        if (has(t, "\\b(?:notification|notify|alert|remind)\\b")) {
            Regex("(?:saying|says?|message|text|:)\\s*\"?(.{1,200}?)\"?\\s+(?:to|on|for|par)\\b").find(raw.lowercase(Locale.ROOT).let { normalize(raw) })?.let {
                return ParsedUdn(UdnIntent.Notify(it.groupValues[1].trim()), target)
            }
        }

        // ---- queries ----
        if (has(t, "\\bwhere\\b.*\\b(?:is|my)\\b|\\blocation\\b|\\blocate\\b|\\bkahan\\b|\\bfind my\\b")) return ParsedUdn(UdnIntent.Location, target)
        if (has(t, "\\b(?:battery|charge|charging)\\b")) return ParsedUdn(UdnIntent.Query(UdnQuery.BATTERY), target)
        if (has(t, "\\b(?:wifi|wi-fi|internet|connectivity|mobile data|signal)\\b")) return ParsedUdn(UdnIntent.Query(UdnQuery.CONNECTIVITY), target)
        if (has(t, "\\b(?:online|offline|reachable|available|awake)\\b") && !has(t, "\\b(?:modules?|features?)\\b")) {
            return ParsedUdn(UdnIntent.Query(UdnQuery.ONLINE), target)
        }
        if (has(t, "\\b(?:info|information|details|specs|model|android version)\\b") && !has(t, "\\b$openVerb\\b")) {
            return ParsedUdn(UdnIntent.Query(UdnQuery.INFO), target)
        }

        // ---- modules, camera, apps ----
        if (has(t, "\\b(?:modules?|features?|functions?|capabilities)\\b")) return ParsedUdn(UdnIntent.ListModules, target)
        if (has(t, "\\b$openVerb\\b")) {
            if (has(rest, "\\bcamera\\b") && !has(rest, "\\bscan")) return ParsedUdn(UdnIntent.OpenCamera, target)
            val androidSettings = has(rest, "\\b(?:android|system|phone|wifi|bluetooth)\\s+settings?\\b")
            val module = if (androidSettings) null else UdnModules.fromSpoken(rest)
            if (module != null) return ParsedUdn(UdnIntent.OpenModule(module.id), target)
            val app = Regex("\\b$openVerb\\s+(?:the\\s+|an?\\s+)?(.{2,40}?)(?:\\s+(?:app|application))?(?:\\s+(?:on|in|par|pe)\\b|$)").find(rest.ifBlank { t })
                ?.groupValues?.get(1)?.trim()
                ?: rest.replace(Regex("^$openVerb\\s+"), "").replace(Regex("\\b(?:on|in|par|pe)$"), "").trim()
            if (app.isNotBlank() && app.length <= 40) return ParsedUdn(UdnIntent.LaunchApp(app), target)
        }
        return null
    }

    private val messageBodies = listOf(
        Regex("(?i)\\b(?:message|msg|text|sandesh)\\b[^:]{0,60}?(?:\\bsaying\\b|\\bthat says\\b|\\bsays\\b|:)\\s*(.+)$"),
        Regex("(?i)\\b(?:message|msg|sandesh)\\s+(?:bhejo|bhej\\s*do|bhejna|bhejiye|likho)\\s+(?:ki\\s+)?(.+)$"),
        Regex("(?i)\\btell\\s+(?:my|the|mere)\\s+.{1,30}?\\s+(?:that|to)\\s+(.+)$"),
        Regex("(?i)\\b(?:bolo|bol\\s*do|batao)\\s+(?:ki\\s+)(.+)$")
    )

    /** The message body keeps its original capitalisation; the target is read only from the words before it. */
    private fun parseMessage(raw: String): ParsedUdn? {
        val r = raw.trim().replace(Regex("(?i)^(?:hey |ok |okay )?jarvis[, ]+"), "")
        for (rx in messageBodies) {
            val m = rx.find(r) ?: continue
            val body = m.groupValues[1].trim().trim('"', '\u201c', '\u201d').trim()
            if (body.isEmpty()) continue
            val target = extractTarget(normalize(r.substring(0, m.groups[1]!!.range.first))) ?: continue
            return ParsedUdn(UdnIntent.Message(body.take(500)), target)
        }
        return null
    }

    private fun percentIntent(t: String, volume: Boolean): UdnIntent {
        val number = Regex("\\b(\\d{1,3})\\s*(?:%|percent|pratishat)?").find(t)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 100)
        val mute = volume && has(t, "\\b(?:mute|silent|zero)\\b")
        val delta = when {
            number != null -> null
            has(t, "\\b(?:up|increase|raise|louder|badha|badhao|zyada|more|higher)\\b") -> 15
            has(t, "\\b(?:down|decrease|lower|reduce|quieter|kam|kum|less|dim)\\b") -> -15
            else -> null
        }
        val level = if (mute) 0 else number
        return if (volume) UdnIntent.Volume(level, delta) else UdnIntent.Brightness(level, delta)
    }

    // ------------------------------------------------------------------ target resolution

    private val platformWords = mapOf(
        "windows" to setOf("laptop", "pc", "computer", "desktop", "windows", "notebook"),
        "android" to setOf("phone", "mobile", "smartphone", "android", "handset")
    )
    private val tabletWords = setOf("tablet", "pad", "tab")
    private val otherWords = setOf("old", "new", "main", "second", "spare", "backup")

    private fun tokens(s: String?): Set<String> =
        s.orEmpty().lowercase(Locale.ROOT).split(Regex("[^a-z0-9]+")).filter { it.length >= 2 }.toSet()

    private fun aliasesOf(d: UdnDeviceRef): Set<String> {
        val a = HashSet<String>()
        a += tokens(d.name); a += tokens(d.manufacturer); a += tokens(d.model)
        a += platformWords[d.platform].orEmpty()
        val tabletish = (tokens(d.name) + tokens(d.model)).any { it in tabletWords || it.endsWith("pad") || it.startsWith("tab") }
        if (tabletish) { a += tabletWords; a -= setOf("phone", "mobile", "smartphone", "handset") }
        return a
    }

    fun resolve(target: UdnTarget, devices: List<UdnDeviceRef>): UdnResolution {
        val others = devices.filter { !it.isSelf && it.authorized }
        return when (target) {
            UdnTarget.All -> if (others.isEmpty()) UdnResolution.NotFound("other devices") else UdnResolution.Many(others)
            UdnTarget.Other -> when (others.size) {
                0 -> UdnResolution.NotFound("another device")
                1 -> UdnResolution.Single(others.first())
                else -> UdnResolution.Ambiguous(others)
            }
            is UdnTarget.Named -> {
                val words = tokens(target.phrase) - otherWords - setOf("my", "the", "device", "devices")
                if (words.isEmpty()) return resolve(UdnTarget.Other, devices)
                val scored = devices.filter { it.authorized }.map { d ->
                    val al = aliasesOf(d)
                    d to words.count { w -> w in al || al.any { it.length >= 4 && w.length >= 4 && (it.startsWith(w) || w.startsWith(it)) } }
                }.filter { it.second > 0 }
                if (scored.isEmpty()) return UdnResolution.NotFound(target.phrase)
                val best = scored.maxOf { it.second }
                val top = scored.filter { it.second == best }.map { it.first }
                val remote = top.filter { !it.isSelf }
                when {
                    top.size == 1 && top.first().isSelf -> UdnResolution.ThisDevice
                    remote.size == 1 && (top.size == 1 || top.none { it.isSelf }) -> UdnResolution.Single(remote.first())
                    remote.isEmpty() -> UdnResolution.ThisDevice
                    else -> UdnResolution.Ambiguous(remote)
                }
            }
        }
    }

    // ------------------------------------------------------------------ conversational answers

    private val yes = Regex("^(?:yes|yeah|yep|yup|sure|confirm|confirmed|do it|go ahead|proceed|ok|okay|haan|han|ha|haa|kar do|karo|theek hai|thik hai|bilkul)\\b")
    private val no = Regex("^(?:no|nope|nah|cancel|stop|don't|do not|nahi|nahin|mat karo|rehne do|ruko)\\b")

    fun readYesNo(raw: String): Boolean? {
        val t = normalize(raw).replace(wake, "")
        return when {
            yes.containsMatchIn(t) -> true
            no.containsMatchIn(t) -> false
            else -> null
        }
    }

    /** Picks one of [candidates] from an answer such as "the Redmi Pad", "first", "second one", "pehla". */
    fun pickCandidate(raw: String, candidates: List<UdnDeviceRef>): UdnDeviceRef? {
        val t = normalize(raw)
        val whole = t.removePrefix("the ").removeSuffix(" one").trim()
        val spelled = listOf("one", "two", "three").indexOf(whole)
        if (spelled in candidates.indices) return candidates[spelled]
        val ordinals = listOf(
            listOf("first", "1st", "pehla", "pehle", "1"), listOf("second", "2nd", "doosra", "dusra", "2"),
            listOf("third", "3rd", "teesra", "3")
        )
        for ((i, words) in ordinals.withIndex()) {
            if (i < candidates.size && words.any { Regex("(^|\\W)" + Regex.escape(it) + "(\\W|$)").containsMatchIn(t) }) return candidates[i]
        }
        val r = resolve(UdnTarget.Named(t), candidates.map { it.copy(isSelf = false, authorized = true) })
        return (r as? UdnResolution.Single)?.device
    }
}
