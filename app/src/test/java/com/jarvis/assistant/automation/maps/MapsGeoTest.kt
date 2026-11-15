package com.jarvis.assistant.automation.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapsGeoTest {
    private val lat = 12.9716
    private val lon = 77.5946

    @Test fun distanceIsRoughlyRight() {
        // Bengaluru to Mysuru is about 126 km in a straight line.
        val d = MapsGeo.haversineM(12.9716, 77.5946, 12.2958, 76.6394)
        assertTrue(d in 120_000.0..132_000.0)
        assertEquals(0.0, MapsGeo.haversineM(lat, lon, lat, lon), 0.001)
    }

    @Test fun userWordsBecomeTheSearchTerm() {
        assertEquals("dominos", MapsGeo.cleanQuery("Find the dominos store nearest to me"))
        assertEquals("petrol pump", MapsGeo.cleanQuery("nearest petrol pump"))
        assertEquals("store", MapsGeo.cleanQuery("store"))
    }

    @Test fun categoriesAreOnlyMatchedWhole() {
        assertEquals(listOf("amenity=fuel"), MapsGeo.categoryTags("petrol pump"))
        assertEquals(listOf("amenity=atm"), MapsGeo.categoryTags("ATM"))
        assertNull(MapsGeo.categoryTags("dominos"))
    }

    @Test fun brandRegexToleratesApostrophes() {
        val rx = Regex(MapsGeo.fuzzyRegex("dominos"), RegexOption.IGNORE_CASE)
        assertTrue(rx.containsMatchIn("Domino's Pizza"))
        assertTrue(rx.containsMatchIn("Dominos"))
        assertTrue(rx.containsMatchIn("Domino’s"))
        assertTrue(!rx.containsMatchIn("Dolmen Mall"))
    }

    @Test fun overpassQueryUsesTagsForCategoriesAndNamesForBrands() {
        val cat = MapsGeo.overpassQuery("petrol pump", lat, lon, 3000)
        assertTrue(cat.contains("nwr[\"amenity\"=\"fuel\"](around:3000,12.971600,77.594600)"))
        val brand = MapsGeo.overpassQuery("dominos", lat, lon, 3000)
        assertTrue(brand.contains("nwr[\"name\"~\""))
        assertTrue(brand.contains("nwr[\"brand\"~\""))
        assertTrue(brand.contains("out center tags"))
    }

    private val overpass = """
        {"elements":[
          {"type":"node","id":1,"lat":12.9750,"lon":77.5990,"tags":{"name":"Domino's Pizza","addr:housenumber":"12","addr:street":"MG Road","addr:city":"Bengaluru","opening_hours":"Mo-Su 10:00-23:00","phone":"+91 80 1111 2222"}},
          {"type":"way","id":2,"center":{"lat":12.9751,"lon":77.5991},"tags":{"name":"Domino's Pizza"}},
          {"type":"node","id":3,"lat":12.9300,"lon":77.6200,"tags":{"brand":"Domino's"}},
          {"type":"node","id":4,"lat":12.9400,"lon":77.6000,"tags":{"shop":"nameless"}},
          {"type":"node","id":5,"tags":{"name":"No position"}}
        ]}
    """.trimIndent()

    @Test fun overpassAnswerIsParsedRankedAndDeduplicated() {
        val parsed = MapsGeo.parseOverpass(overpass, lat, lon)
        assertEquals(3, parsed.size)
        val ranked = MapsGeo.rank(parsed, 5)
        assertEquals(2, ranked.size)                       // the way beside the node is the same shop
        assertEquals("12 MG Road, Bengaluru", ranked[0].address)
        assertEquals("Mo-Su 10:00-23:00", ranked[0].hours)
        assertEquals("+91 80 1111 2222", ranked[0].phone)
        assertTrue(ranked[0].distanceM!! < ranked[1].distanceM!!)
        assertEquals("address not listed", ranked[1].address)
    }

    @Test fun brokenAnswersGiveNothingInsteadOfCrashing() {
        assertTrue(MapsGeo.parseOverpass("<html>busy</html>", lat, lon).isEmpty())
        assertTrue(MapsGeo.parseNominatim("{}", null, null).isEmpty())
    }

    @Test fun nominatimAnswerIsParsed() {
        val json = """[{"lat":"12.97","lon":"77.59","display_name":"Koramangala, Bengaluru, Karnataka, India","name":"Koramangala","extratags":{"opening_hours":"24/7"}}]"""
        val p = MapsGeo.parseNominatim(json, lat, lon).single()
        assertEquals("Koramangala", p.name)
        assertEquals("24/7", p.hours)
        assertTrue(p.distanceM!! > 0)
    }

    @Test fun distanceIsSpokenSensibly() {
        assertEquals("120 m", MapsGeo.distanceText(118.0))
        assertEquals("1.4 km", MapsGeo.distanceText(1_380.0))
        assertEquals("distance unknown", MapsGeo.distanceText(null))
    }
}
