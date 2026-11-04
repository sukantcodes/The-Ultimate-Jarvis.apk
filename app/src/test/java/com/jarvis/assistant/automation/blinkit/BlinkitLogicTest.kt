package com.jarvis.assistant.automation.blinkit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlinkitQueryParserTest {

    @Test fun `the owner's own example parses budget, value and product`() {
        val r = BlinkitQueryParser.parse("Jarvis, ₹200 ke andar best value wala detergent find karo")
        assertEquals("detergent", r.query)
        assertEquals(200.0, r.maxPrice!!, 0.0)
        assertEquals(BlinkitSort.VALUE, r.sort)
    }

    @Test fun `english budget phrases`() {
        assertEquals(60.0, BlinkitQueryParser.parse("milk under 60").maxPrice!!, 0.0)
        assertEquals(150.0, BlinkitQueryParser.parse("show me rice within rs 150").maxPrice!!, 0.0)
        val range = BlinkitQueryParser.parse("oil between 100 and 250")
        assertEquals(100.0, range.minPrice!!, 0.0)
        assertEquals(250.0, range.maxPrice!!, 0.0)
    }

    @Test fun `hinglish budget phrases and devanagari digits`() {
        assertEquals(200.0, BlinkitQueryParser.parse("200 rupaye tak dahi").maxPrice!!, 0.0)
        assertEquals(200.0, BlinkitQueryParser.parse("२०० ke andar shampoo").maxPrice!!, 0.0)
        assertEquals("curd", BlinkitQueryParser.parse("200 rupaye tak dahi").query)
    }

    @Test fun `hinglish grocery words become the english search word`() {
        assertEquals("milk", BlinkitQueryParser.parse("doodh chahiye").query)
        assertEquals("sugar", BlinkitQueryParser.parse("cheeni dikhao").query)
        assertEquals("eggs", BlinkitQueryParser.parse("ande lena hai").query)
    }

    @Test fun `quantity words and numbers`() {
        val r = BlinkitQueryParser.parse("doodh 2 packet")
        assertEquals("milk", r.query)
        assertEquals(2, r.quantity)
        assertEquals(3, BlinkitQueryParser.parse("teen packets bread").quantity)
        assertEquals(2, BlinkitQueryParser.parse("2 eggs").quantity)
    }

    @Test fun `a pack size is a pack hint, never a quantity or a price`() {
        val r = BlinkitQueryParser.parse("atta 5 kg sasta")
        assertEquals(1, r.quantity)
        assertNull(r.maxPrice)
        assertEquals("5 kg", r.packHint)
        assertTrue(r.query.contains("atta"))
        assertTrue(r.query.contains("5 kg"))
        assertEquals(BlinkitSort.PRICE_LOW, r.sort)
    }

    @Test fun `unit price and rating sort words`() {
        assertEquals(BlinkitSort.UNIT_PRICE, BlinkitQueryParser.parse("rice sabse sasta per kg").sort)
        assertEquals(BlinkitSort.RATING, BlinkitQueryParser.parse("top rated biscuits").sort)
        assertEquals(BlinkitSort.VALUE, BlinkitQueryParser.parse("paisa vasool oil").sort)
    }

    @Test fun `a good detergent is a value request for detergent`() {
        val r = BlinkitQueryParser.parse("a good detergent")
        assertEquals("detergent", r.query)
        assertEquals(BlinkitSort.VALUE, r.sort)
        assertNull(r.vague)
    }

    @Test fun `vague asks turn into a theme with concrete searches`() {
        val r = BlinkitQueryParser.parse("something for breakfast")
        assertNotNull(r.vague)
        assertEquals("breakfast", r.vague!!.label)
        assertTrue("bread" in r.vague!!.searchTerms)
        assertNotNull(BlinkitQueryParser.parse("nashta ka kuch saman").vague)
        assertNotNull(BlinkitQueryParser.parse("kuch meetha").vague)
    }

    @Test fun `a concrete product that mentions a theme is not vague`() {
        assertNull(BlinkitQueryParser.parse("breakfast cereal").vague)
    }

    @Test fun `spelling mistakes are repaired against common grocery words`() {
        assertEquals("biscuit", BlinkitQueryParser.correct("biscut"))
        assertEquals("detergent", BlinkitQueryParser.correct("detergnt"))
        assertEquals("shampoo", BlinkitQueryParser.correct("shampo"))
        assertEquals("noodles", BlinkitQueryParser.correct("noodels"))
        assertEquals("biscuit", BlinkitQueryParser.parse("biscut chahiye").query)
    }

    @Test fun `real words and brands are not corrupted by spelling repair`() {
        assertEquals("milky", BlinkitQueryParser.correct("milky"))
        assertEquals("maggi", BlinkitQueryParser.correct("maggi"))
        assertEquals("amul", BlinkitQueryParser.correct("amul"))
        assertEquals("paneer", BlinkitQueryParser.correct("paneer"))
    }

    @Test fun `brands are detected`() {
        assertEquals("amul", BlinkitQueryParser.parse("amul butter 500 g").brand)
        assertEquals("surf excel", BlinkitQueryParser.parse("surf excel 1 kg").brand)
    }

    @Test fun `shopping list with a total budget`() {
        val list = BlinkitQueryParser.parseShoppingList("milk, bread aur 2 eggs total 300 ke andar")
        assertEquals(3, list.items.size)
        assertEquals(300.0, list.totalBudget!!, 0.0)
        assertEquals(listOf("milk", "bread", "eggs"), list.items.map { it.query })
        assertEquals(2, list.items[2].quantity)
        assertTrue(list.items.all { it.maxPrice == null })
    }

    @Test fun `a single item budget is that product's price limit, not a total`() {
        val list = BlinkitQueryParser.parseShoppingList("₹200 ke andar detergent")
        assertEquals(1, list.items.size)
        assertEquals(200.0, list.items[0].maxPrice!!, 0.0)
        assertNull(list.totalBudget)
    }

    @Test fun `explicit tool values override the sentence`() {
        val base = BlinkitQueryParser.parse("detergent under 200")
        val m = BlinkitQueryParser.merge(base, maxPrice = 150.0, quantity = 2, sort = BlinkitSort.UNIT_PRICE)
        assertEquals(150.0, m.maxPrice!!, 0.0)
        assertEquals(2, m.quantity)
        assertEquals(BlinkitSort.UNIT_PRICE, m.sort)
        assertEquals("detergent", m.query)
    }
}

