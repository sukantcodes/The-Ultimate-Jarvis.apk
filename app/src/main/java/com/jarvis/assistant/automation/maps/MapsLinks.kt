package com.jarvis.assistant.automation.maps

import java.net.URLEncoder
import java.util.Locale

/** Builds the links that make Google Maps do things. Pure text work, so it can be tested without a phone. */
object MapsLinks {
    const val PACKAGE = "com.google.android.apps.maps"

    enum class Mode(val key: String, val navCode: String?, val urlMode: String) {
        DRIVING("driving", "d", "driving"),
        WALKING("walking", "w", "walking"),
        BICYCLING("bicycling", "b", "bicycling"),
        TWO_WHEELER("two_wheeler", "l", "two-wheeler"),
        TRANSIT("transit", null, "transit")
    }

    /** Null when the words name no travel mode at all; blank means the default, driving. */
    fun mode(text: String?): Mode? {
        val t = text?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (t.isEmpty()) return Mode.DRIVING
        return when (t.replace('-', ' ').replace('_', ' ')) {
            "driving", "drive", "car", "cab", "auto", "taxi" -> Mode.DRIVING
            "walking", "walk", "foot", "on foot" -> Mode.WALKING
            "cycle", "cycling", "bicycle", "bicycling" -> Mode.BICYCLING
            "bike", "motorbike", "motorcycle", "scooter", "two wheeler", "twowheeler", "moped" -> Mode.TWO_WHEELER
            "transit", "bus", "train", "metro", "public transport" -> Mode.TRANSIT
            else -> null
        }
    }

    enum class Avoid(val key: String, val navCode: String, val urlWord: String) {
        TOLLS("tolls", "t", "tolls"), HIGHWAYS("highways", "h", "highways"), FERRIES("ferries", "f", "ferries")
    }

    /** Understands "toll", "tolls", "highway", "express way", "ferry". Unknown words are returned so they can be reported. */
    fun avoid(words: List<String>): Pair<Set<Avoid>, List<String>> {
        val found = LinkedHashSet<Avoid>()
        val unknown = ArrayList<String>()
        words.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.forEach { w ->
            when {
                w.startsWith("toll") -> found += Avoid.TOLLS
                w.startsWith("highway") || w.startsWith("express") || w == "freeway" || w == "motorway" -> found += Avoid.HIGHWAYS
                w.startsWith("ferr") -> found += Avoid.FERRIES
                else -> unknown += w
            }
        }
        return found to unknown
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun coords(lat: Double, lon: Double): String = String.format(Locale.ROOT, "%.6f,%.6f", lat, lon)

    /** Starts turn-by-turn guidance at once. Not available for public transport. */
    fun navigationUri(destination: String, mode: Mode, avoid: Set<Avoid>): String? {
        val code = mode.navCode ?: return null
        val sb = StringBuilder("google.navigation:q=").append(enc(destination)).append("&mode=").append(code)
        if (avoid.isNotEmpty()) sb.append("&avoid=").append(avoid.joinToString("") { it.navCode })
        return sb.toString()
    }

    /**
     * The directions link. With [navigate] and no origin, Maps starts guidance at once; with an origin that is not
     * where the phone is, Maps can only show the route, so [navigate] has no effect there.
     */
    fun directionsUrl(origin: String?, destination: String, waypoints: List<String>, mode: Mode, avoid: Set<Avoid>, navigate: Boolean): String {
        val sb = StringBuilder("https://www.google.com/maps/dir/?api=1&destination=").append(enc(destination))
        if (!origin.isNullOrBlank()) sb.append("&origin=").append(enc(origin))
        if (waypoints.isNotEmpty()) sb.append("&waypoints=").append(waypoints.joinToString("%7C") { enc(it) })
        sb.append("&travelmode=").append(mode.urlMode)
        if (avoid.isNotEmpty()) sb.append("&avoid=").append(avoid.joinToString("%7C") { it.urlWord })
        if (navigate && origin.isNullOrBlank()) sb.append("&dir_action=navigate")
        return sb.toString()
    }

    fun searchUri(query: String, lat: Double?, lon: Double?): String =
        "geo:" + (if (lat != null && lon != null) coords(lat, lon) else "0,0") + "?q=" + enc(query)

    fun isCurrentLocation(text: String?): Boolean {
        val t = text?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return t.isEmpty() || t in setOf("current", "current location", "my location", "here", "me", "my current location", "where i am")
    }
}
