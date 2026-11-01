package com.jarvis.assistant.automation.blinkit

import java.util.Locale

/**
 * Turns what the user said or typed (English, Hinglish, or a mix) into structured Blinkit requests: product words,
 * a price limit, quantity, pack size, brand, sort preference, vague intents ("something for breakfast") and whole
 * shopping lists with a total budget.
 *
 * Numbers only count as prices next to a price word ("under 200", "200 ke andar", "rs 200"); a number next to a unit
 * ("5 kg", "500 ml") is a pack size, not a quantity or a price.
 */
object BlinkitQueryParser {

    private const val NUM = """(\d+(?:\.\d+)?)\s*(?:(k|thousand|hazaar|hazar|hajar)\b)?"""
    private const val RS = """(?:rs\s*)?"""

    private val RANGE_BETWEEN = Regex("""between\s+$RS$NUM\s*$RS\s*(?:and|to|-)\s*$RS$NUM""")
    private val RANGE_SE_TAK = Regex("""$RS$NUM\s*$RS\s*(?:se|to|-)\s*$RS$NUM\s*(?:rs\s*)?(?:tak|ke beech me|ke beech|ke bich|ke andar)""")
    private val MAX_WORD = Regex("""(?:under|below|less than|within|upto|up to|maximum|max|at most|not more than|budget(?: of| is)?|total(?: of)?)\s*$RS$NUM""")
    private val MAX_HINGLISH = Regex(
        """$RS$NUM\s*(?:rs\s*)?(?:se kam|se kum|se neeche|se niche|ke neeche|ke niche|ke andar|ke ander|ke under|ke bhitar|ka budget(?: me| mein)?|ke budget(?: me| mein)?|me|mein)\b"""
    )
    private val MAX_TAK = Regex("""$RS$NUM\s*(?:rs\s*)?tak\b""")
    private val MIN_WORD = Regex("""(?:above|over|more than|at least|minimum|min|starting from)\s*$RS$NUM""")
    private val MIN_HINGLISH = Regex("""$RS$NUM\s*(?:rs\s*)?(?:se zyada|se jyada|se upar|ke upar|se adhik)""")
    private val RS_PREFIX = Regex("""\brs\s*(\d+(?:\.\d+)?)""")

    private val SORT_VALUE = Regex(
        """best value|value for money|paisa vasool|paisa wasool|kifayati|sabse sahi|best deal|worth it|value wala|value wale|good value"""
    )
    private val SORT_LOW = Regex("""cheapest|lowest price|price low|sabse sasta|sabse saste|sabse sasti|\bsasta\b|\bsasti\b|\bsaste\b|kam daam|kam price""")
    private val SORT_UNIT = Regex("""per\s*(?:kg|kilo|litre|liter|ltr|l|item|piece)\b|unit price|rate per|lowest rate""")
    private val SORT_RATING = Regex("""top[- ]rated|highest rated|best rated|good rating|achi rating|acchi rating|best quality|high quality""")
    private val SORT_GOOD = Regex("""\bbest\b|\bgood\b|\bgreat\b|\baccha\b|\bacha\b|\bachha\b|\bachi\b|\bacchi\b|\bbadhiya\b|\bquality\b""")

    private val PACK_HINT = Regex("""\b(\d+(?:\.\d+)?)\s?(kg|kgs|g|gm|gms|grams?|ml|l|ltr|litres?|liters?)\b""")
    private val QTY_LABEL = Regex("""(?:quantity|qty)\s*[:=]?\s*(\d{1,2})""")
    private val QTY_UNIT = Regex(
        """\b(\d{1,2}|ek|do|teen|char|paanch|panch|chhe|saat|aath|nau|das|one|two|three|four|five|six|seven|eight|nine|ten)\s+(?:packets?|pkts?|pcs|pc|pieces?|packs?|bottles?|bags?|boxes|box|cans?|tubs?|sachets?|units?|nos|dozen)\b"""
    )
    private val QTY_LEADING = Regex("""^(\d{1,2})\s+(?=[a-z])""")
    private val NUMBER_WORDS = mapOf(
        "ek" to 1, "one" to 1, "do" to 2, "two" to 2, "teen" to 3, "three" to 3, "char" to 4, "four" to 4,
        "paanch" to 5, "panch" to 5, "five" to 5, "chhe" to 6, "six" to 6, "saat" to 7, "seven" to 7,
        "aath" to 8, "eight" to 8, "nau" to 9, "nine" to 9, "das" to 10, "ten" to 10
    )

