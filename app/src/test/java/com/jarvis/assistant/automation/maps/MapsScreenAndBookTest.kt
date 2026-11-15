package com.jarvis.assistant.automation.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapsScreenAndBookTest {
    @Test fun navigationScreenIsRecognisedAndRead() {
        val s = MapsScreen.parse(listOf("Turn left onto 80 Feet Road", "200 m", "11 min", "4.2 km · 10:52 am", "Exit", "Overview", "Re-centre"))
        assertTrue(s.navigating)
        assertEquals(11, s.etaMinutes)
        assertEquals("4.2 km", s.remaining)
        assertEquals("10:52 AM", s.arrival)
        assertEquals("Turn left onto 80 Feet Road", s.nextInstruction)
        assertTrue(s.describe().contains("11 min left"))
    }

    @Test fun hoursAndMinutesAreAdded() {
        assertEquals(135, MapsScreen.parse(listOf("2 hr 15 min", "Exit")).etaMinutes)
        assertTrue(MapsScreen.parse(listOf("1 h 5 min", "Exit")).describe().contains("1 h 5 min left"))
    }

    @Test fun aRoutePreviewIsNotNavigation() {
        val s = MapsScreen.parse(listOf("Directions", "11 min", "4.2 km", "Start", "Steps"))
        assertFalse(s.navigating)
        assertEquals("Google Maps is not showing active navigation.", s.describe())
        assertFalse(MapsScreen.parse(emptyList()).navigating)
    }

    @Test fun aLoneTurnDistanceIsNotTheTripDistance() {
        assertNull(MapsScreen.parse(listOf("200 m", "11 min", "Exit")).remaining)
    }

    @Test fun placesAreFoundByNameAliasAndSynonym() {
        val book = PlaceBook(listOf(SavedPlace("Home", "12 MG Road", 12.9, 77.6), SavedPlace("Work", "Tech Park"), SavedPlace("Mom's House", aliases = listOf("amma"))))
        assertEquals("Home", book.find("my home")?.name)
        assertEquals("Work", book.find("office")?.name)
        assertEquals("Mom's House", book.find("moms house")?.name)
        assertEquals("Mom's House", book.find("Amma")?.name)
        assertNull(book.find("dominos"))
        assertNull(book.find(""))
    }

    @Test fun destinationPrefersCoordinatesThenAddressThenName() {
        assertEquals("12.900000,77.600000", SavedPlace("Home", "12 MG Road", 12.9, 77.6).destination())
        assertEquals("Tech Park", SavedPlace("Work", "Tech Park").destination())
        assertEquals("Gym", SavedPlace("Gym").destination())
    }

    @Test fun savingReplacesAndPersistsAndSurvivesJson() {
        var stored: List<SavedPlace> = emptyList()
        val book = PlaceBook(emptyList()) { stored = it }
        book.save(SavedPlace("Home", "Old"))
        book.save(SavedPlace("home", "New", 1.0, 2.0))
        assertEquals(1, stored.size)
        assertEquals("New", stored[0].address)
        val back = PlaceBook.fromJson(PlaceBook.toJson(stored))
        assertEquals(stored, back)
        assertTrue(book.remove("HOME"))
        assertTrue(stored.isEmpty())
        assertFalse(book.remove("home"))
    }

    @Test fun damagedStorageGivesAnEmptyList() {
        assertTrue(PlaceBook.fromJson("not json").isEmpty())
        assertTrue(PlaceBook.fromJson(null).isEmpty())
        // A place saved by an older build without aliases must still load.
        val old = PlaceBook.fromJson("""[{"name":"Home","address":"X"}]""")
        assertNotNull(old.single().aliases)
        assertEquals("Home", PlaceBook(old).find("home")?.name)
    }

    @Test fun sessionTracksTheTripAndItsChanges() {
        val s = MapsSession()
        assertFalse(s.active)
        s.start(MapsTarget("Airport", "Airport"), emptyList(), MapsLinks.Mode.DRIVING, emptySet())
        s.addStop(MapsTarget("Hebbal", "Hebbal"))
        assertTrue(s.summary().contains("Airport") && s.summary().contains("via Hebbal"))
        assertTrue(s.removeStop("heb"))
        assertFalse(s.removeStop("heb"))
        s.end()
        assertFalse(s.active)
    }
}
