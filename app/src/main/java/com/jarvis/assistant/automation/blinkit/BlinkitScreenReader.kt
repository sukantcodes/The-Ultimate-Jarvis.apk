package com.jarvis.assistant.automation.blinkit

import com.jarvis.assistant.automation.amazon.AmazonNode
import com.jarvis.assistant.automation.amazon.AmazonScreenReader
import com.jarvis.assistant.automation.amazon.AmazonText
import com.jarvis.assistant.automation.amazon.TitleMatch
import java.util.Locale

/** What closed a product card in the tree: the control that sits at its end. */
enum class Closer { ADD, STEPPER, NOTIFY, OPTIONS, NONE }

/** One product card or cart row as it appears on screen, before it is turned into a product. */
data class RawCard(
    val nodes: List<AmazonNode>,
    val closer: Closer,
    val closerNode: AmazonNode?,
    val minusNode: AmazonNode?,
    val qty: Int,
    /** Index of the closing node in the screen's node list (document order). */
    val endIndex: Int
) {
    val text: String get() = nodes.joinToString(" | ") { it.label }
}

/**
 * Reads Blinkit screens from a list of accessibility nodes: which screen it is, the products, the cart and bill, the
 * delivery address and saved addresses, and order tracking. Every rule is label based and never pixel based, so
 * different screen sizes and layouts keep working. Labels are English; another app language is reported as an unknown
 * screen instead of being guessed at.
 *
 * Product cards are grouped in document order: a card ends at its ADD / stepper / Notify me control. That is how
 * RecyclerView and Compose lists expose items, and it is what makes two-column grids readable. Blinkit publishes no
 * documented label list and changes its app often, so these rules MUST be validated on a real device.
 */
object BlinkitScreenReader {

    // ---- Phrase lists -------------------------------------------------------------------------------

    private val CHALLENGE_PHRASES = listOf(
        "enter otp", "enter the otp", "one time password", "one-time password", "otp sent", "otp has been sent",
        "verification code", "enter the code", "captcha", "not a robot", "verify your identity", "enter upi pin",
        "upi pin", "enter your pin", "enter cvv", "security code", "3d secure", "authorise payment", "authorize payment",
        "approve the request", "approve payment", "collect request", "complete the payment in"
    )
    private val SIGN_IN_PHRASES = listOf(
        "enter your phone number", "enter phone number", "enter mobile number", "enter your mobile number",
        "continue with phone", "log in or sign up", "login or sign up", "sign up or log in", "login to continue"
    )
    private val OFFLINE_PHRASES = listOf(
        "no internet", "you're offline", "you are offline", "check your internet", "check your connection", "no network",
        "network error", "unable to connect", "connection lost", "internet connection"
    )
    private val ERROR_PHRASES = listOf(
        "something went wrong", "went wrong", "try again", "oops", "we're sorry", "we are sorry", "could not load",
        "couldn't load", "unable to load", "service unavailable", "server error"
    )
    private val NOT_SERVICEABLE_PHRASES = listOf(
        "not delivering to your location", "not delivering here", "don't deliver here", "do not deliver here",
        "doesn't deliver here", "not serviceable", "coming soon to your location", "we are not available in your area",
        "we're not available in your area", "currently not available at your location", "outside our delivery area",
        "no stores nearby", "not available at your location", "not delivering to this location"
    )
    private val STORE_CLOSED_PHRASES = listOf(
        "high demand", "not accepting orders", "stores are closed", "store is closed", "we'll be back", "we will be back",
        "currently closed", "unable to take orders", "temporarily not delivering", "taking a break", "not able to deliver right now"
    )
    private val UNAVAILABLE_PHRASES = listOf("out of stock", "sold out", "currently unavailable", "notify me", "not available")
    private val ORDER_PLACED_PHRASES = listOf(
        "order placed", "order confirmed", "your order has been placed", "order has been placed", "thank you for your order",
        "we have received your order", "order received"
    )
    private val TRACKING_PHRASES = listOf(
        "arriving in", "preparing your order", "packing your order", "being packed", "is on the way", "on the way",
        "delivery partner", "order id", "out for delivery", "your order is being", "has arrived", "partner assigned",
        "order delivered", "delivered in", "order cancelled", "order canceled"
    )
    private val ADDRESS_PICKER_PHRASES = listOf(
        "select delivery address", "select address", "select your address", "saved addresses", "add new address",
        "use current location", "confirm location", "search for area", "choose delivery address", "change address"
    )

    private fun blob(nodes: List<AmazonNode>): String =
        nodes.filter { it.label.isNotBlank() || it.editable }.joinToString(" \n ") { it.haystack }

    // ---- Screen classification ----------------------------------------------------------------------

