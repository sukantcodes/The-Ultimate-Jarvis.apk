package com.jarvis.assistant.automation.rapido

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RapidoQueryParserTest {
    private fun p(s: String) = RapidoQueryParser.parse(s)

    @Test fun hinglishFromToWithVehicle() {
        val r = p("mujhe bike book karo Indiranagar se Koramangala")
        assertEquals("Indiranagar", r.pickup)
        assertEquals("Koramangala", r.drop)
        assertEquals(VehicleCategory.BIKE, r.vehicle)
    }

    @Test fun englishCheapestAutoToPlaceUsesCurrentLocation() {
        val r = p("cheapest auto to the airport")
        assertEquals("the airport".removePrefix("the "), r.drop?.removePrefix("the "))
        assertEquals(VehicleCategory.AUTO, r.vehicle)
        assertEquals(RidePriority.CHEAPEST, r.priority)
        assertTrue(r.usesCurrentLocation)
    }

    @Test fun takWithBudget() {
        val r = p("HSR tak cab 300 ke neeche")
        assertEquals("HSR", r.drop)
        assertEquals(VehicleCategory.CAB, r.vehicle)
        assertEquals(300.0, r.maxBudget!!, 0.0)
    }

    @Test fun englishBudgetAndFromTo() {
        val r = p("book a cab from MG Road to Whitefield under 500")
        assertEquals("MG Road", r.pickup)
        assertEquals("Whitefield", r.drop)
        assertEquals(500.0, r.maxBudget!!, 0.0)
    }

    @Test fun stopsEnglishAndHindi() {
        val a = p("book a cab from MG Road to Whitefield via Marathahalli")
        assertEquals(listOf("Marathahalli"), a.stops)
        assertEquals("Whitefield", a.drop)
        val b = p("Whitefield tak cab Marathahalli hote hue")
        assertEquals(listOf("Marathahalli"), b.stops)
        assertEquals("Whitefield", b.drop)
    }

    @Test fun scheduledRidesAreRecognisedNotSilentlyBookedNow() {
        val r = p("kal subah 7 baje Airport tak auto")
        assertTrue(r.scheduled)
        assertEquals("Airport", r.drop)
        assertFalse(p("Airport tak auto abhi").scheduled)
    }

    @Test fun placeNamesContainingVehicleOrFillerWordsAreKeptWhole() {
        val a = p("taxi to Car Street")
        assertEquals("Car Street", a.drop)
        val b = p("cab to Auto Nagar")
        assertEquals("Auto Nagar", b.drop)
        assertEquals(VehicleCategory.CAB, b.vehicle)
    }

    @Test fun cabTiers() {
        assertEquals("premium", p("premium cab to Whitefield").cabTier)
        assertEquals("xl", p("xl cab to Whitefield").cabTier)
        assertNull(p("auto to Whitefield").cabTier)
    }

    @Test fun noDestinationMeansAskNotGuess() {
        assertNull(p("book a ride").drop)
        assertNull(p("").drop)
    }

    @Test fun hereMeansCurrentLocation() {
        val r = p("here se Koramangala bike")
        assertTrue(r.usesCurrentLocation)
        assertEquals("Koramangala", r.drop)
    }

    @Test fun devanagariWordsAreUnderstood() {
        val r = p("इंदिरानगर से कोरमंगला बाइक बुक करो")
        assertEquals("इंदिरानगर", r.pickup)
        assertEquals("कोरमंगला", r.drop)
        assertEquals(VehicleCategory.BIKE, r.vehicle)
    }

    @Test fun pickupNoteIsCaptured() {
        val r = p("auto to Whitefield note: wait at the blue gate")
        assertEquals("wait at the blue gate", r.pickupNote)
        assertEquals("Whitefield", r.drop)
    }

    @Test fun mergeLetsExplicitFieldsWinAndBlankChangesNothing() {
        val base = p("bike to Koramangala")
        val m = RapidoQueryParser.merge(base, drop = "  ", vehicle = VehicleCategory.AUTO, maxBudget = 120.0, pickup = "my location")
        assertEquals("Koramangala", m.drop)
        assertEquals(VehicleCategory.AUTO, m.vehicle)
        assertEquals(120.0, m.maxBudget!!, 0.0)
        assertTrue(m.usesCurrentLocation)
    }
}
