package com.jarvis.assistant.automation.blinkit

import com.jarvis.assistant.automation.rapido.LocationAccess
import com.jarvis.assistant.automation.rapido.LocationState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drives the real agent against a simulated Blinkit app (screens, taps, loading, errors, login, payment app). */
class BlinkitAgentFlowTest {

    private val catalog = listOf(
        Item("Amul Taaza Toned Milk", "500 ml", 29, 30, "4.5 (50k)"),
        Item("Amul Gold Full Cream Milk", "1 L", 68, 70, "4.7 (10.2k)"),
        Item("Mother Dairy Cow Milk", "500 ml", 33, 33, "4.3 (2k)", inStock = false),
        Item("Surf Excel Easy Wash Detergent Powder", "1 kg", 135, 150, "4.5 (20k)"),
        Item("Ariel Matic Detergent Powder", "2 kg", 380, 420, "4.6 (9k)"),
        Item("Wheel Active Detergent Powder", "500 g", 32, 35, "4.0 (3k)"),
        Item("Britannia Brown Bread", "400 g", 55, 55, "4.4 (8k)"),
        Item("Harvest Gold White Bread", "400 g", 40, 42, "4.0 (6k)")
    )

    private fun world() = BlinkitWorld(catalog)
    private fun BlinkitWorld.clicked(label: String) = phone.log.count { it == "click:$label" }
    private fun BlinkitWorld.typed() = phone.log.any { it.startsWith("type:") }

    private fun BlinkitWorld.cartWith(vararg items: Pair<String, Int>) { items.forEach { cart[it.first] = it.second }; render() }

    private fun BlinkitWorld.prepare(utterance: String = "checkout karo"): BlinkitResult = runBlocking {
        at = BlinkitWorld.At.CART; render()
        agent.prepareCheckout(utterance)
    }

    // ---- Search and value ---------------------------------------------------------------------

    @Test fun `search reads real products and reports unit prices and best value`() = runBlocking {
        val w = world()
        val r = w.agent.search(BlinkitQueryParser.parse("milk"))
        assertTrue(r.message, r.success && r.verified)
        assertTrue(r.message.contains("Amul Taaza Toned Milk"))
        assertTrue(r.message.contains("₹58/L"))        // derived: 29 / 0.5 L
        assertTrue(r.message.contains("Best value"))
        assertTrue(r.message.contains("calculated"))    // says which numbers are derived
        assertTrue(w.phone.log.any { it.startsWith("launch:https://blinkit.com/s/?q=milk") })
    }

    @Test fun `owner example - 200 rupee detergent search ranks by value inside the budget`() = runBlocking {
        val w = world()
        val r = w.agent.search(BlinkitQueryParser.parse("₹200 ke andar best value detergent"))
        assertTrue(r.message, r.success)
        assertFalse("over-budget Ariel must be excluded", r.message.contains("Ariel"))
        assertTrue(r.message.contains("Best value: Wheel Active"))
        assertTrue(r.message.contains("Surf Excel"))
    }

    @Test fun `nothing within budget says so and names the cheapest real option`() = runBlocking {
        val w = world()
        val r = w.agent.search(BlinkitQueryParser.parse("detergent under 20"))
        assertEquals(BlinkitStatus.NOT_FOUND, r.status)
        assertTrue(r.message.contains("none matched your limits"))
        assertTrue(r.message.contains("Wheel Active"))
    }

    @Test fun `out of stock items are reported with in-stock alternatives`() = runBlocking {
        val w = world()
        val r = w.agent.search(BlinkitQueryParser.parse("mother dairy milk"))
        assertTrue(r.message, r.message.contains("out of stock") || r.message.contains("OUT OF STOCK"))
        assertTrue(r.message.contains("alternatives"))
    }

    @Test fun `no search results is an honest not found`() = runBlocking {
        val w = world()
        val r = w.agent.search(BlinkitRequest("zzzz"))
        assertEquals(BlinkitStatus.NOT_FOUND, r.status)
    }

