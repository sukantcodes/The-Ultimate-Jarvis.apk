package com.jarvis.assistant.automation.amazon

import java.util.Locale

/**
 * Turns what the user said or typed (English, Hinglish, or a mix) into a structured Amazon request:
 * the product words, a price range, a minimum rating, Prime, sort order, quantity and variants.
 *
 * Numbers only count as prices when a price word sits next to them ("under 500", "500 ke neeche",
 * "rs 500", "500 tak"), so product names such as "iPhone 15" or "4K TV" are left alone.
 */
object AmazonQueryParser {

    private const val NUM = """(\d+(?:\.\d+)?)\s*(?:(k|thousand|hazaar|hazar|hajar|lakhs?|lacs?)\b)?"""
    private const val RS = """(?:rs\s*)?"""

    private val RANGE_BETWEEN = Regex("""between\s+$RS$NUM\s*$RS\s*(?:and|to|-)\s*$RS$NUM""")
    private val RANGE_SE_TAK = Regex("""$RS$NUM\s*$RS\s*(?:se|to|-)\s*$RS$NUM\s*(?:rs\s*)?(?:tak|ke beech me|ke beech|ke bich|ke andar)""")
    private val RANGE_RS = Regex("""rs\s*$NUM\s*(?:se|to|-)\s*(?:rs\s*)?$NUM""")

    private val MAX_WORD = Regex(
        """(?:under|below|less than|lesser than|within|upto|up to|maximum|max|at most|not more than|budget(?: of| is)?)\s*$RS$NUM"""
    )
    private val MAX_HINGLISH = Regex(
        """$RS$NUM\s*(?:rs\s*)?(?:se kam|se kum|se neeche|se niche|ke neeche|ke niche|ke andar|ke ander|ke under|ke bhitar|ka budget(?: me| mein)?|ke budget(?: me| mein)?)"""
    )
    private val MAX_TAK = Regex("""$RS$NUM\s*(?:rs\s*)?tak\b""")

    private val MIN_WORD = Regex(
        """(?:above|over|more than|greater than|at least|minimum|min|starting from)\s*$RS$NUM"""
    )
    private val MIN_HINGLISH = Regex(
        """$RS$NUM\s*(?:rs\s*)?(?:se zyada|se jyada|se upar|ke upar|se adhik|se jada|se bada)"""
    )

    private val RATING_STARS = Regex(
        """(\d(?:\.\d)?)\s*(?:\+|plus)?\s*(?:star|stars|\*|rating|ratings)(?:\s*(?:and above|and up|or above|or more|or higher|\+|plus|se upar|se zyada|se jyada|ya zyada|ya jyada|ke upar|above|up))?"""
    )
    private val RATING_WORD = Regex("""rating\s*(?:of\s*)?(\d(?:\.\d)?)\s*(?:\+|plus|and above|se upar|se zyada)?""")
    private val RATING_QUALITATIVE = Regex(
        """top[- ]rated|highest rated|best rated|good rating|good ratings|achi rating|acchi rating|accha rating|badhiya rating|best rating|high rating"""
    )

    private val PRIME = Regex("""\bprime\b(?!\s*video)(?:\s*(?:wale|wali|wala|eligible|only|delivery|items?))?""")

    private val SORT_LOW = Regex(
        """cheapest|lowest price|lowest cost|low to high|price low|sabse sasta|sabse saste|sabse sasti|\bsasta\b|\bsasti\b|\bsaste\b|kam daam|kam price|kam kimat"""
    )
    private val SORT_HIGH = Regex("""most expensive|highest price|high to low|price high|sabse mehnga|sabse mahanga|\bmehnga\b|\bmahanga\b""")
    private val SORT_RATING = Regex("""\bbest\b|\btop\b|highest rated|best rated|sabse accha|sabse acha|sabse achha|sabse badhiya|\bbadhiya\b""")
    private val SORT_NEW = Regex("""\bnewest\b|\blatest\b|new arrivals?|\bnaya\b|\bnayi\b|\bnaye\b|new launch""")

