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
import com.jarvis.assistant.study.PathCmd
import com.jarvis.assistant.study.StepKind
import com.jarvis.assistant.study.TextMetrics
import com.jarvis.assistant.util.ThemeManager
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import java.util.WeakHashMap

/**
 * Dynamic studio-whiteboard colour palette with paper-contrast safety and zero static hex literals.
 * The primary board accent harmonizes with the user's active JARVIS theme while maintaining >= 4.5:1 legibility on paper.
 */
object StudyPalette {
    val PAPER: Int = Color.rgb(255, 255, 255)
    val PAPER_GRID: Int = Color.rgb(212, 218, 228)

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
        Ink.PAPER -> PAPER
    }
}

/** Fonts for the clean printed board. Editorial Newsreader serif for maths and equations; crisp JARVIS sans for prose. */
class StudyFonts(context: Context) {
    private val sans: Typeface = ResourcesCompat.getFont(context, R.font.jarvis_sans_regular)
        ?: ResourcesCompat.getFont(context, R.font.jarvis_sans)
        ?: Typeface.SANS_SERIF
    private val mathRoman: Typeface = ResourcesCompat.getFont(context, R.font.jarvis_serif_regular)
        ?: ResourcesCompat.getFont(context, R.font.jarvis_serif)
        ?: Typeface.SERIF
    private val mathItalic: Typeface = Typeface.create(mathRoman, Typeface.ITALIC)
    private val bold: Typeface = ResourcesCompat.getFont(context, R.font.jarvis_sans_semibold)
        ?: Typeface.create(sans, Typeface.BOLD)
    private val code: Typeface = ResourcesCompat.getFont(context, R.font.jarvis_mono_regular)
        ?: ResourcesCompat.getFont(context, R.font.jarvis_mono)
        ?: Typeface.MONOSPACE

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
        val targetAlpha = ((globalAlpha * fillAlpha).toInt()).coerceIn(6, 245)
        if (fillInk == Ink.PAPER) {
            fillPaint.shader = null
            fillPaint.color = ColorUtils.setAlphaComponent(base, targetAlpha)
            return
        }
        val highlight = ColorUtils.blendARGB(base, StudyPalette.PAPER, 0.44f)
        val shadowBase = ColorUtils.blendARGB(base, StudyPalette.color(Ink.INK), 0.14f)
        val midColor = ColorUtils.setAlphaComponent(base, targetAlpha)
        val hiColor = ColorUtils.setAlphaComponent(highlight, (targetAlpha * 0.78f).toInt().coerceIn(4, 235))
        val deepColor = ColorUtils.setAlphaComponent(shadowBase, (targetAlpha * 1.18f).toInt().coerceAtMost(248))