    @Test fun `when the link is ignored the agent types the search itself`() = runBlocking {
        val w = world()
        w.linkWorks = false
        val r = w.agent.search(BlinkitQueryParser.parse("milk"))
        assertTrue(r.message, r.success)
        assertTrue(w.phone.log.contains("type:milk"))
        assertTrue(r.message.contains("Amul Taaza"))
    }

    @Test fun `typing failure is reported, not faked`() = runBlocking {
        val w = world()
        w.linkWorks = false
        w.phone.typingWorks = false
        val r = w.agent.search(BlinkitQueryParser.parse("milk"))
        assertFalse(r.success)
        assertTrue(r.status == BlinkitStatus.TIMEOUT || r.status == BlinkitStatus.NOT_FOUND || r.status == BlinkitStatus.ERROR)
    }

    @Test fun `a vague ask runs a few real searches and suggests in-stock items`() = runBlocking {
        val w = world()
        val r = w.agent.search(BlinkitQueryParser.parse("something for breakfast"))
        assertTrue(r.message, r.success)
        assertTrue(r.message.contains("breakfast"))
        assertTrue(r.message.contains("bread"))
        assertTrue(r.message.contains("Britannia") || r.message.contains("Harvest"))
    }

    @Test fun `compare names best value, lowest price and lowest unit price`() = runBlocking {
        val w = world()
        w.agent.search(BlinkitQueryParser.parse("detergent"))
        val r = w.agent.compare(emptyList())
        assertTrue(r.message, r.success)
        assertTrue(r.message.contains("Best value"))
        assertTrue(r.message.contains("Lowest unit price"))
        assertTrue(r.message.contains("Lowest price"))
    }

    @Test fun `payment method changes only when a visible option matches and refreshed summary is verified`() = runBlocking {
        val w = world()
        w.cartWith("Amul Taaza Toned Milk" to 2)
        val prepared = w.prepare()
        assertEquals(BlinkitStatus.PREPARED, prepared.status)
        val selected = w.agent.selectPaymentMethod("PhonePe", "choose PhonePe")
        assertTrue(selected.message, selected.success && selected.verified)
        assertEquals(BlinkitStatus.PREPARED, selected.status)
        assertEquals("PhonePe", w.paymentMethod)
        assertTrue(selected.message.contains("PhonePe"))
        assertTrue(selected.message.contains("Order NOT placed"))
        assertEquals(0, w.payClicks)
    }

    @Test fun `payment method selection refuses when no matching visible option exists`() = runBlocking {
        val w = world()
        w.cartWith("Amul Taaza Toned Milk" to 1)
        w.prepare()
        val selected = w.agent.selectPaymentMethod("Bitcoin", "choose Bitcoin")
        assertFalse(selected.success)
        assertEquals(BlinkitStatus.NOT_FOUND, selected.status)
        assertEquals("UPI - Google Pay", w.paymentMethod)
        assertEquals(0, w.payClicks)
    }

    // ---- Serviceability, login, recovery ------------------------------------------------------

    @Test fun `an address Blinkit does not serve is reported and the address is not changed`() = runBlocking {
        val w = world()
        w.phone.launchHandler = { w.phone.screen = BScreens.notServiceable() }
        val r = w.agent.search(BlinkitQueryParser.parse("milk"))
        assertEquals(BlinkitStatus.NOT_SERVICEABLE, r.status)
        assertTrue(r.message.contains("did not change the address"))
        assertEquals("HOME - Flat 12, MG Road, Hyderabad", w.address)
    }

    @Test fun `a closed store is reported`() = runBlocking {
        val w = world()
        w.phone.launchHandler = { w.phone.screen = BScreens.storeClosed() }
        assertEquals(BlinkitStatus.STORE_CLOSED, w.agent.search(BlinkitQueryParser.parse("milk")).status)
    }

