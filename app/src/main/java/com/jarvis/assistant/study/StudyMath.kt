package com.jarvis.assistant.study

/**
 * Native typesetter for the LaTeX subset that school-level lessons use: fractions, powers, subscripts, roots, Greek letters,
 * operators, arrows, units and chemical formulas. It produces [DrawOp]s directly, so the animation engine can write the
 * equation stroke by stroke and no WebView or bundled font is needed. Anything it does not recognise is written as plain
 * text, never dropped, so a formula can never silently lose part of its meaning.
 */
object StudyMath {

    class Box(val w: Float, val asc: Float, val desc: Float, val ops: List<DrawOp>) {
        val h: Float get() = asc + desc
    }

    private sealed class Node {
        class Sym(val text: String, val roman: Boolean) : Node()
        class Frac(val n: List<Node>, val d: List<Node>) : Node()
        class Sup(val base: Node?, val sup: List<Node>) : Node()
        class Sub(val base: Node?, val sub: List<Node>) : Node()
        class SupSub(val base: Node?, val sub: List<Node>, val sup: List<Node>) : Node()
        class Root(val body: List<Node>, val index: List<Node>?) : Node()
        class Space(val em: Float) : Node()
    }

    private val SYMBOLS: Map<String, String> = mapOf(
        "times" to "×", "cdot" to "·", "div" to "÷", "pm" to "±", "mp" to "∓",
        "leq" to "≤", "le" to "≤", "geq" to "≥", "ge" to "≥", "neq" to "≠", "ne" to "≠",
        "approx" to "≈", "equiv" to "≡", "propto" to "∝", "sim" to "∼",
        "infty" to "∞", "degree" to "°", "circ" to "°", "partial" to "∂", "nabla" to "∇",
        "rightarrow" to "→", "to" to "→", "leftarrow" to "←", "Rightarrow" to "⇒", "implies" to "⇒",
        "Leftrightarrow" to "⇔", "leftrightarrow" to "↔", "uparrow" to "↑", "downarrow" to "↓",
        "rightleftharpoons" to "⇌", "therefore" to "∴", "because" to "∵", "angle" to "∠",
        "perp" to "⊥", "parallel" to "∥", "in" to "∈", "notin" to "∉", "subset" to "⊂",
        "cup" to "∪", "cap" to "∩", "forall" to "∀", "exists" to "∃",
        "sum" to "Σ", "prod" to "Π", "int" to "∫", "ldots" to "…", "cdots" to "⋯", "dots" to "…",
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε",
        "varepsilon" to "ε", "zeta" to "ζ", "eta" to "η", "theta" to "θ", "iota" to "ι",
        "kappa" to "κ", "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ", "pi" to "π",
        "rho" to "ρ", "sigma" to "σ", "tau" to "τ", "upsilon" to "υ", "phi" to "φ",
        "varphi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
        "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ",
        "Pi" to "Π", "Sigma" to "Σ", "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
        "prime" to "′", "ell" to "ℓ", "hbar" to "ℏ", "%" to "%", "&" to "&", "#" to "#", "_" to "_", "\$" to "\$"
    )

    private val ROMAN_FUNCS = setOf("sin", "cos", "tan", "cot", "sec", "csc", "log", "ln", "exp", "lim", "max", "min", "det", "arcsin", "arccos", "arctan", "sinh", "cosh", "tanh")
    private val GREEK_UPPER_ROMAN = true

    // ---------------------------------------------------------------------------------------------------------
    // Parsing
    // ---------------------------------------------------------------------------------------------------------

    private class P(val s: String) {
        var i = 0
        fun eof() = i >= s.length
        fun peek(): Char = s[i]
    }

    private fun parseSeq(p: P, stopAtBrace: Boolean, depth: Int): List<Node> {
        val out = ArrayList<Node>()
        while (!p.eof()) {
            val c = p.peek()
            if (c == '}' && stopAtBrace) break
            if (c == '}') { p.i++; continue }
            val atom = parseAtom(p, depth) ?: continue
            var node: Node = atom
            // postfix scripts
            var sup: List<Node>? = null
            var sub: List<Node>? = null
            while (!p.eof() && (p.peek() == '^' || p.peek() == '_')) {
                val which = p.peek()
                p.i++
                val arg = parseScriptArg(p, depth)
                if (which == '^') sup = arg else sub = arg
            }
            if (sup != null && sub != null) node = Node.SupSub(atom, sub, sup)
            else if (sup != null) node = Node.Sup(atom, sup)
            else if (sub != null) node = Node.Sub(atom, sub)
            out.add(node)
        }
        return out
    }

