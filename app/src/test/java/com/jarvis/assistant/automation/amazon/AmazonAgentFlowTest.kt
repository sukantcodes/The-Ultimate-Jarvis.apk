package com.jarvis.assistant.automation.amazon

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drives the real agent against a simulated Amazon app (screens, taps, loading, errors, sign-in, OTP). */
class AmazonAgentFlowTest {

    private val boat = Triple("boAt Rockerz 255 Pro+ Bluetooth Neckband with 60 Hours Playback", "4.1 out of 5 stars 85,432 ratings", "₹1,299 M.R.P: ₹3,990 (67% off) FREE delivery")
    private val noise = Triple("Noise Buds VS104 Truly Wireless Earbuds with ENC Bluetooth", "4.0 out of 5 stars 12,000 ratings", "₹999 Prime FREE delivery")
    private val zeb = Triple("Zebronics Zeb-Thunder Wireless Bluetooth Headphone 60 hrs", "3.9 out of 5 stars 310 ratings", "₹799 FREE delivery")

    private class World(val cards: List<Triple<String, String, String>>) {
        val phone = FakePhone()
        val cartRows = ArrayList<Triple<String, String, Int>>()
        var currentTitle = ""
        var currentPrice = ""
        var addWorks = true
        var placeClicks = 0
        var onPlace: () -> List<AmazonNode> = { Screens.placed() }
        var options: List<String> = emptyList()
        val gate = PurchaseGate({ phone.now() }, InMemoryOrderLedger())
        val agent = AmazonAgent(phone, gate)

        fun results() = Screens.searchResults(*cards.toTypedArray())
        fun cartScreen() = Screens.cart(*cartRows.toTypedArray(), subtotal = if (cartRows.isEmpty()) null else "₹" + cartRows.sumOf { it.second.filter { c -> c.isDigit() || c == '.' }.toDouble() * it.third }.toLong())
        fun price(p: String) = Regex("₹[0-9,]+").find(p)!!.value

        init {
            phone.launchHandler = { url ->
                when {
                    url.contains("/s?") -> phone.screen = results()
                    url.contains("/cart/") -> phone.screen = cartScreen()
                }
            }
            phone.backHandler = { phone.screen = results() }
            phone.clickHandler = { n ->
                val label = n.label
                val card = cards.firstOrNull { it.first == label }
                when {
                    card != null -> { currentTitle = card.first; currentPrice = price(card.third); phone.screen = Screens.product(card.first, currentPrice, options = options) }
                    label == "Add to Cart" -> {
                        if (addWorks) cartRows += Triple(currentTitle, currentPrice, 1)
                        phone.screen = Screens.product(currentTitle, currentPrice, options = options) + Screens.node("Added to cart")
                    }
                    label.startsWith("Proceed to Buy") -> phone.screen = Screens.review(cartRows[0].first, cartRows[0].second, cartRows[0].second)
                    label == "Place your order" -> { placeClicks++; phone.screen = onPlace() }
                    label == "Delete" -> { if (cartRows.isNotEmpty()) cartRows.removeAt(0); phone.screen = cartScreen() }
                    label == "Try again" -> phone.screen = results()
                    else -> options.firstOrNull { it == label }?.let { o -> phone.screen = Screens.product(currentTitle, currentPrice, options = options, selected = listOf(o)) }
                }
            }
        }
    }

    private fun world() = World(listOf(boat, noise, zeb))
    private fun run(block: suspend () -> AmazonResult): AmazonResult = runBlocking { block() }

    // ---- search -----------------------------------------------------------------------------------

