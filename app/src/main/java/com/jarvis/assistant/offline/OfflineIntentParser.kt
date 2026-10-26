package com.jarvis.assistant.offline

import java.util.Locale

/**
 * A phone action the offline brain can run instantly, without asking the language model.
 *
 * The 1.7B local model is far too slow (and too unreliable) to decide *whether* a spoken sentence is a
 * phone command, so the common commands are recognised here with plain pattern matching. This is what
 * makes offline commands feel instant: no model load, no token generation, just a regex and the action.
 */
sealed class OfflineIntent {
    object LockScreen : OfflineIntent()
    object UnlockScreen : OfflineIntent()
    object GoStandby : OfflineIntent()
    object StopSpeaking : OfflineIntent()
    object TellTime : OfflineIntent()
    object TellDate : OfflineIntent()
    object TellBattery : OfflineIntent()

    /** [action] is one of: pause, play, next, previous. */
    data class Media(val action: String) : OfflineIntent()

    /** [gesture] is one of: home, back, recents, notifications, quick_settings, screenshot. */
    data class Navigate(val gesture: String) : OfflineIntent()

    data class Scroll(val direction: String, val distance: String, val repeat: Int = 1) : OfflineIntent()
    data class Tap(val label: String) : OfflineIntent()
    data class TypeText(val text: String) : OfflineIntent()
    /** [explicit] is true for "open/launch X" (always answer), false for looser verbs like "go to X" (fall back to chat if no such app). */
    data class OpenApp(val name: String, val explicit: Boolean = true) : OfflineIntent()

    /** [toggle] is null for "open the page", true/false when the user asked to turn the feature on/off. */
    data class OpenSettings(val page: String, val toggle: Boolean? = null) : OfflineIntent()

    data class SetTimer(val seconds: Int) : OfflineIntent()

    /** [ambiguous] is true when the user gave no am/pm, so the executor must pick the next occurrence. */
    data class SetAlarm(val hour: Int, val minute: Int, val ambiguous: Boolean) : OfflineIntent()

    data class DoNotDisturb(val on: Boolean) : OfflineIntent()
    data class BatterySaver(val on: Boolean) : OfflineIntent()
    data class CallContact(val target: String) : OfflineIntent()

    /** [action] is one of: end, speaker_on, speaker_off, mute, unmute, hold, resume. */
    data class CallControl(val action: String) : OfflineIntent()

    data class SendSms(val contact: String, val body: String) : OfflineIntent()
}

