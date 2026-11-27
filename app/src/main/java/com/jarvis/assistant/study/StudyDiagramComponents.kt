package com.jarvis.assistant.study

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Parametric precision STEM & engineering component expanders for [DiagramSpec].
 * Turns high-level component descriptors (resistors, inductors, coils, batteries, diodes, gears, pulleys,
 * springs, lab glassware, dimension brackets, angle arcs, flowchart diamonds, cylinders, curved arrows)
 * into mathematically aligned vector geometry + crisp embedded labels instead of crude hand-drawn shapes.
 */
object StudyDiagramComponents {

    private fun alongUnit(x1: Float, y1: Float, x2: Float, y2: Float): Pair<Pair<Float, Float>, Pair<Float, Float>> {
        val dx = x2 - x1
        val dy = y2 - y1
        val len = hypot(dx, dy).coerceAtLeast(1e-3f)
        val ux = dx / len
        val uy = dy / len
        return (ux to uy) to (-uy to ux)
    }

    /** Exact IEEE/IEC 6-peak zigzag resistor between `(x1, y1)` and `(x2, y2)`. */
    fun resistor(
        x1: Float, y1: Float, x2: Float, y2: Float,
        ink: Ink = Ink.ORANGE,
        strokeScale: Float = 1.15f,
        label: String = "",
        layer: Int = 2
    ): List<DiagramSpec.Item> {
        val out = ArrayList<DiagramSpec.Item>()
        val len = hypot(x2 - x1, y2 - y1).coerceAtLeast(4f)
        val (u, n) = alongUnit(x1, y1, x2, y2)
        val bodyFrac = 0.56f
        val leadLen = len * (1f - bodyFrac) * 0.5f
        val bodyLen = len * bodyFrac
        val amp = (len * 0.14f).coerceIn(2.0f, 6.5f)
        val peaks = 6

        val bx0 = x1 + u.first * leadLen
        val by0 = y1 + u.second * leadLen
        val bx1 = x2 - u.first * leadLen
        val by1 = y2 - u.second * leadLen

        val pts = ArrayList<Float>((peaks + 4) * 2)
        pts.add(x1); pts.add(y1)
        pts.add(bx0); pts.add(by0)
        val step = bodyLen / peaks
        for (i in 0 until peaks) {
            val t = (i + 0.5f) * step
            val side = if (i % 2 == 0) 1f else -1f
            pts.add(bx0 + u.first * t + n.first * amp * side)
            pts.add(by0 + u.second * t + n.second * amp * side)
        }
        pts.add(bx1); pts.add(by1)
        pts.add(x2); pts.add(y2)
        out.add(DiagramSpec.Item.Poly(pts, closed = false, ink = ink, smooth = false, strokeScale = strokeScale, layer = layer))

        if (label.isNotBlank()) {
            val mx = (x1 + x2) * 0.5f + n.first * (amp + 5.2f)
            val my = (y1 + y2) * 0.5f + n.second * (amp + 5.2f)
            out.add(DiagramSpec.Item.Label(mx, my, label, ink, badge = false, place = LabelPlace.INLINE, sizeScale = 0.88f))
        }
        return out
    }

    /** Exact parallel-plate capacitor between `(x1, y1)` and `(x2, y2)`. */
    fun capacitor(
        x1: Float, y1: Float, x2: Float, y2: Float,
        ink: Ink = Ink.TEAL,
        strokeScale: Float = 1.25f,
        label: String = "",
        layer: Int = 2
    ): List<DiagramSpec.Item> {
        val out = ArrayList<DiagramSpec.Item>()
        val len = hypot(x2 - x1, y2 - y1).coerceAtLeast(4f)
        val (u, n) = alongUnit(x1, y1, x2, y2)
        val gap = (len * 0.10f).coerceIn(1.8f, 4.8f)
        val plateHalf = (len * 0.24f).coerceIn(3.5f, 10f)
        val mx = (x1 + x2) * 0.5f
        val my = (y1 + y2) * 0.5f
        val p1x = mx - u.first * (gap * 0.5f)
        val p1y = my - u.second * (gap * 0.5f)
        val p2x = mx + u.first * (gap * 0.5f)
        val p2y = my + u.second * (gap * 0.5f)

        out.add(DiagramSpec.Item.Line(x1, y1, p1x, p1y, false, false, ink, strokeScale = strokeScale, layer = layer))
        out.add(DiagramSpec.Item.Line(p2x, p2y, x2, y2, false, false, ink, strokeScale = strokeScale, layer = layer))
        out.add(DiagramSpec.Item.Line(p1x - n.first * plateHalf, p1y - n.second * plateHalf, p1x + n.first * plateHalf, p1y + n.second * plateHalf, false, false, ink, strokeScale = strokeScale * 1.35f, layer = layer))
        out.add(DiagramSpec.Item.Line(p2x - n.first * plateHalf, p2y - n.second * plateHalf, p2x + n.first * plateHalf, p2y + n.second * plateHalf, false, false, ink, strokeScale = strokeScale * 1.35f, layer = layer))
        if (label.isNotBlank()) {
            out.add(DiagramSpec.Item.Label(mx + n.first * (plateHalf + 5.2f), my + n.second * (plateHalf + 5.2f), label, ink, badge = false, place = LabelPlace.INLINE, sizeScale = 0.88f))
        }
        return out
    }

