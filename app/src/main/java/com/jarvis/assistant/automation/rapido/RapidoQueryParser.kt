package com.jarvis.assistant.automation.rapido

import java.util.Locale

/**
 * Understands a ride request said in English, Hinglish or a little Devanagari:
 * "mujhe bike book karo Indiranagar se Koramangala", "cheapest auto to the airport", "HSR tak cab 300 ke neeche".
 *
 * It only extracts what was said. It never invents a place: if no destination can be found, [RideRequest.drop] is null
 * and the assistant must ask. Place names keep the user's own spelling and capitalisation.
 */
object RapidoQueryParser {
    private val I = RegexOption.IGNORE_CASE

    // Devanagari words that matter, mapped token by token so place names in Hindi script are left alone.
    private val DEVANAGARI = mapOf(
        "से" to "se", "तक" to "tak", "बाइक" to "bike", "ऑटो" to "auto", "ऑटोरिक्शा" to "auto", "कैब" to "cab", "टैक्सी" to "taxi",
        "सस्ता" to "sasta", "सस्ती" to "sasti", "जल्दी" to "jaldi", "बुक" to "book", "करो" to "karo", "कर" to "kar", "दो" to "do",
        "जाना" to "jana", "जाने" to "jane", "चलो" to "chalo", "है" to "hai", "मुझे" to "mujhe", "कल" to "kal", "अभी" to "abhi"
    )

    private val FILLER = setOf(
        "book", "booking", "ride", "rides", "karo", "kar", "karna", "do", "dijiye", "dena", "de", "me", "mujhe", "mera", "meri", "mere",
        "please", "pls", "plz", "chahiye", "chahie", "hai", "hain", "ek", "a", "an", "the", "bike", "taxi", "auto", "cab", "car", "rapido",
        "abhi", "now", "i", "need", "want", "get", "find", "show", "check", "fare", "price", "rate", "kitna", "kitne", "ka", "ki", "ko",
        "ke", "liye", "jao", "jana", "jaana", "jane", "chalo", "hume", "humein", "hamein", "go", "going", "from", "to", "at", "and", "then",
        "se", "tak", "yahan", "here", "my", "your", "on", "via", "sasta", "sasti", "cheapest", "cheap", "fastest", "fast", "jaldi", "urgent",
        "ola", "uber", "by", "using", "karwao", "karwa", "lagao", "laga", "pahunchna", "pahuchna", "reach", "take", "drop", "bhai", "yaar", "jarvis"
    )

    private val CURRENT_LOCATION = Regex(
        """^(?:my |mera |meri |mere )?(?:current |abhi ki )?(?:location|loc|place)$|^(?:here|yahan|yahaan|yaha|current location|my location|mere paas|mere location)$""", I
    )