        fillPaint.alpha = 255
        fillPaint.shader = when {
            box.width() < 2f || box.height() < 2f -> null
            shade == FillShade.RADIAL -> RadialGradient(
                box.centerX() - box.width() * 0.18f,
                box.centerY() - box.height() * 0.18f,
                max(box.width(), box.height()) * 0.72f,
                intArrayOf(hiColor, midColor, deepColor),
                floatArrayOf(0f, 0.58f, 1f),
                Shader.TileMode.CLAMP
            )
            shade == FillShade.LINEAR -> LinearGradient(
                box.left, box.top, box.right, box.bottom,
                intArrayOf(hiColor, midColor, deepColor),
                floatArrayOf(0f, 0.52f, 1f),
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
            is DrawOp.VectorPath -> drawVectorPath(canvas, op, frac, alpha)
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

    private fun drawVectorPath(canvas: Canvas, op: DrawOp.VectorPath, frac: Float, alpha: Int) {
        if (op.cmds.isEmpty()) return
        path.reset()
        for (c in op.cmds) {
            when (c) {
                is PathCmd.MoveTo -> path.moveTo(c.x, c.y)
                is PathCmd.LineTo -> path.lineTo(c.x, c.y)
                is PathCmd.QuadTo -> path.quadTo(c.cx, c.cy, c.x, c.y)
                is PathCmd.CubicTo -> path.cubicTo(c.c1x, c.c1y, c.c2x, c.c2y, c.x, c.y)
                is PathCmd.Close -> path.close()
            }
        }
        if (op.fill && op.paintMode != PaintMode.STROKE_ONLY && (op.paintMode == PaintMode.FILL_ONLY || frac >= 1f)) {
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
        // Distance-clamped Catmull-Rom to cubic Bezier spline prevents loops/overshoots on varied spacing
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

            val segLen = kotlin.math.hypot(x2 - x1, y2 - y1).coerceAtLeast(1e-3f)
            val maxHandle = segLen * 0.42f

            var h1x = (x2 - x0) / 6f
            var h1y = (y2 - y0) / 6f
            val h1Len = kotlin.math.hypot(h1x, h1y)
            if (h1Len > maxHandle) {
                val s = maxHandle / h1Len
                h1x *= s; h1y *= s
            }

            var h2x = (x3 - x1) / 6f
            var h2y = (y3 - y1) / 6f
            val h2Len = kotlin.math.hypot(h2x, h2y)
            if (h2Len > maxHandle) {
                val s = maxHandle / h2Len
                h2x *= s; h2y *= s
            }

            target.cubicTo(x1 + h1x, y1 + h1y, x2 - h2x, y2 - h2y, x2, y2)
        }
        if (closed) target.close()
    }

    /** Clean printed card wash used sparingly for highlighted blocks (final answers, code, key callouts). */
    fun drawBlockBackground(canvas: Canvas, block: BoardBlock, width: Float, margin: Float, color: Int, alpha: Int) {
        val left = margin - 14f
        val top = block.top - 4f
        val right = width - margin + 14f
        val bottom = (block.bottom - 14f).coerceAtLeast(top + 24f)
        rect.set(left, top, right, bottom)
        val clampedAlpha = alpha.coerceIn(0, 255)
        panelPaint.pathEffect = null
        panelPaint.shader = null
        panelPaint.style = Paint.Style.FILL
        panelPaint.color = ColorUtils.setAlphaComponent(color, (clampedAlpha * 0.65f).toInt().coerceIn(3, 42))
        canvas.drawRoundRect(rect, 14f, 14f, panelPaint)
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

    /** Bounds returned after highlighting an active paragraph so viewport auto-scroll follows smoothly. */
    data class HighlightFocus(val topY: Float, val bottomY: Float, val centerY: Float)

    private data class VisualLine(
        val minX: Float,
        val maxX: Float,
        val baselineY: Float,
        val fontSize: Float,
        val text: String
    )

    private data class VisualParagraph(
        val minX: Float,
        val maxX: Float,
        val topY: Float,
        val bottomY: Float,
        val text: String,
        val isDiagramLabel: Boolean = false
    ) {
        val centerY: Float get() = (topY + bottomY) * 0.5f
    }

    private data class CachedParagraphData(
        val paragraphs: List<VisualParagraph>,
        val fracStops: List<Pair<Float, VisualParagraph>>
    )

    private val paragraphCacheMain = WeakHashMap<BoardBlock, CachedParagraphData>()
    private val paragraphCacheNotes = WeakHashMap<BoardBlock, CachedParagraphData>()

    private fun cachedParagraphs(block: BoardBlock, showNotes: Boolean): CachedParagraphData {
        val cache = if (showNotes) paragraphCacheNotes else paragraphCacheMain
        cache[block]?.let { return it }
        val paras = extractVisualParagraphs(block, showNotes)
        val ops = block.allOps(showNotes)
        val stops = ArrayList<Pair<Float, VisualParagraph>>(paras.size)
        if (paras.isNotEmpty() && ops.isNotEmpty()) {
            var total = 0f
            for (op in ops) total += op.weight.coerceAtLeast(1f)
            total = total.coerceAtLeast(1f)
            var acc = 0f
            val bestFracPerPara = FloatArray(paras.size) { -1f }
            for (op in ops) {
                val w = op.weight.coerceAtLeast(1f)
                acc += w
                if (op is DrawOp.Glyphs && op.text.isNotBlank()) {
                    var bestIdx = 0
                    var bestDist = Float.MAX_VALUE
                    for (pIdx in paras.indices) {
                        val p = paras[pIdx]
                        val clamped = op.y.coerceIn(p.topY, p.bottomY)
                        val d = abs(op.y - clamped)
                        if (d < bestDist) {
                            bestDist = d
                            bestIdx = pIdx
                        }
                    }
                    bestFracPerPara[bestIdx] = acc / total
                }
            }
            var prevEnd = 0f
            for (pIdx in paras.indices) {
                val endF = if (pIdx == paras.lastIndex) 1f else max(prevEnd + 0.01f, bestFracPerPara[pIdx].takeIf { it > 0f } ?: ((pIdx + 1f) / paras.size))
                prevEnd = endF.coerceIn(0f, 1f)
                stops.add(prevEnd to paras[pIdx])
            }
        }
        return CachedParagraphData(paras, stops).also { cache[block] = it }
    }

    /** Precision colour-matched stylus tip + pulsing laser aura halo when writing or drawing live. */
    fun drawPen(canvas: Canvas, x: Float, y: Float, color: Int, pulsePhase: Float = 0f) {
        val pulse = 1f + 0.18f * sin(pulsePhase.toDouble()).toFloat()
        panelPaint.pathEffect = null
        panelPaint.style = Paint.Style.FILL
        panelPaint.color = color
        panelPaint.alpha = 44
        canvas.drawCircle(x, y, 20f * pulse, panelPaint)
        panelPaint.alpha = 112
        canvas.drawCircle(x, y, 11.5f * (0.94f + 0.10f * pulse), panelPaint)
        panelPaint.alpha = 246
        canvas.drawCircle(x, y, 5.8f, panelPaint)
        panelPaint.color = StudyPalette.PAPER
        panelPaint.alpha = 255
        canvas.drawCircle(x, y, 2.2f, panelPaint)
    }

    /** Subtle left margin ribbon indicating which printed block is currently being explained. */
    fun drawActiveStepSpotlight(canvas: Canvas, block: BoardBlock, width: Float, margin: Float, color: Int, pulsePhase: Float) {
        if (block.isQuestion) return
        val glow = 0.78f + 0.22f * sin(pulsePhase.toDouble()).toFloat()
        panelPaint.pathEffect = null
        panelPaint.style = Paint.Style.FILL
        panelPaint.color = color
        panelPaint.alpha = (110 * glow).toInt().coerceIn(80, 145)
        rect.set(margin - 14f, block.top, margin - 10f, (block.bottom - 18f).coerceAtLeast(block.top + 12f))
        canvas.drawRoundRect(rect, 2f, 2f, panelPaint)
    }

    /** Pulsing teacher spotlight pin + laser reticle pointing directly to the explained diagram part or paragraph rail. */
    fun drawTeacherPin(canvas: Canvas, x: Float, y: Float, color: Int, pulsePhase: Float) {
        val pulse = 1f + 0.25f * sin(pulsePhase.toDouble()).toFloat()
        panelPaint.pathEffect = null
        panelPaint.style = Paint.Style.FILL
        panelPaint.color = color
        panelPaint.alpha = 46
        canvas.drawCircle(x, y, 16f * pulse, panelPaint)
        panelPaint.alpha = 104
        canvas.drawCircle(x, y, 9.5f * (0.94f + 0.12f * pulse), panelPaint)
        panelPaint.alpha = 245
        canvas.drawCircle(x, y, 4.5f, panelPaint)
        panelPaint.color = StudyPalette.PAPER
        panelPaint.alpha = 255
        canvas.drawCircle(x, y, 2f, panelPaint)
    }

    /** Draws a clean, low-noise printed highlighter wash and subtle margin accent bar around the active line/paragraph. */
    private fun drawWholeParagraphHighlightBox(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        accentColor: Int,
        pulsePhase: Float
    ) {
        val glow = 0.85f + 0.15f * sin(pulsePhase.toDouble()).toFloat()
        val safeBottom = max(bottom, top + 20f)
        val safeRight = max(right, left + 28f)

        panelPaint.pathEffect = null
        panelPaint.style = Paint.Style.FILL
        panelPaint.color = accentColor
        panelPaint.alpha = (18 * glow).toInt().coerceIn(12, 26)
        rect.set(left, top, safeRight, safeBottom)
        canvas.drawRoundRect(rect, 10f, 10f, panelPaint)

        panelPaint.alpha = (190 * glow).toInt().coerceIn(150, 220)
        rect.set(left, top + 3f, left + 4.5f, (safeBottom - 3f).coerceAtLeast(top + 12f))
        canvas.drawRoundRect(rect, 2.5f, 2.5f, panelPaint)
    }

    /**
     * Highlights the whole active paragraph currently being written at [fraction] (for calling BEFORE [drawOps]
     * so written ink remains razor-sharp on top of the luminous highlight).
     */
    fun drawWritingParagraphHighlight(
        canvas: Canvas,
        block: BoardBlock,
        showNotes: Boolean,
        boardWidth: Float,
        margin: Float,
        fraction: Float,
        accentColor: Int,
        pulsePhase: Float
    ): HighlightFocus? {
        val cached = cachedParagraphs(block, showNotes)
        val paragraphs = cached.paragraphs
        if (paragraphs.isEmpty()) {
            val f = fraction.coerceIn(0f, 1f)
            val fallbackTop = (block.top + 4f).coerceAtLeast(0f)
            val fallbackBot = (block.bottom - 14f).coerceAtLeast(fallbackTop + 24f)
            drawWholeParagraphHighlightBox(canvas, margin - 14f, fallbackTop, boardWidth - margin + 14f, fallbackBot, accentColor, pulsePhase)
            return HighlightFocus(fallbackTop, fallbackBot, fallbackTop + (fallbackBot - fallbackTop) * f)
        }

        val f = fraction.coerceIn(0f, 1f)
        val activePara = cached.fracStops.firstOrNull { f <= it.first }?.second ?: paragraphs.last()

        val left = if (activePara.isDiagramLabel) (activePara.minX - 10f) else (margin - 14f)
        val right = if (activePara.isDiagramLabel) (activePara.maxX + 10f) else (boardWidth - margin + 14f)
        drawWholeParagraphHighlightBox(canvas, left, activePara.topY, right, activePara.bottomY, accentColor, pulsePhase)
        return HighlightFocus(activePara.topY, activePara.bottomY, activePara.centerY)
    }

    /**
     * Highlights the whole active paragraph being explained on already-printed boards (saved lessons, replays,
     * or completed strokes while voice finishes), synchronized with [explainFrac] and time-aligned [spokenTranscript].
     */
    fun drawLiveParagraphFocus(
        canvas: Canvas,
        block: BoardBlock,
        showNotes: Boolean,
        boardWidth: Float,
        margin: Float,
        explainFrac: Float,
        spokenTranscript: String,
        accentColor: Int,
        pulsePhase: Float
    ): HighlightFocus? {
        val paragraphs = cachedParagraphs(block, showNotes).paragraphs
        if (paragraphs.isNotEmpty()) {
            val f = explainFrac.coerceIn(0f, 1f)
            val fallbackIdx = (f * paragraphs.size).toInt().coerceIn(0, paragraphs.lastIndex)
            val matchedIdx = matchParagraphFromTranscript(paragraphs, spokenTranscript, fallbackIdx, block.kind == StepKind.DIAGRAM)
            val para = paragraphs[matchedIdx ?: fallbackIdx]
            val left = if (para.isDiagramLabel) (para.minX - 10f) else (margin - 14f)
            val right = if (para.isDiagramLabel) (para.maxX + 10f) else (boardWidth - margin + 14f)
            drawWholeParagraphHighlightBox(canvas, left, para.topY, right, para.bottomY, accentColor, pulsePhase)
            return HighlightFocus(para.topY, para.bottomY, para.centerY)
        }

        val ops = block.allOps(showNotes)
        if (ops.isEmpty()) return null
        val shapePoint = findShapeFocusPoint(ops, explainFrac.coerceIn(0f, 1f)) ?: return null
        val hTop = (shapePoint.second - 28f).coerceAtLeast(block.top)
        val hBot = (shapePoint.second + 28f).coerceAtMost(block.bottom)
        drawWholeParagraphHighlightBox(canvas, margin - 14f, hTop, boardWidth - margin + 14f, hBot, accentColor, pulsePhase)
        return HighlightFocus(hTop, hBot, shapePoint.second)
    }

    /** Draws pulsing pointer marker over an already-printed diagram block while explaining. */
    fun drawLiveDiagramBeacon(
        canvas: Canvas,
        block: BoardBlock,
        showNotes: Boolean,
        explainFrac: Float,
        spokenTranscript: String,
        accentColor: Int,
        pulsePhase: Float
    ) {
        if (block.kind != StepKind.DIAGRAM) return
        val paragraphs = cachedParagraphs(block, showNotes).paragraphs
        if (paragraphs.isNotEmpty()) {
            val f = explainFrac.coerceIn(0f, 1f)
            val fallbackIdx = (f * paragraphs.size).toInt().coerceIn(0, paragraphs.lastIndex)
            val matchedIdx = matchParagraphFromTranscript(paragraphs, spokenTranscript, fallbackIdx, isDiagram = true)
            val para = paragraphs[matchedIdx ?: fallbackIdx]
            drawTeacherPin(canvas, para.maxX + 8f, para.centerY, accentColor, pulsePhase)
            return
        }
        val shapePoint = findShapeFocusPoint(block.allOps(showNotes), explainFrac.coerceIn(0f, 1f)) ?: return
        drawTeacherPin(canvas, shapePoint.first, shapePoint.second, accentColor, pulsePhase)
    }

    private fun extractVisualParagraphs(
        block: BoardBlock,
        showNotes: Boolean
    ): List<VisualParagraph> {
        val out = ArrayList<VisualParagraph>()
        if (block.kind == StepKind.DIAGRAM) {
            for (line in extractVisualLines(block.ops)) {
                out.add(
                    VisualParagraph(
                        line.minX, line.maxX,
                        line.baselineY - line.fontSize * 0.98f,
                        line.baselineY + line.fontSize * 0.36f,
                        line.text,
                        isDiagramLabel = true
                    )
                )
            }
            if (showNotes && block.noteOps.isNotEmpty()) {
                out.addAll(linesToParagraphs(extractVisualLines(block.noteOps)))
            }
            return out
        }

        out.addAll(linesToParagraphs(extractVisualLines(block.ops)))
        if (showNotes && block.noteOps.isNotEmpty()) {
            out.addAll(linesToParagraphs(extractVisualLines(block.noteOps)))
        }
        return out
    }

    private fun linesToParagraphs(lines: List<VisualLine>): List<VisualParagraph> {
        if (lines.isEmpty()) return emptyList()
        val out = ArrayList<VisualParagraph>()
        var minX = lines[0].minX
        var maxX = lines[0].maxX
        var topY = lines[0].baselineY - lines[0].fontSize * 1.02f
        var botY = lines[0].baselineY + lines[0].fontSize * 0.40f
        var prevBaseline = lines[0].baselineY
        var prevSize = lines[0].fontSize
        val sb = StringBuilder(lines[0].text)

        for (idx in 1 until lines.size) {
            val l = lines[idx]
            val gap = l.baselineY - prevBaseline
            val maxSz = max(prevSize, l.fontSize)
            val splitForGap = gap > maxSz * 1.75f
            val splitForBullet = gap > maxSz * 1.15f && BULLET_START_REGEX.containsMatchIn(l.text.trim())
            if (splitForGap || splitForBullet) {
                out.add(VisualParagraph(minX, maxX, topY, botY, sb.toString().trim()))
                minX = l.minX
                maxX = l.maxX
                topY = l.baselineY - l.fontSize * 1.02f
                botY = l.baselineY + l.fontSize * 0.40f
                sb.clear()
                sb.append(l.text)
            } else {
                minX = min(minX, l.minX)
                maxX = max(maxX, l.maxX)
                topY = min(topY, l.baselineY - l.fontSize * 1.02f)
                botY = max(botY, l.baselineY + l.fontSize * 0.40f)
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(l.text)
            }
            prevBaseline = l.baselineY
            prevSize = l.fontSize
        }
        if (sb.isNotEmpty()) {
            out.add(VisualParagraph(minX, maxX, topY, botY, sb.toString().trim()))
        }
        return out
    }

    private fun extractVisualLines(ops: List<DrawOp>): List<VisualLine> {
        val glyphs = ops.filterIsInstance<DrawOp.Glyphs>().filter { it.text.isNotBlank() }
        if (glyphs.isEmpty()) return emptyList()
        val sorted = glyphs.sortedWith(compareBy({ it.y }, { it.x }))
        val lines = ArrayList<VisualLine>()
        var curMinX = Float.MAX_VALUE
        var curMaxX = Float.MIN_VALUE
        var curY = sorted[0].y
        var curSize = sorted[0].size
        val curText = StringBuilder()

        fun measureWidth(g: DrawOp.Glyphs): Float {
            text.typeface = fonts.of(g.face)
            text.textSize = g.size
            return text.measureText(g.text)
        }

        for (g in sorted) {
            val w = measureWidth(g)
            val sameRow = abs(g.y - curY) <= max(curSize, g.size) * 0.45f
            if (!sameRow && curText.isNotEmpty()) {
                lines.add(VisualLine(curMinX, curMaxX, curY, curSize, curText.toString().trim()))
                curText.clear()
                curMinX = Float.MAX_VALUE
                curMaxX = Float.MIN_VALUE
                curY = g.y
                curSize = g.size
            }
            curMinX = min(curMinX, g.x)
            curMaxX = max(curMaxX, g.x + w)
            if (g.size > curSize) curSize = g.size
            if (curText.isNotEmpty()) curText.append(' ')
            curText.append(g.text.trim())
        }
        if (curText.isNotEmpty()) {
            lines.add(VisualLine(curMinX, curMaxX, curY, curSize, curText.toString().trim()))
        }
        // Keep primary readable lines if block has both a caption badge (<= 17f) and larger body lines
        val bodyLines = lines.filter { it.fontSize > 17.5f }
        return if (bodyLines.isNotEmpty()) bodyLines else lines
    }

    private fun matchParagraphFromTranscript(
        paragraphs: List<VisualParagraph>,
        spokenTranscript: String,
        fallbackIdx: Int,
        isDiagram: Boolean
    ): Int? {
        if (spokenTranscript.length < 6 || paragraphs.size <= 1) return null
        val tailStr = spokenTranscript.takeLast(52).lowercase()
        val veryTailStr = spokenTranscript.takeLast(24).lowercase()
        val tailWords = tailStr
            .replace(PUNCT_REGEX, " ")
            .split(WS_REGEX)
            .filter { w -> w.length >= 3 || (w.length >= 2 && w.any { it.isDigit() }) }
        if (tailWords.isEmpty()) return null

        var bestIdx = -1
        var bestScore = 0
        for (i in paragraphs.indices) {
            if (!isDiagram && i < fallbackIdx - 1) continue
            val paraLow = paragraphs[i].text.lowercase()
            var score = 0
            for (w in tailWords) {
                if (paraLow.contains(w)) {
                    val baseW = if (w.length >= 4 || w.any { it.isDigit() }) 2 else 1
                    val recencyBoost = if (veryTailStr.contains(w)) 2 else 0
                    score += baseW + recencyBoost
                }
            }
            if (score > 0 && i == fallbackIdx) score += 1
            if (score > bestScore || (score == bestScore && score > 0 && abs(i - fallbackIdx) < abs(bestIdx - fallbackIdx))) {
                bestScore = score
                bestIdx = i
            }
        }
        return if (bestIdx >= 0 && bestScore >= 2) bestIdx else null
    }

    private fun findShapeFocusPoint(ops: List<DrawOp>, frac: Float): Pair<Float, Float>? {
        val shapes = ops.filter { it !is DrawOp.Glyphs }
        if (shapes.isEmpty()) return null
        val target = shapes[(frac * shapes.size).toInt().coerceIn(0, shapes.lastIndex)]
        return when (target) {
            is DrawOp.Oval -> target.cx to target.cy
            is DrawOp.Arc -> target.cx to target.cy
            is DrawOp.Rect -> (target.x + target.w * 0.5f) to (target.y + target.h * 0.5f)
            is DrawOp.Line -> ((target.x1 + target.x2) * 0.5f) to ((target.y1 + target.y2) * 0.5f)
            is DrawOp.Poly -> {
                val n = target.pts.size / 2
                if (n <= 0) null else target.pts[(n / 2) * 2] to target.pts[(n / 2) * 2 + 1]
            }
            is DrawOp.VectorPath -> {
                val pts = com.jarvis.assistant.study.SvgPathParser.samplePoints(target.cmds)
                if (pts.isEmpty()) null else pts[pts.size / 2]
            }
            is DrawOp.Glyphs -> target.x to target.y
        }
    }

    companion object {
        private val BULLET_START_REGEX = Regex("^(\\d+[.)]|[•\\-*]|Key Point)\\b.*", RegexOption.IGNORE_CASE)
        private val PUNCT_REGEX = Regex("[^\\p{L}\\p{N}\\s]")
        private val WS_REGEX = Regex("\\s+")
    }
}
