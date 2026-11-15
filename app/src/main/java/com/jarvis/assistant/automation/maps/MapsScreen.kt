package com.jarvis.assistant.automation.maps

import java.util.Locale

/** What the Google Maps screen shows while guiding. Read from the texts on screen; every field may be missing. */
data class NavSnapshot(
    val navigating: Boolean,
    val etaMinutes: Int?,
    val remaining: String?,
    val arrival: String?,
    val nextInstruction: String?
) {
    fun describe(): String {
        if (!navigating) return "Google Maps is not showing active navigation."
        val parts = ArrayList<String>()
        etaMinutes?.let { parts += if (it >= 60) "${it / 60} h ${it % 60} min left" else "$it min left" }
        remaining?.let { parts += "$it to go" }
        arrival?.let { parts += "arriving around $it" }
        nextInstruction?.let { parts += "next: $it" }
        return "Navigating: " + parts.joinToString(", ") + "."
    }
}

object MapsScreen {
    private val CONTROLS = setOf("exit", "end", "overview", "re-centre", "re-center", "recentre", "recenter", "resume", "mute", "unmute", "sound and voice")
    private val HOURS_MIN = Regex("(\\d+)\\s*(?:h|hr|hrs|hour|hours)\\s*(\\d+)\\s*(?:min|mins|minutes?)", RegexOption.IGNORE_CASE)
    private val HOURS_ONLY = Regex("(\\d+)\\s*(?:h|hr|hrs|hour|hours)\\b", RegexOption.IGNORE_CASE)
    private val MINUTES = Regex("(\\d+)\\s*(?:min|mins|minutes?)\\b", RegexOption.IGNORE_CASE)
    private val DISTANCE = Regex("(\\d+(?:\\.\\d+)?)\\s*(km|mi|m|ft)\\b", RegexOption.IGNORE_CASE)
    private val CLOCK = Regex("\\b(\\d{1,2}:\\d{2})\\s*(am|pm)?", RegexOption.IGNORE_CASE)
    private val INSTRUCTION = Regex("\\b(turn|head|continue|keep|merge|take|roundabout|u-turn|arrive|destination|exit onto|slight|sharp|ramp)\\b", RegexOption.IGNORE_CASE)

    fun parse(texts: List<String>): NavSnapshot {
        val clean = texts.map { it.trim() }.filter { it.isNotEmpty() }
        var eta: Int? = null
        var remaining: String? = null
        var arrival: String? = null
        var instruction: String? = null
        for (t in clean) {
            if (eta == null) {
                HOURS_MIN.find(t)?.let { eta = it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }
                    ?: HOURS_ONLY.find(t)?.let { eta = it.groupValues[1].toInt() * 60 }
                    ?: MINUTES.find(t)?.let { eta = it.groupValues[1].toInt() }
            }
            // Only the bottom bar ("4.2 km · 10:52 am") gives the distance to the end; a lone "200 m" is the next turn.
            if (remaining == null && t.length <= 40 && (CLOCK.containsMatchIn(t) || t.contains('·') || t.contains('•'))) DISTANCE.find(t)?.let { remaining = it.groupValues[1] + " " + it.groupValues[2].lowercase(Locale.ROOT) }
            if (arrival == null && t.length <= 40) CLOCK.find(t)?.let { arrival = (it.groupValues[1] + " " + it.groupValues[2]).trim().uppercase(Locale.ROOT) }
            if (instruction == null && t.length in 6..80 && INSTRUCTION.containsMatchIn(t)) instruction = t
        }
        val hasControl = clean.any { it.lowercase(Locale.ROOT) in CONTROLS }
        return NavSnapshot(hasControl && eta != null, eta, remaining, arrival, instruction)
    }
}
