package com.jarvis.assistant.study

import kotlin.math.abs
import kotlin.math.max

/**
 * A small, strict expression evaluator used for two things only: re-checking numeric arithmetic in a lesson step, and
 * sampling a function for a graph. It returns null whenever it is not completely sure what the text means, so it can
 * never produce a false "this is wrong".
 */
object StudyExpression {

    private const val MAX_DEPTH = 40

    fun evaluate(text: String, vars: Map<String, Double> = emptyMap(), implicitMultiply: Boolean = false): Double? {
        val normalized = normalize(text) ?: return null
        if (normalized.isEmpty()) return null
        return try {
            val p = Parser(normalized, vars, implicitMultiply)
            val v = p.parseExpression(0)
            if (p.pos != normalized.length) null else v.takeIf { it.isFinite() }
        } catch (_: Failure) {
            null
        } catch (_: StackOverflowError) {
            null
        }
    }

    /** Rewrites LaTeX-ish and unicode notation into plain ASCII arithmetic, or returns null if it contains something unknown. */
    fun normalize(raw: String): String? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        s = replaceLatexCommands(s, 0) ?: return null
        val sb = StringBuilder()
        for (ch in s) {
            when (ch) {
                ' ', '\t', '\n', '\r', ' ', ' ' -> Unit
                '−', '–', '—' -> sb.append('-')
                '×', '⋅', '·', '∗' -> sb.append('*')
                '÷' -> sb.append('/')
                'π' -> sb.append("pi")
                '²' -> sb.append("^2")
                '³' -> sb.append("^3")
                '{' -> sb.append('(')
                '}' -> sb.append(')')
                '[' -> sb.append('(')
                ']' -> sb.append(')')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun replaceLatexCommands(input: String, depth: Int): String? {
        if (depth > 12) return null
        val out = StringBuilder()
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c != '\\') {
                if (c == '√') {
                    // unicode square root: either a bracketed group or a number
                    var j = i + 1
                    if (j < input.length && (input[j] == '(' || input[j] == '{')) {
                        val group = readGroup(input, j) ?: return null
                        val inner = replaceLatexCommands(group.first, depth + 1) ?: return null
                        out.append("sqrt(").append(inner).append(')')
                        i = group.second
                        continue
                    }
                    val start = j
                    while (j < input.length && (input[j].isDigit() || input[j] == '.')) j++
                    if (j == start) return null
                    out.append("sqrt(").append(input, start, j).append(')')
                    i = j
                    continue
                }
                out.append(c)
                i++
                continue
            }
            var j = i + 1
            val nameStart = j
            while (j < input.length && input[j].isLetter()) j++
            val name = input.substring(nameStart, j)
            if (name.isEmpty()) {
                // escaped symbol such as "\," or "\%"
                if (j < input.length) {
                    val sym = input[j]
                    if (sym == ',' || sym == ';' || sym == ' ' || sym == '!' || sym == ':') { i = j + 1; continue }
                    return null
                }
                return null
            }
            when (name) {
                "frac", "dfrac", "tfrac" -> {
                    val a = readGroup(input, skipSpaces(input, j)) ?: return null
                    val b = readGroup(input, skipSpaces(input, a.second)) ?: return null
                    val na = replaceLatexCommands(a.first, depth + 1) ?: return null
                    val nb = replaceLatexCommands(b.first, depth + 1) ?: return null
                    out.append("((").append(na).append(")/(").append(nb).append("))")
                    i = b.second
                }
                "sqrt" -> {
                    var k = skipSpaces(input, j)
                    if (k < input.length && input[k] == '[') return null // nth roots are not rechecked
                    val g = readGroup(input, k) ?: return null
                    val inner = replaceLatexCommands(g.first, depth + 1) ?: return null
                    out.append("sqrt(").append(inner).append(')')
                    i = g.second
                }
                "times", "cdot" -> { out.append('*'); i = j }
                "div" -> { out.append('/'); i = j }
                "pi" -> { out.append("pi"); i = j }
                "left", "right", "quad", "qquad" -> {
                    i = j
                    if ((name == "left" || name == "right") && i < input.length && (input[i] == '(' || input[i] == ')' || input[i] == '[' || input[i] == ']')) {
                        // keep the bracket itself
                    }
                }
                "sin", "cos", "tan", "ln", "log", "exp", "arcsin", "arccos", "arctan" -> {
                    out.append(
                        when (name) { "arcsin" -> "asin"; "arccos" -> "acos"; "arctan" -> "atan"; else -> name }
                    )
                    i = j
                }
                else -> return null
            }
        }
        return out.toString()
    }

    private fun skipSpaces(s: String, from: Int): Int {
        var k = from
        while (k < s.length && s[k] == ' ') k++
        return k
    }

    /** Reads a bracketed group starting at [open]; returns its inside text and the index after the closing bracket. */
    private fun readGroup(s: String, open: Int): Pair<String, Int>? {
        if (open >= s.length) return null
        val o = s[open]
        val close = when (o) { '{' -> '}'; '(' -> ')'; else -> return null }
        var depth = 0
        var k = open
        while (k < s.length) {
            if (s[k] == o) depth++
            else if (s[k] == close) {
                depth--
                if (depth == 0) return s.substring(open + 1, k) to (k + 1)
            }
            k++
        }
        return null
    }

