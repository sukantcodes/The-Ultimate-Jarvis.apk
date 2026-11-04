package com.jarvis.assistant.automation.amazon

import java.util.Locale

/** A price found inside text. */
data class PricePoint(val value: Double, val start: Int, val end: Int)

/** Small text readers for the way Amazon words prices, ratings and delivery. */
object AmazonText {
    private val PRICE = Regex("""(?:₹|[$]|(?<![a-z])rs\.?|(?<![a-z])inr)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""", RegexOption.IGNORE_CASE)
    private val RATING_OUT_OF = Regex("""(\d(?:\.\d)?)\s*out of\s*5""", RegexOption.IGNORE_CASE)
    private val RATING_STAR = Regex("""(\d\.\d)\s*(?:stars?|★)""", RegexOption.IGNORE_CASE)
    private val COUNT_AFTER_RATING = Regex("""out of\s*5(?:\s*stars?)?[\s,.;:|()-]*([0-9][0-9,]*(?:\.[0-9])?\s*[kKmM]?)(?![0-9])""", RegexOption.IGNORE_CASE)
    private val COUNT_LABEL = Regex("""([0-9][0-9,]*(?:\.[0-9])?\s*[kKmM]?)\s*(?:global\s+)?(?:ratings?|reviews?)""", RegexOption.IGNORE_CASE)
    private val MRP_BEFORE = Regex("""(m\.?r\.?p\.?|list price|was|typical price|original price)[\s:|]*$""")
    private val DISCOUNT = Regex("""(\d{1,2})\s*%\s*off""", RegexOption.IGNORE_CASE)
    private val DELIVERY = Regex(
        """(?:free delivery|free shipping|get it by|get it (?:tomorrow|today)|delivery (?:by|on)|arrives?|arriving|delivered by)[^|;.\n]{0,60}""",
        RegexOption.IGNORE_CASE
    )
    // Amounts must sit in the same element as the word "delivery" (no "|" between), so a price that merely
    // comes before the word, or the next element's price, is never read as a fee.
    private val DELIVERY_FEE_C = Regex("""(?:delivery|shipping)[ \t:]*(?:charges?|fee)?[ \t:]*(?:₹|(?<![a-z])rs\.?)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""", RegexOption.IGNORE_CASE)
    private val DELIVERY_FEE_B = Regex("""(?:₹|(?<![a-z])rs\.?)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)[ \t]*(?:delivery|shipping)(?![ \t]*(?:by|on|charges|fee))""", RegexOption.IGNORE_CASE)
    private val OFFER = Regex(
        """(?:coupon|save extra|extra \d+% off|bank offer|no cost emi|cashback|limited time deal|deal of the day)[^|;.\n]{0,50}""",
        RegexOption.IGNORE_CASE
    )

    fun parseAmount(raw: String): Double? = raw.replace(",", "").trim().toDoubleOrNull()

    fun prices(text: String): List<PricePoint> =
        PRICE.findAll(text).mapNotNull { m ->
            parseAmount(m.groupValues[1])?.let { PricePoint(it, m.range.first, m.range.last + 1) }
        }.toList()

    fun hasPrice(text: String): Boolean = PRICE.containsMatchIn(text)

