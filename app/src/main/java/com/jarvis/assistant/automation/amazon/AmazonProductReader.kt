package com.jarvis.assistant.automation.amazon

import java.util.Locale

/** What a product page shows. Fields that are not visible stay null / empty; nothing is guessed. */
data class ProductDetails(
    val title: String,
    val price: Double?,
    val mrp: Double?,
    val discountPercent: Int?,
    val rating: Double?,
    val ratingCount: Int?,
    val prime: Boolean,
    val deliveryText: String?,
    val freeDelivery: Boolean?,
    val deliveryFee: Double?,
    val offers: List<String>,
    val specs: List<String>,
    val seller: String?,
    val unavailable: Boolean
)

/** Reads a product page from accessibility nodes. */
object AmazonProductReader {

    private val SPEC_HEADERS = listOf("about this item", "product details", "technical details", "specifications", "key features")
    private val STOP_HEADERS = listOf(
        "customer reviews", "customers say", "frequently bought", "similar items", "sponsored", "compare with similar",
        "from the manufacturer", "product description", "questions and answers", "customer questions", "reviews", "videos"
    )

    private fun isTitleLine(n: AmazonNode): Boolean {
        val l = n.label
        if (l.length < 18 || l.count { it.isLetter() } < 10) return false
        if (AmazonText.hasPrice(l)) return false
        val lower = l.lowercase(Locale.ROOT)
        return listOf("sponsored", "free delivery", "get it by", "add to", "buy now", "deliver to", "visit the ", "sold by", "ships from")
            .none { lower.startsWith(it) }
    }

    /** The product title: first long title-like text near the top of the page. */
    fun title(nodes: List<AmazonNode>): String {
        val sorted = nodes.filter { it.label.isNotBlank() }.sortedWith(compareBy({ it.top }, { it.left }))
        return sorted.firstOrNull { isTitleLine(it) && it.top < 1400 }?.label?.take(200).orEmpty()
    }

    fun details(nodes: List<AmazonNode>): ProductDetails {
        val sorted = nodes.filter { it.label.isNotBlank() }.sortedWith(compareBy({ it.top }, { it.left }))
        val title = title(nodes)
        val titleIndex = sorted.indexOfFirst { it.label.startsWith(title.take(30)) && title.isNotEmpty() }
        // Price, rating and delivery live between the title and the specification block.
        val head = sorted.drop(maxOf(titleIndex, 0)).take(40).joinToString(" | ") { it.label }
        val (price, mrp, discount) = AmazonText.priceFields(head)
        val (note, free, fee) = AmazonText.delivery(head, price)

        val specs = ArrayList<String>()
        val labels = sorted.map { it.label }
        val start = labels.indexOfFirst { l -> SPEC_HEADERS.any { l.lowercase(Locale.ROOT).startsWith(it) } }
        if (start >= 0) {
            for (l in labels.drop(start + 1)) {
                val lower = l.lowercase(Locale.ROOT)
                if (STOP_HEADERS.any { lower.startsWith(it) }) break
                if (l.length >= 12 && !AmazonText.hasPrice(l) && specs.none { it == l }) specs += l.take(160)
                if (specs.size >= 8) break
            }
        }
        val seller = labels.firstOrNull { it.lowercase(Locale.ROOT).startsWith("sold by") }
            ?.substringAfter("by", "")?.trim()?.take(60)?.ifBlank { null }
            ?: labels.firstOrNull { it.lowercase(Locale.ROOT).startsWith("sold by") }?.take(80)

        return ProductDetails(
            title = title,
            price = price,
            mrp = mrp,
            discountPercent = discount,
            rating = AmazonText.rating(head),
            ratingCount = AmazonText.ratingCount(head),
            prime = AmazonText.isPrime(head),
            deliveryText = note,
            freeDelivery = free,
            deliveryFee = fee,
            offers = AmazonText.offers(nodes.joinToString(" ") { it.label }),
            specs = specs,
            seller = seller,
            unavailable = AmazonScreenReader.isUnavailable(nodes)
        )
    }
}