    @Test fun searchReadsRanksAndAppliesLimitsFromTheScreen() {
        val w = world()
        val req = AmazonQueryParser.parse("amazon pe bluetooth headphones 1000 ke neeche 4 star se upar")
        val r = run { w.agent.search(req) }
        assertTrue(r.message, r.success)
        assertTrue(r.verified)
        // boAt (₹1,299) is over the limit; Zebronics (3.9★) is under the rating floor -> only Noise remains
        assertEquals(1, w.agent.let { (w.phone.log.count { it.startsWith("launch:") }) })
        assertTrue(r.message, r.message.contains("Noise Buds"))
        assertFalse(r.message, r.message.contains("boAt"))
        assertTrue(w.phone.log.first { it.startsWith("launch:") }.contains("high-price=1000"))
    }

    @Test fun searchWithNothingMatchingSaysSoInsteadOfInventing() {
        val w = world()
        val r = run { w.agent.search(AmazonSearchRequest("headphones", maxPrice = 100.0)) }
        assertTrue(r.success)
        assertEquals(AmazonStatus.NOT_FOUND, r.status)
        assertTrue(r.message.contains("none matched"))
    }

    @Test fun searchRecoversFromErrorPageByRetrying() {
        val w = world()
        w.phone.launchHandler = { w.phone.screen = Screens.error() }
        val r = run { w.agent.search(AmazonSearchRequest("headphones")) }
        assertTrue(r.message, r.success)
        assertTrue(w.phone.log.contains("click:Try again"))
    }

    @Test fun searchGivesUpHonestlyWhenAmazonStaysBroken() {
        val w = world()
        w.phone.launchHandler = { w.phone.screen = Screens.offline() }
        w.phone.clickHandler = { }
        val r = run { w.agent.search(AmazonSearchRequest("headphones")) }
        assertFalse(r.success)
        assertEquals(AmazonStatus.OFFLINE, r.status)
        assertTrue(w.phone.clicks <= 4)
    }

    @Test fun searchNeverLoadsForeverAndStopsInsideItsBudget() {
        val w = world()
        w.phone.launchHandler = { w.phone.screen = Screens.loading() }
        val r = run { w.agent.search(AmazonSearchRequest("headphones")) }
        assertFalse(r.success)
        assertEquals(AmazonStatus.TIMEOUT, r.status)
        assertTrue("virtual time used: ${w.phone.clock}", w.phone.clock <= AmazonAgent.SEARCH_BUDGET_MS + 5_000)
    }

    @Test fun signInIsHandedToTheUserAndNothingIsTyped() {
        val w = world()
        w.phone.launchHandler = { w.phone.screen = Screens.signIn() }
        val r = run { w.agent.search(AmazonSearchRequest("headphones")) }
        assertEquals(AmazonStatus.NEEDS_SIGN_IN, r.status)
        assertTrue(w.phone.log.none { it.startsWith("type:") })
    }

    @Test fun otpAndCaptchaAreHandedToTheUser() {
        val w = world()
        w.phone.launchHandler = { w.phone.screen = Screens.otp() }
        val r = run { w.agent.search(AmazonSearchRequest("headphones")) }
        assertEquals(AmazonStatus.NEEDS_USER, r.status)
        assertTrue(r.message, r.message.contains("OTP"))
        assertTrue(w.phone.log.none { it.startsWith("type:") })
        assertEquals(0, w.phone.clicks)
    }

    @Test fun deepLinkIgnoredFallsBackToTypingTheSearch() {
        val w = world()
        w.phone.launchHandler = { w.phone.screen = Screens.home() }
        w.phone.clickHandler = { }
        w.phone.submitHandler = { w.phone.screen = w.results(); true }
        val r = run { w.agent.search(AmazonSearchRequest("bluetooth headphones")) }
        assertTrue(r.message, r.success)
        assertTrue(w.phone.log.contains("type:bluetooth headphones"))
        assertTrue(w.phone.log.contains("submit"))
    }

    @Test fun typingFailureIsReportedNotFaked() {
        val w = world()
        w.phone.launchHandler = { w.phone.screen = Screens.home() }
        w.phone.clickHandler = { }
        w.phone.typingWorks = false
        val r = run { w.agent.search(AmazonSearchRequest("bluetooth headphones")) }
        assertFalse(r.success)
        assertTrue(w.phone.log.none { it == "submit" })
        assertTrue(w.phone.clock < 120_000)
    }

