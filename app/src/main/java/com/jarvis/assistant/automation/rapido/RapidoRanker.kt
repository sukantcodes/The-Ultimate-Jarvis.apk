package com.jarvis.assistant.automation.rapido

import java.util.Locale

/** Filters, compares and recommends ride options. Uses only values that were read from the screen. */
object RapidoRanker {

    /** Options that match the vehicle/tier the user asked for. Unavailable options are kept out of recommendations, not out of the list. */
    fun matching(options: List<FareOption>, request: RideRequest): List<FareOption> {
        var list = options
        request.vehicle?.let { v -> list = list.filter { it.category == v } }
        request.cabTier?.let { tier ->
            val tiered = list.filter { tierMatches(it, tier) }
            // A tier the screen does not show is not guessed; the caller reports it as unmet.
            list = tiered
        }
        return list
    }

    fun tierMatches(option: FareOption, tier: String): Boolean {
        val n = option.name.lowercase(Locale.ROOT)
        return when (tier.lowercase(Locale.ROOT)) {
            "premium" -> Regex("""premium|prime|sedan|comfort""").containsMatchIn(n)
            "xl" -> Regex("""\bxl\b|suv|innova|6 seat""").containsMatchIn(n) || (option.seats ?: 0) >= 6
            "economy" -> Regex("""economy|mini|hatch|\bcab\b""").containsMatchIn(n) && !Regex("""premium|prime|sedan|\bxl\b|suv""").containsMatchIn(n)
            else -> true
        }
    }

    /** Within the user's budget by the HIGHEST price Rapido may charge, so a range never hides a higher fare. */
    fun withinBudget(option: FareOption, budget: Double?): Boolean =
        budget == null || (option.worstCaseFare?.let { it <= budget } ?: false)

    /** Orders available options for the request. Unknown fares sort last. */
    fun rank(options: List<FareOption>, priority: RidePriority): List<FareOption> {
        val available = options.filter { it.available }
        val unavailable = options.filter { !it.available }
        val sorted = when (priority) {
            RidePriority.CHEAPEST -> available.sortedWith(compareBy<FareOption>({ it.expectedFare ?: Double.MAX_VALUE }, { it.etaMinutes ?: Int.MAX_VALUE }))
            RidePriority.FASTEST -> available.sortedWith(compareBy<FareOption>({ it.etaMinutes ?: Int.MAX_VALUE }, { it.expectedFare ?: Double.MAX_VALUE }))
            RidePriority.BALANCED -> available.sortedBy { balancedScore(it, available) }
        }
        return sorted + unavailable
    }

    /** Lower is better: price and wait each count half, both scaled against the best option on screen. */
    private fun balancedScore(o: FareOption, all: List<FareOption>): Double {
        val fares = all.mapNotNull { it.expectedFare }
        val etas = all.mapNotNull { it.etaMinutes?.toDouble() }
        val priceScore = o.expectedFare?.let { f -> if (fares.isEmpty() || fares.min() <= 0) 1.0 else f / fares.min() } ?: 3.0
        val etaScore = o.etaMinutes?.let { e -> if (etas.isEmpty() || etas.min() <= 0) 1.0 else (e.toDouble() + 1) / (etas.min() + 1) } ?: 2.0
        return 0.5 * priceScore + 0.5 * etaScore
    }

    data class Recommendation(val option: FareOption?, val reason: String, val overBudget: Boolean = false)

