package com.jarvis.assistant.automation.amazon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PurchaseGateTest {
    private fun summary(total: Double? = 999.0, address: String = "Deliver to Sukant Hyderabad", pay: String = "Pay on Delivery", qty: Int = 1) =
        CheckoutSummary(listOf(CartItem("Logitech M331 Silent Plus Wireless Mouse", 999.0, qty)), 999.0, 0.0, total, address, pay, "tomorrow")

    private var now = 1_000_000L
    private fun gate() = PurchaseGate({ now }, InMemoryOrderLedger())
    private fun denied(d: GateDecision) = (d as GateDecision.Denied).code

    @Test fun explicitPhrasesAreAcceptedAndHedgesRejected() {
        listOf("confirm the order", "yes place the order", "place order", "haan order kar do", "go ahead and buy it",
            "confirm kar do", "order place kar do", "Yes, place my order please").forEach {
            assertTrue(it, ConfirmationPhrase.isExplicit(it))
        }
        listOf("don't place the order", "nahi abhi order mat karo", "wait", "cancel the order", "maybe later", "what is the total",
            "ok", "hmm", "no", "do not confirm the order", "ruko order mat karo", "show my cart", "").forEach {
            assertFalse(it, ConfirmationPhrase.isExplicit(it))
        }
    }

    @Test fun cannotPlaceBeforePreparing() {
        assertEquals("NOT_PREPARED", denied(gate().authorize(summary(), "confirm the order")))
    }

    @Test fun cannotConfirmInTheSameUtteranceThatPreparedIt() {
        val g = gate()
        g.prepare(summary(), "buy the mouse and confirm the order")
        now += 10_000
        assertEquals("SAME_TURN", denied(g.authorize(summary(), "buy the mouse and confirm the order")))
        assertEquals("SAME_TURN", denied(g.authorize(summary(), "please buy the mouse and confirm the order now")))
    }

    @Test fun cannotConfirmInstantlyEvenWithDifferentWords() {
        val g = gate()
        g.prepare(summary(), "checkout karo")
        now += 500
        assertEquals("SAME_TURN", denied(g.authorize(summary(), "confirm the order")))
    }

    @Test fun vagueOrMissingConfirmationIsRefused() {
        val g = gate()
        g.prepare(summary(), "checkout karo")
        now += 10_000
        assertEquals("NOT_CONFIRMED", denied(g.authorize(summary(), "hmm ok")))
        assertEquals("NOT_CONFIRMED", denied(g.authorize(summary(), "")))
        assertEquals("NOT_CONFIRMED", denied(g.authorize(summary(), "don't place the order")))
    }

    @Test fun happyPathAllowsExactlyOnce() {
        val g = gate()
        g.prepare(summary(), "checkout karo")
        now += 10_000
        val ok = g.authorize(summary(), "yes place the order")
        assertTrue(ok is GateDecision.Allowed)
        g.markAttempted((ok as GateDecision.Allowed).order)
        now += 5_000
        assertEquals("DUPLICATE", denied(g.authorize(summary(), "yes place the order")))
    }

    @Test fun anyChangeToTheCheckoutIsRefused() {
        val g = gate()
        g.prepare(summary(), "checkout karo")
        now += 10_000
        assertEquals("CHANGED", denied(g.authorize(summary(total = 1049.0), "confirm the order")))
        assertEquals("CHANGED", denied(g.authorize(summary(address = "Deliver to Someone Else Delhi"), "confirm the order")))
        assertEquals("CHANGED", denied(g.authorize(summary(pay = "Credit card ending 4321"), "confirm the order")))
        assertEquals("CHANGED", denied(g.authorize(summary(qty = 2), "confirm the order")))
    }

    @Test fun staleAndUnreadablePreparationsAreRefused() {
        val g = gate()
        g.prepare(summary(), "checkout karo")
        now += 11 * 60_000
        assertEquals("EXPIRED", denied(g.authorize(summary(), "confirm the order")))
        val g2 = gate()
        g2.prepare(summary(), "checkout karo")
        now += 10_000
        assertEquals("UNREADABLE", denied(g2.authorize(summary(total = null), "confirm the order")))
    }

    @Test fun placedCheckoutCannotBeResubmittedLater() {
        val g = gate()
        val p = g.prepare(summary(), "checkout karo")
        now += 10_000
        g.markAttempted(p); g.markPlaced(p)
        // user prepares the very same checkout again (e.g. model retries) and confirms again
        g.prepare(summary(), "checkout again")
        now += 10_000
        assertEquals("DUPLICATE", denied(g.authorize(summary(), "confirm the order")))
    }
}
