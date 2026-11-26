package com.jarvis.assistant.ui.studyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.study.BoardBlock
import com.jarvis.assistant.study.DrawOp
import com.jarvis.assistant.study.Face
import com.jarvis.assistant.study.Ink
import com.jarvis.assistant.study.TextMetrics
import kotlin.math.hypot

/** Pen colours on the whiteboard. The same colours are used when exporting, so a PDF looks like the board. */
object StudyPalette {
    const val PAPER = 0xFFFBFBF6.toInt()
    fun color(ink: Ink): Int = when (ink) {
        Ink.INK -> 0xFF1A1F2B.toInt()
        Ink.ACCENT -> 0xFF0B63C5.toInt()
        Ink.NOTE -> 0xFF5A6578.toInt()
        Ink.RESULT -> 0xFF0A8A56.toInt()
        Ink.WARN -> 0xFFC62828.toInt()
        Ink.FAINT -> 0xFFB3BAC6.toInt()
    }
}

/** Fonts for the board. Maths is set in a serif face (italic for variables); lesson text uses JARVIS's own sans. */
class StudyFonts(context: Context) {
    private val sans: Typeface = ResourcesCompat.getFont(context, R.font.jarvis_sans) ?: Typeface.SANS_SERIF
    private val mathRoman: Typeface = Typeface.SERIF
    private val mathItalic: Typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
    private val bold: Typeface = Typeface.create(sans, Typeface.BOLD)
    private val code: Typeface = Typeface.MONOSPACE

    fun of(face: Face): Typeface = when (face) {
        Face.MATH_ITALIC -> mathItalic
        Face.MATH_ROMAN -> mathRoman
        Face.BODY -> sans
        Face.BODY_BOLD -> bold
        Face.CODE -> code
    }
}

/** Measures text with real Paint metrics for the layout engine. One instance per thread. */
class AndroidMetrics(private val fonts: StudyFonts) : TextMetrics {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    override fun width(text: String, size: Float, face: Face): Float {
        paint.typeface = fonts.of(face)
        paint.textSize = size
        return paint.measureText(text)
    }
}

/**
 * Draws [DrawOp]s, optionally only the first part of them, so the board can be written progressively: lines and shapes are
 * drawn along their length, text is revealed left to right under a pen tip. Used by the live board and by every export.
 */
class StudyPainter(private val fonts: StudyFonts) {

    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val path = Path()
    private val rect = RectF()
    private val dash = DashPathEffect(floatArrayOf(10f, 8f), 0f)

    /** Where the pen tip is after the last [drawOps] call, in board units. */
    var tipX = 0f; private set
    var tipY = 0f; private set
    var tipVisible = false; private set

    /**
     * Draws [ops] up to [fraction] (0..1) of their total pen travel. [alpha] (0..255) fades everything, for erasing.
     * Returns true when the whole list was drawn.
     */
    fun drawOps(canvas: Canvas, ops: List<DrawOp>, fraction: Float, alpha: Int = 255): Boolean {
        tipVisible = false
        if (ops.isEmpty()) return true
        val f = fraction.coerceIn(0f, 1f)
        if (f >= 1f) {
            for (op in ops) drawOp(canvas, op, 1f, alpha)
            return true
        }
        var total = 0f
        for (op in ops) total += op.weight.coerceAtLeast(1f)
        var budget = total * f
        for (op in ops) {
            val w = op.weight.coerceAtLeast(1f)
            if (budget >= w) { drawOp(canvas, op, 1f, alpha); budget -= w }
            else {
                if (budget > 0f) drawOp(canvas, op, budget / w, alpha)
                break
            }
        }
        return false
    }

    private fun setTip(x: Float, y: Float) { tipX = x; tipY = y; tipVisible = true }

    private fun stylePaint(p: Paint, op: DrawOp, strokeWidth: Float, alpha: Int, fill: Boolean) {
        p.color = StudyPalette.color(op.ink)
        p.alpha = (alpha * (p.alpha / 255f)).toInt().coerceIn(0, 255)
        p.style = if (fill) Paint.Style.FILL else Paint.Style.STROKE
        p.strokeWidth = strokeWidth
    }