    private val QTY_LABEL = Regex("""(?:quantity|qty|qnty)\s*[:=]?\s*(\d{1,2})""")
    private val QTY_DIGIT_UNIT = Regex("""\b(\d{1,2})\s*(?:x\b|pcs\b|pc\b|pieces?\b|packs?\b|units?\b|nos\b)""")
    private val QTY_WORD_UNIT = Regex(
        """\b(ek|do|teen|char|paanch|panch|chhe|saat|aath|nau|das|one|two|three|four|five|six|seven|eight|nine|ten)\s+(?:pcs|pc|pieces?|packs?|units?|nos)\b"""
    )
    private val NUMBER_WORDS = mapOf(
        "ek" to 1, "one" to 1, "do" to 2, "two" to 2, "teen" to 3, "three" to 3, "char" to 4, "four" to 4,
        "paanch" to 5, "panch" to 5, "five" to 5, "chhe" to 6, "six" to 6, "saat" to 7, "seven" to 7,
        "aath" to 8, "eight" to 8, "nau" to 9, "nine" to 9, "das" to 10, "ten" to 10
    )

    private val COLORS = listOf(
        "black", "white", "blue", "red", "green", "silver", "gold", "grey", "gray", "pink", "purple",
        "yellow", "orange", "brown", "beige", "navy", "maroon"
    )
    private val COLOR_REGEX = Regex("""\b(${COLORS.joinToString("|")})\b""")
    private val STORAGE_REGEX = Regex("""\b(\d{2,4})\s?(gb|tb)\b""")
    private val SIZE_LABEL = Regex("""\bsize\s+([a-z0-9.]{1,5})\b""")
    private val SIZE_LETTER = Regex("""\b(xxs|xs|xxl|xxxl|2xl|3xl|xl)\b""")

    private val STOPWORDS = setOf(
        "amazon", "amazon.in", "amazon.com", "of", "on", "in", "at", "pe", "par", "per", "se", "ke", "ka", "ki", "ko",
        "me", "mein", "main", "mere", "meri", "mera", "mujhe", "mujhko", "hamare", "for", "my", "the", "a", "an",
        "please", "plz", "pls", "kindly", "jarvis", "hey", "ok", "okay", "search", "find", "look", "looking",
        "show", "dikhao", "dikha", "dikhana", "dhoondo", "dhundo", "dhundho", "khojo", "dekho", "dekhna",
        "batao", "bata", "do", "de", "dena", "kar", "karo", "kijiye", "kijie", "chahiye", "chahie", "chaiye",
        "want", "need", "i", "we", "buy", "order", "kharid", "kharido", "khareedo", "lena", "lo", "le", "lijiye",
        "add", "cart", "to", "some", "any", "ek", "koi", "wale", "wala", "wali", "rs", "rupees", "rupee", "rupaye",
        "rupaiye", "under", "below", "above", "over", "best", "top", "cheapest", "latest", "newest", "sabse",
        "sasta", "sasti", "saste", "acha", "accha", "achha", "badhiya", "and", "or", "with", "ya", "aur", "tak",
        "neeche", "niche", "upar", "kam", "zyada", "jyada", "budget", "rated", "rating", "ratings", "star", "stars",
        "prime", "pieces", "piece", "pcs", "pack", "packs", "quantity", "qty", "wali", "is", "hai", "ho", "hain",
        "it", "this", "that", "item", "product", "products", "dhoond", "khoj", "daalo", "dalo", "dal", "do"
    )