    /** Known brands, matched on the normalised sentence. Longer names first. */
    private val BRANDS = listOf(
        "surf excel", "mother dairy", "dairy milk", "head and shoulders", "head & shoulders", "india gate", "red label",
        "taj mahal", "tata salt", "tata tea", "clinic plus", "amul", "tata", "aashirvaad", "fortune", "ariel", "tide", "nestle",
        "britannia", "parle", "maggi", "dettol", "colgate", "pepsodent", "himalaya", "patanjali", "saffola", "daawat",
        "haldiram", "lays", "kurkure", "cadbury", "vim", "harpic", "lizol", "comfort", "rin", "wheel", "ghadi", "nirma",
        "dove", "lux", "lifebuoy", "pears", "sunsilk", "pantene", "kissan", "knorr", "bournvita", "horlicks", "complan",
        "yippee", "mdh", "everest", "catch", "dabur", "vatika", "closeup", "sensodyne", "odonil", "good knight", "mortein"
    )

    /** Hinglish and spelling variants → the English word Blinkit's search understands. Multi-word keys first. */
    private val ALIASES: List<Pair<String, String>> = listOf(
        "washing powder" to "detergent powder", "kapde dhone ka" to "detergent", "kapde dhone wala" to "detergent",
        "chai patti" to "tea", "cold drink" to "cold drink", "bartan dhone ka" to "dish wash", "bartan dhone wala" to "dish wash",
        "toilet safai" to "toilet cleaner", "floor safai" to "floor cleaner", "dant manjan" to "toothpaste",
        "doodh" to "milk", "dudh" to "milk", "dahi" to "curd", "makhan" to "butter", "anda" to "eggs", "ande" to "eggs",
        "aata" to "atta", "chawal" to "rice", "chaawal" to "rice", "daal" to "dal", "cheeni" to "sugar", "chini" to "sugar",
        "namak" to "salt", "tel" to "oil", "sabun" to "soap", "pani" to "water", "chai" to "tea", "aloo" to "potato",
        "pyaz" to "onion", "pyaaz" to "onion", "tamatar" to "tomato", "kela" to "banana", "kele" to "banana", "seb" to "apple",
        "sabzi" to "vegetables", "sabji" to "vegetables", "phal" to "fruits", "nimbu" to "lemon", "adrak" to "ginger",
        "lehsun" to "garlic", "mirch" to "chilli", "haldi" to "turmeric", "jeera" to "cumin", "dhaniya" to "coriander",
        "bisket" to "biscuit", "biskut" to "biscuit", "biscut" to "biscuit", "burger" to "burger", "roti" to "atta"
    )

    private val VOCAB = listOf(
        "milk", "bread", "butter", "cheese", "paneer", "curd", "yogurt", "eggs", "atta", "rice", "dal", "sugar", "salt",
        "oil", "ghee", "tea", "coffee", "detergent", "shampoo", "soap", "toothpaste", "biscuit", "biscuits", "noodles",
        "chocolate", "chips", "namkeen", "juice", "water", "potato", "onion", "tomato", "banana", "apple", "vegetables",
        "fruits", "cornflakes", "oats", "honey", "jam", "ketchup", "sauce", "pasta", "cleaner", "dishwash", "tissue",
        "sanitizer", "diapers", "ice cream", "cookies", "popcorn", "turmeric", "masala", "flour", "powder", "liquid",
        "conditioner", "deodorant", "lotion", "razor", "cereal", "peanut", "almonds", "cashew", "raisins", "coconut"
    )