    /** Smooth looped inductor / solenoid coil between `(x1, y1)` and `(x2, y2)`. */
    fun inductor(
        x1: Float, y1: Float, x2: Float, y2: Float,
        turns: Int = 5,
        ink: Ink = Ink.VIOLET,
        strokeScale: Float = 1.2f,
        label: String = "",
        layer: Int = 2
    ): List<DiagramSpec.Item> {
        val out = ArrayList<DiagramSpec.Item>()
        val len = hypot(x2 - x1, y2 - y1).coerceAtLeast(6f)
        val (u, n) = alongUnit(x1, y1, x2, y2)
        val safeTurns = turns.coerceIn(3, 10)
        val leadLen = len * 0.16f
        val bodyLen = len - leadLen * 2f
        val rLoop = (bodyLen / safeTurns) * 0.5f
        val loopH = rLoop * 1.45f

        val bx0 = x1 + u.first * leadLen
        val by0 = y1 + u.second * leadLen
        val bx1 = x2 - u.first * leadLen
        val by1 = y2 - u.second * leadLen

        out.add(DiagramSpec.Item.Line(x1, y1, bx0, by0, false, false, ink, strokeScale = strokeScale, layer = layer))
        out.add(DiagramSpec.Item.Line(bx1, by1, x2, y2, false, false, ink, strokeScale = strokeScale, layer = layer))

        val cmds = ArrayList<PathCmd>(safeTurns + 2)
        cmds.add(PathCmd.MoveTo(bx0, by0))
        var curX = bx0
        var curY = by0
        for (i in 0 until safeTurns) {
            val nx = bx0 + u.first * ((i + 1) * (bodyLen / safeTurns))
            val ny = by0 + u.second * ((i + 1) * (bodyLen / safeTurns))
            val c1x = curX + n.first * loopH
            val c1y = curY + n.second * loopH
            val c2x = nx + n.first * loopH
            val c2y = ny + n.second * loopH
            cmds.add(PathCmd.CubicTo(c1x, c1y, c2x, c2y, nx, ny))
            curX = nx; curY = ny
        }
        out.add(DiagramSpec.Item.VectorPath(cmds, fill = false, ink = ink, strokeScale = strokeScale, layer = layer))
        if (label.isNotBlank()) {
            val mx = (x1 + x2) * 0.5f + n.first * (loopH + 5f)
            val my = (y1 + y2) * 0.5f + n.second * (loopH + 5f)
            out.add(DiagramSpec.Item.Label(mx, my, label, ink, badge = false, place = LabelPlace.INLINE, sizeScale = 0.88f))
        }
        return out
    }

    /** Multi-cell DC battery symbol with exact positive (long thin) and negative (short thick) plates. */
    fun battery(
        x1: Float, y1: Float, x2: Float, y2: Float,
        cells: Int = 2,
        ink: Ink = Ink.WARN,
        strokeScale: Float = 1.2f,
        label: String = "",
        layer: Int = 2
    ): List<DiagramSpec.Item> {
        val out = ArrayList<DiagramSpec.Item>()
        val len = hypot(x2 - x1, y2 - y1).coerceAtLeast(6f)
        val (u, n) = alongUnit(x1, y1, x2, y2)
        val count = cells.coerceIn(1, 4)
        val span = min(len * 0.52f, count * 6.5f)
        val startX = (x1 + x2) * 0.5f - u.first * (span * 0.5f)
        val startY = (y1 + y2) * 0.5f - u.second * (span * 0.5f)
        val endX = (x1 + x2) * 0.5f + u.first * (span * 0.5f)
        val endY = (y1 + y2) * 0.5f + u.second * (span * 0.5f)

        out.add(DiagramSpec.Item.Line(x1, y1, startX, startY, false, false, ink, strokeScale = strokeScale, layer = layer))
        out.add(DiagramSpec.Item.Line(endX, endY, x2, y2, false, false, ink, strokeScale = strokeScale, layer = layer))

        val longH = (len * 0.25f).coerceIn(4.2f, 10.5f)
        val shortH = longH * 0.55f
        val step = if (count == 1) span else span / (count * 2 - 1).toFloat()
        for (c in 0 until count) {
            val posOff = c * step * 2f
            val negOff = posOff + step
            val px = startX + u.first * posOff
            val py = startY + u.second * posOff
            val nx = startX + u.first * negOff
            val ny = startY + u.second * negOff
            out.add(DiagramSpec.Item.Line(px - n.first * longH, py - n.second * longH, px + n.first * longH, py + n.second * longH, false, false, Ink.WARN, strokeScale = strokeScale * 1.15f, layer = layer))
            out.add(DiagramSpec.Item.Line(nx - n.first * shortH, ny - n.second * shortH, nx + n.first * shortH, ny + n.second * shortH, false, false, Ink.ACCENT, strokeScale = strokeScale * 1.85f, layer = layer))
        }
        // '+' and '-' polarity indicators
        out.add(DiagramSpec.Item.Label(startX - u.first * 2.5f + n.first * (longH + 3.2f), startY - u.second * 2.5f + n.second * (longH + 3.2f), "+", Ink.WARN, badge = false, place = LabelPlace.INLINE, sizeScale = 0.85f))
        out.add(DiagramSpec.Item.Label(endX + u.first * 2.5f + n.first * (longH + 3.2f), endY + u.second * 2.5f + n.second * (longH + 3.2f), "−", Ink.ACCENT, badge = false, place = LabelPlace.INLINE, sizeScale = 0.85f))
        if (label.isNotBlank()) {
            val mx = (x1 + x2) * 0.5f - n.first * (longH + 5.5f)
            val my = (y1 + y2) * 0.5f - n.second * (longH + 5.5f)
            out.add(DiagramSpec.Item.Label(mx, my, label, ink, badge = false, place = LabelPlace.INLINE, sizeScale = 0.88f))
        }
        return out
    }