    fun parse(utterance: String): AmazonSearchRequest {
        var s = normalize(utterance)
        var minPrice: Double? = null
        var maxPrice: Double? = null

        // 1. Price ranges first, so their numbers are not read again as single limits.
        RANGE_BETWEEN.find(s)?.let { m ->
            val a = amount(m.groupValues[1], m.groupValues[2])
            val b = amount(m.groupValues[3], m.groupValues[4])
            minPrice = minOf(a, b); maxPrice = maxOf(a, b)
            s = s.replaceRange(m.range, " ")
        }
        if (maxPrice == null) {
            RANGE_SE_TAK.find(s)?.let { m ->
                val a = amount(m.groupValues[1], m.groupValues[2])
                val b = amount(m.groupValues[3], m.groupValues[4])
                minPrice = minOf(a, b); maxPrice = maxOf(a, b)
                s = s.replaceRange(m.range, " ")
            }
        }
        if (maxPrice == null) {
            RANGE_RS.find(s)?.let { m ->
                val a = amount(m.groupValues[1], m.groupValues[2])
                val b = amount(m.groupValues[3], m.groupValues[4])
                minPrice = minOf(a, b); maxPrice = maxOf(a, b)
                s = s.replaceRange(m.range, " ")
            }
        }

        // 2. Single limits.
        if (maxPrice == null) {
            (MAX_WORD.find(s) ?: MAX_HINGLISH.find(s))?.let { m ->
                maxPrice = amount(m.groupValues[1], m.groupValues[2])
                s = s.replaceRange(m.range, " ")
            }
        }
        if (maxPrice == null) {
            MAX_TAK.find(s)?.let { m ->
                val value = amount(m.groupValues[1], m.groupValues[2])
                if (value >= 50.0) {
                    maxPrice = value
                    s = s.replaceRange(m.range, " ")
                }
            }
        }
        if (minPrice == null) {
            (MIN_WORD.find(s) ?: MIN_HINGLISH.find(s))?.let { m ->
                minPrice = amount(m.groupValues[1], m.groupValues[2])
                s = s.replaceRange(m.range, " ")
            }
        }

        // 3. Rating.
        var minRating: Double? = null
        (RATING_STARS.find(s) ?: RATING_WORD.find(s))?.let { m ->
            val value = m.groupValues[1].toDoubleOrNull()
            if (value != null && value in 1.0..5.0) {
                minRating = value
                s = s.replaceRange(m.range, " ")
            }
        }
        var sort = SortPreference.RELEVANCE
        RATING_QUALITATIVE.find(s)?.let { m ->
            if (minRating == null) minRating = 4.0
            sort = SortPreference.RATING
            s = s.replaceRange(m.range, " ")
        }

        // 4. Prime.
        var prime = false
        PRIME.find(s)?.let { m ->
            prime = true
            s = s.replaceRange(m.range, " ")
        }

        // 5. Quantity.
        var quantity = 1
        QTY_LABEL.find(s)?.let { m ->
            quantity = m.groupValues[1].toInt().coerceIn(1, 20)
            s = s.replaceRange(m.range, " ")
        } ?: QTY_DIGIT_UNIT.find(s)?.let { m ->
            quantity = m.groupValues[1].toInt().coerceIn(1, 20)
            s = s.replaceRange(m.range, " ")
        } ?: QTY_WORD_UNIT.find(s)?.let { m ->
            quantity = (NUMBER_WORDS[m.groupValues[1]] ?: 1).coerceIn(1, 20)
            s = s.replaceRange(m.range, " ")
        }

        // 6. Sort order (price words win over "best").
        if (SORT_LOW.containsMatchIn(s)) {
            sort = SortPreference.PRICE_LOW
            s = SORT_LOW.replace(s, " ")
        } else if (SORT_HIGH.containsMatchIn(s)) {
            sort = SortPreference.PRICE_HIGH
            s = SORT_HIGH.replace(s, " ")
        } else if (SORT_NEW.containsMatchIn(s)) {
            sort = SortPreference.NEWEST
            s = SORT_NEW.replace(s, " ")
        } else if (SORT_RATING.containsMatchIn(s)) {
            if (sort == SortPreference.RELEVANCE) sort = SortPreference.RATING
            s = SORT_RATING.replace(s, " ")
        }

        // 7. Variants. Colours and storage stay in the search words (they help the search); "size 9" does not.
        val variants = ArrayList<String>()
        COLOR_REGEX.findAll(s).forEach { variants.add(it.groupValues[1]) }
        STORAGE_REGEX.findAll(s).forEach { variants.add(it.groupValues[1] + " " + it.groupValues[2].uppercase(Locale.ROOT)) }
        SIZE_LABEL.find(s)?.let { m ->
            variants.add("size " + m.groupValues[1])
            s = s.replaceRange(m.range, " ")
        } ?: SIZE_LETTER.find(s)?.let { m ->
            variants.add("size " + m.groupValues[1].uppercase(Locale.ROOT))
            s = s.replaceRange(m.range, " ")
        }

        // 8. What is left, minus filler words, is the product.
        val words = s.split(Regex("[^\\p{L}\\p{N}.+&-]+")).filter { it.isNotBlank() }
        val kept = words.filterNot { it in STOPWORDS }
        var query = kept.joinToString(" ").trim().trim('.', '-', '+', '&')
        if (query.isBlank()) {
            query = words.filterNot { it.startsWith("amazon") }.joinToString(" ").trim()
        }

        return AmazonSearchRequest(
            query = query,
            minPrice = minPrice,
            maxPrice = maxPrice,
            minRating = minRating,
            primeOnly = prime,
            sort = sort,
            quantity = quantity,
            variants = variants.distinct()
        )
    }