    private class Failure : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    private class Parser(val s: String, val vars: Map<String, Double>, val implicit: Boolean) {
        var pos = 0

        fun parseExpression(depth: Int): Double {
            if (depth > MAX_DEPTH) throw Failure()
            var v = parseTerm(depth)
            while (pos < s.length && (s[pos] == '+' || s[pos] == '-')) {
                val op = s[pos++]
                val r = parseTerm(depth)
                v = if (op == '+') v + r else v - r
            }
            return v
        }

        private fun parseTerm(depth: Int): Double {
            var v = parseUnary(depth)
            while (pos < s.length) {
                val c = s[pos]
                if (c == '*') { pos++; v *= parseUnary(depth) }
                else if (c == '/') {
                    pos++
                    val d = parseUnary(depth)
                    if (d == 0.0) throw Failure()
                    v /= d
                } else if (implicit && (c.isLetter() || c.isDigit() || c == '(' || c == '.')) {
                    v *= parseUnary(depth)
                } else break
            }
            return v
        }

        private fun parseUnary(depth: Int): Double {
            if (pos < s.length && s[pos] == '-') { pos++; return -parseUnary(depth + 1) }
            if (pos < s.length && s[pos] == '+') { pos++; return parseUnary(depth + 1) }
            return parsePower(depth)
        }

        private fun parsePower(depth: Int): Double {
            val base = parseAtom(depth)
            if (pos < s.length && s[pos] == '^') {
                pos++
                val e = parseUnary(depth + 1)
                val r = Math.pow(base, e)
                if (!r.isFinite()) throw Failure()
                return r
            }
            return base
        }

        private fun parseAtom(depth: Int): Double {
            if (depth > MAX_DEPTH) throw Failure()
            if (pos >= s.length) throw Failure()
            val c = s[pos]
            if (c == '(') {
                pos++
                val v = parseExpression(depth + 1)
                if (pos >= s.length || s[pos] != ')') throw Failure()
                pos++
                return v
            }
            if (c.isDigit() || c == '.') {
                val start = pos
                while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
                val text = s.substring(start, pos)
                if (text.count { it == '.' } > 1) throw Failure()
                return text.toDoubleOrNull() ?: throw Failure()
            }
            if (c.isLetter()) {
                val start = pos
                while (pos < s.length && s[pos].isLetter()) pos++
                val name = s.substring(start, pos)
                val func = FUNCTIONS[name]
                if (func != null) {
                    if (pos >= s.length || s[pos] != '(') throw Failure()
                    pos++
                    val arg = parseExpression(depth + 1)
                    if (pos >= s.length || s[pos] != ')') throw Failure()
                    pos++
                    val r = func(arg)
                    if (!r.isFinite()) throw Failure()
                    return r
                }
                if (name == "pi") return Math.PI
                vars[name]?.let { return it }
                if (implicit && name.length > 1) {
                    // "xy" with only single-letter variables defined: treat as a product, left to right
                    var product = 1.0
                    for (ch in name) product *= vars[ch.toString()] ?: throw Failure()
                    return product
                }
                throw Failure()
            }
            throw Failure()
        }
    }

    private val FUNCTIONS: Map<String, (Double) -> Double> = mapOf(
        "sin" to { x -> Math.sin(x) },
        "cos" to { x -> Math.cos(x) },
        "tan" to { x -> Math.tan(x) },
        "asin" to { x -> Math.asin(x) },
        "acos" to { x -> Math.acos(x) },
        "atan" to { x -> Math.atan(x) },
        "sqrt" to { x -> if (x < 0) Double.NaN else Math.sqrt(x) },
        "ln" to { x -> if (x <= 0) Double.NaN else Math.log(x) },
        "log" to { x -> if (x <= 0) Double.NaN else Math.log10(x) },
        "exp" to { x -> Math.exp(x) },
        "abs" to { x -> abs(x) }
    )

    /** Half of the last printed digit of a plain decimal or "a x 10^n" literal, or null when [side] is not such a literal. */
    internal fun literalHalfUnit(side: String): Double? {
        val s = normalize(side) ?: return null
        val m = Regex("^-?(\\d+)(?:\\.(\\d+))?(?:\\*10\\^\\(?(-?\\d+)\\)?)?$").matchEntire(s) ?: return null
        val decimals = m.groupValues[2].length
        val exp = m.groupValues[3].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
        val hasSci = m.groupValues[3].isNotEmpty()
        if (decimals == 0 && !hasSci) return null // a bare whole number must be exact
        if (decimals == 0) return null
        return 0.5 * Math.pow(10.0, (exp - decimals).toDouble())
    }

    internal fun close(a: Double, b: Double, halfUnit: Double?, approx: Boolean): Boolean {
        val scale = max(abs(a), abs(b))
        if (approx) return abs(a - b) <= max(0.02 * scale, 1e-9)
        if (halfUnit != null && abs(a - b) <= halfUnit * 1.0001) return true
        return abs(a - b) <= max(1e-9, 1e-6 * scale)
    }
}