    fun rating(text: String): Double? =
        (RATING_OUT_OF.find(text) ?: RATING_STAR.find(text))?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it in 0.0..5.0 }

    fun ratingCount(text: String): Int? {
        val raw = (COUNT_AFTER_RATING.find(text) ?: COUNT_LABEL.find(text))?.groupValues?.get(1) ?: return null
        val t = raw.trim().replace(",", "").lowercase(Locale.ROOT)
        val mult = when (t.lastOrNull()) {
            'k' -> 1_000.0
            'm' -> 1_000_000.0
            else -> 1.0
        }
        val num = t.trimEnd('k', 'm').trim().toDoubleOrNull() ?: return null
        return (num * mult).toInt()
    }

    fun isPrime(text: String): Boolean = Regex("""\bprime\b(?!\s*video)""", RegexOption.IGNORE_CASE).containsMatchIn(text)
    fun isSponsored(text: String): Boolean = Regex("""\bsponsored\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)

    /** Current price, MRP and discount percent. */
    fun priceFields(text: String): Triple<Double?, Double?, Int?> {
        val points = prices(text)
        val lower = text.lowercase(Locale.ROOT)
        var current: Double? = null
        var mrp: Double? = null
        for (p in points) {
            val before = lower.substring(maxOf(0, p.start - 16), p.start)
            if (MRP_BEFORE.containsMatchIn(before)) {
                if (mrp == null) mrp = p.value
            } else if (current == null) {
                current = p.value
            }
        }
        if (mrp == null && current != null) {
            val distinct = points.map { it.value }.distinct()
            if (distinct.size == 2 && distinct.max() > current) mrp = distinct.max()
        }
        var discount = DISCOUNT.find(text)?.groupValues?.get(1)?.toIntOrNull()
        if (discount == null && current != null && mrp != null && mrp > current) {
            discount = (((mrp - current) / mrp) * 100).toInt()
        }
        return Triple(current, mrp, discount)
    }

    fun delivery(text: String, itemPrice: Double? = null): Triple<String?, Boolean?, Double?> {
        val note = DELIVERY.find(text)?.value?.trim()
        fun fee(r: Regex): Double? = r.findAll(text).mapNotNull { parseAmount(it.groupValues[1]) }
            .firstOrNull { itemPrice == null || it != itemPrice }
        val amount = fee(DELIVERY_FEE_C) ?: fee(DELIVERY_FEE_B)
        val freeSaid = Regex("""\bfree (?:delivery|shipping)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)
        return when {
            freeSaid && amount != null -> Triple(note, null, amount)   // "free over X, otherwise a fee": not clear, so not claimed
            freeSaid -> Triple(note, true, null)
            amount != null -> Triple(note, false, amount)
            else -> Triple(note, null, null)
        }
    }

    fun offers(text: String): List<String> =
        OFFER.findAll(text).map { it.value.trim() }.distinct().take(3).toList()
}

/** A group of nodes that belong to one product card (a search result, a cart row, a checkout row). */
data class ScreenBlock(val title: String, val text: String, val nodes: List<AmazonNode>, val top: Int, val bottom: Int)

/**
 * Reads Amazon screens from a list of accessibility nodes: which screen it is, the products on it, the cart,
 * the checkout summary, and where the buttons are.
 *
 * Every matching rule is label based (never pixel based), so different screen sizes and layouts keep working.
 * The labels are English; the Amazon app in another language is reported as an unknown screen instead of
 * being guessed at.
 */
object AmazonScreenReader {

    // ---- Screen classification ----------------------------------------------------------------

    private val CHALLENGE_PHRASES = listOf(
        "enter otp", "enter the otp", "one time password", "one-time password", "otp sent", "otp has been sent",
        "verification code", "enter the code", "captcha", "type the characters", "enter the characters you see",
        "solve this puzzle", "solve the puzzle", "not a robot", "verify it's you", "verify it’s you",
        "verify your identity", "two-step verification", "two step verification", "approve the notification",
        "authentication required", "enter upi pin", "upi pin", "enter your pin", "enter cvv", "security code",
        "3d secure", "authorise payment", "authorize payment"
    )
    private val SIGN_IN_STRONG = listOf(
        "sign in or create account", "enter mobile number or email", "email or mobile phone number",
        "mobile number or email", "enter your password", "enter password", "forgot password", "sign in to your account"
    )
    private val OFFLINE_PHRASES = listOf(
        "no internet", "you're offline", "you are offline", "check your internet", "check your connection",
        "no network", "network error", "unable to connect", "connection lost", "internet connection"
    )
    private val ERROR_PHRASES = listOf(
        "something went wrong", "went wrong", "try again", "oops", "we're sorry", "we are sorry", "page not found",
        "service unavailable", "temporarily unavailable", "could not load", "couldn't load", "unable to load"
    )
    private val ORDER_PLACED_PHRASES = listOf(
        "order placed", "your order has been placed", "order has been placed", "order confirmed",
        "your order is confirmed", "thank you, your order", "thanks for your order", "thank you for your order"
    )
    private val CHECKOUT_PHRASES = listOf(
        "select a delivery address", "choose a delivery address", "select delivery address", "deliver to this address",
        "use this address", "select a payment method", "choose a payment method", "use this payment method",
        "select a delivery option", "choose your delivery option", "choose a delivery option"
    )
    private val UNAVAILABLE_PHRASES = listOf("currently unavailable", "out of stock", "temporarily out of stock")

