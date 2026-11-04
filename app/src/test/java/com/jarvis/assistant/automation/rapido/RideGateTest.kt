package com.jarvis.assistant.automation.rapido

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RideGateTest {
    private fun summary(fare: Double? = 140.0, drop: String = "Koramangala 5th Block", option: String? = "Auto", pay: String = "Cash", stops: List<String> = emptyList(), max: Double? = null) =
        RideSummary("12 MG Road", drop, stops, option, VehicleCategory.AUTO, fare, max, 5, pay)

    private var now = 1_000_000L
    private fun gate() = RideGate({ now }, InMemoryRideLedger())
    private fun denied(d: RideDecision) = (d as RideDecision.Denied).code

    @Test fun explicitPhrasesAreAcceptedAndHedgesRejected() {
        listOf("confirm the ride", "yes book it", "haan book kar do", "go ahead and book the ride", "confirm booking", "book it",
            "auto book kar do", "ride book karo", "Yes, confirm the booking please").forEach {
            assertTrue(it, RideConfirmation.isExplicit(RideAction.BOOK, it))
        }
        listOf("don't book it", "nahi abhi book mat karo", "wait", "cancel the ride", "maybe later", "what is the fare", "ok", "hmm",
            "no", "do not confirm the ride", "ruko book mat karo", "show fares", "").forEach {
            assertFalse(it, RideConfirmation.isExplicit(RideAction.BOOK, it))
        }
    }

    @Test fun cancelPhrasesAndRefusals() {
        listOf("yes cancel it", "haan cancel kar do", "cancel the ride", "cancel my booking please", "confirm cancel the ride").forEach {
            assertTrue(it, RideConfirmation.isExplicit(RideAction.CANCEL, it))
        }
        listOf("don't cancel the ride", "cancel mat karo", "nahi cancel nahi karna", "wait", "keep the ride", "ok", "").forEach {
            assertFalse(it, RideConfirmation.isExplicit(RideAction.CANCEL, it))
        }
    }

    @Test fun cannotBookBeforePreparing() {
        assertEquals("NOT_PREPARED", denied(gate().authorize(summary(), "confirm the ride")))
    }

    @Test fun cannotConfirmInTheSameUtteranceThatPreparedIt() {
        val g = gate()
        g.prepare(summary(), "book an auto to koramangala and confirm the ride")
        now += 10_000
        assertEquals("SAME_TURN", denied(g.authorize(summary(), "book an auto to koramangala and confirm the ride")))
        assertEquals("SAME_TURN", denied(g.authorize(summary(), "please book an auto to koramangala and confirm the ride now")))
    }

    @Test fun cannotConfirmInstantlyEvenWithDifferentWords() {
        val g = gate()
        g.prepare(summary(), "auto to koramangala")
        now += 500
        assertEquals("SAME_TURN", denied(g.authorize(summary(), "confirm the ride")))
    }

    @Test fun vagueOrMissingConfirmationIsRefused() {
        val g = gate()
        g.prepare(summary(), "auto to koramangala")
        now += 10_000
        assertEquals("NOT_CONFIRMED", denied(g.authorize(summary(), "hmm ok")))
        assertEquals("NOT_CONFIRMED", denied(g.authorize(summary(), "")))
        assertEquals("NOT_CONFIRMED", denied(g.authorize(summary(), "don't book it")))
    }

    @Test fun happyPathAllowsExactlyOnce() {
        val g = gate()
        g.prepare(summary(), "auto to koramangala")
        now += 10_000
        val ok = g.authorize(summary(), "yes book it")
        assertTrue(ok is RideDecision.Allowed)
        g.markAttempted((ok as RideDecision.Allowed).ride)
        now += 5_000
        assertEquals("DUPLICATE", denied(g.authorize(summary(), "yes book it")))
    }

    @Test fun anyChangeToTheRideIsRefused() {
        val g = gate()
        g.prepare(summary(), "auto to koramangala")
        now += 10_000
        assertEquals("CHANGED", denied(g.authorize(summary(fare = 175.0), "confirm the ride")))
        assertEquals("CHANGED", denied(g.authorize(summary(drop = "Whitefield"), "confirm the ride")))
        assertEquals("CHANGED", denied(g.authorize(summary(pay = "UPI"), "confirm the ride")))
        assertEquals("CHANGED", denied(g.authorize(summary(option = "Bike"), "confirm the ride")))
        assertEquals("CHANGED", denied(g.authorize(summary(stops = listOf("Lalbagh")), "confirm the ride")))
        assertEquals("CHANGED", denied(g.authorize(summary(max = 160.0), "confirm the ride")))
    }

    @Test fun pickupEtaChangingIsNotAChange() {
        val g = gate()
        g.prepare(summary(), "auto to koramangala")
        now += 10_000
        val later = summary().copy(etaMinutes = 9)
        assertTrue(g.authorize(later, "confirm the ride") is RideDecision.Allowed)
    }

    @Test fun staleFareAndUnreadableScreenAreRefused() {
        val g = gate()
        g.prepare(summary(), "auto to koramangala")
        now += 5 * 60_000
        assertEquals("EXPIRED", denied(g.authorize(summary(), "confirm the ride")))
        g.prepare(summary(), "auto to koramangala")
        now += 10_000
        assertEquals("UNREADABLE", denied(g.authorize(summary(fare = null), "confirm the ride")))
    }

    @Test fun preparingAgainNeverErasesASubmittedRide() {
        val g = gate()
        val p = g.prepare(summary(), "auto to koramangala")
        now += 10_000
        g.markAttempted((g.authorize(summary(), "confirm the ride") as RideDecision.Allowed).ride)
        now += 60_000
        g.prepare(summary(), "auto again")
        now += 10_000
        assertEquals("DUPLICATE", denied(g.authorize(summary(), "yes book it")))
        assertEquals(p.fingerprint, g.activeFingerprint())
    }

    @Test fun anotherUnresolvedBookingBlocksANewOne() {
        val g = gate()
        g.prepare(summary(), "auto to koramangala")
        now += 10_000
        g.markAttempted((g.authorize(summary(), "confirm the ride") as RideDecision.Allowed).ride)
        now += 60_000
        g.prepare(summary(drop = "Whitefield"), "auto to whitefield")
        now += 10_000
        assertEquals("OTHER_ACTIVE", denied(g.authorize(summary(drop = "Whitefield"), "yes book it")))
    }

    @Test fun releasingAnEndedRideAllowsBookingAgain() {
        val g = gate()
        g.prepare(summary(), "auto to koramangala")
        now += 10_000
        val ride = (g.authorize(summary(), "confirm the ride") as RideDecision.Allowed).ride
        g.markAttempted(ride)
        g.release(ride.fingerprint)
        g.prepare(summary(), "auto to koramangala again")
        now += 10_000
        assertTrue(g.authorize(summary(), "yes book it") is RideDecision.Allowed)
        assertNull(RideGate({ now }, InMemoryRideLedger()).activeFingerprint())
    }

    @Test fun duplicateWindowEventuallyExpires() {
        val g = gate()
        g.prepare(summary(), "auto to koramangala")
        now += 10_000
        g.markAttempted((g.authorize(summary(), "confirm the ride") as RideDecision.Allowed).ride)
        now += 21 * 60_000
        g.prepare(summary(), "auto to koramangala once more")
        now += 10_000
        assertTrue(g.authorize(summary(), "yes book it") is RideDecision.Allowed)
    }

    @Test fun cancellingNeedsPreparationThenANewExplicitMessage() {
        val g = gate()
        fun code(d: CancelDecision) = (d as CancelDecision.Denied).code
        assertEquals("NOT_PREPARED", code(g.authorizeCancel(RideStage.DRIVER_ASSIGNED, "yes cancel it")))
        g.prepareCancel(RideStage.DRIVER_ASSIGNED, "cancel my ride")
        now += 500
        assertEquals("SAME_TURN", code(g.authorizeCancel(RideStage.DRIVER_ASSIGNED, "yes cancel it")))
        now += 10_000
        assertEquals("NOT_CONFIRMED", code(g.authorizeCancel(RideStage.DRIVER_ASSIGNED, "what is the fee")))
        assertEquals("NOT_CONFIRMED", code(g.authorizeCancel(RideStage.DRIVER_ASSIGNED, "don't cancel it")))
        assertEquals("CHANGED", code(g.authorizeCancel(RideStage.DRIVER_ARRIVED, "yes cancel it")))
        g.prepareCancel(RideStage.DRIVER_ASSIGNED, "cancel my ride")
        now += 10_000
        assertTrue(g.authorizeCancel(RideStage.DRIVER_ASSIGNED, "yes cancel it") is CancelDecision.Allowed)
        now += 4 * 60_000
        g.prepareCancel(RideStage.DRIVER_ASSIGNED, "cancel my ride")
        now += 4 * 60_000
        assertEquals("EXPIRED", code(g.authorizeCancel(RideStage.DRIVER_ASSIGNED, "yes cancel it")))
    }
}