    private val STOPWORDS = setOf(
        "blinkit", "blinket", "on", "in", "at", "pe", "par", "per", "se", "ke", "ka", "ki", "ko", "me", "mein", "main",
        "mere", "meri", "mera", "mujhe", "mujhko", "for", "my", "the", "a", "an", "please", "plz", "pls", "jarvis", "hey",
        "ok", "okay", "search", "find", "look", "show", "dikhao", "dikha", "dhoondo", "dhundo", "khojo", "dekho", "batao",
        "bata", "do", "de", "dena", "kar", "karo", "kijiye", "chahiye", "chahie", "chaiye", "want", "need", "i", "we", "buy",
        "order", "kharid", "kharido", "lena", "lo", "le", "add", "cart", "to", "some", "any", "koi", "wale", "wala", "wali",
        "rs", "rupees", "rupee", "rupaye", "under", "below", "above", "over", "best", "good", "great", "cheapest", "sabse",
        "sasta", "sasti", "saste", "acha", "accha", "achha", "achi", "acchi", "badhiya", "and", "or", "with", "ya", "aur",
        "tak", "neeche", "niche", "andar", "ander", "upar", "kam", "zyada", "jyada", "budget", "rated", "rating", "value",
        "money", "paisa", "vasool", "sab", "total", "kul", "overall", "within", "is", "hai", "ho", "hain", "it", "this",
        "that", "item", "items", "product", "products", "packet", "packets", "pack", "packs", "pcs", "pieces", "piece",
        "quantity", "qty", "kuch", "something", "anything", "ke liye", "liye", "from", "of", "up", "kg", "litre", "liter",
        "ltr", "quality", "deal", "worth", "sahi", "kifayati", "wale", "mangwao", "mangao", "mangwa", "jaldi", "fast",
        "saman", "samaan", "saaman", "samagri", "cheez", "cheeze", "cheezein", "cheezen", "stuff", "things"
    )

    // Vague asks: when nothing concrete is left after removing filler, these words decide what to search.
    private val VAGUE: List<Pair<Regex, VagueIntent>> = listOf(
        Regex("""\b(breakfast|nashta|nasta|naashta|naasta)\b""") to VagueIntent("breakfast", listOf("bread", "eggs", "milk", "cornflakes", "oats")),
        Regex("""\b(snacks?|namkeen|munchies)\b""") to VagueIntent("snacks", listOf("chips", "namkeen", "biscuits", "popcorn")),
        Regex("""\b(sweet|sweets|meetha|dessert|mithai)\b""") to VagueIntent("something sweet", listOf("chocolate", "ice cream", "cookies", "gulab jamun")),
        Regex("""\b(dinner|lunch|khana)\b""") to VagueIntent("dinner", listOf("atta", "rice", "dal", "paneer", "vegetables")),
        Regex("""\b(cleaning|safai|ghar ki safai)\b""") to VagueIntent("cleaning", listOf("floor cleaner", "dish wash", "detergent", "toilet cleaner")),
        Regex("""\b(party)\b""") to VagueIntent("party", listOf("cold drink", "chips", "ice cubes", "namkeen")),
        Regex("""\b(healthy)\b""") to VagueIntent("healthy food", listOf("oats", "fruits", "nuts", "curd"))
    )

    // ---- Public API ---------------------------------------------------------------------------------

    fun parse(utterance: String): BlinkitRequest {
        val s = normalize(utterance)
        val b = extractBudget(s)
        return buildRequest(b.rest, b.min, b.max)
    }

    /**
     * Splits "milk, bread aur 2 eggs total 300 ke andar" into items. With two or more items a budget phrase is the
     * total for the whole list; with one item it is that product's price limit.
     */
    fun parseShoppingList(utterance: String): ShoppingList {
        val s = normalize(utterance)
        val b = extractBudget(s)
        val parts = b.rest.split(Regex("""\s*(?:,|\+|&|\bplus\b|\band\b|\baur\b|\btatha\b)\s*"""))
            .map { it.trim() }.filter { it.isNotBlank() }
        val items = parts.map { buildRequest(it, null, null) }.filter { it.query.isNotBlank() }
        if (items.isEmpty()) return ShoppingList(emptyList(), b.max)
        if (items.size == 1) return ShoppingList(listOf(items[0].copy(minPrice = b.min, maxPrice = b.max)), null)
        return ShoppingList(items, b.max)
    }

    fun merge(
        base: BlinkitRequest,
        query: String? = null,
        minPrice: Double? = null,
        maxPrice: Double? = null,
        quantity: Int? = null,
        sort: BlinkitSort? = null,
        brand: String? = null,
        packHint: String? = null
    ): BlinkitRequest = base.copy(
        query = query?.trim()?.takeIf { it.isNotBlank() } ?: base.query,
        minPrice = minPrice ?: base.minPrice,
        maxPrice = maxPrice ?: base.maxPrice,
        quantity = (quantity ?: base.quantity).coerceIn(1, 20),
        sort = sort ?: base.sort,
        brand = brand?.takeIf { it.isNotBlank() } ?: base.brand,
        packHint = packHint?.takeIf { it.isNotBlank() } ?: base.packHint,
        vague = if (query != null && query.isNotBlank()) null else base.vague
    )

