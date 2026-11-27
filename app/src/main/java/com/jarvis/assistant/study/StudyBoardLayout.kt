package com.jarvis.assistant.study

import kotlin.math.max
import kotlin.math.min

/** One laid-out piece of the board: a question, a step, a diagram or the final answer, with absolute positions. */
class BoardBlock(
    val stepIndex: Int,
    val kind: StepKind,
    /** What is written for the step itself (caption, formula, diagram), in writing order. */
    val ops: List<DrawOp>,
    /** The short explanation under the step. Can be hidden without losing any maths. */
    val noteOps: List<DrawOp>,
    val top: Float,
    val bottom: Float,
    val isFinal: Boolean = false,
    val isQuestion: Boolean = false
) {
    val height: Float get() = bottom - top
    fun allOps(showNotes: Boolean): List<DrawOp> = if (showNotes) ops + noteOps else ops
}

class BoardStyle(
    val width: Float,
    val baseSize: Float = 30f,
    val margin: Float = 30f,
    val blockGap: Float = 22f
) {
    val inner: Float get() = width - margin * 2
    val noteSize: Float get() = baseSize * 0.66f
    val captionSize: Float get() = baseSize * 0.5f
}

object StudyBoardLayout {

    private sealed class InlineRun {
        class Words(val text: String, val bold: Boolean, val ink: Ink) : InlineRun()
        class InlineMath(val expr: String, val ink: Ink) : InlineRun()
    }

    private sealed class RichToken {
        class Word(val text: String, val bold: Boolean, val ink: Ink) : RichToken()
        class MathBox(val box: StudyMath.Box) : RichToken()
    }

    private data class MeasuredLine(val items: List<RichToken>, val asc: Float, val desc: Float)

    private val CODE_KEYWORDS = setOf(
        "def", "class", "return", "if", "elif", "else", "for", "while", "in", "import", "from", "as",
        "fun", "val", "var", "when", "try", "catch", "finally", "public", "private", "static", "void",
        "int", "float", "double", "boolean", "string", "char", "const", "let", "function", "async", "await",
        "true", "false", "null", "None", "True", "False", "print", "println", "switch", "case", "break", "continue"
    )

    private val STEP_PALETTE = listOf(Ink.ACCENT, Ink.TEAL, Ink.VIOLET, Ink.RESULT)

    fun wrap(text: String, size: Float, face: Face, maxW: Float, metrics: TextMetrics): List<String> {
        val lines = ArrayList<String>()
        for (para in text.split('\n')) {
            if (para.isBlank()) { lines.add(""); continue }
            var cur = StringBuilder()
            for (word in para.trim().split(Regex("\\s+"))) {
                val trial = if (cur.isEmpty()) word else cur.toString() + " " + word
                if (cur.isNotEmpty() && metrics.width(trial, size, face) > maxW) {
                    lines.add(cur.toString()); cur = StringBuilder(word)
                } else cur = StringBuilder(trial)
                // a single unbroken word that is too wide is split by characters, never allowed to overflow
                while (metrics.width(cur.toString(), size, face) > maxW && cur.length > 1) {
                    var k = cur.length - 1
                    while (k > 1 && metrics.width(cur.substring(0, k), size, face) > maxW) k--
                    lines.add(cur.substring(0, k)); cur = StringBuilder(cur.substring(k))
                }
            }
            if (cur.isNotEmpty()) lines.add(cur.toString())
        }
        return lines
    }

