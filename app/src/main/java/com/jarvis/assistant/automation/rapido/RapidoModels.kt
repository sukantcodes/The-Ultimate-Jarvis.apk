package com.jarvis.assistant.automation.rapido

import java.security.MessageDigest
import java.util.Locale

/*
 * Plain data types for Rapido App Mastery. Nothing in this package's "pure" files touches Android classes,
 * so parsing, ranking, safety and flow rules can be unit-tested on a normal JVM (same approach as the Amazon module).
 */

/** One accessibility node reduced to plain data. */
data class RapidoNode(
    val text: String = "",
    val description: String = "",
    val viewId: String = "",
    val className: String = "",
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val selected: Boolean = false,
    val checked: Boolean = false,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0
) {
    /** What a person would read for this node. */
    val label: String get() = (if (text.isNotBlank()) text else description).trim()

    /** Text and description together, lower-cased, for keyword checks. */
    val haystack: String get() = (text + " " + description).trim().lowercase(Locale.ROOT)

    val centerY: Int get() = (top + bottom) / 2
    val centerX: Int get() = (left + right) / 2
}

enum class RapidoScreen {
    UNKNOWN, LOADING, HOME, LOCATION_SEARCH, MAP_PICKER, RIDE_OPTIONS,
    SEARCHING_DRIVER, NO_DRIVERS, DRIVER_ASSIGNED, DRIVER_ARRIVED, ON_TRIP, TRIP_COMPLETED, CANCEL_FLOW,
    LOCATION_OFF, SIGN_IN, CHALLENGE, OFFLINE, ERROR
}

enum class VehicleCategory(val spoken: String) { BIKE("Bike taxi"), AUTO("Auto"), CAB("Cab"), OTHER("Other") }

enum class RidePriority { BALANCED, CHEAPEST, FASTEST }

/** What the user asked for, after English/Hinglish understanding. */
data class RideRequest(
    val drop: String?,
    /** null means "my current location", which is Rapido's own default pickup. */
    val pickup: String? = null,
    val stops: List<String> = emptyList(),
    val vehicle: VehicleCategory? = null,
    /** "economy", "premium" or "xl" when the user named a cab class. */
    val cabTier: String? = null,
    val priority: RidePriority = RidePriority.BALANCED,
    val maxBudget: Double? = null,
    /** Raw words of a requested future time ("kal subah 7 baje"); null for a ride right now. */
    val scheduledText: String? = null,
    val pickupNote: String? = null
) {
    val usesCurrentLocation: Boolean get() = pickup.isNullOrBlank()
    val scheduled: Boolean get() = !scheduledText.isNullOrBlank()
}

/** One ride type with the fare and pickup time Rapido shows for it. Only values read from the screen. */
data class FareOption(
    /** The name on the card, e.g. "Bike", "Auto", "Cab Economy". */
    val name: String,
    val category: VehicleCategory,
    val fare: Double?,
    /** Upper end when Rapido shows a range such as "₹80-₹95". */
    val fareMax: Double? = null,
    val etaMinutes: Int? = null,
    val seats: Int? = null,
    val features: List<String> = emptyList(),
    /** Visible extras such as tolls, taxes, surge or fees, exactly as worded on the screen. */
    val extras: List<String> = emptyList(),
    val available: Boolean = true,
    val selected: Boolean = false,
    val position: Int = 0
) {
    /** The price to plan with: the middle of a range, or the single price. */
    val expectedFare: Double? get() = fare?.let { f -> if (fareMax != null) (f + fareMax) / 2.0 else f }

    /** The most it could cost, for budget checks. */
    val worstCaseFare: Double? get() = fareMax ?: fare

    val key: String get() = RideText.normalize(name).take(40) + "|" + (fare?.toLong() ?: -1L)
}

/** Everything the user must hear before a ride is booked. */
data class RideSummary(
    val pickup: String?,
    val drop: String?,
    val stops: List<String> = emptyList(),
    val optionName: String?,
    val category: VehicleCategory?,
    val fare: Double?,
    val fareMax: Double? = null,
    val etaMinutes: Int? = null,
    val payment: String? = null,
    val extras: List<String> = emptyList()
) {
    /** A summary is only safe to confirm when both places, the ride type and a fare were read. */
    val readable: Boolean get() =
        !pickup.isNullOrBlank() && !drop.isNullOrBlank() && !optionName.isNullOrBlank() && fare != null

    /**
     * Fingerprint of the things the user agreed to: pickup, drop, stops, ride type, fare (and range) and payment line.
     * The pickup-to-arrival time is left out on purpose, because it changes every few seconds.
     * Only a hash is kept, so addresses are never stored.
     */
    fun fingerprint(): String {
        val raw = listOf(
            RideText.normalize(pickup.orEmpty()).take(60),
            RideText.normalize(drop.orEmpty()).take(60),
            stops.joinToString(";") { RideText.normalize(it).take(40) },
            RideText.normalize(optionName.orEmpty()).take(40),
            fare?.let { String.format(Locale.US, "%.2f", it) }.orEmpty(),
            fareMax?.let { String.format(Locale.US, "%.2f", it) }.orEmpty(),
            RideText.normalize(payment.orEmpty()).take(40)
        ).joinToString("#")
        return RideText.sha(raw)
    }
}

enum class RideStage { NONE, SEARCHING, DRIVER_ASSIGNED, DRIVER_ARRIVED, ON_TRIP, COMPLETED, CANCELLED, NO_DRIVERS, UNKNOWN }

/** What the app shows about the captain. Every field is null when it is not visible. The ride-start OTP is never read. */
data class DriverInfo(
    val name: String? = null,
    val vehicleNumber: String? = null,
    val vehicleModel: String? = null,
    val rating: Double? = null,
    val etaText: String? = null
) {
    val any: Boolean get() = name != null || vehicleNumber != null || vehicleModel != null || rating != null || etaText != null
}

object RapidoMoney {
    private fun one(value: Double): String =
        if (value % 1.0 == 0.0) "₹" + String.format(Locale.US, "%,.0f", value)
        else "₹" + String.format(Locale.US, "%,.2f", value)

    /** "₹89", "₹80-₹95", or "fare not visible". */
    fun format(fare: Double?, fareMax: Double? = null): String = when {
        fare == null -> "fare not visible"
        fareMax != null && fareMax > fare -> one(fare) + "-" + one(fareMax)
        else -> one(fare)
    }
}

/** Loose text comparison, because Rapido shortens place names differently on every screen. */
object RideText {
    private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")

    fun normalize(s: String): String = s.lowercase(Locale.ROOT).replace(NON_ALNUM, " ").trim()

    fun tokens(s: String): Set<String> =
        normalize(s).split(' ').filter { it.length >= 2 }.toSet()

    /** Share of the shorter text's words that appear in the other (0..1). A cut-short label still scores high. */
    fun containment(a: String, b: String): Double {
        val ta = tokens(a)
        val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        return ta.intersect(tb).size.toDouble() / minOf(ta.size, tb.size)
    }

    fun sha(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(24)
    }
}
