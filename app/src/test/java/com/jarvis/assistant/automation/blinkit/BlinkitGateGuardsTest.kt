package com.jarvis.assistant.automation.blinkit

import com.jarvis.assistant.automation.amazon.InMemoryOrderLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlinkitConfirmationTest {
    private fun order(t: String) = BlinkitConfirmation.isExplicit(BlinkitAction.ORDER, t)
    private fun cancel(t: String) = BlinkitConfirmation.isExplicit(BlinkitAction.CANCEL, t)

    @Test fun `clear english and hinglish order confirmations pass`() {
        assertTrue(order("yes place the order"))
        assertTrue(order("haan order kar do"))
        assertTrue(order("confirm the order"))
        assertTrue(order("go ahead and place it"))
        assertTrue(order("haan pay kar do"))
        assertTrue(order("order laga do"))
    }

    @Test fun `refusals, hesitation and vague words never confirm an order`() {
        assertFalse(order("don't place the order"))
        assertFalse(order("nahi order mat karo"))
        assertFalse(order("wait, not yet"))
        assertFalse(order("ruko abhi order nahi"))
        assertFalse(order("ok"))
        assertFalse(order("what is the total"))
        assertFalse(order("thik hai"))
        assertFalse(order(""))
    }

    @Test fun `cancel needs a clear cancel instruction`() {
        assertTrue(cancel("yes cancel it"))
        assertTrue(cancel("haan order cancel kar do"))
        assertFalse(cancel("don't cancel"))
        assertFalse(cancel("nahi cancel mat karo"))
        assertFalse(cancel("keep the order"))
        assertFalse(cancel("what does cancelling cost"))
    }
}

class BlinkitGateTest {
    private var now = 1_000_000L
    private val ledger = InMemoryOrderLedger()
    private val gate = BlinkitGate({ now }, ledger)

    private fun summary(total: Double = 56.0, address: String = "Home, MG Road", qty: Int = 1) = CheckoutSummary(
        listOf(CartLine("Amul Taaza Toned Milk", "500 ml", 29.0, qty)),
        BillDetails(29.0, listOf("Delivery charge" to 25.0), emptyList(), total, null, null), address, "UPI", null
    )

    private fun prepared(utterance: String = "get me milk and check out") = gate.prepare(summary(), utterance)

    @Test fun `nothing can be ordered before a checkout is prepared`() {
        val d = gate.authorize(summary(), "yes place the order")
        assertEquals("NOT_PREPARED", (d as OrderDecision.Denied).code)
    }

    @Test fun `the same sentence can never prepare and confirm`() {
        prepared("yes place the order")
        now += 10_000
        assertEquals("SAME_TURN", (gate.authorize(summary(), "yes place the order") as OrderDecision.Denied).code)
    }

    @Test fun `an instant confirmation is refused as the same turn`() {
        prepared()
        now += 500
        assertEquals("SAME_TURN", (gate.authorize(summary(), "haan place the order") as OrderDecision.Denied).code)
    }

    @Test fun `a vague reply is not a confirmation`() {
        prepared()
        now += 10_000
        assertEquals("NOT_CONFIRMED", (gate.authorize(summary(), "ok") as OrderDecision.Denied).code)
    }

    @Test fun `a later explicit confirmation on an unchanged screen is allowed`() {
        prepared()
        now += 10_000
        assertTrue(gate.authorize(summary(), "haan place the order") is OrderDecision.Allowed)
    }

    @Test fun `a changed total, address or quantity blocks the order`() {
        prepared()
        now += 10_000
        assertEquals("CHANGED", (gate.authorize(summary(total = 61.0), "yes place the order") as OrderDecision.Denied).code)
        assertEquals("CHANGED", (gate.authorize(summary(address = "Work, HITEC City"), "yes place the order") as OrderDecision.Denied).code)
        assertEquals("CHANGED", (gate.authorize(summary(qty = 2), "yes place the order") as OrderDecision.Denied).code)
    }

    @Test fun `an old preparation expires`() {
        prepared()
        now += 9 * 60_000L
        assertEquals("EXPIRED", (gate.authorize(summary(), "yes place the order") as OrderDecision.Denied).code)
    }

    @Test fun `an unreadable total blocks the order`() {
        prepared()
        now += 10_000
        val unreadable = summary().copy(bill = summary().bill.copy(total = null))
        assertEquals("UNREADABLE", (gate.authorize(unreadable, "yes place the order") as OrderDecision.Denied).code)
    }

    @Test fun `a checkout submitted once can never be submitted again, even after a timeout`() {
        val p = prepared()
        now += 10_000
        val allowed = gate.authorize(summary(), "yes place the order") as OrderDecision.Allowed
        gate.markAttempted(allowed.order) // written before the tap, so a timeout or crash cannot lose it
        now += 60_000
        gate.prepare(summary(), "check out again")
        now += 10_000
        assertEquals("DUPLICATE", (gate.authorize(summary(), "yes place the order") as OrderDecision.Denied).code)
        assertNotNull(p)
    }