    @Test fun launchFailureAndUnreadableScreenAreReported() {
        val w = world()
        w.phone.launchWorks = false
        assertEquals(AmazonStatus.LAUNCH_FAILED, run { w.agent.search(AmazonSearchRequest("x y")) }.status)
        val w2 = world()
        w2.phone.unreadable = true
        w2.phone.launchHandler = { }
        assertEquals(AmazonStatus.SCREEN_UNREADABLE, run { w2.agent.search(AmazonSearchRequest("x y")) }.status)
    }

    @Test fun userStopCancelsImmediately() {
        val w = world()
        w.phone.cancelFlag = true
        val r = run { w.agent.search(AmazonSearchRequest("headphones")) }
        assertEquals(AmazonStatus.CANCELLED, r.status)
        assertEquals(0, w.phone.clicks)
    }

    // ---- compare ----------------------------------------------------------------------------------

    @Test fun compareUsesRealNumbersAndCanReadSpecs() {
        val w = world()
        run { w.agent.search(AmazonSearchRequest("headphones")) }
        val quick = run { w.agent.compare(emptyList(), deep = false) }
        assertTrue(quick.message, quick.success)
        assertTrue(quick.message.contains("lowest price"))
        val deep = run { w.agent.compare(listOf(1, 2), deep = true) }
        assertTrue(deep.message, deep.success)
        assertTrue(deep.message, deep.message.contains("8GB RAM"))
        assertEquals(2, deep.extra["details_read"])
    }

    @Test fun compareWithoutSearchExplainsWhy() {
        val r = run { world().agent.compare(emptyList(), false) }
        assertFalse(r.success)
    }

    // ---- cart -------------------------------------------------------------------------------------

    @Test fun addToCartIsOnlyReportedWhenTheCartShowsTheItem() {
        val w = world()
        run { w.agent.search(AmazonSearchRequest("headphones")) }
        val r = run { w.agent.addToCart(2, 1, emptyList()) }
        assertTrue(r.message, r.success && r.verified)
        assertEquals(1, w.cartRows.size)
        assertTrue(r.message.contains("Verified in the cart"))
    }

    @Test fun addToCartWithoutCartEvidenceIsNotClaimed() {
        val w = world()
        w.addWorks = false
        run { w.agent.search(AmazonSearchRequest("headphones")) }
        val r = run { w.agent.addToCart(2, 1, emptyList()) }
        assertFalse(r.success)
        assertEquals(AmazonStatus.NOT_VERIFIED, r.status)
    }

    @Test fun missingVariantMeansNothingIsAdded() {
        val w = world()
        w.options = listOf("Black", "Blue")
        run { w.agent.search(AmazonSearchRequest("headphones")) }
        val r = run { w.agent.addToCart(1, 1, listOf("Green")) }
        assertEquals(AmazonStatus.VARIANT_UNMET, r.status)
        assertTrue(w.phone.log.none { it == "click:Add to Cart" })
        assertTrue(w.cartRows.isEmpty())
    }

    @Test fun variantIsSelectedAndConfirmedBeforeAdding() {
        val w = world()
        w.options = listOf("Black", "Blue")
        run { w.agent.search(AmazonSearchRequest("headphones")) }
        val r = run { w.agent.addToCart(1, 1, listOf("Blue")) }
        assertTrue(r.message, r.success)
        assertTrue(w.phone.log.indexOf("click:Blue") < w.phone.log.indexOf("click:Add to Cart"))
    }

    @Test fun repeatedAddRequestDoesNotAddTwice() {
        val w = world()
        run { w.agent.search(AmazonSearchRequest("headphones")) }
        run { w.agent.addToCart(2, 1, emptyList()) }
        w.phone.screen = Screens.product(w.currentTitle, w.currentPrice)
        val again = run { w.agent.addToCart(null, 1, emptyList()) }
        assertTrue(again.message, again.message.contains("did not add it again"))
        assertEquals(1, w.cartRows.size)
        assertEquals(1, w.phone.log.count { it == "click:Add to Cart" })
    }