    private fun parseScriptArg(p: P, depth: Int): List<Node> {
        while (!p.eof() && p.peek() == ' ') p.i++
        if (p.eof()) return emptyList()
        if (p.peek() == '{') {
            p.i++
            val inner = if (depth > 14) emptyList() else parseSeq(p, true, depth + 1)
            if (!p.eof() && p.peek() == '}') p.i++
            return inner
        }
        if (p.peek() == '\\') {
            val a = parseAtom(p, depth + 1)
            return if (a == null) emptyList() else listOf(a)
        }
        // a single character, or a signed number such as 10^-3 and x^+
        val c = p.peek()
        p.i++
        if ((c == '-' || c == '+' || c == '−') && !p.eof() && (p.peek().isDigit() || p.peek().isLetter())) {
            val sb = StringBuilder().append(if (c == '−') '-' else c)
            while (!p.eof() && p.peek().isDigit()) { sb.append(p.peek()); p.i++ }
            if (sb.length == 1 && !p.eof()) { sb.append(p.peek()); p.i++ }
            return listOf(Node.Sym(sb.toString(), true))
        }
        val sb = StringBuilder().append(c)
        if (c.isDigit()) while (!p.eof() && p.peek().isDigit()) { sb.append(p.peek()); p.i++ }
        return listOf(Node.Sym(sb.toString(), !c.isLetter()))
    }

    private fun parseGroup(p: P, depth: Int): List<Node> {
        while (!p.eof() && p.peek() == ' ') p.i++
        if (p.eof()) return emptyList()
        if (p.peek() != '{') {
            val a = parseAtom(p, depth + 1)
            return if (a == null) emptyList() else listOf(a)
        }
        p.i++
        val inner = if (depth > 14) emptyList() else parseSeq(p, true, depth + 1)
        if (!p.eof() && p.peek() == '}') p.i++
        return inner
    }

    private fun readBraceText(p: P): String {
        while (!p.eof() && p.peek() == ' ') p.i++
        if (p.eof() || p.peek() != '{') return ""
        var depth = 0
        val start = p.i + 1
        while (!p.eof()) {
            if (p.peek() == '{') depth++
            else if (p.peek() == '}') { depth--; if (depth == 0) { val t = p.s.substring(start, p.i); p.i++; return t } }
            p.i++
        }
        return p.s.substring(start)
    }