    /**
     * Picks the best option for the request, or says why none fits. Never invents an option: when the budget excludes
     * everything, the cheapest available option is still named, flagged as over budget.
     */
    fun recommend(options: List<FareOption>, request: RideRequest): Recommendation {
        if (options.isEmpty()) return Recommendation(null, "Rapido showed no ride options I could read.")
        val wanted = matching(options, request)
        if (wanted.isEmpty()) {
            val what = listOfNotNull(request.cabTier, request.vehicle?.spoken?.lowercase(Locale.ROOT)).joinToString(" ").ifBlank { "that ride type" }
            return Recommendation(null, "Rapido is not showing $what for this trip right now. Shown instead: ${options.joinToString { it.name }}.")
        }
        val available = wanted.filter { it.available }
        if (available.isEmpty()) return Recommendation(null, "${wanted.joinToString { it.name }} ${if (wanted.size == 1) "is" else "are"} shown but not available right now.")
        val inBudget = available.filter { withinBudget(it, request.maxBudget) }
        if (request.maxBudget != null && inBudget.isEmpty()) {
            val cheapest = rank(available, RidePriority.CHEAPEST).first()
            return Recommendation(cheapest, "Nothing is within ${RapidoMoney.format(request.maxBudget)}. The cheapest is ${cheapest.name} at ${RapidoMoney.format(cheapest.fare, cheapest.fareMax)}.", overBudget = true)
        }
        val pool = if (request.maxBudget != null) inBudget else available
        val best = rank(pool, request.priority).first()
        val reason = when (request.priority) {
            RidePriority.CHEAPEST -> "lowest fare shown"
            RidePriority.FASTEST -> "shortest pickup time shown"
            RidePriority.BALANCED -> "best balance of fare and pickup time shown"
        } + (if (request.maxBudget != null) ", within ${RapidoMoney.format(request.maxBudget)}" else "")
        return Recommendation(best, reason)
    }

    fun describe(o: FareOption): String {
        val parts = ArrayList<String>()
        parts += o.name + ": " + RapidoMoney.format(o.fare, o.fareMax)
        o.etaMinutes?.let { parts += "pickup in about $it min" }
        o.seats?.let { parts += "$it seats" }
        if (o.features.isNotEmpty()) parts += o.features.joinToString("/")
        if (o.extras.isNotEmpty()) parts += "shown extras: " + o.extras.joinToString("; ")
        if (!o.available) parts += "NOT available now"
        return parts.joinToString(", ")
    }

    /** Side-by-side lines plus the names of the cheapest and fastest available options. */
    fun compare(options: List<FareOption>): String {
        if (options.isEmpty()) return "No options to compare."
        val sb = StringBuilder()
        options.forEachIndexed { i, o -> sb.append(i + 1).append(". ").append(describe(o)).append('\n') }
        val avail = options.filter { it.available }
        rank(avail, RidePriority.CHEAPEST).firstOrNull()?.let { sb.append("Cheapest shown: ").append(it.name).append(" (").append(RapidoMoney.format(it.fare, it.fareMax)).append(")\n") }
        avail.filter { it.etaMinutes != null }.minByOrNull { it.etaMinutes!! }?.let { sb.append("Fastest pickup shown: ").append(it.name).append(" (about ").append(it.etaMinutes).append(" min)\n") }
        return sb.toString().trim()
    }

    /** A stable signature of the fare list, so two reads can be compared for changes. */
    fun signature(options: List<FareOption>): String =
        RideText.sha(options.joinToString(";") { it.key + "@" + (it.fareMax?.toLong() ?: 0L) + "@" + it.available })

    /** Human description of what changed between two reads of the same trip, or null when nothing price-relevant changed. */
    fun diff(before: List<FareOption>, after: List<FareOption>): String? {
        val changes = ArrayList<String>()
        for (a in after) {
            val b = before.firstOrNull { RideText.normalize(it.name) == RideText.normalize(a.name) }
            if (b == null) { changes += "${a.name} newly shown at ${RapidoMoney.format(a.fare, a.fareMax)}"; continue }
            if (b.fare != a.fare || b.fareMax != a.fareMax) changes += "${a.name} changed from ${RapidoMoney.format(b.fare, b.fareMax)} to ${RapidoMoney.format(a.fare, a.fareMax)}"
            if (b.available != a.available) changes += "${a.name} is now ${if (a.available) "available" else "unavailable"}"
        }
        before.filter { b -> after.none { RideText.normalize(it.name) == RideText.normalize(b.name) } }.forEach { changes += "${it.name} is no longer shown" }
        return changes.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }
}
