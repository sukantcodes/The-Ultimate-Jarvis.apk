package com.jarvis.assistant.automation.blinkit

import com.jarvis.assistant.automation.amazon.AmazonNode
import com.jarvis.assistant.automation.amazon.TitleMatch
import java.security.MessageDigest
import java.util.Locale

/*
 * Plain data types for Blinkit App Mastery. Nothing in this package's "pure" files touches Android classes, so the
 * parsing, ranking, safety and flow rules can be unit-tested on a normal JVM. Accessibility nodes are the same plain
 * AmazonNode values the Amazon and Rapido modules use.
 */

enum class BlinkitScreen {
    UNKNOWN, LOADING, HOME, SEARCH_RESULTS, PRODUCT, CART, ADDRESS_PICKER, PAYMENT, ORDER_PLACED, ORDER_TRACKING,
    ORDERS_LIST, SIGN_IN, CHALLENGE, OFFLINE, ERROR, NOT_SERVICEABLE, STORE_CLOSED
}

enum class BlinkitSort { RELEVANCE, PRICE_LOW, UNIT_PRICE, VALUE, RATING }

/** What the user asked for, after English / Hinglish understanding. */
data class BlinkitRequest(
    val query: String,
    val minPrice: Double? = null,
    val maxPrice: Double? = null,
    val quantity: Int = 1,
    val sort: BlinkitSort = BlinkitSort.RELEVANCE,
    val brand: String? = null,
    /** Pack size the user named ("5 kg", "500 ml"), kept so the right pack is picked. */
    val packHint: String? = null,
    /** Set for vague asks such as "something for breakfast": the concrete searches to run. */
    val vague: VagueIntent? = null
)

data class VagueIntent(val label: String, val searchTerms: List<String>)

/** One request inside a shopping list, plus the total budget the user gave for the whole list (if any). */
data class ShoppingList(val items: List<BlinkitRequest>, val totalBudget: Double?)

enum class PackKind { WEIGHT, VOLUME, COUNT }

/** A pack size reduced to a base unit: grams, millilitres or pieces. */
data class PackSize(val kind: PackKind, val baseAmount: Double, val label: String) {
    /** Human unit price label: "₹/kg", "₹/L" or "₹/item". */
    val unitLabel: String get() = when (kind) { PackKind.WEIGHT -> "₹/kg"; PackKind.VOLUME -> "₹/L"; PackKind.COUNT -> "₹/item" }

    /** Price for one kg, one litre or one item, computed from the visible price and this pack. */
    fun unitPrice(price: Double): Double = when (kind) {
        PackKind.WEIGHT, PackKind.VOLUME -> price / baseAmount * 1000.0
        PackKind.COUNT -> price / baseAmount
    }
}

data class BlinkitProduct(
    val name: String,
    val price: Double?,
    val mrp: Double? = null,
    val discountPercent: Int? = null,
    val packText: String? = null,
    val pack: PackSize? = null,
    val rating: Double? = null,
    val ratingCount: Int? = null,
    val etaText: String? = null,
    /** false when the card says out of stock / notify me / sold out. */
    val available: Boolean = true,
    /** Quantity already in the cart, read from the card's stepper. */
    val inCartQty: Int = 0,
    /** The card has several pack options behind a button instead of a plain ADD. */
    val hasOptions: Boolean = false,
    val offers: List<String> = emptyList(),
    val position: Int = 0
) {
    val unitPrice: Double? get() = if (price != null && pack != null) pack.unitPrice(price) else null
    val key: String get() = TitleMatch.normalize(name).take(60) + "|" + TitleMatch.normalize(packText.orEmpty()) + "|" + (price?.toLong() ?: -1L)
}

data class CartLine(val name: String, val packText: String?, val price: Double?, val quantity: Int = 1, val unavailable: Boolean = false) {
    val key: String get() = TitleMatch.normalize(name).take(60) + "|" + TitleMatch.normalize(packText.orEmpty())
}

/** The bill block on the cart / payment screen. Every field is null unless it was actually read. */
data class BillDetails(
    val itemTotal: Double?,
    /** Visible fee lines (delivery, handling, small cart, surge ...), in order; FREE reads as 0.0. */
    val fees: List<Pair<String, Double>>,
    val discounts: List<Pair<String, Double>>,
    val total: Double?,
    /** Money still needed to reach the minimum order value, when the app says so. */
    val minOrderGap: Double?,
    /** Money still needed to unlock free delivery, when the app says so. */
    val freeDeliveryGap: Double?
) {
    val feeTotal: Double get() = fees.sumOf { it.second }
}

data class CartSnapshot(val lines: List<CartLine>, val bill: BillDetails, val empty: Boolean, val address: String?, val eta: String?)