    @Test fun `login screen is handed to the user and nothing is typed`() = runBlocking {
        val w = world()
        w.phone.launchHandler = { w.phone.screen = BScreens.login() }
        val r = w.agent.search(BlinkitQueryParser.parse("milk"))
        assertEquals(BlinkitStatus.NEEDS_SIGN_IN, r.status)
        assertFalse(w.typed())
    }

    @Test fun `an OTP screen is handed to the user and nothing is typed`() = runBlocking {
        val w = world()
        w.phone.launchHandler = { w.phone.screen = BScreens.otp() }
        val r = w.agent.search(BlinkitQueryParser.parse("milk"))
        assertEquals(BlinkitStatus.NEEDS_USER, r.status)
        assertFalse(w.typed())
    }

    @Test fun `an offline page is retried with Try again and then succeeds`() = runBlocking {
        val w = world()
        w.phone.launchHandler = { w.at = BlinkitWorld.At.RESULTS; w.render(); w.phone.oneShot.add(BScreens.offline()) }
        val r = w.agent.search(BlinkitQueryParser.parse("milk"))
        assertTrue(r.message, r.success)
        assertTrue(w.phone.log.contains("click:Try again"))
    }

    @Test fun `a page that stays broken ends honestly after bounded retries`() = runBlocking {
        val w = world()
        w.phone.launchHandler = { w.phone.screen = BScreens.error() }
        w.phone.clickHandler = { w.phone.screen = BScreens.error() }
        val r = w.agent.search(BlinkitQueryParser.parse("milk"))
        assertFalse(r.success)
        assertTrue(w.clicked("Try again") <= 3)
    }

    @Test fun `unreadable screen is reported with the accessibility hint`() = runBlocking {
        val w = world()
        w.phone.unreadable = true
        val r = w.agent.search(BlinkitQueryParser.parse("milk"))
        assertEquals(BlinkitStatus.SCREEN_UNREADABLE, r.status)
    }

    @Test fun `missing app is reported`() = runBlocking {
        val w = world()
        w.phone.installed = false
        w.phone.launchWorks = false
        val r = w.agent.search(BlinkitQueryParser.parse("milk"))
        assertEquals(BlinkitStatus.LAUNCH_FAILED, r.status)
        assertTrue(r.message.contains("not installed"))
    }

    @Test fun `the user can stop a running search`() = runBlocking {
        val w = world()
        w.phone.cancelFlag = true
        assertEquals(BlinkitStatus.CANCELLED, w.agent.search(BlinkitQueryParser.parse("milk")).status)
    }

    // ---- Cart ---------------------------------------------------------------------------------

    @Test fun `add to cart is verified by reading the cart`() = runBlocking {
        val w = world()
        w.agent.search(BlinkitQueryParser.parse("milk"))
        val r = w.agent.cartAction("add", 1, null, 1, null, null)
        assertTrue(r.message, r.success && r.verified && r.status == BlinkitStatus.OK)
        assertEquals(1, w.cart["Amul Taaza Toned Milk"])
        assertTrue(r.message.startsWith("Verified in the cart"))
    }

    @Test fun `quantity three taps ADD once and plus twice, and is proven in the cart`() = runBlocking {
        val w = world()
        w.agent.search(BlinkitQueryParser.parse("milk"))
        val r = w.agent.cartAction("add", 1, null, 3, null, null)
        assertTrue(r.message, r.success && r.verified)
        assertEquals(3, w.cart["Amul Taaza Toned Milk"])
        assertEquals(1, w.clicked("ADD"))
        assertEquals(2, w.clicked("+"))
    }

    @Test fun `quantity mismatch in cart is not reported as verified success`() = runBlocking {
        val w = world()
        w.phone.incrementWorks = false
        w.agent.search(BlinkitQueryParser.parse("milk"))
        val result = w.agent.cartAction("add", 1, null, 3, null, null)

        assertFalse(result.success)
        assertFalse(result.verified)
        assertEquals(BlinkitStatus.NOT_VERIFIED, result.status)
        assertEquals(1, w.cart["Amul Taaza Toned Milk"])
        assertTrue(result.message.contains("quantity is 1, not 3"))
    }

