package com.jarvis.assistant.automation.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapsLinksTest {
    @Test fun modeUnderstandsEverydayWords() {
        assertEquals(MapsLinks.Mode.DRIVING, MapsLinks.mode(null))
        assertEquals(MapsLinks.Mode.DRIVING, MapsLinks.mode("car"))
        assertEquals(MapsLinks.Mode.TWO_WHEELER, MapsLinks.mode("bike"))
        assertEquals(MapsLinks.Mode.TWO_WHEELER, MapsLinks.mode("two-wheeler"))
        assertEquals(MapsLinks.Mode.BICYCLING, MapsLinks.mode("cycle"))
        assertEquals(MapsLinks.Mode.WALKING, MapsLinks.mode("on foot"))
        assertEquals(MapsLinks.Mode.TRANSIT, MapsLinks.mode("metro"))
        assertNull(MapsLinks.mode("teleport"))
    }

    @Test fun avoidWordsAreRecognisedAndUnknownOnesReturned() {
        val (found, unknown) = MapsLinks.avoid(listOf("Toll roads", "express way", "ferry", "potholes"))
        assertEquals(setOf(MapsLinks.Avoid.TOLLS, MapsLinks.Avoid.HIGHWAYS, MapsLinks.Avoid.FERRIES), found)
        assertEquals(listOf("potholes"), unknown)
    }

    @Test fun navigationUriStartsGuidanceWithModeAndAvoid() {
        assertEquals("google.navigation:q=12.9345%2C77.6101&mode=d", MapsLinks.navigationUri("12.9345,77.6101", MapsLinks.Mode.DRIVING, emptySet()))
        assertEquals("google.navigation:q=MG%20Road&mode=l&avoid=th",
            MapsLinks.navigationUri("MG Road", MapsLinks.Mode.TWO_WHEELER, setOf(MapsLinks.Avoid.TOLLS, MapsLinks.Avoid.HIGHWAYS)))
        assertNull(MapsLinks.navigationUri("x", MapsLinks.Mode.TRANSIT, emptySet()))
    }

    @Test fun directionsUrlCarriesOriginStopsAndAvoid() {
        val url = MapsLinks.directionsUrl("Indiranagar", "Airport", listOf("Hebbal", "Yelahanka"), MapsLinks.Mode.DRIVING, setOf(MapsLinks.Avoid.TOLLS), navigate = true)
        assertTrue(url.contains("destination=Airport"))
        assertTrue(url.contains("origin=Indiranagar"))
        assertTrue(url.contains("waypoints=Hebbal%7CYelahanka"))
        assertTrue(url.contains("avoid=tolls"))
        // Maps can not guide from another place, so navigation is only requested without an origin.
        assertFalse(url.contains("dir_action=navigate"))
        assertTrue(MapsLinks.directionsUrl(null, "Airport", emptyList(), MapsLinks.Mode.DRIVING, emptySet(), navigate = true).contains("dir_action=navigate"))
    }

    @Test fun searchUriUsesPositionWhenKnown() {
        assertEquals("geo:12.934500,77.610100?q=dominos%20pizza", MapsLinks.searchUri("dominos pizza", 12.9345, 77.6101))
        assertEquals("geo:0,0?q=atm", MapsLinks.searchUri("atm", null, null))
    }

    @Test fun currentLocationWords() {
        assertTrue(MapsLinks.isCurrentLocation(null))
        assertTrue(MapsLinks.isCurrentLocation(" My Location "))
        assertFalse(MapsLinks.isCurrentLocation("Koramangala"))
    }
}
