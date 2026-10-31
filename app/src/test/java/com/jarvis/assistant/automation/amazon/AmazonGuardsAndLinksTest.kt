package com.jarvis.assistant.automation.amazon

import com.jarvis.assistant.skills.SkillParser
import com.jarvis.assistant.util.ConfirmationPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AmazonGuardsAndLinksTest {

    @Test fun purchaseButtonsCannotBeTappedDirectly() {
        listOf("Place your order", "Buy Now", "place order", "Pay now", "Confirm and pay", "Pay with Amazon Pay").forEach {
            assertNotNull(it, AmazonGuards.genericClickBlock(true, AmazonScreen.PRODUCT, it, "", false))
        }
        assertNotNull(AmazonGuards.genericClickBlock(true, AmazonScreen.PRODUCT, "Continue", "the Buy Now button", false))
    }

    @Test fun ordinaryShoppingTapsAreNotBlocked() {
        listOf("Add to Cart", "Search", "Black", "Delete", "Proceed to Buy").forEach {
            assertNull(it, AmazonGuards.genericClickBlock(true, AmazonScreen.PRODUCT, it, "", false))
        }
    }

    @Test fun tapsByCoordinateOrIdAreBlockedOnlyOnTheReviewScreen() {
        assertNotNull(AmazonGuards.genericClickBlock(true, AmazonScreen.ORDER_REVIEW, "Continue", "", true))
        assertNull(AmazonGuards.genericClickBlock(true, AmazonScreen.CART, "Continue", "", true))
        assertNull(AmazonGuards.genericClickBlock(true, AmazonScreen.ORDER_REVIEW, "Back", "", false))
    }

    @Test fun nothingIsBlockedOutsideAmazon() {
        assertNull(AmazonGuards.genericClickBlock(false, AmazonScreen.ORDER_REVIEW, "Place your order", "", true))
        assertNull(AmazonGuards.genericTypeBlock(false, AmazonScreen.CHALLENGE))
    }

    @Test fun securityScreensCannotBeTypedInto() {
        assertNotNull(AmazonGuards.genericTypeBlock(true, AmazonScreen.CHALLENGE))
        assertNotNull(AmazonGuards.genericTypeBlock(true, AmazonScreen.SIGN_IN))
        assertNull(AmazonGuards.genericTypeBlock(true, AmazonScreen.HOME))
        assertNull(AmazonGuards.genericTypeBlock(true, AmazonScreen.SEARCH_RESULTS))
    }

    @Test fun searchLinksCarryEncodedQueryAndHints() {
        val url = AmazonLinks.searchUrl(AmazonSearchRequest("iphone 15 & case", minPrice = 1000.0, maxPrice = 60000.0, sort = SortPreference.PRICE_LOW))
        assertTrue(url, url.startsWith("https://www.amazon.in/s?"))
        assertTrue(url, url.contains("k=iphone+15+%26+case"))
        assertTrue(url, url.contains("low-price=1000"))
        assertTrue(url, url.contains("high-price=60000"))
        assertTrue(url, url.contains("s=price-asc-rank"))
        assertEquals("https://www.amazon.in/gp/cart/view.html", AmazonLinks.cartUrl())
    }

    @Test fun asinsAreValidatedBeforeUse() {
        assertEquals("B0CHX1W1XY", AmazonLinks.extractAsin("https://www.amazon.in/Apple-iPhone-15/dp/B0CHX1W1XY/ref=sr_1_1"))
        assertNull(AmazonLinks.extractAsin("https://example.com/not-amazon"))
        assertNotNull(AmazonLinks.productUrl("B0CHX1W1XY"))
        assertNull(AmazonLinks.productUrl("../../etc"))
        assertTrue(AmazonLinks.isAmazonShoppingPackage("in.amazon.mShop.android.shopping"))
        assertFalse(AmazonLinks.isAmazonShoppingPackage("com.amazon.avod.thirdpartyclient"))
    }

    @Test fun policyKeepsTheOwnersAmazonConfirmationException() {
        val text = ConfirmationPolicy.promptSection()
        assertTrue(text.contains("ONE EXCEPTION"))
        assertTrue(text.contains("amazon_place_order"))
        assertTrue(text.contains("new message"))
    }

    @Test fun builtInSkillIsValidAndTeachesTheSafeFlow() {
        val file = listOf("src/main/assets/skills/amazon-shopping.md", "app/src/main/assets/skills/amazon-shopping.md")
            .map { File(it) }.first { it.exists() }
        val skill = SkillParser.parse(file.readText())
        assertNotNull(skill)
        assertEquals("amazon-shopping", skill!!.name)
        assertTrue(skill.description.length <= SkillParser.MAX_DESCRIPTION)
        assertTrue(skill.body.length <= SkillParser.MAX_BODY)
        assertTrue(skill.body.contains("amazon_place_order"))
        assertTrue(skill.body.contains("PLACED"))
    }
}
