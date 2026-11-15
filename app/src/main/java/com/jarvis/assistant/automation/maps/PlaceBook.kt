package com.jarvis.assistant.automation.maps

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.util.Locale

/** A place the user named for Jarvis ("home", "office", "mom's house"). Kept on the phone only. */
data class SavedPlace(
    val name: String,
    val address: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val aliases: List<String> = emptyList()
) {
    /** What Google Maps should be given: exact coordinates when known, otherwise the address, otherwise the name. */
    fun destination(): String = when {
        lat != null && lon != null -> MapsLinks.coords(lat, lon)
        !address.isNullOrBlank() -> address
        else -> name
    }
}

class PlaceBook(initial: List<SavedPlace> = emptyList(), private val persist: (List<SavedPlace>) -> Unit = {}) {
    private val items = ArrayList(initial)

    @Synchronized fun all(): List<SavedPlace> = items.toList()

    @Synchronized fun find(query: String): SavedPlace? {
        val q = norm(query)
        if (q.isEmpty()) return null
        fun names(p: SavedPlace) = (listOf(p.name) + p.aliases).map(::norm) + synonyms(norm(p.name))
        return items.firstOrNull { q in names(it) }
            ?: items.firstOrNull { p -> names(p).any { it.startsWith(q) || q.startsWith(it) && it.length >= 3 } }
            ?: items.firstOrNull { p -> names(p).any { it.contains(q) && q.length >= 3 } }
    }

    @Synchronized fun save(place: SavedPlace) {
        require(place.name.isNotBlank()) { "A place needs a name." }
        items.removeAll { norm(it.name) == norm(place.name) }
        items += place.copy(name = place.name.trim())
        persist(items.toList())
    }

    @Synchronized fun remove(name: String): Boolean {
        val hit = find(name) ?: return false
        items.remove(hit)
        persist(items.toList())
        return true
    }

    companion object {
        private fun norm(s: String) = s.trim().lowercase(Locale.ROOT).replace(Regex("[^a-z0-9\\u0900-\\u097F\\u0980-\\u09FF\\u0C80-\\u0CFF ]"), "").replace(Regex("\\s+"), " ")

        private fun synonyms(name: String): List<String> = when (name) {
            "home" -> listOf("my home", "house", "my house", "ghar")
            "work" -> listOf("office", "my office", "my work", "workplace")
            "office" -> listOf("work", "my office", "my work")
            else -> emptyList()
        }

        private val gson = Gson()

        fun toJson(list: List<SavedPlace>): String = gson.toJson(list)

        fun fromJson(json: String?): List<SavedPlace> {
            if (json.isNullOrBlank()) return emptyList()
            val arr = runCatching { JsonParser.parseString(json).asJsonArray }.getOrNull() ?: return emptyList()
            return arr.mapNotNull { e -> runCatching { gson.fromJson(e, SavedPlace::class.java) }.getOrNull() }
                .filter { !(it.name as String?).isNullOrBlank() }
                // Gson skips Kotlin defaults, so a stored place without aliases would otherwise carry a null list.
                .map { SavedPlace(it.name, it.address, it.lat, it.lon, (it.aliases as List<String>?) ?: emptyList()) }
        }
    }
}
