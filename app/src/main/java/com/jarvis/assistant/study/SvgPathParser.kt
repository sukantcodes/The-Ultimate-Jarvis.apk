package com.jarvis.assistant.study

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure-Kotlin SVG path (`d` attribute) parser converting `M/m, L/l, H/h, V/v, C/c, S/s, Q/q, T/t, A/a, Z/z`
 * into immutable [PathCmd] primitives for JVM diagram layout, bounding-box clamping, and progressive canvas drawing.
 */
object SvgPathParser {

    fun parse(d: String): List<PathCmd> {
        if (d.isBlank()) return emptyList()
        val tokens = tokenize(d)
        if (tokens.isEmpty()) return emptyList()

        val cmds = ArrayList<PathCmd>(tokens.size)
        var i = 0
        var cmd = '\u0000'
        var cx = 0f
        var cy = 0f
        var sx = 0f
        var sy = 0f
        var lastCtrlX = 0f
        var lastCtrlY = 0f
        var lastCmd = '\u0000'

        fun hasNum(): Boolean = i < tokens.size && tokens[i].num != null
        fun nextNum(): Float? = if (hasNum()) tokens[i++].num else null

        while (i < tokens.size) {
            val tok = tokens[i]
            if (tok.cmd != null) {
                cmd = tok.cmd
                i++
                if (cmd == 'z' || cmd == 'Z') {
                    cmds.add(PathCmd.Close)
                    cx = sx
                    cy = sy
                    lastCmd = cmd
                    continue
                }
            } else if (cmd == '\u0000') {
                i++
                continue
            }

            when (cmd) {
                'M', 'm' -> {
                    val x = nextNum() ?: break
                    val y = nextNum() ?: break
                    val nx = if (cmd == 'm') cx + x else x
                    val ny = if (cmd == 'm') cy + y else y
                    cmds.add(PathCmd.MoveTo(nx, ny))
                    cx = nx; cy = ny
                    sx = nx; sy = ny
                    lastCmd = cmd
                    // Per SVG spec, additional coordinate pairs after MoveTo become LineTo
                    cmd = if (cmd == 'm') 'l' else 'L'
                }
                'L', 'l' -> {
                    val x = nextNum() ?: break
                    val y = nextNum() ?: break
                    val nx = if (cmd == 'l') cx + x else x
                    val ny = if (cmd == 'l') cy + y else y
                    cmds.add(PathCmd.LineTo(nx, ny))
                    cx = nx; cy = ny
                    lastCmd = cmd
                }
                'H', 'h' -> {
                    val x = nextNum() ?: break
                    val nx = if (cmd == 'h') cx + x else x
                    cmds.add(PathCmd.LineTo(nx, cy))
                    cx = nx
                    lastCmd = cmd
                }
                'V', 'v' -> {
                    val y = nextNum() ?: break
                    val ny = if (cmd == 'v') cy + y else y
                    cmds.add(PathCmd.LineTo(cx, ny))
                    cy = ny
                    lastCmd = cmd
                }
                'C', 'c' -> {
                    val x1 = nextNum() ?: break
                    val y1 = nextNum() ?: break
                    val x2 = nextNum() ?: break
                    val y2 = nextNum() ?: break
                    val x = nextNum() ?: break
                    val y = nextNum() ?: break
                    val rel = cmd == 'c'
                    val ax1 = if (rel) cx + x1 else x1
                    val ay1 = if (rel) cy + y1 else y1
                    val ax2 = if (rel) cx + x2 else x2
                    val ay2 = if (rel) cy + y2 else y2
                    val ax = if (rel) cx + x else x
                    val ay = if (rel) cy + y else y
                    cmds.add(PathCmd.CubicTo(ax1, ay1, ax2, ay2, ax, ay))
                    lastCtrlX = ax2; lastCtrlY = ay2
                    cx = ax; cy = ay
                    lastCmd = cmd
                }
                'S', 's' -> {
                    val x2 = nextNum() ?: break
                    val y2 = nextNum() ?: break
                    val x = nextNum() ?: break
                    val y = nextNum() ?: break
                    val rel = cmd == 's'
                    val hasPrevCubic = lastCmd in charArrayOf('C', 'c', 'S', 's')
                    val ax1 = if (hasPrevCubic) 2f * cx - lastCtrlX else cx
                    val ay1 = if (hasPrevCubic) 2f * cy - lastCtrlY else cy
                    val ax2 = if (rel) cx + x2 else x2
                    val ay2 = if (rel) cy + y2 else y2
                    val ax = if (rel) cx + x else x
                    val ay = if (rel) cy + y else y
                    cmds.add(PathCmd.CubicTo(ax1, ay1, ax2, ay2, ax, ay))
                    lastCtrlX = ax2; lastCtrlY = ay2
                    cx = ax; cy = ay
                    lastCmd = cmd
                }
                'Q', 'q' -> {
                    val x1 = nextNum() ?: break
                    val y1 = nextNum() ?: break
                    val x = nextNum() ?: break
                    val y = nextNum() ?: break
                    val rel = cmd == 'q'
                    val ax1 = if (rel) cx + x1 else x1
                    val ay1 = if (rel) cy + y1 else y1
                    val ax = if (rel) cx + x else x
                    val ay = if (rel) cy + y else y
                    cmds.add(PathCmd.QuadTo(ax1, ay1, ax, ay))
                    lastCtrlX = ax1; lastCtrlY = ay1
                    cx = ax; cy = ay
                    lastCmd = cmd
                }
                'T', 't' -> {
                    val x = nextNum() ?: break
                    val y = nextNum() ?: break
                    val rel = cmd == 't'
                    val hasPrevQuad = lastCmd in charArrayOf('Q', 'q', 'T', 't')
                    val ax1 = if (hasPrevQuad) 2f * cx - lastCtrlX else cx
                    val ay1 = if (hasPrevQuad) 2f * cy - lastCtrlY else cy
                    val ax = if (rel) cx + x else x
                    val ay = if (rel) cy + y else y
                    cmds.add(PathCmd.QuadTo(ax1, ay1, ax, ay))
                    lastCtrlX = ax1; lastCtrlY = ay1
                    cx = ax; cy = ay
                    lastCmd = cmd
                }
                'A', 'a' -> {
                    val rx = nextNum() ?: break
                    val ry = nextNum() ?: break
                    val rot = nextNum() ?: break
                    val large = nextNum() ?: break
                    val sweep = nextNum() ?: break
                    val x = nextNum() ?: break
                    val y = nextNum() ?: break
                    val rel = cmd == 'a'
                    val ax = if (rel) cx + x else x
                    val ay = if (rel) cy + y else y
                    appendArcCubics(cmds, cx, cy, rx, ry, rot, large != 0f, sweep != 0f, ax, ay)
                    cx = ax; cy = ay
                    lastCmd = cmd
                }
                else -> i++
            }
        }
        return cmds
    }