    fun classify(nodes: List<AmazonNode>): BlinkitScreen {
        val visible = nodes.filter { it.label.isNotBlank() || it.editable }
        val labeled = visible.count { it.label.isNotBlank() }
        val text = blob(nodes)
        val cards = cards(nodes)
        val addCards = cards.count { it.closer == Closer.ADD || it.closer == Closer.OPTIONS }
        val proceed = proceedButton(nodes)
        val pay = finalPayButton(nodes)
        val hasBill = text.contains("bill details") || text.contains("item total") || text.contains("grand total") || text.contains("to pay")
        val priceNodes = visible.count { AmazonText.hasPrice(it.haystack) }

        if (labeled < 3 && !text.contains("search")) return BlinkitScreen.LOADING
        if (CHALLENGE_PHRASES.any { text.contains(it) }) return BlinkitScreen.CHALLENGE
        if (NOT_SERVICEABLE_PHRASES.any { text.contains(it) } && addCards == 0) return BlinkitScreen.NOT_SERVICEABLE
        if (STORE_CLOSED_PHRASES.any { text.contains(it) } && addCards == 0 && pay == null) return BlinkitScreen.STORE_CLOSED

        val hasEditable = visible.any { it.editable }
        if (SIGN_IN_PHRASES.any { text.contains(it) } && addCards == 0 && proceed == null && pay == null) return BlinkitScreen.SIGN_IN
        if (hasEditable && (text.contains("phone number") || text.contains("mobile number")) && (text.contains("login") || text.contains("log in")) &&
            addCards == 0 && proceed == null && pay == null && priceNodes == 0
        ) return BlinkitScreen.SIGN_IN

        if (pay != null) return BlinkitScreen.PAYMENT
        if (TRACKING_PHRASES.count { text.contains(it) } >= 1 && ORDER_PLACED_PHRASES.any { text.contains(it) } || TRACKING_PHRASES.count { text.contains(it) } >= 2) {
            if (addCards == 0 && proceed == null) return BlinkitScreen.ORDER_TRACKING
        }
        if (ORDER_PLACED_PHRASES.any { text.contains(it) } && addCards == 0 && proceed == null) return BlinkitScreen.ORDER_PLACED
        if (ADDRESS_PICKER_PHRASES.count { text.contains(it) } >= 1 && addCards == 0 && proceed == null && !text.contains("search results")) {
            return BlinkitScreen.ADDRESS_PICKER
        }

        val hasContent = priceNodes > 0 || proceed != null
        if (!hasContent) {
            if (OFFLINE_PHRASES.any { text.contains(it) }) return BlinkitScreen.OFFLINE
            if (ERROR_PHRASES.any { text.contains(it) }) return BlinkitScreen.ERROR
        }

        if ((proceed != null && hasBill) || text.contains("cart is empty") || text.contains("your cart is empty") ||
            (text.contains("bill details") && cards.any { it.closer == Closer.STEPPER })
        ) return BlinkitScreen.CART
        if (text.contains("your orders") || text.contains("my orders") || text.contains("past orders") || text.contains("order history")) {
            if (addCards == 0) return BlinkitScreen.ORDERS_LIST
        }
        val productDetail = text.contains("product details") || text.contains("view product details") || text.contains("highlights") || text.contains("shelf life")
        if (productDetail && addCards <= 3 && (cards.isNotEmpty() || priceNodes > 0)) return BlinkitScreen.PRODUCT
        if (addCards >= 2 || (cards.size >= 2 && priceNodes >= 2) || text.contains("showing results") || text.contains("search results")) {
            return BlinkitScreen.SEARCH_RESULTS
        }
        if (addCards == 1 && priceNodes >= 1) return BlinkitScreen.PRODUCT
        if (text.contains("search") && (text.contains("minutes") || text.contains("mins") || text.contains("categories") || text.contains("order again") || text.contains("delivery in"))) {
            return BlinkitScreen.HOME
        }
        if (text.contains("search")) return BlinkitScreen.HOME
        return BlinkitScreen.UNKNOWN
    }

    /** What a screen needs from the user, for spoken messages. */
    fun challengeKind(nodes: List<AmazonNode>): String {
        val t = blob(nodes)
        return when {
            t.contains("captcha") || t.contains("not a robot") -> "a CAPTCHA"
            t.contains("upi pin") || t.contains("enter your pin") -> "your UPI PIN"
            t.contains("cvv") || t.contains("security code") -> "your card security code"
            t.contains("collect request") || t.contains("approve") -> "approval in your payment app"
            t.contains("otp") || t.contains("one time password") || t.contains("one-time password") || t.contains("verification code") -> "an OTP or verification code"
            else -> "a security check"
        }
    }

    // ---- Buttons and fields -------------------------------------------------------------------------

    private fun clickableMatching(nodes: List<AmazonNode>, accept: (String) -> Boolean): AmazonNode? =
        nodes.filter { n -> n.clickable && listOf(n.text, n.description).filter { it.isNotBlank() }.map { AmazonScreenReader.cleanLabel(it) }.any(accept) }
            .minByOrNull { it.top }

