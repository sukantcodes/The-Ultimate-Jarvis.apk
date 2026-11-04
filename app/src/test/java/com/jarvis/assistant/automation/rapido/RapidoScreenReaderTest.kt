package com.jarvis.assistant.automation.rapido

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RapidoScreenReaderTest {
    private fun cls(n: List<RapidoNode>) = RapidoScreenReader.classify(n)

    @Test fun classifiesEveryBookingAndRideScreen() {
        assertEquals(RapidoScreen.HOME, cls(RScreens.home()))
        assertEquals(RapidoScreen.LOCATION_SEARCH, cls(RScreens.search(listOf("Koramangala 5th Block, Bengaluru", "Koramangala Water Tank, Bengaluru"))))
        assertEquals(RapidoScreen.MAP_PICKER, cls(RScreens.mapPin("Koramangala 5th Block")))
        assertEquals(RapidoScreen.RIDE_OPTIONS, cls(RScreens.options("Current location", "Koramangala", RScreens.defaultCards)))
        assertEquals(RapidoScreen.SEARCHING_DRIVER, cls(RScreens.searching()))
        assertEquals(RapidoScreen.DRIVER_ASSIGNED, cls(RScreens.assigned()))
        assertEquals(RapidoScreen.DRIVER_ARRIVED, cls(RScreens.arrived()))
        assertEquals(RapidoScreen.ON_TRIP, cls(RScreens.onTrip()))
        assertEquals(RapidoScreen.TRIP_COMPLETED, cls(RScreens.completed()))
        assertEquals(RapidoScreen.NO_DRIVERS, cls(RScreens.noDrivers()))
        assertEquals(RapidoScreen.CANCEL_FLOW, cls(RScreens.cancelDialog()))
        assertEquals(RapidoScreen.OFFLINE, cls(RScreens.offline()))
        assertEquals(RapidoScreen.LOCATION_OFF, cls(RScreens.locationOff()))
        assertEquals(RapidoScreen.LOADING, cls(RScreens.loading()))
    }

    @Test fun loginOtpEntryAndPaymentPinAreUserOnlyScreens() {
        assertEquals(RapidoScreen.SIGN_IN, cls(RScreens.login()))
        assertEquals(RapidoScreen.CHALLENGE, cls(RScreens.otpEntry()))
        assertEquals(RapidoScreen.CHALLENGE, cls(RScreens.upiPin()))
        assertEquals("an OTP", RapidoScreenReader.challengeKind(RScreens.otpEntry()))
        assertEquals("your UPI PIN", RapidoScreenReader.challengeKind(RScreens.upiPin()))
    }

    @Test fun theRideStartOtpShownToThePassengerIsNotAChallenge() {
        // "Share OTP 4821 with captain" is information on a normal ride screen, not a field to fill.
        assertEquals(RapidoScreen.DRIVER_ASSIGNED, cls(RScreens.assigned()))
        assertEquals(RapidoScreen.DRIVER_ARRIVED, cls(RScreens.arrived()))
    }

    @Test fun readsFareCardsWithEtaSeatsAndAvailability() {
        val nodes = RScreens.options("Home", "Office", RScreens.defaultCards, unavailable = setOf("Cab Premium"),
            extraByCard = mapOf("Cab Economy" to "Toll charges extra"))
        val o = RapidoScreenReader.fareOptions(nodes)
        assertEquals(listOf("Bike", "Auto", "Cab Economy", "Cab Premium"), o.map { it.name })
        assertEquals(89.0, o[0].fare!!, 0.0)
        assertEquals(3, o[0].etaMinutes)
        assertEquals(VehicleCategory.BIKE, o[0].category)
        assertEquals(VehicleCategory.AUTO, o[1].category)
        assertEquals(VehicleCategory.CAB, o[2].category)
        assertTrue(o[2].extras.any { it.contains("Toll", ignoreCase = true) })
        assertFalse(o[3].available)
    }

    @Test fun fareRangesAreKeptAsRanges() {
        assertEquals(80.0 to 95.0, RapidoText.fare("Auto ₹80-₹95"))
        assertEquals(80.0 to 95.0, RapidoText.fare("₹80 - 95"))
        assertEquals(120.0 to null, RapidoText.fare("₹120"))
        assertEquals(null to null, RapidoText.fare("no price"))
        assertEquals("₹80-₹95", RapidoMoney.format(80.0, 95.0))
        assertEquals("fare not visible", RapidoMoney.format(null))
    }

    @Test fun bookButtonNamesTheSelectedRide() {
        val n = RScreens.options("Home", "Office", RScreens.defaultCards, selectedName = "Auto")
        assertEquals("Book Auto", RapidoScreenReader.bookButton(n)?.label)
        assertTrue(RapidoScreenReader.fareOptions(n).first { it.name == "Auto" }.selected)
        assertFalse(RapidoScreenReader.fareOptions(n).first { it.name == "Bike" }.selected)
    }

    @Test fun readsPickupDropAndPayment() {
        val n = RScreens.options("12 MG Road, Bengaluru", "Koramangala 5th Block", RScreens.defaultCards, selectedName = "Bike", payment = "Pay via UPI")
        assertEquals("12 MG Road, Bengaluru", RapidoScreenReader.pickupText(n))
        assertEquals("Koramangala 5th Block", RapidoScreenReader.dropText(n))
        assertEquals("Pay via UPI", RapidoScreenReader.paymentText(n))
    }

    @Test fun readsCaptainDetailsAndNeverTheStartOtp() {
        val d = RapidoScreenReader.readDriver(RScreens.assigned())
        assertEquals("Ravi Kumar", d.name)
        assertEquals("KA 01 AB 1234", d.vehicleNumber)
        assertEquals("Honda Activa", d.vehicleModel)
        assertEquals(4.8, d.rating!!, 0.0)
        assertNotNull(d.etaText)
        assertFalse(d.toString().contains("4821"))
    }

    @Test fun suggestionsAreThePlacesUnderTheField() {
        val n = RScreens.search(listOf("Koramangala 5th Block, Bengaluru", "Koramangala Water Tank, Bengaluru"))
        val s = RapidoScreenReader.suggestions(n).map { it.label }
        assertEquals(listOf("Koramangala 5th Block, Bengaluru", "Koramangala Water Tank, Bengaluru"), s)
    }

    @Test fun cancelAndConfirmButtonsAreFound() {
        assertEquals("Cancel ride", RapidoScreenReader.cancelRideButton(RScreens.assigned())?.label)
        assertNull(RapidoScreenReader.cancelRideButton(RScreens.home()))
        assertEquals("Cancel ride", RapidoScreenReader.confirmCancelButton(RScreens.cancelDialog())?.label)
    }

    @Test fun activeRideScreensAreRecognised() {
        listOf(RapidoScreen.SEARCHING_DRIVER, RapidoScreen.DRIVER_ASSIGNED, RapidoScreen.DRIVER_ARRIVED, RapidoScreen.ON_TRIP)
            .forEach { assertTrue(it.name, RapidoScreenReader.isActiveRide(it)) }
        listOf(RapidoScreen.HOME, RapidoScreen.RIDE_OPTIONS, RapidoScreen.NO_DRIVERS, RapidoScreen.TRIP_COMPLETED)
            .forEach { assertFalse(it.name, RapidoScreenReader.isActiveRide(it)) }
    }

    @Test fun unknownTextIsReportedAsUnknownNotGuessed() {
        assertEquals(RapidoScreen.UNKNOWN, cls(listOf(RScreens.node("Something nobody has seen"))))
    }
}
