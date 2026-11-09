package com.jarvis.assistant.automation.blinkit

import com.jarvis.assistant.automation.amazon.AmazonNode
import com.jarvis.assistant.automation.amazon.AmazonScreenReader
import com.jarvis.assistant.automation.amazon.TitleMatch
import com.jarvis.assistant.automation.rapido.LocationAccess
import com.jarvis.assistant.automation.rapido.LocationState
import java.util.Locale

/** Everything the agent needs from the phone. The Android implementation lives in AndroidBlinkitDriver. */
interface BlinkitDriver {
    fun isInstalled(): Boolean
    fun foregroundPackage(): String?
    /** Visible nodes of the active window, or null when the screen cannot be read. */
    suspend fun snapshot(): List<AmazonNode>?
    /** Opens [url] in the Blinkit app, or just the app's home when [url] is null; true only when Blinkit came to the front. */
    suspend fun launch(url: String?): Boolean
    suspend fun click(node: AmazonNode): Boolean
    /** Types into the field and returns true only when the text was read back from it. */
    suspend fun typeInto(node: AmazonNode, text: String): Boolean
    suspend fun submitSearch(): Boolean
    suspend fun scroll(down: Boolean): Boolean
    suspend fun back(): Boolean
    suspend fun pause(ms: Long)
    fun now(): Long
    fun cancelled(): Boolean
    fun progress(message: String)
    suspend fun locationState(): LocationState
}

object BlinkitStatus {
    const val OK = "OK"
    const val NEEDS_SIGN_IN = "NEEDS_SIGN_IN"
    const val NEEDS_USER = "NEEDS_USER"
    const val NEEDS_ADDRESS = "NEEDS_ADDRESS"
    const val NEEDS_PAYMENT_AUTH = "NEEDS_PAYMENT_AUTH"
    const val OFFLINE = "OFFLINE"
    const val ERROR = "ERROR"
    const val TIMEOUT = "TIMEOUT"
    const val CANCELLED = "CANCELLED"
    const val NOT_FOUND = "NOT_FOUND"
    const val AMBIGUOUS = "AMBIGUOUS"
    const val UNAVAILABLE = "UNAVAILABLE"
    const val NOT_SERVICEABLE = "NOT_SERVICEABLE"
    const val STORE_CLOSED = "STORE_CLOSED"
    const val MIN_ORDER = "MIN_ORDER"
    const val OVER_BUDGET = "OVER_BUDGET"
    const val MISMATCH = "MISMATCH"
    const val NOT_VERIFIED = "NOT_VERIFIED"
    const val LAUNCH_FAILED = "LAUNCH_FAILED"
    const val SCREEN_UNREADABLE = "SCREEN_UNREADABLE"
    const val WRONG_SCREEN = "WRONG_SCREEN"
    const val REFUSED = "REFUSED"
    const val PLACED = "PLACED"
    const val PREPARED = "PREPARED"
    const val NEEDS_LOCATION = "NEEDS_LOCATION"
}

data class BlinkitResult(
    val success: Boolean,
    val status: String,
    val message: String,
    val verified: Boolean = false,
    val extra: Map<String, Any?> = emptyMap()
)

/** Remembers what the last search showed, the budget plan and what was already added, so follow-ups refer to real things. */
class BlinkitSession {
    @Volatile var lastRequest: BlinkitRequest? = null
    @Volatile var lastResults: List<BlinkitProduct> = emptyList()
    @Volatile var plan: BudgetPlan? = null
    @Volatile var lastCart: CartSnapshot? = null
    private val added = HashMap<String, Long>()

    @Synchronized fun recentlyAdded(key: String, now: Long, windowMs: Long = 120_000L): Boolean = added[key]?.let { now - it < windowMs } == true
    @Synchronized fun noteAdded(key: String, now: Long) { added[key] = now }
    @Synchronized fun clearAdded() { added.clear() }
}

