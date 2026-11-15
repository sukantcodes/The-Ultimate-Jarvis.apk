package com.jarvis.assistant.automation.maps

/** One stop or destination: what to show the user, and what to hand to Google Maps. */
data class MapsTarget(val label: String, val query: String)

/**
 * The trip Jarvis started, kept so it can be changed while it is running (add a stop, avoid tolls, switch mode,
 * go somewhere else). Google Maps offers no way to ask what trip it is on, so this is Jarvis's own record.
 */
class MapsSession {
    var destination: MapsTarget? = null
        private set
    var stops: List<MapsTarget> = emptyList()
        private set
    var mode: MapsLinks.Mode = MapsLinks.Mode.DRIVING
        private set
    var avoid: Set<MapsLinks.Avoid> = emptySet()
        private set
    var candidates: List<MapsPlace> = emptyList()
        private set

    val active: Boolean get() = destination != null

    @Synchronized fun remember(candidates: List<MapsPlace>) { this.candidates = candidates }

    @Synchronized fun start(destination: MapsTarget, stops: List<MapsTarget>, mode: MapsLinks.Mode, avoid: Set<MapsLinks.Avoid>) {
        this.destination = destination; this.stops = stops; this.mode = mode; this.avoid = avoid
    }

    @Synchronized fun end() { destination = null; stops = emptyList(); avoid = emptySet() }

    @Synchronized fun addStop(stop: MapsTarget) { stops = stops + stop }

    @Synchronized fun removeStop(text: String): Boolean {
        val t = text.trim().lowercase()
        val hit = stops.firstOrNull { it.label.lowercase().contains(t) || it.query.lowercase().contains(t) } ?: return false
        stops = stops - hit
        return true
    }

    @Synchronized fun summary(): String {
        val d = destination ?: return "No trip started by Jarvis."
        val via = if (stops.isEmpty()) "" else ", via " + stops.joinToString(" then ") { it.label }
        val av = if (avoid.isEmpty()) "" else ", avoiding " + avoid.joinToString(" and ") { it.key }
        return "To ${d.label}$via by ${mode.key.replace('_', ' ')}$av."
    }
}