class PackParserTest {
    @Test fun `weights and volumes become base units`() {
        assertEquals(500.0, PackParser.parse("500 g")!!.baseAmount, 0.0)
        assertEquals(1000.0, PackParser.parse("1 kg")!!.baseAmount, 0.0)
        assertEquals(PackKind.VOLUME, PackParser.parse("1 L")!!.kind)
        assertEquals(750.0, PackParser.parse("750 ml")!!.baseAmount, 0.0)
        assertEquals(2500.0, PackParser.parse("2.5 kg")!!.baseAmount, 0.0)
    }

    @Test fun `multipacks and counts`() {
        assertEquals(500.0, PackParser.parse("2 x 250 ml")!!.baseAmount, 0.0)
        assertEquals(PackKind.COUNT, PackParser.parse("pack of 6")!!.kind)
        assertEquals(6.0, PackParser.parse("pack of 6")!!.baseAmount, 0.0)
        assertEquals(12.0, PackParser.parse("12 pcs")!!.baseAmount, 0.0)
        assertEquals(12.0, PackParser.parse("1 dozen")!!.baseAmount, 0.0)
        assertEquals(1000.0, PackParser.parse("250 g pack of 4")!!.baseAmount, 0.0)
    }

    @Test fun `unit price is derived from visible price and pack`() {
        assertEquals(180.0, PackParser.parse("500 g")!!.unitPrice(90.0), 0.001)
        assertEquals(60.0, PackParser.parse("2 L")!!.unitPrice(120.0), 0.001)
        assertEquals(5.0, PackParser.parse("pack of 6")!!.unitPrice(30.0), 0.001)
    }

    @Test fun `no pack text gives no unit price`() {
        assertNull(PackParser.parse(null))
        assertNull(PackParser.parse("Fresh and tasty"))
    }

    @Test fun `pack labels are told apart from product names`() {
        assertTrue(PackParser.looksLikePackLabel("500 g"))
        assertTrue(PackParser.looksLikePackLabel("2 x 250 ml"))
        assertFalse(PackParser.looksLikePackLabel("Amul Taaza Milk 500 ml"))
    }
}

class BlinkitRankerTest {
    private fun p(name: String, pack: String, price: Double, rating: Double? = null, count: Int? = null, available: Boolean = true, mrp: Double? = null) =
        BlinkitProduct(name = name, price = price, mrp = mrp, packText = pack, pack = PackParser.parse(pack), rating = rating, ratingCount = count, available = available)

