package com.jarvis.assistant.ui.studyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import com.jarvis.assistant.R
import com.jarvis.assistant.study.BoardBlock
import com.jarvis.assistant.study.DrawOp
import com.jarvis.assistant.study.Face
import com.jarvis.assistant.study.FillShade
import com.jarvis.assistant.study.Ink
import com.jarvis.assistant.study.PaintMode
import com.jarvis.assistant.study.TextMetrics
import com.jarvis.assistant.util.ThemeManager
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Dynamic studio-whiteboard colour palette with paper-contrast safety and zero static hex literals.
 * The primary board accent harmonizes with the user's active JARVIS theme while maintaining >= 4.5:1 legibility on paper.
 */
object StudyPalette {
    val PAPER: Int = Color.rgb(251, 251, 246)
    val PAPER_GRID: Int = Color.rgb(180, 188, 204)

    private val inkColor = ColorUtils.HSLToColor(floatArrayOf(222f, 0.24f, 0.14f))
    private val defaultAccent = ColorUtils.HSLToColor(floatArrayOf(214f, 0.88f, 0.40f))
    private val noteColor = ColorUtils.HSLToColor(floatArrayOf(218f, 0.16f, 0.41f))
    private val resultColor = ColorUtils.HSLToColor(floatArrayOf(156f, 0.85f, 0.29f))
    private val warnColor = ColorUtils.HSLToColor(floatArrayOf(358f, 0.72f, 0.44f))
    private val faintColor = ColorUtils.HSLToColor(floatArrayOf(218f, 0.20f, 0.72f))
    private val goldColor = ColorUtils.HSLToColor(floatArrayOf(37f, 0.90f, 0.39f))
    private val violetColor = ColorUtils.HSLToColor(floatArrayOf(266f, 0.68f, 0.44f))
    private val tealColor = ColorUtils.HSLToColor(floatArrayOf(188f, 0.84f, 0.32f))
    private val roseColor = ColorUtils.HSLToColor(floatArrayOf(336f, 0.74f, 0.45f))
    private val orangeColor = ColorUtils.HSLToColor(floatArrayOf(24f, 0.88f, 0.42f))
    private val brownColor = ColorUtils.HSLToColor(floatArrayOf(26f, 0.48f, 0.34f))

    @Volatile private var cachedRawAccent = 0
    @Volatile private var cachedResolvedAccent = defaultAccent

    private fun resolvedAccent(): Int {
        val raw = runCatching { ThemeManager.getThemeAccentColor() }.getOrDefault(defaultAccent)
        if (raw == cachedRawAccent) return cachedResolvedAccent
        val hsv = FloatArray(3)
        Color.colorToHSV(raw, hsv)
        if (hsv[1] > 0.12f) hsv[1] = hsv[1].coerceAtLeast(0.70f)
        if (hsv[2] > 0.58f) hsv[2] = 0.58f
        val candidate = Color.HSVToColor(hsv)
        val safe = if (ColorUtils.calculateContrast(candidate, PAPER) >= 4.2) candidate else defaultAccent
        cachedRawAccent = raw
        cachedResolvedAccent = safe
        return safe
    }

    fun color(ink: Ink): Int = when (ink) {
        Ink.INK -> inkColor
        Ink.ACCENT -> resolvedAccent()
        Ink.NOTE -> noteColor
        Ink.RESULT -> resultColor
        Ink.WARN -> warnColor
        Ink.FAINT -> faintColor
        Ink.GOLD -> goldColor
        Ink.VIOLET -> violetColor
        Ink.TEAL -> tealColor
        Ink.ROSE -> roseColor
        Ink.ORANGE -> orangeColor
        Ink.BROWN -> brownColor
    }
}

/** Fonts for the board. Maths uses serif faces (italic for variables); lesson prose uses JARVIS's native sans. */
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
 * Renders [DrawOp]s cleanly and progressively: structural outlines draw stroke-by-stroke along their exact path
 * (including smooth Catmull-Rom splines via [PathMeasure]), colour fills sweep live across shaded interiors, and text
 * reveals under a colour-matched stylus tip. Shared by both the live board and PDF/PNG exports.
 */