    fun layoutQuestion(question: String, startY: Float, st: BoardStyle, m: TextMetrics, prefix: String = "Q."): BoardBlock {
        val ops = ArrayList<DrawOp>()
        val size = st.baseSize * 0.90f
        var y = startY + st.margin * 0.4f
        val pillH = size * 1.18f
        val pwText = m.width(prefix, size * 0.82f, Face.BODY_BOLD)
        val pillW = max(pwText + 18f, size * 1.2f)
        val pillInk = if (prefix == "?") Ink.VIOLET else Ink.ACCENT
        ops.add(
            DrawOp.Rect(
                st.margin, y + size * 0.08f, pillW, pillH,
                stroke = 1.6f, ink = pillInk, fill = true,
                rx = pillH * 0.42f, ry = pillH * 0.42f,
                fillInk = pillInk, shade = FillShade.FLAT, fillAlpha = 0.14f
            )
        )
        ops.add(
            DrawOp.Glyphs(
                prefix,
                st.margin + (pillW - pwText) / 2f,
                y + size * 0.08f + (pillH + m.ascent(size * 0.82f, Face.BODY_BOLD) - m.descent(size * 0.82f, Face.BODY_BOLD)) / 2f,
                size * 0.82f, Face.BODY_BOLD, pillInk
            )
        )

        val contentX = st.margin + pillW + 14f
        val availW = (st.width - st.margin - contentX).coerceAtLeast(120f)
        val math = StudyMath.looksLikeMath(question) && !question.contains('\n') && question.length < 90
        if (math) {
            val b = StudyMath.layoutWrapped(question, size, availW, Ink.INK, m, size * 0.3f)
            var yy = y
            for (box in b) {
                val base = yy + box.asc
                for (op in box.ops) ops.add(op.moved(contentX, base))
                yy += box.h + size * 0.3f
            }
            y = max(y + pillH + 8f, yy)
        } else {
            val richH = layoutRichText(
                question, contentX, y + size * 0.06f, availW, size,
                baseInk = Ink.INK, termInk = pillInk, mathInk = Ink.ACCENT,
                m = m, out = ops, boldTermPrefix = false
            )
            y = max(y + pillH + 8f, y + richH + 4f)
        }
        val ruleY = y + st.blockGap * 0.25f
        ops.add(DrawOp.Line(st.margin, ruleY, st.width - st.margin, ruleY, 2f, Ink.FAINT))
        return BoardBlock(
            0, StepKind.STEP,
            clampToMargin(ops, st.margin, st.width - st.margin, m),
            emptyList(), startY, ruleY + st.blockGap, isQuestion = true
        )
    }