    private fun drawOp(canvas: Canvas, op: DrawOp, frac: Float, alpha: Int) {
        when (op) {
            is DrawOp.Glyphs -> {
                text.typeface = fonts.of(op.face)
                text.textSize = op.size
                text.style = Paint.Style.FILL
                text.color = StudyPalette.color(op.ink)
                text.alpha = alpha
                if (frac >= 1f) {
                    canvas.drawText(op.text, op.x, op.y, text)
                } else {
                    val w = text.measureText(op.text)
                    canvas.save()
                    canvas.clipRect(op.x - 2f, op.y - op.size, op.x + w * frac + 1f, op.y + op.size * 0.4f)
                    canvas.drawText(op.text, op.x, op.y, text)
                    canvas.restore()
                    setTip(op.x + w * frac, op.y - op.size * 0.3f)
                }
            }
            is DrawOp.Line -> {
                ink.pathEffect = if (op.dashed) dash else null
                stylePaint(ink, op, op.stroke, alpha, false)
                val x = op.x1 + (op.x2 - op.x1) * frac
                val y = op.y1 + (op.y2 - op.y1) * frac
                canvas.drawLine(op.x1, op.y1, x, y, ink)
                ink.pathEffect = null
                if (frac < 1f) setTip(x, y)
            }
            is DrawOp.Poly -> drawPoly(canvas, op, frac, alpha)
            is DrawOp.Oval -> {
                rect.set(op.cx - op.rx, op.cy - op.ry, op.cx + op.rx, op.cy + op.ry)
                if (op.fill && frac >= 1f) {
                    stylePaint(ink, op, 1f, (alpha * 0.9f).toInt(), true)
                    canvas.drawOval(rect, ink)
                }
                ink.pathEffect = if (op.dashed) dash else null
                stylePaint(ink, op, op.stroke, alpha, false)
                if (frac >= 1f) canvas.drawOval(rect, ink)
                else {
                    canvas.drawArc(rect, -90f, 360f * frac, false, ink)
                    val a = Math.toRadians(-90.0 + 360.0 * frac)
                    setTip(op.cx + (op.rx * Math.cos(a)).toFloat(), op.cy + (op.ry * Math.sin(a)).toFloat())
                }
                ink.pathEffect = null
            }
            is DrawOp.Rect -> {
                if (op.fill && frac >= 1f) {
                    stylePaint(ink, op, 1f, (alpha * 0.9f).toInt(), true)
                    canvas.drawRect(op.x, op.y, op.x + op.w, op.y + op.h, ink)
                }
                val pts = floatArrayOf(op.x, op.y, op.x + op.w, op.y, op.x + op.w, op.y + op.h, op.x, op.y + op.h)
                drawPoly(canvas, DrawOp.Poly(pts, op.stroke, op.ink, closed = true), frac, alpha)
            }
        }
    }

    private fun drawPoly(canvas: Canvas, op: DrawOp.Poly, frac: Float, alpha: Int) {
        val p = op.pts
        if (p.size < 4) return
        path.reset()
        path.moveTo(p[0], p[1])
        val n = p.size / 2
        if (frac >= 1f) {
            for (i in 1 until n) path.lineTo(p[i * 2], p[i * 2 + 1])
            if (op.closed) path.close()
            if (op.fill) { stylePaint(ink, op, 1f, (alpha * 0.9f).toInt(), true); canvas.drawPath(path, ink) }
        } else {
            // walk the outline until the pen has used up its share of the length
            var remaining = op.weight * frac
            var lx = p[0]; var ly = p[1]
            var i = 1
            while (i <= n && remaining > 0f) {
                val ni = if (i < n) i else 0
                if (i == n && !op.closed) break
                val nx = p[ni * 2]; val ny = p[ni * 2 + 1]
                val seg = hypot(nx - lx, ny - ly)
                if (seg <= remaining) { path.lineTo(nx, ny); remaining -= seg; lx = nx; ly = ny }
                else {
                    val t = if (seg > 0f) remaining / seg else 0f
                    lx += (nx - lx) * t; ly += (ny - ly) * t
                    path.lineTo(lx, ly); remaining = 0f
                }
                i++
            }
            setTip(lx, ly)
        }
        ink.pathEffect = if (op.dashed) dash else null
        stylePaint(ink, op, op.stroke, alpha, false)
        canvas.drawPath(path, ink)
        ink.pathEffect = null
    }

    /** Soft tinted panel behind a formula or the final answer. */
    fun drawBlockBackground(canvas: Canvas, block: BoardBlock, width: Float, margin: Float, color: Int, alpha: Int) {
        ink.pathEffect = null
        ink.style = Paint.Style.FILL
        ink.color = color
        ink.alpha = alpha.coerceIn(0, 255)
        rect.set(margin - 16f, block.top - 6f, width - margin + 16f, block.bottom - 14f)
        canvas.drawRoundRect(rect, 14f, 14f, ink)
    }

    fun drawSelection(canvas: Canvas, block: BoardBlock, width: Float, margin: Float, color: Int) {
        ink.pathEffect = null
        ink.style = Paint.Style.STROKE
        ink.strokeWidth = 3f
        ink.color = color
        ink.alpha = 255
        rect.set(margin - 16f, block.top - 6f, width - margin + 16f, block.bottom - 14f)
        canvas.drawRoundRect(rect, 14f, 14f, ink)
    }

    fun drawBookmark(canvas: Canvas, block: BoardBlock, width: Float, margin: Float, color: Int) {
        ink.pathEffect = null
        ink.style = Paint.Style.FILL
        ink.color = color
        ink.alpha = 255
        val x = width - margin - 6f
        path.reset()
        path.moveTo(x - 22f, block.top - 6f)
        path.lineTo(x, block.top - 6f)
        path.lineTo(x, block.top + 34f)
        path.lineTo(x - 11f, block.top + 24f)
        path.lineTo(x - 22f, block.top + 34f)
        path.close()
        canvas.drawPath(path, ink)
    }

    fun drawPen(canvas: Canvas, x: Float, y: Float, color: Int) {
        ink.pathEffect = null
        ink.style = Paint.Style.FILL
        ink.color = color
        ink.alpha = 200
        canvas.drawCircle(x, y, 6f, ink)
    }
}