    /** Hinged circuit switch symbol between `(x1, y1)` and `(x2, y2)`. */
    fun switch(
        x1: Float, y1: Float, x2: Float, y2: Float,
        open: Boolean = true,
        ink: Ink = Ink.RESULT,
        strokeScale: Float = 1.2f,
        label: String = "",
        layer: Int = 2
    ): List<DiagramSpec.Item> {
        val out = ArrayList<DiagramSpec.Item>()
        val len = hypot(x2 - x1, y2 - y1).coerceAtLeast(4f)
        val (u, n) = alongUnit(x1, y1, x2, y2)
        val ax = (x1 + x2) * 0.5f - u.first * (len * 0.22f)
        val ay = (y1 + y2) * 0.5f - u.second * (len * 0.22f)
        val bx = (x1 + x2) * 0.5f + u.first * (len * 0.22f)
        val by = (y1 + y2) * 0.5f + u.second * (len * 0.22f)
        out.add(DiagramSpec.Item.Line(x1, y1, ax, ay, false, false, ink, strokeScale = strokeScale, layer = layer))
        out.add(DiagramSpec.Item.Line(bx, by, x2, y2, false, false, ink, strokeScale = strokeScale, layer = layer))
        out.add(DiagramSpec.Item.Circle(ax, ay, 1.4f, fill = true, ink = ink, fillInk = ink, fillAlpha = 0.85f, layer = layer))
        out.add(DiagramSpec.Item.Circle(bx, by, 1.4f, fill = true, ink = ink, fillInk = ink, fillAlpha = 0.85f, layer = layer))
        val tipX = if (open) bx - n.first * (len * 0.24f) else bx
        val tipY = if (open) by - n.second * (len * 0.24f) else by
        out.add(DiagramSpec.Item.Line(ax, ay, tipX, tipY, false, false, ink, strokeScale = strokeScale * 1.15f, layer = layer))
        if (label.isNotBlank()) {
            val mx = (x1 + x2) * 0.5f + n.first * (len * 0.22f + 4f)
            val my = (y1 + y2) * 0.5f + n.second * (len * 0.22f + 4f)
            out.add(DiagramSpec.Item.Label(mx, my, label, ink, badge = false, place = LabelPlace.INLINE, sizeScale = 0.88f))
        }
        return out
    }

    /** Incandescent lamp / bulb symbol with internal coiled filament. */
    fun bulb(
        cx: Float, cy: Float, r: Float,
        ink: Ink = Ink.GOLD,
        strokeScale: Float = 1.2f,
        label: String = "",
        layer: Int = 0
    ): List<DiagramSpec.Item> {
        val out = ArrayList<DiagramSpec.Item>()
        out.add(DiagramSpec.Item.Circle(cx, cy, r, fill = true, ink = ink, fillInk = ink, fillAlpha = 0.22f, strokeScale = strokeScale, layer = layer))
        // Filament loop inside bulb
        out.add(
            DiagramSpec.Item.Poly(
                listOf(cx - r * 0.58f, cy + r * 0.3f, cx - r * 0.25f, cy - r * 0.35f, cx, cy + r * 0.15f, cx + r * 0.25f, cy - r * 0.35f, cx + r * 0.58f, cy + r * 0.3f),
                closed = false, ink = Ink.ORANGE, smooth = true, strokeScale = strokeScale, layer = 2
            )
        )
        if (label.isNotBlank()) {
            out.add(DiagramSpec.Item.Label(cx, cy + r + 4.5f, label, ink, badge = false, place = LabelPlace.INLINE, sizeScale = 0.86f))
        }
        return out
    }

    /** Standard circular electrical meter (`A`, `V`, `G`) with centered letter inside. */
    fun meter(
        cx: Float, cy: Float, r: Float,
        symbol: String = "A",
        ink: Ink = Ink.TEAL,
        fillInk: Ink = ink,
        strokeScale: Float = 1.2f
    ): List<DiagramSpec.Item> = listOf(
        DiagramSpec.Item.Circle(
            cx = cx, cy = cy, r = r, fill = true, ink = ink, fillInk = fillInk,
            shade = FillShade.RADIAL, fillAlpha = 0.28f, strokeScale = strokeScale, layer = 0,
            text = symbol.take(4), textInk = ink
        )
    )

    /** Semiconductor diode / LED symbol between `(x1, y1)` and `(x2, y2)`. */
    fun diode(
        x1: Float, y1: Float, x2: Float, y2: Float,
        led: Boolean = false,
        ink: Ink = Ink.ACCENT,
        strokeScale: Float = 1.2f,
        label: String = "",
        layer: Int = 2
    ): List<DiagramSpec.Item> {
        val out = ArrayList<DiagramSpec.Item>()
        val len = hypot(x2 - x1, y2 - y1).coerceAtLeast(6f)
        val (u, n) = alongUnit(x1, y1, x2, y2)
        val triSpan = min(len * 0.36f, 8f)
        val halfH = triSpan * 0.65f
        val mx = (x1 + x2) * 0.5f
        val my = (y1 + y2) * 0.5f
        val baseX = mx - u.first * (triSpan * 0.5f)
        val baseY = my - u.second * (triSpan * 0.5f)
        val tipX = mx + u.first * (triSpan * 0.5f)
        val tipY = my + u.second * (triSpan * 0.5f)

        out.add(DiagramSpec.Item.Line(x1, y1, baseX, baseY, false, false, ink, strokeScale = strokeScale, layer = layer))
        out.add(DiagramSpec.Item.Line(tipX, tipY, x2, y2, false, false, ink, strokeScale = strokeScale, layer = layer))
        out.add(
            DiagramSpec.Item.Poly(
                listOf(
                    baseX - n.first * halfH, baseY - n.second * halfH,
                    baseX + n.first * halfH, baseY + n.second * halfH,
                    tipX, tipY
                ),
                closed = true, ink = ink, smooth = false, fill = true, fillInk = ink, fillAlpha = 0.32f, strokeScale = strokeScale, layer = 0
            )
        )
        out.add(
            DiagramSpec.Item.Line(
                tipX - n.first * halfH, tipY - n.second * halfH,
                tipX + n.first * halfH, tipY + n.second * halfH,
                false, false, ink, strokeScale = strokeScale * 1.4f, layer = layer
            )
        )
        if (led) {
            for (d in listOf(-2.5f, 2.5f)) {
                val px = mx + u.first * d + n.first * (halfH + 1.5f)
                val py = my + u.second * d + n.second * (halfH + 1.5f)
                out.add(DiagramSpec.Item.Line(px, py, px + (u.first + n.first) * 3.8f, py + (u.second + n.second) * 3.8f, true, false, Ink.GOLD, strokeScale = 0.9f, layer = layer))
            }
        }
        if (label.isNotBlank()) {
            out.add(DiagramSpec.Item.Label(mx - n.first * (halfH + 5f), my - n.second * (halfH + 5f), label, ink, badge = false, place = LabelPlace.INLINE, sizeScale = 0.88f))
        }
        return out
    }