    @Test fun addToCartNeedsAProductPage() {
        val w = world()
        w.phone.screen = Screens.home()
        assertEquals(AmazonStatus.WRONG_SCREEN, run { w.agent.addToCart(null, 1, emptyList()) }.status)
    }

    @Test fun removeFromCartIsVerifiedAndAmbiguityIsAsked() {
        val w = world()
        w.cartRows += Triple("Dell Inspiron 15 Laptop 8GB RAM 512GB SSD", "₹45,990", 1)
        w.cartRows += Triple("Dell Wireless Mouse WM126 Optical 1000 DPI", "₹499", 1)
        val amb = run { w.agent.cartAction("remove", "dell", null) }
        assertEquals(AmazonStatus.AMBIGUOUS, amb.status)
        assertEquals(2, w.cartRows.size)
        val ok = run { w.agent.cartAction("remove", "laptop", null) }
        assertTrue(ok.message, ok.success && ok.verified)
        assertEquals(1, w.cartRows.size)
        val missing = run { w.agent.cartAction("remove", "toaster", null) }
        assertEquals(AmazonStatus.NOT_FOUND, missing.status)
    }

    @Test fun viewCartReadsRealRowsAndEmptyCart() {
        val w = world()
        assertTrue(run { w.agent.cartAction("view", null, null) }.message.contains("empty"))
        w.cartRows += Triple("Logitech M331 Silent Plus Wireless Mouse 2.4GHz", "₹899", 2)
        val r = run { w.agent.cartAction("view", null, null) }
        assertTrue(r.message, r.message.contains("× 2"))
    }

    // ---- checkout and the order gate --------------------------------------------------------------

    private fun readyToReview(w: World): AmazonResult {
        w.cartRows += Triple("Logitech M331 Silent Plus Wireless Mouse 2.4GHz", "₹899", 1)
        return run { w.agent.prepareCheckout("checkout karo") }
    }

    @Test fun prepareCheckoutStopsAtTheReviewScreenWithoutOrdering() {
        val w = world()
        val r = readyToReview(w)
        assertEquals(r.message, AmazonStatus.PREPARED, r.status)
        assertTrue(r.message.contains("Order NOT placed"))
        assertTrue(r.message.contains("₹899"))
        assertNotNull(w.gate.pending)
        assertEquals(0, w.placeClicks)
    }

    @Test fun emptyCartCannotBeCheckedOut() {
        val w = world()
        assertEquals(AmazonStatus.NOT_FOUND, run { w.agent.prepareCheckout("checkout") }.status)
    }

    @Test fun addressChoiceIsLeftToTheUser() {
        val w = world()
        w.cartRows += Triple("Logitech M331 Silent Plus Wireless Mouse 2.4GHz", "₹899", 1)
        val two = { Screens.reset(); listOf(Screens.node("Select a delivery address"), Screens.node("Deliver to this address", clickable = true), Screens.node("Deliver to this address", clickable = true), Screens.node("Add a new address", clickable = true)) }
        w.phone.clickHandler = { n -> if (n.label.startsWith("Proceed")) w.phone.screen = two() }
        val r = run { w.agent.prepareCheckout("checkout") }
        assertEquals(AmazonStatus.AMBIGUOUS, r.status)
        assertEquals(1, w.phone.log.count { it.startsWith("click:") })
    }

    @Test fun placeOrderRefusesWithoutAPreparedCheckoutOrConfirmation() {
        val w = world()
        w.phone.screen = Screens.review("Logitech M331 Silent Plus Wireless Mouse 2.4GHz", "₹899", "₹899")
        val none = run { w.agent.placeOrder("confirm the order") }
        assertEquals(AmazonStatus.REFUSED, none.status)
        assertEquals(0, w.placeClicks)
    }