    fun layoutStep(
        step: StudyStep,
        startY: Float,
        st: BoardStyle,
        m: TextMetrics,
        isFinalBlock: Boolean = false,
        finalCaption: String = "Final Answer"
    ): BoardBlock {
        val ops = ArrayList<DrawOp>()
        val notes = ArrayList<DrawOp>()
        var y = startY
        val size = st.baseSize

        val accentInk = when {
            isFinalBlock || step.isFinalHighlight -> Ink.RESULT
            step.kind == StepKind.FORMULA -> Ink.ACCENT
            step.kind == StepKind.GIVEN -> Ink.TEAL
            step.kind == StepKind.REQUIRED -> Ink.VIOLET
            step.kind == StepKind.DEFINITION -> Ink.VIOLET
            step.kind == StepKind.CODE -> Ink.TEAL
            step.kind == StepKind.DIAGRAM -> Ink.ACCENT
            else -> if (step.index > 0) STEP_PALETTE[(step.index - 1) % STEP_PALETTE.size] else Ink.VIOLET
        }
        val primaryTextInk = when {
            isFinalBlock || step.isFinalHighlight -> Ink.RESULT
            step.kind == StepKind.FORMULA -> Ink.ACCENT
            else -> Ink.INK
        }

        val caption = when {
            isFinalBlock -> finalCaption.ifBlank { "Final Answer" }
            step.kind.caption.isNotEmpty() -> step.kind.caption
            step.kind == StepKind.STEP && step.index != 0 && step.index != Int.MAX_VALUE && step.proseMode ->
                "Step ${if (step.index < 0) -step.index else step.index}"
            else -> ""
        }
        val hasSideRail = step.kind == StepKind.GIVEN || step.kind == StepKind.REQUIRED || step.kind == StepKind.DEFINITION
        val indent = if (hasSideRail) 14f else 0f

        var captionBottom = y
        if (caption.isNotEmpty()) {
            val badgeText = if (step.kind == StepKind.CODE && step.language.isNotBlank()) {
                "${caption.uppercase()} · ${step.language.uppercase()}"
            } else caption.uppercase()
            val cs = st.captionSize * 0.94f
            val tw = m.width(badgeText, cs, Face.BODY_BOLD)
            val bh = cs * 1.48f
            val bw = tw + 18f
            ops.add(
                DrawOp.Rect(
                    st.margin, y, bw, bh,
                    stroke = 1.2f, ink = accentInk, fill = true,
                    rx = bh * 0.45f, ry = bh * 0.45f,
                    fillInk = accentInk, shade = FillShade.FLAT, fillAlpha = 0.14f
                )
            )
            ops.add(
                DrawOp.Glyphs(
                    badgeText,
                    st.margin + 9f,
                    y + (bh + m.ascent(cs, Face.BODY_BOLD) - m.descent(cs, Face.BODY_BOLD)) / 2f,
                    cs, Face.BODY_BOLD, accentInk
                )
            )
            y += bh + cs * 0.65f
            captionBottom = y
        }

        val x0 = st.margin + indent
        val maxW = (st.inner - indent).coerceAtLeast(100f)
        val contentStartY = y

        if (step.content.isNotEmpty()) {
            when {
                step.kind == StepKind.CODE -> {
                    val cs = size * 0.60f
                    val pad = 14f
                    val rawLines = step.content.split('\n')
                    val wrappedLines = rawLines.flatMap { wrap(it, cs, Face.CODE, maxW - pad * 2f, m).ifEmpty { listOf("") } }
                    val boxTop = y
                    val lineH = cs * 1.38f
                    val boxH = wrappedLines.size * lineH + pad * 1.6f
                    ops.add(
                        DrawOp.Rect(
                            x0, boxTop, maxW, boxH,
                            stroke = 1.6f, ink = Ink.TEAL, fill = true,
                            rx = 14f, ry = 14f, fillInk = Ink.TEAL, shade = FillShade.LINEAR, fillAlpha = 0.08f
                        )
                    )
                    var ly = boxTop + pad * 0.85f
                    for (line in wrappedLines) {
                        drawHighlightedCodeLine(line, x0 + pad, ly + cs, cs, m, ops)
                        ly += lineH
                    }
                    y = boxTop + boxH + 4f
                }
                step.proseMode || hasNaturalProseWithInlineMath(step.content) -> {
                    val proseSize = size * if (isFinalBlock) 0.90f else 0.86f
                    val usedH = layoutRichText(
                        step.content, x0, y, maxW, proseSize,
                        baseInk = Ink.INK,
                        termInk = accentInk,
                        mathInk = if (isFinalBlock) Ink.RESULT else Ink.ACCENT,
                        m = m,
                        out = ops,
                        boldTermPrefix = true
                    )
                    y += usedH
                    if (isFinalBlock || step.isFinalHighlight) {
                        val top = captionBottom - 6f
                        ops.add(DrawOp.Rect(x0 - 10f, top, maxW + 20f, y - top + 6f, 3f, Ink.RESULT, rx = 14f, ry = 14f))
                        y += 6f
                    }
                }
                else -> {
                    val rows = step.content.split('\n').filter { it.isNotBlank() }
                    for (row in rows) {
                        val boxes = StudyMath.layoutWrapped(row, size, maxW, primaryTextInk, m, size * 0.3f)
                        for (box in boxes) {
                            val base = y + box.asc
                            for (op in box.ops) ops.add(op.moved(x0, base))
                            y += box.h + size * 0.24f
                        }
                    }
                    if (isFinalBlock || step.isFinalHighlight) {
                        // A crisp rectangular frame round the final answer (kept as DrawOp.Rect for board consistency)
                        val top = captionBottom - 6f
                        ops.add(DrawOp.Rect(x0 - 10f, top, maxW + 20f, y - top + 4f, 3f, Ink.RESULT, rx = 14f, ry = 14f))
                        y += 4f
                    }
                }
            }
        }

        if (hasSideRail && y > contentStartY + 4f) {
            ops.add(0, DrawOp.Line(st.margin + 3f, contentStartY + 2f, st.margin + 3f, y - 6f, 4f, accentInk))
        }

        step.diagram?.let { rawDiag ->
            val d = StudyDiagramAtlas.enhance(rawDiag.title, rawDiag)
            val maxDiagW = maxW.coerceAtMost(st.width - st.margin * 2f)
            val maxDiagH = min(st.width * 0.88f, 880f)
            val laid = StudyDiagramLayout.layout(d, maxDiagW, maxDiagH, m, max(2.2f, size * 0.068f))
            for (op in laid.ops) ops.add(op.moved(x0, y + 6f))
            y += laid.height + 12f
        }

        if (step.explanation.isNotEmpty()) {
            val ns = st.noteSize
            y += 4f
            val noteStartY = y
            val usedNoteH = layoutRichText(
                step.explanation, x0 + 16f, y, (maxW - 18f).coerceAtLeast(80f), ns,
                baseInk = Ink.NOTE, termInk = Ink.TEAL, mathInk = Ink.ACCENT,
                m = m, out = notes, boldTermPrefix = true
            )
            y += usedNoteH
            if (y > noteStartY + 4f) {
                notes.add(0, DrawOp.Line(x0 + 5f, noteStartY + 3f, x0 + 5f, y - 4f, 2.5f, Ink.GOLD))
            }
        }

        return BoardBlock(
            step.index,
            step.kind,
            clampToMargin(ops, st.margin - 12f, st.width - st.margin + 12f, m),
            clampToMargin(notes, st.margin - 6f, st.width - st.margin + 6f, m),
            startY,
            y + st.blockGap,
            isFinal = isFinalBlock || step.isFinalHighlight
        )
    }