    /** 3-bar electrical earth ground symbol descending from `(x, y)`. */
    fun ground(x: Float, y: Float, size: Float = 6f, ink: Ink = Ink.INK, strokeScale: Float = 1.2f): List<DiagramSpec.Item> = listOf(
        DiagramSpec.Item.Line(x, y, x, y + size * 0.55f, false, false, ink, strokeScale = strokeScale, layer = 2),
        DiagramSpec.Item.Line(x - size, y + size * 0.55f, x + size, y + size * 0.55f, false, false, ink, strokeScale = strokeScale * 1.2f, layer = 2),
        DiagramSpec.Item.Line(x - size * 0.65f, y + size * 0.85f, x + size * 0.65f, y + size * 0.85f, false, false, ink, strokeScale = strokeScale * 1.1f, layer = 2),
        DiagramSpec.Item.Line(x - size * 0.30f, y + size * 1.15f, x + size * 0.30f, y + size * 1.15f, false, false, ink, strokeScale = strokeScale, layer = 2)
    )

    /** Helical mechanical spring between `(x1, y1)` and `(x2, y2)`. */
    fun spring(
        x1: Float, y1: Float, x2: Float, y2: Float,
        coils: Int = 7,
        ink: Ink = Ink.TEAL,
        strokeScale: Float = 1.15f,
        label: String = ""
    ): List<DiagramSpec.Item> {
        val out = ArrayList<DiagramSpec.Item>()
        val len = hypot(x2 - x1, y2 - y1).coerceAtLeast(6f)
        val (u, n) = alongUnit(x1, y1, x2, y2)
        val safeCoils = coils.coerceIn(3, 14)
        val lead = len * 0.14f
        val body = len - lead * 2f
        val amp = (len * 0.12f).coerceIn(2.0f, 6.5f)
        val bx0 = x1 + u.first * lead
        val by0 = y1 + u.second * lead
        val bx1 = x2 - u.first * lead
        val by1 = y2 - u.second * lead
        val pts = ArrayList<Float>((safeCoils + 4) * 2)
        pts.add(x1); pts.add(y1)
        pts.add(bx0); pts.add(by0)
        val step = body / safeCoils
        for (i in 0 until safeCoils) {
            val t = (i + 0.5f) * step
            val side = if (i % 2 == 0) 1f else -1f
            pts.add(bx0 + u.first * t + n.first * amp * side)
            pts.add(by0 + u.second * t + n.second * amp * side)
        }
        pts.add(bx1); pts.add(by1)
        pts.add(x2); pts.add(y2)
        out.add(DiagramSpec.Item.Poly(pts, closed = false, ink = ink, smooth = true, strokeScale = strokeScale, layer = 2))
        if (label.isNotBlank()) {
            val mx = (x1 + x2) * 0.5f + n.first * (amp + 5f)
            val my = (y1 + y2) * 0.5f + n.second * (amp + 5f)
            out.add(DiagramSpec.Item.Label(mx, my, label, ink, badge = false, place = LabelPlace.INLINE, sizeScale = 0.88f))
        }
        return out
    }

    /** Mathematically generated precision toothed gear wheel + center axle hub. */
    fun gear(
        cx: Float, cy: Float, r: Float,
        teeth: Int = 12,
        ink: Ink = Ink.INK,
        fillInk: Ink = Ink.ACCENT,
        strokeScale: Float = 1.15f
    ): List<DiagramSpec.Item> {
        val count = teeth.coerceIn(6, 28)
        val rInner = r * 0.78f
        val rOuter = r
        val pts = ArrayList<Float>(count * 8)
        val step = (2.0 * PI) / count
        for (i in 0 until count) {
            val a0 = i * step
            val a1 = a0 + step * 0.20
            val a2 = a0 + step * 0.30
            val a3 = a0 + step * 0.70
            val a4 = a0 + step * 0.80
            for ((ang, rad) in listOf(a0 to rInner, a1 to rInner, a2 to rOuter, a3 to rOuter, a4 to rInner)) {
                pts.add(cx + (rad * cos(ang)).toFloat())
                pts.add(cy + (rad * sin(ang)).toFloat())
            }
        }
        return listOf(
            DiagramSpec.Item.Poly(pts, closed = true, ink = ink, smooth = false, fill = true, fillInk = fillInk, shade = FillShade.RADIAL, fillAlpha = 0.30f, strokeScale = strokeScale, layer = 0),
            DiagramSpec.Item.Circle(cx, cy, r * 0.32f, fill = true, ink = ink, fillInk = Ink.PAPER, fillAlpha = 0.92f, strokeScale = strokeScale * 0.9f, layer = 2),
            DiagramSpec.Item.Circle(cx, cy, r * 0.12f, fill = true, ink = ink, fillInk = ink, fillAlpha = 0.8f, layer = 2)
        )
    }

