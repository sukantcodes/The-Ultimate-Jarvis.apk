package com.jarvis.assistant.automation.amazon

import java.util.Locale

/** Everything the agent needs from the phone. The Android implementation lives in AndroidAmazonDriver. */
interface AmazonDriver {
    fun isAmazonInstalled(): Boolean
    fun foregroundPackage(): String?
    /** Visible nodes of the active window, or null when the screen cannot be read. */
    suspend fun snapshot(): List<AmazonNode>?
    /** Opens a link in the Amazon app (or the browser when the app is missing) and reports whether the target app came to the front. */
    suspend fun launch(url: String): Boolean
    /** Performs one click and returns whether it was dispatched. */
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
}

object AmazonStatus {
    const val OK = "OK"
    const val NEEDS_SIGN_IN = "NEEDS_SIGN_IN"
    const val NEEDS_USER = "NEEDS_USER"
    const val OFFLINE = "OFFLINE"
    const val ERROR = "ERROR"
    const val TIMEOUT = "TIMEOUT"
    const val CANCELLED = "CANCELLED"
    const val NOT_FOUND = "NOT_FOUND"
    const val AMBIGUOUS = "AMBIGUOUS"
    const val UNAVAILABLE = "UNAVAILABLE"
    const val MISMATCH = "MISMATCH"
    const val VARIANT_UNMET = "VARIANT_UNMET"
    const val NOT_VERIFIED = "NOT_VERIFIED"
    const val LAUNCH_FAILED = "LAUNCH_FAILED"
    const val SCREEN_UNREADABLE = "SCREEN_UNREADABLE"
    const val WRONG_SCREEN = "WRONG_SCREEN"
    const val REFUSED = "REFUSED"
    const val PLACED = "PLACED"
    const val NEEDS_PAYMENT_AUTH = "NEEDS_PAYMENT_AUTH"
    const val PREPARED = "PREPARED"
}

data class AmazonResult(
    val success: Boolean,
    val status: String,
    val message: String,
    val verified: Boolean = false,
    val extra: Map<String, Any?> = emptyMap()
)

/** Remembers what the last search showed and what was already added, so follow-up commands refer to real things. */
class AmazonSession {
    @Volatile var lastRequest: AmazonSearchRequest? = null
    @Volatile var lastResults: List<ProductCandidate> = emptyList()
    @Volatile var openProduct: ProductCandidate? = null
    @Volatile var lastCart: CartSnapshot? = null
    private val added = HashMap<String, Long>()

    @Synchronized fun recentlyAdded(key: String, now: Long, windowMs: Long = 120_000L): Boolean =
        added[key]?.let { now - it < windowMs } == true

    @Synchronized fun noteAdded(key: String, now: Long) { added[key] = now }
    @Synchronized fun clearAdded() { added.clear() }
}

