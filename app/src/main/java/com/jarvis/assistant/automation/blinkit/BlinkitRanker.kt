package com.jarvis.assistant.automation.blinkit

import com.jarvis.assistant.automation.amazon.TitleMatch
import java.util.Locale

/** One wanted item inside a budget plan: what was asked and the in-stock products found for it. */
data class PlanItem(val request: BlinkitRequest, val candidates: List<BlinkitProduct>)

data class PlanChoice(val item: PlanItem, val product: BlinkitProduct, val cost: Double)

data class BudgetPlan(
    val choices: List<PlanChoice>,
    val total: Double,
    val budget: Double,
    /** true when the whole list fits inside the budget. */
    val fits: Boolean,
    val shortfall: Double,
    /** Wanted items that had no in-stock match at all. */
    val missing: List<String>,
    /** When it does not fit: the item whose removal would make it fit with the least loss. */
    val suggestedDrop: String?
)

data class Comparison(
    val lines: List<String>,
    val bestValue: BlinkitProduct?,
    val cheapest: BlinkitProduct?,
    val lowestUnitPrice: BlinkitProduct?,
    val comparableUnits: Boolean,
    val notes: List<String>
)

/**
 * Value-for-money logic. "Best value" is NOT "cheapest": it is the lowest price per kg / litre / item after a modest,
 * evidence-based adjustment for ratings (and for the brand the user asked for). Every number comes from what the
 * screen showed; a unit price is derived from the visible price and visible pack size, and is labelled as derived.
 */
object BlinkitRanker {

    /** Multiplier around 1.0: a well-reviewed product is worth more per rupee. Unknown rating counts as 1.0. */
    fun qualityFactor(p: BlinkitProduct, brand: String? = null): Double {
        var f = 1.0
        val r = p.rating
        if (r != null) {
            val raw = 1.0 + 0.25 * ((r - 3.5) / 1.5).coerceIn(-1.0, 1.0)
            val count = p.ratingCount
            // A rating from a handful of people says little: pull it toward neutral.
            val weight = if (count == null) 1.0 else (count / 50.0).coerceIn(0.0, 1.0)
            f = 1.0 + (raw - 1.0) * weight
        }
        if (!brand.isNullOrBlank() && TitleMatch.normalize(p.name).contains(TitleMatch.normalize(brand))) f *= 1.10
        return f
    }

    /** Price per unit after the quality adjustment, or the plain price when no pack size was read. */
    fun effectiveCost(p: BlinkitProduct, brand: String? = null): Double? {
        val base = p.unitPrice ?: p.price ?: return null
        return base / qualityFactor(p, brand)
    }

    private fun stem(w: String): String = when {
        w.length > 4 && w.endsWith("ies") -> w.dropLast(3) + "y"
        w.length > 4 && w.endsWith("es") -> w.dropLast(2)
        w.length > 3 && w.endsWith("s") -> w.dropLast(1)
        else -> w
    }

    private val SIZE_WORD = Regex("""^\d+(?:\.\d+)?(?:kg|g|gm|ml|l|ltr)?$|^(?:kg|g|gm|ml|l|ltr)$""")

    private fun queryTokens(q: String): Set<String> =
        TitleMatch.normalize(q).split(' ').filter { it.length >= 2 && !SIZE_WORD.matches(it) }.map { stem(it) }.toSet()

    /** Does the result plausibly answer the query? Blinkit also lists related items; those are dropped. */
    fun relevant(p: BlinkitProduct, query: String): Boolean {
        val q = queryTokens(query)
        if (q.isEmpty()) return true
        val n = TitleMatch.normalize(p.name).split(' ').map { stem(it) }.toSet()
        val hit = q.count { it in n }
        return hit.toDouble() / q.size >= 0.5
    }