    fun proceedButton(nodes: List<AmazonNode>): AmazonNode? = clickableMatching(nodes) {
        Regex("""^(proceed|proceed to pay|proceed to payment|proceed to checkout|continue to pay|continue to payment)(\s*[₹\d,.].*)?$""").matches(it)
    }

    fun loginToProceedButton(nodes: List<AmazonNode>): AmazonNode? = clickableMatching(nodes) { it.startsWith("login to proceed") || it.startsWith("log in to proceed") }

    /** The button that submits the order or opens the payment app. Never matches "Proceed". */
    fun finalPayButton(nodes: List<AmazonNode>): AmazonNode? = clickableMatching(nodes) {
        Regex("""^(pay now|place order|pay and place order|pay & place order|confirm and pay|confirm & pay|pay securely|make payment|pay(?:\s*[₹]?\s*[\d,.]+)?)$""").matches(it)
    }

    fun viewCartButton(nodes: List<AmazonNode>): AmazonNode? = clickableMatching(nodes) {
        it.startsWith("view cart") || it == "cart" || it == "my cart" || it.startsWith("go to cart") || it.startsWith("view basket")
    }

    fun searchBox(nodes: List<AmazonNode>): AmazonNode? {
        nodes.filter { it.editable }.firstOrNull { it.top < 700 }?.let { return it }
        return nodes.filter { it.top in 0..600 && it.clickable && Regex("""^search\b""").containsMatchIn(it.haystack) }.minByOrNull { it.top }
    }

    fun retryButton(nodes: List<AmazonNode>): AmazonNode? = AmazonScreenReader.retryButton(nodes)
    fun dismissButton(nodes: List<AmazonNode>): AmazonNode? = AmazonScreenReader.dismissButton(nodes)

    fun cancelOrderButton(nodes: List<AmazonNode>): AmazonNode? = clickableMatching(nodes) { it == "cancel order" || it == "cancel" || it == "cancel this order" }
    fun confirmCancelButton(nodes: List<AmazonNode>): AmazonNode? = clickableMatching(nodes) {
        it == "yes, cancel" || it == "yes cancel" || it == "yes, cancel order" || it == "confirm cancellation" || it == "cancel order" || it == "yes"
    }
    fun helpButton(nodes: List<AmazonNode>): AmazonNode? = clickableMatching(nodes) {
        it == "need help" || it == "need help?" || it == "help" || it == "get help" || it == "contact support" || it == "chat with us" || it.startsWith("need help with")
    }
    fun removeButtonFor(nodes: List<AmazonNode>, card: RawCard): AmazonNode? = card.minusNode

    // ---- Product cards ------------------------------------------------------------------------------

    private val ADD_LABELS = setOf("add", "add to cart", "add item")
    private val NOTIFY_LABELS = setOf("notify me", "notify", "out of stock", "sold out", "currently unavailable", "unavailable")
    private val PLUS_LABELS = setOf("+", "increase quantity", "increase", "add one more", "plus", "increment")
    private val MINUS_LABELS = setOf("-", "−", "–", "decrease quantity", "decrease", "remove one", "minus", "decrement")
    private val OPTIONS = Regex("""^\d+\s+options?$|^select( pack| size)?$|^view options$""")
    private val ETA_RE = Regex("""\b(\d{1,2})\s*(?:-\s*\d{1,2}\s*)?(?:mins?|minutes?)\b""", RegexOption.IGNORE_CASE)
    private val NOISE_NAME = Regex(
        """^(sort|filters?|relevance|search|showing|results?|delivery in|blinkit|bestseller|best seller|sponsored|ad|new|limited|save|buy|free|only|add|off|view|see|offers?|price|deals?|categories|shop|store|back|cart)\b""",
        RegexOption.IGNORE_CASE
    )

    private fun closerOf(label: String): Closer? {
        val l = AmazonScreenReader.cleanLabel(label)
        return when {
            l in ADD_LABELS -> Closer.ADD
            l in NOTIFY_LABELS -> Closer.NOTIFY
            l in PLUS_LABELS -> Closer.STEPPER
            OPTIONS.matches(l) -> Closer.OPTIONS
            else -> null
        }
    }

    /** Splits the screen into cards in document order; each ends at its control (ADD, stepper "+", Notify me, options). */
    fun cards(nodes: List<AmazonNode>): List<RawCard> {
        val items = nodes.filter { it.label.isNotBlank() }
        val out = ArrayList<RawCard>()
        var buf = ArrayList<AmazonNode>()
        items.forEachIndexed { index, n ->
            val kind = closerOf(n.label) ?: closerOf(n.description)
            if (kind == null) { buf += n; return@forEachIndexed }
            var qty = 0
            var minus: AmazonNode? = null
            val content = ArrayList<AmazonNode>()
            for (b in buf) {
                val c = AmazonScreenReader.cleanLabel(b.label)
                when {
                    kind == Closer.STEPPER && c in MINUS_LABELS -> minus = b
                    kind == Closer.STEPPER && Regex("""^\d{1,2}$""").matches(c) -> qty = c.toInt()
                    else -> content += b
                }
            }
            if (kind == Closer.STEPPER && qty == 0) qty = 1
            out += RawCard(content, kind, n, minus, qty, index)
            buf = ArrayList()
        }
        return out
    }