    /** Grooved mechanical pulley wheel + center axle + optional overhead bracket. */
    fun pulley(
        cx: Float, cy: Float, r: Float,
        ink: Ink = Ink.INK,
        fillInk: Ink = Ink.TEAL,
        strokeScale: Float = 1.2f,
        mountUp: Boolean = true
    ): List<DiagramSpec.Item> {
        val out = ArrayList<DiagramSpec.Item>()
        out.add(DiagramSpec.Item.Circle(cx, cy, r, fill = true, ink = ink, fillInk = fillInk, shade = FillShade.RADIAL, fillAlpha = 0.28f, strokeScale = strokeScale, layer = 0))
        out.add(DiagramSpec.Item.Circle(cx, cy, r * 0.72f, fill = false, ink = Ink.NOTE, dashed = true, strokeScale = 0.85f, layer = 2))
        out.add(DiagramSpec.Item.Circle(cx, cy, r * 0.22f, fill = true, ink = ink, fillInk = ink, fillAlpha = 0.85f, layer = 2))
        val dir = if (mountUp) -1f else 1f
        out.add(DiagramSpec.Item.Line(cx, cy, cx, cy + dir * r * 1.45f, false, false, Ink.BROWN, strokeScale = strokeScale * 1.35f, layer = 2))
        return out
    }

    /** Flowchart decision diamond with centered auto-fitted text. */
    fun diamond(
        cx: Float, cy: Float, w: Float, h: Float,
        ink: Ink = Ink.GOLD,
        fillInk: Ink = ink,
        fillAlpha: Float = 0.24f,
        strokeScale: Float = 1.2f,
        text: String = ""
    ): DiagramSpec.Item.Poly {
        val hw = w * 0.5f
        val hh = h * 0.5f
        return DiagramSpec.Item.Poly(
            pts = listOf(cx, cy - hh, cx + hw, cy, cx, cy + hh, cx - hw, cy),
            closed = true,
            ink = ink,
            smooth = false,
            fill = true,
            fillInk = fillInk,
            shade = FillShade.LINEAR,
            fillAlpha = fillAlpha,
            strokeScale = strokeScale,
            layer = 0,
            text = text,
            textInk = ink
        )
    }

    /** 3D database / tank cylinder with curved elliptical top and bottom + centered label. */
    fun cylinder(
        x: Float, y: Float, w: Float, h: Float,
        ink: Ink = Ink.ACCENT,
        fillInk: Ink = ink,
        fillAlpha: Float = 0.24f,
        strokeScale: Float = 1.2f,
        text: String = ""
    ): List<DiagramSpec.Item> {
        val capRy = (h * 0.16f).coerceIn(2f, 7f)
        val cx = x + w * 0.5f
        val rx = w * 0.5f
        val d = "M $x ${y + capRy} L $x ${y + h - capRy} " +
            "A $rx $capRy 0 0 0 ${x + w} ${y + h - capRy} " +
            "L ${x + w} ${y + capRy} Z"
        val cmds = SvgPathParser.parse(d)
        val out = ArrayList<DiagramSpec.Item>()
        out.add(DiagramSpec.Item.VectorPath(cmds, fill = true, ink = ink, fillInk = fillInk, shade = FillShade.LINEAR, fillAlpha = fillAlpha, strokeScale = strokeScale, layer = 0, text = text, textInk = ink))
        out.add(DiagramSpec.Item.Oval(cx, y + capRy, rx, capRy, fill = true, ink = ink, fillInk = fillInk, shade = FillShade.RADIAL, fillAlpha = (fillAlpha * 1.35f).coerceAtMost(0.55f), strokeScale = strokeScale, layer = 2))
        return out
    }

