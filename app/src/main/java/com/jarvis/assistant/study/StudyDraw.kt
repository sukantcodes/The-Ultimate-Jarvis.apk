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
    GOLD, VIOLET, TEAL, ROSE, ORANGE, BROWN,
    PAPER
}

enum class Face { MATH_ITALIC, MATH_ROMAN, BODY, BODY_BOLD, CODE }

/** Shading style for filled vector diagram regions. */
enum class FillShade { FLAT, LINEAR, RADIAL }

/** Controls whether a shape op draws only its outline, sweeps its interior colour wash, or draws both together. */
enum class PaintMode { FILL_AND_STROKE, STROKE_ONLY, FILL_ONLY }

/** Pure-Kotlin vector path segment commands (zero Android dependencies for JVM layout & unit tests). */
sealed class PathCmd {
    data class MoveTo(val x: Float, val y: Float) : PathCmd()
    data class LineTo(val x: Float, val y: Float) : PathCmd()
    data class QuadTo(val cx: Float, val cy: Float, val x: Float, val y: Float) : PathCmd()
    data class CubicTo(val c1x: Float, val c1y: Float, val c2x: Float, val c2y: Float, val x: Float, val y: Float) : PathCmd()
    data object Close : PathCmd()
}

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

    /** Arbitrary multi-segment vector path using SVG-grade lines, quadratic curves, and cubic Beziers. */
    class VectorPath(
        val cmds: List<PathCmd>,
        val stroke: Float,
        override val ink: Ink,
        val fill: Boolean = false,
        val dashed: Boolean = false,
        val fillInk: Ink = ink,
        val shade: FillShade = FillShade.RADIAL,
        val paintMode: PaintMode = PaintMode.FILL_AND_STROKE,
        val fillAlpha: Float = 0.28f
    ) : DrawOp() {
        override val weight: Float
            get() {
                var len = 0f
                var cx = 0f
                var cy = 0f
                var sx = 0f
                var sy = 0f
                for (c in cmds) {
                    when (c) {
                        is PathCmd.MoveTo -> { cx = c.x; cy = c.y; sx = cx; sy = cy }
                        is PathCmd.LineTo -> { len += hypot(c.x - cx, c.y - cy); cx = c.x; cy = c.y }
                        is PathCmd.QuadTo -> {
                            len += (hypot(c.cx - cx, c.cy - cy) + hypot(c.x - c.cx, c.y - c.cy)) * 0.85f
                            cx = c.x; cy = c.y
                        }
                        is PathCmd.CubicTo -> {
                            val chord = hypot(c.x - cx, c.y - cy)
                            val ctrl = hypot(c.c1x - cx, c.c1y - cy) + hypot(c.c2x - c.c1x, c.c2y - c.c1y) + hypot(c.x - c.c2x, c.y - c.c2y)
                            len += (chord + ctrl) * 0.5f
                            cx = c.x; cy = c.y
                        }
                        is PathCmd.Close -> { len += hypot(sx - cx, sy - cy); cx = sx; cy = sy }
                    }
                }
                val safeLen = max(len, 12f)
                return if (paintMode == PaintMode.FILL_ONLY) max(safeLen * 0.45f, 36f) else safeLen
            }

        override fun moved(dx: Float, dy: Float) = VectorPath(
            cmds.map { c ->
                when (c) {
                    is PathCmd.MoveTo -> PathCmd.MoveTo(c.x + dx, c.y + dy)
                    is PathCmd.LineTo -> PathCmd.LineTo(c.x + dx, c.y + dy)
                    is PathCmd.QuadTo -> PathCmd.QuadTo(c.cx + dx, c.cy + dy, c.x + dx, c.y + dy)
                    is PathCmd.CubicTo -> PathCmd.CubicTo(c.c1x + dx, c.c1y + dy, c.c2x + dx, c.c2y + dy, c.x + dx, c.y + dy)
                    is PathCmd.Close -> PathCmd.Close
                }
            },
            stroke, ink, fill, dashed, fillInk, shade, paintMode, fillAlpha
        )

        override fun scaled(anchorX: Float, anchorY: Float, factor: Float): DrawOp {
            fun sx(v: Float) = anchorX + (v - anchorX) * factor
            fun sy(v: Float) = anchorY + (v - anchorY) * factor
            return VectorPath(
                cmds.map { c ->
                    when (c) {
                        is PathCmd.MoveTo -> PathCmd.MoveTo(sx(c.x), sy(c.y))
                        is PathCmd.LineTo -> PathCmd.LineTo(sx(c.x), sy(c.y))
                        is PathCmd.QuadTo -> PathCmd.QuadTo(sx(c.cx), sy(c.cy), sx(c.x), sy(c.y))
                        is PathCmd.CubicTo -> PathCmd.CubicTo(sx(c.c1x), sy(c.c1y), sx(c.c2x), sy(c.c2y), sx(c.x), sy(c.y))
                        is PathCmd.Close -> PathCmd.Close
                    }
                },
                max(1f, stroke * factor), ink, fill, dashed, fillInk, shade, paintMode, fillAlpha
            )
        }
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
        fun includePt(x: Float, y: Float, pad: Float) = include(x - pad, y - pad, x + pad, y + pad)
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
                is DrawOp.VectorPath -> {
                    val pad = op.stroke * 0.6f
                    var cx = 0f
                    var cy = 0f
                    var sx = 0f
                    var sy = 0f
                    for (c in op.cmds) {
                        when (c) {
                            is PathCmd.MoveTo -> { cx = c.x; cy = c.y; sx = cx; sy = cy; includePt(cx, cy, pad) }
                            is PathCmd.LineTo -> { cx = c.x; cy = c.y; includePt(cx, cy, pad) }
                            is PathCmd.QuadTo -> {
                                for (step in 1..4) {
                                    val t = step * 0.25f
                                    val mt = 1f - t
                                    val px = mt * mt * cx + 2f * mt * t * c.cx + t * t * c.x
                                    val py = mt * mt * cy + 2f * mt * t * c.cy + t * t * c.y
                                    includePt(px, py, pad)
                                }
                                cx = c.x; cy = c.y
                            }
                            is PathCmd.CubicTo -> {
                                for (step in 1..5) {
                                    val t = step * 0.2f
                                    val mt = 1f - t
                                    val mt2 = mt * mt
                                    val t2 = t * t
                                    val px = mt2 * mt * cx + 3f * mt2 * t * c.c1x + 3f * mt * t2 * c.c2x + t2 * t * c.x
                                    val py = mt2 * mt * cy + 3f * mt2 * t * c.c1y + 3f * mt * t2 * c.c2y + t2 * t * c.y
                                    includePt(px, py, pad)
                                }
                                cx = c.x; cy = c.y
                            }
                            is PathCmd.Close -> { cx = sx; cy = sy }
                        }
                    }
                }
            }
        }
        return if (minX.isFinite() && minY.isFinite() && maxX.isFinite() && maxY.isFinite()) {
            Box(minX, minY, maxX, maxY)
        } else null
    }

    fun maxX(ops: List<DrawOp>, metrics: TextMetrics): Float = measure(ops, metrics)?.maxX ?: 0f
}