    /** Hard limits from the request: relevance, price range, brand (only when the user named one and it exists). */
    fun filter(products: List<BlinkitProduct>, request: BlinkitRequest): List<BlinkitProduct> {
        var list = products.filter { it.price != null }
        val relevantOnly = list.filter { relevant(it, request.vague?.label ?: request.query) }
        if (request.vague == null) list = relevantOnly
        request.minPrice?.let { m -> list = list.filter { it.price!! >= m } }
        request.maxPrice?.let { m -> list = list.filter { it.price!! <= m } }
        val brand = request.brand
        if (!brand.isNullOrBlank()) {
            val wanted = list.filter { TitleMatch.normalize(it.name).contains(TitleMatch.normalize(brand)) }
            if (wanted.isNotEmpty()) list = wanted
        }
        return list
    }

    /** The pack kind most results share, so unit prices are only compared like with like. */
    fun dominantKind(products: List<BlinkitProduct>): PackKind? =
        products.mapNotNull { it.pack?.kind }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    fun rank(products: List<BlinkitProduct>, sort: BlinkitSort, brand: String? = null): List<BlinkitProduct> {
        val kind = dominantKind(products)
        fun comparable(p: BlinkitProduct) = p.pack?.kind == kind && kind != null
        val inStockFirst = compareBy<BlinkitProduct> { if (it.available) 0 else 1 }
        return when (sort) {
            BlinkitSort.RELEVANCE -> products.sortedWith(inStockFirst.thenBy { it.position })
            BlinkitSort.PRICE_LOW -> products.sortedWith(inStockFirst.thenBy { it.price ?: Double.MAX_VALUE })
            BlinkitSort.UNIT_PRICE -> products.sortedWith(
                inStockFirst.thenBy { if (comparable(it)) 0 else 1 }.thenBy { if (comparable(it)) it.unitPrice ?: Double.MAX_VALUE else it.price ?: Double.MAX_VALUE }
            )
            BlinkitSort.VALUE -> products.sortedWith(
                inStockFirst.thenBy { if (comparable(it)) 0 else 1 }
                    .thenBy { (if (comparable(it)) effectiveCost(it, brand) else (it.price?.div(qualityFactor(it, brand)))) ?: Double.MAX_VALUE }
            )
            BlinkitSort.RATING -> products.sortedWith(
                inStockFirst.thenByDescending { weightedRating(it) }.thenBy { it.price ?: Double.MAX_VALUE }
            )
        }
    }

    /** Rating that discounts products with very few reviews. */
    private fun weightedRating(p: BlinkitProduct): Double {
        val r = p.rating ?: return 0.0
        val c = p.ratingCount ?: 50
        return (r * c + 3.5 * 20) / (c + 20)
    }

    // ---- Describing and comparing -------------------------------------------------------------------

    fun describe(p: BlinkitProduct): String {
        val sb = StringBuilder(p.name.take(70))
        p.packText?.let { sb.append(" (").append(it).append(")") }
        sb.append(" — ").append(BlinkitMoney.format(p.price))
        if (p.mrp != null && p.price != null && p.mrp > p.price) {
            sb.append(" (MRP ").append(BlinkitMoney.format(p.mrp))
            p.discountPercent?.let { sb.append(", ").append(it).append("% off") }
            sb.append(")")
        }
        p.unitPrice?.let { sb.append(" · ").append(BlinkitMoney.format(Math.round(it * 100) / 100.0)).append(p.pack!!.unitLabel.removePrefix("₹")) }
        sb.append(" · ").append(if (p.rating != null) "★${p.rating}" + (p.ratingCount?.let { " (${compactCount(it)})" } ?: "") else "rating not shown")
        p.etaText?.let { sb.append(" · ").append(it) }
        if (!p.available) sb.append(" · OUT OF STOCK")
        if (p.offers.isNotEmpty()) sb.append(" · offer: ").append(p.offers.first().take(40))
        return sb.toString()
    }

    private fun compactCount(n: Int): String = if (n >= 1000) String.format(Locale.US, "%.1fk", n / 1000.0).replace(".0k", "k") else n.toString()

