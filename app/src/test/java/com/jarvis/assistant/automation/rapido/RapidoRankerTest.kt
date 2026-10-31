package com.jarvis.assistant.automation.rapido

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RapidoRankerTest {
    private fun opt(name: String, cat: VehicleCategory, fare: Double?, eta: Int?, max: Double? = null, ok: Boolean = true, pos: Int = 0) =
        FareOption(name, cat, fare, max, eta, available = ok, position = pos)

    private val bike = opt("Bike", VehicleCategory.BIKE, 89.0, 3)
    private val auto = opt("Auto", VehicleCategory.AUTO, 140.0, 5)
    private val eco = opt("Cab Economy", VehicleCategory.CAB, 210.0, 7)
    private val prem = opt("Cab Premium", VehicleCategory.CAB, 320.0, 9)
    private val all = listOf(bike, auto, eco, prem)

    @Test fun cheapestAndFastestPickTheRightOption() {
        assertEquals("Bike", RapidoRanker.recommend(all, RideRequest(drop = "x", priority = RidePriority.CHEAPEST)).option?.name)
        val slowCheap = listOf(opt("Bike", VehicleCategory.BIKE, 80.0, 12), opt("Auto", VehicleCategory.AUTO, 130.0, 2))
        assertEquals("Auto", RapidoRanker.recommend(slowCheap, RideRequest(drop = "x", priority = RidePriority.FASTEST)).option?.name)
        assertEquals("Bike", RapidoRanker.recommend(slowCheap, RideRequest(drop = "x", priority = RidePriority.CHEAPEST)).option?.name)
    }

    @Test fun vehicleAndTierFiltersNeverSwitchToSomethingElse() {
        val r = RapidoRanker.recommend(all, RideRequest(drop = "x", vehicle = VehicleCategory.CAB, cabTier = "premium"))
        assertEquals("Cab Premium", r.option?.name)
        val none = RapidoRanker.recommend(listOf(bike, auto), RideRequest(drop = "x", vehicle = VehicleCategory.CAB))
        assertNull(none.option)
        assertTrue(none.reason.contains("not showing"))
    }

    @Test fun budgetUsesTheHighestFareOfARange() {
        val ranged = opt("Auto", VehicleCategory.AUTO, 80.0, 4, max = 130.0)
        assertFalse(RapidoRanker.withinBudget(ranged, 100.0))
        assertTrue(RapidoRanker.withinBudget(ranged, 130.0))
        assertFalse(RapidoRanker.withinBudget(opt("X", VehicleCategory.OTHER, null, 3), 500.0))
    }

    @Test fun overBudgetNamesTheCheapestAndSaysSo() {
        val r = RapidoRanker.recommend(all, RideRequest(drop = "x", maxBudget = 50.0))
        assertTrue(r.overBudget)
        assertEquals("Bike", r.option?.name)
        assertTrue(r.reason.contains("Nothing is within"))
    }

    @Test fun unavailableOptionsAreNeverRecommended() {
        val list = listOf(opt("Bike", VehicleCategory.BIKE, 60.0, 2, ok = false), auto)
        assertEquals("Auto", RapidoRanker.recommend(list, RideRequest(drop = "x", priority = RidePriority.CHEAPEST)).option?.name)
        val allOff = RapidoRanker.recommend(listOf(opt("Bike", VehicleCategory.BIKE, 60.0, 2, ok = false)), RideRequest(drop = "x"))
        assertNull(allOff.option)
    }

    @Test fun emptyListInventsNothing() {
        val r = RapidoRanker.recommend(emptyList(), RideRequest(drop = "x"))
        assertNull(r.option)
        assertEquals("No options to compare.", RapidoRanker.compare(emptyList()))
    }

    @Test fun comparisonNamesCheapestAndFastestFromScreenValuesOnly() {
        val text = RapidoRanker.compare(all)
        assertTrue(text.contains("Cheapest shown: Bike"))
        assertTrue(text.contains("Fastest pickup shown: Bike"))
        assertFalse(text.contains("₹999"))
    }

    @Test fun diffReportsPriceAndAvailabilityChanges() {
        val after = listOf(bike.copy(fare = 99.0), auto.copy(available = false), eco)
        val d = RapidoRanker.diff(all, after)!!
        assertTrue(d.contains("Bike changed from ₹89 to ₹99"))
        assertTrue(d.contains("Auto is now unavailable"))
        assertTrue(d.contains("Cab Premium is no longer shown"))
        assertNull(RapidoRanker.diff(all, all))
        assertNotNull(RapidoRanker.signature(all))
        assertFalse(RapidoRanker.signature(all) == RapidoRanker.signature(after))
    }
}