    private fun isPriceOnly(label: String): Boolean = Regex("""^[₹rs.\s]*\d[\d,]*(?:\.\d{1,2})?\s*$""", RegexOption.IGNORE_CASE).matches(label.trim()) || (AmazonText.hasPrice(label) && label.trim().length <= 12)
    private fun isEta(label: String): Boolean = ETA_RE.containsMatchIn(label) && label.length <= 24
    private fun isRatingLike(label: String): Boolean = Regex("""^\d(?:\.\d)?\s*(?:★|\(|stars?|/5).*$|^\(\s*[\d.,]+\s*[km]?\s*\)$""", RegexOption.IGNORE_CASE).matches(label.trim())
    private fun isOffLabel(label: String): Boolean = Regex("""\b\d{1,2}\s*%\s*off\b|^save\s|^₹\s*\d+\s*off$""", RegexOption.IGNORE_CASE).containsMatchIn(label) && label.length <= 28

    private fun nameLike(n: AmazonNode): Boolean {
        val l = n.label.trim()
        if (l.count { it.isLetter() } < 4) return false
        if (isPriceOnly(l) || isEta(l) || isRatingLike(l) || isOffLabel(l) || PackParser.looksLikePackLabel(l)) return false
        if (NOISE_NAME.containsMatchIn(l) && l.length < 28) return false
        if (closerOf(l) != null) return false
        return true
    }

    /** Name part of a node that holds a whole card ("Amul Milk 500 ml, ₹29, MRP ₹30 ..."). */
    private fun nameFromSelfContained(label: String): String {
        val cut = listOf(label.indexOf('₹'), Regex("""\bmrp\b""", RegexOption.IGNORE_CASE).find(label)?.range?.first ?: -1)
            .filter { it > 4 }.minOrNull()
        val head = if (cut != null) label.substring(0, cut) else label
        return head.trim().trim(',', '.', '-', '|', ';', ':').take(120)
    }

    private val AVAILABILITY_BAD = Regex("""out of stock|sold out|currently unavailable|notify me|unavailable""", RegexOption.IGNORE_CASE)
    private val OFFER_RE = Regex("""(?:buy \d+ get \d+|save\s*₹\s*\d+|extra \d+%|combo|bundle|free\s+\w+|coupon)[^|;]{0,28}""", RegexOption.IGNORE_CASE)