    private fun parseAtom(p: P, depth: Int): Node? {
        val c = p.peek()
        when {
            c == ' ' || c == '\n' || c == '\t' -> {
                p.i++
                return Node.Space(0.22f)
            }
            c == '{' -> {
                p.i++
                val inner = if (depth > 14) emptyList() else parseSeq(p, true, depth + 1)
                if (!p.eof() && p.peek() == '}') p.i++
                return if (inner.size == 1) inner[0] else GroupNode(inner)
            }
            c == '\\' -> {
                p.i++
                if (p.eof()) return Node.Sym("\\", true)
                if (!p.peek().isLetter()) {
                    val sym = p.peek()
                    p.i++
                    return when (sym) {
                        ',', ';', ':', ' ' -> Node.Space(0.2f)
                        '!' -> null
                        '\\' -> Node.Space(0.6f)
                        '{', '}' -> Node.Sym(sym.toString(), true)
                        else -> Node.Sym(SYMBOLS[sym.toString()] ?: sym.toString(), true)
                    }
                }
                val start = p.i
                while (!p.eof() && p.peek().isLetter()) p.i++
                val name = p.s.substring(start, p.i)
                return when (name) {
                    "frac", "dfrac", "tfrac" -> {
                        val n = parseGroup(p, depth); val d = parseGroup(p, depth)
                        Node.Frac(n, d)
                    }
                    "sqrt" -> {
                        var index: List<Node>? = null
                        if (!p.eof() && p.peek() == '[') {
                            val close = p.s.indexOf(']', p.i)
                            if (close > 0) {
                                val sub = P(p.s.substring(p.i + 1, close))
                                index = parseSeq(sub, false, depth + 1)
                                p.i = close + 1
                            }
                        }
                        Node.Root(parseGroup(p, depth), index)
                    }
                    "text", "mathrm", "textrm", "mathbf", "textbf", "operatorname", "mbox", "unit", "ce" -> {
                        val t = readBraceText(p)
                        if (name == "ce") chemical(t) else Node.Sym(t, true)
                    }
                    "left", "right", "big", "Big", "bigg", "displaystyle", "limits", "nolimits" -> {
                        if ((name == "left" || name == "right") && !p.eof() && p.peek() == '.') p.i++
                        null
                    }
                    "quad" -> Node.Space(1f)
                    "qquad" -> Node.Space(2f)
                    "hat", "bar", "vec", "overline", "dot", "tilde" -> {
                        val inner = parseGroup(p, depth)
                        val mark = when (name) { "vec" -> "→"; "hat" -> "^"; "bar", "overline" -> "¯"; "dot" -> "˙"; else -> "~" }
                        GroupNode(inner + Node.Sym(mark, true))
                    }
                    else -> when {
                        name in ROMAN_FUNCS -> Node.Sym(name, true)
                        SYMBOLS.containsKey(name) -> Node.Sym(SYMBOLS.getValue(name), GREEK_UPPER_ROMAN && name[0].isUpperCase() || !isGreekLower(name))
                        else -> Node.Sym(name, true)
                    }
                }
            }
            c == '-' -> { p.i++; return Node.Sym("−", true) }
            c == '*' -> { p.i++; return Node.Sym("×", true) }
            c.isDigit() -> {
                val start = p.i
                while (!p.eof() && (p.peek().isDigit() || (p.peek() == '.' && p.i + 1 < p.s.length && p.s[p.i + 1].isDigit()))) p.i++
                return Node.Sym(p.s.substring(start, p.i), true)
            }
            c.isLetter() -> {
                // a run of 3+ letters is a word (units, function names); a single letter is a variable
                val start = p.i
                while (!p.eof() && p.peek().isLetter()) p.i++
                val word = p.s.substring(start, p.i)
                if (word.length == 1) return Node.Sym(word, false)
                if (word in ROMAN_FUNCS) return Node.Sym(word, true)
                if (word.length >= 3 && word.all { it.code < 128 }) return Node.Sym(word, true)
                // short letter runs such as "xy" or "ab" are products of variables
                return GroupNode(word.map { Node.Sym(it.toString(), false) })
            }
            else -> {
                p.i++
                return Node.Sym(c.toString(), true)
            }
        }
    }

    private fun isGreekLower(name: String) = name.isNotEmpty() && name[0].isLowerCase() && SYMBOLS[name]?.firstOrNull()?.let { it in 'α'..'ω' } == true

    private class GroupNode(val nodes: List<Node>) : Node()

    /** "H2O", "CO2", "Ca(OH)2", "SO4^2-" -> subscripts for the digits that follow letters or a closing bracket. */
    private fun chemical(text: String): Node {
        val out = ArrayList<Node>()
        var i = 0
        var prevIsFormulaChar = false
        while (i < text.length) {
            val c = text[i]
            when {
                c.isDigit() && prevIsFormulaChar -> {
                    val st = i
                    while (i < text.length && text[i].isDigit()) i++
                    out.add(Node.Sub(null, listOf(Node.Sym(text.substring(st, i), true))))
                    prevIsFormulaChar = false
                    continue
                }
                c == '^' -> {
                    i++
                    val st = i
                    while (i < text.length && (text[i].isDigit() || text[i] == '+' || text[i] == '-')) i++
                    out.add(Node.Sup(null, listOf(Node.Sym(text.substring(st, i).replace('-', '−'), true))))
                    prevIsFormulaChar = false
                    continue
                }
                else -> {
                    out.add(Node.Sym(c.toString(), true))
                    prevIsFormulaChar = c.isLetter() || c == ')'
                }
            }
            i++
        }
        return GroupNode(out)
    }

    // ---------------------------------------------------------------------------------------------------------
    // Layout
    // ---------------------------------------------------------------------------------------------------------

    private class Ctx(val metrics: TextMetrics, val ink: Ink, val stroke: Float)

    private fun faceOf(roman: Boolean) = if (roman) Face.MATH_ROMAN else Face.MATH_ITALIC