    fun cleanLabel(raw: String): String =
        raw.lowercase(Locale.ROOT).replace(Regex(""",?\s*(button|link|double tap to activate.*)$"""), "").trim().trimEnd('.', ':')

    private fun labelsOf(n: AmazonNode): List<String> =
        listOf(n.text, n.description).filter { it.isNotBlank() }.map { cleanLabel(it) }

    fun classify(nodes: List<AmazonNode>): AmazonScreen {
        val visible = nodes.filter { it.label.isNotBlank() || it.editable }
        val labeled = visible.count { it.label.isNotBlank() }
        val blob = visible.joinToString(" \n ") { it.haystack }
        val priceNodes = visible.count { AmazonText.hasPrice(it.haystack) }
        val addToCart = addToCartButton(nodes)
        val buyNow = findByLabel(nodes) { it.startsWith("buy now") } != null
        val placeOrder = placeOrderButton(nodes)
        val proceed = proceedToBuyButton(nodes)

        if (labeled < 3 && !blob.contains("search")) return AmazonScreen.LOADING
        if (CHALLENGE_PHRASES.any { blob.contains(it) }) return AmazonScreen.CHALLENGE

        val hasEditable = visible.any { it.editable }
        if (SIGN_IN_STRONG.any { blob.contains(it) } && addToCart == null && proceed == null) return AmazonScreen.SIGN_IN
        if (hasEditable && (blob.contains("sign in") || blob.contains("sign-in") || blob.contains("log in")) &&
            (blob.contains("password") || blob.contains("mobile") || blob.contains("email")) &&
            addToCart == null && proceed == null && priceNodes == 0
        ) return AmazonScreen.SIGN_IN

        if (placeOrder == null && ORDER_PLACED_PHRASES.any { blob.contains(it) }) return AmazonScreen.ORDER_PLACED
        if (placeOrder != null) return AmazonScreen.ORDER_REVIEW
        if (CHECKOUT_PHRASES.any { blob.contains(it) }) return AmazonScreen.CHECKOUT

        val hasContent = priceNodes > 0 || addToCart != null || proceed != null
        if (!hasContent) {
            if (OFFLINE_PHRASES.any { blob.contains(it) }) return AmazonScreen.OFFLINE
            if (ERROR_PHRASES.any { blob.contains(it) }) return AmazonScreen.ERROR
        }

        if (proceed != null || blob.contains("cart is empty") || blob.contains("shopping cart") && addToCart == null) {
            return AmazonScreen.CART
        }
        if (addToCart != null || buyNow || (UNAVAILABLE_PHRASES.any { blob.contains(it) } &&
                (blob.contains("about this item") || blob.contains("product details") || blob.contains("sold by")))
        ) return AmazonScreen.PRODUCT

        if (priceNodes >= 3 || (priceNodes >= 2 && (blob.contains("sort") || blob.contains("filter") || blob.contains("results")))) {
            return AmazonScreen.SEARCH_RESULTS
        }
        if (blob.contains("search amazon") || blob.contains("search products") || blob.contains("search for products") ||
            blob.contains("hello,") || blob.contains("deals")
        ) return AmazonScreen.HOME
        return AmazonScreen.UNKNOWN
    }

