package com.jarvis.assistant.automation.maps

import java.util.Locale

/** The few things the flows below need from the phone, so they can be tested with a pretend phone. */
interface MapsDriver {
    /** Opens a link in Google Maps. */
    suspend fun open(uri: String): Boolean
    /** Texts currently on screen inside Google Maps, or null when the screen can not be read (Accessibility off, other app in front). */
    suspend fun texts(): List<String>?
    /** Taps the first control whose text or description contains [label]. */
    suspend fun click(label: String): Boolean
    suspend fun scrollDown(): Boolean
    suspend fun pause(ms: Long)
    fun cancelled(): Boolean
}

sealed class MapsFlowResult {
    data class Started(val snapshot: NavSnapshot) : MapsFlowResult()
    data class Done(val note: String) : MapsFlowResult()
    /** The flow could not continue; [seen] is a short sample of what was on screen, so the user is told the truth. */
    data class Stuck(val step: String, val seen: String) : MapsFlowResult()
    object ScreenUnreadable : MapsFlowResult()
    object Cancelled : MapsFlowResult()
}

/**
 * Drives Google Maps' own lists (Starred places, Favorites, Want to go, custom lists) through its screen, because
 * Google gives other apps no way to read them. Each step checks that the screen really changed before going on.
 */
class MapsSavedFlow(private val d: MapsDriver) {
    companion object {
        /** Tries to find a control, scrolling a little between looks. */
        private const val LOOKS = 6

        fun listLabel(raw: String?): String = when (raw?.trim()?.lowercase(Locale.ROOT).orEmpty()) {
            "", "starred", "star", "starred places", "stared" -> "Starred places"
            "favorite", "favorites", "favourite", "favourites", "liked", "like", "liked places", "hearted" -> "Favorites"
            "want to go", "wishlist", "want" -> "Want to go"
            "labeled", "labelled" -> "Labeled"
            else -> raw!!.trim()
        }
    }

    private suspend fun waitFor(label: String, scroll: Boolean): Boolean? {
        repeat(LOOKS) { i ->
            if (d.cancelled()) return null
            val t = d.texts() ?: return null
            if (t.any { it.contains(label, ignoreCase = true) }) return d.click(label)
            if (scroll && i >= 1) d.scrollDown()
            d.pause(700)
        }
        return false
    }

    private suspend fun sample(): String = d.texts()?.take(12)?.joinToString(" | ").orEmpty().take(300)

    private suspend fun step(label: String, scroll: Boolean = false): MapsFlowResult? = when (waitFor(label, scroll)) {
        null -> if (d.cancelled()) MapsFlowResult.Cancelled else MapsFlowResult.ScreenUnreadable
        false -> MapsFlowResult.Stuck("find \"$label\"", sample())
        true -> null
    }

    /** Opens Google Maps' saved list and starts navigation to [place] from it. */
    suspend fun navigateToSaved(list: String?, place: String): MapsFlowResult {
        if (!d.open("https://www.google.com/maps")) return MapsFlowResult.Stuck("open Google Maps", "")
        d.pause(1500)
        step("Saved")?.let { return it }
        d.pause(800)
        step(listLabel(list))?.let { return it }
        d.pause(800)
        step(place, scroll = true)?.let { return it }
        d.pause(900)
        step("Directions")?.let { return it }
        d.pause(900)
        step("Start")?.let { return it }
        repeat(4) {
            d.pause(1200)
            val snap = d.texts()?.let { MapsScreen.parse(it) }
            if (snap != null && snap.navigating) return MapsFlowResult.Started(snap)
        }
        return MapsFlowResult.Stuck("confirm that navigation started", sample())
    }

    /** Taps the first of [labels] that is on screen (Exit, Overview, Re-centre ...). */
    suspend fun tapFirst(labels: List<String>): String? {
        val t = d.texts() ?: return null
        val hit = labels.firstOrNull { l -> t.any { it.contains(l, ignoreCase = true) } } ?: return null
        return if (d.click(hit)) hit else null
    }
}