    fun compare(products: List<BlinkitProduct>, brand: String? = null): Comparison {
        val inStock = products.filter { it.available && it.price != null }
        val kind = dominantKind(inStock)
        val comparable = inStock.filter { it.pack?.kind == kind && kind != null }
        val notes = ArrayList<String>()
        val kindsMixed = inStock.mapNotNull { it.pack?.kind }.distinct().size > 1
        if (kindsMixed) notes += "Pack types differ (for example weight vs count), so unit prices are compared only among the ${kind?.name?.lowercase(Locale.ROOT)} packs."
        if (inStock.any { it.pack == null }) notes += "Some pack sizes were not visible, so their unit price is unavailable (not estimated)."
        if (inStock.any { it.rating == null }) notes += "Some ratings were not shown; those products get no rating adjustment."
        val bestUnit = comparable.minByOrNull { it.unitPrice ?: Double.MAX_VALUE }
        val bestValue = if (comparable.isNotEmpty()) comparable.minByOrNull { effectiveCost(it, brand) ?: Double.MAX_VALUE }
        else inStock.minByOrNull { (it.price ?: Double.MAX_VALUE) / qualityFactor(it, brand) }
        val cheapest = inStock.minByOrNull { it.price ?: Double.MAX_VALUE }
        val lines = products.mapIndexed { i, p -> "${i + 1}. " + describe(p) }
        return Comparison(lines, bestValue, cheapest, bestUnit, !kindsMixed && comparable.size == inStock.size, notes)
    }

    /** In-stock products similar to an unavailable one: same kind of pack, overlapping name words. */
    fun alternatives(unavailable: BlinkitProduct, pool: List<BlinkitProduct>, limit: Int = 3): List<BlinkitProduct> {
        return pool.asSequence()
            .filter { it.available && it.price != null && it.key != unavailable.key }
            .map { it to TitleMatch.containment(it.name, unavailable.name) }
            .filter { (p, sim) ->
                val sameKind = unavailable.pack == null || p.pack == null || p.pack.kind == unavailable.pack.kind
                // Same kind of pack and either mostly the same words, or at least one real category word in common
                // ("milk", "detergent") so another brand of the same thing is offered.
                val sharedCategoryWord = TitleMatch.tokens(p.name).intersect(TitleMatch.tokens(unavailable.name)).any { it.length >= 4 }
                sameKind && (sim >= 0.34 || sharedCategoryWord)
            }
            .sortedWith(compareByDescending<Pair<BlinkitProduct, Double>> { it.second }
                .thenBy { Math.abs((it.first.price ?: 0.0) - (unavailable.price ?: it.first.price ?: 0.0)) })
            .map { it.first }.take(limit).toList()
    }

    // ---- Budget planning ----------------------------------------------------------------------------

    private const val CANDIDATES_PER_ITEM = 4
    private const val EXACT_SEARCH_MAX_ITEMS = 8

