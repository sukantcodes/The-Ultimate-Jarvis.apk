package com.jarvis.assistant.automation.maps

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapsSavedFlowTest {
    /** A tiny pretend Google Maps: each tap moves to the next screen when the label is on the current one. */
    private class FakeMaps(private val screens: Map<String, List<String>>, private val moves: Map<Pair<String, String>, String>, var unreadable: Boolean = false) : MapsDriver {
        var screen = "home"
        val taps = ArrayList<String>()
        override suspend fun open(uri: String): Boolean { screen = "home"; return true }
        override suspend fun texts(): List<String>? = if (unreadable) null else screens[screen]
        override suspend fun click(label: String): Boolean {
            val on = screens[screen].orEmpty().firstOrNull { it.contains(label, true) } ?: return false
            taps += label
            moves[screen to on]?.let { screen = it }
            return true
        }
        override suspend fun scrollDown() = true
        override suspend fun pause(ms: Long) {}
        override fun cancelled() = false
    }

    private fun maps(withStart: Boolean = true) = FakeMaps(
        screens = mapOf(
            "home" to listOf("Explore", "Saved", "Go"),
            "saved" to listOf("Starred places", "Favorites", "Want to go"),
            "starred" to listOf("Gym", "Grandma's House", "Dominos"),
            "card" to listOf("Grandma's House", "Directions", "Save"),
            "preview" to listOf("Grandma's House", "Start", "Steps"),
            "nav" to listOf("11 min", "4.2 km · 10:52 am", "Exit", "Overview")
        ),
        moves = buildMap {
            put("home" to "Saved", "saved")
            put("saved" to "Starred places", "starred")
            put("starred" to "Grandma's House", "card")
            put("card" to "Directions", "preview")
            if (withStart) put("preview" to "Start", "nav")
        }
    )

    @Test fun starredPlaceIsOpenedAndNavigationConfirmed() = runBlocking {
        val m = maps()
        val r = MapsSavedFlow(m).navigateToSaved("starred", "Grandma's House")
        assertTrue(r is MapsFlowResult.Started)
        assertEquals(listOf("Saved", "Starred places", "Grandma's House", "Directions", "Start"), m.taps)
    }

    @Test fun whenStartDoesNothingItSaysSoInsteadOfClaimingSuccess() = runBlocking {
        val r = MapsSavedFlow(maps(withStart = false)).navigateToSaved("starred", "Grandma's House")
        assertTrue(r is MapsFlowResult.Stuck)
        assertEquals("confirm that navigation started", (r as MapsFlowResult.Stuck).step)
    }

    @Test fun aMissingPlaceStopsWithWhatWasOnScreen() = runBlocking {
        val r = MapsSavedFlow(maps()).navigateToSaved("starred", "Nonexistent Cafe") as MapsFlowResult.Stuck
        assertTrue(r.step.contains("Nonexistent Cafe"))
        assertTrue(r.seen.contains("Dominos"))
    }

    @Test fun anUnreadableScreenIsReportedAsSuch() = runBlocking {
        val m = maps(); m.unreadable = true
        assertTrue(MapsSavedFlow(m).navigateToSaved(null, "Gym") is MapsFlowResult.ScreenUnreadable)
    }

    @Test fun listNamesAreMapped() {
        assertEquals("Starred places", MapsSavedFlow.listLabel(null))
        assertEquals("Favorites", MapsSavedFlow.listLabel("liked"))
        assertEquals("Want to go", MapsSavedFlow.listLabel("Want to go"))
        assertEquals("Road trip", MapsSavedFlow.listLabel(" Road trip "))
    }

    @Test fun controlsAreTappedOnlyWhenPresent() = runBlocking {
        val m = maps(); m.screen = "nav"
        assertEquals("Exit", MapsSavedFlow(m).tapFirst(listOf("Exit navigation", "Exit", "End")))
        m.screen = "home"
        assertEquals(null, MapsSavedFlow(m).tapFirst(listOf("Exit")))
    }
}