    @Test fun `preparing again does not erase the fact that an order was submitted`() {
        prepared()
        now += 10_000
        val a = gate.authorize(summary(), "yes place the order") as OrderDecision.Allowed
        gate.markPlaced(a.order)
        now += 20_000
        gate.prepare(summary(), "same again")
        now += 10_000
        assertEquals("DUPLICATE", (gate.authorize(summary(), "yes place the order") as OrderDecision.Denied).code)
    }

    @Test fun `the same checkout may be ordered again after the duplicate window`() {
        prepared()
        now += 10_000
        val a = gate.authorize(summary(), "yes place the order") as OrderDecision.Allowed
        gate.markPlaced(a.order)
        now += 31 * 60_000L
        gate.prepare(summary(), "order milk again")
        now += 10_000
        assertTrue(gate.authorize(summary(), "yes place the order") is OrderDecision.Allowed)
    }

    // ---- Cancelling ---------------------------------------------------------------------------

    @Test fun `cancelling needs an explanation first`() {
        assertEquals("NOT_PREPARED", (gate.authorizeCancel(OrderStage.PREPARING, "yes cancel it") as CancelDecision.Denied).code)
    }

    @Test fun `cancelling needs a new explicit message`() {
        gate.prepareCancel(OrderStage.PREPARING, "cancel my order")
        now += 10_000
        assertEquals("SAME_TURN", (gate.authorizeCancel(OrderStage.PREPARING, "cancel my order") as CancelDecision.Denied).code)
        assertEquals("NOT_CONFIRMED", (gate.authorizeCancel(OrderStage.PREPARING, "hmm what will it cost") as CancelDecision.Denied).code)
        assertTrue(gate.authorizeCancel(OrderStage.PREPARING, "haan cancel kar do") is CancelDecision.Allowed)
    }

    @Test fun `a changed order stage blocks cancelling`() {
        gate.prepareCancel(OrderStage.PREPARING, "cancel my order")
        now += 10_000
        assertEquals("CHANGED", (gate.authorizeCancel(OrderStage.ON_THE_WAY, "yes cancel it") as CancelDecision.Denied).code)
    }

    @Test fun `an old cancel request expires`() {
        gate.prepareCancel(OrderStage.PREPARING, "cancel my order")
        now += 4 * 60_000L
        assertEquals("EXPIRED", (gate.authorizeCancel(OrderStage.PREPARING, "yes cancel it") as CancelDecision.Denied).code)
    }
}

class BlinkitGuardsTest {
    @Test fun `pay and place order buttons cannot be tapped directly`() {
        assertEquals(BlinkitGuards.PURCHASE_MESSAGE, BlinkitGuards.genericClickBlock(true, BlinkitScreen.PAYMENT, "Pay ₹56", "", false))
        assertEquals(BlinkitGuards.PURCHASE_MESSAGE, BlinkitGuards.genericClickBlock(true, BlinkitScreen.CART, "place order", "", false))
        assertEquals(BlinkitGuards.PURCHASE_MESSAGE, BlinkitGuards.genericClickBlock(true, BlinkitScreen.PAYMENT, "", "confirm & pay", false))
    }

    @Test fun `on the payment screen a tap by position is blocked`() {
        assertEquals(BlinkitGuards.PURCHASE_MESSAGE, BlinkitGuards.genericClickBlock(true, BlinkitScreen.PAYMENT, "", "", true))
        assertNull(BlinkitGuards.genericClickBlock(true, BlinkitScreen.SEARCH_RESULTS, "", "", true))
    }

    @Test fun `cancel order buttons cannot be tapped directly`() {
        assertEquals(BlinkitGuards.CANCEL_MESSAGE, BlinkitGuards.genericClickBlock(true, BlinkitScreen.ORDER_TRACKING, "Cancel order", "", false))
    }

    @Test fun `ordinary taps and other apps are untouched`() {
        assertNull(BlinkitGuards.genericClickBlock(true, BlinkitScreen.HOME, "Search", "", false))
        assertNull(BlinkitGuards.genericClickBlock(false, null, "Pay now", "", false))
    }

    @Test fun `typing into login, OTP and PIN screens is blocked`() {
        assertEquals(BlinkitGuards.TYPING_MESSAGE, BlinkitGuards.genericTypeBlock(true, BlinkitScreen.SIGN_IN))
        assertEquals(BlinkitGuards.TYPING_MESSAGE, BlinkitGuards.genericTypeBlock(true, BlinkitScreen.CHALLENGE))
        assertNull(BlinkitGuards.genericTypeBlock(true, BlinkitScreen.HOME))
        assertNull(BlinkitGuards.genericTypeBlock(false, BlinkitScreen.SIGN_IN))
    }

    @Test fun `links never claim a deep link scheme`() {
        assertEquals("https://blinkit.com/s/?q=amul+milk", BlinkitLinks.searchUrl("amul milk"))
        assertTrue(BlinkitLinks.isBlinkitPackage("com.grofers.customerapp"))
        assertFalse(BlinkitLinks.isBlinkitPackage("com.rapido.passenger"))
    }
}
