package com.jarvis.assistant.study

import kotlin.math.max

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
        val size = st.baseSize * 0.92f
        var y = startY + st.margin * 0.4f
        val pw = m.width("$prefix ", size, Face.BODY_BOLD)
        val math = StudyMath.looksLikeMath(question) && !question.contains('\n') && question.length < 90
        if (math) {
            ops.add(DrawOp.Glyphs(prefix, st.margin, y + size, size, Face.BODY_BOLD, Ink.ACCENT))
            val b = StudyMath.layoutWrapped(question, size, st.inner - pw, Ink.INK, m, size * 0.3f)
            var yy = y
            for (box in b) {
                val base = yy + box.asc
                for (op in box.ops) ops.add(op.moved(st.margin + pw, base))
                yy += box.h + size * 0.3f
            }
            y = yy
        } else {
            val lines = wrap(question, size, Face.BODY, st.inner - pw, m)
            lines.forEachIndexed { i, l ->
                if (i == 0) ops.add(DrawOp.Glyphs(prefix, st.margin, y + size, size, Face.BODY_BOLD, Ink.ACCENT))
                ops.add(DrawOp.Glyphs(l, st.margin + pw, y + size, size, Face.BODY, Ink.INK))
                y += size * 1.35f
            }
        }
        val ruleY = y + st.blockGap * 0.3f
        ops.add(DrawOp.Line(st.margin, ruleY, st.width - st.margin, ruleY, 2f, Ink.FAINT))
        return BoardBlock(0, StepKind.STEP, ops, emptyList(), startY, ruleY + st.blockGap, isQuestion = true)
    }

    fun layoutStep(step: StudyStep, startY: Float, st: BoardStyle, m: TextMetrics, isFinalBlock: Boolean = false): BoardBlock {
        val ops = ArrayList<DrawOp>()
        val notes = ArrayList<DrawOp>()
        var y = startY
        val size = st.baseSize
        val ink = when {
            isFinalBlock || step.isFinalHighlight -> Ink.RESULT
            step.kind == StepKind.FORMULA -> Ink.ACCENT
            step.kind == StepKind.GIVEN || step.kind == StepKind.REQUIRED -> Ink.INK
            else -> Ink.INK
        }
        val caption = if (isFinalBlock) "Answer" else step.kind.caption
        var indent = 0f
        if (caption.isNotEmpty()) {
            ops.add(DrawOp.Glyphs(caption.uppercase(), st.margin, y + st.captionSize, st.captionSize, Face.BODY_BOLD, if (isFinalBlock) Ink.RESULT else Ink.NOTE))
            y += st.captionSize * 1.5f
        }
        val x0 = st.margin + indent
        val maxW = st.inner - indent
        if (step.content.isNotEmpty()) {
            when {
                step.kind == StepKind.CODE -> {
                    val cs = size * 0.62f
                    val lines = step.content.split('\n').flatMap { wrap(it, cs, Face.CODE, maxW - 20f, m).ifEmpty { listOf("") } }
                    val boxTop = y
                    val boxH = lines.size * cs * 1.35f + 16f
                    ops.add(DrawOp.Rect(x0, boxTop, maxW, boxH, 1.5f, Ink.FAINT))
                    var ly = boxTop + 8f
                    for (l in lines) { ops.add(DrawOp.Glyphs(l, x0 + 10f, ly + cs, cs, Face.CODE, Ink.INK)); ly += cs * 1.35f }
                    y = boxTop + boxH
                }
                step.proseMode -> {
                    val lines = wrap(step.content, size * 0.86f, Face.BODY, maxW, m)
                    for (l in lines) { ops.add(DrawOp.Glyphs(l, x0, y + size * 0.86f, size * 0.86f, Face.BODY, ink)); y += size * 0.86f * 1.38f }
                }
                else -> {
                    val rows = step.content.split('\n').filter { it.isNotBlank() }
                    for (row in rows) {
                        val boxes = StudyMath.layoutWrapped(row, size, maxW, ink, m, size * 0.3f)
                        for (box in boxes) {
                            val base = y + box.asc
                            for (op in box.ops) ops.add(op.moved(x0, base))
                            y += box.h + size * 0.22f
                        }
                    }
                    if (isFinalBlock || step.isFinalHighlight) {
                        // a hand-drawn box round the answer, written after the answer itself
                        val top = (if (caption.isNotEmpty()) startY + st.captionSize * 1.5f else startY) - 6f
                        ops.add(DrawOp.Rect(x0 - 10f, top, maxW + 20f, y - top + 2f, 3f, Ink.RESULT))
                    }
                }
            }
        }
        step.diagram?.let { d ->
            val laid = StudyDiagramLayout.layout(d, maxW.coerceAtMost(st.width * 0.9f), st.width * 0.7f, m, max(2f, size * 0.07f))
            for (op in laid.ops) ops.add(op.moved(x0, y + 6f))
            y += laid.height + 10f
        }
        if (step.explanation.isNotEmpty()) {
            val ns = st.noteSize
            y += 2f
            val lines = wrap(step.explanation, ns, Face.BODY, maxW - 14f, m)
            for (l in lines) { notes.add(DrawOp.Glyphs(l, x0 + 14f, y + ns, ns, Face.BODY, Ink.NOTE)); y += ns * 1.38f }
            notes.add(0, DrawOp.Line(x0 + 4f, y - lines.size * ns * 1.38f + 4f, x0 + 4f, y - 2f, 2f, Ink.NOTE))
        }
        return BoardBlock(step.index, step.kind, ops, notes, startY, y + st.blockGap, isFinal = isFinalBlock || step.isFinalHighlight)
    }

    fun layoutFinal(answer: String, explanation: String, startY: Float, st: BoardStyle, m: TextMetrics): BoardBlock {
        val step = StudyStep(index = Int.MAX_VALUE, kind = StepKind.RESULT, content = answer, explanation = explanation,
            proseMode = !StudyMath.looksLikeMath(answer))
        return layoutStep(step, startY, st, m, isFinalBlock = true)
    }
}