    fun layoutFinal(answer: String, explanation: String, startY: Float, st: BoardStyle, m: TextMetrics, caption: String = "Final Answer"): BoardBlock {
        val step = StudyStep(
            index = Int.MAX_VALUE,
            kind = StepKind.RESULT,
            content = answer,
            explanation = explanation,
            proseMode = !StudyMath.looksLikeMath(answer)
        )
        return layoutStep(step, startY, st, m, isFinalBlock = true, finalCaption = caption)
    }

    // ---------------------------------------------------------------------------------------------------------
    // Rich Multi-Color Inline Typesetting (Prose + Inline LaTeX + Coloured Key Terms + Bullets)
    // ---------------------------------------------------------------------------------------------------------

    private fun hasNaturalProseWithInlineMath(s: String): Boolean {
        val proseWords = Regex("(?<!\\\\)\\b[\\p{L}]{3,}\\b").findAll(s).count()
        val spaces = s.count { it == ' ' }
        return proseWords >= 4 && spaces >= 4
    }

    private fun layoutRichText(
        text: String,
        startX: Float,
        startY: Float,
        maxW: Float,
        size: Float,
        baseInk: Ink,
        termInk: Ink,
        mathInk: Ink,
        m: TextMetrics,
        out: MutableList<DrawOp>,
        boldTermPrefix: Boolean
    ): Float {
        var y = startY
        val rawParas = text.split('\n')
        var stepBadgeCount = 0

        for (rawPara in rawParas) {
            val trimmed = rawPara.trim()
            if (trimmed.isEmpty()) {
                y += size * 0.55f
                continue
            }

            var bodyText = trimmed
            var bulletColor: Ink? = null
            var numberedBadge: String? = null
            var prefixIndent = 0f

            val numMatch = Regex("^(\\d{1,2})[.)]\\s+(.+)$").matchEntire(bodyText)
            when {
                bodyText.startsWith("• ") || bodyText.startsWith("- ") || bodyText.startsWith("* ") -> {
                    bodyText = bodyText.substring(2).trimStart()
                    bulletColor = STEP_PALETTE[stepBadgeCount++ % STEP_PALETTE.size]
                    prefixIndent = size * 0.95f
                }
                numMatch != null -> {
                    numberedBadge = numMatch.groupValues[1]
                    bodyText = numMatch.groupValues[2].trimStart()
                    bulletColor = STEP_PALETTE[(numberedBadge.toIntOrNull()?.minus(1) ?: stepBadgeCount++) % STEP_PALETTE.size]
                    prefixIndent = size * 1.45f
                }
            }

            // Draw visual bullet dot or numbered color badge
            if (bulletColor != null) {
                if (numberedBadge != null) {
                    val bw = max(size * 1.08f, m.width(numberedBadge, size * 0.72f, Face.BODY_BOLD) + 10f)
                    val bh = size * 1.05f
                    out.add(
                        DrawOp.Rect(
                            startX, y + size * 0.14f, bw, bh,
                            stroke = 1.2f, ink = bulletColor, fill = true,
                            rx = bh * 0.38f, ry = bh * 0.38f,
                            fillInk = bulletColor, shade = FillShade.FLAT, fillAlpha = 0.16f
                        )
                    )
                    val tw = m.width(numberedBadge, size * 0.72f, Face.BODY_BOLD)
                    out.add(
                        DrawOp.Glyphs(
                            numberedBadge,
                            startX + (bw - tw) / 2f,
                            y + size * 0.14f + (bh + m.ascent(size * 0.72f, Face.BODY_BOLD) - m.descent(size * 0.72f, Face.BODY_BOLD)) / 2f,
                            size * 0.72f, Face.BODY_BOLD, bulletColor
                        )
                    )
                } else {
                    val r = (size * 0.16f).coerceIn(3f, 6f)
                    out.add(DrawOp.Oval(startX + r + 3f, y + size * 0.62f, r, r, 1f, bulletColor, fill = true, fillInk = bulletColor, shade = FillShade.FLAT, fillAlpha = 0.9f))
                }
            }

            val lineX = startX + prefixIndent
            val lineMaxW = (maxW - prefixIndent).coerceAtLeast(60f)

            val runs = parseRichRuns(bodyText, baseInk, termInk, mathInk, boldTermPrefix)
            val tokens = tokenizeRuns(runs, size, lineMaxW, m)
            val lines = wrapTokens(tokens, size, lineMaxW, m)

            for (line in lines) {
                var cx = lineX
                val asc = max(line.asc, m.ascent(size, Face.BODY))
                val desc = max(line.desc, m.descent(size, Face.BODY))
                val baseY = y + asc
                for (tok in line.items) {
                    when (tok) {
                        is RichToken.Word -> {
                            val face = if (tok.bold) Face.BODY_BOLD else Face.BODY
                            out.add(DrawOp.Glyphs(tok.text, cx, baseY, size, face, tok.ink))
                            cx += m.width(tok.text, size, face)
                        }
                        is RichToken.MathBox -> {
                            for (op in tok.box.ops) out.add(op.moved(cx, baseY))
                            cx += tok.box.w
                        }
                    }
                }
                y += asc + desc + size * 0.34f
            }
            y += size * 0.12f
        }
        return y - startY
    }

