package com.jarvis.assistant.automation.amazon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmazonScreenReaderTest {
    private val results = Screens.searchResults(
        Triple("boAt Rockerz 255 Pro+ Bluetooth Neckband with 60 Hours Playback", "4.1 out of 5 stars 85,432 ratings", "₹1,299 M.R.P: ₹3,990 (67% off) FREE delivery"),
        Triple("Sponsored Noise Buds VS104 Truly Wireless Earbuds with ENC", "4.0 out of 5 stars 12,000 ratings", "₹999 Delivery ₹40"),
        Triple("Zebronics Zeb-Thunder Wireless Bluetooth Headphone 60 hrs", "3.9 out of 5 stars 310 ratings", "₹799 Prime FREE delivery")
    )

    @Test fun classifiesCoreScreens() {
        assertEquals(AmazonScreen.SEARCH_RESULTS, AmazonScreenReader.classify(results))
        assertEquals(AmazonScreen.PRODUCT, AmazonScreenReader.classify(Screens.product("Dell Inspiron 15 Laptop 8GB RAM 512GB SSD Intel Core i5", "₹45,990")))
        assertEquals(AmazonScreen.CART, AmazonScreenReader.classify(Screens.cart(Triple("Dell Inspiron 15 Laptop 8GB RAM 512GB SSD", "₹45,990", 1))))
        assertEquals(AmazonScreen.CART, AmazonScreenReader.classify(Screens.cart()))
        assertEquals(AmazonScreen.ORDER_REVIEW, AmazonScreenReader.classify(Screens.review("Dell Inspiron 15 Laptop 8GB RAM 512GB SSD", "₹45,990", "₹45,990")))
        assertEquals(AmazonScreen.ORDER_PLACED, AmazonScreenReader.classify(Screens.placed()))
        assertEquals(AmazonScreen.HOME, AmazonScreenReader.classify(Screens.home()))
    }

    @Test fun classifiesProblemScreens() {
        assertEquals(AmazonScreen.LOADING, AmazonScreenReader.classify(Screens.loading()))
        assertEquals(AmazonScreen.ERROR, AmazonScreenReader.classify(Screens.error()))
        assertEquals(AmazonScreen.OFFLINE, AmazonScreenReader.classify(Screens.offline()))
        assertEquals(AmazonScreen.SIGN_IN, AmazonScreenReader.classify(Screens.signIn()))
        assertEquals(AmazonScreen.CHALLENGE, AmazonScreenReader.classify(Screens.otp()))
    }

    @Test fun otpAndCaptchaAreNeverTreatedAsNormalScreens() {
        val captcha = listOf(Screens.node("Type the characters you see in this image"), Screens.node("Enter the characters", editable = true), Screens.node("Continue shopping", clickable = true), Screens.node("Amazon"))
        assertEquals(AmazonScreen.CHALLENGE, AmazonScreenReader.classify(captcha))
        assertEquals("a CAPTCHA", AmazonScreenReader.challengeKind(captcha))
        assertEquals("an OTP or verification code", AmazonScreenReader.challengeKind(Screens.otp()))
    }

    @Test fun extractsProductsWithPricesRatingsAndDelivery() {
        val items = AmazonScreenReader.extractProducts(results)
        assertEquals(3, items.size)
        val boat = items[0]
        assertEquals(1299.0, boat.price!!, 0.0)
        assertEquals(3990.0, boat.mrp!!, 0.0)
        assertEquals(67, boat.discountPercent)
        assertEquals(4.1, boat.rating!!, 0.0)
        assertEquals(85432, boat.ratingCount)
        assertEquals(true, boat.freeDelivery)
        val noise = items[1]
        assertTrue(noise.sponsored)
        assertEquals(40.0, noise.deliveryFee!!, 0.0)
        assertEquals(false, noise.freeDelivery)
        assertEquals(1039.0, noise.totalCost!!, 0.0)
        assertTrue(items[2].prime)
    }

    @Test fun extractsSelfContainedCardsAndDeduplicates() {
        val one = "Samsung Galaxy M14 5G (Berry Blue, 6GB, 128GB Storage) 4.2 out of 5 stars 5,100 ratings ₹12,490 M.R.P: ₹17,990 FREE delivery"
        val screen = listOf(Screens.node("Search Amazon.in", editable = true), Screens.node("Sort by", clickable = true),
            Screens.node(one, clickable = true), Screens.node(one, clickable = true), Screens.node("results"))
        val items = AmazonScreenReader.extractProducts(screen)
        assertEquals(1, items.size)
        assertEquals(12490.0, items[0].price!!, 0.0)
        assertTrue(items[0].title.startsWith("Samsung Galaxy M14"))
    }

    @Test fun cartRowsAndSubtotal() {
        val cart = AmazonScreenReader.extractCart(Screens.cart(
            Triple("Dell Inspiron 15 Laptop 8GB RAM 512GB SSD Intel Core i5", "₹45,990", 1),
            Triple("Logitech M331 Silent Plus Wireless Mouse 2.4GHz", "₹899", 2), subtotal = "₹47,788"))
        assertEquals(2, cart.items.size)
        assertEquals(2, cart.items[1].quantity)
        assertEquals(47788.0, cart.subtotal!!, 0.0)
        assertFalse(cart.empty)
        assertTrue(AmazonScreenReader.extractCart(Screens.cart()).empty)
    }

    @Test fun checkoutSummaryIsReadAndFingerprintTracksChanges() {
        val a = AmazonScreenReader.extractCheckout(Screens.review("Dell Inspiron 15 Laptop 8GB RAM 512GB SSD", "₹45,990", "₹45,990"))
        assertTrue(a.readable)
        assertEquals(45990.0, a.total!!, 0.0)
        assertEquals(0.0, a.deliveryFee!!, 0.0)
        assertTrue(a.addressLabel!!.contains("Hyderabad"))
        assertTrue(a.paymentLabel!!.contains("Pay on Delivery"))
        val same = AmazonScreenReader.extractCheckout(Screens.review("Dell Inspiron 15 Laptop 8GB RAM 512GB SSD", "₹45,990", "₹45,990"))
        assertEquals(a.fingerprint(), same.fingerprint())
        val dearer = AmazonScreenReader.extractCheckout(Screens.review("Dell Inspiron 15 Laptop 8GB RAM 512GB SSD", "₹45,990", "₹46,490"))
        assertTrue(a.fingerprint() != dearer.fingerprint())
        val otherPay = AmazonScreenReader.extractCheckout(Screens.review("Dell Inspiron 15 Laptop 8GB RAM 512GB SSD", "₹45,990", "₹45,990", pay = "Credit card ending 4321"))
        assertTrue(a.fingerprint() != otherPay.fingerprint())
    }

    @Test fun buttonFindersAreExactAndSafe() {
        val product = Screens.product("Dell Inspiron 15 Laptop 8GB RAM 512GB SSD Intel Core i5", "₹45,990")
        assertEquals("Add to Cart", AmazonScreenReader.addToCartButton(product)!!.label)
        assertNull(AmazonScreenReader.placeOrderButton(product))
        val none = listOf(Screens.node("Add to List", clickable = true), Screens.node("Add to Wish List", clickable = true), Screens.node("Buy Now", clickable = true))
        assertNull(AmazonScreenReader.addToCartButton(none))
        // dismiss never matches anything that buys
        val buying = listOf(Screens.node("Place your order", clickable = true), Screens.node("Buy Now", clickable = true), Screens.node("Add to Cart", clickable = true))
        assertNull(AmazonScreenReader.dismissButton(buying))
        assertNotNull(AmazonScreenReader.dismissButton(listOf(Screens.node("No thanks", clickable = true))))
    }

    @Test fun variantsAreFoundAndVerified() {
        val before = Screens.product("Apple iPhone 15 (128 GB) - Black", "₹65,000", options = listOf("Black", "Blue", "128 GB", "256 GB"))
        assertNotNull(AmazonScreenReader.variantNode(before, "128 GB"))
        assertNull(AmazonScreenReader.variantNode(before, "Green"))
        assertFalse(AmazonScreenReader.isVariantSelected(before, "Blue"))
        val after = Screens.product("Apple iPhone 15 (128 GB) - Black", "₹65,000", options = listOf("Black", "Blue"), selected = listOf("Blue"))
        assertTrue(AmazonScreenReader.isVariantSelected(after, "Blue"))
    }

    @Test fun productDetailsReadSpecsAndNeverInventValues() {
        val d = AmazonProductReader.details(Screens.product("Dell Inspiron 15 Laptop 8GB RAM 512GB SSD Intel Core i5", "₹45,990"))
        assertEquals(45990.0, d.price!!, 0.0)
        assertEquals(4.3, d.rating!!, 0.0)
        assertEquals(2, d.specs.size)
        assertTrue(d.seller!!.contains("RetailNet"))
        assertFalse(d.unavailable)
        val gone = AmazonProductReader.details(Screens.product("Dell Inspiron 15 Laptop 8GB RAM 512GB SSD Intel Core i5", "₹45,990", withAdd = false, unavailable = true))
        assertTrue(gone.unavailable)
    }
}