    /** Samples curve points along [cmds] for tight bounding-box & anchor snapping calculations. */
    fun samplePoints(cmds: List<PathCmd>): List<Pair<Float, Float>> {
        val pts = ArrayList<Pair<Float, Float>>(cmds.size * 3)
        var cx = 0f
        var cy = 0f
        var sx = 0f
        var sy = 0f
        for (c in cmds) {
            when (c) {
                is PathCmd.MoveTo -> { cx = c.x; cy = c.y; sx = cx; sy = cy; pts.add(cx to cy) }
                is PathCmd.LineTo -> {
                    pts.add(((cx + c.x) * 0.5f) to ((cy + c.y) * 0.5f))
                    cx = c.x; cy = c.y
                    pts.add(cx to cy)
                }
                is PathCmd.QuadTo -> {
                    for (step in 1..4) {
                        val t = step * 0.25f
                        val mt = 1f - t
                        val px = mt * mt * cx + 2f * mt * t * c.cx + t * t * c.x
                        val py = mt * mt * cy + 2f * mt * t * c.cy + t * t * c.y
                        pts.add(px to py)
                    }
                    cx = c.x; cy = c.y
                }
                is PathCmd.CubicTo -> {
                    for (step in 1..4) {
                        val t = step * 0.25f
                        val mt = 1f - t
                        val mt2 = mt * mt
                        val t2 = t * t
                        val px = mt2 * mt * cx + 3f * mt2 * t * c.c1x + 3f * mt * t2 * c.c2x + t2 * t * c.x
                        val py = mt2 * mt * cy + 3f * mt2 * t * c.c1y + 3f * mt * t2 * c.c2y + t2 * t * c.y
                        pts.add(px to py)
                    }
                    cx = c.x; cy = c.y
                }
                is PathCmd.Close -> { cx = sx; cy = sy }
            }
        }
        return pts
    }

    private class Tok(val cmd: Char? = null, val num: Float? = null)

    private fun tokenize(d: String): List<Tok> {
        val out = ArrayList<Tok>()
        val n = d.length
        var i = 0
        while (i < n) {
            val ch = d[i]
            when {
                ch.isWhitespace() || ch == ',' -> i++
                ch.isLetter() -> {
                    out.add(Tok(cmd = ch))
                    i++
                }
                ch == '-' || ch == '+' || ch == '.' || ch.isDigit() -> {
                    val start = i
                    if (ch == '-' || ch == '+') i++
                    var sawDot = ch == '.'
                    var sawExp = false
                    while (i < n) {
                        val c = d[i]
                        when {
                            c.isDigit() -> i++
                            c == '.' && !sawDot && !sawExp -> { sawDot = true; i++ }
                            (c == 'e' || c == 'E') && !sawExp -> {
                                sawExp = true; i++
                                if (i < n && (d[i] == '+' || d[i] == '-')) i++
                            }
                            else -> break
                        }
                    }
                    val v = d.substring(start, i).toDoubleOrNull()
                    if (v != null && v.isFinite()) {
                        out.add(Tok(num = v.toFloat().coerceIn(-10_000f, 10_000f)))
                    }
                }
                else -> i++
            }
        }
        return out
    }

