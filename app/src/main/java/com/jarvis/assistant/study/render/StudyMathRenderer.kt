package com.jarvis.assistant.study.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import kotlin.math.max
import kotlin.math.min

/**
 * Custom mathematical notation & equation typesetting engine for the JARVIS Live Study Board.
 *
 * Parses LaTeX-like and plain math expressions into a structured AST supporting:
 * - Stacked fractions (`\frac{a}{b}`)
 * - Geometric square/nth roots (`\sqrt{...}`, `\sqrt[n]{...}`)
 * - Multi-character superscripts & subscripts (`x^{2}`, `H_2O`, `Fe^{3+}`)
 * - Vectors & overbars (`\vec{F}`, `\bar{x}`)
 * - Greek, calculus, inequality, and chemical reaction symbols
 * - Intelligent operator-aware multi-line wrapping so formulas never overflow any screen width
 * - Progressive stroke/character reveal with active writing nib coordinate tracking
 */
object StudyMathRenderer {

    sealed class MathNode {
        data class TextRun(val text: String) : MathNode()
        data class Fraction(val numerator: List<MathNode>, val denominator: List<MathNode>) : MathNode()
        data class Radical(val radicand: List<MathNode>, val index: List<MathNode>? = null) : MathNode()
        data class Script(val base: List<MathNode>, val sup: List<MathNode>? = null, val sub: List<MathNode>? = null) : MathNode()
        data class Accent(val inner: List<MathNode>, val isVectorArrow: Boolean) : MathNode()
        data class Group(val children: List<MathNode>) : MathNode()
    }

    data class DrawCommand(
        val kind: Kind,
        val rect: RectF,
        val text: String = "",
        val baselineY: Float = 0f,
        val textSize: Float = 0f,
        val isItalic: Boolean = false,
        val strokePath: Path? = null,
        val strokeWidth: Float = 2f,
        val charWeight: Int = 1,
        val highlighted: Boolean = false
    ) {
        enum class Kind {
            TEXT,
            FRACTION_BAR,
            PATH_STROKE,
            HIGHLIGHT_PILL
        }
    }

    data class MeasuredMathLine(
        val commands: List<DrawCommand>,
        val width: Float,
        val height: Float,
        val topOffset: Float,
        val totalUnits: Int
    )

    data class MeasuredExpression(
        val raw: String,
        val lines: List<MeasuredMathLine>,
        val totalWidth: Float,
        val totalHeight: Float,
        val totalUnits: Int
    )

    private val SYMBOL_REPLACEMENTS: List<Pair<String, String>> = listOf(
        "\\rightleftharpoons" to "⇌",
        "\\leftrightarrow" to "↔",
        "\\rightarrow" to "→",
        "\\leftarrow" to "←",
        "\\implies" to "⇒",
        "\\iff" to "⇔",
        "\\therefore" to "∴",
        "\\because" to "∵",
        "\\approx" to "≈",
        "\\equiv" to "≡",
        "\\propto" to "∝",
        "\\infty" to "∞",
        "\\degree" to "°",
        "\\times" to "×",
        "\\cdot" to "·",
        "\\div" to "÷",
        "\\pm" to "±",
        "\\mp" to "∓",
        "\\leq" to "≤",
        "\\geq" to "≥",
        "\\neq" to "≠",
        "\\le" to "≤",
        "\\ge" to "≥",
        "\\ne" to "≠",
        "\\alpha" to "α",
        "\\beta" to "β",
        "\\gamma" to "γ",
        "\\delta" to "δ",
        "\\Delta" to "Δ",
        "\\theta" to "θ",
        "\\Theta" to "Θ",
        "\\lambda" to "λ",
        "\\mu" to "μ",
        "\\pi" to "π",
        "\\rho" to "ρ",
        "\\sigma" to "σ",
        "\\Sigma" to "∑",
        "\\omega" to "ω",
        "\\Omega" to "Ω",
        "\\phi" to "φ",
        "\\psi" to "ψ",
        "\\tau" to "τ",
        "\\int" to "∫",
        "\\sum" to "∑",
        "\\prod" to "∏",
        "\\partial" to "∂",
        "\\nabla" to "∇",
        "\\in" to "∈",
        "\\notin" to "∉",
        "\\subset" to "⊂",
        "\\subseteq" to "⊆",
        "\\cup" to "∪",
        "\\cap" to "∩",
        "\\forall" to "∀",
        "\\exists" to "∃",
        "\\sin" to "sin",
        "\\cos" to "cos",
        "\\tan" to "tan",
        "\\log" to "log",
        "\\ln" to "ln",
        "\\lim" to "lim",
        "\\left(" to "(",
        "\\right)" to ")",
        "\\left[" to "[",
        "\\right]" to "]",
        "\\left|" to "|",
        "\\right|" to "|",
        "\\to" to "→",
        "<->" to "⇌",
        "<=>" to "⇌",
        "->" to "→",
        "=>" to "⇒",
        "<=" to "≤",
        ">=" to "≥",
        "!=" to "≠",
        "+-" to "±"
    )