/** Everything the user must see before an order is placed. */
data class CheckoutSummary(
    val items: List<CartLine>,
    val bill: BillDetails,
    val addressLabel: String?,
    val paymentLabel: String?,
    val deliveryEstimate: String?
) {
    val total: Double? get() = bill.total

    /** Safe to confirm only when the total and at least one item were read. */
    val readable: Boolean get() = total != null && items.isNotEmpty()

    /**
     * Fingerprint of what the user agreed to: items, quantities, total, address line and payment line. Only a hash
     * is kept, so the address text is never stored.
     */
    fun fingerprint(): String {
        val itemPart = items.map { TitleMatch.normalize(it.name).take(60) + "|" + TitleMatch.normalize(it.packText.orEmpty()) + "x" + it.quantity }
            .sorted().joinToString(";")
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

enum class OrderStage { PLACED, PREPARING, PARTNER_ASSIGNED, ON_THE_WAY, ARRIVED, DELIVERED, CANCELLED, UNKNOWN }

/** What an order tracking screen says right now. Nothing here is inferred. */
data class OrderStatus(
    val stage: OrderStage,
    val etaText: String?,
    val partnerName: String?,
    val delayNotice: String?,
    val itemNotices: List<String>,
    val cancelOffered: Boolean,
    val helpOffered: Boolean,
    val refundMentioned: Boolean
)

data class AddressEntry(val label: String, val text: String, val node: AmazonNode?)

object BlinkitMoney {
    /** "₹1,299" style text. Whole rupees show without decimals. */
    fun format(value: Double?): String {
        if (value == null) return "not visible"
        return if (value % 1.0 == 0.0) "₹" + String.format(Locale.US, "%,.0f", value)
        else "₹" + String.format(Locale.US, "%,.2f", value)
    }
}

/** Reads pack sizes such as "500 g", "1 L", "2 x 250 ml", "pack of 6" and "6 pcs". */
object PackParser {
    private const val UNIT = """(kg|kgs|kilograms?|g|gm|gms|grams?|ltrs?|litres?|liters?|l|ml|millilit(?:re|er)s?|pcs|pc|pieces?|units?|eggs?|nos|n)"""
    private val MULTI = Regex("""(\d+)\s*[x×*]\s*(\d+(?:\.\d+)?)\s*$UNIT\b""", RegexOption.IGNORE_CASE)
    private val SINGLE = Regex("""(\d+(?:\.\d+)?)\s*$UNIT\b""", RegexOption.IGNORE_CASE)
    private val PACK_OF = Regex("""\bpack of (\d+)\b""", RegexOption.IGNORE_CASE)
    private val DOZEN = Regex("""\b(\d+|a|one)?\s*dozen\b""", RegexOption.IGNORE_CASE)

    private fun kindAndFactor(unit: String): Pair<PackKind, Double> = when (unit.lowercase(Locale.ROOT)) {
        "kg", "kgs", "kilogram", "kilograms" -> PackKind.WEIGHT to 1000.0
        "g", "gm", "gms", "gram", "grams" -> PackKind.WEIGHT to 1.0
        "l", "ltr", "ltrs", "litre", "litres", "liter", "liters" -> PackKind.VOLUME to 1000.0
        "ml", "millilitre", "millilitres", "milliliter", "milliliters" -> PackKind.VOLUME to 1.0
        else -> PackKind.COUNT to 1.0
    }

    fun parse(text: String?): PackSize? {
        if (text.isNullOrBlank()) return null
        MULTI.find(text)?.let { m ->
            val count = m.groupValues[1].toDoubleOrNull() ?: return@let
            val each = m.groupValues[2].toDoubleOrNull() ?: return@let
            val (kind, factor) = kindAndFactor(m.groupValues[3])
            val total = if (kind == PackKind.COUNT) count * each else count * each * factor
            if (total > 0) return PackSize(kind, total, m.value.trim())
        }
        SINGLE.find(text)?.let { m ->
            val n = m.groupValues[1].toDoubleOrNull() ?: return@let
            val (kind, factor) = kindAndFactor(m.groupValues[2])
            val base = n * factor
            if (base <= 0) return@let
            // "pack of 4" next to "250 g" means four packs of that size.
            val multi = PACK_OF.find(text)?.groupValues?.get(1)?.toIntOrNull()
            return if (multi != null && kind != PackKind.COUNT) PackSize(kind, base * multi, m.value.trim() + " x" + multi)
            else PackSize(kind, base, m.value.trim())
        }
        PACK_OF.find(text)?.let { m -> m.groupValues[1].toDoubleOrNull()?.let { return PackSize(PackKind.COUNT, it, m.value.trim()) } }
        DOZEN.find(text)?.let { m ->
            val n = m.groupValues[1].let { if (it.isBlank() || it.equals("a", true) || it.equals("one", true)) 1.0 else it.toDoubleOrNull() ?: 1.0 }
            return PackSize(PackKind.COUNT, n * 12.0, m.value.trim())
        }
        return null
    }

    /** True when the whole short text is only a pack size (so it is a pack label, not a product name). */
    fun looksLikePackLabel(text: String): Boolean {
        val t = text.trim()
        if (t.length > 24) return false
        val stripped = t.lowercase(Locale.ROOT)
            .replace(Regex("""\bpack of \d+\b"""), " ").replace(Regex("""\bdozen\b"""), " ")
            .replace(MULTI, " ").replace(SINGLE, " ").replace(Regex("""[\s(),.+-]+"""), " ").trim()
        return parse(t) != null && stripped.isEmpty()
    }
}