    @Test fun `asking twice never doubles the item`() = runBlocking {
        val w = world()
        w.agent.search(BlinkitQueryParser.parse("milk"))
        w.agent.cartAction("add", 1, null, 1, null, null)
        val again = w.agent.cartAction("add", 1, null, 1, null, null)
        assertTrue(again.message, again.success)
        assertTrue(again.message.contains("Already in the cart"))
        assertEquals(1, w.cart["Amul Taaza Toned Milk"])
    }

    @Test fun `an ADD tap that does nothing is never reported as added`() = runBlocking {
        val w = world()
        w.addWorks = false
        w.agent.search(BlinkitQueryParser.parse("milk"))
        val r = w.agent.cartAction("add", 1, null, 1, null, null)
        assertFalse(r.success)
        assertEquals(BlinkitStatus.NOT_VERIFIED, r.status)
        assertTrue(w.cart.isEmpty())
    }

    @Test fun `adding while logged out hands login to the user`() = runBlocking {
        val w = world()
        w.loggedIn = false
        w.agent.search(BlinkitQueryParser.parse("milk"))
        val r = w.agent.cartAction("add", 1, null, 1, null, null)
        assertEquals(BlinkitStatus.NEEDS_SIGN_IN, r.status)
        assertFalse(w.typed())
    }

    @Test fun `an out of stock product is not added and alternatives are offered`() = runBlocking {
        val w = world()
        w.agent.search(BlinkitQueryParser.parse("milk"))
        // The out-of-stock card sorts last in the ranked results (third of three milk products).
        val r = w.agent.cartAction("add", 3, null, 1, null, null)
        assertEquals(BlinkitStatus.UNAVAILABLE, r.status)
        assertTrue(w.cart.isEmpty())
    }

    @Test fun `view cart shows rows, every visible fee and the payable amount`() = runBlocking {
        val w = world()
        w.cartWith("Amul Taaza Toned Milk" to 2)
        val r = w.agent.cartAction("view", null, null, null, null, null)
        assertTrue(r.message, r.success && r.verified)
        assertTrue(r.message.contains("Delivery charge: ₹25"))
        assertTrue(r.message.contains("Handling charge: ₹2"))
        assertTrue(r.message.contains("To pay: ₹85"))
        assertTrue(r.message.contains("× 2"))
    }

    @Test fun `remove is verified by the item leaving the cart`() = runBlocking {
        val w = world()
        w.cartWith("Amul Taaza Toned Milk" to 2, "Britannia Brown Bread" to 1)
        val r = w.agent.cartAction("remove", null, "bread", null, null, null)
        assertTrue(r.message, r.success && r.verified)
        assertFalse(w.cart.containsKey("Britannia Brown Bread"))
        assertEquals(2, w.cart["Amul Taaza Toned Milk"])
    }

    @Test fun `set quantity is verified in the cart`() = runBlocking {
        val w = world()
        w.cartWith("Amul Taaza Toned Milk" to 1)
        val r = w.agent.cartAction("set_quantity", null, "taaza", 4, null, null)
        assertTrue(r.message, r.success && r.verified)
        assertEquals(4, w.cart["Amul Taaza Toned Milk"])
        val down = w.agent.cartAction("set_quantity", null, "taaza", 2, null, null)
        assertTrue(down.message, down.success)
        assertEquals(2, w.cart["Amul Taaza Toned Milk"])
    }

    @Test fun `an ambiguous item name is not guessed`() = runBlocking {
        val w = world()
        w.cartWith("Amul Taaza Toned Milk" to 1, "Amul Gold Full Cream Milk" to 1)
        val r = w.agent.cartAction("remove", null, "amul", null, null, null)
        assertEquals(BlinkitStatus.AMBIGUOUS, r.status)
        assertEquals(2, w.cart.size)
    }

