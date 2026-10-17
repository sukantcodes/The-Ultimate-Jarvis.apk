package com.jarvis.assistant.studio

import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Matches what you said against phrase patterns such as "call {contact} with sim {sim}". Words are compared
 * ignoring capitals and punctuation, small spelling or hearing mistakes are tolerated in longer words, and
 * {variables} capture whatever you said in that place.
 */
object PhraseMatcher {
    private const val MAX_VAR_TOKENS = 12
    private const val MAX_INPUT_TOKENS = 18
    private const val MIN_AVERAGE = 0.8

    private val leadingFillers = setOf("jarvis", "hey", "hi", "hello", "ok", "okay", "please", "plz", "kindly")
    private val trailingFillers = setOf("please", "plz", "ji", "sir", "maam")
    private val variableToken = Regex("^\\{([A-Za-z_][A-Za-z0-9_]*)\\}$")

    /** What you said, as lower-case words for matching and as written words for filling in variables. */
    class Spoken(val norm: List<String>, val display: List<String>)

    class Pattern(val tokens: List<String>, val literalCount: Int)

    class Result(val average: Double, val literals: Int, val variables: Map<String, String>)

    private fun clean(raw: String): String = raw.trim { !it.isLetterOrDigit() }

    private fun normalize(word: String): String =
        word.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")

    fun tokenize(text: String): Spoken? {
        val norm = ArrayList<String>()
        val display = ArrayList<String>()
        for (raw in text.trim().split(Regex("\\s+"))) {
            val shown = clean(raw)
            val n = normalize(shown)
            if (n.isEmpty()) continue
            norm.add(n)
            display.add(shown)
        }
        while (norm.isNotEmpty() && norm.first() in leadingFillers) {
            norm.removeAt(0)
            display.removeAt(0)
        }
        while (norm.isNotEmpty() && norm.last() in trailingFillers) {
            norm.removeAt(norm.size - 1)
            display.removeAt(display.size - 1)
        }
        if (norm.isEmpty() || norm.size > MAX_INPUT_TOKENS) return null
        return Spoken(norm, display)
    }

    /** Turns a phrase pattern into words and {variables}. Returns null when it has no fixed words at all. */
    fun compile(phrase: String): Pattern? {
        val tokens = ArrayList<String>()
        var literals = 0
        for (raw in phrase.trim().split(Regex("\\s+"))) {
            if (raw.isEmpty()) continue
            val variable = variableToken.matchEntire(raw.trim { it == '.' || it == ',' || it == '!' || it == '?' })
            if (variable != null) {
                tokens.add("{" + variable.groupValues[1].lowercase(Locale.ROOT) + "}")
            } else {
                val word = normalize(raw)
                if (word.isNotEmpty()) {
                    tokens.add(word)
                    literals++
                }
            }
        }
        if (literals == 0) return null
        return Pattern(tokens, literals)
    }

    private fun isVariable(token: String) = token.length > 2 && token.first() == '{' && token.last() == '}'

    private fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = min(min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    /** 1.0 for the same word, 0.7 for a close spelling of a longer word, 0 otherwise. */
    private fun literalScore(expected: String, heard: String): Double {
        if (expected == heard) return 1.0
        if (expected.length >= 4 && heard.length >= 4) {
            val allowed = if (max(expected.length, heard.length) >= 8) 2 else 1
            if (editDistance(expected, heard) <= allowed) return 0.7
        }
        return 0.0
    }

    fun match(pattern: Pattern, spoken: Spoken): Result? {
        val m = pattern.tokens.size
        val n = spoken.norm.size
        val impossible = Double.NEGATIVE_INFINITY
        val best = Array(m + 1) { DoubleArray(n + 1) { Double.NaN } }
        val takes = Array(m + 1) { IntArray(n + 1) }

        fun solve(pi: Int, ti: Int): Double {
            val cached = best[pi][ti]
            if (!cached.isNaN()) return cached
            var result = impossible
            if (pi == m) {
                result = if (ti == n) 0.0 else impossible
            } else {
                val token = pattern.tokens[pi]
                if (isVariable(token)) {
                    val isLast = pi == m - 1
                    val maxTake = min(MAX_VAR_TOKENS, n - ti)
                    for (k in 1..maxTake) {
                        if (isLast && k != n - ti) continue
                        val rest = solve(pi + 1, ti + k)
                        if (rest == impossible) continue
                        val candidate = rest - 0.001 * k
                        if (candidate > result) {
                            result = candidate
                            takes[pi][ti] = k
                        }
                    }
                } else if (ti < n) {
                    val score = literalScore(token, spoken.norm[ti])
                    if (score > 0.0) {
                        val rest = solve(pi + 1, ti + 1)
                        if (rest != impossible) {
                            result = rest + score
                            takes[pi][ti] = 1
                        }
                    }
                }
            }
            best[pi][ti] = result
            return result
        }

        if (solve(0, 0) == impossible) return null

        val variables = LinkedHashMap<String, String>()
        var literalSum = 0.0
        var pi = 0
        var ti = 0
        while (pi < m) {
            val token = pattern.tokens[pi]
            if (isVariable(token)) {
                val k = takes[pi][ti]
                variables[token.substring(1, token.length - 1)] = spoken.display.subList(ti, ti + k).joinToString(" ")
                ti += k
            } else {
                literalSum += literalScore(token, spoken.norm[ti])
                ti++
            }
            pi++
        }
        val average = literalSum / pattern.literalCount
        if (average < MIN_AVERAGE) return null
        return Result(average, pattern.literalCount, variables)
    }
}