    fun sortFromText(value: String?): BlinkitSort? = when (value?.trim()?.lowercase(Locale.ROOT)) {
        "price_low", "cheapest", "lowest_price" -> BlinkitSort.PRICE_LOW
        "unit_price", "per_unit", "price_per_unit" -> BlinkitSort.UNIT_PRICE
        "value", "best_value", "value_for_money" -> BlinkitSort.VALUE
        "rating", "top_rated", "best_rated" -> BlinkitSort.RATING
        "relevance", "default" -> BlinkitSort.RELEVANCE
        else -> null
    }

    // ---- Internals -----------------------------------------------------------------------------------

    private data class Budget(val rest: String, val min: Double?, val max: Double?)

    private fun extractBudget(input: String): Budget {
        var s = input
        var min: Double? = null
        var max: Double? = null
        RANGE_BETWEEN.find(s)?.let { m ->
            val a = amount(m.groupValues[1], m.groupValues[2]); val c = amount(m.groupValues[3], m.groupValues[4])
            min = minOf(a, c); max = maxOf(a, c); s = s.replaceRange(m.range, " ")
        }
        if (max == null) RANGE_SE_TAK.find(s)?.let { m ->
            val a = amount(m.groupValues[1], m.groupValues[2]); val c = amount(m.groupValues[3], m.groupValues[4])
            min = minOf(a, c); max = maxOf(a, c); s = s.replaceRange(m.range, " ")
        }
        if (max == null) (MAX_WORD.find(s) ?: MAX_HINGLISH.find(s))?.let { m ->
            // "5 kg me" is a pack size, never a budget: the number must not sit right before a unit.
            val value = amount(m.groupValues[1], m.groupValues[2])
            val after = s.substring(maxOf(0, m.range.first), m.range.last + 1)
            if (!Regex("""\d\s*(kg|g|gm|ml|l|ltr)\b""").containsMatchIn(after)) { max = value; s = s.replaceRange(m.range, " ") }
        }
        if (max == null) MAX_TAK.find(s)?.let { m ->
            val value = amount(m.groupValues[1], m.groupValues[2])
            if (value >= 20.0) { max = value; s = s.replaceRange(m.range, " ") }
        }
        if (max == null) RS_PREFIX.find(s)?.let { m ->
            max = m.groupValues[1].toDoubleOrNull(); s = s.replaceRange(m.range, " ")
        }
        if (min == null) (MIN_WORD.find(s) ?: MIN_HINGLISH.find(s))?.let { m ->
            min = amount(m.groupValues[1], m.groupValues[2]); s = s.replaceRange(m.range, " ")
        }
        return Budget(s.replace(Regex("\\s+"), " ").trim(), min, max)
    }

