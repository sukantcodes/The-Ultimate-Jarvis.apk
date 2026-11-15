package com.jarvis.assistant.automation.maps

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A place found by a search. Distances are straight-line from the search centre, never road distance. */
data class MapsPlace(
    val name: String,
    val lat: Double,
    val lon: Double,
    val address: String,
    val distanceM: Double?,
    val hours: String? = null,
    val phone: String? = null,
    val website: String? = null
)

/** Nearby-place search over OpenStreetMap data: query building, parsing, ranking. No network here. */
object MapsGeo {
    private const val EARTH_M = 6_371_000.0

    fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_M * asin(sqrt(a))
    }

    /** Everyday words that mean a kind of place rather than a shop name, and the OpenStreetMap tags that mark it. */
    private val CATEGORIES: List<Pair<List<String>, List<String>>> = listOf(
        listOf("petrol pump", "petrol bunk", "petrol station", "gas station", "fuel station", "petrol", "pump") to listOf("amenity=fuel"),
        listOf("atm", "cash machine") to listOf("amenity=atm"),
        listOf("hospital") to listOf("amenity=hospital"),
        listOf("clinic", "doctor") to listOf("amenity=clinic", "amenity=doctors"),
        listOf("pharmacy", "medical store", "chemist", "medicine shop") to listOf("amenity=pharmacy"),
        listOf("restaurant", "hotel for food") to listOf("amenity=restaurant"),
        listOf("cafe", "coffee shop", "coffee") to listOf("amenity=cafe"),
        listOf("bank") to listOf("amenity=bank"),
        listOf("police station", "police") to listOf("amenity=police"),
        listOf("parking", "car park") to listOf("amenity=parking"),
        listOf("ev charging", "charging station", "ev charger") to listOf("amenity=charging_station"),
        listOf("toilet", "restroom", "washroom") to listOf("amenity=toilets"),
        listOf("supermarket", "grocery", "grocery store") to listOf("shop=supermarket", "shop=convenience"),
        listOf("mall", "shopping mall") to listOf("shop=mall"),
        listOf("bakery") to listOf("shop=bakery"),
        listOf("hotel", "lodge") to listOf("tourism=hotel", "tourism=guest_house"),
        listOf("bus stop", "bus stand") to listOf("highway=bus_stop", "amenity=bus_station"),
        listOf("metro station", "railway station", "train station") to listOf("railway=station", "railway=halt"),
        listOf("airport") to listOf("aeroway=aerodrome"),
        listOf("school") to listOf("amenity=school"),
        listOf("temple", "mandir") to listOf("amenity=place_of_worship"),
        listOf("gym", "fitness centre") to listOf("leisure=fitness_centre")
    )

    private val FILLER = setOf("nearest", "nearby", "closest", "near", "me", "find", "the", "a", "an", "my", "to", "show", "search", "for", "around", "here", "open", "store", "shop", "outlet")

    /** "Find the dominos store nearest to me" -> "dominos". Keeps the words if stripping would leave nothing. */
    fun cleanQuery(raw: String): String {
        val words = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val kept = words.filter { it.lowercase(Locale.ROOT).trim('.', ',', '?', '!') !in FILLER }
        return (if (kept.isEmpty()) words else kept).joinToString(" ")
    }

    /** The tags for a kind of place, or null when the query reads like a brand or shop name. */
    fun categoryTags(query: String): List<String>? {
        val q = query.trim().lowercase(Locale.ROOT)
        return CATEGORIES.firstOrNull { (words, _) -> words.any { it == q } }?.second
    }

    /** "dominos" must also match "Domino's": every letter may be followed by an apostrophe, dot, dash or space. */
    fun fuzzyRegex(query: String): String =
        query.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }.map { it.toString() }.joinToString("['’. -]?")

    fun overpassQuery(query: String, lat: Double, lon: Double, radiusM: Int): String {
        val around = "(around:$radiusM,${MapsLinks.coords(lat, lon)})"
        val tags = categoryTags(query)
        val body = if (tags != null) {
            tags.joinToString("") { t -> val (k, v) = t.split("=", limit = 2); "nwr[\"$k\"=\"$v\"]$around;" }
        } else {
            val rx = fuzzyRegex(query)
            "nwr[\"name\"~\"$rx\",i]$around;nwr[\"brand\"~\"$rx\",i]$around;"
        }
        return "[out:json][timeout:20];($body);out center tags 60;"
    }

    private fun JsonObject.str(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }

    private fun addressOf(tags: JsonObject): String {
        val line1 = listOfNotNull(tags.str("addr:housenumber"), tags.str("addr:street")).joinToString(" ")
        val parts = listOf(line1, tags.str("addr:suburb").orEmpty(), tags.str("addr:city").orEmpty(), tags.str("addr:postcode").orEmpty()).filter { it.isNotBlank() }
        return if (parts.isEmpty()) "address not listed" else parts.joinToString(", ")
    }

    /** Reads an Overpass answer. Entries without a name or position are dropped. */
    fun parseOverpass(json: String, lat: Double, lon: Double): List<MapsPlace> {
        val root = runCatching { JsonParser.parseString(json) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyList()
        val elements = root.getAsJsonArray("elements") ?: return emptyList()
        return elements.mapNotNull { e ->
            val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val tags = o.getAsJsonObject("tags") ?: return@mapNotNull null
            val name = tags.str("name") ?: tags.str("brand") ?: return@mapNotNull null
            val (pLat, pLon) = position(o) ?: return@mapNotNull null
            MapsPlace(
                name, pLat, pLon, addressOf(tags), haversineM(lat, lon, pLat, pLon),
                tags.str("opening_hours"), tags.str("phone") ?: tags.str("contact:phone"), tags.str("website") ?: tags.str("contact:website")
            )
        }
    }

    private fun position(o: JsonObject): Pair<Double, Double>? {
        fun num(e: JsonElement?): Double? = e?.takeIf { it.isJsonPrimitive }?.runCatching { asDouble }?.getOrNull()
        val lat = num(o.get("lat")) ?: num(o.getAsJsonObject("center")?.get("lat"))
        val lon = num(o.get("lon")) ?: num(o.getAsJsonObject("center")?.get("lon"))
        return if (lat != null && lon != null) lat to lon else null
    }

    /** Reads a Nominatim search answer (used to find an area by name, and as a fallback search). */
    fun parseNominatim(json: String, fromLat: Double?, fromLon: Double?): List<MapsPlace> {
        val arr = runCatching { JsonParser.parseString(json) }.getOrNull()?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val pLat = o.str("lat")?.toDoubleOrNull() ?: return@mapNotNull null
            val pLon = o.str("lon")?.toDoubleOrNull() ?: return@mapNotNull null
            val display = o.str("display_name").orEmpty()
            val name = o.str("name") ?: display.substringBefore(",").trim().ifEmpty { return@mapNotNull null }
            val extra = o.getAsJsonObject("extratags")
            MapsPlace(
                name, pLat, pLon, display.ifEmpty { "address not listed" },
                if (fromLat != null && fromLon != null) haversineM(fromLat, fromLon, pLat, pLon) else null,
                extra?.str("opening_hours"), extra?.str("phone"), extra?.str("website")
            )
        }
    }

    /** Nearest first, dropping the same shop listed twice (a node and its building) within 60 m. */
    fun rank(places: List<MapsPlace>, limit: Int): List<MapsPlace> {
        val sorted = places.sortedBy { it.distanceM ?: Double.MAX_VALUE }
        val kept = ArrayList<MapsPlace>()
        for (p in sorted) {
            val twin = kept.any { it.name.equals(p.name, ignoreCase = true) && haversineM(it.lat, it.lon, p.lat, p.lon) < 60.0 }
            if (!twin) kept += p
            if (kept.size >= limit) break
        }
        return kept
    }

    fun distanceText(m: Double?): String = when {
        m == null -> "distance unknown"
        m < 950 -> "${(Math.round(m / 10.0) * 10).toInt()} m"
        else -> String.format(Locale.ROOT, "%.1f km", m / 1000.0)
    }
}