    /**
     * Splits a natural sentence into coloured plain text spans and inline LaTeX / formula spans (such as
     * `\frac{1}{2}`, `\ce{H2O}`, `CO_{2}`, `E = mc^{2}`, or key headings before `:`).
     */
    private fun parseRichRuns(
        line: String,
        baseInk: Ink,
        termInk: Ink,
        mathInk: Ink,
        boldTermPrefix: Boolean
    ): List<InlineRun> {
        val out = ArrayList<InlineRun>()
        var rest = line
        // Highlight key concept label before ':' cleanly when not part of a URL or ratio
        if (boldTermPrefix) {
            val colon = rest.indexOf(':')
            if (colon in 2..42 && !rest.substring(0, colon).contains('=')) {
                val head = rest.substring(0, colon + 1)
                if (!head.contains('\\') && !head.startsWith("http", true)) {
                    out.add(InlineRun.Words("$head ", bold = true, ink = termInk))
                    rest = rest.substring(colon + 1).trimStart()
                }
            }
        }

        // Tokenize inline math spans: \cmd{...}{...}..., formula with ^ or _, or spaced short equation a = b
        val pattern = Regex(
            """(\\(?:d?frac|sqrt|ce|text|mathrm|textbf)\s*(?:\[[^\[\]]*\])?(?:\{[^{}]*(?:\{[^{}]*\}[^{}]*)?\})+""" +
                """|\\[a-zA-Z]+(?:\s*\{[^{}]*\})?""" +
                """|[A-Za-z0-9)]+(?:[\^_]\{[^{}]+\}|[\^_][A-Za-z0-9+\-]+)+)"""
        )

        var pos = 0
        for (match in pattern.findAll(rest)) {
            if (match.range.first > pos) {
                val chunk = rest.substring(pos, match.range.first)
                if (chunk.isNotEmpty()) out.add(InlineRun.Words(chunk, bold = false, ink = baseInk))
            }
            out.add(InlineRun.InlineMath(match.value, mathInk))
            pos = match.range.last + 1
        }
        if (pos < rest.length) {
            out.add(InlineRun.Words(rest.substring(pos), bold = false, ink = baseInk))
        }
        return out
    }

    private fun tokenizeRuns(runs: List<InlineRun>, size: Float, maxW: Float, m: TextMetrics): List<RichToken> {
        val tokens = ArrayList<RichToken>()
        for (run in runs) {
            when (run) {
                is InlineRun.InlineMath -> {
                    val b = StudyMath.layoutWrapped(run.expr, size * 0.96f, maxW, run.ink, m, size * 0.2f).firstOrNull()
                    if (b != null) tokens.add(RichToken.MathBox(b))
                }
                is InlineRun.Words -> {
                    // Split keeping trailing space attached so spacing stays natural and never orphans punctuation
                    val parts = Regex("\\S+\\s*|\\s+").findAll(run.text)
                    for (part in parts) {
                        val w = part.value
                        if (w.isEmpty()) continue
                        val face = if (run.bold) Face.BODY_BOLD else Face.BODY
                        if (m.width(w, size, face) <= maxW) {
                            tokens.add(RichToken.Word(w, run.bold, run.ink))
                        } else {
                            // Break overly long unbroken words cleanly across lines
                            var cur = w
                            while (cur.isNotEmpty()) {
                                var k = cur.length
                                while (k > 1 && m.width(cur.substring(0, k), size, face) > maxW) k--
                                tokens.add(RichToken.Word(cur.substring(0, k), run.bold, run.ink))
                                cur = cur.substring(k)
                            }
                        }
                    }
                }
            }
        }
        return tokens
    }