    private fun layoutSeq(nodes: List<Node>, size: Float, ctx: Ctx, depth: Int): Box {
        val ops = ArrayList<DrawOp>()
        var x = 0f
        var asc = ctx.metrics.ascent(size, Face.MATH_ROMAN)
        var desc = ctx.metrics.descent(size, Face.MATH_ROMAN)
        var i = 0
        while (i < nodes.size) {
            val n = nodes[i]
            val b = layoutNode(n, size, ctx, depth + 1)
            // binary operators get a little air on both sides
            val isOp = n is Node.Sym && n.text.length == 1 && n.text[0] in "+=−×÷±≤≥≠≈→⇒⇌⇔∴∝≡<>" && i > 0 && i < nodes.size - 1
            val pad = if (isOp) size * 0.22f else 0f
            x += pad
            for (op in b.ops) ops.add(op.moved(x, 0f))
            x += b.w + pad
            if (b.asc > asc) asc = b.asc
            if (b.desc > desc) desc = b.desc
            i++
        }
        return Box(x, asc, desc, ops)
    }

    private fun layoutNode(n: Node, size: Float, ctx: Ctx, depth: Int): Box {
        if (depth > 30) return Box(0f, 0f, 0f, emptyList())
        val m = ctx.metrics
        return when (n) {
            is Node.Space -> Box(size * n.em, 0f, 0f, emptyList())
            is Node.Sym -> {
                val face = faceOf(n.roman)
                Box(m.width(n.text, size, face), m.ascent(size, face), m.descent(size, face),
                    listOf(DrawOp.Glyphs(n.text, 0f, 0f, size, face, ctx.ink)))
            }
            is GroupNode -> layoutSeq(n.nodes, size, ctx, depth)
            is Node.Frac -> {
                val s = size * 0.82f
                val num = layoutSeq(n.n, s, ctx, depth)
                val den = layoutSeq(n.d, s, ctx, depth)
                val pad = size * 0.14f
                val w = maxOf(num.w, den.w) + pad * 2
                val axis = size * 0.3f
                val ops = ArrayList<DrawOp>()
                val numX = (w - num.w) / 2f
                val denX = (w - den.w) / 2f
                val gap = size * 0.16f
                for (op in num.ops) ops.add(op.moved(numX, -(axis + gap + num.desc)))
                for (op in den.ops) ops.add(op.moved(denX, -axis + gap + den.asc))
                ops.add(DrawOp.Line(0f, -axis, w, -axis, ctx.stroke, ctx.ink))
                Box(w, axis + gap + num.h, -axis + gap + den.h + 0f, ops)
            }
            is Node.Sup -> scripts(n.base, null, n.sup, size, ctx, depth)
            is Node.Sub -> scripts(n.base, n.sub, null, size, ctx, depth)
            is Node.SupSub -> scripts(n.base, n.sub, n.sup, size, ctx, depth)
            is Node.Root -> {
                val body = layoutSeq(n.body, size, ctx, depth)
                val hook = size * 0.55f
                val top = body.asc + size * 0.12f
                val ops = ArrayList<DrawOp>()
                var x0 = 0f
                if (n.index != null) {
                    val idx = layoutSeq(n.index, size * 0.5f, ctx, depth)
                    for (op in idx.ops) ops.add(op.moved(0f, -(top * 0.45f)))
                    x0 = idx.w
                }
                val pts = floatArrayOf(
                    x0, -size * 0.28f,
                    x0 + hook * 0.28f, -size * 0.34f,
                    x0 + hook * 0.62f, body.desc + size * 0.06f,
                    x0 + hook, -top,
                    x0 + hook + body.w + size * 0.18f, -top
                )
                ops.add(DrawOp.Poly(pts, ctx.stroke, ctx.ink))
                for (op in body.ops) ops.add(op.moved(x0 + hook + size * 0.08f, 0f))
                Box(x0 + hook + body.w + size * 0.2f, top + ctx.stroke, body.desc + size * 0.06f, ops)
            }
        }
    }