    private val a = p("Brand A Detergent Powder", "500 g", 90.0, 4.5, 10_000)
    private val b = p("Brand B Detergent Powder", "1 kg", 150.0, 3.9, 200)
    private val c = p("Brand C Detergent Powder", "250 g", 55.0, 4.0, 1_000)
    private val d = p("Brand D Detergent Powder", "1 kg", 155.0, 4.8, 5_000)

    @Test fun `unit price is price over pack size`() {
        assertEquals(180.0, a.unitPrice!!, 0.001)
        assertEquals(150.0, b.unitPrice!!, 0.001)
        assertEquals(220.0, c.unitPrice!!, 0.001)
    }

    @Test fun `best value is not the cheapest and not just the lowest raw unit price`() {
        val cmp = BlinkitRanker.compare(listOf(a, b, c, d))
        assertEquals("Brand C Detergent Powder", cmp.cheapest!!.name)        // lowest price on the shelf
        assertEquals("Brand B Detergent Powder", cmp.lowestUnitPrice!!.name) // lowest raw ₹/kg
        assertEquals("Brand D Detergent Powder", cmp.bestValue!!.name)       // ₹155/kg but 4.8★ from 5,000 buyers
    }

    @Test fun `a perfect rating from a handful of people barely counts`() {
        val few = p("Rare", "1 kg", 100.0, 5.0, 3)
        val many = p("Popular", "1 kg", 100.0, 5.0, 5_000)
        assertTrue(BlinkitRanker.qualityFactor(few) < 1.03)
        assertTrue(BlinkitRanker.qualityFactor(many) > 1.2)
    }

    @Test fun `a missing rating is neutral and is flagged, not invented`() {
        val unrated = p("No rating", "1 kg", 100.0)
        assertEquals(1.0, BlinkitRanker.qualityFactor(unrated), 0.0)
        val cmp = BlinkitRanker.compare(listOf(unrated, a))
        assertTrue(cmp.notes.any { it.contains("ratings were not shown") })
    }

    @Test fun `budget filter keeps only products within the limit`() {
        val req = BlinkitRequest("detergent powder", maxPrice = 100.0, sort = BlinkitSort.VALUE)
        val kept = BlinkitRanker.filter(listOf(a, b, c, d), req)
        assertEquals(setOf("Brand A Detergent Powder", "Brand C Detergent Powder"), kept.map { it.name }.toSet())
    }

    @Test fun `within a 200 rupee budget the best value pick is chosen`() {
        val req = BlinkitRequest("detergent powder", maxPrice = 200.0, sort = BlinkitSort.VALUE)
        val ranked = BlinkitRanker.rank(BlinkitRanker.filter(listOf(a, b, c, d), req), req.sort)
        assertEquals("Brand D Detergent Powder", ranked.first().name)
    }

    @Test fun `results that do not match the query are dropped`() {
        val soap = p("Dettol Antiseptic Soap", "125 g", 45.0)
        val kept = BlinkitRanker.filter(listOf(a, soap), BlinkitRequest("detergent powder"))
        assertEquals(listOf("Brand A Detergent Powder"), kept.map { it.name })
    }

    @Test fun `requested brand is preferred when it exists`() {
        val kept = BlinkitRanker.filter(listOf(a, d), BlinkitRequest("detergent powder", brand = "brand d"))
        assertEquals(listOf("Brand D Detergent Powder"), kept.map { it.name })
    }

    @Test fun `out of stock items are never recommended and sort last`() {
        val gone = p("Brand E Detergent Powder", "1 kg", 60.0, 4.9, 9_000, available = false)
        val cmp = BlinkitRanker.compare(listOf(gone, a, b))
        assertFalse(cmp.bestValue!!.name == gone.name)
        assertFalse(cmp.cheapest!!.name == gone.name)
        assertEquals("Brand E Detergent Powder", BlinkitRanker.rank(listOf(gone, a), BlinkitSort.PRICE_LOW).last().name)
    }

    @Test fun `alternatives for an unavailable product are in stock and similar`() {
        val gone = p("Brand E Detergent Powder", "1 kg", 120.0, available = false)
        val soap = p("Dettol Antiseptic Soap", "125 g", 45.0)
        val alt = BlinkitRanker.alternatives(gone, listOf(a, b, soap, gone))
        assertTrue(alt.isNotEmpty())
        assertTrue(alt.all { it.available && it.name.contains("Detergent") })
    }

    @Test fun `mixed pack types are not compared by unit price`() {
        val eggs = p("Farm Eggs", "pack of 6", 60.0)
        val cmp = BlinkitRanker.compare(listOf(a, b, eggs))
        assertTrue(cmp.notes.any { it.contains("differ") })
        assertEquals("Brand B Detergent Powder", cmp.lowestUnitPrice!!.name)
    }

