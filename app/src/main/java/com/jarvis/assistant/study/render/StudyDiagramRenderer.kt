package com.jarvis.assistant.study.render

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import com.jarvis.assistant.study.model.DiagramElement
import com.jarvis.assistant.study.model.StudyDiagramSpec
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Vector diagram renderer operating in normalized 0..100 coordinates.
 * Drives both live progressive stroke animations on [StudyWhiteboardView] and full-precision
 * vector diagrams inside exported A4 PDF pages.
 */
object StudyDiagramRenderer {

    data class DiagramPalette(
        val cardFillColor: Int,
        val cardBorderColor: Int,
        val gridColor: Int,
        val inkColor: Int,
        val accentColor: Int,
        val secondaryColor: Int,
        val successColor: Int,
        val labelBgColor: Int
    )

    private val scratchPath = Path()
    private val partialPath = Path()
    private val pathMeasure = PathMeasure()

    fun drawDiagram(
        canvas: Canvas,
        spec: StudyDiagramSpec,
        bounds: RectF,
        progress: Float,
        palette: DiagramPalette,
        density: Float
    ): PointF? {
        if (progress <= 0.01f || spec.elements.isEmpty()) return null

        val p = progress.coerceIn(0f, 1f)
        val corner = 16f * density

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.cardFillColor
            style = Paint.Style.FILL
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.cardBorderColor
            style = Paint.Style.STROKE
            strokeWidth = 1.25f * density
        }
        canvas.drawRoundRect(bounds, corner, corner, bgPaint)
        canvas.drawRoundRect(bounds, corner, corner, borderPaint)

        var topOffset = 10f * density
        if (spec.title.isNotBlank()) {
            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.accentColor
                textSize = 12.5f * density
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val titleY = bounds.top + 20f * density
            canvas.drawText(spec.title, bounds.left + 14f * density, titleY, titlePaint)
            topOffset = 30f * density
        }