    /** True when the product page says the item cannot be bought right now. */
    fun isUnavailable(nodes: List<AmazonNode>): Boolean {
        val blob = nodes.joinToString(" ") { it.haystack }
        return addToCartButton(nodes) == null && UNAVAILABLE_PHRASES.any { blob.contains(it) }
    }

    /** "Added to cart" style confirmation after pressing the button. */
    fun addedToCartVisible(nodes: List<AmazonNode>): Boolean {
        val blob = nodes.joinToString(" ") { it.haystack }
        return blob.contains("added to cart") || blob.contains("added to basket") || blob.contains("item added")
    }

    /** Short name for what a screen needs from the user, for spoken messages. */
    fun challengeKind(nodes: List<AmazonNode>): String {
        val blob = nodes.joinToString(" ") { it.haystack }
        return when {
            blob.contains("captcha") || blob.contains("characters you see") || blob.contains("puzzle") || blob.contains("not a robot") -> "a CAPTCHA"
            blob.contains("upi pin") || blob.contains("enter your pin") -> "your UPI PIN"
            blob.contains("cvv") || blob.contains("security code") -> "your card security code"
            blob.contains("otp") || blob.contains("one time password") || blob.contains("one-time password") || blob.contains("verification code") -> "an OTP or verification code"
            else -> "a security check"
        }
    }

    // ---- Button and field finders -------------------------------------------------------------

    /** First node (clickable ones first) with a cleaned label accepted by [accept] and not by [reject]. */
    fun findByLabel(
        nodes: List<AmazonNode>,
        reject: (String) -> Boolean = { false },
        accept: (String) -> Boolean
    ): AmazonNode? {
        val matches = nodes.filter { n -> labelsOf(n).any { l -> accept(l) && !reject(l) } }
        return matches.firstOrNull { it.clickable } ?: matches.firstOrNull()
    }

    fun findAllByLabel(nodes: List<AmazonNode>, accept: (String) -> Boolean): List<AmazonNode> =
        nodes.filter { n -> labelsOf(n).any(accept) }.sortedWith(compareBy({ it.top }, { it.left }))

    fun addToCartButton(nodes: List<AmazonNode>): AmazonNode? =
        findByLabel(nodes, reject = { it.contains("buy now") || it.contains("list") || it.contains("wish") }) {
            Regex("""^add to (cart|basket)( \(.*\))?$""").matches(it)
        }

    fun proceedToBuyButton(nodes: List<AmazonNode>): AmazonNode? =
        findByLabel(nodes) { Regex("""^proceed to (buy|checkout)\b.*$""").matches(it) }

    fun placeOrderButton(nodes: List<AmazonNode>): AmazonNode? =
        findByLabel(nodes) { Regex("""^place (your )?order( and pay)?$""").matches(it) }

    fun retryButton(nodes: List<AmazonNode>): AmazonNode? =
        findByLabel(nodes) { it == "try again" || it == "retry" || it == "reload" || it == "refresh" }

    private val DISMISS = setOf("no thanks", "no, thanks", "not now", "maybe later", "skip", "close", "dismiss", "later")

    /** A harmless pop-up button such as "No thanks" or "Not now". Never matches anything that buys or confirms. */
    fun dismissButton(nodes: List<AmazonNode>): AmazonNode? = findByLabel(nodes) { it in DISMISS }

    fun deleteButtons(nodes: List<AmazonNode>): List<AmazonNode> =
        findAllByLabel(nodes) { it == "delete" || it == "remove" || it == "remove from cart" }

    fun quantityControl(nodes: List<AmazonNode>): AmazonNode? =
        findByLabel(nodes) { Regex("""^(qty|quantity)\s*:?\s*\d*$""").matches(it) }