    // ---- Budget plan --------------------------------------------------------------------------

    @Test fun `budget plan picks real products inside the total and can add them to the cart`() = runBlocking {
        val w = world()
        val list = BlinkitQueryParser.parseShoppingList("milk, bread total 120 ke andar")
        val plan = w.agent.planBudget(list, 0.0)
        assertTrue(plan.message, plan.success && plan.verified)
        assertTrue(plan.message.contains("Items total"))
        assertTrue(plan.message.contains("Delivery, handling and small-cart fees are not included"))
        val added = w.agent.cartAction("add_plan", null, null, null, null, null)
        assertTrue(added.message, added.success)
        assertEquals(2, w.cart.size)
        assertTrue(w.itemTotal() <= 120)
    }

    @Test fun `a plan that cannot fit says how far over it is`() = runBlocking {
        val w = world()
        val plan = w.agent.planBudget(BlinkitQueryParser.parseShoppingList("milk, bread total 50 ke andar"), 0.0)
        assertEquals(BlinkitStatus.OVER_BUDGET, plan.status)
        assertTrue(plan.message.contains("over"))
    }

    // ---- Checkout and the confirmed order -----------------------------------------------------

    @Test fun `prepare checkout stops at payment and reads back items, fees, total and address`() {
        val w = world()
        w.cartWith("Amul Taaza Toned Milk" to 2)
        val r = w.prepare()
        assertEquals(r.message, BlinkitStatus.PREPARED, r.status)
        assertTrue(r.message.contains("Order NOT placed"))
        assertTrue(r.message.contains("Delivery charge: ₹25"))
        assertTrue(r.message.contains("To pay: ₹85"))
        assertTrue(r.message.contains("MG Road"))
        assertTrue(r.message.contains("UPI"))
        assertEquals(0, w.payClicks)
    }

    @Test fun `one sentence can never prepare and place an order`() = runBlocking {
        val w = world()
        w.cartWith("Amul Taaza Toned Milk" to 1)
        w.prepare("yes place the order")
        w.phone.letTimePass(10_000)
        val r = w.agent.placeOrder("yes place the order")
        assertEquals(BlinkitStatus.REFUSED, r.status)
        assertEquals(0, w.payClicks)
    }

    @Test fun `ordering without a prepared checkout is refused`() = runBlocking {
        val w = world()
        w.cartWith("Amul Taaza Toned Milk" to 1)
        w.at = BlinkitWorld.At.PAYMENT; w.render()
        val r = w.agent.placeOrder("haan order kar do")
        assertEquals(BlinkitStatus.REFUSED, r.status)
        assertEquals(0, w.payClicks)
    }

    @Test fun `a vague reply does not place the order`() = runBlocking {
        val w = world()
        w.cartWith("Amul Taaza Toned Milk" to 1)
        w.prepare()
        w.phone.letTimePass(10_000)
        assertEquals(BlinkitStatus.REFUSED, w.agent.placeOrder("ok").status)
        assertEquals(0, w.payClicks)
    }

    @Test fun `confirmed order is placed once and verified by the order screen`() = runBlocking {
        val w = world()
        w.cartWith("Amul Taaza Toned Milk" to 2)
        w.prepare("checkout karo")
        w.phone.letTimePass(10_000)
        val r = w.agent.placeOrder("haan order place kar do")
        assertTrue(r.message, r.success && r.verified && r.status == BlinkitStatus.PLACED)
        assertEquals(1, w.payClicks)
        assertTrue(r.message.contains("₹85"))
    }

    @Test fun `a second order command cannot place the same order again`() = runBlocking {
        val w = world()
        w.cartWith("Amul Taaza Toned Milk" to 1)
        w.prepare()
        w.phone.letTimePass(10_000)
        w.agent.placeOrder("haan order kar do")
        w.phone.letTimePass(5_000)
        val again = w.agent.placeOrder("haan order kar do")
        assertFalse(again.success)
        assertEquals(1, w.payClicks)
    }