    /** Understands the sentence. Explicit tool fields are layered on top with [merge]. */
    fun parse(said: String): RideRequest {
        var work = scriptToLatin(said).replace(Regex("[,;|]+"), " ").replace(Regex("\\s+"), " ").trim()
        if (work.isEmpty()) return RideRequest(drop = null)

        // 1. budget ----------------------------------------------------------------------------
        var budget: Double? = null
        for (r in BUDGET_PATTERNS) {
            val m = r.find(work) ?: continue
            budget = m.groupValues.drop(1).firstOrNull { it.isNotBlank() && it.all { c -> c.isDigit() } }?.toDoubleOrNull()
            if (budget != null) { work = work.removeRange(m.range).trim(); break }
        }

        // 2. a note for the captain, then the time ------------------------------------------------
        var note: String? = null
        NOTE.find(work)?.let { m ->
            note = m.groupValues[1].trim().ifBlank { null }
            work = work.removeRange(m.range).trim()
        }
        val scheduleBits = ArrayList<String>()
        for (r in SCHEDULE_PATTERNS) {
            r.findAll(work).toList().asReversed().forEach { m ->
                scheduleBits.add(0, m.value.trim())
                work = work.removeRange(m.range)
            }
        }
        work = work.replace(Regex("\\s+"), " ").trim()

        // 3. stops ("via Lalbagh", "Lalbagh hote hue") ----------------------------------------------
        val stops = ArrayList<String>()
        VIA_EN.findAll(work).toList().asReversed().forEach { m ->
            cleanPlace(m.groupValues[1], trimLeading = false)?.let { stops.add(0, it) }
            work = work.removeRange(m.range)
        }
        VIA_HI.findAll(work).toList().asReversed().forEach { m ->
            cleanPlace(m.groupValues[1], trimLeading = true)?.let { stops.add(0, it) }
            work = work.removeRange(m.range)
        }
        work = work.replace(Regex("\\s+"), " ").trim()

        // 4. places -----------------------------------------------------------------------------
        var pickup: String? = null
        var drop: String? = null
        val from = FROM_TO_EN.find(work)
        val se = SE_PATTERN.find(work)
        val toOnly = TO_ONLY_EN.find(work)
        val tak = TAK_PATTERN.find(work)
        when {
            // After "from"/"to" the words ARE the place, so nothing is trimmed from the front ("to Car Street").
            from != null -> { pickup = cleanPlace(from.groupValues[1], trimLeading = false); drop = cleanPlace(from.groupValues[2], trimLeading = false) }
            // Before "se"/"tak" the sentence still holds command words ("mujhe bike book karo Indiranagar"), so they are trimmed.
            se != null -> { pickup = cleanPlace(se.groupValues[1], trimLeading = true); drop = cleanPlace(se.groupValues[2], trimLeading = false) }
            toOnly != null -> drop = cleanPlace(toOnly.groupValues[1], trimLeading = false)
            tak != null -> drop = cleanPlace(tak.groupValues[1], trimLeading = true)
        }
        if (pickup != null && CURRENT_LOCATION.matches(pickup.trim())) pickup = null

        // 5. ride type and priority come only from the words that are NOT part of a place name ("Auto Nagar", "Car Street").
        var rest = work
        listOfNotNull(pickup, drop).plus(stops).forEach { place ->
            rest = Regex(Regex.escape(place), I).replace(rest, " ")
        }
        val vehicle = vehicleFrom(rest)
        val tier = if (vehicle == VehicleCategory.CAB || vehicle == null) cabTierFrom(rest) else null
        val cheap = CHEAP.containsMatchIn(rest)
        val fast = FAST.containsMatchIn(rest)
        val priority = when {
            cheap && !fast -> RidePriority.CHEAPEST
            fast && !cheap -> RidePriority.FASTEST
            else -> RidePriority.BALANCED
        }

        return RideRequest(
            drop = drop,
            pickup = pickup,
            stops = stops,
            vehicle = vehicle,
            cabTier = tier,
            priority = priority,
            maxBudget = budget,
            scheduledText = scheduleBits.joinToString(" ").trim().ifBlank { null },
            pickupNote = note
        )
    }

    /** Applies explicit tool arguments over what the sentence said. Blank or null arguments change nothing. */
    fun merge(
        base: RideRequest,
        drop: String? = null,
        pickup: String? = null,
        stops: List<String>? = null,
        vehicle: VehicleCategory? = null,
        cabTier: String? = null,
        priority: RidePriority? = null,
        maxBudget: Double? = null,
        scheduledText: String? = null,
        pickupNote: String? = null
    ): RideRequest {
        val pick = pickup?.trim()?.takeIf { it.isNotEmpty() }
        return base.copy(
            drop = drop?.trim()?.takeIf { it.isNotEmpty() } ?: base.drop,
            pickup = if (pick != null && CURRENT_LOCATION.matches(pick)) null else pick ?: base.pickup,
            stops = stops?.map { it.trim() }?.filter { it.isNotEmpty() } ?: base.stops,
            vehicle = vehicle ?: base.vehicle,
            cabTier = cabTier?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: base.cabTier,
            priority = priority ?: base.priority,
            maxBudget = maxBudget?.takeIf { it > 0 } ?: base.maxBudget,
            scheduledText = scheduledText?.trim()?.takeIf { it.isNotEmpty() } ?: base.scheduledText,
            pickupNote = pickupNote?.trim()?.takeIf { it.isNotEmpty() } ?: base.pickupNote
        )
    }

    fun vehicleFromText(text: String): VehicleCategory? = vehicleFrom(scriptToLatin(text))

