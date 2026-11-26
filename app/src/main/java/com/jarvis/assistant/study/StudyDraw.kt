package com.jarvis.assistant.study

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Which pen or marker colour a mark is written in. The Android layer maps these dynamically to high-contrast
 * studio-whiteboard colours so the board stays vivid and legible across both live rendering and PDF/PNG export.
 */
enum class Ink {
    INK, ACCENT, NOTE, RESULT, WARN, FAINT,
    GOLD, VIOLET, TEAL, ROSE, ORANGE, BROWN
}

enum class Face { MATH_ITALIC, MATH_ROMAN, BODY, BODY_BOLD, CODE }

/** Shading style for filled vector diagram regions. */
enum class FillShade { FLAT, LINEAR, RADIAL }

/** Controls whether a shape op draws only its outline, sweeps its interior colour wash, or draws both together. */
enum class PaintMode { FILL_AND_STROKE, STROKE_ONLY, FILL_ONLY }

/** Measures text so layout can run without any Android classes. The Android implementation wraps Paint. */
interface TextMetrics {
    fun width(text: String, size: Float, face: Face): Float
    fun ascent(size: Float, face: Face): Float = size * 0.78f
    fun descent(size: Float, face: Face): Float = size * 0.24f
}

/**
 * One mark on the board. Positions are in board pixels. [weight] is roughly how much pen travel the mark needs, so the
 * animation can spend time in proportion to how much there is to write or fill.
 */
sealed class DrawOp {
    abstract val ink: Ink
    abstract val weight: Float
    abstract fun moved(dx: Float, dy: Float): DrawOp
    abstract fun scaled(anchorX: Float, anchorY: Float, factor: Float): DrawOp

    class Glyphs(
        val text: String,
        val x: Float,
        /** Baseline. */
        val y: Float,
        val size: Float,
        val face: Face,
        override val ink: Ink
    ) : DrawOp() {
        override val weight: Float get() = text.length * size * 1.05f
        override fun moved(dx: Float, dy: Float) = Glyphs(text, x + dx, y + dy, size, face, ink)
        override fun scaled(anchorX: Float, anchorY: Float, factor: Float) = Glyphs(
            text,
            anchorX + (x - anchorX) * factor,
            anchorY + (y - anchorY) * factor,
            size * factor,
            face,
            ink
        )
    }

    class Line(
        val x1: Float, val y1: Float, val x2: Float, val y2: Float,
        val stroke: Float,
        override val ink: Ink,
        val dashed: Boolean = false
    ) : DrawOp() {
        override val weight: Float get() = hypot(x2 - x1, y2 - y1)
        override fun moved(dx: Float, dy: Float) = Line(x1 + dx, y1 + dy, x2 + dx, y2 + dy, stroke, ink, dashed)
        override fun scaled(anchorX: Float, anchorY: Float, factor: Float) = Line(
            anchorX + (x1 - anchorX) * factor,
            anchorY + (y1 - anchorY) * factor,
            anchorX + (x2 - anchorX) * factor,
            anchorY + (y2 - anchorY) * factor,
            max(1f, stroke * factor),
            ink,
            dashed
        )
    }

    /** Connected polyline or smooth Catmull-Rom spline curve as `[x0, y0, x1, y1, ...]`. */
    class Poly(
        val pts: FloatArray,
        val stroke: Float,
        override val ink: Ink,
        val closed: Boolean = false,
        val fill: Boolean = false,
        val dashed: Boolean = false,
        val smooth: Boolean = false,
        val fillInk: Ink = ink,
        val shade: FillShade = FillShade.FLAT,
        val paintMode: PaintMode = PaintMode.FILL_AND_STROKE,
        val fillAlpha: Float = 0.28f
    ) : DrawOp() {
        override val weight: Float
            get() {
                var len = 0f
                var i = 2
                while (i + 1 < pts.size) { len += hypot(pts[i] - pts[i - 2], pts[i + 1] - pts[i - 1]); i += 2 }
                if (closed && pts.size >= 4) len += hypot(pts[0] - pts[pts.size - 2], pts[1] - pts[pts.size - 1])
                if (paintMode == PaintMode.FILL_ONLY) return max(len * 0.45f, 36f)
                return len
            }

        override fun moved(dx: Float, dy: Float): DrawOp {
            val p = FloatArray(pts.size)
            for (i in pts.indices step 2) { p[i] = pts[i] + dx; p[i + 1] = pts[i + 1] + dy }
            return Poly(p, stroke, ink, closed, fill, dashed, smooth, fillInk, shade, paintMode, fillAlpha)
        }

        override fun scaled(anchorX: Float, anchorY: Float, factor: Float): DrawOp {
            val p = FloatArray(pts.size)
            for (i in pts.indices step 2) {
                p[i] = anchorX + (pts[i] - anchorX) * factor
                p[i + 1] = anchorY + (pts[i + 1] - anchorY) * factor
            }
            return Poly(p, max(1f, stroke * factor), ink, closed, fill, dashed, smooth, fillInk, shade, paintMode, fillAlpha)
        }
    }

