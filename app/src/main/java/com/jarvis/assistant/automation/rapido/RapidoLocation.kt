package com.jarvis.assistant.automation.rapido

/** What JARVIS can say about the phone's location. Coordinates are never part of this and never leave the device. */
enum class LocationAccess { PRECISE, APPROXIMATE, PERMISSION_DENIED, SERVICES_OFF, UNAVAILABLE }

data class LocationState(
    val access: LocationAccess,
    /** Accuracy radius in metres when a fix exists. */
    val accuracyMeters: Int? = null,
    /** Coarse place name (area or city) when the phone could resolve one. Never a street address or coordinates. */
    val areaLabel: String? = null
) {
    /** True when Rapido can be expected to find the pickup on its own. */
    val usable: Boolean get() = access == LocationAccess.PRECISE || access == LocationAccess.APPROXIMATE
}

object LocationClassifier {
    /** A fix within this radius is treated as precise enough for a pickup pin. */
    const val PRECISE_METERS = 100
    /** A fix worse than this is reported as unavailable instead of approximate. */
    const val USELESS_METERS = 5_000

    fun classify(
        hasFinePermission: Boolean,
        hasCoarsePermission: Boolean,
        servicesEnabled: Boolean,
        accuracyMeters: Float?,
        fixAgeMs: Long? = null,
        areaLabel: String? = null
    ): LocationState {
        if (!hasFinePermission && !hasCoarsePermission) return LocationState(LocationAccess.PERMISSION_DENIED)
        if (!servicesEnabled) return LocationState(LocationAccess.SERVICES_OFF)
        if (accuracyMeters == null || accuracyMeters <= 0f) return LocationState(LocationAccess.UNAVAILABLE)
        // A fix older than 30 minutes says nothing about where the phone is now.
        if (fixAgeMs != null && fixAgeMs > 30 * 60_000L) return LocationState(LocationAccess.UNAVAILABLE)
        val acc = accuracyMeters.toInt()
        if (acc > USELESS_METERS) return LocationState(LocationAccess.UNAVAILABLE, acc)
        // Only the coarse permission was granted: whatever the fix, it is approximate by the user's own choice.
        val precise = hasFinePermission && acc <= PRECISE_METERS
        return LocationState(if (precise) LocationAccess.PRECISE else LocationAccess.APPROXIMATE, acc, areaLabel)
    }

    /** One honest sentence for the user, and what they can do about it. */
    fun explain(state: LocationState): String = when (state.access) {
        LocationAccess.PRECISE ->
            "Location is on and precise (about ${state.accuracyMeters} m)." + (state.areaLabel?.let { " You are around $it." } ?: "")
        LocationAccess.APPROXIMATE ->
            "Location is only approximate (about ${state.accuracyMeters} m). Rapido may place the pickup pin a little off; check the pin before booking."
        LocationAccess.PERMISSION_DENIED ->
            "Jarvis does not have location permission. Allow location for Jarvis in the app settings, and make sure Rapido has it too."
        LocationAccess.SERVICES_OFF ->
            "The phone's location (GPS) is switched off. Turn it on in quick settings; Jarvis does not switch it on by itself."
        LocationAccess.UNAVAILABLE ->
            "The phone has no usable location fix right now. Move to an open area, wait a few seconds, or give me the pickup address."
    }
}