    fun priorityFromText(text: String): RidePriority? {
        val t = scriptToLatin(text).trim()
        if (t.isEmpty()) return null
        return when (t.lowercase(Locale.ROOT).replace(' ', '_')) {
            "cheapest", "cheap", "lowest", "price", "budget" -> RidePriority.CHEAPEST
            "fastest", "fast", "quickest", "time", "urgent" -> RidePriority.FASTEST
            "balanced", "best", "default" -> RidePriority.BALANCED
            else -> null
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private fun scriptToLatin(s: String): String =
        s.split(' ').joinToString(" ") { DEVANAGARI[it.trim('।', '.', '?', '!')] ?: it }

    private fun vehicleFrom(text: String): VehicleCategory? = when {
        BIKE.containsMatchIn(text) -> VehicleCategory.BIKE
        AUTO.containsMatchIn(text) -> VehicleCategory.AUTO
        CAB.containsMatchIn(text) -> VehicleCategory.CAB
        else -> null
    }

    private fun cabTierFrom(text: String): String? = when {
        Regex("""\b(?:premium|prime|sedan|comfort)\b""", I).containsMatchIn(text) -> "premium"
        Regex("""\b(?:xl|suv|6 seater|six seater|innova)\b""", I).containsMatchIn(text) -> "xl"
        Regex("""\b(?:economy|mini|hatchback|cheap cab|sasti cab)\b""", I).containsMatchIn(text) -> "economy"
        else -> null
    }

    /** Removes filler words from both ends only, so a place name such as "Car Street" keeps its inner words. */
    private fun cleanPlace(raw: String, trimLeading: Boolean = true): String? {
        val words = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
        if (trimLeading) while (words.isNotEmpty() && words.first().lowercase(Locale.ROOT).trim('.', '!', '?') in FILLER) words.removeAt(0)
        while (words.isNotEmpty() && words.last().lowercase(Locale.ROOT).trim('.', '!', '?') in FILLER) words.removeAt(words.size - 1)
        val text = words.joinToString(" ").trim('.', '!', '?', ' ')
        return text.ifBlank { null }
    }

    private val BIKE = Regex("""\b(?:bike taxi|bike|motorcycle|two wheeler|2 wheeler|moto)\b""", I)
    private val AUTO = Regex("""\b(?:auto rickshaw|autorickshaw|auto|rickshaw|riksha|tuk tuk)\b""", I)
    private val CAB = Regex("""\b(?:cab|taxi|car|sedan|suv|hatchback|innova)\b""", I)
    private val CHEAP = Regex("""\b(?:cheapest|cheap|sasta|sasti|sabse sasta|sabse sasti|kam kharcha|low cost|lowest fare|lowest price|budget friendly)\b""", I)
    private val FAST = Regex("""\b(?:fastest|quickest|quickly|quick|jaldi|jldi|turant|urgent|urgently|asap|emergency|late ho raha|late ho rha|sabse jaldi)\b""", I)

    // "under 200", "max 150", "300 ke neeche", "₹250 tak", "250 rupees se kam"
    private val BUDGET_PATTERNS = listOf(
        Regex("""\b(?:under|below|within|upto|up to|max(?:imum)?|at most|not more than|less than)\s*(?:rs\.?|₹|inr)?\s*(\d{2,5})\b""", I),
        Regex("""(?:₹|\brs\.?|\binr)\s*(\d{2,5})\s*(?:ke neeche|ke andar|se kam|tak|se zyada nahi|max)\b""", I),
        Regex("""\b(\d{2,5})\s*(?:rupees|rupaye|rupay|rs|₹)?\s*(?:ke neeche|ke andar|se kam|se niche|se neeche)\b""", I),
        Regex("""\b(\d{2,5})\s*(?:rupees|rupaye|rupay|rs|₹)\s*tak\b""", I)
    )

    private val NOTE = Regex("""\b(?:pickup note|pick up note|note)\s*[:\-]?\s*(.+)$""", I)

    private val SCHEDULE_PATTERNS = listOf(
        Regex("""\b(?:subah|shaam|raat|dopahar|morning|evening|tonight|afternoon)\b(?=.*\b(?:\d{1,2}(?::\d{2})?\s*(?:am|pm|baje)|kal|tomorrow|schedule)\b)""", I),
        Regex("""\b(?:day after tomorrow|tomorrow|parso|kal|next week|schedule(?:d)?(?: for)?|baad mein|baad me|later)\b""", I),
        Regex("""\b(?:at|around|by|ko)?\s*\d{1,2}(?::\d{2})?\s*(?:am|pm|baje)\b""", I)
    )

    private val VIA_EN = Regex("""\b(?:via|stop at|stopping at|stop by|through|passing through)\s+(.+?)(?=\s+(?:to|then|and|se|tak|jana|jaana|chalo)\b|$)""", I)
    // Only the (up to two) words right before the phrase are the stop; a lazy ".+?" would swallow the whole sentence.
    private val VIA_HI = Regex("""(\S+(?:\s+\S+)?)\s+(?:hote hue|hote huye|ke raste se|ke raste|via hokar|hokar|ke through)\b""", I)

    private val FROM_TO_EN = Regex("""\bfrom\s+(.+?)\s+to\s+(.+)$""", I)
    private val SE_PATTERN = Regex("""^(.+?)\s+se\s+(.+?)(?:\s+(?:tak|jana|jaana|jane|chalo|jaake|pahunchna|pahuchna|ke liye)\b.*)?$""", I)
    private val TO_ONLY_EN = Regex("""\b(?:to|towards|till|until|drop(?: me)?(?: at| to)?|take me to|go to|reach|heading to)\s+(.+)$""", I)
    private val TAK_PATTERN = Regex("""^(.+?)\s+(?:tak|jana|jaana|jane|jaake|chalo|pahunchna|pahuchna|ke liye)\b""", I)
}