    @Test fun placeOrderRefusesSameSentenceVagueAndFastConfirmations() {
        val w = world()
        w.cartRows += Triple("Logitech M331 Silent Plus Wireless Mouse 2.4GHz", "₹899", 1)
        run { w.agent.prepareCheckout("buy the mouse and confirm the order") }
        w.phone.letTimePass(10_000)
        assertEquals("SAME_TURN", run { w.agent.placeOrder("buy the mouse and confirm the order") }.extra["code"])
        assertEquals("NOT_CONFIRMED", run { w.agent.placeOrder("hmm ok") }.extra["code"])
        assertEquals(0, w.placeClicks)
    }

    @Test fun confirmedOrderIsPlacedOnceAndVerified() {
        val w = world()
        readyToReview(w)
        w.phone.letTimePass(10_000)
        val r = run { w.agent.placeOrder("yes place the order") }
        assertEquals(r.message, AmazonStatus.PLACED, r.status)
        assertTrue(r.verified)
        assertEquals(1, w.placeClicks)
        // a second confirmation (model retry or the user repeating) can never order again
        val again = run { w.agent.placeOrder("yes place the order") }
        assertFalse(again.success)
        assertEquals(1, w.placeClicks)
    }

    @Test fun orderIsNeverClaimedWithoutAmazonsConfirmationScreen() {
        val w = world()
        w.onPlace = { Screens.review("Logitech M331 Silent Plus Wireless Mouse 2.4GHz", "₹899", "₹899") }
        readyToReview(w)
        w.phone.letTimePass(10_000)
        val r = run { w.agent.placeOrder("confirm the order") }
        assertFalse(r.success)
        assertEquals(AmazonStatus.NOT_VERIFIED, r.status)
        assertTrue(r.message.contains("cannot say it was placed"))
        assertEquals(1, w.placeClicks)
        // and a later retry cannot press it again
        w.phone.letTimePass(5_000)
        val retry = run { w.agent.placeOrder("confirm the order") }
        assertEquals("DUPLICATE", retry.extra["code"])
        assertEquals(1, w.placeClicks)
    }

    @Test fun paymentAuthenticationIsLeftToTheUser() {
        val w = world()
        w.onPlace = { Screens.otp() }
        readyToReview(w)
        w.phone.letTimePass(10_000)
        val r = run { w.agent.placeOrder("confirm the order") }
        assertEquals(AmazonStatus.NEEDS_PAYMENT_AUTH, r.status)
        assertFalse(r.success)
        assertTrue(w.phone.log.none { it.startsWith("type:") })
        // once the user finished, Jarvis only reports what Amazon shows
        w.phone.screen = Screens.placed()
        val check = run { w.agent.cartAction("check_order", null, null) }
        assertEquals(AmazonStatus.PLACED, check.status)
    }

    @Test fun totalChangingBeforeConfirmationBlocksTheOrder() {
        val w = world()
        readyToReview(w)
        w.phone.letTimePass(10_000)
        w.phone.screen = Screens.review("Logitech M331 Silent Plus Wireless Mouse 2.4GHz", "₹899", "₹1,049")
        val r = run { w.agent.placeOrder("confirm the order") }
        assertEquals("CHANGED", r.extra["code"])
        assertTrue(r.message.contains("₹1,049"))
        assertEquals(0, w.placeClicks)
    }

    @Test fun placeOrderOutsideTheReviewScreenDoesNothing() {
        val w = world()
        readyToReview(w)
        w.phone.letTimePass(10_000)
        w.phone.screen = Screens.cart(Triple("Logitech M331 Silent Plus Wireless Mouse 2.4GHz", "₹899", 1))
        assertEquals(AmazonStatus.WRONG_SCREEN, run { w.agent.placeOrder("confirm the order") }.status)
        assertEquals(0, w.placeClicks)
    }
}