    fun searchBox(nodes: List<AmazonNode>): AmazonNode? {
        val editable = nodes.filter { it.editable }
        editable.firstOrNull { it.viewId.contains("search", true) || it.haystack.contains("search") }?.let { return it }
        editable.firstOrNull { it.top < 500 }?.let { return it }
        return nodes.filter { it.top in 0..450 && (it.viewId.contains("search", true) || it.haystack.contains("search amazon")) }
            .let { found -> found.firstOrNull { it.clickable } ?: found.firstOrNull() }
    }

    /** The button to use for a variant such as "Black", "128 GB" or "size 9". */
    fun variantNode(nodes: List<AmazonNode>, variant: String): AmazonNode? {
        val want = variantKey(variant)
        if (want.isEmpty()) return null
        val matches = nodes.filter { n ->
            n.clickable && n.label.length in 1..48 && labelsOf(n).any { l ->
                val k = variantKey(l)
                k == want || k.contains(want) && l.length <= want.length + 14
            }
        }
        return matches.firstOrNull()
    }

    fun isVariantSelected(nodes: List<AmazonNode>, variant: String): Boolean {
        val want = variantKey(variant)
        if (want.isEmpty()) return false
        return nodes.any { n ->
            val k = variantKey(n.haystack)
            val chipSelected = (n.selected || n.checked || n.haystack.contains("selected")) && k.contains(want)
            val header = Regex("""(colou?r|size|style|pattern|capacity|storage|configuration|variant)[a-z ]{0,12}:\s*[a-z0-9 .\-]{0,24}""")
                .findAll(n.haystack).any { variantKey(it.value).endsWith(want) }
            chipSelected || header
        }
    }

    private fun variantKey(s: String): String =
        s.lowercase(Locale.ROOT).replace("size", "").replace(Regex("[^a-z0-9]+"), "")

    // ---- Product, cart and checkout reading -----------------------------------------------------

    private val NOISE_PREFIX = listOf(
        "free delivery", "get it by", "bought in past", "limited time", "save ", "m.r.p", "add to", "buy now",
        "qty", "quantity", "delete", "save for later", "see more", "eligible for", "only ", "list price", "subtotal",
        "proceed", "order total", "deliver to", "delivering to", "shipping to"
    )
    private val BOUNDARY = Regex("""^(subtotal|proceed to|order total|items?:|delivery:|total:)""")

    private fun isTitleLike(n: AmazonNode): Boolean {
        val l = n.label
        if (l.length < 18) return false
        if (l.count { it.isLetter() } < 10) return false
        if (AmazonText.hasPrice(l)) return false
        val lower = l.lowercase(Locale.ROOT)
        if (NOISE_PREFIX.any { lower.startsWith(it) }) return false
        if (AmazonText.rating(l) != null && l.length < 60) return false
        return true
    }

    private fun deriveTitle(text: String): String {
        var t = text.replace(Regex("""^\s*(sponsored( ad)?\s*[-:,]?\s*)"""), "")
        val cut = listOf(
            Regex(""",?\s*\d(?:\.\d)?\s*out of\s*5"""),
            Regex("""[₹$]"""),
            Regex("""\bsponsored\b""", RegexOption.IGNORE_CASE)
        ).mapNotNull { r -> r.find(t)?.range?.first }.filter { it > 8 }.minOrNull()
        if (cut != null) t = t.substring(0, cut)
        return t.trim().trim(',', '.', '-', '|', ';').take(180)
    }

