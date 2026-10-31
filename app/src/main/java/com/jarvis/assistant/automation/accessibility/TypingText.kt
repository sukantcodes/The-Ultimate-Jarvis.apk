package com.jarvis.assistant.automation.accessibility

import java.text.Normalizer
import java.util.Locale

/**
 * Android-free helpers for the universal typing engine: how typed text is compared with what an
 * app reports back, and how candidate input fields are ranked. Kept free of framework classes so
 * it can be unit-tested on the JVM.
 */
object TypingText {

    enum class Match { EXACT, LOOSE, NONE }

    /** Zero-width / bidi marks that apps add or strip freely (ZWJ/ZWNJ included, they do not change what the user sees). */
    private val INVISIBLE = Regex("[\\u200B\\u200C\\u200D\\u200E\\u200F\\u2060\\uFEFF\\u202A-\\u202E]")
    private val WHITESPACE = Regex("\\s+")

    /** How much extra text (counter, prefix such as a currency sign, hint) a field may show around the typed text. */
    private const val DECORATION_SLACK = 32

    /** Unicode-aware normalisation used on BOTH sides of every comparison (NFC, no invisibles, single spaces, case-folded). */
    fun normalize(value: CharSequence?): String {
        if (value == null) return ""
        var s = value.toString().replace("\r\n", "\n").replace(' ', ' ')
        s = Normalizer.normalize(s, Normalizer.Form.NFC)
        s = INVISIBLE.replace(s, "")
        s = WHITESPACE.replace(s, " ").trim()
        return s.lowercase(Locale.ROOT)
    }

    private fun squash(normalized: String): String = normalized.replace(" ", "")

    /**
     * Compares what the field now holds ([current]) with what was requested ([expected]).
     * EXACT  - identical after normalisation.
     * LOOSE  - same text modulo whitespace, or the text appears exactly once inside a field that only adds a
     *          small decoration (hint, counter, currency prefix). Still a real readback of the typed text.
     * NONE   - not verified.
     */
    fun match(current: CharSequence?, expected: CharSequence?): Match {
        val e = normalize(expected)
        if (e.isEmpty()) return Match.NONE
        val c = normalize(current)
        if (c.isEmpty()) return Match.NONE
        if (c == e) return Match.EXACT
        if (squash(c) == squash(e)) return Match.LOOSE
        val first = c.indexOf(e)
        if (first >= 0 && c.indexOf(e, first + 1) < 0 && c.length <= e.length + DECORATION_SLACK) return Match.LOOSE
        return Match.NONE
    }

    fun isVerified(current: CharSequence?, expected: CharSequence?): Boolean = match(current, expected) != Match.NONE

    /**
     * Password fields expose only a mask. Count it as a readback only when the mask has exactly as many
     * characters as the requested text (the best evidence the platform allows).
     */
    fun isMaskOf(current: CharSequence?, expected: CharSequence?): Boolean {
        val c = current?.toString().orEmpty()
        val e = expected?.toString().orEmpty()
        if (c.isEmpty() || e.isEmpty()) return false
        val distinct = c.codePoints().distinct().toArray()
        if (distinct.size != 1 || distinct[0] !in intArrayOf('•'.code, '*'.code, '●'.code, '·'.code, '∙'.code)) return false
        return c.codePointCount(0, c.length) == e.codePointCount(0, e.length)
    }

    // ---------------------------------------------------------------------------------------------
    // Candidate ranking
    // ---------------------------------------------------------------------------------------------

    /** Plain-data description of an editable-looking node (no Android types). */
    data class Candidate(
        val index: Int,
        val inputFocused: Boolean,
        val focused: Boolean,
        val supportsSetText: Boolean,
        val isLeaf: Boolean,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        val width get() = (right - left).coerceAtLeast(0)
        val height get() = (bottom - top).coerceAtLeast(0)
        val centerX get() = (left + right) / 2
        val centerY get() = (top + bottom) / 2
    }

    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = (right - left).coerceAtLeast(0)
        val height get() = (bottom - top).coerceAtLeast(0)
        val centerX get() = (left + right) / 2
        val centerY get() = (top + bottom) / 2
    }

    private fun overlapArea(c: Candidate, t: Box): Long {
        val w = (minOf(c.right, t.right) - maxOf(c.left, t.left)).coerceAtLeast(0)
        val h = (minOf(c.bottom, t.bottom) - maxOf(c.top, t.top)).coerceAtLeast(0)
        return w.toLong() * h.toLong()
    }

    /** Higher is better. Focus beats geometry; geometry beats tree order. */
    fun score(c: Candidate, target: Box?): Long {
        var s = 0L
        if (c.inputFocused) s += 10_000
        if (c.focused) s += 6_000
        if (target != null && target.width > 0 && target.height > 0) {
            val overlap = overlapArea(c, target)
            if (overlap > 0) {
                val smaller = minOf(
                    c.width.toLong() * c.height.toLong(),
                    target.width.toLong() * target.height.toLong()
                ).coerceAtLeast(1L)
                s += 3_000 + (2_000L * overlap / smaller)
            } else {
                val dx = (c.centerX - target.centerX).toLong()
                val dy = (c.centerY - target.centerY).toLong()
                val distance = Math.sqrt((dx * dx + dy * dy).toDouble()).toLong()
                s -= distance / 4
            }
        }
        if (c.supportsSetText) s += 40
        if (c.isLeaf) s += 20
        return s
    }

    /** Best candidate, ties resolved by tree order (earlier wins) so results are deterministic. */
    fun pick(candidates: List<Candidate>, target: Box?): Candidate? =
        candidates.sortedWith(compareByDescending<Candidate> { score(it, target) }.thenBy { it.index }).firstOrNull()

    fun rank(candidates: List<Candidate>, target: Box?): List<Candidate> =
        candidates.sortedWith(compareByDescending<Candidate> { score(it, target) }.thenBy { it.index })
}