    private fun scripts(base: Node?, sub: List<Node>?, sup: List<Node>?, size: Float, ctx: Ctx, depth: Int): Box {
        val b = if (base != null) layoutNode(base, size, ctx, depth + 1) else Box(0f, ctx.metrics.ascent(size, Face.MATH_ROMAN), ctx.metrics.descent(size, Face.MATH_ROMAN), emptyList())
        val s = size * 0.62f
        val ops = ArrayList<DrawOp>(b.ops)
        var w = b.w
        var asc = b.asc
        var desc = b.desc
        val supBox = sup?.let { layoutSeq(it, s, ctx, depth + 1) }
        val subBox = sub?.let { layoutSeq(it, s, ctx, depth + 1) }
        val gapX = size * 0.04f
        val shiftUp = b.asc * 0.55f
        val shiftDown = size * 0.22f
        var sw = 0f
        if (supBox != null) {
            for (op in supBox.ops) ops.add(op.moved(b.w + gapX, -shiftUp))
            sw = maxOf(sw, supBox.w)
            asc = maxOf(asc, shiftUp + supBox.asc)
        }
        if (subBox != null) {
            for (op in subBox.ops) ops.add(op.moved(b.w + gapX, shiftDown))
            sw = maxOf(sw, subBox.w)
            desc = maxOf(desc, shiftDown + subBox.desc)
        }
        w += gapX + sw + size * 0.04f
        return Box(w, asc, desc, ops)
    }

    // ---------------------------------------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Typesets [latex] at [size]. If it is wider than [maxWidth], the whole formula is scaled down (never below 55% so it
     * stays readable); a formula that is still too wide is broken at its top-level "=" or operators by [layoutWrapped].
     */
    fun layout(latex: String, size: Float, ink: Ink, metrics: TextMetrics, stroke: Float = size * 0.055f): Box {
        val p = P(latex.trim())
        val nodes = parseSeq(p, false, 0)
        return layoutSeq(nodes, size, Ctx(metrics, ink, stroke.coerceAtLeast(1f)), 0)
    }

    /** One or more lines of typeset math that each fit [maxWidth]. Lines are positioned with baselines at [startY]. */
    fun layoutWrapped(latex: String, size: Float, maxWidth: Float, ink: Ink, metrics: TextMetrics, lineGap: Float): List<Box> {
        val whole = layout(latex, size, ink, metrics)
        if (whole.w <= maxWidth) return listOf(whole)
        val scale = maxWidth / whole.w
        if (scale >= 0.78f) return listOf(layout(latex, size * scale, ink, metrics))
        // break at top-level relations/operators, keeping each piece intact
        val pieces = splitTopLevel(latex)
        if (pieces.size <= 1) return listOf(layout(latex, size * maxOf(scale, 0.55f), ink, metrics))
        val lines = ArrayList<Box>()
        var current = StringBuilder()
        for (piece in pieces) {
            val trial = current.toString() + piece
            if (current.isNotEmpty() && layout(trial, size, ink, metrics).w > maxWidth) {
                lines.add(fit(current.toString(), size, maxWidth, ink, metrics))
                current = StringBuilder(piece.trimStart())
            } else current.append(piece)
        }
        if (current.isNotBlank()) lines.add(fit(current.toString(), size, maxWidth, ink, metrics))
        return lines
    }

    private fun fit(latex: String, size: Float, maxWidth: Float, ink: Ink, metrics: TextMetrics): Box {
        val b = layout(latex, size, ink, metrics)
        return if (b.w <= maxWidth) b else layout(latex, size * maxOf(maxWidth / b.w, 0.55f), ink, metrics)
    }

    /** Splits before top-level "=", "+", "-" (outside braces) so a long line can wrap without cutting a fraction in half. */
    internal fun splitTopLevel(s: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var start = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '{' || c == '(') depth++
            else if (c == '}' || c == ')') depth--
            else if (depth == 0 && i > start && (c == '=' || c == '+' || c == '-' || c == '−')) {
                // never split a script exponent like 10^-3 or a leading minus
                val prev = s[i - 1]
                if (prev != '^' && prev != '_' && prev != '(' && prev != '{' && prev != '=') {
                    out.add(s.substring(start, i)); start = i
                }
            }
            i++
        }
        out.add(s.substring(start))
        return out
    }

    /**
     * Decides whether a step's text is mathematical or ordinary prose, so words are never forced into italic math face.
     * A step is math when it has a LaTeX command, an "=" relation, a script, or is mostly digits and operators.
     */
    fun looksLikeMath(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (t.contains('\\') || t.contains('^') || t.contains('_') || t.contains('=') || t.contains('→') || t.contains('⇌')) return true
        val letters = t.count { it.isLetter() }
        val spaces = t.count { it == ' ' }
        val words = t.split(Regex("\\s+")).count { w -> w.count { it.isLetter() } >= 3 }
        if (words >= 2 || (spaces >= 3 && letters > t.length / 2)) return false
        val mathish = t.count { it.isDigit() || it in "+-*/()<>.×÷−" }
        return mathish * 2 >= t.length - spaces
    }
}