/** Converts spoken English number words ("twenty five", "one hundred") and digit strings to numbers. */
object NumberWords {
    private val units = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
        "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
        "eighteen" to 18, "nineteen" to 19
    )
    private val tens = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fourty" to 40, "fifty" to 50,
        "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90
    )
    private val digitWords = mapOf(
        "zero" to '0', "oh" to '0', "o" to '0', "one" to '1', "two" to '2', "three" to '3', "four" to '4',
        "five" to '5', "six" to '6', "seven" to '7', "eight" to '8', "nine" to '9'
    )

    /** "twenty five" -> 25, "7" -> 7, "a hundred" -> 100. Returns null if the phrase is not a number. */
    fun parse(phrase: String): Int? {
        val p = phrase.trim().lowercase(Locale.ROOT)
        if (p.isEmpty()) return null
        if (p.all { it.isDigit() } && p.length <= 6) return p.toIntOrNull()
        val tokens = p.split(Regex("[\\s-]+")).filter { it.isNotEmpty() && it != "and" && it != "a" && it != "an" }
        if (tokens.isEmpty()) return null
        var total = 0
        var current = 0
        for (token in tokens) {
            val unit = units[token]
            val ten = tens[token]
            when {
                unit != null -> current += unit
                ten != null -> current += ten
                token == "hundred" -> current = (if (current == 0) 1 else current) * 100
                token == "thousand" -> {
                    total += (if (current == 0) 1 else current) * 1000
                    current = 0
                }
                else -> return null
            }
        }
        return total + current
    }

    /** "nine eight seven six" -> "9876", "9 8 7" -> "987". Returns null unless every token is a digit. */
    fun spokenDigits(phrase: String): String? {
        val tokens = phrase.trim().lowercase(Locale.ROOT).split(Regex("[\\s-]+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        val sb = StringBuilder()
        for (token in tokens) {
            if (token.all { it.isDigit() || it == '+' }) {
                sb.append(token)
            } else {
                sb.append(digitWords[token] ?: return null)
            }
        }
        return sb.toString().takeIf { it.isNotEmpty() }
    }
}

object OfflineIntentParser {
    // ---- Normalisation -------------------------------------------------------------------------

    private val wakePrefix = Regex("^(?:(?:hey|hi|hello|ok|okay)\\s+)?jarvis\\b\\s*")
    private val politePrefixes = listOf(
        Regex("^(?:please|kindly|just|so|then|now|hey|ok|okay)\\s+"),
        Regex("^(?:can|could|would|will) you\\s+"),
        Regex("^(?:i want you to|i need you to|i would like you to|i want to|i would like to|i wanna|go ahead and)\\s+")
    )
    private val politeSuffix = Regex("\\s+(?:please|for me|right now|now|quickly|thanks|thank you)$")

    /** Lower-cases, strips punctuation, the wake word and polite filler: "Hey Jarvis, please lock my phone." -> "lock my phone". */
    fun normalize(raw: String): String {
        var t = raw.lowercase(Locale.ROOT)
            .replace("’", "'")
            .replace(Regex("[,.!?;:\"]"), " ")
            .replace("'", "")
            .replace(Regex("\\s+"), " ")
            .trim()
        t = t.replace(Regex("\\b(what|who|where|how|that)s\\b"), "$1 is")
        t = t.replace(Regex("\\s+"), " ").trim()
        t = t.replace(wakePrefix, "").trim()
        var changed = true
        while (changed) {
            changed = false
            for (prefix in politePrefixes) {
                val stripped = t.replace(prefix, "")
                if (stripped != t) {
                    t = stripped.trim()
                    changed = true
                }
            }
        }
        var suffixChanged = true
        while (suffixChanged) {
            val stripped = t.replace(politeSuffix, "")
            suffixChanged = stripped != t
            t = stripped.trim()
        }
        return t
    }

    /** True when the utterance is nothing but the wake phrase ("hey jarvis"), so it must not be treated as a question. */
    fun isOnlyWakePhrase(raw: String): Boolean = normalize(raw).isEmpty()

    // ---- Parsing -------------------------------------------------------------------------------

    fun parse(raw: String): OfflineIntent? {
        val t = normalize(raw)
        if (t.isEmpty()) return null
        return parseStandby(t)
            ?: parseStop(t)
            ?: parseLock(t)
            ?: parseInfo(t)
            ?: parseMedia(t)
            ?: parseNavigation(t)
            ?: parseScroll(t)
            ?: parseCallControl(t)
            ?: parsePower(t)
            ?: parseSettings(t)
            ?: parseTimerOrAlarm(t)
            ?: parseSms(t)
            ?: parseCall(t)
            ?: parseTypeOrTap(t)
            ?: parseOpenApp(t)
    }

    private val standbyRegex = Regex(
        "^(?:go to sleep|go to standby|go on standby|standby|stand by|sleep|sleep mode|go to background|" +
            "go in the background|go quiet|thats all|that is all|goodbye|good bye|bye|bye bye|good night)$"
    )

    private fun parseStandby(t: String): OfflineIntent? =
        if (standbyRegex.matches(t)) OfflineIntent.GoStandby else null

    private val stopRegex = Regex(
        "^(?:stop|stop talking|stop speaking|stop it|be quiet|quiet|silence|shut up|enough|that is enough|" +
            "never mind|nevermind|cancel|cancel that)$"
    )

    private fun parseStop(t: String): OfflineIntent? =
        if (stopRegex.matches(t)) OfflineIntent.StopSpeaking else null

    private val lockRegex = Regex(
        "^(?:lock(?: the| my)?(?: screen| phone| device| display| mobile)?|lock it|lock up|screen lock|lock screen|" +
            "(?:turn|switch) off(?: the| my)? (?:screen|display))$"
    )
    private val unlockRegex = Regex("^unlock(?: the| my)?(?: screen| phone| device| display| mobile)?$")

    private fun parseLock(t: String): OfflineIntent? = when {
        unlockRegex.matches(t) -> OfflineIntent.UnlockScreen
        lockRegex.matches(t) -> OfflineIntent.LockScreen
        else -> null
    }

    private val timeRegex = Regex(
        "^(?:what time is it|what time is it now|what is the time|what is the time now|what is the current time|" +
            "tell me the time|tell me what time it is|current time|the time|time|do you know the time|time check)$"
    )
    private val dateRegex = Regex(
        "^(?:what is the date|what is the date today|what is todays date|what is today|what is the day today|" +
            "what day is it|what day is it today|what day is today|which day is it|which day is today|" +
            "todays date|tell me the date|tell me todays date|the date|date)$"
    )
    private val batteryRegex = Regex(
        "^(?:what is (?:the |my )?battery(?: level| percentage| percent| status| charge)?|" +
            "what is (?:the |my )?(?:charge|charging) (?:level|status)|" +
            "how much battery(?: do i have| is left| is there| is remaining| left| remaining| do we have)?|" +
            "how much charge(?: is left| do i have| left)?|" +
            "battery(?: level| percentage| percent| status| left| check)?|" +
            "check (?:the |my )?battery(?: level| status)?|" +
            "is (?:my|the) (?:phone|battery) charging|tell me (?:the |my )?battery(?: level| status| percentage)?)$"
    )

    private fun parseInfo(t: String): OfflineIntent? = when {
        timeRegex.matches(t) -> OfflineIntent.TellTime
        dateRegex.matches(t) -> OfflineIntent.TellDate
        batteryRegex.matches(t) -> OfflineIntent.TellBattery
        else -> null
    }

    private val pauseRegex = Regex(
        "^(?:pause|pause (?:the )?(?:music|playback|song|video)|stop (?:the )?(?:music|playback|song)|stop playing)$"
    )
    private val playRegex = Regex(
        "^(?:resume|resume (?:the )?(?:music|playback|song)|continue (?:the )?(?:music|playing|playback)|" +
            "play (?:the )?music|start playback|play)$"
    )
    private val nextRegex = Regex("^(?:next|next (?:song|track|video|one)|skip|skip (?:this|song|track|this song)|play next)$")
    private val previousRegex = Regex("^(?:previous|previous (?:song|track|video|one)|play previous|last song)$")

    private fun parseMedia(t: String): OfflineIntent? = when {
        pauseRegex.matches(t) -> OfflineIntent.Media("pause")
        playRegex.matches(t) -> OfflineIntent.Media("play")
        nextRegex.matches(t) -> OfflineIntent.Media("next")
        previousRegex.matches(t) -> OfflineIntent.Media("previous")
        else -> null
    }

    private val homeRegex = Regex(
        "^(?:(?:press |tap |hit )?(?:the )?home(?: button| key)|(?:press|hit) home|" +
            "(?:(?:go to|show|open|take me to|back to) )?(?:the )?home ?screen)$"
    )
    private val backRegex = Regex(
        "^(?:go back|press back|(?:press |tap |hit )?(?:the )?back(?: button)?|navigate back|" +
            "go to (?:the )?previous screen)$"
    )
    private val recentsRegex = Regex(
        "^(?:(?:open|show|go to|press) )?(?:the )?(?:recent apps|recents|recent screen|recent screens|app switcher|" +
            "overview screen|recent tasks)$"
    )
    private val notificationsRegex = Regex(
        "^(?:(?:open|show|pull down|check|see|go to) )?(?:the |my )?notifications?(?: panel| shade| bar| drawer| tray)?$"
    )
    private val quickSettingsRegex = Regex("^(?:(?:open|show|pull down) )?(?:the )?quick settings(?: panel| menu)?$")
    private val screenshotRegex = Regex(
        "^(?:(?:take|capture|grab) )?(?:a |the )?screen ?shot(?: of (?:this|the) screen)?$|" +
            "^(?:take|capture|grab) (?:a )?picture of (?:the|this) screen$|^capture (?:the |this )?screen$"
    )

    private fun parseNavigation(t: String): OfflineIntent? = when {
        homeRegex.matches(t) -> OfflineIntent.Navigate("home")
        backRegex.matches(t) -> OfflineIntent.Navigate("back")
        recentsRegex.matches(t) -> OfflineIntent.Navigate("recents")
        quickSettingsRegex.matches(t) -> OfflineIntent.Navigate("quick_settings")
        notificationsRegex.matches(t) -> OfflineIntent.Navigate("notifications")
        screenshotRegex.matches(t) -> OfflineIntent.Navigate("screenshot")
        else -> null
    }

    private val scrollRegex = Regex(
        "^(?:scroll|swipe)(?: the)?(?: page| screen| list| feed| view)?(?: (up|down|left|right))?" +
            "(?: (?:to )?(?:the )?(top|bottom|end|start|beginning))?" +
            "(?: (a little bit|a little|a bit|little|slightly|a lot|a lot more|more|far))?$"
    )
    private val pageScrollRegex = Regex("^page (up|down)$")

    private fun parseScroll(t: String): OfflineIntent? {
        pageScrollRegex.matchEntire(t)?.let { return OfflineIntent.Scroll(it.groupValues[1], "large") }
        val m = scrollRegex.matchEntire(t) ?: return null
        val direction = m.groupValues[1]
        val edge = m.groupValues[2]
        val modifier = m.groupValues[3]
        if (direction.isEmpty() && edge.isEmpty()) return null
        if (edge.isNotEmpty()) {
            val dir = if (edge == "top" || edge == "start" || edge == "beginning") "up" else "down"
            return OfflineIntent.Scroll(dir, "large", repeat = 5)
        }
        val distance = when {
            modifier.contains("little") || modifier.contains("bit") || modifier == "slightly" -> "small"
            modifier.contains("lot") || modifier == "more" || modifier == "far" -> "large"
            else -> "medium"
        }
        return OfflineIntent.Scroll(direction, distance)
    }

    private val endCallRegex = Regex("^(?:hang up|(?:end|cut|disconnect)(?: the| this)? (?:call|phone call)|hang up the call)$")
    private val speakerRegex = Regex(
        "^(?:(?:turn|switch|put) )?(?:the )?(?:call )?(?:(on|off) )?(?:the )?speaker(?:phone)?(?: (on|off))?$|" +
            "^(?:put|switch) (?:it|the call) (?:on|to) speaker(?:phone)?$"
    )
    private val muteCallRegex = Regex("^(mute|unmute)(?: the| my)? (?:call|mic|microphone|myself)$")
    private val holdRegex = Regex("^(?:(put )?(?:the )?(?:call )?on hold|hold (?:the )?call|resume (?:the )?call|unhold(?: the call)?)$")

    private fun parseCallControl(t: String): OfflineIntent? {
        if (endCallRegex.matches(t)) return OfflineIntent.CallControl("end")
        if (muteCallRegex.matches(t)) {
            return OfflineIntent.CallControl(if (t.startsWith("unmute")) "unmute" else "mute")
        }
        if (holdRegex.matches(t)) {
            return OfflineIntent.CallControl(if (t.contains("resume") || t.contains("unhold")) "resume" else "hold")
        }
        val speaker = speakerRegex.matchEntire(t)
        if (speaker != null) {
            val off = t.contains("off") || t.contains("handset") || t.contains("normal")
            return OfflineIntent.CallControl(if (off) "speaker_off" else "speaker_on")
        }
        return null
    }

    private val onWords = Regex("\\b(?:on|enable|enabled|activate|start)\\b")
    private val offWords = Regex("\\b(?:off|disable|disabled|deactivate|stop)\\b")

    private fun onOff(t: String): Boolean? {
        val off = offWords.containsMatchIn(t)
        val on = onWords.containsMatchIn(t)
        return when {
            off && !on -> false
            on && !off -> true
            on && off -> {
                // "turn off ..." / "switch on ..." - whichever comes first decides.
                val onAt = onWords.find(t)!!.range.first
                val offAt = offWords.find(t)!!.range.first
                onAt < offAt
            }
            else -> null
        }
    }

    private val dndRegex = Regex(
        "^(?:(?:turn|switch|put|set|enable|disable)(?: on| off)?(?: the| my)? )?(?:do not disturb|dont disturb|dnd)" +
            "(?: mode)?(?: on| off)?$"
    )
    private val batterySaverRegex = Regex(
        "^(?:(?:turn|switch|put|set|enable|disable)(?: on| off)?(?: the| my)? )?" +
            "(?:battery saver|battery saving|power saving|power saver|low power)(?: mode)?(?: on| off)?$"
    )

    private fun parsePower(t: String): OfflineIntent? {
        if (dndRegex.matches(t)) return onOff(t)?.let { OfflineIntent.DoNotDisturb(it) }
        if (batterySaverRegex.matches(t)) return onOff(t)?.let { OfflineIntent.BatterySaver(it) }
        return null
    }

    // Settings pages whose name is specific enough to be understood without the word "settings".
    private val specificPages: Map<String, String> = linkedMapOf(
        "wi fi" to "wifi", "wifi" to "wifi", "wireless" to "wifi",
        "bluetooth" to "bluetooth",
        "airplane mode" to "airplane", "flight mode" to "airplane", "aeroplane mode" to "airplane",
        "nfc" to "nfc",
        "accessibility" to "accessibility",
        "developer options" to "developer",
        "mobile data" to "mobile_network", "mobile network" to "mobile_network", "hotspot" to "mobile_network",
        "mobile hotspot" to "mobile_network"
    )

    // Pages that are only a settings request when the user says "settings" (otherwise it could be an app or chat).
    private val genericPages: Map<String, String> = linkedMapOf(
        "display" to "display", "brightness" to "display", "sound" to "sound", "sounds" to "sound",
        "location" to "location", "battery" to "battery", "storage" to "storage", "security" to "security",
        "privacy" to "privacy", "language" to "language", "date and time" to "date", "apps" to "apps",
        "application" to "apps", "notification" to "notifications", "notifications" to "notifications"
    )

    private val toggleablePages: Map<String, String> = linkedMapOf(
        "wi fi" to "wifi", "wifi" to "wifi", "bluetooth" to "bluetooth",
        "airplane mode" to "airplane", "flight mode" to "airplane", "aeroplane mode" to "airplane",
        "location" to "location", "gps" to "location", "nfc" to "nfc",
        "mobile data" to "mobile_network", "mobile hotspot" to "mobile_network", "hotspot" to "mobile_network",
        "data" to "mobile_network"
    )

    private val mainSettingsRegex = Regex(
        "^(?:open|show|go to|launch|take me to) (?:the |my )?(?:(?:device|phone|android|system) )?settings(?: app| page| menu)?$"
    )

    private fun alternation(keys: Collection<String>): String =
        keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }

    private val specificPageRegex = Regex(
        "^(?:open|show|go to|launch|take me to) (?:the |my )?(?:(?:device|phone|android|system) )?" +
            "(" + alternation(specificPages.keys) + ")(?: settings| page| options| menu| screen)?$"
    )
    private val genericPageRegex = Regex(
        "^(?:open|show|go to|launch|take me to) (?:the |my )?(?:(?:device|phone|android|system) )?" +
            "(" + alternation(genericPages.keys) + ") (?:settings|options|page|menu|screen)$"
    )
    private val toggleRegexes = run {
        val pages = alternation(toggleablePages.keys)
        listOf(
            Regex("^(?:turn|switch|put|set|enable|disable)(?: on| off)?(?: the| my)? ($pages)(?: on| off)?$"),
            Regex("^($pages) (?:on|off)$")
        )
    }

    private fun parseSettings(t: String): OfflineIntent? {
        if (mainSettingsRegex.matches(t)) return OfflineIntent.OpenSettings("main")
        for (regex in toggleRegexes) {
            val m = regex.matchEntire(t) ?: continue
            val toggle = onOff(t) ?: continue
            val page = toggleablePages[m.groupValues[1]] ?: continue
            return OfflineIntent.OpenSettings(page, toggle)
        }
        specificPageRegex.matchEntire(t)?.let { m ->
            specificPages[m.groupValues[1]]?.let { return OfflineIntent.OpenSettings(it) }
        }
        genericPageRegex.matchEntire(t)?.let { m ->
            genericPages[m.groupValues[1]]?.let { return OfflineIntent.OpenSettings(it) }
        }
        return null
    }

    // ---- Timers and alarms ---------------------------------------------------------------------

    private val timerRegexes = listOf(
        Regex("^(?:set|start|create|begin)(?: a| an| the)? timer (?:for |of |to )?(.+)$"),
        Regex("^(?:set|start|create|begin)(?: a| an| the)? (.+?) timer$"),
        Regex("^timer (?:for |of )?(.+)$")
    )
    private val alarmRegexes = listOf(
        Regex("^(?:set|create|add|make)(?: an| a| the)? alarm(?: for| at| to)? (.+)$"),
        Regex("^wake me up(?: at| by)? (.+)$"),
        Regex("^alarm (?:for|at) (.+)$")
    )

    private fun parseTimerOrAlarm(t: String): OfflineIntent? {
        for (regex in timerRegexes) {
            val m = regex.matchEntire(t) ?: continue
            val seconds = parseDurationSeconds(m.groupValues[1]) ?: continue
            return OfflineIntent.SetTimer(seconds)
        }
        for (regex in alarmRegexes) {
            val m = regex.matchEntire(t) ?: continue
            val time = parseClockTime(m.groupValues[1]) ?: continue
            return OfflineIntent.SetAlarm(time.hour, time.minute, time.ambiguous)
        }
        return null
    }

    private val unitSeconds = mapOf(
        "hour" to 3600, "hours" to 3600, "hr" to 3600, "hrs" to 3600,
        "minute" to 60, "minutes" to 60, "min" to 60, "mins" to 60,
        "second" to 1, "seconds" to 1, "sec" to 1, "secs" to 1
    )

    /** "five minutes" -> 300, "one hour and thirty minutes" -> 5400, "half an hour" -> 1800. Null if no duration found. */
    fun parseDurationSeconds(phrase: String): Int? {
        var p = phrase.lowercase(Locale.ROOT).trim()
        p = p.replace(Regex("\\b(?:an hour and a half|one and a half hours?)\\b"), "90 minutes")
        p = p.replace(Regex("\\bhalf (?:an )?hour\\b"), "30 minutes")
        p = p.replace(Regex("\\bquarter (?:of )?(?:an )?hour\\b"), "15 minutes")
        val tokens = p.split(Regex("\\s+")).filter { it.isNotEmpty() && it != "and" }
        var total = 0L
        val number = ArrayList<String>()
        var foundUnit = false
        for (token in tokens) {
            val unit = unitSeconds[token]
            if (unit != null) {
                val amount = if (number.isEmpty() || number.all { it == "a" || it == "an" }) 1 else NumberWords.parse(number.joinToString(" ")) ?: return null
                total += amount.toLong() * unit
                number.clear()
                foundUnit = true
            } else {
                number.add(token)
            }
        }
        if (!foundUnit || number.isNotEmpty() || total <= 0L || total > 86_400L) return null
        return total.toInt()
    }

    data class ClockTime(val hour: Int, val minute: Int, val ambiguous: Boolean)

    /** "six thirty a m" -> 6:30, "half past seven" -> 7:30, "quarter to nine p m" -> 20:45. Null if not a time. */
    fun parseClockTime(expression: String): ClockTime? {
        var s = expression.lowercase(Locale.ROOT).trim()
        s = s.replace(Regex("\\b([ap])\\s+m\\b"), "$1m")
        s = s.replace(Regex("\\bo\\s*clock\\b"), " ")
        var meridiem: String? = null
        Regex("\\b(am|pm)\\b").find(s)?.let {
            meridiem = it.value
            s = s.replace(it.value, " ")
        }
        if (meridiem == null) {
            if (Regex("\\bmorning\\b").containsMatchIn(s)) meridiem = "am"
            else if (Regex("\\b(?:afternoon|evening|night|tonight)\\b").containsMatchIn(s)) meridiem = "pm"
        }
        s = s.replace(Regex("\\b(?:in the|at|this|of the)\\b"), " ")
        s = s.replace(Regex("\\b(?:morning|afternoon|evening|night|tonight)\\b"), " ")
        s = s.replace(Regex("\\s+"), " ").trim()
        if (s.isEmpty()) return null

        var hour: Int
        var minute: Int
        val relative = Regex("^(half|quarter) (past|to) (.+)$").matchEntire(s)
        if (relative != null) {
            val base = NumberWords.parse(relative.groupValues[3]) ?: return null
            val offset = if (relative.groupValues[1] == "half") 30 else 15
            if (relative.groupValues[2] == "past") {
                hour = base
                minute = offset
            } else {
                hour = base - 1
                minute = 60 - offset
            }
        } else {
            val tokens = s.split(" ")
            val whole = NumberWords.parse(s)
            val digitsOnly = s.all { it.isDigit() }
            if (digitsOnly && s.length in 3..4) {
                val n = s.toInt()
                hour = n / 100
                minute = n % 100
            } else if (whole != null && whole in 0..23) {
                hour = whole
                minute = 0
            } else {
                var found: Pair<Int, Int>? = null
                for (i in 1 until tokens.size) {
                    val h = NumberWords.parse(tokens.subList(0, i).joinToString(" ")) ?: continue
                    var minuteTokens = tokens.subList(i, tokens.size)
                    if (minuteTokens.isNotEmpty() && (minuteTokens[0] == "oh" || minuteTokens[0] == "o" || minuteTokens[0] == "zero")) {
                        minuteTokens = minuteTokens.drop(1)
                    }
                    val m = NumberWords.parse(minuteTokens.joinToString(" ")) ?: continue
                    if (h in 0..23 && m in 0..59) {
                        found = Pair(h, m)
                        break
                    }
                }
                val pair = found ?: return null
                hour = pair.first
                minute = pair.second
            }
        }
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return null

        var ambiguous = false
        when (meridiem) {
            "am" -> if (hour in 1..12) hour %= 12
            "pm" -> if (hour in 1..12) hour = (hour % 12) + 12
            else -> ambiguous = hour in 1..12
        }
        return ClockTime(hour, minute, ambiguous)
    }

    // ---- Messaging and calls -------------------------------------------------------------------

    private val otherMessengers = Regex("\\b(?:whatsapp|telegram|instagram|email|mail|gmail|facebook|messenger|signal)\\b")

    private val smsRegexes = listOf(
        Regex("^(?:send|write|compose)(?: a| an)? (?:text message|text|sms|message)(?: to| for)? (.+?) (?:saying|says|that says|and say|and tell (?:him|her|them)|with message|with the message|that) (.+)$"),
        Regex("^(?:text|sms) (?:to )?(.+?) (?:saying|says|that says|that|and say) (.+)$"),
        Regex("^(?:text|sms) (?:to )?(\\S+) (.+)$"),
        Regex("^(?:send|write|compose)(?: a| an)? (?:text message|text|sms|message) (?:to |for )?(\\S+) (.+)$")
    )

    private fun parseSms(t: String): OfflineIntent? {
        if (otherMessengers.containsMatchIn(t)) return null
        for (regex in smsRegexes) {
            val m = regex.matchEntire(t) ?: continue
            val contact = m.groupValues[1].trim()
            val body = m.groupValues[2].trim()
            if (contact.isNotEmpty() && body.isNotEmpty()) return OfflineIntent.SendSms(contact, body)
        }
        return null
    }

    private val callRegex = Regex("^(?:call|phone|dial|ring)(?: up| to)? (.+)$")
    private val callRejectTargets = setOf("me", "back", "him", "her", "them", "someone", "somebody", "anyone", "again", "later")
    private val callRejectStart = Regex("^(?:it|this|that)\\b")

    private fun parseCall(t: String): OfflineIntent? {
        if (otherMessengers.containsMatchIn(t) || t.contains("video")) return null
        val m = callRegex.matchEntire(t) ?: return null
        val target = m.groupValues[1].trim()
        if (target.isEmpty() || target in callRejectTargets || callRejectStart.containsMatchIn(target)) return null
        val digits = NumberWords.spokenDigits(target)
        return OfflineIntent.CallContact(if (digits != null && digits.length >= 3) digits else target)
    }

    // ---- Typing, tapping, opening apps ----------------------------------------------------------

    private val typeRegexes = listOf(
        Regex("^type (.+)$"),
        Regex("^(?:write|enter) (.+?) (?:in|into|on) (?:the |this )?(?:box|field|text box|search bar|search box|text field)$")
    )
    private val tapRegex = Regex("^(?:tap|click|press|touch|select|hit)(?: on)?(?: the)? (.+?)(?: button| icon| option| tab| link)?$")

    private fun parseTypeOrTap(t: String): OfflineIntent? {
        for (regex in typeRegexes) {
            val m = regex.matchEntire(t) ?: continue
            val text = m.groupValues[1].trim()
            if (text.isNotEmpty()) return OfflineIntent.TypeText(text)
        }
        val tap = tapRegex.matchEntire(t) ?: return null
        val label = tap.groupValues[1].trim()
        if (label.isEmpty() || label.split(" ").size > 4) return null
        return OfflineIntent.Tap(label)
    }

    private val openAppRegex = Regex("^(open|launch|start|run|go to|switch to|take me to)(?: the| my)? (.+?)(?: app| application)?$")
    private val notAnAppName = Regex("^(?:a|an|to|up|some|something|new|own|your)\\b")

    private fun parseOpenApp(t: String): OfflineIntent? {
        val m = openAppRegex.matchEntire(t) ?: return null
        val verb = m.groupValues[1]
        val name = m.groupValues[2].trim()
        if (name.isEmpty() || name.split(" ").size > 5 || notAnAppName.containsMatchIn(name)) return null
        return OfflineIntent.OpenApp(name, explicit = verb == "open" || verb == "launch")
    }
}