    /** Symmetric lab glassware (`round` | `conical` | `beaker` | `test_tube`) with optional liquid fill. */
    fun flask(
        cx: Float, cy: Float, w: Float, h: Float,
        kind: String = "conical",
        liquidFrac: Float = 0.42f,
        ink: Ink = Ink.TEAL,
        liquidInk: Ink = Ink.ACCENT,
        strokeScale: Float = 1.2f
    ): List<DiagramSpec.Item> {
        val out = ArrayList<DiagramSpec.Item>()
        val topY = cy - h * 0.5f
        val botY = cy + h * 0.5f
        val hw = w * 0.5f
        val neckW = hw * 0.34f
        val liqF = liquidFrac.coerceIn(0f, 0.85f)

        when (kind.lowercase()) {
            "round", "round_bottom" -> {
                val bulbR = min(hw, h * 0.36f)
                val bulbCy = botY - bulbR
                val neckTopY = topY
                val neckMeetY = bulbCy - bulbR * 0.86f
                // Glassware silhouette via smooth SVG path
                val d = "M ${cx - neckW} $neckTopY L ${cx - neckW} $neckMeetY " +
                    "C ${cx - bulbR * 1.05f} ${neckMeetY + bulbR * 0.2f}, ${cx - bulbR * 1.05f} ${botY - bulbR * 0.15f}, $cx $botY " +
                    "C ${cx + bulbR * 1.05f} ${botY - bulbR * 0.15f}, ${cx + bulbR * 1.05f} ${neckMeetY + bulbR * 0.2f}, ${cx + neckW} $neckMeetY " +
                    "L ${cx + neckW} $neckTopY"
                if (liqF > 0.05f) {
                    val liqSweep = 180f * (0.4f + liqF * 0.55f)
                    out.add(DiagramSpec.Item.Arc(cx, bulbCy, bulbR * 0.92f, bulbR * 0.92f, 90f - liqSweep * 0.5f, liqSweep, useCenter = false, fill = true, ink = liquidInk, fillInk = liquidInk, shade = FillShade.RADIAL, fillAlpha = 0.38f, strokeScale = 0.8f, layer = 1))
                }
                out.add(DiagramSpec.Item.VectorPath(SvgPathParser.parse(d), fill = true, ink = ink, fillInk = ink, shade = FillShade.LINEAR, fillAlpha = 0.10f, strokeScale = strokeScale, layer = 0))
                out.add(DiagramSpec.Item.Oval(cx, neckTopY, neckW * 1.18f, max(1.2f, neckW * 0.28f), fill = true, ink = ink, fillInk = Ink.PAPER, fillAlpha = 0.65f, strokeScale = strokeScale * 0.9f, layer = 2))
            }
            "beaker" -> {
                val d = "M ${cx - hw * 1.08f} $topY L ${cx - hw} ${topY + 2f} L ${cx - hw} ${botY - 3f} " +
                    "Q ${cx - hw} $botY ${cx - hw + 3f} $botY L ${cx + hw - 3f} $botY " +
                    "Q ${cx + hw} $botY ${cx + hw} ${botY - 3f} L ${cx + hw} ${topY + 2f} L ${cx + hw * 1.08f} $topY"
                if (liqF > 0.05f) {
                    val liqH = (h - 4f) * liqF
                    out.add(DiagramSpec.Item.Rect(cx - hw + 0.8f, botY - liqH, w - 1.6f, liqH - 0.6f, fill = true, ink = liquidInk, fillInk = liquidInk, rx = 1.5f, ry = 1.5f, shade = FillShade.LINEAR, fillAlpha = 0.34f, strokeScale = 0.7f, layer = 1))
                }
                out.add(DiagramSpec.Item.VectorPath(SvgPathParser.parse(d), fill = true, ink = ink, fillInk = ink, shade = FillShade.LINEAR, fillAlpha = 0.08f, strokeScale = strokeScale, layer = 0))
                // Measurement graduations
                for (g in 1..3) {
                    val gy = botY - h * 0.22f * g
                    out.add(DiagramSpec.Item.Line(cx - hw + 1.5f, gy, cx - hw + hw * 0.35f, gy, false, false, Ink.NOTE, strokeScale = 0.65f, layer = 2))
                }
            }
            "test_tube" -> {
                val r = hw
                val d = "M ${cx - r} $topY L ${cx - r} ${botY - r} A $r $r 0 0 0 ${cx + r} ${botY - r} L ${cx + r} $topY"
                if (liqF > 0.05f) {
                    val liqH = (h - 3f) * liqF
                    out.add(DiagramSpec.Item.Rect(cx - r + 0.6f, botY - liqH, w - 1.2f, liqH - 0.5f, fill = true, ink = liquidInk, fillInk = liquidInk, rx = r * 0.7f, ry = r * 0.7f, shade = FillShade.LINEAR, fillAlpha = 0.38f, strokeScale = 0.7f, layer = 1))
                }
                out.add(DiagramSpec.Item.VectorPath(SvgPathParser.parse(d), fill = true, ink = ink, fillInk = ink, fillAlpha = 0.08f, strokeScale = strokeScale, layer = 0))
                out.add(DiagramSpec.Item.Oval(cx, topY, r * 1.15f, max(1f, r * 0.25f), fill = true, ink = ink, fillInk = Ink.PAPER, fillAlpha = 0.6f, strokeScale = strokeScale * 0.9f, layer = 2))
            }
            else -> { // conical / Erlenmeyer flask
                val neckBotY = topY + h * 0.34f
                val d = "M ${cx - neckW} $topY L ${cx - neckW} $neckBotY L ${cx - hw} ${botY - 3f} " +
                    "Q ${cx - hw} $botY ${cx - hw + 3f} $botY L ${cx + hw - 3f} $botY " +
                    "Q ${cx + hw} $botY ${cx + hw} ${botY - 3f} L ${cx + neckW} $neckBotY L ${cx + neckW} $topY"
                if (liqF > 0.05f) {
                    val liqTopY = botY - (botY - neckBotY) * liqF
                    val t = (liqTopY - neckBotY) / (botY - neckBotY).coerceAtLeast(1f)
                    val lhw = neckW + (hw - neckW) * t
                    out.add(DiagramSpec.Item.Poly(listOf(cx - lhw + 0.8f, liqTopY, cx + lhw - 0.8f, liqTopY, cx + hw - 1.2f, botY - 1f, cx - hw + 1.2f, botY - 1f), closed = true, ink = liquidInk, smooth = false, fill = true, fillInk = liquidInk, shade = FillShade.LINEAR, fillAlpha = 0.35f, strokeScale = 0.7f, layer = 1))
                }
                out.add(DiagramSpec.Item.VectorPath(SvgPathParser.parse(d), fill = true, ink = ink, fillInk = ink, shade = FillShade.LINEAR, fillAlpha = 0.09f, strokeScale = strokeScale, layer = 0))
                out.add(DiagramSpec.Item.Oval(cx, topY, neckW * 1.18f, max(1f, neckW * 0.28f), fill = true, ink = ink, fillInk = Ink.PAPER, fillAlpha = 0.6f, strokeScale = strokeScale * 0.9f, layer = 2))
            }
        }
        return out
    }

