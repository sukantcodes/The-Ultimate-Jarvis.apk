package com.jarvis.assistant.automation.amazon

import java.util.Locale

/** The result of comparing several products. */
data class Comparison(
    val bestPrice: ProductCandidate?,
    val bestRated: ProductCandidate?,
    val bestValue: ProductCandidate?,
    val lines: List<String>
)

/** Filtering, ranking and comparison of what was read from the screen. */
object AmazonRanker {

    /** Items with this few ratings are treated as unproven. */
    private const val PRIOR_WEIGHT = 50.0
    private const val PRIOR_MEAN = 3.8

    /**
     * Keeps only products that satisfy the request. A product whose value is NOT visible is kept for
     * price and rating filters (it cannot be shown to break them) but flagged by [unverifiedFilters].
     * Prime is enforced only when the card shows Prime.
     */
    fun filter(candidates: List<ProductCandidate>, request: AmazonSearchRequest): List<ProductCandidate> =
        candidates.filter { c ->
            (request.minPrice == null || c.price == null || c.price >= request.minPrice) &&
                (request.maxPrice == null || c.price == null || c.price <= request.maxPrice) &&
                (request.minRating == null || c.rating == null || c.rating >= request.minRating) &&
                (!request.primeOnly || c.prime)
        }

    /** Names the filters that could not be checked for [c] because the card did not show the value. */
    fun unverifiedFilters(c: ProductCandidate, request: AmazonSearchRequest): List<String> {
        val out = ArrayList<String>()
        if ((request.minPrice != null || request.maxPrice != null) && c.price == null) out += "price"
        if (request.minRating != null && c.rating == null) out += "rating"
        return out
    }

    /** Rating pulled toward the average when there are few ratings, so 5.0 from 3 buyers does not win. */
    fun trustedRating(c: ProductCandidate): Double? {
        val r = c.rating ?: return null
        val n = (c.ratingCount ?: 0).toDouble()
        return (r * n + PRIOR_MEAN * PRIOR_WEIGHT) / (n + PRIOR_WEIGHT)
    }

    /** Higher is better. Sponsored cards are slightly penalised because their position is paid for. */
    fun score(c: ProductCandidate): Double {
        val rating = trustedRating(c) ?: PRIOR_MEAN - 0.4
        var s = rating * 10.0
        if (c.prime) s += 0.5
        if (c.sponsored) s -= 0.6
        c.discountPercent?.let { s += minOf(it, 60) / 40.0 }
        return s
    }

    fun rank(candidates: List<ProductCandidate>, sort: SortPreference): List<ProductCandidate> = when (sort) {
        SortPreference.PRICE_LOW -> candidates.sortedWith(compareBy({ it.totalCost ?: Double.MAX_VALUE }, { it.position }))
        SortPreference.PRICE_HIGH -> candidates.sortedWith(compareByDescending<ProductCandidate> { it.totalCost ?: -1.0 }.thenBy { it.position })
        SortPreference.RATING -> candidates.sortedWith(compareByDescending<ProductCandidate> { score(it) }.thenBy { it.totalCost ?: Double.MAX_VALUE })
        SortPreference.NEWEST, SortPreference.RELEVANCE -> candidates.sortedBy { it.position }
    }

    private fun starText(c: ProductCandidate): String =
        c.rating?.let { r ->
            val count = c.ratingCount?.let { n -> " (" + String.format(Locale.US, "%,d", n) + " ratings)" }.orEmpty()
            String.format(Locale.US, "%.1f", r) + "★" + count
        } ?: "rating not shown"

    fun deliveryText(c: ProductCandidate): String = when {
        c.freeDelivery == true -> "free delivery"
        c.deliveryFee != null -> "delivery " + Money.format(c.deliveryFee)
        else -> "delivery charge not shown"
    }

    /** One spoken-style line for a product. */
    fun describe(c: ProductCandidate): String {
        val parts = ArrayList<String>()
        parts += Money.format(c.price)
        c.mrp?.takeIf { it > (c.price ?: 0.0) }?.let { parts += "MRP " + Money.format(it) + (c.discountPercent?.let { d -> " ($d% off)" } ?: "") }
        parts += starText(c)
        if (c.prime) parts += "Prime"
        parts += deliveryText(c)
        c.deliveryText?.takeIf { c.freeDelivery != true }?.let { parts += it.take(40) }
        if (c.sponsored) parts += "sponsored"
        c.offers.firstOrNull()?.let { parts += it.take(50) }
        return c.title.take(80) + " — " + parts.joinToString(", ")
    }

    /** Picks the cheapest, best-rated and best-value product among [items] and writes one line per product. */
    fun compare(items: List<ProductCandidate>): Comparison {
        if (items.isEmpty()) return Comparison(null, null, null, emptyList())
        val pool = items.filter { it.price != null }
        val cheapest = pool.minByOrNull { it.totalCost ?: Double.MAX_VALUE }
        val ratedPool = items.filter { it.rating != null }
        val bestRated = ratedPool.maxByOrNull { score(it) }
        val minCost = pool.mapNotNull { it.totalCost }.minOrNull()
        // Value: trusted rating per rupee relative to the cheapest option.
        val bestValue = pool.filter { it.rating != null }.maxByOrNull { c ->
            val rel = if (minCost != null && minCost > 0 && c.totalCost != null) c.totalCost!! / minCost else 1.0
            (trustedRating(c) ?: 0.0) * 10.0 - (rel - 1.0) * 6.0
        }
        val lines = items.mapIndexed { i, c ->
            val tags = ArrayList<String>()
            if (c.key == cheapest?.key) tags += "lowest price"
            if (c.key == bestRated?.key) tags += "best rated"
            if (c.key == bestValue?.key) tags += "best value"
            (i + 1).toString() + ". " + describe(c) + if (tags.isEmpty()) "" else "  [" + tags.joinToString(", ") + "]"
        }
        return Comparison(cheapest, bestRated, bestValue, lines)
    }

    /** Adds the details read from product pages (specs, seller) under each compared product. */
    fun withDetails(line: String, details: ProductDetails?): String {
        if (details == null) return line + "\n   details: could not be read"
        val sb = StringBuilder(line)
        if (details.unavailable) sb.append("\n   currently unavailable")
        details.seller?.let { sb.append("\n   ").append(it) }
        if (details.specs.isEmpty()) sb.append("\n   specifications: not visible on the page")
        else details.specs.take(5).forEach { sb.append("\n   - ").append(it.take(110)) }
        return sb.toString()
    }
}