    /** W3C SVG F.6.5 / F.6.6 elliptical arc parameterization into cubic Bezier segments. */
    private fun appendArcCubics(
        out: MutableList<PathCmd>,
        x0: Float, y0: Float,
        rawRx: Float, rawRy: Float,
        angleDeg: Float,
        largeArc: Boolean,
        sweepFlag: Boolean,
        x: Float, y: Float
    ) {
        if (abs(x - x0) < 1e-4f && abs(y - y0) < 1e-4f) return
        var rx = abs(rawRx).toDouble()
        var ry = abs(rawRy).toDouble()
        if (rx < 1e-5 || ry < 1e-5) {
            out.add(PathCmd.LineTo(x, y))
            return
        }

        val phi = Math.toRadians(angleDeg.toDouble())
        val cosPhi = cos(phi)
        val sinPhi = sin(phi)
        val dx2 = (x0 - x) * 0.5
        val dy2 = (y0 - y) * 0.5
        val x1p = cosPhi * dx2 + sinPhi * dy2
        val y1p = -sinPhi * dx2 + cosPhi * dy2

        val lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
        if (lambda > 1.0) {
            val s = sqrt(lambda)
            rx *= s
            ry *= s
        }

        val rx2 = rx * rx
        val ry2 = ry * ry
        val x1p2 = x1p * x1p
        val y1p2 = y1p * y1p
        val denom = rx2 * y1p2 + ry2 * x1p2
        if (denom < 1e-10) {
            out.add(PathCmd.LineTo(x, y))
            return
        }
        val num = max(0.0, rx2 * ry2 - denom)
        val coef = (if (largeArc == sweepFlag) -1.0 else 1.0) * sqrt(num / denom)
        val cxp = coef * (rx * y1p / ry)
        val cyp = coef * -(ry * x1p / rx)

        val cx = cosPhi * cxp - sinPhi * cyp + (x0 + x) * 0.5
        val cy = sinPhi * cxp + cosPhi * cyp + (y0 + y) * 0.5

        fun vecAngle(ux: Double, uy: Double, vx: Double, vy: Double): Double {
            val dot = ux * vx + uy * vy
            val len = sqrt((ux * ux + uy * uy) * (vx * vx + vy * vy)).coerceAtLeast(1e-12)
            val cosA = (dot / len).coerceIn(-1.0, 1.0)
            val sign = if (ux * vy - uy * vx < 0) -1.0 else 1.0
            return sign * acos(cosA)
        }

        val ux = (x1p - cxp) / rx
        val uy = (y1p - cyp) / ry
        val vx = (-x1p - cxp) / rx
        val vy = (-y1p - cyp) / ry
        val theta1 = vecAngle(1.0, 0.0, ux, uy)
        var dTheta = vecAngle(ux, uy, vx, vy)
        if (!sweepFlag && dTheta > 0) dTheta -= 2.0 * PI
        else if (sweepFlag && dTheta < 0) dTheta += 2.0 * PI

        val segs = max(1, ceil(abs(dTheta) / (PI * 0.5)).toInt())
        val delta = dTheta / segs
        val t = (4.0 / 3.0) * kotlin.math.tan(delta * 0.25)

        var a1 = theta1
        for (s in 0 until segs) {
            val a2 = a1 + delta
            val sin1 = sin(a1); val cos1 = cos(a1)
            val sin2 = sin(a2); val cos2 = cos(a2)

            val p1x = cos1 - sin1 * t
            val p1y = sin1 + cos1 * t
            val p2x = cos2 + sin2 * t
            val p2y = sin2 - cos2 * t

            fun mapX(px: Double, py: Double) = (cosPhi * rx * px - sinPhi * ry * py + cx).toFloat()
            fun mapY(px: Double, py: Double) = (sinPhi * rx * px + cosPhi * ry * py + cy).toFloat()

            out.add(
                PathCmd.CubicTo(
                    mapX(p1x, p1y), mapY(p1x, p1y),
                    mapX(p2x, p2y), mapY(p2x, p2y),
                    if (s == segs - 1) x else mapX(cos2, sin2),
                    if (s == segs - 1) y else mapY(cos2, sin2)
                )
            )
            a1 = a2
        }
    }
}