    /** Precision Bunsen burner with base, air collar, barrel, and dual-cone flame. */
    fun burner(cx: Float, botY: Float, h: Float = 20f, ink: Ink = Ink.NOTE, strokeScale: Float = 1.1f): List<DiagramSpec.Item> {
        val baseH = h * 0.14f
        val barrelH = h * 0.46f
        val flameH = h * 0.40f
        val barrelTop = botY - baseH - barrelH
        return listOf(
            DiagramSpec.Item.Rect(cx - h * 0.34f, botY - baseH, h * 0.68f, baseH, fill = true, ink = ink, fillInk = ink, rx = 1.2f, ry = 1.2f, fillAlpha = 0.45f, strokeScale = strokeScale, layer = 0),
            DiagramSpec.Item.Rect(cx - h * 0.10f, barrelTop, h * 0.20f, barrelH, fill = true, ink = ink, fillInk = Ink.FAINT, rx = 0.8f, ry = 0.8f, fillAlpha = 0.40f, strokeScale = strokeScale, layer = 0),
            // Outer orange flame cone
            DiagramSpec.Item.VectorPath(
                SvgPathParser.parse("M ${cx - h * 0.12f} $barrelTop C ${cx - h * 0.22f} ${barrelTop - flameH * 0.45f}, ${cx - h * 0.06f} ${barrelTop - flameH * 0.8f}, $cx ${barrelTop - flameH} C ${cx + h * 0.06f} ${barrelTop - flameH * 0.8f}, ${cx + h * 0.22f} ${barrelTop - flameH * 0.45f}, ${cx + h * 0.12f} $barrelTop Z"),
                fill = true, ink = Ink.ORANGE, fillInk = Ink.GOLD, shade = FillShade.RADIAL, fillAlpha = 0.56f, strokeScale = strokeScale, layer = 0
            ),
            // Inner hot blue cone
            DiagramSpec.Item.VectorPath(
                SvgPathParser.parse("M ${cx - h * 0.06f} $barrelTop C ${cx - h * 0.08f} ${barrelTop - flameH * 0.35f}, $cx ${barrelTop - flameH * 0.55f}, $cx ${barrelTop - flameH * 0.60f} C $cx ${barrelTop - flameH * 0.55f}, ${cx + h * 0.08f} ${barrelTop - flameH * 0.35f}, ${cx + h * 0.06f} $barrelTop Z"),
                fill = true, ink = Ink.ACCENT, fillInk = Ink.TEAL, fillAlpha = 0.65f, strokeScale = 0.8f, layer = 2
            )
        )
    }

    /** Precision thermometer with glass stem, tick marks, mercury column, and bulb. */
    fun thermometer(x: Float, y1: Float, y2: Float, ink: Ink = Ink.INK, mercuryInk: Ink = Ink.WARN, strokeScale: Float = 1.0f): List<DiagramSpec.Item> {
        val top = min(y1, y2)
        val bot = max(y1, y2)
        val h = (bot - top).coerceAtLeast(8f)
        val stemW = (h * 0.09f).coerceIn(1.8f, 3.6f)
        val bulbR = stemW * 0.95f
        val bulbY = bot - bulbR
        val out = ArrayList<DiagramSpec.Item>()
        out.add(DiagramSpec.Item.Rect(x - stemW * 0.5f, top, stemW, bulbY - top, fill = true, ink = ink, fillInk = Ink.PAPER, rx = stemW * 0.5f, ry = stemW * 0.5f, fillAlpha = 0.80f, strokeScale = strokeScale, layer = 0))
        out.add(DiagramSpec.Item.Line(x, top + h * 0.25f, x, bulbY, false, false, mercuryInk, strokeScale = strokeScale * 1.1f, layer = 2))
        out.add(DiagramSpec.Item.Circle(x, bulbY, bulbR, fill = true, ink = ink, fillInk = mercuryInk, shade = FillShade.RADIAL, fillAlpha = 0.82f, strokeScale = strokeScale, layer = 2))
        for (i in 1..4) {
            val ty = top + h * (0.14f + i * 0.14f)
            out.add(DiagramSpec.Item.Line(x + stemW * 0.5f, ty, x + stemW * 0.5f + 1.8f, ty, false, false, Ink.NOTE, strokeScale = 0.7f, layer = 2))
        }
        return out
    }

    /** Exact perpendicular Liebig condenser between `(x1, y1)` and `(x2, y2)` with water jacket & nozzles. */
    fun condenser(
        x1: Float, y1: Float, x2: Float, y2: Float,
        jacketWidth: Float = 7.5f,
        ink: Ink = Ink.TEAL,
        jacketInk: Ink = Ink.ACCENT,
        strokeScale: Float = 1.15f
    ): List<DiagramSpec.Item> {
        val out = ArrayList<DiagramSpec.Item>()
        val len = hypot(x2 - x1, y2 - y1).coerceAtLeast(8f)
        val (u, n) = alongUnit(x1, y1, x2, y2)
        val jStart = len * 0.16f
        val jEnd = len * 0.84f
        val hw = jacketWidth * 0.5f

        val jx1 = x1 + u.first * jStart
        val jy1 = y1 + u.second * jStart
        val jx2 = x1 + u.first * jEnd
        val jy2 = y1 + u.second * jEnd

        // Outer water cooling jacket aligned perfectly perpendicular to inner vapor tube
        out.add(
            DiagramSpec.Item.Poly(
                pts = listOf(
                    jx1 + n.first * hw, jy1 + n.second * hw,
                    jx2 + n.first * hw, jy2 + n.second * hw,
                    jx2 - n.first * hw, jy2 - n.second * hw,
                    jx1 - n.first * hw, jy1 - n.second * hw
                ),
                closed = true, ink = jacketInk, smooth = false, fill = true, fillInk = jacketInk,
                shade = FillShade.LINEAR, fillAlpha = 0.22f, strokeScale = strokeScale, layer = 0
            )
        )
        // Inner vapor tube running cleanly end-to-end
        out.add(DiagramSpec.Item.Line(x1, y1, x2, y2, arrow = true, dashed = false, ink = ink, strokeScale = strokeScale * 1.2f, layer = 2))
        // Water In (lower/exit end) and Water Out (upper/entry end) perpendicular nozzles
        val inBaseX = x1 + u.first * (jEnd - len * 0.08f) - n.first * hw
        val inBaseY = y1 + u.second * (jEnd - len * 0.08f) - n.second * hw
        out.add(DiagramSpec.Item.Line(inBaseX - n.first * 4.5f, inBaseY - n.second * 4.5f, inBaseX, inBaseY, arrow = true, dashed = false, ink = jacketInk, strokeScale = strokeScale, layer = 2))

        val outBaseX = x1 + u.first * (jStart + len * 0.08f) + n.first * hw
        val outBaseY = y1 + u.second * (jStart + len * 0.08f) + n.second * hw
        out.add(DiagramSpec.Item.Line(outBaseX, outBaseY, outBaseX + n.first * 4.5f, outBaseY + n.second * 4.5f, arrow = true, dashed = false, ink = jacketInk, strokeScale = strokeScale, layer = 2))
        return out
    }