    @Test fun `description shows verified fields and says when a rating is missing`() {
        assertTrue(BlinkitRanker.describe(a).contains("₹180/kg"))
        assertTrue(BlinkitRanker.describe(p("Plain", "500 g", 50.0)).contains("rating not shown"))
    }
}

class BudgetPlanTest {
    private fun p(name: String, pack: String, price: Double, rating: Double? = null, count: Int? = null) =
        BlinkitProduct(name = name, price = price, packText = pack, pack = PackParser.parse(pack), rating = rating, ratingCount = count)

    private val milk = PlanItem(BlinkitRequest("milk"), listOf(
        p("Amul Taaza Toned Milk", "500 ml", 29.0, 4.5, 50_000),
        p("Mother Dairy Full Cream Milk", "1 L", 68.0, 4.2, 10_000)))
    private val bread = PlanItem(BlinkitRequest("bread"), listOf(
        p("Britannia Brown Bread", "400 g", 55.0, 4.5, 8_000),
        p("Harvest Gold White Bread", "400 g", 40.0, 4.0, 6_000),
        p("Cheap Loaf Bread", "300 g", 25.0, 3.4, 300)))
    private val eggs = PlanItem(BlinkitRequest("eggs", quantity = 2), listOf(
        p("Farm Fresh Eggs", "pack of 6", 60.0, 4.4, 4_000),
        p("Country Eggs", "pack of 6", 75.0, 4.6, 2_000)))

    @Test fun `a generous budget picks the highest value combination`() {
        val plan = BlinkitRanker.plan(listOf(milk, bread, eggs), 1_000.0)
        assertTrue(plan.fits)
        assertEquals(3, plan.choices.size)
        assertTrue(plan.total <= 1_000.0)
    }

    @Test fun `the plan never goes over the budget when a fit exists`() {
        val cheapestTotal = 29.0 + 25.0 + 60.0 * 2
        val plan = BlinkitRanker.plan(listOf(milk, bread, eggs), cheapestTotal + 5)
        assertTrue(plan.fits)
        assertTrue(plan.total <= cheapestTotal + 5 + 1e-9)
    }

    @Test fun `a budget exactly equal to the cheapest combination still fits`() {
        val cheapestTotal = 29.0 + 25.0 + 60.0 * 2
        val plan = BlinkitRanker.plan(listOf(milk, bread, eggs), cheapestTotal)
        assertTrue(plan.fits)
        assertEquals(cheapestTotal, plan.total, 1e-9)
    }

    @Test fun `quantity is part of the cost`() {
        val plan = BlinkitRanker.plan(listOf(eggs), 500.0)
        assertEquals(plan.choices.first().product.price!! * 2, plan.choices.first().cost, 1e-9)
    }

    @Test fun `when nothing fits the cheapest combination is returned with the shortfall and a drop suggestion`() {
        val plan = BlinkitRanker.plan(listOf(milk, bread, eggs), 100.0)
        assertFalse(plan.fits)
        assertEquals(29.0 + 25.0 + 120.0, plan.total, 1e-9)
        assertEquals(plan.total - 100.0, plan.shortfall, 1e-9)
        assertEquals("eggs", plan.suggestedDrop)
    }

    @Test fun `an item with nothing in stock is reported missing and the rest are still planned`() {
        val none = PlanItem(BlinkitRequest("ghee"), listOf(BlinkitProduct(name = "Ghee", price = 500.0, available = false)))
        val plan = BlinkitRanker.plan(listOf(milk, none), 200.0)
        assertEquals(listOf("ghee"), plan.missing)
        assertEquals(1, plan.choices.size)
    }

    @Test fun `reserve for fees is kept out of the item budget`() {
        val plan = BlinkitRanker.plan(listOf(milk, bread), 100.0, reserve = 40.0)
        assertTrue(plan.total <= 60.0 + 1e-9)
    }

    @Test fun `long lists fall back to a greedy plan and still respect the budget`() {
        val many = (1..10).map { i ->
            PlanItem(BlinkitRequest("item$i"), listOf(p("Item$i Cheap", "500 g", 20.0 + i, 3.8, 500), p("Item$i Premium", "500 g", 40.0 + i, 4.8, 5_000)))
        }
        val cheapest = many.sumOf { 20.0 + many.indexOf(it) + 1 }
        val plan = BlinkitRanker.plan(many, cheapest + 60)
        assertTrue(plan.fits)
        assertTrue(plan.total <= cheapest + 60 + 1e-9)
    }
}
