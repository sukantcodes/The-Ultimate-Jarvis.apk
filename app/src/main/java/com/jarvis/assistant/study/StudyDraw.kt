package com.jarvis.assistant.study

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot

/** Which pen colour a mark is written in. The Android layer maps these to real colours for the light board and for export. */
enum class Ink { INK, ACCENT, NOTE, RESULT, WARN, FAINT }

enum class Face { MATH_ITALIC, MATH_ROMAN, BODY, BODY_BOLD, CODE }

/** Measures text so layout can run without any Android classes. The Android implementation wraps Paint. */
interface TextMetrics {
    fun width(text: String, size: Float, face: Face): Float
    fun ascent(size: Float, face: Face): Float = size * 0.78f
    fun descent(size: Float, face: Face): Float = size * 0.24f
}

/**
 * One mark on the board. Positions are in board pixels. [weight] is roughly how much pen travel the mark needs, so the
 * animation can spend time in proportion to how much there is to write.
 */
sealed class DrawOp {
    abstract val ink: Ink
    abstract val weight: Float
    abstract fun moved(dx: Float, dy: Float): DrawOp

    class Glyphs(
        val text: String,
        val x: Float,
        /** Baseline. */
        val y: Float,
        val size: Float,
        val face: Face,
        override val ink: Ink
    ) : DrawOp() {
        override val weight: Float get() = text.length * size * 1.1f
        override fun moved(dx: Float, dy: Float) = Glyphs(text, x + dx, y + dy, size, face, ink)
    }

    class Line(
        val x1: Float, val y1: Float, val x2: Float, val y2: Float,
        val stroke: Float,
        override val ink: Ink,
        val dashed: Boolean = false
    ) : DrawOp() {
        override val weight: Float get() = hypot(x2 - x1, y2 - y1)
        override fun moved(dx: Float, dy: Float) = Line(x1 + dx, y1 + dy, x2 + dx, y2 + dy, stroke, ink, dashed)
    }

    /** Connected points as [x0, y0, x1, y1, ...]. */
    class Poly(
        val pts: FloatArray,
        val stroke: Float,
        override val ink: Ink,
        val closed: Boolean = false,
        val fill: Boolean = false,
        val dashed: Boolean = false
    ) : DrawOp() {
        override val weight: Float
            get() {
                var len = 0f
                var i = 2
                while (i + 1 < pts.size) { len += hypot(pts[i] - pts[i - 2], pts[i + 1] - pts[i - 1]); i += 2 }
                if (closed && pts.size >= 4) len += hypot(pts[0] - pts[pts.size - 2], pts[1] - pts[pts.size - 1])
                return len
            }
        override fun moved(dx: Float, dy: Float): DrawOp {
            val p = FloatArray(pts.size)
            for (i in pts.indices step 2) { p[i] = pts[i] + dx; p[i + 1] = pts[i + 1] + dy }
            return Poly(p, stroke, ink, closed, fill, dashed)
        }
    }

    class Oval(
        val cx: Float, val cy: Float, val rx: Float, val ry: Float,
        val stroke: Float,
        override val ink: Ink,
        val fill: Boolean = false,
        val dashed: Boolean = false
    ) : DrawOp() {
        override val weight: Float get() = (PI * (rx + ry)).toFloat()
        override fun moved(dx: Float, dy: Float) = Oval(cx + dx, cy + dy, rx, ry, stroke, ink, fill, dashed)
    }

    class Rect(
        val x: Float, val y: Float, val w: Float, val h: Float,
        val stroke: Float,
        override val ink: Ink,
        val fill: Boolean = false
    ) : DrawOp() {
        override val weight: Float get() = 2f * (abs(w) + abs(h))
        override fun moved(dx: Float, dy: Float) = Rect(x + dx, y + dy, w, h, stroke, ink, fill)
    }
}

/** Bounds of a list of marks, for sizing and export. */
object DrawBounds {
    fun maxX(ops: List<DrawOp>, metrics: TextMetrics): Float {
        var m = 0f
        for (op in ops) {
            val r = when (op) {
                is DrawOp.Glyphs -> op.x + metrics.width(op.text, op.size, op.face)
                is DrawOp.Line -> maxOf(op.x1, op.x2)
                is DrawOp.Poly -> { var mx = 0f; var i = 0; while (i < op.pts.size) { mx = maxOf(mx, op.pts[i]); i += 2 }; mx }
                is DrawOp.Oval -> op.cx + op.rx
                is DrawOp.Rect -> op.x + op.w
            }
            if (r > m) m = r
        }
        return m
    }
}