    /** Engineering dimension line offset perpendicularly by [offset] with extension ticks & centered label. */
    fun dimension(
        x1: Float, y1: Float, x2: Float, y2: Float,
        text: String,
        offset: Float = 5f,
        ink: Ink = Ink.NOTE,
        strokeScale: Float = 0.95f
    ): List<DiagramSpec.Item> {
        val out = ArrayList<DiagramSpec.Item>()
        val (_, n) = alongUnit(x1, y1, x2, y2)
        val ox = n.first * offset
        val oy = n.second * offset
        val dx1 = x1 + ox
        val dy1 = y1 + oy
        val dx2 = x2 + ox
        val dy2 = y2 + oy
        val tickSign = if (offset >= 0f) 1f else -1f
        // Faint extension ticks
        out.add(DiagramSpec.Item.Line(x1, y1, dx1 + n.first * tickSign * 2f, dy1 + n.second * tickSign * 2f, false, true, Ink.FAINT, strokeScale = 0.7f, layer = 2))
        out.add(DiagramSpec.Item.Line(x2, y2, dx2 + n.first * tickSign * 2f, dy2 + n.second * tickSign * 2f, false, true, Ink.FAINT, strokeScale = 0.7f, layer = 2))
        out.add(DiagramSpec.Item.Line(dx1, dy1, dx2, dy2, arrow = true, dashed = false, ink = ink, doubleArrow = true, strokeScale = strokeScale, layer = 2))
        if (text.isNotBlank()) {
            val mx = (dx1 + dx2) * 0.5f + n.first * tickSign * 4.2f
            val my = (dy1 + dy2) * 0.5f + n.second * tickSign * 4.2f
            out.add(DiagramSpec.Item.Label(mx, my, text, ink, badge = true, place = LabelPlace.INLINE, sizeScale = 0.88f))
        }
        return out
    }

    /** Precision angle arc + centered degree/symbol label along the bisector. */
    fun angle(
        cx: Float, cy: Float, r: Float,
        startAngle: Float, sweepAngle: Float,
        text: String = "",
        ink: Ink = Ink.GOLD,
        strokeScale: Float = 1.15f
    ): List<DiagramSpec.Item> {
        val out = ArrayList<DiagramSpec.Item>()
        out.add(DiagramSpec.Item.Arc(cx, cy, r, r, startAngle, sweepAngle, useCenter = false, fill = false, ink = ink, strokeScale = strokeScale, layer = 2))
        if (text.isNotBlank()) {
            val midRad = Math.toRadians((startAngle + sweepAngle * 0.5f).toDouble())
            val lr = r + 5.0f
            out.add(
                DiagramSpec.Item.Label(
                    cx + (lr * cos(midRad)).toFloat(),
                    cy + (lr * sin(midRad)).toFloat(),
                    text, ink, badge = false, place = LabelPlace.INLINE, sizeScale = 0.90f
                )
            )
        }
        return out
    }

    /** Smooth quadratic Bezier curved arrow with accurately tangent-aligned arrowhead + midpoint label. */
    fun curvedArrow(
        x1: Float, y1: Float, cx: Float, cy: Float, x2: Float, y2: Float,
        ink: Ink = Ink.ACCENT,
        arrow: Boolean = true,
        doubleArrow: Boolean = false,
        dashed: Boolean = false,
        strokeScale: Float = 1.2f,
        text: String = "",
        layer: Int = 2
    ): List<DiagramSpec.Item> {
        val out = ArrayList<DiagramSpec.Item>()
        val cmds = listOf(PathCmd.MoveTo(x1, y1), PathCmd.QuadTo(cx, cy, x2, y2))
        out.add(DiagramSpec.Item.VectorPath(cmds, fill = false, ink = ink, dashed = dashed, strokeScale = strokeScale, layer = layer))
        fun addTip(fx: Float, fy: Float, tx: Float, ty: Float) {
            val d = hypot(tx - fx, ty - fy)
            if (d < 0.5f) return
            val a = atan2((ty - fy).toDouble(), (tx - fx).toDouble())
            val headLen = 2.8f
            for (delta in listOf(2.65, -2.65)) {
                out.add(DiagramSpec.Item.Line(tx, ty, tx + (headLen * cos(a + delta)).toFloat(), ty + (headLen * sin(a + delta)).toFloat(), false, false, ink, strokeScale = strokeScale, layer = layer))
            }
        }
        if (arrow) addTip(cx, cy, x2, y2)
        if (doubleArrow) addTip(cx, cy, x1, y1)
        if (text.isNotBlank()) {
            val mx = 0.25f * x1 + 0.5f * cx + 0.25f * x2
            val my = 0.25f * y1 + 0.5f * cy + 0.25f * y2
            out.add(DiagramSpec.Item.Label(mx, my, text, ink, badge = true, place = LabelPlace.INLINE, sizeScale = 0.85f))
        }
        return out
    }
}