    /** Explicit values (from the AI's tool arguments) win over what was understood from the sentence. */
    fun merge(
        base: AmazonSearchRequest,
        query: String? = null,
        minPrice: Double? = null,
        maxPrice: Double? = null,
        minRating: Double? = null,
        primeOnly: Boolean? = null,
        sort: SortPreference? = null,
        quantity: Int? = null,
        variants: List<String>? = null
    ): AmazonSearchRequest = base.copy(
        query = query?.trim()?.takeIf { it.isNotBlank() } ?: base.query,
        minPrice = minPrice ?: base.minPrice,
        maxPrice = maxPrice ?: base.maxPrice,
        minRating = minRating ?: base.minRating,
        primeOnly = primeOnly ?: base.primeOnly,
        sort = sort ?: base.sort,
        quantity = (quantity ?: base.quantity).coerceIn(1, 20),
        variants = variants?.filter { it.isNotBlank() }?.ifEmpty { null } ?: base.variants
    )

    fun sortFromText(value: String?): SortPreference? = when (value?.trim()?.lowercase(Locale.ROOT)) {
        "price_low", "price_asc", "cheapest", "low_to_high", "lowest_price" -> SortPreference.PRICE_LOW
        "price_high", "price_desc", "high_to_low", "highest_price" -> SortPreference.PRICE_HIGH
        "rating", "top_rated", "best", "best_rated" -> SortPreference.RATING
        "newest", "latest", "new" -> SortPreference.NEWEST
        "relevance", "featured", "default" -> SortPreference.RELEVANCE
        else -> null
    }

    private fun normalize(raw: String): String {
        var s = raw.lowercase(Locale.ROOT)
        // Devanagari digits to ASCII.
        val sb = StringBuilder(s.length)
        for (ch in s) sb.append(if (ch in '०'..'९') ('0' + (ch - '०')) else ch)
        s = sb.toString()
        s = s.replace("₹", " rs ")
        s = Regex("""\b(?:rupees|rupee|rupaye|rupaiye|rupay|inr)\b""").replace(s, " rs ")
        s = Regex("""\brs\.?""").replace(s, "rs")
        s = Regex("""(?<=\d),(?=\d{2,3})""").replace(s, "")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    private fun amount(number: String, unit: String): Double {
        val base = number.toDoubleOrNull() ?: 0.0
        val mult = when (unit) {
            "k", "thousand", "hazaar", "hazar", "hajar" -> 1_000.0
            "lakh", "lakhs", "lac", "lacs" -> 100_000.0
            else -> 1.0
        }
        return base * mult
    }
}