class AmazonAgent(
    private val driver: AmazonDriver,
    private val gate: PurchaseGate,
    private val session: AmazonSession = AmazonSession(),
    private val market: AmazonLinks.Market = AmazonLinks.Market.IN
) {
    companion object {
        const val SEARCH_BUDGET_MS = 90_000L
        const val CART_BUDGET_MS = 75_000L
        const val CHECKOUT_BUDGET_MS = 100_000L
        const val ORDER_VERIFY_MS = 25_000L
        const val MAX_RESULTS_KEPT = 15
        const val MAX_SCROLL_PAGES = 4
    }

    private class Deadline(private val driver: AmazonDriver, budgetMs: Long) {
        private val end = driver.now() + budgetMs
        fun left(): Long = end - driver.now()
        fun expired(): Boolean = left() <= 0
    }

    private class Waited(val screen: AmazonScreen, val nodes: List<AmazonNode>, val code: String? = null)

    // ---- Waiting and recovery -------------------------------------------------------------------

    /**
     * Reads the screen until it is one of [accept] or a state only the user can fix. Along the way it waits out
     * loading, clicks "Try again" on error and offline pages (with growing pauses), dismisses harmless pop-ups
     * ("No thanks", "Not now") and reopens [reopenUrl] when the page stays broken.
     */
    private suspend fun waitFor(
        deadline: Deadline,
        timeoutMs: Long,
        reopenUrl: String?,
        accept: Set<AmazonScreen>,
        homeLimit: Int = Int.MAX_VALUE
    ): Waited {
        val limit = Deadline(driver, minOf(timeoutMs, deadline.left()))
        var recoveries = 0
        var dismissals = 0
        var unknownStreak = 0
        var homeStreak = 0
        var last: Waited = Waited(AmazonScreen.UNKNOWN, emptyList(), "TIMEOUT")
        while (!limit.expired()) {
            if (driver.cancelled()) return Waited(last.screen, last.nodes, "CANCELLED")
            val nodes = driver.snapshot()
            if (nodes == null) {
                last = Waited(AmazonScreen.UNKNOWN, emptyList(), "UNREADABLE")
                driver.pause(500)
                continue
            }
            val screen = AmazonScreenReader.classify(nodes)
            last = Waited(screen, nodes)
            if (screen in accept) return last
            homeStreak = if (screen == AmazonScreen.HOME) homeStreak + 1 else 0
            if (homeStreak >= homeLimit) return last
            when (screen) {
                AmazonScreen.SIGN_IN, AmazonScreen.CHALLENGE, AmazonScreen.ORDER_PLACED -> return last
                AmazonScreen.LOADING -> driver.pause(450)
                AmazonScreen.OFFLINE, AmazonScreen.ERROR -> {
                    if (recoveries >= 3) return last
                    recoveries++
                    driver.progress("Amazon page had a problem; retrying ($recoveries of 3)")
                    driver.pause(1_200L * recoveries)
                    val retry = AmazonScreenReader.retryButton(nodes)
                    if (retry != null) driver.click(retry)
                    else if (reopenUrl != null) driver.launch(reopenUrl)
                    driver.pause(900)
                }
                else -> {
                    val dismiss = AmazonScreenReader.dismissButton(nodes)
                    if (dismiss != null && dismissals < 3) {
                        dismissals++
                        driver.click(dismiss)
                        driver.pause(500)
                    } else {
                        unknownStreak++
                        if (unknownStreak == 8 && reopenUrl != null) driver.launch(reopenUrl)
                        driver.pause(450)
                    }
                }
            }
        }
        return Waited(last.screen, last.nodes, last.code ?: "TIMEOUT")
    }

    /** Converts a screen the agent cannot work with into an honest result for the user. */
    private fun stuck(w: Waited, doing: String): AmazonResult = when {
        w.code == "CANCELLED" -> AmazonResult(false, AmazonStatus.CANCELLED, "Stopped while $doing, as you asked.")
        w.screen == AmazonScreen.SIGN_IN -> AmazonResult(false, AmazonStatus.NEEDS_SIGN_IN,
            "Amazon is asking you to sign in. Please sign in yourself (Jarvis never types passwords), then tell me to continue.")
        w.screen == AmazonScreen.CHALLENGE -> AmazonResult(false, AmazonStatus.NEEDS_USER,
            "Amazon is asking for ${AmazonScreenReader.challengeKind(w.nodes)}. Please complete it yourself; Jarvis does not enter or bypass security checks. Tell me to continue afterwards.")
        w.screen == AmazonScreen.OFFLINE -> AmazonResult(false, AmazonStatus.OFFLINE,
            "Amazon cannot load because the phone seems to be offline. Check the internet connection and tell me to try again.")
        w.screen == AmazonScreen.ERROR -> AmazonResult(false, AmazonStatus.ERROR,
            "Amazon kept showing an error page while $doing, even after retries.")
        w.code == "UNREADABLE" -> AmazonResult(false, AmazonStatus.SCREEN_UNREADABLE,
            "The screen could not be read. Make sure the Jarvis accessibility service is on and the Amazon screen is visible.")
        else -> AmazonResult(false, AmazonStatus.TIMEOUT,
            "Amazon did not reach the expected screen while $doing within the time limit (last screen seen: ${w.screen.name.lowercase(Locale.ROOT).replace('_', ' ')}).")
    }

    private suspend fun open(url: String): Boolean {
        for (attempt in 1..2) {
            if (driver.cancelled()) return false
            if (driver.launch(url)) return true
            driver.pause(700)
        }
        return false
    }

    private fun launchFailure(): AmazonResult = if (driver.cancelled()) AmazonResult(false, AmazonStatus.CANCELLED, "Stopped, as you asked.") else AmazonResult(false, AmazonStatus.LAUNCH_FAILED,
        if (driver.isAmazonInstalled()) "Could not bring the Amazon app to the front. Open Amazon once yourself, then try again."
        else "The Amazon Shopping app is not installed, and the browser could not be opened either.")

    // ---- Search -----------------------------------------------------------------------------------

    suspend fun search(request: AmazonSearchRequest): AmazonResult {
        if (request.query.isBlank()) return AmazonResult(false, AmazonStatus.NOT_FOUND, "Tell me what to search for on Amazon.")
        val deadline = Deadline(driver, SEARCH_BUDGET_MS)
        driver.progress("Searching Amazon for ${request.query}")
        val url = AmazonLinks.searchUrl(request, market)
        if (!open(url)) return launchFailure()

        var waited = waitFor(deadline, 22_000, url, setOf(AmazonScreen.SEARCH_RESULTS), homeLimit = 4)
        if (waited.screen != AmazonScreen.SEARCH_RESULTS && waited.code != "CANCELLED" && waited.code != "UNREADABLE" &&
            waited.screen in setOf(AmazonScreen.HOME, AmazonScreen.UNKNOWN, AmazonScreen.PRODUCT, AmazonScreen.CART)
        ) {
            driver.progress("The link did not open the results; typing the search instead")
            waited = typedSearch(request, deadline, waited)
        }
        if (waited.screen != AmazonScreen.SEARCH_RESULTS) return stuck(waited, "searching for ${request.query}")

        val found = LinkedHashMap<String, ProductCandidate>()
        var nodes = waited.nodes
        var quietScrolls = 0
        for (page in 0..MAX_SCROLL_PAGES) {
            if (driver.cancelled()) return AmazonResult(false, AmazonStatus.CANCELLED, "Stopped the Amazon search.")
            val before = found.size
            AmazonScreenReader.extractProducts(nodes).forEach { p ->
                val dup = found.values.firstOrNull { it.price == p.price && TitleMatch.containment(it.title, p.title) >= 0.8 }
                if (dup == null) found[p.key] = p.copy(position = found.size)
            }
            driver.progress("Reading results: ${found.size} products so far")
            quietScrolls = if (found.size == before) quietScrolls + 1 else 0
            if (found.size >= 12 || quietScrolls >= 2 || page == MAX_SCROLL_PAGES || deadline.expired()) break
            if (!driver.scroll(true)) break
            driver.pause(700)
            val next = driver.snapshot() ?: break
            if (AmazonScreenReader.classify(next) != AmazonScreen.SEARCH_RESULTS) break
            nodes = next
        }
        if (found.isEmpty()) {
            return AmazonResult(false, AmazonStatus.NOT_FOUND,
                "I opened the Amazon results but could not read any product with a price from the screen. Nothing was guessed.")
        }

        val matching = AmazonRanker.filter(found.values.toList(), request)
        val ranked = AmazonRanker.rank(matching, request.sort).take(MAX_RESULTS_KEPT)
        session.lastRequest = request
        session.lastResults = ranked
        session.openProduct = null
        if (ranked.isEmpty()) {
            return AmazonResult(true, AmazonStatus.NOT_FOUND,
                "Amazon showed ${found.size} products, but none matched your limits (${describeRequest(request)}).",
                verified = true, extra = mapOf("seen" to found.size))
        }
        val top = ranked.take(5)
        val caveats = ArrayList<String>()
        top.forEach { c -> AmazonRanker.unverifiedFilters(c, request).takeIf { it.isNotEmpty() }?.let { caveats += "#${top.indexOf(c) + 1}: ${it.joinToString()} not shown on the card" } }
        val msg = StringBuilder("Found ${ranked.size} matching products (read from the screen). Top ${top.size}:\n")
        top.forEachIndexed { i, c -> msg.append(i + 1).append(". ").append(AmazonRanker.describe(c)).append('\n') }
        if (caveats.isNotEmpty()) msg.append("Note: ").append(caveats.joinToString("; ")).append('\n')
        return AmazonResult(true, AmazonStatus.OK, msg.toString().trim(), verified = true,
            extra = mapOf("count" to ranked.size, "request" to describeRequest(request)))
    }

    private suspend fun typedSearch(request: AmazonSearchRequest, deadline: Deadline, from: Waited): Waited {
        var nodes = from.nodes
        for (attempt in 1..2) {
            if (driver.cancelled() || deadline.expired()) break
            var box = AmazonScreenReader.searchBox(nodes)
            if (box == null) { driver.pause(500); nodes = driver.snapshot() ?: continue; box = AmazonScreenReader.searchBox(nodes) }
            if (box == null) continue
            driver.click(box)
            driver.pause(500)
            val fresh = driver.snapshot() ?: continue
            val field = AmazonScreenReader.searchBox(fresh)?.takeIf { it.editable } ?: fresh.firstOrNull { it.editable } ?: continue
            if (!driver.typeInto(field, request.query)) { driver.pause(400); continue }
            val submitted = driver.submitSearch() || run {
                val after = driver.snapshot().orEmpty()
                val suggestion = AmazonScreenReader.findByLabel(after) { l -> l == request.query.lowercase(Locale.ROOT).trim() && true }
                    ?: AmazonScreenReader.findByLabel(after) { it == "search" || it == "go" }
                suggestion != null && driver.click(suggestion)
            }
            if (!submitted) continue
            val w = waitFor(deadline, 18_000, null, setOf(AmazonScreen.SEARCH_RESULTS))
            if (w.screen == AmazonScreen.SEARCH_RESULTS || w.code != null || w.screen in setOf(AmazonScreen.SIGN_IN, AmazonScreen.CHALLENGE)) return w
            nodes = w.nodes
        }
        return Waited(AmazonScreen.UNKNOWN, nodes, "TIMEOUT")
    }

    private fun describeRequest(r: AmazonSearchRequest): String {
        val p = ArrayList<String>()
        if (r.minPrice != null) p += "from ${Money.format(r.minPrice)}"
        if (r.maxPrice != null) p += "up to ${Money.format(r.maxPrice)}"
        if (r.minRating != null) p += "${r.minRating}★ and above"
        if (r.primeOnly) p += "Prime only"
        if (r.sort != SortPreference.RELEVANCE) p += "sorted by ${r.sort.name.lowercase(Locale.ROOT).replace('_', ' ')}"
        return if (p.isEmpty()) "no extra limits" else p.joinToString(", ")
    }

    // ---- Compare ----------------------------------------------------------------------------------

    /** Compares products from the last search. With [deep] each product page is opened to read specifications. */
    suspend fun compare(indices: List<Int>, deep: Boolean): AmazonResult {
        val results = session.lastResults
        if (results.isEmpty()) return AmazonResult(false, AmazonStatus.NOT_FOUND, "There are no search results to compare yet. Search first.")
        val chosen = (if (indices.isEmpty()) (1..minOf(3, results.size)).toList() else indices)
            .distinct().filter { it in 1..results.size }.take(4)
        if (chosen.size < 2) return AmazonResult(false, AmazonStatus.NOT_FOUND, "Pick at least two results from the last search to compare.")
        val items = chosen.map { results[it - 1] }
        val comparison = AmazonRanker.compare(items)
        val deadline = Deadline(driver, SEARCH_BUDGET_MS)
        val lines = ArrayList<String>()
        var detailsRead = 0
        for ((i, line) in comparison.lines.withIndex()) {
            var details: ProductDetails? = null
            if (deep && !deadline.expired() && !driver.cancelled()) {
                driver.progress("Reading specifications ${i + 1} of ${items.size}")
                details = readDetails(items[i], deadline)
                if (details != null) detailsRead++
            }
            lines += if (deep) AmazonRanker.withDetails(line, details) else line
        }
        val sb = StringBuilder("Comparison of ${items.size} products (all values read from Amazon's screen):\n")
        sb.append(lines.joinToString("\n"))
        comparison.bestValue?.let { sb.append("\nBest value: ").append(it.title.take(60)) }
        comparison.bestPrice?.let { sb.append("\nLowest price incl. visible delivery: ").append(it.title.take(60)).append(" at ").append(Money.format(it.totalCost)) }
        comparison.bestRated?.let { sb.append("\nBest rated (trusting only well-reviewed items): ").append(it.title.take(60)) }
        if (deep && detailsRead < items.size) sb.append("\nSpecifications could not be read for ${items.size - detailsRead} product(s); they are marked above.")
        return AmazonResult(true, AmazonStatus.OK, sb.toString(), verified = true,
            extra = mapOf("compared" to chosen, "details_read" to detailsRead))
    }

    private suspend fun readDetails(candidate: ProductCandidate, deadline: Deadline): ProductDetails? {
        val opened = openCandidate(candidate, deadline)
        if (opened.first != null) return null
        var nodes = opened.second
        var details = AmazonProductReader.details(nodes)
        if (details.specs.isEmpty() && !deadline.expired()) {
            for (i in 1..3) {
                if (!driver.scroll(true)) break
                driver.pause(600)
                nodes = driver.snapshot() ?: break
                details = AmazonProductReader.details(nodes).let { d -> if (d.specs.isNotEmpty()) d.copy(price = details.price ?: d.price, rating = details.rating ?: d.rating) else details }
                if (details.specs.isNotEmpty()) break
            }
        }
        driver.back()
        driver.pause(700)
        waitFor(deadline, 8_000, null, setOf(AmazonScreen.SEARCH_RESULTS))
        return details
    }

    // ---- Open a product ---------------------------------------------------------------------------

    /** Finds the product's card on the results list (scrolling if needed), opens it, and checks the page title. */
    private suspend fun openCandidate(candidate: ProductCandidate, deadline: Deadline): Pair<AmazonResult?, List<AmazonNode>> {
        var nodes = driver.snapshot() ?: return AmazonResult(false, AmazonStatus.SCREEN_UNREADABLE, "The screen could not be read.") to emptyList()
        if (AmazonScreenReader.classify(nodes) != AmazonScreen.SEARCH_RESULTS) {
            val url = session.lastRequest?.let { AmazonLinks.searchUrl(it, market) }
            if (url == null || !open(url)) return launchFailure() to emptyList()
            val w = waitFor(deadline, 20_000, url, setOf(AmazonScreen.SEARCH_RESULTS))
            if (w.screen != AmazonScreen.SEARCH_RESULTS) return stuck(w, "reopening the results") to emptyList()
            nodes = w.nodes
        }
        fun cardFor(list: List<AmazonNode>): ScreenBlock? =
            AmazonScreenReader.blocks(list).filter { TitleMatch.containment(it.title, candidate.title) >= 0.6 }
                .firstOrNull { b -> AmazonText.priceFields(b.text).first.let { it == null || it == candidate.price } }

        var card = cardFor(nodes)
        var tries = 0
        // The card may be above or below: go to the top, then walk down.
        while (card == null && tries < 14 && !deadline.expired() && !driver.cancelled()) {
            val goingUp = tries < 5
            if (!driver.scroll(!goingUp)) break
            driver.pause(550)
            nodes = driver.snapshot() ?: break
            card = cardFor(nodes)
            tries++
        }
        val target = card ?: return AmazonResult(false, AmazonStatus.NOT_FOUND,
            "Could not find '${candidate.title.take(50)}' on the results screen again, so nothing was opened.") to nodes
        val clickNode = target.nodes.firstOrNull { it.clickable && TitleMatch.containment(it.label, candidate.title) >= 0.5 }
            ?: target.nodes.firstOrNull { it.clickable } ?: target.nodes.first()
        if (!driver.click(clickNode)) return AmazonResult(false, AmazonStatus.ERROR, "The tap on the product did not go through.") to nodes
        val w = waitFor(deadline, 18_000, null, setOf(AmazonScreen.PRODUCT))
        if (w.screen != AmazonScreen.PRODUCT) return stuck(w, "opening the product") to w.nodes
        val pageTitle = AmazonProductReader.title(w.nodes)
        if (pageTitle.isNotBlank() && TitleMatch.containment(pageTitle, candidate.title) < 0.45) {
            driver.back()
            return AmazonResult(false, AmazonStatus.MISMATCH,
                "The page that opened ('${pageTitle.take(60)}') does not match the chosen product ('${candidate.title.take(60)}'), so nothing was changed.") to w.nodes
        }
        session.openProduct = candidate
        return null to w.nodes
    }

    suspend fun openProduct(index: Int): AmazonResult {
        val candidate = session.lastResults.getOrNull(index - 1)
            ?: return AmazonResult(false, AmazonStatus.NOT_FOUND, "There is no result number $index in the last search.")
        val deadline = Deadline(driver, SEARCH_BUDGET_MS)
        driver.progress("Opening result $index")
        val (err, nodes) = openCandidate(candidate, deadline)
        if (err != null) return err
        val d = AmazonProductReader.details(nodes)
        val sb = StringBuilder("Opened: ${d.title.ifBlank { candidate.title }.take(100)}\n")
        sb.append("Price ${Money.format(d.price ?: candidate.price)}")
        d.rating?.let { sb.append(", rating ").append(it) }
        if (d.unavailable) sb.append("\nThis item is currently unavailable.")
        d.specs.take(4).forEach { sb.append("\n- ").append(it.take(110)) }
        return AmazonResult(true, AmazonStatus.OK, sb.toString(), verified = true, extra = mapOf("unavailable" to d.unavailable))
    }

    // ---- Add to cart ------------------------------------------------------------------------------

    suspend fun addToCart(index: Int?, quantity: Int, variants: List<String>): AmazonResult {
        val deadline = Deadline(driver, CART_BUDGET_MS)
        var candidate: ProductCandidate? = session.openProduct
        if (index != null) {
            candidate = session.lastResults.getOrNull(index - 1)
                ?: return AmazonResult(false, AmazonStatus.NOT_FOUND, "There is no result number $index in the last search.")
            driver.progress("Opening the product to add it")
            val (err, _) = openCandidate(candidate, deadline)
            if (err != null) return err
        }
        var nodes = driver.snapshot() ?: return AmazonResult(false, AmazonStatus.SCREEN_UNREADABLE, "The screen could not be read.")
        if (AmazonScreenReader.classify(nodes) != AmazonScreen.PRODUCT) {
            return AmazonResult(false, AmazonStatus.WRONG_SCREEN,
                "No product page is open. Open one first (by its number from the search results).")
        }
        val details = AmazonProductReader.details(nodes)
        val title = candidate?.title?.takeIf { it.isNotBlank() } ?: details.title
        if (title.isBlank()) return AmazonResult(false, AmazonStatus.NOT_VERIFIED, "The product title cannot be read, so it cannot be verified in the cart.")
        if (details.unavailable) return AmazonResult(false, AmazonStatus.UNAVAILABLE, "That product is currently unavailable on Amazon.")
        val key = CartItem(title, details.price ?: candidate?.price).key
        if (session.recentlyAdded(key, driver.now())) {
            return AmazonResult(true, AmazonStatus.OK, "That product was just added to the cart, so I did not add it again.", verified = true)
        }

        // Variants first; if one cannot be selected nothing is added.
        val unmet = ArrayList<String>()
        for (variant in variants) {
            if (!selectVariant(variant, deadline)) unmet += variant
        }
        if (unmet.isNotEmpty()) {
            return AmazonResult(false, AmazonStatus.VARIANT_UNMET,
                "Could not select ${unmet.joinToString(", ")} on this product (not offered, unavailable, or not confirmed on screen). Nothing was added to the cart.")
        }

        driver.progress("Adding to cart")
        var button: AmazonNode? = null
        for (i in 0..5) {
            nodes = driver.snapshot() ?: break
            button = AmazonScreenReader.addToCartButton(nodes)
            if (button != null || deadline.expired() || driver.cancelled()) break
            if (!driver.scroll(true)) break
            driver.pause(500)
        }
        if (button == null) return AmazonResult(false, AmazonStatus.NOT_FOUND, "The Add to Cart button was not found on this page.")
        if (!driver.click(button)) return AmazonResult(false, AmazonStatus.ERROR, "The Add to Cart tap did not go through.")
        driver.pause(1_200)
        val sawConfirmation = driver.snapshot()?.let { AmazonScreenReader.addedToCartVisible(it) } == true

        // Authoritative check: the cart itself.
        val cart = readCart(deadline)
        if (cart.first != null) {
            return if (sawConfirmation) AmazonResult(false, AmazonStatus.NOT_VERIFIED,
                "Amazon showed an 'Added to cart' message, but the cart could not be opened to confirm: ${cart.first!!.message}")
            else cart.first!!
        }
        val snapshot = cart.second!!
        val inCart = snapshot.items.firstOrNull { TitleMatch.containment(it.title, title) >= 0.6 }
        if (inCart == null) {
            return AmazonResult(false, AmazonStatus.NOT_VERIFIED,
                "I pressed Add to Cart but the item is not visible in the cart, so I cannot say it was added.")
        }
        session.noteAdded(key, driver.now())
        var finalItem = inCart
        if (quantity > 1 && inCart.quantity != quantity) {
            val q = setQuantity(title, quantity, deadline)
            if (q.first != null) {
                return AmazonResult(true, AmazonStatus.NOT_VERIFIED,
                    "Added to the cart (verified), but the quantity could not be set to $quantity: ${q.first!!.message}",
                    verified = true, extra = mapOf("in_cart" to true))
            }
            finalItem = q.second ?: finalItem
        }
        return AmazonResult(true, AmazonStatus.OK,
            "Verified in the cart: ${finalItem.title.take(70)} × ${finalItem.quantity}" +
                (finalItem.price?.let { " at ${Money.format(it)}" } ?: "") +
                (snapshot.subtotal?.let { ". Cart subtotal ${Money.format(it)}" } ?: ""),
            verified = true, extra = mapOf("in_cart" to true, "quantity" to finalItem.quantity))
    }

    private suspend fun selectVariant(variant: String, deadline: Deadline): Boolean {
        for (i in 0..5) {
            if (driver.cancelled() || deadline.expired()) return false
            val nodes = driver.snapshot() ?: return false
            if (AmazonScreenReader.isVariantSelected(nodes, variant)) return true
            val node = AmazonScreenReader.variantNode(nodes, variant)
            if (node != null) {
                driver.click(node)
                driver.pause(900)
                val after = driver.snapshot() ?: return false
                return AmazonScreenReader.isVariantSelected(after, variant)
            }
            if (!driver.scroll(i < 4)) return false
            driver.pause(450)
        }
        return false
    }

    // ---- Cart -------------------------------------------------------------------------------------

    private suspend fun readCart(deadline: Deadline): Pair<AmazonResult?, CartSnapshot?> {
        val url = AmazonLinks.cartUrl(market)
        if (!open(url)) return launchFailure() to null
        val w = waitFor(deadline, 20_000, url, setOf(AmazonScreen.CART))
        if (w.screen != AmazonScreen.CART) return stuck(w, "opening the cart") to null
        var nodes = w.nodes
        var snap = AmazonScreenReader.extractCart(nodes)
        // Long carts: look one page further down once.
        if (!snap.empty && driver.scroll(true)) {
            driver.pause(600)
            nodes = driver.snapshot() ?: nodes
            val more = AmazonScreenReader.extractCart(nodes)
            snap = CartSnapshot((snap.items + more.items).distinctBy { it.key }, snap.subtotal ?: more.subtotal, false)
            driver.scroll(false)
        }
        session.lastCart = snap
        return null to snap
    }

    suspend fun cartAction(action: String, item: String?, quantity: Int?): AmazonResult {
        val deadline = Deadline(driver, CART_BUDGET_MS)
        driver.progress("Opening the cart")
        when (action) {
            "check_order" -> return checkOrderScreen()
            "view", "show", "" -> {
                val (err, snap) = readCart(deadline)
                if (err != null) return err
                return AmazonResult(true, AmazonStatus.OK, describeCart(snap!!), verified = true, extra = mapOf("items" to snap.items.size))
            }
            "remove" -> {
                if (item.isNullOrBlank()) return AmazonResult(false, AmazonStatus.NOT_FOUND, "Which cart item should I remove?")
                return removeItem(item, deadline)
            }
            "set_quantity" -> {
                if (item.isNullOrBlank() || quantity == null) return AmazonResult(false, AmazonStatus.NOT_FOUND, "I need the item and the new quantity.")
                val (err, _) = readCart(deadline)
                if (err != null) return err
                val q = setQuantity(item, quantity, deadline)
                return q.first ?: AmazonResult(true, AmazonStatus.OK,
                    "Quantity of ${q.second!!.title.take(60)} is now ${q.second!!.quantity} (verified in the cart).", verified = true)
            }
            else -> return AmazonResult(false, AmazonStatus.NOT_FOUND, "Unknown cart action '$action'. Use view, remove or set_quantity.")
        }
    }

    private fun describeCart(s: CartSnapshot): String {
        if (s.empty || s.items.isEmpty()) return "The Amazon cart is empty."
        val sb = StringBuilder("Cart has ${s.items.size} item(s):\n")
        s.items.forEachIndexed { i, c -> sb.append(i + 1).append(". ").append(c.title.take(70)).append(" × ").append(c.quantity).append(" — ").append(Money.format(c.price)).append('\n') }
        s.subtotal?.let { sb.append("Subtotal ").append(Money.format(it)) }
        return sb.toString().trim()
    }

    /** Words of [query] that must all appear in a cart title for it to count as the item the user means. */
    private fun matchesItem(title: String, query: String): Boolean {
        val q = TitleMatch.tokens(query)
        if (q.isEmpty()) return false
        return q.all { it in TitleMatch.tokens(title) || TitleMatch.normalize(title).contains(it) }
    }

    private suspend fun removeItem(query: String, deadline: Deadline): AmazonResult {
        val (err, snap) = readCart(deadline)
        if (err != null) return err
        val nodes = driver.snapshot() ?: return AmazonResult(false, AmazonStatus.SCREEN_UNREADABLE, "The cart screen could not be read.")
        val blocks = AmazonScreenReader.blocks(nodes).filter { matchesItem(it.title, query) }
        if (blocks.isEmpty()) return AmazonResult(false, AmazonStatus.NOT_FOUND, "No cart item matches '$query'. " + describeCart(snap!!))
        if (blocks.size > 1) return AmazonResult(false, AmazonStatus.AMBIGUOUS,
            "More than one cart item matches '$query': " + blocks.joinToString("; ") { it.title.take(50) } + ". Say which one to remove.")
        val block = blocks.first()
        val delete = AmazonScreenReader.deleteButtonFor(nodes, block)
            ?: return AmazonResult(false, AmazonStatus.NOT_FOUND, "The Delete button for that cart item was not found.")
        driver.progress("Removing from the cart")
        if (!driver.click(delete)) return AmazonResult(false, AmazonStatus.ERROR, "The Delete tap did not go through.")
        driver.pause(1_300)
        val after = driver.snapshot() ?: return AmazonResult(false, AmazonStatus.NOT_VERIFIED, "Pressed Delete but could not re-read the cart to confirm.")
        val remaining = AmazonScreenReader.extractCart(after)
        val stillThere = remaining.items.any { matchesItem(it.title, query) }
        return if (!stillThere) {
            session.lastCart = remaining
            AmazonResult(true, AmazonStatus.OK, "Removed from the cart (verified: it no longer appears).", verified = true)
        } else AmazonResult(false, AmazonStatus.NOT_VERIFIED, "Pressed Delete but the item still appears in the cart.")
    }

    /** Sets the quantity of a cart row and verifies it by re-reading the row. Returns (error, item). */
    private suspend fun setQuantity(query: String, target: Int, deadline: Deadline): Pair<AmazonResult?, CartItem?> {
        if (target !in 1..20) return AmazonResult(false, AmazonStatus.NOT_FOUND, "Quantity must be between 1 and 20.") to null
        var nodes = driver.snapshot() ?: return AmazonResult(false, AmazonStatus.SCREEN_UNREADABLE, "The cart screen could not be read.") to null
        if (AmazonScreenReader.classify(nodes) != AmazonScreen.CART) return AmazonResult(false, AmazonStatus.WRONG_SCREEN, "The cart is not open.") to null
        fun rowItem(list: List<AmazonNode>): CartItem? = AmazonScreenReader.extractCart(list).items.firstOrNull { matchesItem(it.title, query) }

        val row = rowItem(nodes) ?: return AmazonResult(false, AmazonStatus.NOT_FOUND, "That item is not in the cart.") to null
        if (row.quantity == target) return null to row
        // Way 1: the Qty control opens a list of numbers.
        val qtyNode = AmazonScreenReader.quantityControl(nodes)
        if (qtyNode != null && driver.click(qtyNode)) {
            driver.pause(700)
            val picker = driver.snapshot()
            val choice = picker?.let { AmazonScreenReader.findByLabel(it) { l -> l == target.toString() || l == "qty: $target" || l == "quantity: $target" } }
            if (choice != null && driver.click(choice)) {
                driver.pause(1_200)
                nodes = driver.snapshot() ?: nodes
                rowItem(nodes)?.takeIf { it.quantity == target }?.let { return null to it }
            } else driver.back()
        }
        // Way 2: plus / minus buttons, re-reading after every tap.
        var current = rowItem(driver.snapshot() ?: nodes)?.quantity ?: row.quantity
        var taps = 0
        while (current != target && taps < 12 && !deadline.expired() && !driver.cancelled()) {
            val live = driver.snapshot() ?: break
            val wantUp = target > current
            val button = AmazonScreenReader.findByLabel(live) { l ->
                if (wantUp) l.startsWith("increase quantity") || l == "add one" || l == "+" || l.startsWith("increment")
                else l.startsWith("decrease quantity") || l == "remove one" || l == "-" || l == "−" || l.startsWith("decrement")
            } ?: break
            if (!driver.click(button)) break
            driver.pause(1_000)
            val newQty = rowItem(driver.snapshot() ?: break)?.quantity ?: break
            if (newQty == current) break
            current = newQty
            taps++
        }
        val finalRow = rowItem(driver.snapshot() ?: nodes)
        return if (finalRow != null && finalRow.quantity == target) null to finalRow
        else AmazonResult(false, AmazonStatus.NOT_VERIFIED,
            "Could not confirm the quantity is $target (it shows ${finalRow?.quantity ?: "unknown"}).") to finalRow
    }

    // ---- Checkout ---------------------------------------------------------------------------------

    private val CONTINUE_LABELS = setOf("deliver to this address", "use this address", "use this payment method", "continue")

    /**
     * Opens the cart, proceeds to checkout, and stops on the final review screen. It never presses the order
     * button. The summary it returns is what the user must hear and approve.
     */
    suspend fun prepareCheckout(utterance: String): AmazonResult {
        val deadline = Deadline(driver, CHECKOUT_BUDGET_MS)
        driver.progress("Opening the cart")
        gate.clear()
        val (err, cart) = readCart(deadline)
        if (err != null) return err
        if (cart!!.empty || cart.items.isEmpty()) return AmazonResult(false, AmazonStatus.NOT_FOUND, "The cart is empty, so there is nothing to check out.")

        var nodes = driver.snapshot() ?: return AmazonResult(false, AmazonStatus.SCREEN_UNREADABLE, "The cart screen could not be read.")
        var proceed: AmazonNode? = null
        for (i in 0..5) {
            proceed = AmazonScreenReader.proceedToBuyButton(nodes)
            if (proceed != null || driver.cancelled()) break
            if (!driver.scroll(true)) break
            driver.pause(450)
            nodes = driver.snapshot() ?: break
        }
        if (proceed == null) return AmazonResult(false, AmazonStatus.NOT_FOUND, "The Proceed to Buy button was not found in the cart.")
        driver.progress("Going to checkout")
        if (!driver.click(proceed)) return AmazonResult(false, AmazonStatus.ERROR, "The Proceed to Buy tap did not go through.")

        var steps = 0
        while (steps < 8 && !deadline.expired()) {
            steps++
            if (driver.cancelled()) return AmazonResult(false, AmazonStatus.CANCELLED, "Stopped during checkout. Nothing was ordered.")
            val w = waitFor(deadline, 25_000, null, setOf(AmazonScreen.ORDER_REVIEW, AmazonScreen.CHECKOUT))
            when (w.screen) {
                AmazonScreen.ORDER_REVIEW -> return reviewResult(w.nodes, cart, utterance)
                AmazonScreen.CHECKOUT -> {
                    // Only continue with what Amazon already selected; never change the address or payment.
                    val buttons = AmazonScreenReader.findAllByLabel(w.nodes) { it in CONTINUE_LABELS && true }
                        .filter { it.clickable }
                    if (buttons.size != 1) {
                        return AmazonResult(false, AmazonStatus.AMBIGUOUS,
                            "Amazon is asking you to choose an address or payment method (${buttons.size} choices). Please pick it yourself, then tell me to continue. Nothing was ordered.")
                    }
                    driver.progress("Continuing with the address and payment Amazon has selected")
                    driver.click(buttons.first())
                    driver.pause(1_000)
                }
                else -> return stuck(w, "going through checkout")
            }
        }
        return AmazonResult(false, AmazonStatus.TIMEOUT, "Checkout did not reach the final review screen in time. Nothing was ordered.")
    }

    private fun reviewResult(nodes: List<AmazonNode>, cart: CartSnapshot, utterance: String): AmazonResult {
        val summary = AmazonScreenReader.extractCheckout(nodes)
        if (!summary.readable) {
            return AmazonResult(false, AmazonStatus.NOT_VERIFIED,
                "I reached the review screen but could not read the items and total reliably, so I will not offer to place it. Please review it yourself on screen.")
        }
        val cartTitles = cart.items.map { it.title }
        val unmatched = cartTitles.filter { ct -> summary.items.none { TitleMatch.containment(it.title, ct) >= 0.5 } }
        val order = gate.prepare(summary, utterance)
        val sb = StringBuilder("READY FOR YOUR CONFIRMATION. Order NOT placed.\n")
        summary.items.forEach { sb.append("- ").append(it.title.take(70)).append(" × ").append(it.quantity).append(" — ").append(Money.format(it.price)).append('\n') }
        summary.itemsTotal?.let { sb.append("Items: ").append(Money.format(it)).append('\n') }
        summary.deliveryFee?.let { sb.append("Delivery: ").append(if (it == 0.0) "free" else Money.format(it)).append('\n') }
        sb.append("Total to pay: ").append(Money.format(summary.total)).append('\n')
        sb.append("Deliver to: ").append(summary.addressLabel ?: "address not readable").append('\n')
        sb.append("Payment: ").append(summary.paymentLabel ?: "payment method not readable").append('\n')
        summary.deliveryEstimate?.let { sb.append("Delivery estimate: ").append(it).append('\n') }
        if (unmatched.isNotEmpty()) sb.append("WARNING: these cart items were not found on the review screen: ").append(unmatched.joinToString("; ") { it.take(40) }).append('\n')
        sb.append("Read this to the user and wait for them to say clearly that you should place the order. Do not call amazon_place_order in this same turn.")
        return AmazonResult(true, AmazonStatus.PREPARED, sb.toString(), verified = true,
            extra = mapOf("total" to summary.total, "fingerprint" to order.fingerprint, "cart_mismatch" to unmatched.isNotEmpty()))
    }

    // ---- Place the order --------------------------------------------------------------------------

    suspend fun placeOrder(utterance: String): AmazonResult {
        val w0 = driver.snapshot() ?: return AmazonResult(false, AmazonStatus.SCREEN_UNREADABLE, "The screen could not be read, so the order was not placed.")
        if (AmazonScreenReader.classify(w0) != AmazonScreen.ORDER_REVIEW) {
            return AmazonResult(false, AmazonStatus.WRONG_SCREEN,
                "The Amazon order review screen is not open, so nothing was ordered. Prepare the checkout again.")
        }
        val current = AmazonScreenReader.extractCheckout(w0)
        when (val decision = gate.authorize(current, utterance)) {
            is GateDecision.Denied -> {
                if (decision.code == "CHANGED" && current.readable) {
                    gate.prepare(current, utterance)
                    return AmazonResult(false, AmazonStatus.REFUSED,
                        decision.reason + " New total: ${Money.format(current.total)}. Ask the user to confirm again.",
                        extra = mapOf("code" to decision.code))
                }
                return AmazonResult(false, AmazonStatus.REFUSED, decision.reason, extra = mapOf("code" to decision.code))
            }
            is GateDecision.Allowed -> {
                val order = decision.order
                val button = AmazonScreenReader.placeOrderButton(w0)
                    ?: return AmazonResult(false, AmazonStatus.NOT_FOUND, "The Place Order button was not found, so nothing was ordered.")
                // Recorded BEFORE the tap so that, whatever happens next, this checkout can never be submitted twice.
                gate.markAttempted(order)
                driver.progress("Placing the order")
                var dispatched = driver.click(button)
                if (!dispatched) {
                    driver.pause(800)
                    val again = driver.snapshot()
                    val b2 = again?.let { if (AmazonScreenReader.classify(it) == AmazonScreen.ORDER_REVIEW) AmazonScreenReader.placeOrderButton(it) else null }
                    // A retry is only safe when the first tap was never dispatched at all.
                    if (b2 != null) dispatched = driver.click(b2)
                }
                if (!dispatched) return AmazonResult(false, AmazonStatus.ERROR,
                    "The tap on Place Order could not be sent. The order was NOT placed.")
                return verifyPlacement(order)
            }
        }
    }

    private suspend fun verifyPlacement(order: PendingOrder): AmazonResult {
        val deadline = Deadline(driver, ORDER_VERIFY_MS)
        var lastScreen = AmazonScreen.UNKNOWN
        var lastNodes: List<AmazonNode> = emptyList()
        while (!deadline.expired()) {
            driver.pause(1_000)
            val nodes = driver.snapshot() ?: continue
            val screen = AmazonScreenReader.classify(nodes)
            lastScreen = screen; lastNodes = nodes
            when (screen) {
                AmazonScreen.ORDER_PLACED -> {
                    gate.markPlaced(order)
                    session.clearAdded()
                    return AmazonResult(true, AmazonStatus.PLACED,
                        "Order placed — Amazon's confirmation screen is showing. Total ${Money.format(order.summary.total)}.", verified = true)
                }
                AmazonScreen.CHALLENGE -> return AmazonResult(false, AmazonStatus.NEEDS_PAYMENT_AUTH,
                    "Amazon needs ${AmazonScreenReader.challengeKind(nodes)} to finish paying. Please complete it yourself. The order is NOT confirmed yet; ask me to check the order afterwards.")
                AmazonScreen.SIGN_IN -> return AmazonResult(false, AmazonStatus.NEEDS_SIGN_IN,
                    "Amazon asked you to sign in again. The order is NOT confirmed. Sign in yourself, then ask me to check the order.")
                else -> Unit
            }
        }
        return AmazonResult(false, AmazonStatus.NOT_VERIFIED,
            "I pressed Place Order but did not see Amazon's order confirmation (last screen: ${lastScreen.name.lowercase(Locale.ROOT).replace('_', ' ')}). " +
                "I cannot say it was placed. Check Your Orders in Amazon before trying again; I will not press it a second time.")
    }

    /** Reads the current screen and says whether Amazon is showing an order confirmation. */
    private suspend fun checkOrderScreen(): AmazonResult {
        val nodes = driver.snapshot() ?: return AmazonResult(false, AmazonStatus.SCREEN_UNREADABLE, "The screen could not be read.")
        return when (AmazonScreenReader.classify(nodes)) {
            AmazonScreen.ORDER_PLACED -> {
                gate.pending?.let { gate.markPlaced(it) }
                AmazonResult(true, AmazonStatus.PLACED, "Amazon's order confirmation is on screen: the order was placed.", verified = true)
            }
            AmazonScreen.CHALLENGE -> AmazonResult(false, AmazonStatus.NEEDS_PAYMENT_AUTH,
                "Amazon is still waiting for ${AmazonScreenReader.challengeKind(nodes)}. The order is not confirmed.")
            AmazonScreen.ORDER_REVIEW -> AmazonResult(false, AmazonStatus.NOT_VERIFIED,
                "The review screen is still showing, so the order is not placed yet.")
            else -> AmazonResult(false, AmazonStatus.NOT_VERIFIED,
                "No order confirmation is on screen. Open Your Orders in Amazon to check; I cannot confirm it from here.")
        }
    }
}