    /**
     * Chooses one in-stock product per wanted item so the total (price x quantity) stays inside [budget] and the
     * combined value is highest. Value of a pick = quality factor x (best unit price among that item's candidates /
     * this pick's unit price), so a well-rated, good-per-unit pick scores near its maximum, and spare budget is spent
     * on upgrades only where they genuinely score higher. If nothing fits, the cheapest combination is returned with
     * the shortfall and the item whose removal would fit it.
     */
    fun plan(items: List<PlanItem>, budget: Double, reserve: Double = 0.0): BudgetPlan {
        val limit = budget - reserve
        fun hasStock(item: PlanItem) = item.candidates.any { it.available && it.price != null }
        val missing = items.filterNot { hasStock(it) }.map { it.request.query }
        val active = items.filter { hasStock(it) }
        val pools = active.map { item ->
            val pool = item.candidates.filter { it.available && it.price != null }
            val best = pool.mapNotNull { effectiveCost(it, item.request.brand) }.minOrNull()
            fun utility(p: BlinkitProduct): Double {
                val eff = effectiveCost(p, item.request.brand) ?: return 0.0
                val q = qualityFactor(p, item.request.brand)
                return q * ((best ?: eff) / eff)
            }
            val top = (pool.sortedByDescending { utility(it) }.take(CANDIDATES_PER_ITEM) + pool.minByOrNull { it.price!! }!!).distinctBy { it.key }
            top.map { Triple(it, it.price!! * item.request.quantity, utility(it)) }
        }

        fun cheapestPicks(): List<Triple<BlinkitProduct, Double, Double>> = pools.map { p -> p.minByOrNull { it.second }!! }
        val cheapest = cheapestPicks()
        val cheapestTotal = cheapest.sumOf { it.second }

        val picks: List<Triple<BlinkitProduct, Double, Double>> = when {
            pools.isEmpty() -> emptyList()
            cheapestTotal > limit -> cheapest
            pools.size <= EXACT_SEARCH_MAX_ITEMS -> exactBest(pools, limit)
            else -> greedyBest(pools, cheapest, limit)
        }
        val total = picks.sumOf { it.second }
        val fits = total <= limit + 1e-9
        val choices = active.mapIndexed { i, item -> PlanChoice(item, picks[i].first, picks[i].second) }
        var drop: String? = null
        if (!fits) {
            drop = choices.filter { total - it.cost <= limit + 1e-9 }.minByOrNull { it.cost }?.item?.request?.query
        }
        return BudgetPlan(choices, total, budget, fits, if (fits) 0.0 else total - limit, missing, drop)
    }

    private fun exactBest(pools: List<List<Triple<BlinkitProduct, Double, Double>>>, limit: Double): List<Triple<BlinkitProduct, Double, Double>> {
        var bestUtility = -1.0
        var bestCost = Double.MAX_VALUE
        var best: List<Triple<BlinkitProduct, Double, Double>> = emptyList()
        val minRest = DoubleArray(pools.size + 1)
        for (i in pools.indices.reversed()) minRest[i] = minRest[i + 1] + pools[i].minOf { it.second }
        val current = ArrayList<Triple<BlinkitProduct, Double, Double>>()
        fun dfs(i: Int, cost: Double, utility: Double) {
            if (cost + minRest[i] > limit + 1e-9) return
            if (i == pools.size) {
                if (utility > bestUtility + 1e-9 || (Math.abs(utility - bestUtility) <= 1e-9 && cost < bestCost)) {
                    bestUtility = utility; bestCost = cost; best = ArrayList(current)
                }
                return
            }
            for (c in pools[i]) { current.add(c); dfs(i + 1, cost + c.second, utility + c.third); current.removeAt(current.lastIndex) }
        }
        dfs(0, 0.0, 0.0)
        return best
    }

    private fun greedyBest(
        pools: List<List<Triple<BlinkitProduct, Double, Double>>>,
        start: List<Triple<BlinkitProduct, Double, Double>>,
        limit: Double
    ): List<Triple<BlinkitProduct, Double, Double>> {
        val picks = start.toMutableList()
        var spent = picks.sumOf { it.second }
        while (true) {
            var bestGain = 0.0; var bestIdx = -1; var bestPick: Triple<BlinkitProduct, Double, Double>? = null
            for (i in pools.indices) for (c in pools[i]) {
                val extra = c.second - picks[i].second
                val gain = c.third - picks[i].third
                if (gain > 1e-9 && spent + extra <= limit + 1e-9) {
                    val score = if (extra <= 0) Double.MAX_VALUE else gain / extra
                    if (score > bestGain) { bestGain = score; bestIdx = i; bestPick = c }
                }
            }
            if (bestIdx < 0 || bestPick == null) break
            spent += bestPick.second - picks[bestIdx].second
            picks[bestIdx] = bestPick
        }
        return picks
    }
}
