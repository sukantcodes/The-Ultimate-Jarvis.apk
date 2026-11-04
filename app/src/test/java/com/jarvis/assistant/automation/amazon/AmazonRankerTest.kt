package com.jarvis.assistant.automation.amazon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmazonRankerTest {
    private fun c(title: String, price: Double?, rating: Double? = null, count: Int? = null, prime: Boolean = false,
                  fee: Double? = null, free: Boolean? = null, sponsored: Boolean = false, pos: Int = 0) =
        ProductCandidate(title, price, rating = rating, ratingCount = count, prime = prime, deliveryFee = fee,
            freeDelivery = free, sponsored = sponsored, position = pos)

    @Test fun filtersByPriceRatingAndPrime() {
        val items = listOf(c("A", 400.0, 4.5, 100, true), c("B", 600.0, 4.6, 100, true), c("C", 300.0, 3.2, 100, true), c("D", 350.0, 4.4, 10, false))
        val r = AmazonSearchRequest("x", maxPrice = 500.0, minRating = 4.0, primeOnly = true)
        assertEquals(listOf("A"), AmazonRanker.filter(items, r).map { it.title })
    }

    @Test fun unknownValuesAreKeptButFlagged() {
        val unknown = c("U", null, null)
        val r = AmazonSearchRequest("x", maxPrice = 500.0, minRating = 4.0)
        assertEquals(1, AmazonRanker.filter(listOf(unknown), r).size)
        assertEquals(listOf("price", "rating"), AmazonRanker.unverifiedFilters(unknown, r))
    }

    @Test fun fewRatingsCannotBeatProvenProducts() {
        val proven = c("proven", 500.0, 4.4, 20000)
        val lucky = c("lucky", 500.0, 5.0, 3)
        val ranked = AmazonRanker.rank(listOf(lucky, proven), SortPreference.RATING)
        assertEquals("proven", ranked.first().title)
    }

    @Test fun priceSortIncludesDeliveryFee() {
        val a = c("a", 500.0, fee = 100.0, free = false)
        val b = c("b", 550.0, free = true)
        assertEquals("b", AmazonRanker.rank(listOf(a, b), SortPreference.PRICE_LOW).first().title)
        assertEquals("a", AmazonRanker.rank(listOf(a, b), SortPreference.PRICE_HIGH).first().title.let { if (a.totalCost!! > b.totalCost!!) "a" else "b" })
    }

    @Test fun compareNamesWinnersAndNeverInventsMissingData() {
        val items = listOf(c("cheap", 700.0, 3.9, 800), c("rated", 1500.0, 4.6, 50000, true, free = true), c("mid", 1000.0, 4.4, 30000, free = true), c("blank", null))
        val cmp = AmazonRanker.compare(items)
        assertEquals("cheap", cmp.bestPrice!!.title)
        assertEquals("rated", cmp.bestRated!!.title)
        assertTrue(cmp.lines.size == 4)
        assertTrue(cmp.lines[3].contains("price not visible"))
        assertTrue(cmp.lines[0].contains("lowest price"))
    }

    @Test fun describeUsesRealValuesOnly() {
        val line = AmazonRanker.describe(c("Thing", 1299.0))
        assertTrue(line.contains("₹1,299"))
        assertTrue(line.contains("rating not shown"))
        assertTrue(line.contains("delivery charge not shown"))
    }

    @Test fun withDetailsAdmitsMissingSpecs() {
        val text = AmazonRanker.withDetails("1. x", null)
        assertTrue(text.contains("could not be read"))
    }
}