    /** Reads rating and count from "4.5 (12.3k)", "4.5 ★ 1,204" or "4.3 out of 5". */
    fun ratingOf(text: String): Pair<Double?, Int?> {
        val m = Regex("""(\d(?:\.\d)?)\s*(?:★|stars?)?\s*\(\s*([\d.,]+\s*[kKmM]?)\s*\)""").find(text)
            ?: Regex("""(\d(?:\.\d)?)\s*(?:★|stars?)\s*([\d.,]+\s*[kKmM]?)?""").find(text)
            ?: Regex("""(\d(?:\.\d)?)\s*out of\s*5""").find(text)
        val r = m?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it in 1.0..5.0 }
        val countRaw = m?.groupValues?.getOrNull(2)?.trim().orEmpty()
        var count: Int? = null
        if (countRaw.isNotEmpty()) {
            val t = countRaw.replace(",", "").lowercase(Locale.ROOT)
            val mult = when (t.lastOrNull()) { 'k' -> 1_000.0; 'm' -> 1_000_000.0; else -> 1.0 }
            count = t.trimEnd('k', 'm').trim().toDoubleOrNull()?.let { (it * mult).toInt() }
        }
        return r to count
    }

    /** Current price, MRP and discount percent from the prices on a card. */
    fun priceFields(card: RawCard): Triple<Double?, Double?, Int?> {
        val text = card.text
        val strike = ArrayList<Double>()
        val plain = ArrayList<Double>()
        for (n in card.nodes) {
            val points = AmazonText.prices(n.label + " " + n.description)
            if (points.isEmpty()) continue
            val marked = Regex("""\bmrp\b|strike|original|was\b|list price""", RegexOption.IGNORE_CASE).containsMatchIn(n.label + " " + n.description)
            points.forEach { if (marked && points.size == 1) strike += it.value else plain += it.value }
            if (marked && points.size > 1) { plain.remove(points.first().value); strike += points.last().value; plain += points.first().value }
        }
        val distinct = (plain + strike).distinct()
        var current: Double? = plain.firstOrNull { it !in strike } ?: plain.firstOrNull()
        var mrp: Double? = strike.firstOrNull()
        if (mrp == null && distinct.size >= 2) {
            val lo = distinct.minOrNull()!!; val hi = distinct.maxOrNull()!!
            // Two different prices on a grocery card: the smaller is what you pay, the larger is the crossed-out MRP.
            if (current == null || current == lo) { current = lo; mrp = hi }
        }
        var discount = Regex("""(\d{1,2})\s*%\s*off""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull()
        if (discount == null && current != null && mrp != null && mrp > current) discount = (((mrp - current) / mrp) * 100).toInt()
        return Triple(current, mrp, discount)
    }

    /** Turns a raw card into a product. [allowNameless] is for variant rows that show only a pack size and a price. */
    fun toProduct(card: RawCard, position: Int = 0, allowNameless: Boolean = false): BlinkitProduct? {
        val (price, mrp, discount) = priceFields(card)
        if (price == null) return null
        val priceIdx = card.nodes.indexOfFirst { AmazonText.hasPrice(it.label + " " + it.description) }

        var name: String? = null
        val selfContained = card.nodes.firstOrNull { it.label.length >= 25 && AmazonText.hasPrice(it.label) && it.label.count { c -> c.isLetter() } >= 10 }
        if (selfContained != null) name = nameFromSelfContained(selfContained.label).takeIf { it.length >= 4 }
        if (name == null) {
            val before = card.nodes.take(if (priceIdx >= 0) priceIdx else card.nodes.size).filter { nameLike(it) }
            name = (before.lastOrNull() ?: card.nodes.firstOrNull { nameLike(it) })?.label?.trim()
        }
        val packNode = card.nodes.firstOrNull { PackParser.looksLikePackLabel(it.label) }
        var packText = packNode?.label?.trim()
        var pack = PackParser.parse(packText)
        if (pack == null && name != null) {
            PackParser.parse(name)?.let { pack = it; packText = it.label }
        }
        if (name.isNullOrBlank()) {
            if (!allowNameless || packText == null) return null
            name = packText
        }
        val (rating, count) = ratingOf(card.text)
        val eta = card.nodes.map { it.label }.firstOrNull { isEta(it) }?.trim()
        val available = card.closer != Closer.NOTIFY && !AVAILABILITY_BAD.containsMatchIn(card.text)
        return BlinkitProduct(
            name = name.take(120),
            price = price,
            mrp = mrp?.takeIf { it > price },
            discountPercent = discount,
            packText = packText,
            pack = pack,
            rating = rating,
            ratingCount = count,
            etaText = eta,
            available = available,
            inCartQty = if (card.closer == Closer.STEPPER) card.qty else 0,
            hasOptions = card.closer == Closer.OPTIONS,
            offers = OFFER_RE.findAll(card.text).map { it.value.trim() }.distinct().take(2).toList(),
            position = position
        )
    }

    /** Products on a search / category / product screen. Duplicates (same name, pack and price) are merged. */
    fun extractProducts(nodes: List<AmazonNode>): List<BlinkitProduct> {
        val out = ArrayList<BlinkitProduct>()
        cards(nodes).filter { it.closer != Closer.NONE }.forEach { c ->
            val p = toProduct(c, out.size) ?: return@forEach
            if (out.none { it.key == p.key }) out += p
        }
        return out.mapIndexed { i, p -> p.copy(position = i) }
    }

    /** The card for [product] on the current screen, matched by name and (when known) pack and price. */
    fun locateCard(nodes: List<AmazonNode>, product: BlinkitProduct): RawCard? {
        val candidates = cards(nodes).filter { it.closer != Closer.NONE }.mapNotNull { c -> toProduct(c)?.let { c to it } }
        val matches = candidates.filter { (_, p) ->
            TitleMatch.containment(p.name, product.name) >= 0.7 &&
                (product.pack == null || p.pack == null || p.pack.baseAmount == product.pack.baseAmount) &&
                (product.price == null || p.price == null || p.price == product.price)
        }
        return matches.firstOrNull()?.first
    }

    /** Variant rows shown after tapping "N options": each is a pack size with its own price and ADD button. */
    fun variantRows(nodes: List<AmazonNode>): List<Pair<BlinkitProduct, RawCard>> =
        cards(nodes).filter { it.closer == Closer.ADD || it.closer == Closer.STEPPER }.mapNotNull { c ->
            toProduct(c, 0, allowNameless = true)?.let { it to c }
        }

    // ---- Cart and bill ------------------------------------------------------------------------------

    private fun lines(nodes: List<AmazonNode>): List<String> {
        val out = ArrayList<String>()
        for (n in nodes.filter { it.label.isNotBlank() }) if (out.lastOrNull() != n.label) out += n.label
        return out
    }

    /** First price on the line matching [label], or on one of the next two lines. "FREE" counts as 0. */
    private fun amountAfter(ls: List<String>, label: Regex, rejectLabel: Regex? = null): Double? {
        for (i in ls.indices) {
            val lower = ls[i].lowercase(Locale.ROOT)
            if (!label.containsMatchIn(lower)) continue
            if (rejectLabel != null && rejectLabel.containsMatchIn(lower)) continue
            for (j in i..minOf(i + 2, ls.lastIndex)) {
                val t = if (j == i) lower.substring(label.find(lower)!!.range.last + 1) else ls[j].lowercase(Locale.ROOT)
                if (j > i && label.containsMatchIn(t)) break
                AmazonText.prices(t).firstOrNull()?.let { return it.value }
                if (Regex("""\bfree\b""").containsMatchIn(t)) return 0.0
            }
        }
        return null
    }

    private val FEE_LABEL = Regex(
        """^(delivery (?:charge|fee)s?|handling (?:charge|fee)s?|small cart (?:charge|fee)s?|surge (?:charge|fee)s?|platform fee|convenience fee|packaging (?:charge|fee)|rain (?:charge|fee)s?|late night (?:charge|fee)s?|smart cart (?:charge|fee)|service (?:charge|fee)|other charges)\b"""
    )
    private val DISCOUNT_LABEL = Regex("""^(coupon(?: discount)?|discount|cashback|offer discount|promo(?:code)?(?: discount)?)\b""")

    fun extractBill(nodes: List<AmazonNode>): BillDetails {
        val ls = lines(nodes)
        val fees = ArrayList<Pair<String, Double>>()
        val discounts = ArrayList<Pair<String, Double>>()
        for (i in ls.indices) {
            val lower = ls[i].lowercase(Locale.ROOT).trim()
            val isFee = FEE_LABEL.containsMatchIn(lower)
            val isDiscount = DISCOUNT_LABEL.containsMatchIn(lower)
            if (!isFee && !isDiscount) continue
            var amount: Double? = AmazonText.prices(lower).firstOrNull()?.value
            if (amount == null && Regex("""\bfree\b""").containsMatchIn(lower)) amount = 0.0
            if (amount == null) {
                for (j in i + 1..minOf(i + 2, ls.lastIndex)) {
                    val t = ls[j].lowercase(Locale.ROOT)
                    if (FEE_LABEL.containsMatchIn(t) || DISCOUNT_LABEL.containsMatchIn(t)) break
                    amount = AmazonText.prices(t).firstOrNull()?.value ?: if (Regex("""^\W*free\W*$""").matches(t)) 0.0 else null
                    if (amount != null) break
                }
            }
            if (amount == null) continue
            val name = lower.substringBefore('₹').trim().trim(':', '-', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }
            val pair = name to amount
            if (isFee) { if (fees.none { it.first == name }) fees += pair } else if (discounts.none { it.first == name }) discounts += pair
        }
        val itemTotal = amountAfter(ls, Regex("""item total|items total|cart total|subtotal|sub total|items?\s*\(\d+\)"""))
        val total = amountAfter(ls, Regex("""grand total|to pay|total payable|amount to pay|bill total|you pay|total amount"""))
        val joined = ls.joinToString(" | ").lowercase(Locale.ROOT)
        val freeGap = Regex("""₹\s*([\d,.]+)\s*more[^|]{0,40}free delivery|free delivery[^|]{0,40}(?:add|shop)[^₹|]{0,24}₹\s*([\d,.]+)""").find(joined)?.let { m ->
            AmazonText.parseAmount(m.groupValues[1].ifBlank { m.groupValues[2] })
        }
        var minGap = Regex("""(?:add|shop(?: for)?|order)[^₹|]{0,32}₹\s*([\d,.]+)\s*more[^|]{0,48}(?:minimum|min\.? order|place (?:an )?order|checkout|proceed)""")
            .find(joined)?.let { AmazonText.parseAmount(it.groupValues[1]) }
        if (minGap == null) {
            val minValue = Regex("""minimum (?:order|cart)(?: value)?[^₹|\d]{0,24}₹\s*([\d,.]+)""").find(joined)?.let { AmazonText.parseAmount(it.groupValues[1]) }
            if (minValue != null && itemTotal != null && itemTotal < minValue) minGap = minValue - itemTotal
        }
        return BillDetails(itemTotal, fees, discounts, total, minGap, freeGap)
    }

    /** Cart rows (cards closed by a stepper, plus unavailable rows) that sit above the bill; "you might also like" cards are ignored. */
    fun cartLines(nodes: List<AmazonNode>): List<CartLine> {
        val labeled = nodes.filter { it.label.isNotBlank() }
        val billIdx = labeled.indexOfFirst { it.haystack.contains("bill details") }
        val out = ArrayList<CartLine>()
        for (c in cards(nodes)) {
            val inCartArea = billIdx < 0 || c.endIndex < billIdx
            val unavailableRow = c.closer == Closer.NOTIFY && billIdx >= 0 && inCartArea
            if (!(c.closer == Closer.STEPPER && inCartArea) && !unavailableRow) continue
            val p = toProduct(c, allowNameless = false) ?: run {
                // Unavailable rows may show no price: still report the name.
                val n = c.nodes.firstOrNull { nameLike(it) }?.label?.trim() ?: return@run null
                BlinkitProduct(name = n, price = null, available = false)
            } ?: continue
            if (p.price == null && !unavailableRow) continue
            val line = CartLine(p.name, p.packText, p.price, if (c.closer == Closer.STEPPER) c.qty.coerceAtLeast(1) else 0, unavailable = unavailableRow || !p.available)
            if (out.none { it.key == line.key }) out += line
        }
        return out
    }

    fun extractCart(nodes: List<AmazonNode>): CartSnapshot {
        val text = blob(nodes)
        val lines = cartLines(nodes)
        val empty = lines.isEmpty() && (text.contains("cart is empty") || text.contains("your cart is empty") || text.contains("nothing in your cart"))
        return CartSnapshot(lines, extractBill(nodes), empty, address(nodes), eta(nodes))
    }

    /** Delivery estimate such as "Delivery in 8 minutes". */
    fun eta(nodes: List<AmazonNode>): String? =
        lines(nodes).firstOrNull { Regex("""^(?:delivery|delivering|blinkit)? ?(?:in|within) \d{1,2}\s*(?:-\s*\d{1,2}\s*)?(?:mins?|minutes?)""", RegexOption.IGNORE_CASE).containsMatchIn(it.trim()) }?.trim()?.take(60)

    /** The payment screen's summary: items carried from the cart read, plus the total, address and payment line on screen now. */
    fun extractPayment(nodes: List<AmazonNode>, carried: List<CartLine>): CheckoutSummary {
        val ls = lines(nodes)
        val paymentWords = Regex("""\bupi\b|google pay|gpay|phonepe|paytm|credit card|debit card|net ?banking|wallet|cash on delivery|pay on delivery|\bemi\b|simpl|amazon pay|bhim""")
        var payment: String? = null
        val label = ls.indexOfFirst { Regex("""paying (?:with|via|using)|pay using|pay with|payment method|selected payment""").containsMatchIn(it.lowercase(Locale.ROOT)) }
        if (label >= 0) {
            val same = Regex("""(?i)\b(?:paying (?:with|via|using)|pay using|pay with|payment method|selected payment)\b""")
                .replaceFirst(ls[label], "").trim().trimStart(':', '-', ' ')
            payment = same.takeIf { it.isNotBlank() && paymentWords.containsMatchIn(it.lowercase(Locale.ROOT)) }
                ?: ls.drop(label + 1).take(3).firstOrNull { paymentWords.containsMatchIn(it.lowercase(Locale.ROOT)) }
        }
        if (payment == null) payment = ls.firstOrNull { paymentWords.containsMatchIn(it.lowercase(Locale.ROOT)) && it.length < 60 }
        return CheckoutSummary(carried, extractBill(nodes), address(nodes), payment?.take(60), eta(nodes))
    }

    // ---- Address ------------------------------------------------------------------------------------

    private val ADDRESS_TAGS = setOf("home", "work", "other", "hotel", "friends and family", "friends & family", "office")

    fun address(nodes: List<AmazonNode>): String? {
        val ls = lines(nodes)
        ls.indexOfFirst { Regex("""^(delivering to|deliver to|delivery address|delivering at)\b""", RegexOption.IGNORE_CASE).containsMatchIn(it.trim()) }.takeIf { it >= 0 }?.let { i ->
            val line = ls[i].trim()
            val rest = line.replace(Regex("""^(delivering to|deliver to|delivery address|delivering at)\s*:?\s*""", RegexOption.IGNORE_CASE), "")
            return (if (rest.length >= 6) rest else ls.getOrNull(i + 1)?.let { (rest + " " + it).trim() } ?: rest).take(140).takeIf { it.isNotBlank() }
        }
        val etaIdx = ls.indexOfFirst { Regex("""^(?:delivery|blinkit)?\s*in \d{1,2}\s*(?:mins?|minutes?)""", RegexOption.IGNORE_CASE).containsMatchIn(it.trim()) }
        if (etaIdx >= 0) ls.getOrNull(etaIdx + 1)?.let { if (it.length >= 6 && !closerLike(it)) return it.take(140) }
        ls.firstOrNull { Regex("""^(home|work|other|office)\s*[-–:,]\s*\S+""", RegexOption.IGNORE_CASE).containsMatchIn(it.trim()) }?.let { return it.take(140) }
        return null
    }

    private fun closerLike(label: String) = closerOf(label) != null

    /** Saved addresses on the address picker, each with the node to tap. */
    fun savedAddresses(nodes: List<AmazonNode>): List<AddressEntry> {
        val items = nodes.filter { it.label.isNotBlank() }
        val start = items.indexOfFirst { it.haystack.contains("saved addresses") }.let { if (it < 0) 0 else it + 1 }
        val out = ArrayList<AddressEntry>()
        var label = ""
        var text = StringBuilder()
        var target: AmazonNode? = null
        fun flush() {
            if (text.isNotBlank() || label.isNotBlank()) out += AddressEntry(label, text.toString().trim().take(140), target)
            label = ""; text = StringBuilder(); target = null
        }
        for (n in items.drop(start)) {
            val l = n.label.trim()
            val low = AmazonScreenReader.cleanLabel(l)
            if (low.startsWith("add new") || low.startsWith("use current") || low.contains("search for") || low == "recently used" || low.startsWith("more")) { flush(); if (low.startsWith("add new") || low.startsWith("use current")) continue else break }
            if (low in ADDRESS_TAGS) { flush(); label = l; if (n.clickable) target = n; continue }
            if (l.length >= 8 && (l.contains(',') || l.any { it.isDigit() })) {
                if (text.isEmpty() && label.isEmpty() && out.isNotEmpty() && target != null) flush()
                if (text.length < 120) { if (text.isNotEmpty()) text.append(' '); text.append(l) }
                if (target == null && n.clickable) target = n
            }
        }
        flush()
        return out.filter { it.text.isNotBlank() }
    }

    // ---- Order tracking -----------------------------------------------------------------------------

    private fun has(t: String, vararg phrases: String) = phrases.any { t.contains(it) }

    fun orderStatus(nodes: List<AmazonNode>): OrderStatus {
        val t = blob(nodes)
        val tracking = has(t, "arriving in", "on the way", "preparing your order", "packing your order", "being packed", "assigned", "out for delivery", "picked up")
        val stage = when {
            has(t, "order cancelled", "order canceled", "has been cancelled", "was cancelled", "cancelled by") && !tracking -> OrderStage.CANCELLED
            has(t, "order delivered", "delivered successfully", "delivered in", "has been delivered", "was delivered", "delivered on") && !tracking -> OrderStage.DELIVERED
            has(t, "has arrived", "arrived at your", "at your doorstep", "reached your location") -> OrderStage.ARRIVED
            has(t, "is on the way", "on the way to you", "out for delivery", "arriving in", "heading to you", "picked up your order") -> OrderStage.ON_THE_WAY
            has(t, "partner assigned", "delivery partner assigned", "assigned to your order", "is assigned", "on the way to the store", "heading to the store", "will pick up") -> OrderStage.PARTNER_ASSIGNED
            has(t, "preparing your order", "packing your order", "being packed", "is being prepared", "order is being packed", "packed") -> OrderStage.PREPARING
            has(t, "order placed", "order confirmed", "order received", "your order has been placed") -> OrderStage.PLACED
            else -> OrderStage.UNKNOWN
        }
        val ls = lines(nodes)
        val eta = ls.firstNotNullOfOrNull { l ->
            Regex("""(?:arriving|arrives|delivery|reaching|delivered)\s*(?:in|by|within)\s*(\d{1,2}\s*(?:-\s*\d{1,2}\s*)?(?:mins?|minutes?))""", RegexOption.IGNORE_CASE).find(l)?.groupValues?.get(1)
        }
        val partner = ls.firstNotNullOfOrNull { l ->
            Regex("""^([A-Z][a-z]{2,}(?: [A-Z][a-z]{2,})?) (?:is|will be) (?:on the way|picking|delivering|your delivery partner|assigned)""").find(l.trim())?.groupValues?.get(1)
        }
        val delay = ls.firstOrNull { Regex("""delay|running late|taking longer|longer than usual|bit late|sorry for the wait""", RegexOption.IGNORE_CASE).containsMatchIn(it) }?.take(120)
        val itemNotices = ls.filter { Regex("""unavailable|out of stock|replaced|substitut|missing|removed from your order|could not be delivered""", RegexOption.IGNORE_CASE).containsMatchIn(it) }.map { it.take(120) }.distinct().take(4)
        return OrderStatus(
            stage = stage,
            etaText = eta,
            partnerName = partner,
            delayNotice = delay,
            itemNotices = itemNotices,
            cancelOffered = cancelOrderButton(nodes) != null,
            helpOffered = helpButton(nodes) != null,
            refundMentioned = t.contains("refund")
        )
    }

    /** Short summaries of orders shown on the orders list (up to [limit]). Each block ends at its Reorder / View details button. */
    fun orderSummaries(nodes: List<AmazonNode>, limit: Int = 5): List<String> {
        val ends = setOf("reorder", "order again", "view details", "view order", "track order", "need help", "rate order")
        val out = ArrayList<String>()
        val buf = ArrayList<String>()
        for (n in nodes.filter { it.label.isNotBlank() }) {
            val l = AmazonScreenReader.cleanLabel(n.label)
            if (l in ends) {
                if (buf.any { AmazonText.hasPrice(it) }) out += buf.take(6).joinToString(" · ").take(170)
                buf.clear()
                if (out.size >= limit) break
            } else buf += n.label.trim()
        }
        return out
    }

    // ---- Misc ---------------------------------------------------------------------------------------

    /** The product page or card says the item cannot be bought right now. */
    fun isUnavailable(nodes: List<AmazonNode>): Boolean {
        val t = blob(nodes)
        return cards(nodes).none { it.closer == Closer.ADD || it.closer == Closer.STEPPER } && UNAVAILABLE_PHRASES.any { t.contains(it) }
    }
}
