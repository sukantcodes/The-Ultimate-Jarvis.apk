package com.jarvis.assistant.background

import java.util.Locale

/**
 * Understands "how much time do you have?" answers in English, Hindi (Devanagari or Roman) and Bengali:
 * "10 minutes", "ek ghanta", "आधा घंटा", "डेढ़ घंटे", "sadhe teen ghante", "1 hour 30 min", "দশ মিনিট", "no limit".
 */
internal object TimeBudget {
    const val MIN_SEC = 30
    const val MAX_SEC = 6 * 3600

    private val numberWords: Map<String, String> = buildMap {
        fun add(value: String, vararg names: String) = names.forEach { put(it, value) }
        add("1", "one", "ek", "एक", "এক")
        add("2", "two", "do", "दो", "দুই", "দুটি")
        add("3", "three", "teen", "तीन", "তিন")
        add("4", "four", "char", "chaar", "चार", "চার")
        add("5", "five", "paanch", "panch", "पांच", "पाँच", "পাঁচ")
        add("6", "six", "chhe", "che", "छह", "छः", "ছয়")
        add("7", "seven", "saat", "सात", "সাত")
        add("8", "eight", "aath", "आठ", "আট")
        add("9", "nine", "nau", "नौ", "নয়")
        add("10", "ten", "das", "दस", "দশ")
        add("11", "eleven", "gyarah", "ग्यारह", "এগারো")
        add("12", "twelve", "barah", "बारह", "বারো")
        add("15", "fifteen", "pandrah", "पंद्रह", "পনেরো", "পনের")
        add("20", "twenty", "bees", "बीस", "বিশ")
        add("25", "pachees", "पच्चीस", "পঁচিশ")
        add("30", "thirty", "tees", "तीस", "ত্রিশ")
        add("40", "forty", "chalis", "चालीस", "চল্লিশ")
        add("45", "paintalis", "पैंतालीस", "পঁয়তাল্লিশ")
        add("50", "fifty", "pachas", "पचास", "পঞ্চাশ")
        add("60", "sixty", "saath", "साठ", "ষাট")
        add("1.5", "dedh", "डेढ़", "डेढ", "দেড়")
        add("2.5", "dhai", "ढाई", "আড়াই")
        add("0.5", "aadha", "adha", "आधा", "আধা", "আধ")
    }

    private val wordPattern: Regex = Regex(
        "(?<![\\p{L}\\p{M}])(" + numberWords.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + ")(?![\\p{L}\\p{M}])"
    )
    private val tensAndUnits = Regex("(twenty|thirty|forty|fifty)[\\s-]+(one|two|three|four|five|six|seven|eight|nine)")
    private val tensValue = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50)
    private val unitValue = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9)

    private const val SEC = "seconds?|secs?|sekand|sekend|सेकंड|सेकण्ड|সেকেন্ড"
    private const val MIN = "minutes?|mins?|mint|minat|minit|मिनट|मिनिट|মিনিট"
    private const val HOUR = "hours?|hrs?|hr|h|ghanta|ghante|ghantey|ghanti|घंटा|घंटे|घंटों|घण्टा|घण्टे|ঘণ্টা|ঘন্টা|ঘণ্টায়|ঘন্টায়"
    private fun unitRegex(units: String) = Regex("(\\d+(?:\\.\\d+)?)\\s*(?:$units)(?![\\p{L}\\p{M}])")
    private val secRegex = unitRegex(SEC)
    private val minRegex = unitRegex(MIN)
    private val hourRegex = unitRegex(HOUR)

    private val unlimited = listOf(
        "no limit", "no time limit", "unlimited", "take your time", "as long as", "no rush", "jitna time", "jitna samay",
        "koi limit nahi", "koi jaldi nahi", "limit nahi", "कोई लिमिट नहीं", "कोई जल्दी नहीं", "जितना समय", "जितना टाइम", "লিমিট নেই",
        "যত সময়", "কোনো তাড়া নেই", "সীমা নেই"
    )

    /**
     * Seconds the owner allows, 0 when they said there is no limit, or null when no time can be read.
     * With [requireUnit] a bare number is not a time (used on the first request, where numbers may mean anything).
     */
    fun parse(raw: String, requireUnit: Boolean = false): Int? {
        var s = raw.lowercase(Locale.ROOT).map { if (it.isDigit() && it !in '0'..'9') Character.digit(it, 10).digitToChar() else it }.joinToString("")
        val typedDigits = s
        s = tensAndUnits.replace(s) { m -> ((tensValue[m.groupValues[1]] ?: 0) + (unitValue[m.groupValues[2]] ?: 0)).toString() }
        // "half an hour" / "half hour" only: "one and a half hours" must reach the "N and a half" rule below untouched.
        s = Regex("(?<![\\p{L}\\p{M}])half\\s+(?:an?\\s+)?(?:hour|hr)(?![\\p{L}\\p{M}])").replace(s, "30 minutes")
        s = wordPattern.replace(s) { m -> numberWords[m.groupValues[1]] ?: m.value }
        s = Regex("(?<![\\p{L}\\p{M}\\d.])an?\\s+(hours?|hrs?|hr|minutes?|mins?)").replace(s) { m -> "1 ${m.groupValues[1]}" }
        s = Regex("(?<![\\p{L}\\p{M}])(?:sadhe|sade|साढ़े|साढे|সাড়ে)\\s*(\\d+)").replace(s) { m -> "${m.groupValues[1]}.5" }
        s = Regex("(\\d+)\\s+and\\s+(?:a\\s+)?half").replace(s) { m -> "${m.groupValues[1]}.5" }
        s = Regex("(\\d+)\\s*(hours?|hrs?|hr|ghanta|ghante|घंटा|घंटे|ঘণ্টা|ঘন্টা)\\s+and\\s+(?:a\\s+)?half").replace(s) { m -> "${m.groupValues[1]}.5 ${m.groupValues[2]}" }

        s = Regex("(?<![\\p{L}\\p{M}])half(?![\\p{L}\\p{M}])").replace(s, "0.5")

        fun sum(r: Regex) = r.findAll(s).sumOf { it.groupValues[1].toDouble() }
        val total = sum(secRegex) + sum(minRegex) * 60 + sum(hourRegex) * 3600
        if (total > 0) return total.toInt().coerceIn(MIN_SEC, MAX_SEC)
        if (!requireUnit) {
            // A bare number counts as minutes, but only when the reply is just that number or the owner really typed digits.
            val bare = if (Regex("\\s*\\d+(?:\\.\\d+)?\\s*").matches(s)) s else Regex("\\d+(?:\\.\\d+)?").find(typedDigits)?.value
            bare?.trim()?.toDoubleOrNull()?.let { return (it * 60).toInt().coerceIn(MIN_SEC, MAX_SEC) }
        }
        if (!requireUnit && unlimited.any { s.contains(it) }) return 0
        return null
    }
}