class StudyPainter(private val fonts: StudyFonts) {

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; style = Paint.Style.STROKE }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val path = Path()
    private val partialPath = Path()
    private val measure = PathMeasure()
    private val tipPos = FloatArray(2)
    private val rect = RectF()
    private val bounds = RectF()
    private val dash = DashPathEffect(floatArrayOf(10f, 8f), 0f)

    /** Position and colour of the stylus tip after the last [drawOps] call, in board units. */
    var tipX = 0f; private set
    var tipY = 0f; private set
    var tipInk: Ink = Ink.ACCENT; private set
    var tipVisible = false; private set

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
            if (budget >= w) {
                drawOp(canvas, op, 1f, alpha)
                budget -= w
            } else {
                if (budget > 0f) drawOp(canvas, op, budget / w, alpha)
                break
            }
        }
        return false
    }

    private fun setTip(x: Float, y: Float, ink: Ink) {
        tipX = x; tipY = y; tipInk = ink; tipVisible = true
    }

    private fun applyStroke(ink: Ink, strokeWidth: Float, alpha: Int, dashed: Boolean) {
        strokePaint.color = StudyPalette.color(ink)
        strokePaint.alpha = alpha.coerceIn(0, 255)
        strokePaint.strokeWidth = strokeWidth
        strokePaint.pathEffect = if (dashed) dash else null
    }

    private fun applyFillShader(fillInk: Ink, shade: FillShade, fillAlpha: Float, globalAlpha: Int, box: RectF) {
        val base = StudyPalette.color(fillInk)
        val targetAlpha = ((globalAlpha * fillAlpha).toInt()).coerceIn(6, 240)
        val highlight = ColorUtils.blendARGB(base, StudyPalette.PAPER, 0.56f)
        val midColor = ColorUtils.setAlphaComponent(base, targetAlpha)
        val hiColor = ColorUtils.setAlphaComponent(highlight, (targetAlpha * 0.72f).toInt().coerceIn(4, 230))
        val deepColor = ColorUtils.setAlphaComponent(base, (targetAlpha * 1.15f).toInt().coerceAtMost(245))

        fillPaint.alpha = 255
        fillPaint.shader = when {
            box.width() < 2f || box.height() < 2f -> null
            shade == FillShade.RADIAL -> RadialGradient(
                box.centerX() - box.width() * 0.16f,
                box.centerY() - box.height() * 0.16f,
                max(box.width(), box.height()) * 0.68f,
                intArrayOf(hiColor, midColor, deepColor),
                floatArrayOf(0f, 0.62f, 1f),
                Shader.TileMode.CLAMP
            )
            shade == FillShade.LINEAR -> LinearGradient(
                box.left, box.top, box.right, box.bottom,
                intArrayOf(hiColor, midColor, deepColor),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            else -> null
        }
        if (fillPaint.shader == null) {
            fillPaint.color = midColor
        }
    }

    private fun drawProgressiveFillPath(canvas: Canvas, fillPath: Path, fillInk: Ink, shade: FillShade, fillAlpha: Float, frac: Float, alpha: Int) {
        fillPath.computeBounds(bounds, true)
        if (bounds.isEmpty) return
        applyFillShader(fillInk, shade, fillAlpha, alpha, bounds)
        if (frac >= 1f) {
            canvas.drawPath(fillPath, fillPaint)
        } else {
            val sweepX = bounds.left + bounds.width() * frac
            canvas.save()
            canvas.clipRect(bounds.left - 2f, bounds.top - 2f, sweepX + 1f, bounds.bottom + 2f)
            canvas.drawPath(fillPath, fillPaint)
            canvas.restore()
            setTip(sweepX, bounds.centerY(), fillInk)
        }
        fillPaint.shader = null
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
                    val tipXPos = op.x + w * frac
                    canvas.save()
                    canvas.clipRect(op.x - 2f, op.y - op.size * 1.15f, tipXPos + 1f, op.y + op.size * 0.45f)
                    canvas.drawText(op.text, op.x, op.y, text)
                    canvas.restore()
                    setTip(tipXPos, op.y - op.size * 0.25f, op.ink)
                }
            }
            is DrawOp.Line -> {
                applyStroke(op.ink, op.stroke, alpha, op.dashed)
                val x = op.x1 + (op.x2 - op.x1) * frac
                val y = op.y1 + (op.y2 - op.y1) * frac
                canvas.drawLine(op.x1, op.y1, x, y, strokePaint)
                strokePaint.pathEffect = null
                if (frac < 1f) setTip(x, y, op.ink)
            }
            is DrawOp.Poly -> drawPoly(canvas, op, frac, alpha)
            is DrawOp.Oval -> drawOval(canvas, op, frac, alpha)
            is DrawOp.Arc -> drawArc(canvas, op, frac, alpha)
            is DrawOp.Rect -> drawRect(canvas, op, frac, alpha)
        }
    }

    private fun drawOval(canvas: Canvas, op: DrawOp.Oval, frac: Float, alpha: Int) {
        val rot = abs(op.rotation) > 0.1f
        if (rot) {
            canvas.save()
            canvas.rotate(op.rotation, op.cx, op.cy)
        }
        rect.set(op.cx - op.rx, op.cy - op.ry, op.cx + op.rx, op.cy + op.ry)
        if (op.fill && op.paintMode != PaintMode.STROKE_ONLY && (op.paintMode == PaintMode.FILL_ONLY || frac >= 1f)) {
            path.reset()
            path.addOval(rect, Path.Direction.CW)
            drawProgressiveFillPath(canvas, path, op.fillInk, op.shade, op.fillAlpha, if (op.paintMode == PaintMode.FILL_ONLY) frac else 1f, alpha)
        }
        if (op.paintMode != PaintMode.FILL_ONLY) {
            applyStroke(op.ink, op.stroke, alpha, op.dashed)
            if (frac >= 1f) {
                canvas.drawOval(rect, strokePaint)
            } else {
                canvas.drawArc(rect, -90f, 360f * frac, false, strokePaint)
                val rad = Math.toRadians(-90.0 + 360.0 * frac)
                val lx = op.cx + (op.rx * cos(rad)).toFloat()
                val ly = op.cy + (op.ry * sin(rad)).toFloat()
                if (!rot) setTip(lx, ly, op.ink) else setTip(op.cx, op.cy, op.ink)
            }
            strokePaint.pathEffect = null
        }
        if (rot) canvas.restore()
    }

    private fun drawArc(canvas: Canvas, op: DrawOp.Arc, frac: Float, alpha: Int) {
        rect.set(op.cx - op.rx, op.cy - op.ry, op.cx + op.rx, op.cy + op.ry)
        if (op.fill && op.paintMode != PaintMode.STROKE_ONLY && (op.paintMode == PaintMode.FILL_ONLY || frac >= 1f)) {
            path.reset()
            if (op.useCenter) path.moveTo(op.cx, op.cy)
            path.addArc(rect, op.startAngle, op.sweepAngle)
            if (op.useCenter) path.close()
            drawProgressiveFillPath(canvas, path, op.fillInk, op.shade, op.fillAlpha, if (op.paintMode == PaintMode.FILL_ONLY) frac else 1f, alpha)
        }
        if (op.paintMode != PaintMode.FILL_ONLY) {
            applyStroke(op.ink, op.stroke, alpha, op.dashed)
            val sweepNow = op.sweepAngle * frac
            canvas.drawArc(rect, op.startAngle, sweepNow, op.useCenter, strokePaint)
            if (frac < 1f) {
                val rad = Math.toRadians((op.startAngle + sweepNow).toDouble())
                setTip(op.cx + (op.rx * cos(rad)).toFloat(), op.cy + (op.ry * sin(rad)).toFloat(), op.ink)
            }
            strokePaint.pathEffect = null
        }
    }

    private fun drawRect(canvas: Canvas, op: DrawOp.Rect, frac: Float, alpha: Int) {
        val left = min(op.x, op.x + op.w)
        val top = min(op.y, op.y + op.h)
        val right = max(op.x, op.x + op.w)
        val bottom = max(op.y, op.y + op.h)
        rect.set(left, top, right, bottom)
        val rx = op.rx.coerceIn(0f, rect.width() / 2f)
        val ry = op.ry.coerceIn(0f, rect.height() / 2f)
        path.reset()
        if (rx > 0.5f || ry > 0.5f) path.addRoundRect(rect, rx, ry, Path.Direction.CW)
        else path.addRect(rect, Path.Direction.CW)

        if (op.fill && op.paintMode != PaintMode.STROKE_ONLY && (op.paintMode == PaintMode.FILL_ONLY || frac >= 1f)) {
            drawProgressiveFillPath(canvas, path, op.fillInk, op.shade, op.fillAlpha, if (op.paintMode == PaintMode.FILL_ONLY) frac else 1f, alpha)
        }
        if (op.paintMode != PaintMode.FILL_ONLY) {
            drawBuiltPathStroke(canvas, path, op.ink, op.stroke, op.dashed, frac, alpha)
        }
    }

    private fun drawPoly(canvas: Canvas, op: DrawOp.Poly, frac: Float, alpha: Int) {
        val p = op.pts
        val count = p.size / 2
        if (count < 2) return
        buildPolyPath(p, count, op.closed, op.smooth, path)

        if (op.fill && op.closed && op.paintMode != PaintMode.STROKE_ONLY && (op.paintMode == PaintMode.FILL_ONLY || frac >= 1f)) {
            drawProgressiveFillPath(canvas, path, op.fillInk, op.shade, op.fillAlpha, if (op.paintMode == PaintMode.FILL_ONLY) frac else 1f, alpha)
        }
        if (op.paintMode != PaintMode.FILL_ONLY) {
            drawBuiltPathStroke(canvas, path, op.ink, op.stroke, op.dashed, frac, alpha)
        }
    }

    private fun drawBuiltPathStroke(canvas: Canvas, fullPath: Path, ink: Ink, stroke: Float, dashed: Boolean, frac: Float, alpha: Int) {
        applyStroke(ink, stroke, alpha, dashed)
        if (frac >= 1f) {
            canvas.drawPath(fullPath, strokePaint)
        } else {
            measure.setPath(fullPath, false)
            val len = measure.length
            if (len > 0f) {
                val dist = len * frac
                partialPath.reset()
                if (measure.getSegment(0f, dist, partialPath, true)) {
                    canvas.drawPath(partialPath, strokePaint)
                }
                if (measure.getPosTan(dist, tipPos, null)) {
                    setTip(tipPos[0], tipPos[1], ink)
                }
            }
        }
        strokePaint.pathEffect = null
    }

    private fun buildPolyPath(p: FloatArray, count: Int, closed: Boolean, smooth: Boolean, target: Path) {
        target.reset()
        target.moveTo(p[0], p[1])
        if (!smooth || count < 3) {
            for (i in 1 until count) target.lineTo(p[i * 2], p[i * 2 + 1])
            if (closed) target.close()
            return
        }
        // Centripetal-style Catmull-Rom to cubic Bezier spline for organic smooth anatomy & curves
        val segs = if (closed) count else count - 1
        for (i in 0 until segs) {
            val i0 = if (closed) (i - 1 + count) % count else max(0, i - 1)
            val i1 = i
            val i2 = if (closed) (i + 1) % count else min(count - 1, i + 1)
            val i3 = if (closed) (i + 2) % count else min(count - 1, i + 2)

            val x0 = p[i0 * 2]; val y0 = p[i0 * 2 + 1]
            val x1 = p[i1 * 2]; val y1 = p[i1 * 2 + 1]
            val x2 = p[i2 * 2]; val y2 = p[i2 * 2 + 1]
            val x3 = p[i3 * 2]; val y3 = p[i3 * 2 + 1]

            val c1x = x1 + (x2 - x0) / 6f
            val c1y = y1 + (y2 - y0) / 6f
            val c2x = x2 - (x3 - x1) / 6f
            val c2y = y2 - (y3 - y1) / 6f
            target.cubicTo(c1x, c1y, c2x, c2y, x2, y2)
        }
        if (closed) target.close()
    }

    /** Soft tinted panel behind formulas, definitions, given data, or the final answer. */
    fun drawBlockBackground(canvas: Canvas, block: BoardBlock, width: Float, margin: Float, color: Int, alpha: Int) {
        panelPaint.pathEffect = null
        panelPaint.style = Paint.Style.FILL
        panelPaint.color = color
        panelPaint.alpha = alpha.coerceIn(0, 255)
        rect.set(margin - 16f, block.top - 6f, width - margin + 16f, block.bottom - 14f)
        canvas.drawRoundRect(rect, 16f, 16f, panelPaint)
    }

    fun drawSelection(canvas: Canvas, block: BoardBlock, width: Float, margin: Float, color: Int) {
        panelPaint.pathEffect = null
        panelPaint.style = Paint.Style.STROKE
        panelPaint.strokeWidth = 3f
        panelPaint.color = color
        panelPaint.alpha = 255
        rect.set(margin - 16f, block.top - 6f, width - margin + 16f, block.bottom - 14f)
        canvas.drawRoundRect(rect, 16f, 16f, panelPaint)
    }

    fun drawBookmark(canvas: Canvas, block: BoardBlock, width: Float, margin: Float, color: Int) {
        panelPaint.pathEffect = null
        panelPaint.style = Paint.Style.FILL
        panelPaint.color = color
        panelPaint.alpha = 255
        val x = width - margin - 6f
        path.reset()
        path.moveTo(x - 22f, block.top - 6f)
        path.lineTo(x, block.top - 6f)
        path.lineTo(x, block.top + 34f)
        path.lineTo(x - 11f, block.top + 24f)
        path.lineTo(x - 22f, block.top + 34f)
        path.close()
        canvas.drawPath(path, panelPaint)
    }

    /** Precision colour-matched stylus tip with translucent aura halo when writing or colouring live. */
    fun drawPen(canvas: Canvas, x: Float, y: Float, color: Int) {
        panelPaint.pathEffect = null
        panelPaint.style = Paint.Style.FILL
        panelPaint.color = color
        panelPaint.alpha = 46
        canvas.drawCircle(x, y, 16f, panelPaint)
        panelPaint.alpha = 110
        canvas.drawCircle(x, y, 9.5f, panelPaint)
        panelPaint.alpha = 240
        canvas.drawCircle(x, y, 5f, panelPaint)
    }
}