    private fun buildRequest(input: String, min: Double?, max: Double?): BlinkitRequest {
        var s = input

        // Sort preference. Value words win over generic "good/best".
        var sort = BlinkitSort.RELEVANCE
        when {
            SORT_UNIT.containsMatchIn(s) -> { sort = BlinkitSort.UNIT_PRICE; s = SORT_UNIT.replace(s, " ") }
            SORT_VALUE.containsMatchIn(s) -> { sort = BlinkitSort.VALUE; s = SORT_VALUE.replace(s, " ") }
            SORT_LOW.containsMatchIn(s) -> { sort = BlinkitSort.PRICE_LOW; s = SORT_LOW.replace(s, " ") }
            SORT_RATING.containsMatchIn(s) -> { sort = BlinkitSort.RATING; s = SORT_RATING.replace(s, " ") }
            SORT_GOOD.containsMatchIn(s) -> sort = BlinkitSort.VALUE
        }
        // A budget with "best/good" means best value inside the budget, not just the cheapest.
        if (sort == BlinkitSort.RELEVANCE && max != null) sort = BlinkitSort.VALUE

        // Pack size: kept in the search words, never read as a quantity.
        var packHint: String? = null
        PACK_HINT.find(s)?.let { m -> packHint = m.groupValues[1] + " " + m.groupValues[2] }

        // Quantity.
        var quantity = 1
        val q1 = QTY_LABEL.find(s)
        val q2 = QTY_UNIT.find(s)
        when {
            q1 != null -> { quantity = q1.groupValues[1].toInt(); s = s.replaceRange(q1.range, " ") }
            q2 != null -> {
                val w = q2.groupValues[1]
                quantity = w.toIntOrNull() ?: NUMBER_WORDS[w] ?: 1
                if (q2.value.endsWith("dozen")) quantity = 1
                else s = s.replaceRange(q2.range, " ")
            }
            else -> QTY_LEADING.find(s.trim())?.let { m ->
                val rest = s.trim().substring(m.range.last + 1)
                if (!PACK_HINT.containsMatchIn(m.value + rest.take(6))) { quantity = m.groupValues[1].toInt(); s = rest }
            }
        }
        quantity = quantity.coerceIn(1, 20)

        // Brand.
        var brand: String? = null
        for (b in BRANDS) {
            if (Regex("""\b${Regex.escape(b)}\b""").containsMatchIn(s)) { brand = b; break }
        }

        // Words: Hinglish → English, then spelling repair, then filler removal.
        for ((from, to) in ALIASES) s = Regex("""\b${Regex.escape(from)}\b""").replace(s, to)
        val words = s.split(Regex("[^\\p{L}\\p{N}.&-]+")).filter { it.isNotBlank() }
        val corrected = words.map { correct(it) }
        val kept = corrected.filterNot { it in STOPWORDS }

        // Vague ask: nothing concrete left, only a theme word such as "breakfast".
        val joined = corrected.joinToString(" ")
        val concrete = kept.filterNot { w -> VAGUE.any { (r, _) -> r.matches(w) } }.filter { it.any(Char::isLetter) }
        val theme = VAGUE.firstOrNull { (r, _) -> r.containsMatchIn(joined) }
        if (theme != null && concrete.isEmpty()) {
            return BlinkitRequest(
                query = theme.second.label, minPrice = min, maxPrice = max, quantity = quantity, sort = sort,
                brand = brand, packHint = packHint, vague = theme.second
            )
        }

        var query = kept.joinToString(" ").trim().trim('.', '-', '&')
        if (packHint != null && !query.contains(packHint!!)) query = "$query ${packHint!!}".trim()
        if (query.isBlank()) query = corrected.filterNot { it == "blinkit" }.joinToString(" ").trim()
        return BlinkitRequest(query, min, max, quantity, sort, brand, packHint, null)
    }

    // ---- Spelling repair -----------------------------------------------------------------------------

    /** Fixes a one-letter slip against common grocery words; leaves unknown words and brands alone. */
    fun correct(word: String): String {
        ALIASES.firstOrNull { it.first == word }?.let { return it.second }
        if (word.length < 5 || word.any { it.isDigit() } || word in VOCAB || word in STOPWORDS) return word
        if (BRANDS.any { it.contains(word) }) return word
        var best: String? = null
        var bestDistance = Int.MAX_VALUE
        var ties = 0
        for (v in VOCAB) {
            if (v.length < 4 || v[0] != word[0] || kotlin.math.abs(v.length - word.length) > 2) continue
            // "milky" starts with "milk": a longer real word, not a typo of the shorter one.
            if (word.length > v.length && word.startsWith(v)) continue
            val d = distance(word, v)
            val allowed = if (word.length >= 8) 2 else 1
            if (d <= allowed) {
                if (d < bestDistance) { best = v; bestDistance = d; ties = 1 } else if (d == bestDistance) ties++
            }
        }
        return if (best != null && ties == 1) best else word
    }

    /** Edit distance counting a swap of two neighbouring letters as one edit. */
    internal fun distance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
        }
        return d[a.length][b.length]
    }

    private fun normalize(raw: String): String {
        var s = raw.lowercase(Locale.ROOT)
        val sb = StringBuilder(s.length)
        for (ch in s) sb.append(if (ch in '०'..'९') ('0' + (ch - '०')) else ch)
        s = sb.toString()
        s = s.replace("₹", " rs ")
        s = Regex("""\b(?:rupees|rupee|rupaye|rupaiye|rupay|inr)\b""").replace(s, " rs ")
        s = Regex("""\brs\.?\s*""").replace(s, "rs ")
        s = Regex("""(?<=\d),(?=\d{2,3}\b)""").replace(s, "")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    private fun amount(number: String, unit: String): Double {
        val base = number.toDoubleOrNull() ?: 0.0
        val mult = when (unit) { "k", "thousand", "hazaar", "hazar", "hajar" -> 1_000.0; else -> 1.0 }
        return base * mult
    }
}