    /** Groups nodes into product-card blocks, top to bottom. */
    fun blocks(nodes: List<AmazonNode>): List<ScreenBlock> {
        val sorted = nodes.filter { it.label.isNotBlank() }.sortedWith(compareBy({ it.top }, { it.left }))
        val out = ArrayList<ScreenBlock>()

        // (a) a single node that holds a whole card in its text or description
        val selfContained = sorted.filter {
            val t = it.text.ifBlank { it.description }
            t.length >= 25 && AmazonText.hasPrice(t) && (AmazonText.rating(t) != null || t.length >= 50)
        }
        selfContained.forEach { n ->
            val t = n.label
            out += ScreenBlock(deriveTitle(t), t, listOf(n), n.top, n.bottom)
        }

        // (b) a title node followed by its detail nodes
        fun covered(n: AmazonNode): Boolean = selfContained.any {
            it !== n && it.bottom > it.top && it.top <= n.top && it.bottom >= n.bottom && it.left <= n.left && it.right >= n.right
        }
        var title: AmazonNode? = null
        val buffer = ArrayList<AmazonNode>()
        fun flush() {
            val t = title
            if (t != null && buffer.isNotEmpty()) {
                val all = listOf(t) + buffer
                val text = all.joinToString(" | ") { it.label }
                if (AmazonText.hasPrice(text)) {
                    out += ScreenBlock(t.label.take(180), text, all, all.minOf { it.top }, all.maxOf { it.bottom })
                }
            }
            title = null
            buffer.clear()
        }
        for (n in sorted) {
            if (selfContained.contains(n) || covered(n)) continue
            val lower = n.label.lowercase(Locale.ROOT)
            if (BOUNDARY.containsMatchIn(lower)) { flush(); continue }
            if (isTitleLike(n)) { flush(); title = n; continue }
            if (title != null) buffer += n
        }
        flush()
        return out.sortedBy { it.top }
    }

    private fun toProduct(block: ScreenBlock): ProductCandidate? {
        val (price, mrp, discount) = AmazonText.priceFields(block.text)
        if (price == null || block.title.length < 8) return null
        val (note, free, fee) = AmazonText.delivery(block.text, price)
        return ProductCandidate(
            title = block.title,
            price = price,
            mrp = mrp,
            discountPercent = discount,
            rating = AmazonText.rating(block.text),
            ratingCount = AmazonText.ratingCount(block.text),
            prime = AmazonText.isPrime(block.text),
            sponsored = AmazonText.isSponsored(block.text),
            deliveryText = note,
            freeDelivery = free,
            deliveryFee = fee,
            offers = AmazonText.offers(block.text)
        )
    }

    private fun duplicate(a: ProductCandidate, b: ProductCandidate): Boolean =
        a.price == b.price && (TitleMatch.sameProduct(a.title, b.title) || TitleMatch.containment(a.title, b.title) >= 0.8)

    fun extractProducts(nodes: List<AmazonNode>): List<ProductCandidate> {
        val result = ArrayList<ProductCandidate>()
        for (block in blocks(nodes)) {
            val product = toProduct(block) ?: continue
            val existing = result.indexOfFirst { duplicate(it, product) }
            if (existing < 0) result += product
            else if (richness(product) > richness(result[existing])) result[existing] = product
        }
        return result.mapIndexed { i, p -> p.copy(position = i) }
    }

    private fun richness(p: ProductCandidate): Int =
        listOf(p.rating, p.ratingCount, p.mrp, p.deliveryText).count { it != null } + (if (p.prime) 1 else 0)