    @Test fun `if the result is uncertain the same checkout is never submitted twice`() = runBlocking {
        val w = world()
        w.cartWith("Amul Taaza Toned Milk" to 1)
        w.prepare()
        w.phone.letTimePass(10_000)
        // Pay is tapped but Blinkit shows neither a confirmation nor a payment app (a hung screen).
        val original = w.phone.clickHandler
        w.phone.clickHandler = { n -> if (n.label.startsWith("Pay ₹")) { w.payClicks++ } else original(n) }
        val first = w.agent.placeOrder("haan order kar do")
        assertEquals(BlinkitStatus.NOT_VERIFIED, first.status)
        assertTrue(first.message.contains("I cannot say it was placed"))
        // Back on the payment screen, prepare and confirm again: the ledger blocks it.
        w.prepare("phir se checkout")
        w.phone.letTimePass(10_000)
        val second = w.agent.placeOrder("haan order kar do")
        assertEquals(BlinkitStatus.REFUSED, second.status)
        assertEquals("DUPLICATE", second.extra["code"])
        assertEquals(1, w.payClicks)
    }

    @Test fun `a payment app opening is handed to the user and never called a success`() = runBlocking {
        val w = world()
        w.paymentAppOpens = true
        w.cartWith("Amul Taaza Toned Milk" to 1)
        w.prepare()
        w.phone.letTimePass(10_000)
        val r = w.agent.placeOrder("haan order kar do")
        assertEquals(BlinkitStatus.NEEDS_PAYMENT_AUTH, r.status)
        assertFalse(r.success)
        assertTrue(r.message.contains("NOT confirmed"))
    }

    @Test fun `a price change after read back blocks the order and asks again`() = runBlocking {
        val w = world()
        w.cartWith("Amul Taaza Toned Milk" to 1)
        w.prepare()
        w.phone.letTimePass(10_000)
        w.fees = listOf("Delivery charge" to "₹25", "Handling charge" to "₹2", "Surge charge" to "₹30")
        w.render()
        val r = w.agent.placeOrder("haan order kar do")
        assertEquals(BlinkitStatus.REFUSED, r.status)
        assertEquals("CHANGED", r.extra["code"])
        assertTrue(r.message.contains("New total"))
        assertEquals(0, w.payClicks)
    }

    @Test fun `an expired read back must be redone`() = runBlocking {
        val w = world()
        w.cartWith("Amul Taaza Toned Milk" to 1)
        w.prepare()
        w.phone.letTimePass(9 * 60_000L)
        assertEquals("EXPIRED", w.agent.placeOrder("haan order kar do").extra["code"])
        assertEquals(0, w.payClicks)
    }

    @Test fun `empty cart has nothing to check out`() {
        val w = world()
        w.at = BlinkitWorld.At.CART; w.render()
        val r = runBlocking { w.agent.prepareCheckout("checkout") }
        assertTrue(r.status == BlinkitStatus.NOT_FOUND)
        assertEquals(0, w.payClicks)
    }

    @Test fun `below the minimum order the agent stops and says how much to add`() {
        val w = world()
        w.cartNote = "Add ₹70 more to place your order. Minimum order value is ₹99"
        w.cartWith("Amul Taaza Toned Milk" to 1)
        val r = w.prepare()
        assertEquals(BlinkitStatus.MIN_ORDER, r.status)
        assertTrue(r.message.contains("₹70"))
        assertEquals(0, w.clicked("Proceed"))
    }

    @Test fun `login to proceed is handed to the user`() {
        val w = world()
        w.loggedIn = false
        w.cartWith("Amul Taaza Toned Milk" to 1)
        val r = w.prepare()
        assertEquals(BlinkitStatus.NEEDS_SIGN_IN, r.status)
        assertFalse(w.typed())
    }