        val padX = 14f * density
        val padBottom = 12f * density
        val plot = RectF(
            bounds.left + padX,
            bounds.top + topOffset,
            bounds.right - padX,
            bounds.bottom - padBottom
        )
        if (plot.width() <= 20f || plot.height() <= 20f) return null

        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11f * density
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            color = palette.inkColor
        }

        fun mapX(nx: Float): Float = plot.left + (nx.coerceIn(0f, 100f) / 100f) * plot.width()
        fun mapY(ny: Float): Float = plot.top + (ny.coerceIn(0f, 100f) / 100f) * plot.height()

        val n = spec.elements.size
        val stepSpan = 1f / n.toFloat()
        var activeTip: PointF? = null

        spec.elements.forEachIndexed { idx, el ->
            val elStart = idx * stepSpan
            val localP = ((p - elStart) / stepSpan).coerceIn(0f, 1f)
            if (localP <= 0.005f) return@forEachIndexed

            when (el) {
                is DiagramElement.Axis -> {
                    if (el.showGrid) {
                        strokePaint.color = palette.gridColor
                        strokePaint.strokeWidth = 0.8f * density
                        strokePaint.pathEffect = null
                        val cols = 4
                        for (c in 1 until cols) {
                            val gx = plot.left + (plot.width() / cols) * c
                            val gy = plot.top + (plot.height() / cols) * c
                            canvas.drawLine(gx, plot.top, gx, plot.bottom * min(1f, localP * 1.4f), strokePaint)
                            canvas.drawLine(plot.left, gy, plot.right * min(1f, localP * 1.4f), gy, strokePaint)
                        }
                    }
                    // Compute 0-origin placement inside axis domain if 0 is within [min, max]
                    val xZeroFrac = if (el.xMax > el.xMin) ((0f - el.xMin) / (el.xMax - el.xMin)).coerceIn(0.08f, 0.92f) else 0.5f
                    val yZeroFrac = if (el.yMax > el.yMin) (1f - (0f - el.yMin) / (el.yMax - el.yMin)).coerceIn(0.08f, 0.92f) else 0.5f
                    val axY = plot.top + yZeroFrac * plot.height()
                    val axX = plot.left + xZeroFrac * plot.width()

                    strokePaint.color = palette.inkColor
                    strokePaint.strokeWidth = 1.6f * density
                    strokePaint.alpha = 200
                    strokePaint.pathEffect = null
                    val xEnd = plot.left + plot.width() * localP
                    val yStart = plot.bottom - plot.height() * localP
                    canvas.drawLine(plot.left, axY, xEnd, axY, strokePaint)
                    canvas.drawLine(axX, plot.bottom, axX, yStart, strokePaint)
                    strokePaint.alpha = 255
                    if (localP > 0.75f) {
                        labelPaint.color = palette.secondaryColor
                        canvas.drawText(el.xLabel, plot.right - 14f * density, axY - 6f * density, labelPaint)
                        canvas.drawText(el.yLabel, axX + 6f * density, plot.top + 12f * density, labelPaint)
                    }
                    if (localP < 0.98f) activeTip = PointF(xEnd, axY)
                }

                is DiagramElement.Curve -> {
                    if (el.points.size >= 2) {
                        scratchPath.reset()
                        scratchPath.moveTo(mapX(el.points[0].first), mapY(el.points[0].second))
                        for (i in 1 until el.points.size) {
                            scratchPath.lineTo(mapX(el.points[i].first), mapY(el.points[i].second))
                        }
                        if (el.closed) scratchPath.close()

                        strokePaint.color = palette.accentColor
                        strokePaint.strokeWidth = 2.4f * density
                        strokePaint.pathEffect = null
                        activeTip = drawPathProgressive(canvas, scratchPath, localP, strokePaint) ?: activeTip
                        if (localP > 0.8f && el.label.isNotBlank()) {
                            val mid = el.points[el.points.size / 2]
                            drawTag(canvas, el.label, mapX(mid.first), mapY(mid.second) - 8f * density, palette, labelPaint, density)
                        }
                    }
                }

                is DiagramElement.Line -> {
                    scratchPath.reset()
                    scratchPath.moveTo(mapX(el.x1), mapY(el.y1))
                    scratchPath.lineTo(mapX(el.x2), mapY(el.y2))
                    strokePaint.color = palette.inkColor
                    strokePaint.strokeWidth = 2.0f * density
                    strokePaint.pathEffect = if (el.dashed) DashPathEffect(floatArrayOf(8f * density, 6f * density), 0f) else null
                    activeTip = drawPathProgressive(canvas, scratchPath, localP, strokePaint) ?: activeTip
                    strokePaint.pathEffect = null
                    if (localP > 0.78f && el.label.isNotBlank()) {
                        drawTag(canvas, el.label, (mapX(el.x1) + mapX(el.x2)) / 2f, (mapY(el.y1) + mapY(el.y2)) / 2f - 6f * density, palette, labelPaint, density)
                    }
                }

                is DiagramElement.VectorArrow -> {
                    val sx = mapX(el.x1)
                    val sy = mapY(el.y1)
                    val ex = mapX(el.x2)
                    val ey = mapY(el.y2)
                    scratchPath.reset()
                    scratchPath.moveTo(sx, sy)
                    scratchPath.lineTo(ex, ey)
                    strokePaint.color = if (el.isPrimary) palette.accentColor else palette.secondaryColor
                    strokePaint.strokeWidth = (if (el.isPrimary) 2.7f else 2.1f) * density
                    strokePaint.pathEffect = null
                    activeTip = drawPathProgressive(canvas, scratchPath, localP, strokePaint) ?: activeTip
                    if (localP > 0.82f) {
                        val angle = atan2((ey - sy).toDouble(), (ex - sx).toDouble())
                        val headLen = 9f * density
                        val headPath = Path().apply {
                            moveTo(ex, ey)
                            lineTo(
                                (ex - headLen * cos(angle - PI / 6)).toFloat(),
                                (ey - headLen * sin(angle - PI / 6)).toFloat()
                            )
                            moveTo(ex, ey)
                            lineTo(
                                (ex - headLen * cos(angle + PI / 6)).toFloat(),
                                (ey - headLen * sin(angle + PI / 6)).toFloat()
                            )
                        }
                        canvas.drawPath(headPath, strokePaint)
                        if (el.label.isNotBlank()) {
                            drawTag(canvas, el.label, (sx + ex) / 2f + 4f * density, (sy + ey) / 2f - 8f * density, palette, labelPaint, density)
                        }
                    }
                }

                is DiagramElement.CircleArc -> {
                    val cx = mapX(el.cx)
                    val cy = mapY(el.cy)
                    val rPx = (el.radius / 100f) * min(plot.width(), plot.height())
                    val oval = RectF(cx - rPx, cy - rPx, cx + rPx, cy + rPx)
                    if (el.filled && localP > 0.35f) {
                        fillPaint.color = palette.accentColor
                        fillPaint.alpha = (46 * ((localP - 0.35f) / 0.65f)).toInt()
                        canvas.drawArc(oval, el.startAngleDeg, el.sweepAngleDeg * localP, el.sweepAngleDeg < 359f, fillPaint)
                        fillPaint.alpha = 255
                    }
                    strokePaint.color = palette.secondaryColor
                    strokePaint.strokeWidth = 2.2f * density
                    strokePaint.pathEffect = null
                    canvas.drawArc(oval, el.startAngleDeg, el.sweepAngleDeg * localP, false, strokePaint)
                    if (localP < 0.98f) {
                        val rad = Math.toRadians((el.startAngleDeg + el.sweepAngleDeg * localP).toDouble())
                        activeTip = PointF(cx + rPx * cos(rad).toFloat(), cy + rPx * sin(rad).toFloat())
                    } else if (el.label.isNotBlank()) {
                        drawTag(canvas, el.label, cx, cy, palette, labelPaint, density)
                    }
                }

                is DiagramElement.RectBox -> {
                    val left = mapX(el.x)
                    val top = mapY(el.y)
                    val right = mapX(el.x + el.w)
                    val bottom = mapY(el.y + el.h)
                    val box = RectF(left, top, right, bottom)
                    val rad = if (el.rounded) 10f * density else 2f * density
                    scratchPath.reset()
                    scratchPath.addRoundRect(box, rad, rad, Path.Direction.CW)
                    if (localP > 0.3f) {
                        fillPaint.color = palette.accentColor
                        fillPaint.alpha = (28 * localP).toInt()
                        canvas.drawRoundRect(box, rad, rad, fillPaint)
                        fillPaint.alpha = 255
                    }
                    strokePaint.color = palette.accentColor
                    strokePaint.strokeWidth = 2.0f * density
                    strokePaint.pathEffect = null
                    activeTip = drawPathProgressive(canvas, scratchPath, localP, strokePaint) ?: activeTip
                    if (localP > 0.55f && el.label.isNotBlank()) {
                        drawCenteredLabel(canvas, el.label, box.centerX(), box.centerY(), labelPaint, palette.inkColor, maxW = box.width() - 8f * density)
                    }
                }

                is DiagramElement.DiamondNode -> {
                    val cx = mapX(el.cx)
                    val cy = mapY(el.cy)
                    val hw = (el.w / 200f) * plot.width()
                    val hh = (el.h / 200f) * plot.height()
                    scratchPath.reset()
                    scratchPath.moveTo(cx, cy - hh)
                    scratchPath.lineTo(cx + hw, cy)
                    scratchPath.lineTo(cx, cy + hh)
                    scratchPath.lineTo(cx - hw, cy)
                    scratchPath.close()
                    strokePaint.color = palette.secondaryColor
                    strokePaint.strokeWidth = 2.0f * density
                    strokePaint.pathEffect = null
                    activeTip = drawPathProgressive(canvas, scratchPath, localP, strokePaint) ?: activeTip
                    if (localP > 0.6f && el.label.isNotBlank()) {
                        drawCenteredLabel(canvas, el.label, cx, cy, labelPaint, palette.inkColor, maxW = hw * 1.4f)
                    }
                }

                is DiagramElement.ChemBond -> {
                    val x1 = mapX(el.x1)
                    val y1 = mapY(el.y1)
                    val x2 = mapX(el.x2)
                    val y2 = mapY(el.y2)
                    val dx = x2 - x1
                    val dy = y2 - y1
                    val len = max(1f, kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat())
                    val nx = -dy / len * (3.6f * density)
                    val ny = dx / len * (3.6f * density)
                    strokePaint.color = palette.inkColor
                    strokePaint.strokeWidth = 2.1f * density
                    strokePaint.pathEffect = null

                    val offsets = when (el.order) {
                        2 -> listOf(-0.6f, 0.6f)
                        3 -> listOf(-1.05f, 0f, 1.05f)
                        else -> listOf(0f)
                    }
                    offsets.forEach { mul ->
                        scratchPath.reset()
                        scratchPath.moveTo(x1 + nx * mul, y1 + ny * mul)
                        scratchPath.lineTo(x2 + nx * mul, y2 + ny * mul)
                        activeTip = drawPathProgressive(canvas, scratchPath, localP, strokePaint) ?: activeTip
                    }
                    if (localP > 0.7f && el.label.isNotBlank()) {
                        drawTag(canvas, el.label, (x1 + x2) / 2f, (y1 + y2) / 2f - 8f * density, palette, labelPaint, density)
                    }
                }

                is DiagramElement.ChemRing -> {
                    val cx = mapX(el.cx)
                    val cy = mapY(el.cy)
                    val rPx = (el.radius / 100f) * min(plot.width(), plot.height())
                    val sides = el.sides.coerceIn(3, 8)
                    scratchPath.reset()
                    val verts = ArrayList<PointF>(sides)
                    for (i in 0 until sides) {
                        val angle = -PI / 2.0 + (2.0 * PI * i) / sides
                        val vx = cx + rPx * cos(angle).toFloat()
                        val vy = cy + rPx * sin(angle).toFloat()
                        verts += PointF(vx, vy)
                        if (i == 0) scratchPath.moveTo(vx, vy) else scratchPath.lineTo(vx, vy)
                    }
                    scratchPath.close()
                    strokePaint.color = palette.inkColor
                    strokePaint.strokeWidth = 2.1f * density
                    strokePaint.pathEffect = null
                    activeTip = drawPathProgressive(canvas, scratchPath, localP, strokePaint) ?: activeTip
                    if (el.aromatic && localP > 0.65f) {
                        strokePaint.color = palette.accentColor
                        canvas.drawCircle(cx, cy, rPx * 0.56f, strokePaint)
                    }
                    if (localP > 0.75f && el.vertexLabels.isNotEmpty()) {
                        el.vertexLabels.forEachIndexed { vIdx, txt ->
                            if (vIdx < verts.size && txt.isNotBlank()) {
                                val vp = verts[vIdx]
                                val offX = (vp.x - cx) * 0.22f
                                val offY = (vp.y - cy) * 0.22f
                                drawTag(canvas, txt, vp.x + offX, vp.y + offY, palette, labelPaint, density)
                            }
                        }
                    }
                }

                is DiagramElement.TextLabel -> {
                    if (localP > 0.2f) {
                        labelPaint.color = if (el.emphasis) palette.accentColor else palette.inkColor
                        drawTag(canvas, el.text, mapX(el.x), mapY(el.y), palette, labelPaint, density)
                    }
                }
            }
        }

        return activeTip
    }

    private fun drawPathProgressive(
        canvas: Canvas,
        sourcePath: Path,
        progress: Float,
        paint: Paint
    ): PointF? {
        if (progress >= 0.995f) {
            canvas.drawPath(sourcePath, paint)
            return null
        }
        pathMeasure.setPath(sourcePath, false)
        partialPath.reset()
        var total = 0f
        do {
            total += pathMeasure.length
        } while (pathMeasure.nextContour())

        if (total <= 0.5f) {
            canvas.drawPath(sourcePath, paint)
            return null
        }

        var remaining = total * progress
        pathMeasure.setPath(sourcePath, false)
        var lastPos: FloatArray? = null
        do {
            val len = pathMeasure.length
            if (remaining >= len) {
                pathMeasure.getSegment(0f, len, partialPath, true)
                remaining -= len
            } else if (remaining > 0f) {
                pathMeasure.getSegment(0f, remaining, partialPath, true)
                val pos = FloatArray(2)
                pathMeasure.getPosTan(remaining, pos, null)
                lastPos = pos
                break
            }
        } while (pathMeasure.nextContour())

        canvas.drawPath(partialPath, paint)
        return lastPos?.let { PointF(it[0], it[1]) }
    }

    private fun drawTag(
        canvas: Canvas,
        text: String,
        cx: Float,
        cy: Float,
        palette: DiagramPalette,
        paint: Paint,
        density: Float
    ) {
        val clean = StudyMathRenderer.normalizeSymbols(text)
        val tw = paint.measureText(clean)
        val fm = paint.fontMetrics
        val h = fm.descent - fm.ascent
        val bgRect = RectF(
            cx - tw / 2f - 5f * density,
            cy - h / 2f - 2f * density,
            cx + tw / 2f + 5f * density,
            cy + h / 2f + 2f * density
        )
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.labelBgColor
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(bgRect, 5f * density, 5f * density, bgPaint)
        canvas.drawText(clean, cx - tw / 2f, cy - (fm.ascent + fm.descent) / 2f, paint)
    }

    private fun drawCenteredLabel(
        canvas: Canvas,
        text: String,
        cx: Float,
        cy: Float,
        paint: Paint,
        inkColor: Int,
        maxW: Float
    ) {
        val origColor = paint.color
        val origSize = paint.textSize
        paint.color = inkColor
        val clean = StudyMathRenderer.normalizeSymbols(text)
        while (paint.measureText(clean) > maxW && paint.textSize > 16f) {
            paint.textSize -= 1.5f
        }
        val tw = paint.measureText(clean)
        val fm = paint.fontMetrics
        canvas.drawText(clean, cx - tw / 2f, cy - (fm.ascent + fm.descent) / 2f, paint)
        paint.color = origColor
        paint.textSize = origSize
    }
}