    class Oval(
        val cx: Float, val cy: Float, val rx: Float, val ry: Float,
        val stroke: Float,
        override val ink: Ink,
        val fill: Boolean = false,
        val dashed: Boolean = false,
        val rotation: Float = 0f,
        val fillInk: Ink = ink,
        val shade: FillShade = FillShade.RADIAL,
        val paintMode: PaintMode = PaintMode.FILL_AND_STROKE,
        val fillAlpha: Float = 0.28f
    ) : DrawOp() {
        override val weight: Float
            get() {
                val circumference = (PI * (rx + ry)).toFloat()
                return if (paintMode == PaintMode.FILL_ONLY) max(circumference * 0.45f, 36f) else circumference
            }

        override fun moved(dx: Float, dy: Float) =
            Oval(cx + dx, cy + dy, rx, ry, stroke, ink, fill, dashed, rotation, fillInk, shade, paintMode, fillAlpha)

        override fun scaled(anchorX: Float, anchorY: Float, factor: Float) = Oval(
            anchorX + (cx - anchorX) * factor,
            anchorY + (cy - anchorY) * factor,
            rx * factor,
            ry * factor,
            max(1f, stroke * factor),
            ink,
            fill,
            dashed,
            rotation,
            fillInk,
            shade,
            paintMode,
            fillAlpha
        )
    }

    class Arc(
        val cx: Float, val cy: Float, val rx: Float, val ry: Float,
        val startAngle: Float, val sweepAngle: Float,
        val stroke: Float,
        override val ink: Ink,
        val fill: Boolean = false,
        val useCenter: Boolean = false,
        val dashed: Boolean = false,
        val fillInk: Ink = ink,
        val shade: FillShade = FillShade.RADIAL,
        val paintMode: PaintMode = PaintMode.FILL_AND_STROKE,
        val fillAlpha: Float = 0.26f
    ) : DrawOp() {
        override val weight: Float
            get() {
                val frac = (abs(sweepAngle).coerceIn(1f, 360f)) / 360f
                val arcLen = (PI * (rx + ry) * frac).toFloat()
                return if (paintMode == PaintMode.FILL_ONLY) max(arcLen * 0.45f, 28f) else max(arcLen, 16f)
            }

        override fun moved(dx: Float, dy: Float) =
            Arc(cx + dx, cy + dy, rx, ry, startAngle, sweepAngle, stroke, ink, fill, useCenter, dashed, fillInk, shade, paintMode, fillAlpha)

        override fun scaled(anchorX: Float, anchorY: Float, factor: Float) = Arc(
            anchorX + (cx - anchorX) * factor,
            anchorY + (cy - anchorY) * factor,
            rx * factor,
            ry * factor,
            startAngle,
            sweepAngle,
            max(1f, stroke * factor),
            ink,
            fill,
            useCenter,
            dashed,
            fillInk,
            shade,
            paintMode,
            fillAlpha
        )
    }