    private fun quantityOf(text: String): Int =
        Regex("""(?:qty|quantity)\s*:?\s*(\d{1,2})""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 1

    private fun lines(nodes: List<AmazonNode>): List<String> {
        val out = ArrayList<String>()
        for (n in nodes.filter { it.label.isNotBlank() }.sortedWith(compareBy({ it.top }, { it.left }))) {
            if (out.lastOrNull() != n.label) out += n.label
        }
        return out
    }

    /** First price on the line that matches [label], or on one of the next few lines. */
    private fun valueAfter(lines: List<String>, label: Regex): Double? {
        for (i in lines.indices) {
            if (!label.containsMatchIn(lines[i].lowercase(Locale.ROOT))) continue
            for (j in i..minOf(i + 3, lines.lastIndex)) {
                val text = lines[j]
                if (j == i) {
                    val m = label.find(text.lowercase(Locale.ROOT))!!
                    AmazonText.prices(text.substring(m.range.last + 1)).firstOrNull()?.let { return it.value }
                } else {
                    AmazonText.prices(text).firstOrNull()?.let { return it.value }
                }
            }
        }
        return null
    }

    fun extractCart(nodes: List<AmazonNode>): CartSnapshot {
        val blob = nodes.joinToString(" ") { it.haystack }
        val items = blocks(nodes).mapNotNull { b ->
            val price = AmazonText.priceFields(b.text).first
            if (b.title.length < 8) null else CartItem(b.title, price, quantityOf(b.text))
        }.distinctBy { it.key }
        val ls = lines(nodes)
        val subtotal = valueAfter(ls, Regex("""subtotal"""))
        val empty = items.isEmpty() && (blob.contains("cart is empty") || blob.contains("your amazon cart is empty"))
        return CartSnapshot(items, subtotal, empty)
    }

    fun extractCheckout(nodes: List<AmazonNode>): CheckoutSummary {
        val ls = lines(nodes)
        val items = blocks(nodes).mapNotNull { b ->
            if (b.title.length < 8) null else CartItem(b.title, AmazonText.priceFields(b.text).first, quantityOf(b.text))
        }.distinctBy { it.key }

        val total = valueAfter(ls, Regex("""order total|total payable|grand total|amount payable|total amount|you pay"""))
            ?: valueAfter(ls, Regex("""^total\b"""))
        val itemsTotal = valueAfter(ls, Regex("""^items?\b|item\(s\) total|subtotal"""))
        val deliveryLine = ls.indexOfFirst { Regex("""^(delivery|shipping)\b""").containsMatchIn(it.lowercase(Locale.ROOT)) }
        var fee: Double? = null
        if (deliveryLine >= 0) {
            val window = ls.subList(deliveryLine, minOf(deliveryLine + 3, ls.size)).joinToString(" ").lowercase(Locale.ROOT)
            fee = if (Regex("""\bfree\b""").containsMatchIn(window)) 0.0 else AmazonText.prices(window).firstOrNull()?.value
        }
        val address = ls.indexOfFirst { Regex("""^(deliver(ing)? to|shipping to)\b""").containsMatchIn(it.lowercase(Locale.ROOT)) }
            .takeIf { it >= 0 }?.let { i ->
                val line = ls[i]
                if (line.length < 18 && i + 1 < ls.size) line + " " + ls[i + 1] else line
            }
        val payment = paymentLine(ls)
        val estimate = ls.firstOrNull { Regex("""(arriving|delivery by|get it by|delivered by|arrives)""").containsMatchIn(it.lowercase(Locale.ROOT)) }
        return CheckoutSummary(items, itemsTotal, fee, total, address?.take(140), payment?.take(80), estimate?.take(80))
    }

    private val PAYMENT_WORDS = Regex("""pay on delivery|cash on delivery|\bupi\b|credit card|debit card|amazon pay|net ?banking|\bemi\b|gift card""")

    private fun paymentLine(ls: List<String>): String? {
        val label = ls.indexOfFirst { Regex("""payment method|paying with|pay using|pay with""").containsMatchIn(it.lowercase(Locale.ROOT)) }
        if (label >= 0) {
            val same = ls[label].substringAfter(':', "").trim()
            if (same.isNotBlank()) return same
            ls.drop(label + 1).take(3).firstOrNull { PAYMENT_WORDS.containsMatchIn(it.lowercase(Locale.ROOT)) }?.let { return it }
        }
        return ls.firstOrNull { PAYMENT_WORDS.containsMatchIn(it.lowercase(Locale.ROOT)) && it.length < 80 }
    }

    /** Delete button that sits inside the vertical span of the cart row for [block]. */
    fun deleteButtonFor(nodes: List<AmazonNode>, block: ScreenBlock): AmazonNode? =
        deleteButtons(nodes).firstOrNull { it.centerY >= block.top - 20 && it.centerY <= block.bottom + 200 }
}