    @Test fun `an unavailable cart item stops checkout`() {
        val w = world()
        w.unavailableInCart = setOf("Mother Dairy Cow Milk")
        w.cartWith("Amul Taaza Toned Milk" to 1)
        val r = w.prepare()
        assertEquals(r.message, BlinkitStatus.UNAVAILABLE, r.status)
        assertEquals(0, w.clicked("Proceed"))
    }

    @Test fun `when Blinkit asks for an address choice the agent never picks one`() {
        val w = world()
        w.cartWith("Amul Taaza Toned Milk" to 1)
        w.requireAddressConfirm = false
        w.phone.clickHandler = { n -> if (n.label == "Proceed") { w.at = BlinkitWorld.At.PICKER; w.render() } }
        val before = w.address
        val r = w.prepare()
        assertEquals(r.message, BlinkitStatus.NEEDS_ADDRESS, r.status)
        assertEquals(before, w.address)
    }

    @Test fun `a single already-selected address is continued without changing it`() {
        val w = world()
        w.cartWith("Amul Taaza Toned Milk" to 1)
        w.requireAddressConfirm = true
        val r = w.prepare()
        assertEquals(r.message, BlinkitStatus.PREPARED, r.status)
        assertEquals(1, w.clicked("Proceed to pay"))
        assertEquals("HOME - Flat 12, MG Road, Hyderabad", w.address)
    }

    // ---- Address and location -----------------------------------------------------------------

    @Test fun `address status reads the delivery address`() = runBlocking {
        val w = world()
        val r = w.agent.address("status", null)
        assertTrue(r.message, r.success)
        assertTrue(r.message.contains("MG Road"))
    }

    @Test fun `listing saved addresses does not change the selected one`() = runBlocking {
        val w = world()
        val r = w.agent.address("list", null)
        assertTrue(r.message, r.success)
        assertTrue(r.message.contains("Home") && r.message.contains("Work"))
        assertTrue(r.message.contains("NOT changed"))
        assertEquals("HOME - Flat 12, MG Road, Hyderabad", w.address)
    }

    @Test fun `selecting a saved address is verified on Blinkit's header`() = runBlocking {
        val w = world()
        val r = w.agent.address("select", "work")
        assertTrue(r.message, r.success && r.verified)
        assertEquals("WORK - Tower B, HITEC City, Hyderabad", w.address)
        assertTrue(r.message.contains("HITEC"))
    }

    @Test fun `an address that matches nothing or several changes nothing`() = runBlocking {
        val w = world()
        val none = w.agent.address("select", "mumbai")
        assertEquals(BlinkitStatus.AMBIGUOUS, none.status)
        val several = w.agent.address("select", "hyderabad")
        assertEquals(BlinkitStatus.AMBIGUOUS, several.status)
        assertEquals("HOME - Flat 12, MG Road, Hyderabad", w.address)
    }

    @Test fun `location status explains permission and GPS problems without coordinates`() = runBlocking {
        val w = world()
        w.phone.location = LocationState(LocationAccess.PERMISSION_DENIED)
        assertEquals(BlinkitStatus.NEEDS_LOCATION, w.agent.address("location", null).status)
        w.phone.location = LocationState(LocationAccess.SERVICES_OFF)
        val off = w.agent.address("location", null)
        assertTrue(off.message.contains("does not switch it on"))
        w.phone.location = LocationState(LocationAccess.APPROXIMATE, 800)
        assertTrue(w.agent.address("location", null).message.contains("approximate"))
        w.phone.location = LocationState(LocationAccess.PRECISE, 20, "Indiranagar")
        val ok = w.agent.address("location", null)
        assertTrue(ok.success)
        assertFalse(Regex("""\d+\.\d{4,}""").containsMatchIn(ok.message))
    }

    // ---- Order status, history, help, cancel --------------------------------------------------