class BlinkitAgent(
    private val driver: BlinkitDriver,
    private val gate: BlinkitGate,
    private val session: BlinkitSession = BlinkitSession()
) {
    companion object {
        const val SEARCH_BUDGET_MS = 90_000L
        const val PLAN_BUDGET_MS = 200_000L
        const val CART_BUDGET_MS = 80_000L
        const val CHECKOUT_BUDGET_MS = 100_000L
        const val ORDER_VERIFY_MS = 30_000L
        const val STATUS_BUDGET_MS = 60_000L
        const val MAX_SCROLL_PAGES = 3
        const val MAX_RESULTS_KEPT = 15
        const val MAX_PLAN_ITEMS = 6
        const val MAX_QUANTITY = 20
        private val UNIT_NOTE = "Unit prices are calculated by Jarvis from the visible price and pack size; prices, packs and ratings are read from the screen."
    }

    private class Deadline(private val driver: BlinkitDriver, budgetMs: Long) {
        private val end = driver.now() + budgetMs
        fun left(): Long = end - driver.now()
        fun expired(): Boolean = left() <= 0
    }

    private class Waited(val screen: BlinkitScreen, val nodes: List<AmazonNode>, val code: String? = null)

    // ---- Waiting and recovery -----------------------------------------------------------------------

    private fun noResults(nodes: List<AmazonNode>): Boolean {
        val t = nodes.joinToString(" ") { it.haystack }
        return t.contains("no results") || t.contains("no items found") || t.contains("couldn't find") || t.contains("could not find") || t.contains("nothing found")
    }

    /**
     * Reads the screen until it is one of [accept] or a state only the user can fix. Along the way it waits out loading,
     * taps "Try again" on error and offline pages (with growing pauses), dismisses harmless pop-ups ("Not now") and
     * reopens [reopenUrl] when the page stays broken. Recovery is bounded: three retries, then an honest result.
     */
    private suspend fun waitFor(
        deadline: Deadline,
        timeoutMs: Long,
        reopenUrl: String?,
        accept: Set<BlinkitScreen>,
        homeLimit: Int = Int.MAX_VALUE,
        reopenApp: Boolean = false
    ): Waited {
        val limit = Deadline(driver, minOf(timeoutMs, deadline.left()))
        var recoveries = 0
        var dismissals = 0
        var unknownStreak = 0
        var homeStreak = 0
        var last = Waited(BlinkitScreen.UNKNOWN, emptyList(), "TIMEOUT")
        while (!limit.expired()) {
            if (driver.cancelled()) return Waited(last.screen, last.nodes, "CANCELLED")
            val nodes = driver.snapshot()
            if (nodes == null) { last = Waited(BlinkitScreen.UNKNOWN, emptyList(), "UNREADABLE"); driver.pause(500); continue }
            val screen = BlinkitScreenReader.classify(nodes)
            last = Waited(screen, nodes)
            if (screen in accept) return last
            if (BlinkitScreen.SEARCH_RESULTS in accept && noResults(nodes) && nodes.any { it.editable }) return Waited(screen, nodes, "NO_RESULTS")
            homeStreak = if (screen == BlinkitScreen.HOME) homeStreak + 1 else 0
            if (homeStreak >= homeLimit) return last
            when (screen) {
                BlinkitScreen.SIGN_IN, BlinkitScreen.CHALLENGE, BlinkitScreen.NOT_SERVICEABLE, BlinkitScreen.STORE_CLOSED -> return last
                BlinkitScreen.LOADING -> driver.pause(450)
                BlinkitScreen.OFFLINE, BlinkitScreen.ERROR -> {
                    if (recoveries >= 3) return last
                    recoveries++
                    driver.progress("Blinkit page had a problem; retrying ($recoveries of 3)")
                    driver.pause(1_200L * recoveries)
                    val retry = BlinkitScreenReader.retryButton(nodes)
                    if (retry != null) driver.click(retry)
                    else if (reopenUrl != null) driver.launch(reopenUrl)
                    else if (reopenApp) driver.launch(null)
                    driver.pause(900)
                }
                else -> {
                    val dismiss = BlinkitScreenReader.dismissButton(nodes)
                    if (dismiss != null && dismissals < 3) { dismissals++; driver.click(dismiss); driver.pause(500) }
                    else {
                        unknownStreak++
                        if (unknownStreak == 8 && reopenUrl != null) driver.launch(reopenUrl)
                        driver.pause(450)
                    }
                }
            }
        }
        return Waited(last.screen, last.nodes, last.code ?: "TIMEOUT")
    }

    private fun screenName(s: BlinkitScreen) = s.name.lowercase(Locale.ROOT).replace('_', ' ')

    /** Converts a screen the agent cannot work with into an honest result. */
    private fun stuck(w: Waited, doing: String): BlinkitResult = when {
        w.code == "CANCELLED" -> BlinkitResult(false, BlinkitStatus.CANCELLED, "Stopped while $doing, as you asked.")
        w.screen == BlinkitScreen.SIGN_IN -> BlinkitResult(false, BlinkitStatus.NEEDS_SIGN_IN,
            "Blinkit is asking you to log in. Please log in yourself (Jarvis never enters phone numbers, OTPs or passwords for you), then tell me to continue.")
        w.screen == BlinkitScreen.CHALLENGE -> BlinkitResult(false, BlinkitStatus.NEEDS_USER,
            "Blinkit is asking for ${BlinkitScreenReader.challengeKind(w.nodes)}. Please complete it yourself; Jarvis does not enter or bypass security checks. Tell me to continue afterwards.")
        w.screen == BlinkitScreen.NOT_SERVICEABLE -> BlinkitResult(false, BlinkitStatus.NOT_SERVICEABLE,
            "Blinkit says it does not deliver to the selected location${BlinkitScreenReader.address(w.nodes)?.let { " ($it)" } ?: ""}. " +
                "I did not change the address. Tell me if you want to pick a different saved address.")
        w.screen == BlinkitScreen.STORE_CLOSED -> BlinkitResult(false, BlinkitStatus.STORE_CLOSED,
            "Blinkit is not taking orders right now (the app shows a high-demand or closed notice). Nothing was changed. Try again a little later.")
        w.screen == BlinkitScreen.OFFLINE -> BlinkitResult(false, BlinkitStatus.OFFLINE, "Blinkit cannot load because the phone seems to be offline. Check the internet connection and tell me to try again.")
        w.screen == BlinkitScreen.ERROR -> BlinkitResult(false, BlinkitStatus.ERROR, "Blinkit kept showing an error page while $doing, even after retries.")
        w.code == "UNREADABLE" -> BlinkitResult(false, BlinkitStatus.SCREEN_UNREADABLE, "The screen could not be read. Make sure the Jarvis accessibility service is on and the Blinkit screen is visible.")
        else -> BlinkitResult(false, BlinkitStatus.TIMEOUT, "Blinkit did not reach the expected screen while $doing within the time limit (last screen seen: ${screenName(w.screen)}).")
    }

    private suspend fun open(url: String?): Boolean {
        for (attempt in 1..2) {
            if (driver.cancelled()) return false
            if (driver.launch(url)) return true
            driver.pause(700)
        }
        return false
    }

    private fun launchFailure(): BlinkitResult = if (driver.cancelled()) BlinkitResult(false, BlinkitStatus.CANCELLED, "Stopped, as you asked.") else BlinkitResult(false, BlinkitStatus.LAUNCH_FAILED,
        if (driver.isInstalled()) "Could not bring the Blinkit app to the front. Open Blinkit once yourself, then try again."
        else "The Blinkit app is not installed, so I cannot shop there. Install Blinkit and log in, then try again.")

    // ---- Search -------------------------------------------------------------------------------------

    private class Collected(val error: BlinkitResult?, val products: List<BlinkitProduct>, val noResults: Boolean = false)

    /** Opens Blinkit's search for [query] and reads every product it can see (scrolling a few pages). */
    private suspend fun collect(query: String, deadline: Deadline, maxPages: Int = MAX_SCROLL_PAGES): Collected {
        val url = BlinkitLinks.searchUrl(query)
        if (!open(url)) return Collected(launchFailure(), emptyList())
        val results = setOf(BlinkitScreen.SEARCH_RESULTS, BlinkitScreen.PRODUCT)
        var waited = waitFor(deadline, 18_000, url, results, homeLimit = 4)
        if (waited.code == "NO_RESULTS") return Collected(null, emptyList(), noResults = true)
        if (waited.screen !in results && waited.code != "CANCELLED" && waited.code != "UNREADABLE" &&
            waited.screen in setOf(BlinkitScreen.HOME, BlinkitScreen.UNKNOWN, BlinkitScreen.CART, BlinkitScreen.ORDERS_LIST, BlinkitScreen.ADDRESS_PICKER)
        ) {
            driver.progress("The link did not open the results; typing the search instead")
            waited = typedSearch(query, deadline, waited)
            if (waited.code == "NO_RESULTS") return Collected(null, emptyList(), noResults = true)
        }
        if (waited.screen !in results) return Collected(stuck(waited, "searching for $query"), emptyList())

        val found = LinkedHashMap<String, BlinkitProduct>()
        var nodes = waited.nodes
        var quiet = 0
        for (page in 0..maxPages) {
            if (driver.cancelled()) return Collected(BlinkitResult(false, BlinkitStatus.CANCELLED, "Stopped the Blinkit search."), emptyList())
            val before = found.size
            BlinkitScreenReader.extractProducts(nodes).forEach { p -> if (!found.containsKey(p.key)) found[p.key] = p.copy(position = found.size) }
            driver.progress("Reading results: ${found.size} products so far")
            quiet = if (found.size == before) quiet + 1 else 0
            if (found.size >= 24 || quiet >= 2 || page == maxPages || deadline.expired()) break
            if (!driver.scroll(true)) break
            driver.pause(700)
            val next = driver.snapshot() ?: break
            if (BlinkitScreenReader.classify(next) !in results) break
            nodes = next
        }
        return Collected(null, found.values.toList())
    }

    private suspend fun typedSearch(query: String, deadline: Deadline, from: Waited): Waited {
        var nodes = from.nodes
        for (attempt in 1..2) {
            if (driver.cancelled() || deadline.expired()) break
            var box = BlinkitScreenReader.searchBox(nodes)
            if (box == null) { driver.pause(500); nodes = driver.snapshot() ?: continue; box = BlinkitScreenReader.searchBox(nodes) }
            if (box == null) continue
            driver.click(box)
            driver.pause(600)
            val fresh = driver.snapshot() ?: continue
            val field = fresh.firstOrNull { it.editable } ?: continue
            if (!driver.typeInto(field, query)) { driver.pause(400); continue }
            val submitted = driver.submitSearch() || run {
                val after = driver.snapshot().orEmpty()
                val suggestion = AmazonScreenReader.findByLabel(after) { l -> l == query.lowercase(Locale.ROOT).trim() }
                suggestion != null && driver.click(suggestion)
            }
            if (!submitted) continue
            val w = waitFor(deadline, 16_000, null, setOf(BlinkitScreen.SEARCH_RESULTS, BlinkitScreen.PRODUCT))
            if (w.screen in setOf(BlinkitScreen.SEARCH_RESULTS, BlinkitScreen.PRODUCT) || w.code != null || w.screen in setOf(BlinkitScreen.SIGN_IN, BlinkitScreen.CHALLENGE)) return w
            nodes = w.nodes
        }
        return Waited(BlinkitScreen.UNKNOWN, nodes, "TIMEOUT")
    }

    private fun describeLimits(r: BlinkitRequest): String {
        val p = ArrayList<String>()
        if (r.minPrice != null) p += "from ${BlinkitMoney.format(r.minPrice)}"
        if (r.maxPrice != null) p += "up to ${BlinkitMoney.format(r.maxPrice)}"
        if (r.brand != null) p += "brand ${r.brand}"
        if (r.packHint != null) p += "pack ${r.packHint}"
        if (r.sort != BlinkitSort.RELEVANCE) p += "ranked by ${r.sort.name.lowercase(Locale.ROOT).replace('_', ' ')}"
        return if (p.isEmpty()) "no extra limits" else p.joinToString(", ")
    }

    suspend fun search(request: BlinkitRequest): BlinkitResult {
        if (request.query.isBlank()) return BlinkitResult(false, BlinkitStatus.NOT_FOUND, "Tell me what to search for on Blinkit.")
        if (request.vague != null) return suggest(request)
        val deadline = Deadline(driver, SEARCH_BUDGET_MS)
        driver.progress("Searching Blinkit for ${request.query}")
        val got = collect(request.query, deadline)
        got.error?.let { return it }
        if (got.noResults || got.products.isEmpty()) {
            return BlinkitResult(false, BlinkitStatus.NOT_FOUND,
                "Blinkit showed no product I could read for '${request.query}'. Nothing was guessed. Try a different name or spelling.")
        }
        val relevant = BlinkitRanker.filter(got.products, request.copy(minPrice = null, maxPrice = null, brand = null))
        val matching = BlinkitRanker.filter(got.products, request)
        val ranked = BlinkitRanker.rank(matching, request.sort, request.brand).take(MAX_RESULTS_KEPT)
        session.lastRequest = request
        session.lastResults = ranked
        val inStock = ranked.filter { it.available }
        val unavailableTop = relevant.filter { !it.available }

        if (ranked.isEmpty()) {
            val cheapest = relevant.filter { it.available }.minByOrNull { it.price ?: Double.MAX_VALUE }
            val msg = if (relevant.isEmpty())
                "Blinkit showed ${got.products.size} products, but none looked like '${request.query}'."
            else "Blinkit has '${request.query}', but none matched your limits (${describeLimits(request)})." +
                (cheapest?.let { " The cheapest available is ${BlinkitRanker.describe(it)}." } ?: "")
            return BlinkitResult(true, BlinkitStatus.NOT_FOUND, msg, verified = true, extra = mapOf("seen" to got.products.size))
        }
        val top = ranked.take(5)
        val comparison = BlinkitRanker.compare(inStock, request.brand)
        val sb = StringBuilder("Found ${ranked.size} matching products (read from Blinkit's screen). Top ${top.size}:\n")
        top.forEachIndexed { i, p -> sb.append(i + 1).append(". ").append(BlinkitRanker.describe(p)).append('\n') }
        if (inStock.isEmpty()) {
            sb.append("Every match is out of stock at your delivery address.")
            val alt = BlinkitRanker.alternatives(ranked.first(), got.products)
            if (alt.isNotEmpty()) sb.append(" In-stock alternatives: ").append(alt.joinToString("; ") { BlinkitRanker.describe(it) })
        } else {
            comparison.bestValue?.let { sb.append("Best value: ").append(it.name.take(60)).append(it.packText?.let { p -> " ($p)" } ?: "").append(" at ").append(BlinkitMoney.format(it.price)).append('\n') }
            comparison.cheapest?.takeIf { it.key != comparison.bestValue?.key }?.let { sb.append("Cheapest: ").append(it.name.take(60)).append(" at ").append(BlinkitMoney.format(it.price)).append('\n') }
            unavailableTop.firstOrNull { u -> ranked.take(3).any { TitleMatch.containment(it.name, u.name) >= 0.5 } || request.brand != null }?.let { u ->
                val alt = BlinkitRanker.alternatives(u, got.products)
                sb.append(u.name.take(50)).append(" is out of stock here")
                if (alt.isNotEmpty()) sb.append("; alternatives: ").append(alt.joinToString("; ") { it.name.take(40) + " " + BlinkitMoney.format(it.price) })
                sb.append('\n')
            }
            comparison.notes.forEach { sb.append("Note: ").append(it).append('\n') }
            sb.append(UNIT_NOTE)
        }
        return BlinkitResult(true, BlinkitStatus.OK, sb.toString().trim(), verified = true, extra = mapOf(
            "count" to ranked.size, "in_stock" to inStock.size, "request" to describeLimits(request),
            "best_value" to comparison.bestValue?.name
        ))
    }

    /** Vague asks ("something for breakfast"): runs a few concrete searches and suggests in-stock items from each. */
    private suspend fun suggest(request: BlinkitRequest): BlinkitResult {
        val intent = request.vague!!
        val deadline = Deadline(driver, SEARCH_BUDGET_MS * 2)
        val sb = StringBuilder("Ideas for ${intent.label} (read from Blinkit's screen):\n")
        val all = ArrayList<BlinkitProduct>()
        var searched = 0
        for (term in intent.searchTerms.take(4)) {
            if (deadline.expired() || driver.cancelled()) break
            driver.progress("Looking for $term")
            val got = collect(term, Deadline(driver, 40_000L), maxPages = 1)
            got.error?.let { if (searched == 0) return it }
            val limited = BlinkitRanker.filter(got.products, BlinkitRequest(term, request.minPrice, request.maxPrice))
            val best = BlinkitRanker.rank(limited.filter { it.available }, BlinkitSort.VALUE).take(2)
            searched++
            if (best.isNotEmpty()) { sb.append("• ").append(term).append(": ").append(best.joinToString(" / ") { it.name.take(40) + " " + BlinkitMoney.format(it.price) }).append('\n'); all += best }
            else sb.append("• ").append(term).append(": nothing in stock matched\n")
        }
        if (all.isEmpty()) return BlinkitResult(false, BlinkitStatus.NOT_FOUND, "I searched Blinkit for ${intent.label} but could not read any matching in-stock product.")
        session.lastRequest = request
        session.lastResults = all
        sb.append("Say which ones you want and I will add them. Search a specific item for a full price and unit-price comparison.")
        return BlinkitResult(true, BlinkitStatus.OK, sb.toString().trim(), verified = true, extra = mapOf("count" to all.size))
    }

    // ---- Compare ------------------------------------------------------------------------------------

    suspend fun compare(indices: List<Int>): BlinkitResult {
        val results = session.lastResults
        if (results.isEmpty()) return BlinkitResult(false, BlinkitStatus.NOT_FOUND, "There are no search results to compare yet. Search first.")
        val chosen = (if (indices.isEmpty()) (1..minOf(4, results.size)).toList() else indices).distinct().filter { it in 1..results.size }.take(5)
        if (chosen.size < 2) return BlinkitResult(false, BlinkitStatus.NOT_FOUND, "Pick at least two results from the last search to compare.")
        val items = chosen.map { results[it - 1] }
        val brand = session.lastRequest?.brand
        val c = BlinkitRanker.compare(items, brand)
        val sb = StringBuilder("Comparison of ${items.size} products (prices, packs and ratings read from Blinkit's screen):\n")
        sb.append(c.lines.joinToString("\n"))
        c.bestValue?.let { sb.append("\nBest value: ").append(it.name.take(60)).append(" — lowest price per unit after a modest rating adjustment") }
        c.lowestUnitPrice?.let { sb.append("\nLowest unit price: ").append(it.name.take(60)).append(" at ").append(BlinkitMoney.format(it.unitPrice?.let { u -> Math.round(u * 100) / 100.0 })).append(it.pack!!.unitLabel.removePrefix("₹")) }
        c.cheapest?.let { sb.append("\nLowest price: ").append(it.name.take(60)).append(" at ").append(BlinkitMoney.format(it.price)) }
        c.notes.forEach { sb.append("\nNote: ").append(it) }
        sb.append('\n').append(UNIT_NOTE)
        return BlinkitResult(true, BlinkitStatus.OK, sb.toString(), verified = true, extra = mapOf("compared" to chosen))
    }

    // ---- Budget plan --------------------------------------------------------------------------------

    /** Searches every item of the list, then picks the best-value combination whose total stays inside the budget. */
    suspend fun planBudget(list: ShoppingList, reserve: Double): BlinkitResult {
        if (list.items.isEmpty()) return BlinkitResult(false, BlinkitStatus.NOT_FOUND, "Tell me what items to plan, for example 'milk, bread and eggs within 300 rupees'.")
        val wanted = list.items.take(MAX_PLAN_ITEMS)
        val deadline = Deadline(driver, PLAN_BUDGET_MS)
        val planItems = ArrayList<PlanItem>()
        for ((i, req) in wanted.withIndex()) {
            if (driver.cancelled()) return BlinkitResult(false, BlinkitStatus.CANCELLED, "Stopped planning, as you asked.")
            if (deadline.expired()) return BlinkitResult(false, BlinkitStatus.TIMEOUT, "Planning took too long after reading ${planItems.size} of ${wanted.size} items. Try fewer items.")
            driver.progress("Checking ${req.query} (${i + 1} of ${wanted.size})")
            val got = collect(req.query, Deadline(driver, minOf(40_000L, deadline.left())), maxPages = 1)
            got.error?.let { return it }
            val usable = BlinkitRanker.filter(got.products, req.copy(minPrice = null, maxPrice = null)).filter { it.available }
            planItems += PlanItem(req, usable)
        }
        val budget = list.totalBudget
        if (budget == null) {
            val lines = planItems.map { item ->
                val best = BlinkitRanker.rank(item.candidates, BlinkitSort.VALUE, item.request.brand).firstOrNull()
                "• ${item.request.query}: " + (best?.let { BlinkitRanker.describe(it) } ?: "nothing in stock found")
            }
            return BlinkitResult(true, BlinkitStatus.OK, "No total budget was given, so these are the best-value picks per item (read from the screen):\n" + lines.joinToString("\n") + "\n$UNIT_NOTE", verified = true)
        }
        val plan = BlinkitRanker.plan(planItems, budget, reserve)
        session.plan = plan
        val sb = StringBuilder()
        if (plan.choices.isEmpty()) {
            return BlinkitResult(false, BlinkitStatus.NOT_FOUND, "None of the items had a readable in-stock match, so no plan could be made: ${plan.missing.joinToString(", ")}.")
        }
        sb.append(if (plan.fits) "Best-value combination within ${BlinkitMoney.format(budget)} (item prices only):\n"
        else "Nothing fits ${BlinkitMoney.format(budget)}. The cheapest combination is ${BlinkitMoney.format(plan.total)}, which is ${BlinkitMoney.format(plan.shortfall)} over:\n")
        plan.choices.forEach { c ->
            sb.append("• ").append(c.item.request.query).append(": ").append(c.product.name.take(50)).append(c.product.packText?.let { " ($it)" } ?: "")
                .append(" × ").append(c.item.request.quantity).append(" = ").append(BlinkitMoney.format(c.cost)).append('\n')
        }
        sb.append("Items total ").append(BlinkitMoney.format(plan.total))
        if (plan.fits) sb.append(", leaving ").append(BlinkitMoney.format(budget - plan.total - reserve)).append(" of the budget")
        sb.append(".\n")
        if (plan.missing.isNotEmpty()) sb.append("No in-stock match found for: ").append(plan.missing.joinToString(", ")).append(".\n")
        if (!plan.fits && plan.suggestedDrop != null) sb.append("Dropping ").append(plan.suggestedDrop).append(" would bring it within budget.\n")
        sb.append("Delivery, handling and small-cart fees are not included: Blinkit shows them only in the cart. ")
        sb.append("Say 'add the plan to the cart' and I will add these and read the real bill.\n").append(UNIT_NOTE)
        return BlinkitResult(true, if (plan.fits) BlinkitStatus.OK else BlinkitStatus.OVER_BUDGET, sb.toString().trim(), verified = true,
            extra = mapOf("fits" to plan.fits, "total" to plan.total, "budget" to budget))
    }

    // ---- Cart reading -------------------------------------------------------------------------------

    private fun mergeBill(a: BillDetails, b: BillDetails): BillDetails = BillDetails(
        itemTotal = a.itemTotal ?: b.itemTotal,
        fees = (a.fees + b.fees).distinctBy { it.first },
        discounts = (a.discounts + b.discounts).distinctBy { it.first },
        total = a.total ?: b.total,
        minOrderGap = a.minOrderGap ?: b.minOrderGap,
        freeDeliveryGap = a.freeDeliveryGap ?: b.freeDeliveryGap
    )

    /** Opens the cart (or reads it when already open) and scrolls down to read every row and the whole bill. */
    private suspend fun readCart(deadline: Deadline): Pair<BlinkitResult?, CartSnapshot?> {
        var nodes = driver.snapshot() ?: return BlinkitResult(false, BlinkitStatus.SCREEN_UNREADABLE, "The screen could not be read.") to null
        if (BlinkitScreenReader.classify(nodes) != BlinkitScreen.CART) {
            var button = BlinkitScreenReader.viewCartButton(nodes)
            if (button == null) {
                if (!open(null)) return launchFailure() to null
                val home = waitFor(deadline, 14_000, null, setOf(BlinkitScreen.HOME, BlinkitScreen.SEARCH_RESULTS, BlinkitScreen.PRODUCT, BlinkitScreen.CART), reopenApp = true)
                if (home.screen == BlinkitScreen.CART) nodes = home.nodes
                else {
                    if (home.screen !in setOf(BlinkitScreen.HOME, BlinkitScreen.SEARCH_RESULTS, BlinkitScreen.PRODUCT)) return stuck(home, "opening the cart") to null
                    button = BlinkitScreenReader.viewCartButton(home.nodes)
                    nodes = home.nodes
                }
            }
            if (BlinkitScreenReader.classify(nodes) != BlinkitScreen.CART) {
                if (button == null) {
                    // An empty cart has no "View cart" bar; the cart icon still opens it.
                    return BlinkitResult(false, BlinkitStatus.NOT_FOUND, "I could not find the cart button on screen. If your cart is empty, Blinkit hides it; otherwise open the cart once and tell me to continue.") to null
                }
                driver.progress("Opening the cart")
                if (!driver.click(button)) return BlinkitResult(false, BlinkitStatus.ERROR, "The tap on the cart button did not go through.") to null
                val w = waitFor(deadline, 15_000, null, setOf(BlinkitScreen.CART, BlinkitScreen.ADDRESS_PICKER))
                if (w.screen == BlinkitScreen.ADDRESS_PICKER) return BlinkitResult(false, BlinkitStatus.NEEDS_ADDRESS,
                    "Blinkit is asking you to choose a delivery address before the cart opens. Jarvis does not pick one silently: choose it yourself or tell me which saved address to use.") to null
                if (w.screen != BlinkitScreen.CART) return stuck(w, "opening the cart") to null
                nodes = w.nodes
            }
        }
        var snap = BlinkitScreenReader.extractCart(nodes)
        var lines = snap.lines
        var bill = snap.bill
        var address = snap.address
        var eta = snap.eta
        var pages = 0
        // Long carts: the bill sits at the bottom, so scroll until it stops changing (bounded).
        while (pages < 3 && !deadline.expired() && !driver.cancelled() && (bill.total == null || lines.isNotEmpty())) {
            if (bill.total != null && bill.itemTotal != null && pages >= 1) break
            if (!driver.scroll(true)) break
            driver.pause(600)
            val next = driver.snapshot() ?: break
            if (BlinkitScreenReader.classify(next) != BlinkitScreen.CART) break
            val more = BlinkitScreenReader.extractCart(next)
            val before = lines.size
            lines = (lines + more.lines).distinctBy { it.key }
            bill = mergeBill(bill, more.bill)
            address = address ?: more.address
            eta = eta ?: more.eta
            pages++
            if (lines.size == before && more.bill.total == null) break
        }
        repeat(pages) { driver.scroll(false) }
        snap = CartSnapshot(lines, bill, snap.empty && lines.isEmpty(), address, eta)
        session.lastCart = snap
        return null to snap
    }

    private fun describeCart(s: CartSnapshot): String {
        if (s.empty || s.lines.isEmpty()) return "The Blinkit cart is empty."
        val sb = StringBuilder("Cart has ${s.lines.size} item(s):\n")
        s.lines.forEachIndexed { i, c ->
            sb.append(i + 1).append(". ").append(c.name.take(60)).append(c.packText?.let { " ($it)" } ?: "").append(" × ").append(c.quantity)
                .append(" — ").append(BlinkitMoney.format(c.price)).append(if (c.unavailable) " — UNAVAILABLE" else "").append('\n')
        }
        appendBill(sb, s.bill)
        s.address?.let { sb.append("Delivering to: ").append(it).append('\n') }
        s.eta?.let { sb.append(it).append('\n') }
        return sb.toString().trim()
    }

    private fun appendBill(sb: StringBuilder, bill: BillDetails) {
        bill.itemTotal?.let { sb.append("Item total: ").append(BlinkitMoney.format(it)).append('\n') }
        bill.fees.forEach { sb.append(it.first).append(": ").append(if (it.second == 0.0) "free" else BlinkitMoney.format(it.second)).append('\n') }
        bill.discounts.forEach { sb.append(it.first).append(": -").append(BlinkitMoney.format(Math.abs(it.second))).append('\n') }
        sb.append("To pay: ").append(if (bill.total != null) BlinkitMoney.format(bill.total) else "not readable on screen").append('\n')
        bill.minOrderGap?.let { if (it > 0) sb.append("Minimum order not reached: add ").append(BlinkitMoney.format(it)).append(" more.\n") }
        bill.freeDeliveryGap?.let { if (it > 0) sb.append("Add ").append(BlinkitMoney.format(it)).append(" more for free delivery.\n") }
    }

    // ---- Cart actions -------------------------------------------------------------------------------

    suspend fun cartAction(action: String, index: Int?, item: String?, quantity: Int?, variant: String?, code: String?): BlinkitResult {
        val deadline = Deadline(driver, CART_BUDGET_MS)
        return when (action) {
            "view", "show", "" -> {
                driver.progress("Opening the cart")
                val (err, snap) = readCart(deadline)
                if (err != null) err else BlinkitResult(true, BlinkitStatus.OK, describeCart(snap!!), verified = true,
                    extra = mapOf("items" to snap.lines.size, "to_pay" to snap.bill.total))
            }
            "add" -> addByIndexOrItem(index, item, (quantity ?: session.lastRequest?.quantity ?: 1).coerceIn(1, MAX_QUANTITY), variant ?: session.lastRequest?.packHint, deadline)
            "add_plan" -> addPlan()
            "remove" -> if (item.isNullOrBlank()) BlinkitResult(false, BlinkitStatus.NOT_FOUND, "Which cart item should I remove?") else removeItem(item, deadline)
            "set_quantity" -> if (item.isNullOrBlank() || quantity == null) BlinkitResult(false, BlinkitStatus.NOT_FOUND, "I need the item and the new quantity.") else setQuantity(item, quantity, deadline)
            "coupons" -> coupons(null, deadline)
            "apply_coupon" -> if (code.isNullOrBlank()) BlinkitResult(false, BlinkitStatus.NOT_FOUND, "Which coupon code or offer should I apply?") else coupons(code, deadline)
            else -> BlinkitResult(false, BlinkitStatus.NOT_FOUND, "Unknown cart action '$action'. Use view, add, add_plan, remove, set_quantity, coupons or apply_coupon.")
        }
    }

    private suspend fun addByIndexOrItem(index: Int?, item: String?, quantity: Int, variant: String?, deadline: Deadline): BlinkitResult {
        val product: BlinkitProduct = when {
            index != null -> session.lastResults.getOrNull(index - 1)
                ?: return BlinkitResult(false, BlinkitStatus.NOT_FOUND, "There is no result number $index in the last search.")
            !item.isNullOrBlank() -> {
                val match = session.lastResults.firstOrNull { TitleMatch.containment(it.name, item) >= 0.6 }
                if (match != null) match else {
                    val search = search(BlinkitQueryParser.parse(item))
                    if (!search.success) return search
                    session.lastResults.firstOrNull { it.available }
                        ?: return BlinkitResult(false, BlinkitStatus.UNAVAILABLE, "'$item' is out of stock at your delivery address.")
                }
            }
            else -> return BlinkitResult(false, BlinkitStatus.NOT_FOUND, "Which product should I add? Give its number from the last search.")
        }
        return addProduct(product, quantity, variant, deadline)
    }

    /** Finds the product's card on a results screen (re-searching and scrolling if needed). */
    private suspend fun findCardOnScreen(product: BlinkitProduct, deadline: Deadline): Pair<BlinkitResult?, Pair<RawCard, List<AmazonNode>>?> {
        var nodes = driver.snapshot() ?: return BlinkitResult(false, BlinkitStatus.SCREEN_UNREADABLE, "The screen could not be read.") to null
        var card = if (BlinkitScreenReader.classify(nodes) == BlinkitScreen.SEARCH_RESULTS) BlinkitScreenReader.locateCard(nodes, product) else null
        if (card == null) {
            val query = session.lastRequest?.query?.takeIf { it.isNotBlank() && it != session.lastRequest?.vague?.label } ?: product.name
            val url = BlinkitLinks.searchUrl(query)
            if (!open(url)) return launchFailure() to null
            var w = waitFor(deadline, 18_000, url, setOf(BlinkitScreen.SEARCH_RESULTS, BlinkitScreen.PRODUCT), homeLimit = 4)
            if (w.screen !in setOf(BlinkitScreen.SEARCH_RESULTS, BlinkitScreen.PRODUCT)) w = typedSearch(query, deadline, w)
            if (w.screen !in setOf(BlinkitScreen.SEARCH_RESULTS, BlinkitScreen.PRODUCT)) return stuck(w, "reopening the results") to null
            nodes = w.nodes
            card = BlinkitScreenReader.locateCard(nodes, product)
            var tries = 0
            while (card == null && tries < 8 && !deadline.expired() && !driver.cancelled()) {
                if (!driver.scroll(true)) break
                driver.pause(550)
                nodes = driver.snapshot() ?: break
                card = BlinkitScreenReader.locateCard(nodes, product)
                tries++
            }
        }
        val found = card ?: return BlinkitResult(false, BlinkitStatus.NOT_FOUND,
            "Could not find '${product.name.take(50)}' on Blinkit's results again, so nothing was added.") to null
        return null to (found to nodes)
    }

    private fun lineFor(cart: CartSnapshot, product: BlinkitProduct): CartLine? = cart.lines.firstOrNull { l ->
        TitleMatch.containment(l.name, product.name) >= 0.6 &&
            (product.packText == null || l.packText == null || PackParser.parse(l.packText)?.baseAmount == product.pack?.baseAmount || TitleMatch.normalize(l.packText) == TitleMatch.normalize(product.packText))
    }

    /**
     * Makes sure the cart holds [quantity] of [product]: taps ADD (or picks the pack in the options sheet), raises the
     * stepper, then proves it by opening the cart and reading the row. Adding something twice in a row never doubles it.
     */
    private suspend fun addProduct(product: BlinkitProduct, quantity: Int, variant: String?, deadline: Deadline): BlinkitResult {
        if (!product.available) {
            val alt = BlinkitRanker.alternatives(product, session.lastResults)
            return BlinkitResult(false, BlinkitStatus.UNAVAILABLE, "${product.name.take(60)} is out of stock at your delivery address." +
                (if (alt.isNotEmpty()) " Alternatives in stock: " + alt.joinToString("; ") { BlinkitRanker.describe(it) } else ""))
        }
        val key = product.key
        driver.progress("Adding ${product.name.take(40)} to the cart")
        val (err, found) = findCardOnScreen(product, deadline)
        if (err != null) return err
        var (card, nodes) = found!!
        var target = product

        if (card.closer == Closer.NOTIFY) return BlinkitResult(false, BlinkitStatus.UNAVAILABLE, "${product.name.take(60)} is out of stock right now (the card shows Notify me).")
        if (card.closer == Closer.OPTIONS) {
            if (!driver.click(card.closerNode!!)) return BlinkitResult(false, BlinkitStatus.ERROR, "The tap on the pack options did not go through.")
            driver.pause(900)
            val sheet = driver.snapshot() ?: return BlinkitResult(false, BlinkitStatus.SCREEN_UNREADABLE, "The pack options could not be read.")
            val rows = BlinkitScreenReader.variantRows(sheet).filter { it.first.available }
            val wanted = variant?.let { PackParser.parse(it) } ?: product.pack
            val chosen = rows.filter { wanted != null && it.first.pack?.kind == wanted.kind && it.first.pack?.baseAmount == wanted.baseAmount }
            if (chosen.size != 1) {
                driver.back()
                return BlinkitResult(false, BlinkitStatus.AMBIGUOUS,
                    "This product comes in several packs: " + rows.joinToString("; ") { (p, _) -> (p.packText ?: "?") + " " + BlinkitMoney.format(p.price) } +
                        ". Tell me which pack to add (for example '500 g'). Nothing was added.")
            }
            card = chosen.first().second
            nodes = sheet
            target = chosen.first().first.copy(name = product.name)
        }
        var qtyOnCard = if (card.closer == Closer.STEPPER) card.qty else 0
        if (qtyOnCard >= quantity) {
            val (cartErr, cart) = readCart(deadline)
            if (cartErr != null) return cartErr
            val line = lineFor(cart!!, target)
            return if (line != null && line.quantity >= quantity) BlinkitResult(true, BlinkitStatus.OK, "Already in the cart: ${line.name.take(60)} × ${line.quantity} (verified in the cart).", verified = true, extra = mapOf("in_cart" to true, "quantity" to line.quantity))
            else BlinkitResult(false, BlinkitStatus.NOT_VERIFIED, "The card shows quantity $qtyOnCard but I could not confirm it in the cart.")
        }
        if (qtyOnCard == 0) {
            if (session.recentlyAdded(key, driver.now())) {
                return BlinkitResult(true, BlinkitStatus.NOT_VERIFIED, "That product was added a moment ago, so I did not tap ADD again. Say 'show cart' to check it.", extra = mapOf("in_cart" to true))
            }
            if (!driver.click(card.closerNode!!)) return BlinkitResult(false, BlinkitStatus.ERROR, "The tap on ADD did not go through.")
            session.noteAdded(key, driver.now())
            driver.pause(1_000)
            val after = driver.snapshot() ?: return BlinkitResult(false, BlinkitStatus.NOT_VERIFIED, "Pressed ADD but could not re-read the screen to confirm.")
            when (BlinkitScreenReader.classify(after)) {
                BlinkitScreen.SIGN_IN -> return BlinkitResult(false, BlinkitStatus.NEEDS_SIGN_IN, "Blinkit asks you to log in before adding items. Log in yourself, then tell me to continue.")
                BlinkitScreen.ADDRESS_PICKER -> return BlinkitResult(false, BlinkitStatus.NEEDS_ADDRESS, "Blinkit asks you to choose a delivery address before adding items. Choose it yourself or tell me which saved address to use.")
                BlinkitScreen.CHALLENGE -> return stuck(Waited(BlinkitScreen.CHALLENGE, after), "adding to the cart")
                else -> Unit
            }
            val reCard = BlinkitScreenReader.locateCard(after, target) ?: card
            qtyOnCard = if (reCard.closer == Closer.STEPPER) reCard.qty else 0
            card = reCard
            nodes = after
        }
        // Raise the quantity one tap at a time, re-reading the stepper after every tap.
        var taps = 0
        while (qtyOnCard in 1 until quantity && taps < MAX_QUANTITY && !deadline.expired() && !driver.cancelled()) {
            val live = driver.snapshot() ?: break
            val c = BlinkitScreenReader.locateCard(live, target)?.takeIf { it.closer == Closer.STEPPER } ?: break
            if (!driver.click(c.closerNode!!)) break
            driver.pause(700)
            val next = driver.snapshot()?.let { BlinkitScreenReader.locateCard(it, target) }?.takeIf { it.closer == Closer.STEPPER } ?: break
            if (next.qty == qtyOnCard) break
            qtyOnCard = next.qty
            taps++
        }
        val (cartErr, cart) = readCart(deadline)
        if (cartErr != null) {
            return BlinkitResult(false, BlinkitStatus.NOT_VERIFIED,
                "I pressed ADD and the card showed quantity $qtyOnCard, but the cart could not be opened to confirm: ${cartErr.message}")
        }
        val line = lineFor(cart!!, target)
            ?: return BlinkitResult(false, BlinkitStatus.NOT_VERIFIED, "I pressed ADD but the item is not visible in the cart, so I cannot say it was added.")
        val ok = line.quantity == quantity
        return BlinkitResult(ok, if (ok) BlinkitStatus.OK else BlinkitStatus.NOT_VERIFIED,
            (if (ok) "Verified in the cart: " else "In the cart, but the quantity is ${line.quantity}, not $quantity: ") + "${line.name.take(60)} × ${line.quantity}" +
                (line.price?.let { " at ${BlinkitMoney.format(it)}" } ?: "") + (cart.bill.total?.let { ". To pay so far ${BlinkitMoney.format(it)}" } ?: ""),
            verified = ok, extra = mapOf("in_cart" to true, "quantity" to line.quantity))
    }

    private suspend fun addPlan(): BlinkitResult {
        val plan = session.plan ?: return BlinkitResult(false, BlinkitStatus.NOT_FOUND, "There is no budget plan yet. Ask me to plan a shopping list first.")
        val deadline = Deadline(driver, PLAN_BUDGET_MS)
        val report = ArrayList<String>()
        var okCount = 0
        for (c in plan.choices) {
            if (driver.cancelled()) return BlinkitResult(false, BlinkitStatus.CANCELLED, "Stopped adding the plan, as you asked. Added so far: $okCount.")
            if (deadline.expired()) { report += "• stopped: time limit reached"; break }
            session.lastRequest = c.item.request
            val r = addProduct(c.product, c.item.request.quantity, c.item.request.packHint, Deadline(driver, minOf(60_000L, deadline.left())))
            if (r.status == BlinkitStatus.NEEDS_SIGN_IN || r.status == BlinkitStatus.NEEDS_ADDRESS || r.status == BlinkitStatus.NEEDS_USER) return r
            val good = r.success && r.verified && r.status == BlinkitStatus.OK
            if (good) okCount++
            report += "• ${c.item.request.query}: " + (if (good) "added and verified" else "NOT confirmed — ${r.message.take(120)}")
        }
        val all = okCount == plan.choices.size
        return BlinkitResult(all, if (all) BlinkitStatus.OK else BlinkitStatus.NOT_VERIFIED,
            "Added $okCount of ${plan.choices.size} planned items:\n" + report.joinToString("\n") + (if (all) "\nSay 'show cart' for the real bill." else ""),
            verified = all)
    }

    private fun matchesItem(name: String, query: String): Boolean {
        val q = TitleMatch.tokens(query)
        if (q.isEmpty()) return false
        val n = TitleMatch.tokens(name)
        return q.all { it in n || TitleMatch.normalize(name).contains(it) }
    }

    private suspend fun cartCards(deadline: Deadline): Pair<BlinkitResult?, Pair<CartSnapshot, List<AmazonNode>>?> {
        val (err, snap) = readCart(deadline)
        if (err != null) return err to null
        val nodes = driver.snapshot() ?: return BlinkitResult(false, BlinkitStatus.SCREEN_UNREADABLE, "The cart screen could not be read.") to null
        return null to (snap!! to nodes)
    }

    private fun stepperCard(nodes: List<AmazonNode>, query: String): List<RawCard> =
        BlinkitScreenReader.cards(nodes).filter { c ->
            c.closer == Closer.STEPPER && BlinkitScreenReader.toProduct(c)?.let { matchesItem(it.name, query) } == true
        }

    private suspend fun removeItem(query: String, deadline: Deadline): BlinkitResult {
        val (err, got) = cartCards(deadline)
        if (err != null) return err
        val (snap, nodes) = got!!
        val matches = snap.lines.filter { matchesItem(it.name, query) }
        if (matches.isEmpty()) return BlinkitResult(false, BlinkitStatus.NOT_FOUND, "No cart item matches '$query'. " + describeCart(snap))
        if (matches.size > 1) return BlinkitResult(false, BlinkitStatus.AMBIGUOUS, "More than one cart item matches '$query': " + matches.joinToString("; ") { it.name.take(40) } + ". Say which one to remove.")
        driver.progress("Removing from the cart")
        var taps = 0
        var live = nodes
        while (taps < MAX_QUANTITY + 1 && !deadline.expired() && !driver.cancelled()) {
            val c = stepperCard(live, query).firstOrNull() ?: break
            val minus = c.minusNode ?: return BlinkitResult(false, BlinkitStatus.NOT_FOUND, "The minus button for that cart item was not found.")
            if (!driver.click(minus)) return BlinkitResult(false, BlinkitStatus.ERROR, "The tap to remove did not go through.")
            driver.pause(900)
            live = driver.snapshot() ?: return BlinkitResult(false, BlinkitStatus.NOT_VERIFIED, "Tapped remove but could not re-read the cart to confirm.")
            taps++
        }
        val after = BlinkitScreenReader.extractCart(live)
        session.lastCart = after
        val stillThere = after.lines.any { matchesItem(it.name, query) }
        return if (!stillThere) BlinkitResult(true, BlinkitStatus.OK, "Removed from the cart (verified: it no longer appears).", verified = true)
        else BlinkitResult(false, BlinkitStatus.NOT_VERIFIED, "Pressed remove but the item still appears in the cart.")
    }

    private suspend fun setQuantity(query: String, target: Int, deadline: Deadline): BlinkitResult {
        if (target !in 1..MAX_QUANTITY) return BlinkitResult(false, BlinkitStatus.NOT_FOUND, "Quantity must be between 1 and $MAX_QUANTITY (use remove to take an item out).")
        val (err, got) = cartCards(deadline)
        if (err != null) return err
        var live = got!!.second
        val first = stepperCard(live, query)
        if (first.isEmpty()) return BlinkitResult(false, BlinkitStatus.NOT_FOUND, "That item is not in the cart.")
        if (first.size > 1) return BlinkitResult(false, BlinkitStatus.AMBIGUOUS, "More than one cart item matches '$query'. Say which one.")
        var qty = first.first().qty
        var taps = 0
        while (qty != target && taps < 25 && !deadline.expired() && !driver.cancelled()) {
            val c = stepperCard(live, query).firstOrNull() ?: break
            val button = (if (target > qty) c.closerNode else c.minusNode) ?: break
            if (!driver.click(button)) break
            driver.pause(900)
            live = driver.snapshot() ?: break
            val next = stepperCard(live, query).firstOrNull() ?: break
            if (next.qty == qty) break
            qty = next.qty
            taps++
        }
        val after = BlinkitScreenReader.extractCart(live)
        session.lastCart = after
        val row = after.lines.firstOrNull { matchesItem(it.name, query) }
        return if (row != null && row.quantity == target) BlinkitResult(true, BlinkitStatus.OK, "Quantity of ${row.name.take(60)} is now ${row.quantity} (verified in the cart).", verified = true)
        else BlinkitResult(false, BlinkitStatus.NOT_VERIFIED, "Could not confirm the quantity is $target (it shows ${row?.quantity ?: "unknown"}).")
    }

    // ---- Coupons ------------------------------------------------------------------------------------

    /** Lists the coupons Blinkit shows in the cart, or applies [code] and proves it by the bill changing. */
    private suspend fun coupons(code: String?, deadline: Deadline): BlinkitResult {
        val (err, snap) = readCart(deadline)
        if (err != null) return err
        val before = snap!!
        val nodes = driver.snapshot() ?: return BlinkitResult(false, BlinkitStatus.SCREEN_UNREADABLE, "The cart screen could not be read.")
        val entry = AmazonScreenReader.findByLabel(nodes) { it.contains("coupon") || it.startsWith("offers") || it.contains("apply promo") || it.contains("view all coupons") }
            ?: return BlinkitResult(false, BlinkitStatus.NOT_FOUND, "Blinkit shows no coupon or offer entry in the cart right now, so there is nothing to apply.")
        if (!driver.click(entry)) return BlinkitResult(false, BlinkitStatus.ERROR, "The tap on the coupon entry did not go through.")
        driver.pause(1_000)
        val sheet = driver.snapshot() ?: return BlinkitResult(false, BlinkitStatus.SCREEN_UNREADABLE, "The coupon list could not be read.")
        val texts = sheet.map { it.label.trim() }.filter { it.length in 6..110 && Regex("""(?i)save|off|flat|₹|%|free|code""").containsMatchIn(it) }.distinct().take(8)
        if (code == null) {
            driver.back()
            return if (texts.isEmpty()) BlinkitResult(true, BlinkitStatus.OK, "Blinkit lists no coupons I can read for this cart.", verified = true)
            else BlinkitResult(true, BlinkitStatus.OK, "Offers visible in the cart (as shown by Blinkit, eligibility not checked by Jarvis):\n" + texts.joinToString("\n") { "• $it" }, verified = true)
        }
        val wanted = TitleMatch.normalize(code)
        val target = sheet.firstOrNull { it.label.isNotBlank() && TitleMatch.normalize(it.label).contains(wanted) }
            ?: return BlinkitResult(false, BlinkitStatus.NOT_FOUND, "The coupon '$code' is not in Blinkit's list for this cart. Nothing was applied.").also { driver.back() }
        val applyBtn = AmazonScreenReader.findByLabel(sheet) { it == "apply" || it == "apply coupon" || it == "use" }
        if (!driver.click(applyBtn ?: target)) return BlinkitResult(false, BlinkitStatus.ERROR, "The tap to apply the coupon did not go through.")
        driver.pause(1_500)
        val (err2, after) = readCart(deadline)
        if (err2 != null) return BlinkitResult(false, BlinkitStatus.NOT_VERIFIED, "Tapped the coupon but could not re-read the cart: ${err2.message}")
        val changed = (after!!.bill.total != null && before.bill.total != null && after.bill.total < before.bill.total) || after.bill.discounts.size > before.bill.discounts.size
        return if (changed) BlinkitResult(true, BlinkitStatus.OK, "Coupon applied (verified: the bill went from ${BlinkitMoney.format(before.bill.total)} to ${BlinkitMoney.format(after.bill.total)}).", verified = true)
        else BlinkitResult(false, BlinkitStatus.NOT_VERIFIED, "I tapped the coupon but the bill did not change, so I cannot say it was applied (it may not apply to this cart).")
    }

    // ---- Location and address -----------------------------------------------------------------------

    suspend fun locationStatus(): BlinkitResult {
        val s = driver.locationState()
        val text = when (s.access) {
            LocationAccess.PRECISE -> "Phone location is on and precise (about ${s.accuracyMeters} m)." + (s.areaLabel?.let { " You are around $it." } ?: "")
            LocationAccess.APPROXIMATE -> "Phone location is only approximate (about ${s.accuracyMeters} m). Blinkit may suggest a slightly wrong address; check the delivery address before ordering."
            LocationAccess.PERMISSION_DENIED -> "Jarvis does not have location permission. Allow location for Jarvis in the app settings, and make sure Blinkit has it too."
            LocationAccess.SERVICES_OFF -> "The phone's location (GPS) is switched off. Turn it on in quick settings; Jarvis does not switch it on by itself."
            LocationAccess.UNAVAILABLE -> "The phone has no usable location fix right now. Move to an open area, wait a few seconds, or pick a saved address in Blinkit."
        }
        return BlinkitResult(s.usable, if (s.usable) BlinkitStatus.OK else BlinkitStatus.NEEDS_LOCATION, text, verified = true,
            extra = mapOf("access" to s.access.name))
    }

    private suspend fun homeNodes(deadline: Deadline): Pair<BlinkitResult?, Waited?> {
        if (!open(null)) return launchFailure() to null
        val w = waitFor(deadline, 15_000, null, setOf(BlinkitScreen.HOME, BlinkitScreen.SEARCH_RESULTS, BlinkitScreen.PRODUCT, BlinkitScreen.CART), reopenApp = true)
        if (w.screen !in setOf(BlinkitScreen.HOME, BlinkitScreen.SEARCH_RESULTS, BlinkitScreen.PRODUCT, BlinkitScreen.CART)) return stuck(w, "reading the delivery address") to null
        return null to w
    }

    suspend fun address(action: String, label: String?): BlinkitResult {
        val deadline = Deadline(driver, CART_BUDGET_MS)
        return when (action) {
            "status", "" -> {
                val (err, w) = homeNodes(deadline)
                if (err != null) return err
                val addr = BlinkitScreenReader.address(w!!.nodes)
                val eta = BlinkitScreenReader.eta(w.nodes)
                if (addr == null) BlinkitResult(false, BlinkitStatus.NOT_VERIFIED, "I could not read the delivery address from Blinkit's screen. Please check it on the phone.")
                else BlinkitResult(true, BlinkitStatus.OK, "Blinkit is delivering to: $addr." + (eta?.let { " $it." } ?: "") + " Products and delivery time apply to this address.", verified = true, extra = mapOf("address" to addr))
            }
            "location" -> locationStatus()
            "list", "select" -> pickAddress(action == "select", label, deadline)
            else -> BlinkitResult(false, BlinkitStatus.NOT_FOUND, "Unknown address action '$action'. Use status, location, list or select.")
        }
    }

    private suspend fun openPicker(deadline: Deadline): Pair<BlinkitResult?, Waited?> {
        val (err, home) = homeNodes(deadline)
        if (err != null) return err to null
        val addr = BlinkitScreenReader.address(home!!.nodes)
        val header = (addr?.let { a -> home.nodes.firstOrNull { it.clickable && it.label.isNotBlank() && (a.contains(it.label.trim()) || it.label.contains(a.take(20))) } })
            ?: AmazonScreenReader.findByLabel(home.nodes) { it.startsWith("delivery in") || it.startsWith("blinkit in") || it.startsWith("home") || it.startsWith("work") }
            ?: return BlinkitResult(false, BlinkitStatus.NOT_FOUND, "I could not find the delivery address bar on Blinkit's screen.") to null
        if (!driver.click(header)) return BlinkitResult(false, BlinkitStatus.ERROR, "The tap on the delivery address did not go through.") to null
        val w = waitFor(deadline, 12_000, null, setOf(BlinkitScreen.ADDRESS_PICKER))
        if (w.screen != BlinkitScreen.ADDRESS_PICKER) return stuck(w, "opening the address list") to null
        return null to w
    }

    private suspend fun pickAddress(select: Boolean, label: String?, deadline: Deadline): BlinkitResult {
        val (err, picker) = openPicker(deadline)
        if (err != null) return err
        val entries = BlinkitScreenReader.savedAddresses(picker!!.nodes)
        if (entries.isEmpty()) {
            driver.back()
            return BlinkitResult(false, BlinkitStatus.NOT_FOUND, "Blinkit shows no saved addresses I can read. Add one in Blinkit, or tell me the place and I will check serviceability by searching.")
        }
        fun shown() = entries.mapIndexed { i, e -> "${i + 1}. " + (if (e.label.isNotBlank()) e.label + ": " else "") + e.text }
        if (!select) {
            driver.back()
            driver.pause(600)
            return BlinkitResult(true, BlinkitStatus.OK, "Saved addresses in Blinkit (the delivery address was NOT changed):\n" + shown().joinToString("\n"), verified = true, extra = mapOf("count" to entries.size))
        }
        if (label.isNullOrBlank()) { driver.back(); return BlinkitResult(false, BlinkitStatus.AMBIGUOUS, "Which address should I select? " + shown().joinToString("; ")) }
        val want = TitleMatch.normalize(label)
        val matches = entries.filter { e -> TitleMatch.normalize(e.label) == want || (want.length >= 3 && TitleMatch.normalize(e.text).contains(want)) }
        if (matches.size != 1) {
            driver.back()
            return BlinkitResult(false, BlinkitStatus.AMBIGUOUS, (if (matches.isEmpty()) "No saved address matches '$label'. " else "More than one saved address matches '$label'. ") + shown().joinToString("; ") + ". Nothing was changed.")
        }
        val entry = matches.first()
        val node = entry.node ?: return BlinkitResult(false, BlinkitStatus.NOT_FOUND, "I could not find a tappable row for that address. Nothing was changed.")
        if (!driver.click(node)) return BlinkitResult(false, BlinkitStatus.ERROR, "The tap on the saved address did not go through. Nothing was changed.")
        driver.pause(1_500)
        val w = waitFor(deadline, 14_000, null, setOf(BlinkitScreen.HOME, BlinkitScreen.SEARCH_RESULTS, BlinkitScreen.PRODUCT, BlinkitScreen.CART, BlinkitScreen.NOT_SERVICEABLE))
        if (w.screen == BlinkitScreen.NOT_SERVICEABLE) return BlinkitResult(false, BlinkitStatus.NOT_SERVICEABLE, "Blinkit does not deliver to ${entry.label.ifBlank { "that address" }}. The address is now selected in the app; tell me if you want to switch back.")
        if (w.screen == BlinkitScreen.ADDRESS_PICKER) return BlinkitResult(false, BlinkitStatus.NEEDS_USER, "Blinkit wants you to confirm the pin or details for that address. Please confirm it yourself, then ask me to check the address.")
        if (w.screen !in setOf(BlinkitScreen.HOME, BlinkitScreen.SEARCH_RESULTS, BlinkitScreen.PRODUCT, BlinkitScreen.CART)) return stuck(w, "selecting the address")
        val now = BlinkitScreenReader.address(w.nodes)
        val tokens = TitleMatch.tokens(entry.text)
        val matches2 = now != null && (TitleMatch.normalize(now).contains(TitleMatch.normalize(entry.label)) && entry.label.isNotBlank() || TitleMatch.containment(now, entry.text) >= 0.5 || (tokens.isNotEmpty() && tokens.count { it in TitleMatch.tokens(now) } >= minOf(2, tokens.size)))
        return if (matches2) BlinkitResult(true, BlinkitStatus.OK, "Delivery address is now: $now (verified on Blinkit's screen). Product availability and delivery time apply to this address.", verified = true, extra = mapOf("address" to now))
        else BlinkitResult(false, BlinkitStatus.NOT_VERIFIED, "I tapped the address, but Blinkit's header does not clearly show it (${now ?: "address not readable"}). Please check it on the phone before ordering.")
    }

    // ---- Checkout -----------------------------------------------------------------------------------

    private val CONTINUE_LABELS = setOf("proceed to pay", "confirm address", "deliver here", "confirm & proceed", "confirm and proceed", "proceed", "continue")

    /**
     * Reads the cart and bill, checks the minimum order and unavailable items, goes to the payment screen using the
     * address Blinkit already selected, and STOPS there. It never presses pay / place order, never changes the address,
     * and never picks or changes a payment method.
     */
    suspend fun prepareCheckout(utterance: String): BlinkitResult {
        val deadline = Deadline(driver, CHECKOUT_BUDGET_MS)
        gate.clear()
        driver.progress("Opening the cart")
        val (err, cart) = readCart(deadline)
        if (err != null) return err
        if (cart!!.empty || cart.lines.isEmpty()) return BlinkitResult(false, BlinkitStatus.NOT_FOUND, "The cart is empty, so there is nothing to check out.")
        val unavailable = cart.lines.filter { it.unavailable }
        if (unavailable.isNotEmpty()) {
            return BlinkitResult(false, BlinkitStatus.UNAVAILABLE,
                "Blinkit marks these cart items as unavailable: ${unavailable.joinToString("; ") { it.name.take(40) }}. I did not continue. Remove them, or ask me for in-stock alternatives.",
                extra = mapOf("unavailable" to unavailable.map { it.name }))
        }
        cart.bill.minOrderGap?.takeIf { it > 0 }?.let { gap ->
            return BlinkitResult(false, BlinkitStatus.MIN_ORDER, "The cart is below Blinkit's minimum order: add ${BlinkitMoney.format(gap)} more to check out. Nothing was changed.", extra = mapOf("gap" to gap))
        }
        var nodes = driver.snapshot() ?: return BlinkitResult(false, BlinkitStatus.SCREEN_UNREADABLE, "The cart screen could not be read.")
        BlinkitScreenReader.loginToProceedButton(nodes)?.let {
            return BlinkitResult(false, BlinkitStatus.NEEDS_SIGN_IN, "Blinkit shows 'Login to proceed'. Please log in yourself (Jarvis never enters your phone number, OTP or password), then tell me to continue.")
        }
        var proceed: AmazonNode? = null
        for (i in 0..3) {
            proceed = BlinkitScreenReader.proceedButton(nodes)
            if (proceed != null || driver.cancelled()) break
            if (!driver.scroll(true)) break
            driver.pause(450)
            nodes = driver.snapshot() ?: break
        }
        if (proceed == null) return BlinkitResult(false, BlinkitStatus.NOT_FOUND, "The Proceed button was not found in the cart.")
        driver.progress("Going to payment")
        if (!driver.click(proceed)) return BlinkitResult(false, BlinkitStatus.ERROR, "The Proceed tap did not go through.")

        var steps = 0
        var retaps = 0
        while (steps < 7 && !deadline.expired()) {
            steps++
            if (driver.cancelled()) return BlinkitResult(false, BlinkitStatus.CANCELLED, "Stopped during checkout. Nothing was ordered.")
            val w = waitFor(deadline, 22_000, null, setOf(BlinkitScreen.PAYMENT, BlinkitScreen.ADDRESS_PICKER, BlinkitScreen.CART))
            when (w.screen) {
                BlinkitScreen.PAYMENT -> return paymentResult(w.nodes, cart, utterance)
                BlinkitScreen.ADDRESS_PICKER -> {
                    // Only continue with the address Blinkit already selected; never pick or change one.
                    val buttons = w.nodes.filter { n -> n.clickable && listOf(n.text, n.description).filter { it.isNotBlank() }.map { AmazonScreenReader.cleanLabel(it) }.any { it in CONTINUE_LABELS } }
                    if (buttons.size != 1) return BlinkitResult(false, BlinkitStatus.NEEDS_ADDRESS,
                        "Blinkit is asking you to choose or confirm a delivery address. Please pick it yourself (or tell me which saved address to select), then tell me to continue. Nothing was ordered.")
                    driver.progress("Continuing with the address Blinkit already selected")
                    driver.click(buttons.first())
                    driver.pause(1_000)
                }
                BlinkitScreen.CART -> {
                    // The tap may not have registered; try once or twice more, then report.
                    if (retaps >= 2) return BlinkitResult(false, BlinkitStatus.TIMEOUT, "Blinkit stayed on the cart after Proceed. Nothing was ordered.")
                    retaps++
                    BlinkitScreenReader.proceedButton(w.nodes)?.let { driver.click(it) }
                    driver.pause(1_200)
                }
                else -> return stuck(w, "going to payment")
            }
        }
        return BlinkitResult(false, BlinkitStatus.TIMEOUT, "Checkout did not reach the payment screen in time. Nothing was ordered.")
    }

    /**
     * Selects a payment method only from a visible clickable option on Blinkit's payment screen.
     * The updated bill/payment summary is re-read and the order confirmation gate is refreshed.
     */
    suspend fun selectPaymentMethod(method: String, utterance: String): BlinkitResult {
        val requested = method.trim()
        if (requested.isBlank()) return BlinkitResult(false, BlinkitStatus.NOT_FOUND, "Tell me which visible payment method to select.")
        val nodes = driver.snapshot() ?: return BlinkitResult(false, BlinkitStatus.SCREEN_UNREADABLE, "The payment screen could not be read.")
        if (BlinkitScreenReader.classify(nodes) != BlinkitScreen.PAYMENT) {
            return BlinkitResult(false, BlinkitStatus.WRONG_SCREEN, "Blinkit's payment screen is not open. I did not change the payment method.")
        }
        val pendingOrder = gate.pending
            ?: return BlinkitResult(false, BlinkitStatus.NEEDS_USER, "Prepare checkout first so I can verify the cart and bill before changing payment.")
        val carried = pendingOrder.summary.items
        if (carried.isEmpty()) return BlinkitResult(false, BlinkitStatus.NOT_VERIFIED, "The prepared checkout has no verified item list. Re-open checkout before changing payment.")
        val before = BlinkitScreenReader.extractPayment(nodes, carried)
        val options = nodes.filter { it.clickable && paymentOptionMatches(it.haystack, requested) }
            .distinctBy { TitleMatch.normalize(it.label) }
        if (options.isEmpty()) {
            val visible = nodes.filter { it.clickable && Regex("""upi|google pay|gpay|phonepe|paytm|cash on delivery|pay on delivery|credit card|debit card|net ?banking|wallet|emi""").containsMatchIn(it.haystack) }
                .map { it.label }.filter { it.isNotBlank() }.distinct().take(8)
            return BlinkitResult(false, BlinkitStatus.NOT_FOUND,
                "I could not find a clickable payment option matching '" + requested + "'. Visible options: " + visible.ifEmpty { listOf("none readable") }.joinToString(", ") + ". Nothing was changed.")
        }
        val exact = options.firstOrNull { TitleMatch.normalize(it.label) == TitleMatch.normalize(requested) }
        val target = exact ?: options.singleOrNull()
            ?: return BlinkitResult(false, BlinkitStatus.AMBIGUOUS,
                "More than one visible payment option matches '" + requested + "': " + options.joinToString { it.label } + ". Tell me the exact one.")
        if (paymentLabelMatches(before.paymentLabel.orEmpty(), requested)) {
            return BlinkitResult(true, BlinkitStatus.PREPARED,
                "Blinkit already shows " + before.paymentLabel + " as the selected payment method. Order NOT placed. Review the existing total and confirm in a new message if you want me to place it.",
                verified = true, extra = mapOf("payment" to before.paymentLabel.orEmpty(), "total" to before.total))
        }
        if (!driver.click(target)) return BlinkitResult(false, BlinkitStatus.ERROR, "The payment option tap did not go through. No order was placed.")
        driver.pause(700)
        val afterNodes = driver.snapshot() ?: return BlinkitResult(false, BlinkitStatus.NOT_VERIFIED, "I tapped the payment option but could not re-read the screen. Check the payment method yourself; no order was placed.")
        if (BlinkitScreenReader.classify(afterNodes) != BlinkitScreen.PAYMENT) {
            return BlinkitResult(false, BlinkitStatus.NOT_VERIFIED, "The screen changed after selecting payment, so I could not verify the method. No order was placed.")
        }
        val after = BlinkitScreenReader.extractPayment(afterNodes, carried)
        if (!paymentLabelMatches(after.paymentLabel.orEmpty(), requested)) {
            return BlinkitResult(false, BlinkitStatus.NOT_VERIFIED,
                "I tapped '" + target.label + "', but Blinkit does not show '" + requested + "' as selected (it shows '" + (after.paymentLabel ?: "no readable method") + "'). I did not place the order.")
        }
        if (!after.readable) return BlinkitResult(false, BlinkitStatus.NOT_VERIFIED, "Payment method is selected, but I cannot re-read the items and total reliably. Please review the payment screen; no order was placed.")
        gate.prepare(after, utterance)
        val sb = StringBuilder("PAYMENT METHOD UPDATED AND VERIFIED. Order NOT placed.\n")
        after.items.forEach { sb.append("- ").append(it.name.take(60)).append(it.packText?.let { p -> " (" + p + ")" } ?: "").append(" × ").append(it.quantity).append(" — ").append(BlinkitMoney.format(it.price)).append('\n') }
        appendBill(sb, after.bill)
        sb.append("Deliver to: ").append(after.addressLabel ?: "address not readable").append('\n')
        sb.append("Payment: ").append(after.paymentLabel).append('\n')
        after.deliveryEstimate?.let { sb.append(it).append('\n') }
        sb.append("Read this updated summary and wait for the user to clearly confirm placing the order in a NEW message.")
        return BlinkitResult(true, BlinkitStatus.PREPARED, sb.toString(), verified = true,
            extra = mapOf("payment" to after.paymentLabel, "total" to after.total))
    }

    private fun paymentOptionMatches(candidate: String, requested: String): Boolean {
        val c = TitleMatch.normalize(candidate)
        val r = TitleMatch.normalize(requested)
        if (r.isBlank()) return false
        val aliases = when (r) {
            "gpay", "google pay" -> setOf("google pay", "gpay")
            "cod", "cash" -> setOf("cash on delivery", "pay on delivery", "cod")
            "card" -> setOf("credit card", "debit card", "card")
            "phonepe" -> setOf("phonepe")
            "paytm" -> setOf("paytm")
            "upi" -> setOf("upi")
            else -> setOf(r)
        }
        return aliases.any { alias -> c == alias || c.contains(alias) }
    }

    private fun paymentLabelMatches(label: String, requested: String): Boolean =
        paymentOptionMatches(label, requested)

    private fun paymentResult(nodes: List<AmazonNode>, cart: CartSnapshot, utterance: String): BlinkitResult {
        val summary = BlinkitScreenReader.extractPayment(nodes, cart.lines)
        if (!summary.readable) {
            return BlinkitResult(false, BlinkitStatus.NOT_VERIFIED,
                "I reached the payment screen but could not read the total reliably, so I will not offer to place the order. Please review it yourself on screen.")
        }
        val addressMissing = summary.addressLabel == null && cart.address == null
        val withAddress = if (summary.addressLabel == null && cart.address != null) summary.copy(addressLabel = cart.address) else summary
        val order = gate.prepare(withAddress, utterance)
        val sb = StringBuilder("READY FOR YOUR CONFIRMATION. Order NOT placed.\n")
        withAddress.items.forEach { sb.append("- ").append(it.name.take(60)).append(it.packText?.let { p -> " ($p)" } ?: "").append(" × ").append(it.quantity).append(" — ").append(BlinkitMoney.format(it.price)).append('\n') }
        appendBill(sb, withAddress.bill)
        sb.append("Deliver to: ").append(withAddress.addressLabel ?: "address NOT readable on screen — ask the user to check it on the phone").append('\n')
        sb.append("Payment: ").append(withAddress.paymentLabel ?: "payment method not readable").append('\n')
        withAddress.deliveryEstimate?.let { sb.append(it).append('\n') }
        val cartTotal = cart.bill.total
        if (cartTotal != null && withAddress.bill.total != null && withAddress.bill.total != cartTotal) {
            sb.append("Note: the payable amount changed from ${BlinkitMoney.format(cartTotal)} in the cart to ${BlinkitMoney.format(withAddress.bill.total)} here (fees or offers).\n")
        }
        sb.append("Read this to the user and wait for them to say clearly that you should place the order. Do not call blinkit_place_order in this same turn.")
        return BlinkitResult(true, BlinkitStatus.PREPARED, sb.toString(), verified = true,
            extra = mapOf("total" to withAddress.total, "fingerprint" to order.fingerprint, "address_readable" to !addressMissing))
    }

    // ---- Place the order ----------------------------------------------------------------------------

    suspend fun placeOrder(utterance: String): BlinkitResult {
        val w0 = driver.snapshot() ?: return BlinkitResult(false, BlinkitStatus.SCREEN_UNREADABLE, "The screen could not be read, so the order was not placed.")
        if (BlinkitScreenReader.classify(w0) != BlinkitScreen.PAYMENT) {
            return BlinkitResult(false, BlinkitStatus.WRONG_SCREEN, "Blinkit's payment screen is not open, so nothing was ordered. Prepare the checkout again.")
        }
        val prepared = gate.pending
        val carried = prepared?.summary?.items.orEmpty()
        var current = BlinkitScreenReader.extractPayment(w0, carried)
        if (current.addressLabel == null && prepared?.summary?.addressLabel != null && session.lastCart?.address == prepared.summary.addressLabel) current = current.copy(addressLabel = prepared.summary.addressLabel)
        when (val decision = gate.authorize(current, utterance)) {
            is OrderDecision.Denied -> {
                if (decision.code == "CHANGED" && current.readable) {
                    gate.prepare(current, utterance)
                    return BlinkitResult(false, BlinkitStatus.REFUSED,
                        decision.reason + " New total: ${BlinkitMoney.format(current.total)}. Ask the user to confirm again.", extra = mapOf("code" to decision.code))
                }
                return BlinkitResult(false, BlinkitStatus.REFUSED, decision.reason, extra = mapOf("code" to decision.code))
            }
            is OrderDecision.Allowed -> {
                val order = decision.order
                val button = BlinkitScreenReader.finalPayButton(w0)
                    ?: return BlinkitResult(false, BlinkitStatus.NOT_FOUND, "The pay / place order button was not found, so nothing was ordered.")
                // Recorded BEFORE the tap so that, whatever happens next, this checkout can never be submitted twice.
                gate.markAttempted(order)
                driver.progress("Placing the order")
                var dispatched = driver.click(button)
                if (!dispatched) {
                    driver.pause(800)
                    val again = driver.snapshot()
                    val b2 = again?.let { if (BlinkitScreenReader.classify(it) == BlinkitScreen.PAYMENT) BlinkitScreenReader.finalPayButton(it) else null }
                    // A retry is only safe when the first tap was never dispatched at all.
                    if (b2 != null) dispatched = driver.click(b2)
                }
                if (!dispatched) return BlinkitResult(false, BlinkitStatus.ERROR, "The tap on pay / place order could not be sent. The order was NOT placed.")
                return verifyPlacement(order)
            }
        }
    }

    private suspend fun verifyPlacement(order: PendingBlinkitOrder): BlinkitResult {
        val deadline = Deadline(driver, ORDER_VERIFY_MS)
        var lastScreen = BlinkitScreen.UNKNOWN
        while (!deadline.expired()) {
            driver.pause(1_000)
            val fg = driver.foregroundPackage()
            if (fg != null && !BlinkitLinks.isBlinkitPackage(fg) && fg != "com.jarvis.assistant") {
                return BlinkitResult(false, BlinkitStatus.NEEDS_PAYMENT_AUTH,
                    "A payment app or page ($fg) opened to finish paying. Please complete the payment yourself (Jarvis never enters PINs or approves payments). The order is NOT confirmed yet; ask me to check the order status afterwards.")
            }
            val nodes = driver.snapshot() ?: continue
            val screen = BlinkitScreenReader.classify(nodes)
            lastScreen = screen
            when (screen) {
                BlinkitScreen.ORDER_PLACED, BlinkitScreen.ORDER_TRACKING -> {
                    gate.markPlaced(order)
                    session.clearAdded()
                    val st = BlinkitScreenReader.orderStatus(nodes)
                    return BlinkitResult(true, BlinkitStatus.PLACED,
                        "Order placed — Blinkit's order screen is showing (${st.stage.name.lowercase(Locale.ROOT).replace('_', ' ')}). Total ${BlinkitMoney.format(order.summary.total)}." +
                            (st.etaText?.let { " Arriving in $it." } ?: ""), verified = true,
                        extra = mapOf("order_id" to (st.orderId ?: "")))
                }
                BlinkitScreen.CHALLENGE -> return BlinkitResult(false, BlinkitStatus.NEEDS_PAYMENT_AUTH,
                    "Blinkit needs ${BlinkitScreenReader.challengeKind(nodes)} to finish paying. Please complete it yourself. The order is NOT confirmed yet; ask me to check the order status afterwards.")
                BlinkitScreen.SIGN_IN -> return BlinkitResult(false, BlinkitStatus.NEEDS_SIGN_IN, "Blinkit asked you to log in again. The order is NOT confirmed. Log in yourself, then ask me to check the order status.")
                else -> Unit
            }
        }
        return BlinkitResult(false, BlinkitStatus.NOT_VERIFIED,
            "I pressed pay / place order but did not see Blinkit's order confirmation (last screen: ${screenName(lastScreen)}). I cannot say it was placed. " +
                "Check your Orders in Blinkit before trying again; I will not press it a second time for this checkout.")
    }

    // ---- Order status, history, help, cancel --------------------------------------------------------

    private val TRACK_LABELS = Regex("""^(track order|view order|track|view details|order details|your order|order status)\b|arriving in|order arriving""")

    /** Brings Blinkit to a screen that shows the active order, or null result + nodes. */
    private suspend fun openTracking(deadline: Deadline): Pair<BlinkitResult?, List<AmazonNode>?> {
        var nodes = driver.snapshot()
        if (nodes != null && BlinkitScreenReader.classify(nodes) in setOf(BlinkitScreen.ORDER_TRACKING, BlinkitScreen.ORDER_PLACED)) return null to nodes
        if (!open(null)) return launchFailure() to null
        val w = waitFor(deadline, 14_000, null, setOf(BlinkitScreen.HOME, BlinkitScreen.ORDER_TRACKING, BlinkitScreen.ORDER_PLACED, BlinkitScreen.SEARCH_RESULTS, BlinkitScreen.CART, BlinkitScreen.PRODUCT), reopenApp = true)
        if (w.screen in setOf(BlinkitScreen.ORDER_TRACKING, BlinkitScreen.ORDER_PLACED)) return null to w.nodes
        if (w.screen !in setOf(BlinkitScreen.HOME, BlinkitScreen.SEARCH_RESULTS, BlinkitScreen.CART, BlinkitScreen.PRODUCT)) return stuck(w, "finding your order") to null
        nodes = w.nodes
        val banner = nodes.filter { it.clickable && TRACK_LABELS.containsMatchIn(AmazonScreenReader.cleanLabel(it.label)) }.minByOrNull { -it.top }
        if (banner != null && driver.click(banner)) {
            val t = waitFor(deadline, 12_000, null, setOf(BlinkitScreen.ORDER_TRACKING, BlinkitScreen.ORDER_PLACED))
            if (t.screen in setOf(BlinkitScreen.ORDER_TRACKING, BlinkitScreen.ORDER_PLACED)) return null to t.nodes
        }
        // Fall back to the orders list through the account area.
        val list = openOrdersList(deadline)
        if (list.first != null) return list.first to null
        val latest = BlinkitScreenReader.cards(list.second!!).let { _ ->
            list.second!!.firstOrNull { it.clickable && Regex("""(?i)^(track|view details|view order|order details)""").containsMatchIn(it.label.trim()) }
        }
        if (latest != null && driver.click(latest)) {
            val t = waitFor(deadline, 12_000, null, setOf(BlinkitScreen.ORDER_TRACKING, BlinkitScreen.ORDER_PLACED))
            if (t.screen in setOf(BlinkitScreen.ORDER_TRACKING, BlinkitScreen.ORDER_PLACED)) return null to t.nodes
        }
        return BlinkitResult(false, BlinkitStatus.NOT_FOUND, "I could not find an active order on Blinkit's screen. Open Blinkit's Orders page yourself if you have a live order, and tell me to check again.") to null
    }

    private suspend fun openOrdersList(deadline: Deadline): Pair<BlinkitResult?, List<AmazonNode>?> {
        var nodes = driver.snapshot() ?: return BlinkitResult(false, BlinkitStatus.SCREEN_UNREADABLE, "The screen could not be read.") to null
        if (BlinkitScreenReader.classify(nodes) == BlinkitScreen.ORDERS_LIST) return null to nodes
        if (BlinkitScreenReader.classify(nodes) != BlinkitScreen.HOME) {
            if (!open(null)) return launchFailure() to null
            val h = waitFor(deadline, 14_000, null, setOf(BlinkitScreen.HOME, BlinkitScreen.ORDERS_LIST), reopenApp = true)
            if (h.screen == BlinkitScreen.ORDERS_LIST) return null to h.nodes
            if (h.screen != BlinkitScreen.HOME) return stuck(h, "opening your orders") to null
            nodes = h.nodes
        }
        for (label in listOf(setOf("orders", "your orders", "my orders"), setOf("account", "profile", "my account"))) {
            val tab = AmazonScreenReader.findByLabel(nodes) { it in label }
            if (tab != null && driver.click(tab)) {
                val w = waitFor(deadline, 10_000, null, setOf(BlinkitScreen.ORDERS_LIST, BlinkitScreen.HOME, BlinkitScreen.UNKNOWN))
                if (w.screen == BlinkitScreen.ORDERS_LIST) return null to w.nodes
                nodes = driver.snapshot() ?: w.nodes
                val deeper = AmazonScreenReader.findByLabel(nodes) { it in setOf("your orders", "my orders", "orders", "order history") }
                if (deeper != null && driver.click(deeper)) {
                    val w2 = waitFor(deadline, 10_000, null, setOf(BlinkitScreen.ORDERS_LIST))
                    if (w2.screen == BlinkitScreen.ORDERS_LIST) return null to w2.nodes
                }
            }
        }
        return BlinkitResult(false, BlinkitStatus.NOT_FOUND, "I could not find the Orders page in Blinkit. Open it yourself and tell me to read it.") to null
    }

    private fun describeStatus(st: OrderStatus): String {
        val sb = StringBuilder("Blinkit's order screen shows: ${st.stage.name.lowercase(Locale.ROOT).replace('_', ' ')}.")
        st.etaText?.let { sb.append(" Estimated arrival: ").append(it).append('.') }
        st.partnerName?.let { sb.append(" Delivery partner: ").append(it).append('.') }
        st.delayNotice?.let { sb.append(" Notice: ").append(it).append('.') }
        st.itemNotices.forEach { sb.append("\nItem notice: ").append(it) }
        if (st.stage == OrderStage.UNKNOWN) sb.append(" I could not tell the stage from the text, so I am not claiming one.")
        return sb.toString()
    }

    suspend fun orderStatus(action: String, utterance: String): BlinkitResult {
        val deadline = Deadline(driver, STATUS_BUDGET_MS)
        when (action) {
            "history" -> {
                val (err, nodes) = openOrdersList(deadline)
                if (err != null) return err
                val rows = BlinkitScreenReader.orderSummaries(nodes!!)
                return if (rows.isEmpty()) BlinkitResult(false, BlinkitStatus.NOT_VERIFIED, "The Orders page is open but I could not read individual orders from it. Please read it on screen.")
                else BlinkitResult(true, BlinkitStatus.OK, "Recent Blinkit orders (as listed in the app):\n" + rows.mapIndexed { i, r -> "${i + 1}. $r" }.joinToString("\n"), verified = true, extra = mapOf("count" to rows.size))
            }
            "cancel" -> return cancelOrder(utterance, deadline)
            "help" -> {
                val (err, nodes) = openTracking(deadline)
                if (err != null) return err
                val help = BlinkitScreenReader.helpButton(nodes!!)
                    ?: return BlinkitResult(false, BlinkitStatus.NOT_FOUND, "I could not find Blinkit's help option on the order screen. Open the order and tap Help yourself (for missing items, damaged products or refunds).")
                if (!driver.click(help)) return BlinkitResult(false, BlinkitStatus.ERROR, "The tap on Help did not go through.")
                driver.pause(1_200)
                return BlinkitResult(true, BlinkitStatus.NEEDS_USER,
                    "I opened Blinkit's own help for this order. Describe the problem (missing item, damaged product, refund) there yourself. I cannot see the outcome, so I will not claim a refund or replacement.", verified = true)
            }
            else -> {
                val (err, nodes) = openTracking(deadline)
                if (err != null) return err
                val st = BlinkitScreenReader.orderStatus(nodes!!)
                if (st.stage == OrderStage.PLACED || st.stage != OrderStage.UNKNOWN) {
                    // A live order screen right after an unresolved payment step settles that checkout.
                    if (st.stage != OrderStage.CANCELLED) gate.pending?.let { gate.markPlaced(it) }
                }
                return BlinkitResult(st.stage != OrderStage.UNKNOWN, if (st.stage == OrderStage.UNKNOWN) BlinkitStatus.NOT_VERIFIED else BlinkitStatus.OK, describeStatus(st), verified = st.stage != OrderStage.UNKNOWN,
                    extra = mapOf("stage" to st.stage.name, "eta" to st.etaText, "delay" to st.delayNotice,
                        "cancel_offered" to st.cancelOffered, "order_id" to (st.orderId ?: "")))
            }
        }
    }

    /** Two steps: explain the order state and what cancelling means, then cancel only after a new explicit message. */
    private suspend fun cancelOrder(utterance: String, deadline: Deadline): BlinkitResult {
        val (err, nodes) = openTracking(deadline)
        if (err != null) return err
        val st = BlinkitScreenReader.orderStatus(nodes!!)
        if (st.stage == OrderStage.CANCELLED) return BlinkitResult(true, BlinkitStatus.OK, "Blinkit already shows this order as cancelled.", verified = true)
        if (st.stage == OrderStage.DELIVERED) return BlinkitResult(false, BlinkitStatus.REFUSED, "Blinkit shows this order as delivered, so it cannot be cancelled. For a problem with the items, ask me to open Blinkit's help instead.")
        val button = BlinkitScreenReader.cancelOrderButton(nodes)
        if (button == null) {
            return BlinkitResult(false, BlinkitStatus.NOT_FOUND,
                "Blinkit shows no cancel option for this order (${st.stage.name.lowercase(Locale.ROOT).replace('_', ' ')}); it is usually only offered before the order is packed or picked up. Nothing was cancelled. I can open Blinkit's help instead.")
        }
        if (gate.pendingCancel == null) {
            gate.prepareCancel(st.stage, utterance)
            return BlinkitResult(true, BlinkitStatus.PREPARED,
                "Blinkit shows the order as ${st.stage.name.lowercase(Locale.ROOT).replace('_', ' ')} and offers a Cancel option. Cancelling may carry a fee or refund delay that Blinkit shows only in its own dialog. " +
                    "Order NOT cancelled. Explain this to the user and wait for them to clearly say to cancel in a new message.", verified = true)
        }
        when (val d = gate.authorizeCancel(st.stage, utterance)) {
            is CancelDecision.Denied -> return BlinkitResult(false, BlinkitStatus.REFUSED, d.reason, extra = mapOf("code" to d.code))
            is CancelDecision.Allowed -> {
                driver.progress("Cancelling the order")
                if (!driver.click(button)) return BlinkitResult(false, BlinkitStatus.ERROR, "The tap on Cancel did not go through. Nothing was cancelled.")
                gate.cancelDone()
                driver.pause(1_000)
                val dialog = driver.snapshot()
                val confirm = dialog?.let { d2 -> BlinkitScreenReader.confirmCancelButton(d2) }
                if (confirm != null && !driver.click(confirm)) return BlinkitResult(false, BlinkitStatus.NOT_VERIFIED, "I tapped Cancel but could not confirm the dialog. Check the order in Blinkit.")
                val end = Deadline(driver, 20_000L)
                while (!end.expired()) {
                    driver.pause(1_000)
                    val now = driver.snapshot() ?: continue
                    val after = BlinkitScreenReader.orderStatus(now)
                    if (after.stage == OrderStage.CANCELLED) {
                        return BlinkitResult(true, BlinkitStatus.OK, "Blinkit's screen now shows the order as cancelled (verified)." + (if (after.refundMentioned) " The screen mentions a refund; I cannot confirm when it will arrive." else ""), verified = true)
                    }
                }
                return BlinkitResult(false, BlinkitStatus.NOT_VERIFIED, "I tapped Cancel but Blinkit's screen does not show the order as cancelled. I cannot say it was cancelled; check the order in Blinkit.")
            }
        }
    }
}