    fun normalizeSymbols(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("$$") && s.endsWith("$$") && s.length > 4) {
            s = s.substring(2, s.length - 2).trim()
        } else if (s.startsWith("$") && s.endsWith("$") && s.length > 2) {
            s = s.substring(1, s.length - 1).trim()
        }
        s = s.replace("\\(", "")
            .replace("\\)", "")
            .replace("\\[", "")
            .replace("\\]", "")
            .replace("\\text{", "")
            .replace("\\mathrm{", "")
            .replace("\\mathbf{", "")
            .replace(" }", "}")
        for ((token, replacement) in SYMBOL_REPLACEMENTS) {
            s = s.replace(token, replacement)
        }
        return s.replace(Regex("\\s+"), " ").trim()
    }

    /** Parses a math expression into an AST supporting fractions, roots, scripts, and symbols. */
    fun parseAst(raw: String): List<MathNode> {
        val clean = normalizeSymbols(raw)
        if (clean.isEmpty()) return emptyList()
        val parser = AstParser(clean)
        return parser.parseSequence(stopChar = null)
    }

    /** Wraps top-level AST nodes at operator boundaries so no line exceeds [maxWidthPx]. */
    fun measure(
        raw: String,
        paint: TextPaint,
        maxWidthPx: Float,
        highlightTokens: List<String> = emptyList()
    ): MeasuredExpression {
        val normalized = normalizeSymbols(raw)
        val nodes = parseAst(normalized)
        val safeMaxW = maxWidthPx.coerceAtLeast(120f)
        val highlightSet = highlightTokens.map { normalizeSymbols(it) }.filter { it.isNotBlank() }

        // Split flat token chunks along top-level wrap opportunities before laying out geometry.
        val flatTokens = explodeForWrapping(nodes)
        val wrappedRows = ArrayList<List<MathNode>>()
        var currentRow = ArrayList<MathNode>()
        var currentWidth = 0f

        for (token in flatTokens) {
            val box = measureNodes(listOf(token), paint, paint.textSize, safeMaxW, highlightSet)
            if (currentRow.isNotEmpty() && currentWidth + box.width > safeMaxW) {
                wrappedRows += currentRow
                currentRow = ArrayList()
                currentWidth = 0f
                if (token is MathNode.TextRun && token.text.isBlank()) continue
            }
            currentRow += token
            currentWidth += box.width
        }
        if (currentRow.isNotEmpty()) wrappedRows += currentRow
        if (wrappedRows.isEmpty()) wrappedRows += listOf(MathNode.TextRun(normalized))

        val lines = ArrayList<MeasuredMathLine>(wrappedRows.size)
        var yCursor = 0f
        var maxLineWidth = 0f
        var grandTotalUnits = 0
        val lineGap = paint.textSize * 0.34f

        for (row in wrappedRows) {
            var scale = 1.0f
            var layoutBox = measureNodes(row, paint, paint.textSize * scale, safeMaxW, highlightSet)
            // If an unbreakable single fraction/radical is wider than screen, scale down safely (never clip!).
            while (layoutBox.width > safeMaxW && scale > 0.72f) {
                scale -= 0.08f
                layoutBox = measureNodes(row, paint, paint.textSize * scale, safeMaxW, highlightSet)
            }
            val shifted = layoutBox.commands.map { cmd ->
                val rect = RectF(
                    cmd.rect.left,
                    cmd.rect.top + yCursor,
                    cmd.rect.right,
                    cmd.rect.bottom + yCursor
                )
                val pathCopy = cmd.strokePath?.let { p ->
                    Path(p).apply { offset(0f, yCursor) }
                }
                cmd.copy(
                    rect = rect,
                    baselineY = cmd.baselineY + yCursor,
                    strokePath = pathCopy
                )
            }
            val units = shifted.sumOf { it.charWeight }.coerceAtLeast(1)
            lines += MeasuredMathLine(
                commands = shifted,
                width = layoutBox.width,
                height = layoutBox.height,
                topOffset = yCursor,
                totalUnits = units
            )
            maxLineWidth = max(maxLineWidth, layoutBox.width)
            yCursor += layoutBox.height + lineGap
            grandTotalUnits += units
        }

        val totalHeight = (yCursor - lineGap).coerceAtLeast(paint.textSize * 1.25f)
        return MeasuredExpression(
            raw = raw,
            lines = lines,
            totalWidth = min(maxLineWidth, safeMaxW),
            totalHeight = totalHeight,
            totalUnits = grandTotalUnits.coerceAtLeast(1)
        )
    }

    /**
     * Draws [measured] at top-left `(x, y)` up to [progress] (`0f..1f`), returning the active
     * writing stylus position `(x, y)` in canvas coordinates if still animating, or `null` if done.
     */
    fun draw(
        canvas: Canvas,
        measured: MeasuredExpression,
        x: Float,
        y: Float,
        progress: Float,
        textPaint: TextPaint,
        accentColor: Int,
        highlightBgColor: Int
    ): PointF? {
        if (progress <= 0.001f || measured.lines.isEmpty()) return null
        val p = progress.coerceIn(0f, 1f)
        val targetUnits = (measured.totalUnits * p).coerceAtLeast(1f)
        var drawnUnits = 0f
        var nibPoint: PointF? = null

        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textPaint.color
            style = Paint.Style.FILL
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textPaint.color
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val hlPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = highlightBgColor
            style = Paint.Style.FILL
        }
        val accentTextColor = accentColor

        val origSize = textPaint.textSize
        val origColor = textPaint.color
        val origTypeface = textPaint.typeface

        for (line in measured.lines) {
            for (cmd in line.commands) {
                if (drawnUnits >= targetUnits) break
                val remaining = targetUnits - drawnUnits
                val fraction = (remaining / cmd.charWeight.toFloat()).coerceIn(0f, 1f)

                when (cmd.kind) {
                    DrawCommand.Kind.HIGHLIGHT_PILL -> {
                        val r = RectF(x + cmd.rect.left, y + cmd.rect.top, x + cmd.rect.right, y + cmd.rect.bottom)
                        canvas.drawRoundRect(r, 8f, 8f, hlPaint)
                    }
                    DrawCommand.Kind.FRACTION_BAR -> {
                        val right = x + cmd.rect.left + cmd.rect.width() * fraction
                        barPaint.strokeWidth = cmd.strokeWidth
                        canvas.drawLine(
                            x + cmd.rect.left,
                            y + cmd.rect.centerY(),
                            right,
                            y + cmd.rect.centerY(),
                            barPaint
                        )
                        if (fraction < 1f) {
                            nibPoint = PointF(right, y + cmd.rect.centerY())
                        }
                    }
                    DrawCommand.Kind.PATH_STROKE -> {
                        cmd.strokePath?.let { path ->
                            val copied = Path(path).apply { offset(x, y) }
                            strokePaint.strokeWidth = cmd.strokeWidth
                            strokePaint.color = if (cmd.highlighted) accentTextColor else origColor
                            canvas.drawPath(copied, strokePaint)
                            if (fraction < 1f) {
                                nibPoint = PointF(x + cmd.rect.right, y + cmd.rect.centerY())
                            }
                        }
                    }
                    DrawCommand.Kind.TEXT -> {
                        textPaint.textSize = cmd.textSize
                        textPaint.color = if (cmd.highlighted) accentTextColor else origColor
                        textPaint.typeface = if (cmd.isItalic) {
                            Typeface.create(origTypeface, Typeface.ITALIC)
                        } else {
                            origTypeface
                        }
                        val charCount = max(1, (cmd.text.length * fraction).toInt()).coerceAtMost(cmd.text.length)
                        val visible = if (fraction >= 0.999f) cmd.text else cmd.text.substring(0, charCount)
                        canvas.drawText(visible, x + cmd.rect.left, y + cmd.baselineY, textPaint)
                        if (p < 0.995f) {
                            val wVisible = textPaint.measureText(visible)
                            nibPoint = PointF(x + cmd.rect.left + wVisible, y + cmd.baselineY - cmd.textSize * 0.25f)
                        }
                    }
                }
                drawnUnits += cmd.charWeight * fraction
            }
            if (drawnUnits >= targetUnits) break
        }

        textPaint.textSize = origSize
        textPaint.color = origColor
        textPaint.typeface = origTypeface
        return nibPoint
    }

    private data class BoxResult(
        val commands: List<DrawCommand>,
        val width: Float,
        val height: Float,
        val baselineFromTop: Float
    )

    private fun measureNodes(
        nodes: List<MathNode>,
        basePaint: TextPaint,
        fontSize: Float,
        maxWidth: Float,
        highlights: List<String>
    ): BoxResult {
        if (nodes.isEmpty()) {
            return BoxResult(emptyList(), 0f, fontSize * 1.2f, fontSize * 0.9f)
        }
        val origSize = basePaint.textSize
        basePaint.textSize = fontSize
        val fm = basePaint.fontMetrics
        val lineAscent = -fm.ascent
        val lineDescent = fm.descent
        val defaultHeight = lineAscent + lineDescent
        basePaint.textSize = origSize

        val childBoxes = nodes.map { node ->
            measureSingleNode(node, basePaint, fontSize, maxWidth, highlights)
        }
        val maxAscent = max(lineAscent, childBoxes.maxOfOrNull { it.baselineFromTop } ?: lineAscent)
        val maxDescent = max(lineDescent, childBoxes.maxOfOrNull { it.height - it.baselineFromTop } ?: lineDescent)
        val totalHeight = maxAscent + maxDescent

        val combined = ArrayList<DrawCommand>()
        var xCursor = 0f
        for (box in childBoxes) {
            val yShift = maxAscent - box.baselineFromTop
            for (cmd in box.commands) {
                val r = RectF(
                    cmd.rect.left + xCursor,
                    cmd.rect.top + yShift,
                    cmd.rect.right + xCursor,
                    cmd.rect.bottom + yShift
                )
                val p = cmd.strokePath?.let { path -> Path(path).apply { offset(xCursor, yShift) } }
                combined += cmd.copy(rect = r, baselineY = cmd.baselineY + yShift, strokePath = p)
            }
            xCursor += box.width
        }
        return BoxResult(combined, xCursor, max(defaultHeight, totalHeight), maxAscent)
    }

    private fun measureSingleNode(
        node: MathNode,
        basePaint: TextPaint,
        fontSize: Float,
        maxWidth: Float,
        highlights: List<String>
    ): BoxResult {
        return when (node) {
            is MathNode.Group -> measureNodes(node.children, basePaint, fontSize, maxWidth, highlights)
            is MathNode.TextRun -> {
                val orig = basePaint.textSize
                basePaint.textSize = fontSize
                val w = basePaint.measureText(node.text)
                val fm = basePaint.fontMetrics
                val asc = -fm.ascent
                val desc = fm.descent
                basePaint.textSize = orig
                val isHl = highlights.any { hl ->
                    hl.length >= 2 && node.text.contains(hl, ignoreCase = false)
                }
                val rect = RectF(0f, 0f, w, asc + desc)
                val cmds = buildList {
                    if (isHl && w > 2f) {
                        add(DrawCommand(DrawCommand.Kind.HIGHLIGHT_PILL, RectF(-3f, 0f, w + 3f, asc + desc), charWeight = 0))
                    }
                    add(
                        DrawCommand(
                            kind = DrawCommand.Kind.TEXT,
                            rect = rect,
                            text = node.text,
                            baselineY = asc,
                            textSize = fontSize,
                            charWeight = max(1, node.text.length),
                            highlighted = isHl
                        )
                    )
                }
                BoxResult(cmds, w, asc + desc, asc)
            }
            is MathNode.Fraction -> {
                val innerSize = (fontSize * 0.84f).coerceAtLeast(16f)
                val numBox = measureNodes(node.numerator, basePaint, innerSize, maxWidth, highlights)
                val denBox = measureNodes(node.denominator, basePaint, innerSize, maxWidth, highlights)
                val padX = fontSize * 0.24f
                val gapY = fontSize * 0.14f
                val contentW = max(numBox.width, denBox.width)
                val totalW = contentW + padX * 2f
                val barY = numBox.height + gapY
                val totalH = barY + gapY + denBox.height

                val cmds = ArrayList<DrawCommand>()
                val numX = (totalW - numBox.width) / 2f
                numBox.commands.forEach { cmd ->
                    val r = RectF(cmd.rect.left + numX, cmd.rect.top, cmd.rect.right + numX, cmd.rect.bottom)
                    val p = cmd.strokePath?.let { Path(it).apply { offset(numX, 0f) } }
                    cmds += cmd.copy(rect = r, baselineY = cmd.baselineY, strokePath = p)
                }
                cmds += DrawCommand(
                    kind = DrawCommand.Kind.FRACTION_BAR,
                    rect = RectF(padX * 0.3f, barY - 2f, totalW - padX * 0.3f, barY + 2f),
                    strokeWidth = max(2f, fontSize * 0.065f),
                    charWeight = 2
                )
                val denY = barY + gapY
                val denX = (totalW - denBox.width) / 2f
                denBox.commands.forEach { cmd ->
                    val r = RectF(cmd.rect.left + denX, cmd.rect.top + denY, cmd.rect.right + denX, cmd.rect.bottom + denY)
                    val p = cmd.strokePath?.let { Path(it).apply { offset(denX, denY) } }
                    cmds += cmd.copy(rect = r, baselineY = cmd.baselineY + denY, strokePath = p)
                }
                val baseline = barY - fontSize * 0.22f
                BoxResult(cmds, totalW, totalH, baseline)
            }
            is MathNode.Radical -> {
                val innerBox = measureNodes(node.radicand, basePaint, fontSize * 0.94f, maxWidth, highlights)
                val idxBox = node.index?.let {
                    measureNodes(it, basePaint, (fontSize * 0.58f).coerceAtLeast(13f), maxWidth, highlights)
                }
                val idxW = idxBox?.width?.plus(fontSize * 0.1f) ?: 0f
                val hookW = fontSize * 0.56f
                val topMargin = fontSize * 0.18f
                val totalW = idxW + hookW + innerBox.width + fontSize * 0.16f
                val totalH = innerBox.height + topMargin
                val rootStartX = idxW

                val rootPath = Path().apply {
                    val midY = totalH * 0.58f
                    val botY = totalH - fontSize * 0.06f
                    val topY = topMargin * 0.45f
                    moveTo(rootStartX + hookW * 0.08f, midY)
                    lineTo(rootStartX + hookW * 0.36f, botY)
                    lineTo(rootStartX + hookW * 0.88f, topY)
                    lineTo(totalW - fontSize * 0.06f, topY)
                }
                val cmds = ArrayList<DrawCommand>()
                idxBox?.commands?.forEach { cmd ->
                    cmds += cmd
                }
                cmds += DrawCommand(
                    kind = DrawCommand.Kind.PATH_STROKE,
                    rect = RectF(rootStartX, 0f, totalW, totalH),
                    strokePath = rootPath,
                    strokeWidth = max(2f, fontSize * 0.065f),
                    charWeight = 3
                )
                val contentX = rootStartX + hookW
                innerBox.commands.forEach { cmd ->
                    val r = RectF(cmd.rect.left + contentX, cmd.rect.top + topMargin, cmd.rect.right + contentX, cmd.rect.bottom + topMargin)
                    val p = cmd.strokePath?.let { Path(it).apply { offset(contentX, topMargin) } }
                    cmds += cmd.copy(rect = r, baselineY = cmd.baselineY + topMargin, strokePath = p)
                }
                BoxResult(cmds, totalW, totalH, innerBox.baselineFromTop + topMargin)
            }
            is MathNode.Accent -> {
                val innerBox = measureNodes(node.inner, basePaint, fontSize, maxWidth, highlights)
                val extraTop = fontSize * 0.28f
                val totalH = innerBox.height + extraTop
                val w = max(innerBox.width, fontSize * 0.5f)
                val accentPath = Path().apply {
                    val yLine = extraTop * 0.52f
                    moveTo(2f, yLine)
                    lineTo(w - 2f, yLine)
                    if (node.isVectorArrow) {
                        val ah = fontSize * 0.16f
                        lineTo(w - 2f - ah, yLine - ah * 0.7f)
                        moveTo(w - 2f, yLine)
                        lineTo(w - 2f - ah, yLine + ah * 0.7f)
                    }
                }
                val cmds = ArrayList<DrawCommand>()
                cmds += DrawCommand(
                    kind = DrawCommand.Kind.PATH_STROKE,
                    rect = RectF(0f, 0f, w, extraTop),
                    strokePath = accentPath,
                    strokeWidth = max(1.8f, fontSize * 0.058f),
                    charWeight = 1
                )
                val innerX = (w - innerBox.width) / 2f
                innerBox.commands.forEach { cmd ->
                    val r = RectF(cmd.rect.left + innerX, cmd.rect.top + extraTop, cmd.rect.right + innerX, cmd.rect.bottom + extraTop)
                    val p = cmd.strokePath?.let { Path(it).apply { offset(innerX, extraTop) } }
                    cmds += cmd.copy(rect = r, baselineY = cmd.baselineY + extraTop, strokePath = p)
                }
                BoxResult(cmds, w, totalH, innerBox.baselineFromTop + extraTop)
            }
            is MathNode.Script -> {
                val baseBox = measureNodes(node.base, basePaint, fontSize, maxWidth, highlights)
                val scriptSize = (fontSize * 0.68f).coerceAtLeast(13.5f)
                val supBox = node.sup?.let { measureNodes(it, basePaint, scriptSize, maxWidth, highlights) }
                val subBox = node.sub?.let { measureNodes(it, basePaint, scriptSize, maxWidth, highlights) }

                val supRaise = if (supBox != null) max(fontSize * 0.26f, supBox.height * 0.42f) else 0f
                val subDrop = if (subBox != null) max(fontSize * 0.16f, subBox.height * 0.38f) else 0f

                val totalAscent = max(baseBox.baselineFromTop, baseBox.baselineFromTop + supRaise)
                val totalDescent = max(baseBox.height - baseBox.baselineFromTop, (baseBox.height - baseBox.baselineFromTop) + subDrop)
                val totalH = totalAscent + totalDescent
                val baseShiftY = totalAscent - baseBox.baselineFromTop
                val scriptX = baseBox.width + fontSize * 0.04f
                val scriptW = max(supBox?.width ?: 0f, subBox?.width ?: 0f)
                val totalW = scriptX + scriptW

                val cmds = ArrayList<DrawCommand>()
                baseBox.commands.forEach { cmd ->
                    val r = RectF(cmd.rect.left, cmd.rect.top + baseShiftY, cmd.rect.right, cmd.rect.bottom + baseShiftY)
                    val p = cmd.strokePath?.let { Path(it).apply { offset(0f, baseShiftY) } }
                    cmds += cmd.copy(rect = r, baselineY = cmd.baselineY + baseShiftY, strokePath = p)
                }
                supBox?.let { sb ->
                    val sy = (baseShiftY + baseBox.baselineFromTop - fontSize * 0.45f - sb.baselineFromTop).coerceAtLeast(0f)
                    sb.commands.forEach { cmd ->
                        val r = RectF(cmd.rect.left + scriptX, cmd.rect.top + sy, cmd.rect.right + scriptX, cmd.rect.bottom + sy)
                        val p = cmd.strokePath?.let { Path(it).apply { offset(scriptX, sy) } }
                        cmds += cmd.copy(rect = r, baselineY = cmd.baselineY + sy, strokePath = p)
                    }
                }
                subBox?.let { sb ->
                    val sy = (baseShiftY + baseBox.baselineFromTop + fontSize * 0.12f - sb.baselineFromTop * 0.35f)
                        .coerceAtMost(totalH - sb.height)
                        .coerceAtLeast(0f)
                    sb.commands.forEach { cmd ->
                        val r = RectF(cmd.rect.left + scriptX, cmd.rect.top + sy, cmd.rect.right + scriptX, cmd.rect.bottom + sy)
                        val p = cmd.strokePath?.let { Path(it).apply { offset(scriptX, sy) } }
                        cmds += cmd.copy(rect = r, baselineY = cmd.baselineY + sy, strokePath = p)
                    }
                }
                BoxResult(cmds, totalW, totalH, totalAscent)
            }
        }
    }

    private fun explodeForWrapping(nodes: List<MathNode>): List<MathNode> {
        val out = ArrayList<MathNode>()
        for (n in nodes) {
            when (n) {
                is MathNode.TextRun -> {
                    val buf = StringBuilder()
                    for (ch in n.text) {
                        if (ch == ' ') {
                            if (buf.isNotEmpty()) {
                                out += MathNode.TextRun(buf.toString())
                                buf.clear()
                            }
                            out += MathNode.TextRun(" ")
                        } else if (ch in setOf('+', '-', '=', '→', '⇌', ',')) {
                            if (buf.isNotEmpty()) {
                                out += MathNode.TextRun(buf.toString())
                                buf.clear()
                            }
                            out += MathNode.TextRun(ch.toString())
                        } else {
                            buf.append(ch)
                        }
                    }
                    if (buf.isNotEmpty()) out += MathNode.TextRun(buf.toString())
                }
                is MathNode.Group -> out.addAll(explodeForWrapping(n.children))
                else -> out += n
            }
        }
        return out
    }

    private class AstParser(private val src: String) {
        private var pos = 0

        fun parseSequence(stopChar: Char?): List<MathNode> {
            val out = ArrayList<MathNode>()
            while (pos < src.length) {
                val ch = src[pos]
                if (stopChar != null && ch == stopChar) {
                    pos++
                    break
                }
                val atom = parsePrimary()
                if (atom != null) {
                    out += attachScriptsIfAny(atom)
                }
            }
            return mergeAdjacentText(out)
        }

        private fun parsePrimary(): MathNode? {
            if (pos >= src.length) return null
            val ch = src[pos]
            if (ch == '{') {
                pos++
                val children = parseSequence('}')
                return if (children.size == 1) children.first() else MathNode.Group(children)
            }
            if (ch == '\\') {
                return parseCommand()
            }
            // Handle Unicode √( ... ) style too
            if (ch == '√') {
                pos++
                return if (pos < src.length && (src[pos] == '{' || src[pos] == '(')) {
                    val close = if (src[pos] == '{') '}' else ')'
                    pos++
                    MathNode.Radical(parseSequence(close))
                } else {
                    val arg = parseScriptArg()
                    if (arg.isEmpty()) MathNode.TextRun("√") else MathNode.Radical(arg)
                }
            }
            val start = pos
            while (pos < src.length) {
                val c = src[pos]
                if (c == '\\' || c == '{' || c == '}' || c == '^' || c == '_' || c == '√') break
                pos++
            }
            if (pos == start) {
                pos++
                return MathNode.TextRun(ch.toString())
            }
            return MathNode.TextRun(src.substring(start, pos))
        }

        private fun parseCommand(): MathNode {
            pos++ // consume '\'
            val nameStart = pos
            while (pos < src.length && src[pos].isLetter()) pos++
            val name = src.substring(nameStart, pos)
            skipSpaces()
            return when (name) {
                "frac", "dfrac", "tfrac" -> {
                    val num = parseBracedOrSingle()
                    skipSpaces()
                    val den = parseBracedOrSingle()
                    MathNode.Fraction(num, den)
                }
                "sqrt" -> {
                    val idx = if (pos < src.length && src[pos] == '[') {
                        pos++
                        parseSequence(']')
                    } else null
                    skipSpaces()
                    val inner = parseBracedOrSingle()
                    MathNode.Radical(inner, idx)
                }
                "vec" -> MathNode.Accent(parseBracedOrSingle(), isVectorArrow = true)
                "bar", "overline" -> MathNode.Accent(parseBracedOrSingle(), isVectorArrow = false)
                else -> MathNode.TextRun(name)
            }
        }

        private fun attachScriptsIfAny(base: MathNode): MathNode {
            var sup: List<MathNode>? = null
            var sub: List<MathNode>? = null
            while (pos < src.length) {
                val c = src[pos]
                if (c == '^' && sup == null) {
                    pos++
                    sup = parseScriptArg()
                } else if (c == '_' && sub == null) {
                    pos++
                    sub = parseScriptArg()
                } else {
                    break
                }
            }
            if (sup == null && sub == null) return base
            // Convert trailing word-character in TextRun base so "H_2O" keeps "H" as script base!
            if (base is MathNode.TextRun && base.text.length > 1) {
                val prefix = base.text.dropLast(1)
                val lastChar = base.text.takeLast(1)
                return MathNode.Group(
                    listOf(
                        MathNode.TextRun(prefix),
                        MathNode.Script(listOf(MathNode.TextRun(lastChar)), sup, sub)
                    )
                )
            }
            return MathNode.Script(listOf(base), sup, sub)
        }

        private fun parseBracedOrSingle(): List<MathNode> {
            skipSpaces()
            if (pos >= src.length) return listOf(MathNode.TextRun(""))
            if (src[pos] == '{') {
                pos++
                return parseSequence('}')
            }
            val single = src[pos]
            pos++
            return listOf(MathNode.TextRun(single.toString()))
        }

        private fun parseScriptArg(): List<MathNode> {
            if (pos >= src.length) return emptyList()
            if (src[pos] == '{') {
                pos++
                return parseSequence('}')
            }
            val start = pos
            // Support concise exponents/subscripts like ^-2, ^23, _2
            if (src[pos] == '-' || src[pos] == '+') pos++
            while (pos < src.length && (src[pos].isDigit() || src[pos].isLetter())) {
                if (pos > start && src[pos].isLetter() && src[start].isDigit()) break
                pos++
                if (src[pos - 1].isLetter()) break
            }
            if (pos == start) {
                pos++
            }
            return listOf(MathNode.TextRun(src.substring(start, pos)))
        }

        private fun skipSpaces() {
            while (pos < src.length && src[pos] == ' ') pos++
        }

        private fun mergeAdjacentText(list: List<MathNode>): List<MathNode> {
            val out = ArrayList<MathNode>(list.size)
            for (n in list) {
                if (n is MathNode.TextRun && out.lastOrNull() is MathNode.TextRun) {
                    val prev = out.removeAt(out.lastIndex) as MathNode.TextRun
                    out += MathNode.TextRun(prev.text + n.text)
                } else {
                    out += n
                }
            }
            return out
        }
    }
}