    @Test fun `order status reads the stage and estimate from the tracking screen`() = runBlocking {
        val w = world()
        w.at = BlinkitWorld.At.TRACKING; w.orderStage = "Rahul is on the way"; w.render()
        val r = w.agent.orderStatus("status", "where is my order")
        assertTrue(r.message, r.success && r.verified)
        assertTrue(r.message.contains("on the way"))
        assertTrue(r.message.contains("9 mins"))
    }

    @Test fun `an order screen that cannot be understood is not turned into a claim`() = runBlocking {
        val w = world()
        w.phone.screen = listOf(BScreens.node("Order ID #4821"), BScreens.node("Arriving soon maybe"), BScreens.node("Your order is being handled"), BScreens.node("Help center"))
        w.phone.launchHandler = {}
        val r = w.agent.orderStatus("status", "status")
        assertFalse(r.verified)
    }

    @Test fun `history lists previous orders from the orders page`() = runBlocking {
        val w = world()
        w.at = BlinkitWorld.At.ORDERS; w.render()
        val r = w.agent.orderStatus("history", "my previous orders")
        assertTrue(r.message, r.success)
        assertTrue(r.message.contains("₹182"))
    }

    @Test fun `cancel first explains and cancels nothing`() = runBlocking {
        val w = world()
        w.at = BlinkitWorld.At.TRACKING; w.orderStage = "Preparing your order"; w.render()
        val r = w.agent.orderStatus("cancel", "cancel my order")
        assertEquals(BlinkitStatus.PREPARED, r.status)
        assertEquals(0, w.cancelClicks)
        assertTrue(r.message.contains("NOT cancelled"))
    }

    @Test fun `cancel needs a new explicit message and is verified by the screen`() = runBlocking {
        val w = world()
        w.at = BlinkitWorld.At.TRACKING; w.orderStage = "Preparing your order"; w.render()
        w.agent.orderStatus("cancel", "cancel my order")
        w.phone.letTimePass(10_000)
        val same = w.agent.orderStatus("cancel", "cancel my order")
        assertEquals(BlinkitStatus.REFUSED, same.status)
        assertEquals(0, w.cancelClicks)
        val vague = w.agent.orderStatus("cancel", "hmm what will it cost")
        assertEquals(BlinkitStatus.REFUSED, vague.status)
        val done = w.agent.orderStatus("cancel", "haan cancel kar do")
        assertTrue(done.message, done.success && done.verified)
        assertEquals(1, w.cancelClicks)
        assertTrue(done.message.contains("cancelled"))
    }

    @Test fun `no cancel option means nothing is cancelled and the limit is explained`() = runBlocking {
        val w = world()
        w.cancelAvailable = false
        w.at = BlinkitWorld.At.TRACKING; w.orderStage = "Rahul is on the way"; w.render()
        val r = w.agent.orderStatus("cancel", "cancel my order")
        assertEquals(BlinkitStatus.NOT_FOUND, r.status)
        assertEquals(0, w.cancelClicks)
    }

    @Test fun `a delivered order cannot be cancelled and is never reported cancelled`() = runBlocking {
        val w = world()
        w.at = BlinkitWorld.At.TRACKING; w.orderStage = "Order delivered"; w.render()
        val r = w.agent.orderStatus("cancel", "cancel my order")
        assertEquals(BlinkitStatus.REFUSED, r.status)
        assertEquals(0, w.cancelClicks)
    }

    @Test fun `help opens Blinkit's own help and claims no refund`() = runBlocking {
        val w = world()
        w.at = BlinkitWorld.At.TRACKING; w.render()
        val r = w.agent.orderStatus("help", "item missing")
        assertEquals(BlinkitStatus.NEEDS_USER, r.status)
        assertTrue(w.phone.log.contains("help-opened"))
        assertTrue(r.message.contains("will not claim a refund"))
    }

    @Test fun `search results are remembered for follow up commands`() = runBlocking {
        val w = world()
        w.agent.search(BlinkitQueryParser.parse("milk"))
        assertNotNull(w.agent.compare(listOf(1, 2)).message)
    }
}