    private fun wrapTokens(tokens: List<RichToken>, size: Float, maxW: Float, m: TextMetrics): List<MeasuredLine> {
        val lines = ArrayList<MeasuredLine>()
        var line = ArrayList<RichToken>()
        var lineW = 0f
        var asc = m.ascent(size, Face.BODY)
        var desc = m.descent(size, Face.BODY)

        fun flush() {
            if (line.isNotEmpty()) {
                lines.add(MeasuredLine(line, asc, desc))
                line = ArrayList()
                lineW = 0f
                asc = m.ascent(size, Face.BODY)
                desc = m.descent(size, Face.BODY)
            }
        }

        for (tok in tokens) {
            val tw: Float
            val ta: Float
            val td: Float
            when (tok) {
                is RichToken.Word -> {
                    val face = if (tok.bold) Face.BODY_BOLD else Face.BODY
                    tw = m.width(tok.text, size, face)
                    ta = m.ascent(size, face)
                    td = m.descent(size, face)
                }
                is RichToken.MathBox -> {
                    tw = tok.box.w
                    ta = tok.box.asc
                    td = tok.box.desc
                }
            }
            if (line.isNotEmpty() && lineW + tw > maxW) {
                flush()
                if (tok is RichToken.Word && tok.text.isBlank()) continue
            }
            line.add(tok)
            lineW += tw
            if (ta > asc) asc = ta
            if (td > desc) desc = td
        }
        flush()
        return lines
    }

    private fun drawHighlightedCodeLine(line: String, startX: Float, baseY: Float, size: Float, m: TextMetrics, out: MutableList<DrawOp>) {
        if (line.isEmpty()) return
        val commentIdx = line.indexOf('#').takeIf { it >= 0 } ?: line.indexOf("//").takeIf { it >= 0 }
        val codePart = if (commentIdx != null) line.substring(0, commentIdx) else line
        val commentPart = if (commentIdx != null) line.substring(commentIdx) else ""
        var x = startX
        val tokenRegex = Regex("""("(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'|\b\d+(?:\.\d+)?\b|\b[A-Za-z_][A-Za-z0-9_]*\b|\s+|.)""")
        for (match in tokenRegex.findAll(codePart)) {
            val tok = match.value
            val ink = when {
                tok.startsWith('"') || tok.startsWith('\'') -> Ink.RESULT
                tok.isNotEmpty() && tok[0].isDigit() -> Ink.ORANGE
                tok in CODE_KEYWORDS -> Ink.VIOLET
                else -> Ink.INK
            }
            if (tok.isNotBlank()) out.add(DrawOp.Glyphs(tok, x, baseY, size, Face.CODE, ink))
            x += m.width(tok, size, Face.CODE)
        }
        if (commentPart.isNotEmpty()) {
            out.add(DrawOp.Glyphs(commentPart, x, baseY, size, Face.CODE, Ink.NOTE))
        }
    }

    /** Guarantees no mark overflows left of [minBoundX] or right of [maxBoundX]. */
    private fun clampToMargin(ops: List<DrawOp>, minBoundX: Float, maxBoundX: Float, metrics: TextMetrics): List<DrawOp> {
        val box = DrawBounds.measure(ops, metrics) ?: return ops
        val allowedW = (maxBoundX - minBoundX).coerceAtLeast(40f)
        if (box.minX >= minBoundX - 0.5f && box.maxX <= maxBoundX + 0.5f) return ops
        var out = ops
        if (box.width > allowedW) {
            val factor = (allowedW / box.width).coerceAtLeast(0.45f)
            out = out.map { it.scaled(box.minX, box.minY, factor) }
        }
        val b2 = DrawBounds.measure(out, metrics) ?: return out
        val dx = when {
            b2.minX < minBoundX -> minBoundX - b2.minX
            b2.maxX > maxBoundX -> maxBoundX - b2.maxX
            else -> 0f
        }
        return if (kotlin.math.abs(dx) > 0.2f) out.map { it.moved(dx, 0f) } else out
    }
}
