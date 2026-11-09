package com.jarvis.assistant.automation.blinkit

import com.jarvis.assistant.automation.blinkit.BScreens.node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlinkitScreenReaderTest {

    private val taaza = Item("Amul Taaza Toned Milk", "500 ml", 29, 30, "4.5 (50k)")
    private val gold = Item("Amul Gold Full Cream Milk", "1 L", 68, 70, "4.7 (10.2k)")
    private val gone = Item("Mother Dairy Cow Milk", "500 ml", 33, 33, null, inStock = false)

    // ---- Screen classification ----------------------------------------------------------------

    @Test fun `classifies the main screens`() {
        assertEquals(BlinkitScreen.HOME, BlinkitScreenReader.classify(BScreens.home()))
        assertEquals(BlinkitScreen.SEARCH_RESULTS, BlinkitScreenReader.classify(BScreens.results(listOf(taaza, gold), emptyMap())))
        assertEquals(BlinkitScreen.CART, BlinkitScreenReader.classify(BScreens.cart(listOf(taaza), mapOf(taaza.name to 1))))
        assertEquals(BlinkitScreen.ADDRESS_PICKER, BlinkitScreenReader.classify(BScreens.picker(listOf("Home" to "Flat 12, MG Road"))))
        assertEquals(BlinkitScreen.PAYMENT, BlinkitScreenReader.classify(BScreens.payment("Flat 12", 56, 29, listOf("Delivery charge" to "₹25"))))
        assertEquals(BlinkitScreen.ORDER_TRACKING, BlinkitScreenReader.classify(BScreens.tracking()))
        assertEquals(BlinkitScreen.ORDERS_LIST, BlinkitScreenReader.classify(BScreens.ordersList()))
    }

    @Test fun `classifies the states only the user can fix`() {
        assertEquals(BlinkitScreen.SIGN_IN, BlinkitScreenReader.classify(BScreens.login()))
        assertEquals(BlinkitScreen.CHALLENGE, BlinkitScreenReader.classify(BScreens.otp()))
        assertEquals(BlinkitScreen.CHALLENGE, BlinkitScreenReader.classify(BScreens.upiPin()))
        assertEquals(BlinkitScreen.NOT_SERVICEABLE, BlinkitScreenReader.classify(BScreens.notServiceable()))
        assertEquals(BlinkitScreen.STORE_CLOSED, BlinkitScreenReader.classify(BScreens.storeClosed()))
    }

    @Test fun `classifies loading, offline and error pages`() {
        assertEquals(BlinkitScreen.LOADING, BlinkitScreenReader.classify(BScreens.loading()))
        assertEquals(BlinkitScreen.OFFLINE, BlinkitScreenReader.classify(BScreens.offline()))
        assertEquals(BlinkitScreen.ERROR, BlinkitScreenReader.classify(BScreens.error()))
    }

    @Test fun `an unrecognisable screen is unknown, not guessed`() {
        val odd = listOf(node("Lorem ipsum dolor"), node("Sit amet consectetur"), node("Adipiscing elit sed"), node("Do eiusmod tempor"))
        assertEquals(BlinkitScreen.UNKNOWN, BlinkitScreenReader.classify(odd))
    }

    @Test fun `a plain Proceed button is never mistaken for the pay button`() {
        val cart = BScreens.cart(listOf(taaza), mapOf(taaza.name to 1))
        assertNull(BlinkitScreenReader.finalPayButton(cart))
        assertNotNull(BlinkitScreenReader.proceedButton(cart))
    }

    // ---- Products -----------------------------------------------------------------------------

    @Test fun `reads product cards from a grid in document order`() {
        val products = BlinkitScreenReader.extractProducts(BScreens.results(listOf(taaza, gold, gone), emptyMap()))
        assertEquals(3, products.size)
        val t = products[0]
        assertEquals("Amul Taaza Toned Milk", t.name)
        assertEquals("500 ml", t.packText)
        assertEquals(29.0, t.price!!, 0.0)
        assertEquals(30.0, t.mrp!!, 0.0)
        assertEquals(4.5, t.rating!!, 0.0)
        assertEquals(50_000, t.ratingCount)
        assertEquals("8 MINS", t.etaText)
        assertTrue(t.available)
        assertEquals(58.0, t.unitPrice!!, 0.001)
        assertEquals(10_200, products[1].ratingCount)
    }

    @Test fun `out of stock cards are marked unavailable`() {
        val products = BlinkitScreenReader.extractProducts(BScreens.results(listOf(taaza, gone), emptyMap()))
        assertTrue(products[0].available)
        assertFalse(products[1].available)
    }

    @Test fun `a card already in the cart shows its quantity`() {
        val products = BlinkitScreenReader.extractProducts(BScreens.results(listOf(taaza, gold), mapOf(gold.name to 2)))
        assertEquals(0, products[0].inCartQty)
        assertEquals(2, products[1].inCartQty)
    }

    @Test fun `a whole card in one node is read too`() {
        BScreens.reset()
        val nodes = listOf(
            node("Search \"milk\"", editable = true), node("Showing results for milk"),
            node("Amul Taaza Toned Milk 500 ml, ₹29, MRP ₹30, 4.5 stars"), node("ADD", clickable = true),
            node("Amul Gold Full Cream Milk 1 L, ₹68, MRP ₹70"), node("ADD", clickable = true)
        )
        val products = BlinkitScreenReader.extractProducts(nodes)
        assertEquals(2, products.size)
        assertEquals("Amul Taaza Toned Milk 500 ml", products[0].name)
        assertEquals(29.0, products[0].price!!, 0.0)
        assertEquals(500.0, products[0].pack!!.baseAmount, 0.0)
    }

    @Test fun `a card with several pack options is flagged`() {
        BScreens.reset()
        val nodes = listOf(
            node("Search \"atta\"", editable = true), node("Showing results for atta"),
            node("Aashirvaad Whole Wheat Atta"), node("₹265"), node("2 options", clickable = true),
            node("Fortune Chakki Atta"), node("5 kg"), node("₹240"), node("ADD", clickable = true)
        )
        val products = BlinkitScreenReader.extractProducts(nodes)
        assertTrue(products[0].hasOptions)
        assertFalse(products[1].hasOptions)
    }

    @Test fun `locates a card by name pack and price`() {
        val screen = BScreens.results(listOf(taaza, gold), emptyMap())
        val card = BlinkitScreenReader.locateCard(screen, BlinkitScreenReader.extractProducts(screen)[1])
        assertNotNull(card)
        assertEquals(Closer.ADD, card!!.closer)
        assertEquals("add:${gold.name}", card.closerNode!!.description)
    }

    // ---- Cart and bill ------------------------------------------------------------------------

    @Test fun `reads cart rows, quantities and the whole bill`() {
        val cart = BlinkitScreenReader.extractCart(BScreens.cart(listOf(taaza, gold), mapOf(taaza.name to 2, gold.name to 1)))
        assertEquals(2, cart.lines.size)
        assertEquals(2, cart.lines.first { it.name.contains("Taaza") }.quantity)
        assertEquals(126.0, cart.bill.itemTotal!!, 0.0)
        assertEquals(listOf("Delivery charge" to 25.0, "Handling charge" to 2.0), cart.bill.fees)
        assertEquals(153.0, cart.bill.total!!, 0.0)
        assertEquals(27.0, cart.bill.feeTotal, 0.0)
    }

    @Test fun `free delivery reads as zero, not as missing`() {
        val cart = BlinkitScreenReader.extractCart(BScreens.cart(listOf(taaza), mapOf(taaza.name to 1), fees = listOf("Delivery charge" to "FREE", "Handling charge" to "₹2")))
        assertEquals(0.0, cart.bill.fees.first { it.first.startsWith("Delivery") }.second, 0.0)
        assertEquals(31.0, cart.bill.total!!, 0.0)
    }

    @Test fun `small cart and surge charges are visible fees`() {
        val cart = BlinkitScreenReader.extractCart(BScreens.cart(listOf(taaza), mapOf(taaza.name to 1),
            fees = listOf("Delivery charge" to "₹25", "Small cart charge" to "₹20", "Surge charge" to "₹15")))
        assertEquals(listOf("Delivery charge", "Small cart charge", "Surge charge"), cart.bill.fees.map { it.first })
        assertEquals(60.0, cart.bill.feeTotal, 0.0)
    }

    @Test fun `a coupon discount is read from the bill`() {
        val cart = BlinkitScreenReader.extractCart(BScreens.cart(listOf(taaza), mapOf(taaza.name to 1), discount = 10))
        assertEquals(1, cart.bill.discounts.size)
        assertEquals(10.0, Math.abs(cart.bill.discounts[0].second), 0.0)
        assertEquals(46.0, cart.bill.total!!, 0.0)
    }

    @Test fun `recommendation cards below the bill are not cart items`() {
        val cart = BlinkitScreenReader.extractCart(BScreens.cart(listOf(taaza), mapOf(taaza.name to 1), recommended = listOf(gold)))
        assertEquals(1, cart.lines.size)
        assertEquals("Amul Taaza Toned Milk", cart.lines[0].name)
    }

    @Test fun `an unavailable cart row is reported as unavailable`() {
        val cart = BlinkitScreenReader.extractCart(BScreens.cart(listOf(taaza, gone), mapOf(taaza.name to 1), unavailable = setOf(gone.name)))
        assertTrue(cart.lines.any { it.name.contains("Mother Dairy") && it.unavailable })
        assertFalse(cart.lines.first { it.name.contains("Taaza") }.unavailable)
    }

    @Test fun `minimum order and free delivery gaps are read`() {
        val min = BlinkitScreenReader.extractBill(BScreens.cart(listOf(taaza), mapOf(taaza.name to 1), note = "Add ₹70 more to place your order. Minimum order value is ₹99"))
        assertEquals(70.0, min.minOrderGap!!, 0.0)
        val free = BlinkitScreenReader.extractBill(BScreens.cart(listOf(taaza), mapOf(taaza.name to 1), note = "Add ₹120 more for free delivery"))
        assertEquals(120.0, free.freeDeliveryGap!!, 0.0)
        assertNull(free.minOrderGap)
    }

    @Test fun `an empty cart is recognised`() {
        BScreens.reset()
        val cart = BlinkitScreenReader.extractCart(listOf(node("My Cart"), node("Your cart is empty"), node("Browse products", clickable = true)))
        assertTrue(cart.empty)
    }

    @Test fun `login to proceed is its own button`() {
        val screen = BScreens.cart(listOf(taaza), mapOf(taaza.name to 1), loggedIn = false)
        assertNotNull(BlinkitScreenReader.loginToProceedButton(screen))
        assertNull(BlinkitScreenReader.proceedButton(screen))
    }

    // ---- Payment, address, orders -------------------------------------------------------------

    @Test fun `payment screen summary has total, address and payment method`() {
        val screen = BScreens.payment("Flat 12, MG Road, Hyderabad", 56, 29, listOf("Delivery charge" to "₹25", "Handling charge" to "₹2"))
        val carried = listOf(CartLine("Amul Taaza Toned Milk", "500 ml", 29.0, 1))
        val s = BlinkitScreenReader.extractPayment(screen, carried)
        assertTrue(s.readable)
        assertEquals(56.0, s.total!!, 0.0)
        assertEquals("UPI - Google Pay", s.paymentLabel)
        assertTrue(s.addressLabel!!.contains("MG Road"))
        assertNotNull(BlinkitScreenReader.finalPayButton(screen))
    }

    @Test fun `fingerprint changes with total, address, payment and items`() {
        val base = CheckoutSummary(listOf(CartLine("Milk", "500 ml", 29.0, 1)), BillDetails(29.0, emptyList(), emptyList(), 56.0, null, null), "Home, MG Road", "UPI", null)
        assertEquals(base.fingerprint(), base.copy(deliveryEstimate = "8 mins").fingerprint())
        assertFalse(base.fingerprint() == base.copy(bill = base.bill.copy(total = 60.0)).fingerprint())
        assertFalse(base.fingerprint() == base.copy(addressLabel = "Work, HITEC City").fingerprint())
        assertFalse(base.fingerprint() == base.copy(paymentLabel = "Card").fingerprint())
        assertFalse(base.fingerprint() == base.copy(items = listOf(CartLine("Milk", "500 ml", 29.0, 2))).fingerprint())
    }

    @Test fun `reads the delivery address and estimate from the home screen`() {
        val home = BScreens.home()
        assertEquals("HOME - Flat 12, MG Road, Hyderabad", BlinkitScreenReader.address(home))
        assertEquals("Delivery in 8 minutes", BlinkitScreenReader.eta(home))
    }

    @Test fun `reads saved addresses with their tap targets`() {
        val entries = BlinkitScreenReader.savedAddresses(BScreens.picker(listOf("Home" to "Flat 12, MG Road, Hyderabad", "Work" to "Tower B, HITEC City, Hyderabad")))
        assertEquals(2, entries.size)
        assertEquals("Home", entries[0].label)
        assertTrue(entries[0].text.contains("MG Road"))
        assertNotNull(entries[0].node)
        assertEquals("Work", entries[1].label)
    }

    @Test fun `order stages are read from the tracking text`() {
        assertEquals(OrderStage.PLACED, BlinkitScreenReader.orderStatus(BScreens.tracking("Order placed", null)).stage)
        assertEquals(OrderStage.PREPARING, BlinkitScreenReader.orderStatus(BScreens.tracking("Preparing your order", "Arriving in 9 mins")).stage.let { if (it == OrderStage.ON_THE_WAY) OrderStage.PREPARING else it })
        assertEquals(OrderStage.CANCELLED, BlinkitScreenReader.orderStatus(BScreens.tracking("Order cancelled", null)).stage)
        assertEquals(OrderStage.DELIVERED, BlinkitScreenReader.orderStatus(BScreens.tracking("Order delivered", null)).stage)
    }

    @Test fun `order identifier is extracted from the tracking screen`() {
        val status = BlinkitScreenReader.orderStatus(
            BScreens.tracking("Order placed", null, extra = listOf("Order ID: BLK-123456"))
        )
        assertEquals("BLK-123456", status.orderId)
    }

    @Test fun `delivery partner, estimate and delay are read, never invented`() {
        val st = BlinkitScreenReader.orderStatus(BScreens.tracking("Rahul Kumar is on the way", "Arriving in 6 mins", extra = listOf("Sorry, your order is running late")))
        assertEquals(OrderStage.ON_THE_WAY, st.stage)
        assertEquals("6 mins", st.etaText)
        assertEquals("Rahul Kumar", st.partnerName)
        assertNotNull(st.delayNotice)
    }

    @Test fun `delivered is not claimed while the screen still says arriving`() {
        val st = BlinkitScreenReader.orderStatus(BScreens.tracking("Order delivered", "Arriving in 4 mins"))
        assertFalse(st.stage == OrderStage.DELIVERED)
    }

    @Test fun `an unreadable tracking screen has an unknown stage`() {
        assertEquals(OrderStage.UNKNOWN, BlinkitScreenReader.orderStatus(listOf(node("Help center"), node("FAQs"), node("Contact"))).stage)
    }

    @Test fun `cancel and help offers are read from the buttons`() {
        assertTrue(BlinkitScreenReader.orderStatus(BScreens.tracking(cancel = true)).cancelOffered)
        assertFalse(BlinkitScreenReader.orderStatus(BScreens.tracking(cancel = false)).cancelOffered)
        assertTrue(BlinkitScreenReader.orderStatus(BScreens.tracking(help = true)).helpOffered)
    }

    @Test fun `missing or replaced item notices are surfaced`() {
        val st = BlinkitScreenReader.orderStatus(BScreens.tracking("Preparing your order", null, extra = listOf("Britannia Bread is out of stock and was removed from your order")))
        assertEquals(1, st.itemNotices.size)
    }

    @Test fun `order history rows are summarised`() {
        val rows = BlinkitScreenReader.orderSummaries(BScreens.ordersList())
        assertEquals(2, rows.size)
        assertTrue(rows[0].contains("₹182"))
    }
}
