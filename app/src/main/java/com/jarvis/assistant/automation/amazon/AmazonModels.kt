package com.jarvis.assistant.automation.amazon

import java.security.MessageDigest
import java.util.Locale

/*
 * Plain data types for Amazon App Mastery. Nothing in this package's "pure" files touches Android classes,
 * so the parsing, ranking, safety and flow rules can be unit-tested on a normal JVM.
 */

/** One accessibility node reduced to plain data. */
data class AmazonNode(
    val text: String = "",
    val description: String = "",
    val viewId: String = "",
    val className: String = "",
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val selected: Boolean = false,
    val checked: Boolean = false,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0
) {
    /** What a person would read for this node. */
    val label: String get() = (if (text.isNotBlank()) text else description).trim()

    /** Text and description together, lower-cased, for keyword checks. */
    val haystack: String get() = (text + " " + description).trim().lowercase(Locale.ROOT)

    val centerY: Int get() = (top + bottom) / 2
    val centerX: Int get() = (left + right) / 2
}

enum class AmazonScreen {
    UNKNOWN, LOADING, HOME, SEARCH_RESULTS, PRODUCT, CART, CHECKOUT, ORDER_REVIEW, ORDER_PLACED,
    SIGN_IN, CHALLENGE, OFFLINE, ERROR
}

enum class SortPreference { RELEVANCE, PRICE_LOW, PRICE_HIGH, RATING, NEWEST }

/** What the user asked for, after English/Hinglish understanding. */
data class AmazonSearchRequest(
    val query: String,
    val minPrice: Double? = null,
    val maxPrice: Double? = null,
    val minRating: Double? = null,
    val primeOnly: Boolean = false,
    val sort: SortPreference = SortPreference.RELEVANCE,
    val quantity: Int = 1,
    val variants: List<String> = emptyList()
)

data class ProductCandidate(
    val title: String,
    val price: Double?,
    val mrp: Double? = null,
    val discountPercent: Int? = null,
    val rating: Double? = null,
    val ratingCount: Int? = null,
    val prime: Boolean = false,
    val sponsored: Boolean = false,
    val deliveryText: String? = null,
    /** true = free delivery shown, false = a fee is shown, null = not visible. */
    val freeDelivery: Boolean? = null,
    val deliveryFee: Double? = null,
    val offers: List<String> = emptyList(),
    /** Position on the results list, 0-based, as read from the screen. */
    val position: Int = 0
) {
    /** Price plus a visible delivery fee. */
    val totalCost: Double? get() = price?.let { it + (deliveryFee ?: 0.0) }

    /** Stable identity for de-duplication: normalised title start plus price. */
    val key: String get() = TitleMatch.normalize(title).take(60) + "|" + (price?.toLong() ?: -1L)
}

data class CartItem(val title: String, val price: Double?, val quantity: Int = 1) {
    val key: String get() = TitleMatch.normalize(title).take(60) + "|" + (price?.toLong() ?: -1L)
}

data class CartSnapshot(val items: List<CartItem>, val subtotal: Double?, val empty: Boolean)

/** Everything the user must see before an order is placed. */
data class CheckoutSummary(
    val items: List<CartItem>,
    val itemsTotal: Double?,
    val deliveryFee: Double?,
    val total: Double?,
    val addressLabel: String?,
    val paymentLabel: String?,
    val deliveryEstimate: String?
) {
    /** A summary is only safe to confirm when the total and at least one item were read. */
    val readable: Boolean get() = total != null && items.isNotEmpty()

    /**
     * Fingerprint of the things the user agreed to: items, quantities, total, address line and payment line.
     * Only a hash is kept, so the address text is never stored.
     */
    fun fingerprint(): String {
        val itemPart = items.map { TitleMatch.normalize(it.title).take(60) + "x" + it.quantity }.sorted().joinToString(";")
        val raw = listOf(
            itemPart,
            total?.let { String.format(Locale.US, "%.2f", it) }.orEmpty(),
            TitleMatch.normalize(addressLabel.orEmpty()),
            TitleMatch.normalize(paymentLabel.orEmpty())
        ).joinToString("#")
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(24)
    }
}

object Money {
    /** "₹12,999" style text. Whole rupees are shown without decimals. */
    fun format(value: Double?): String {
        if (value == null) return "price not visible"
        return if (value % 1.0 == 0.0) "₹" + String.format(Locale.US, "%,.0f", value)
        else "₹" + String.format(Locale.US, "%,.2f", value)
    }
}

/** Loose title comparison, because Amazon shortens titles differently on every screen. */
object TitleMatch {
    private val NON_ALNUM = Regex("[^a-z0-9]+")

    fun normalize(s: String): String = s.lowercase(Locale.ROOT).replace(NON_ALNUM, " ").trim()

    fun tokens(s: String): Set<String> =
        normalize(s).split(' ').filter { it.length >= 2 }.toSet()

    /**
     * Share of the shorter title's words found in the other title (0..1). A title cut short with "..."
     * still scores high against the full title.
     */
    fun containment(a: String, b: String): Double {
        val ta = tokens(a)
        val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        val common = ta.intersect(tb).size
        return common.toDouble() / minOf(ta.size, tb.size)
    }

    /** True when two titles almost certainly name the same product. */
    fun sameProduct(a: String, b: String): Boolean {
        val common = tokens(a).intersect(tokens(b)).size
        return common >= 3 && containment(a, b) >= 0.6
    }
}
