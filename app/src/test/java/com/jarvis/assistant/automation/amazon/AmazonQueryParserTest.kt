package com.jarvis.assistant.automation.amazon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmazonQueryParserTest {
    private fun p(s: String) = AmazonQueryParser.parse(s)

    @Test fun englishBudgetAndPrime() {
        val r = p("search amazon for wireless mouse under 500 rs prime only 4 star and above")
        assertEquals("wireless mouse", r.query)
        assertEquals(500.0, r.maxPrice!!, 0.0)
        assertTrue(r.primeOnly)
        assertEquals(4.0, r.minRating!!, 0.0)
    }

    @Test fun hinglishBudgetNeeche() {
        val r = p("Amazon pe iPhone 15 128 GB black 60000 ke neeche dikhao")
        assertTrue(r.query, r.query.contains("iphone 15"))
        assertEquals(60000.0, r.maxPrice!!, 0.0)
        assertTrue(r.variants.contains("black"))
        assertTrue(r.variants.contains("128 GB"))
    }

    @Test fun hinglishCheapestWithLakhTak() {
        val r = p("sabse sasta laptop 1 lakh tak")
        assertEquals(100000.0, r.maxPrice!!, 0.0)
        assertEquals(SortPreference.PRICE_LOW, r.sort)
        assertEquals("laptop", r.query)
    }

    @Test fun priceRangeBetween() {
        val r = p("cheapest 2 pieces of boat earbuds between 1000 and 2000")
        assertEquals(1000.0, r.minPrice!!, 0.0)
        assertEquals(2000.0, r.maxPrice!!, 0.0)
        assertEquals(2, r.quantity)
        assertEquals(SortPreference.PRICE_LOW, r.sort)
        assertEquals("boat earbuds", r.query)
    }

    @Test fun hinglishRangeSeTak() {
        val r = p("500 se 1500 tak ki bluetooth speaker chahiye")
        assertEquals(500.0, r.minPrice!!, 0.0)
        assertEquals(1500.0, r.maxPrice!!, 0.0)
        assertTrue(r.query, r.query.contains("bluetooth speaker"))
    }

    @Test fun productNumbersAreNotPrices() {
        val r = p("samsung 4k tv 55 inch")
        assertNull(r.maxPrice); assertNull(r.minPrice)
        assertTrue(r.query, r.query.contains("4k tv"))
        val s = p("iphone 15 pro max")
        assertNull(s.maxPrice)
        assertEquals("iphone 15 pro max", s.query)
    }

    @Test fun thousandSuffixAndDevanagariDigits() {
        assertEquals(20000.0, p("phone under 20k").maxPrice!!, 0.0)
        assertEquals(500.0, p("चार्जर ५०० ke neeche").maxPrice!!, 0.0)
    }

    @Test fun rupeeSignAndCommas() {
        val r = p("headphones below ₹2,999")
        assertEquals(2999.0, r.maxPrice!!, 0.0)
        assertEquals("headphones", r.query)
    }

    @Test fun qualityWordsSetRatingSort() {
        val r = p("best rated air fryer")
        assertEquals(SortPreference.RATING, r.sort)
        assertEquals(4.0, r.minRating!!, 0.0)
        assertEquals("air fryer", r.query)
    }

    @Test fun sizeAndQuantity() {
        val r = p("add running shoes size 9 qty 2")
        assertEquals(2, r.quantity)
        assertTrue(r.variants.contains("size 9"))
        assertEquals("running shoes", r.query)
    }

    @Test fun mergeKeepsExplicitValues() {
        val base = p("laptop under 50000")
        val m = AmazonQueryParser.merge(base, query = "gaming laptop", maxPrice = 70000.0, quantity = 99)
        assertEquals("gaming laptop", m.query)
        assertEquals(70000.0, m.maxPrice!!, 0.0)
        assertEquals(20, m.quantity)
        assertFalse(m.primeOnly)
    }
}