    class Rect(
        val x: Float, val y: Float, val w: Float, val h: Float,
        val stroke: Float,
        override val ink: Ink,
        val fill: Boolean = false,
        val rx: Float = 0f,
        val ry: Float = 0f,
        val dashed: Boolean = false,
        val fillInk: Ink = ink,
        val shade: FillShade = FillShade.LINEAR,
        val paintMode: PaintMode = PaintMode.FILL_AND_STROKE,
        val fillAlpha: Float = 0.24f
    ) : DrawOp() {
        override val weight: Float
            get() {
                val perim = 2f * (abs(w) + abs(h))
                return if (paintMode == PaintMode.FILL_ONLY) max(perim * 0.4f, 34f) else perim
            }

        override fun moved(dx: Float, dy: Float) =
            Rect(x + dx, y + dy, w, h, stroke, ink, fill, rx, ry, dashed, fillInk, shade, paintMode, fillAlpha)

        override fun scaled(anchorX: Float, anchorY: Float, factor: Float) = Rect(
            anchorX + (x - anchorX) * factor,
            anchorY + (y - anchorY) * factor,
            w * factor,
            h * factor,
            max(1f, stroke * factor),
            ink,
            fill,
            rx * factor,
            ry * factor,
            dashed,
            fillInk,
            shade,
            paintMode,
            fillAlpha
        )
    }
}

/** Bounds of a list of marks, for sizing, zero-overflow containment, and export. */
object DrawBounds {
    data class Box(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float) {
        val width: Float get() = max(0f, maxX - minX)
        val height: Float get() = max(0f, maxY - minY)
    }

    fun measure(ops: List<DrawOp>, metrics: TextMetrics): Box? {
        if (ops.isEmpty()) return null
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        fun include(x1: Float, y1: Float, x2: Float, y2: Float) {
            if (x1 < minX) minX = x1
            if (y1 < minY) minY = y1
            if (x2 > maxX) maxX = x2
            if (y2 > maxY) maxY = y2
        }
        for (op in ops) {
            when (op) {
                is DrawOp.Glyphs -> {
                    val w = metrics.width(op.text, op.size, op.face)
                    include(op.x, op.y - metrics.ascent(op.size, op.face), op.x + w, op.y + metrics.descent(op.size, op.face))
                }
                is DrawOp.Line -> {
                    val pad = op.stroke * 0.6f
                    include(min(op.x1, op.x2) - pad, min(op.y1, op.y2) - pad, max(op.x1, op.x2) + pad, max(op.y1, op.y2) + pad)
                }
                is DrawOp.Poly -> {
                    val pad = op.stroke * 0.6f
                    var i = 0
                    while (i + 1 < op.pts.size) {
                        val px = op.pts[i]; val py = op.pts[i + 1]
                        include(px - pad, py - pad, px + pad, py + pad)
                        i += 2
                    }
                }
                is DrawOp.Oval -> {
                    val rMax = if (abs(op.rotation) > 0.1f) hypot(op.rx, op.ry) else max(op.rx, op.ry)
                    val rxB = if (abs(op.rotation) > 0.1f) rMax else op.rx
                    val ryB = if (abs(op.rotation) > 0.1f) rMax else op.ry
                    val pad = op.stroke * 0.6f
                    include(op.cx - rxB - pad, op.cy - ryB - pad, op.cx + rxB + pad, op.cy + ryB + pad)
                }
                is DrawOp.Arc -> {
                    val pad = op.stroke * 0.6f
                    include(op.cx - op.rx - pad, op.cy - op.ry - pad, op.cx + op.rx + pad, op.cy + op.ry + pad)
                }
                is DrawOp.Rect -> {
                    val pad = op.stroke * 0.6f
                    val x1 = min(op.x, op.x + op.w) - pad
                    val x2 = max(op.x, op.x + op.w) + pad
                    val y1 = min(op.y, op.y + op.h) - pad
                    val y2 = max(op.y, op.y + op.h) + pad
                    include(x1, y1, x2, y2)
                }
            }
        }
        return if (minX.isFinite() && minY.isFinite() && maxX.isFinite() && maxY.isFinite()) {
            Box(minX, minY, maxX, maxY)
        } else null
    }

    fun maxX(ops: List<DrawOp>, metrics: TextMetrics): Float = measure(ops, metrics)?.maxX ?: 0f
}
