package com.jarvis.assistant.study

import com.google.gson.JsonObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A diagram described as data. Shapes live in a 0..100 coordinate space (y grows downward), and graphs are described by a
 * function plus an x-range. The model never draws pixels; it only names the shapes, and the renderer lays them out.
 */
data class DiagramSpec(
    val title: String,
    val items: List<Item>,
    val graph: GraphSpec? = null
) {
    sealed class Item {
        data class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val arrow: Boolean, val dashed: Boolean, val ink: Ink) : Item()
        data class Circle(val cx: Float, val cy: Float, val r: Float, val fill: Boolean, val ink: Ink) : Item()
        data class Oval(val cx: Float, val cy: Float, val rx: Float, val ry: Float, val fill: Boolean, val ink: Ink) : Item()
        data class Rect(val x: Float, val y: Float, val w: Float, val h: Float, val fill: Boolean, val ink: Ink) : Item()
        data class Poly(val pts: List<Float>, val closed: Boolean, val ink: Ink) : Item()
        data class Label(val x: Float, val y: Float, val text: String, val ink: Ink) : Item()
    }

    data class GraphSpec(
        val exprs: List<String>,
        val xMin: Double, val xMax: Double,
        val yMin: Double?, val yMax: Double?,
        val points: List<Pair<Double, Double>>,
        val xLabel: String, val yLabel: String
    )

    companion object {
        private const val MAX_ITEMS = 80

        /** Returns null for a spec with nothing drawable, so an empty or broken diagram is never shown as if it were real. */
        fun parse(o: JsonObject): DiagramSpec? {
            val title = o.str("title") ?: o.str("caption").orEmpty()
            val items = ArrayList<Item>()
            val arr = (o.get("items") ?: o.get("shapes"))?.takeIf { it.isJsonArray }?.asJsonArray
            if (arr != null) {
                for (e in arr) {
                    if (items.size >= MAX_ITEMS) break
                    val s = e.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                    parseItem(s)?.let { items.add(it) }
                }
            }
            val graph = (o.get("graph")?.takeIf { it.isJsonObject }?.asJsonObject ?: o.takeIf { it.has("fn") || it.has("function") || it.has("points") })?.let { parseGraph(it) }
            if (items.isEmpty() && graph == null) return null
            return DiagramSpec(title, items, graph)
        }

        private fun JsonObject.str(k: String): String? = get(k)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
        private fun JsonObject.num(vararg ks: String): Float? {
            for (k in ks) {
                val v = get(k)?.takeIf { it.isJsonPrimitive } ?: continue
                val d = if (v.asJsonPrimitive.isNumber) v.asDouble else v.asString.trim().toDoubleOrNull()
                if (d != null && d.isFinite()) return d.toFloat().coerceIn(-1000f, 1000f)
            }
            return null
        }
        private fun JsonObject.dbl(vararg ks: String): Double? = num(*ks)?.toDouble()
        private fun JsonObject.flag(k: String) = get(k)?.takeIf { it.isJsonPrimitive }?.let { it.asString.equals("true", true) || it.asString == "1" } ?: false

        private fun inkOf(o: JsonObject): Ink = when (o.str("color")?.lowercase() ?: o.str("ink")?.lowercase()) {
            "accent", "blue" -> Ink.ACCENT
            "red", "warn" -> Ink.WARN
            "green", "result" -> Ink.RESULT
            "gray", "grey", "faint" -> Ink.FAINT
            "note" -> Ink.NOTE
            else -> Ink.INK
        }

        private fun parseItem(o: JsonObject): Item? {
            val ink = inkOf(o)
            return when (o.str("t")?.lowercase() ?: o.str("type")?.lowercase() ?: o.str("shape")?.lowercase()) {
                "line", "arrow", "vector", "ray", "segment" -> {
                    val x1 = o.num("x1") ?: return null; val y1 = o.num("y1") ?: return null
                    val x2 = o.num("x2") ?: return null; val y2 = o.num("y2") ?: return null
                    val kind = o.str("t")?.lowercase() ?: o.str("type")?.lowercase().orEmpty()
                    Item.Line(x1, y1, x2, y2, o.flag("arrow") || kind == "arrow" || kind == "vector" || kind == "ray", o.flag("dashed"), ink)
                }
                "circle" -> Item.Circle(o.num("cx", "x") ?: return null, o.num("cy", "y") ?: return null, (o.num("r") ?: return null).coerceAtLeast(0.5f), o.flag("fill"), ink)
                "oval", "ellipse" -> Item.Oval(o.num("cx", "x") ?: return null, o.num("cy", "y") ?: return null, (o.num("rx") ?: return null).coerceAtLeast(0.5f), (o.num("ry") ?: return null).coerceAtLeast(0.5f), o.flag("fill"), ink)
                "rect", "box", "block" -> Item.Rect(o.num("x") ?: return null, o.num("y") ?: return null, o.num("w", "width") ?: return null, o.num("h", "height") ?: return null, o.flag("fill"), ink)
                "poly", "polygon", "polyline", "triangle", "path" -> {
                    val pa = o.get("pts")?.takeIf { it.isJsonArray }?.asJsonArray ?: o.get("points")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
                    val flat = ArrayList<Float>()
                    for (p in pa) {
                        if (p.isJsonArray && p.asJsonArray.size() >= 2) {
                            flat.add(p.asJsonArray[0].asFloat); flat.add(p.asJsonArray[1].asFloat)
                        } else if (p.isJsonPrimitive && p.asJsonPrimitive.isNumber) flat.add(p.asFloat)
                    }
                    if (flat.size < 4 || flat.size % 2 != 0) return null
                    val kind = o.str("t")?.lowercase() ?: o.str("type")?.lowercase().orEmpty()
                    Item.Poly(flat, kind != "polyline" && kind != "path" && !(o.get("closed")?.asString == "false"), ink)
                }
                "label", "text" -> {
                    val text = o.str("text") ?: o.str("content") ?: o.str("label") ?: return null
                    Item.Label(o.num("x") ?: return null, o.num("y") ?: return null, text.take(60), ink)
                }
                else -> null
            }
        }

        private fun parseGraph(o: JsonObject): GraphSpec? {
            val exprs = ArrayList<String>()
            for (k in listOf("fn", "function", "y")) {
                val v = o.get(k) ?: continue
                if (v.isJsonPrimitive) exprs.add(v.asString.trim().removePrefix("y=").removePrefix("y ="))
                else if (v.isJsonArray) v.asJsonArray.forEach { if (it.isJsonPrimitive) exprs.add(it.asString.trim()) }
            }
            val pts = ArrayList<Pair<Double, Double>>()
            o.get("points")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { p ->
                if (p.isJsonArray && p.asJsonArray.size() >= 2) pts.add(p.asJsonArray[0].asDouble to p.asJsonArray[1].asDouble)
            }
            if (exprs.isEmpty() && pts.isEmpty()) return null
            val xMin = o.dbl("xmin", "x_min") ?: pts.minOfOrNull { it.first } ?: -5.0
            val xMax = o.dbl("xmax", "x_max") ?: pts.maxOfOrNull { it.first } ?: 5.0
            if (xMax <= xMin) return null
            return GraphSpec(exprs.filter { it.isNotEmpty() }.take(3), xMin, xMax, o.dbl("ymin", "y_min"), o.dbl("ymax", "y_max"), pts, o.str("xlabel").orEmpty(), o.str("ylabel").orEmpty())
        }
    }
}

object StudyDiagramLayout {

    class Result(val ops: List<DrawOp>, val width: Float, val height: Float)

    /** Lays the diagram out in a box [w] wide, as tall as its content needs up to [maxH]. */
    fun layout(spec: DiagramSpec, w: Float, maxH: Float, metrics: TextMetrics, stroke: Float): Result {
        val ops = ArrayList<DrawOp>()
        val size = min(w, maxH)
        val labelSize = (size * 0.07f).coerceIn(13f, 30f)
        val g = spec.graph
        var h = 0f
        if (spec.title.isNotBlank()) {
            ops.add(DrawOp.Glyphs(spec.title, 0f, labelSize, labelSize, Face.BODY_BOLD, Ink.NOTE))
            h = labelSize * 1.7f
        }
        if (spec.items.isNotEmpty()) {
            val boxH = if (g != null) maxH * 0.55f else maxH
            val sc = min(w / 100f, boxH / 100f)
            val offX = (w - 100f * sc) / 2f
            for (item in spec.items) ops.addAll(shape(item, sc, offX, h, labelSize, stroke, metrics))
            h += 100f * sc + labelSize * 0.6f
        }
        if (g != null) {
            val gh = min(maxH * 0.8f, w * 0.62f)
            ops.addAll(graph(g, 0f, h, w, gh, labelSize, stroke, metrics))
            h += gh + labelSize
        }
        return Result(ops, w, h)
    }

    private fun shape(item: DiagramSpec.Item, sc: Float, ox: Float, oy: Float, labelSize: Float, stroke: Float, metrics: TextMetrics): List<DrawOp> {
        fun X(v: Float) = ox + v * sc
        fun Y(v: Float) = oy + v * sc
        return when (item) {
            is DiagramSpec.Item.Line -> {
                val out = ArrayList<DrawOp>()
                out.add(DrawOp.Line(X(item.x1), Y(item.y1), X(item.x2), Y(item.y2), stroke, item.ink, item.dashed))
                if (item.arrow) {
                    val a = atan2((Y(item.y2) - Y(item.y1)).toDouble(), (X(item.x2) - X(item.x1)).toDouble())
                    val len = stroke * 5f
                    for (d in listOf(2.65, -2.65)) {
                        out.add(DrawOp.Line(X(item.x2), Y(item.y2), X(item.x2) + (len * cos(a + d)).toFloat(), Y(item.y2) + (len * sin(a + d)).toFloat(), stroke, item.ink))
                    }
                }
                out
            }
            is DiagramSpec.Item.Circle -> listOf(DrawOp.Oval(X(item.cx), Y(item.cy), item.r * sc, item.r * sc, stroke, item.ink, item.fill))
            is DiagramSpec.Item.Oval -> listOf(DrawOp.Oval(X(item.cx), Y(item.cy), item.rx * sc, item.ry * sc, stroke, item.ink, item.fill))
            is DiagramSpec.Item.Rect -> listOf(DrawOp.Rect(X(item.x), Y(item.y), item.w * sc, item.h * sc, stroke, item.ink, item.fill))
            is DiagramSpec.Item.Poly -> {
                val p = FloatArray(item.pts.size)
                for (i in p.indices step 2) { p[i] = X(item.pts[i]); p[i + 1] = Y(item.pts[i + 1]) }
                listOf(DrawOp.Poly(p, stroke, item.ink, item.closed))
            }
            is DiagramSpec.Item.Label -> {
                val tw = metrics.width(item.text, labelSize, Face.BODY)
                // Labels are centred on their point so a label never covers the shape it names.
                listOf(DrawOp.Glyphs(item.text, X(item.x) - tw / 2f, Y(item.y) + labelSize * 0.35f, labelSize, Face.BODY, if (item.ink == Ink.INK) Ink.NOTE else item.ink))
            }
        }
    }

    private fun graph(g: DiagramSpec.GraphSpec, ox: Float, oy: Float, w: Float, h: Float, labelSize: Float, stroke: Float, metrics: TextMetrics): List<DrawOp> {
        val out = ArrayList<DrawOp>()
        val padL = labelSize * 1.6f
        val padB = labelSize * 1.6f
        val pw = w - padL - labelSize
        val ph = h - padB - labelSize
        // sample every function; a sample that cannot be computed leaves a gap instead of a made-up value
        val series = ArrayList<List<Pair<Double, Double?>>>()
        val n = 160
        for (expr in g.exprs) {
            val s = ArrayList<Pair<Double, Double?>>()
            for (i in 0..n) {
                val x = g.xMin + (g.xMax - g.xMin) * i / n
                s.add(x to StudyExpression.evaluate(expr, mapOf("x" to x), implicitMultiply = true))
            }
            series.add(s)
        }
        val ys = ArrayList<Double>()
        series.forEach { s -> s.forEach { (_, y) -> if (y != null && abs(y) < 1e9) ys.add(y) } }
        g.points.forEach { ys.add(it.second) }
        if (ys.isEmpty()) return out
        var yMin = g.yMin ?: ys.min()
        var yMax = g.yMax ?: ys.max()
        if (yMax - yMin < 1e-9) { yMin -= 1; yMax += 1 }
        if (g.yMin == null) yMin -= (yMax - yMin) * 0.08
        if (g.yMax == null) yMax += (yMax - yMin) * 0.08
        fun px(x: Double) = ox + padL + ((x - g.xMin) / (g.xMax - g.xMin) * pw).toFloat()
        fun py(y: Double) = oy + labelSize + (ph - ((y - yMin) / (yMax - yMin) * ph)).toFloat()
        val x0 = if (g.xMin <= 0 && g.xMax >= 0) px(0.0) else px(g.xMin)
        val y0 = if (yMin <= 0 && yMax >= 0) py(0.0) else py(yMin)
        out.add(DrawOp.Line(px(g.xMin), y0, px(g.xMax), y0, stroke, Ink.NOTE))
        out.add(DrawOp.Line(x0, py(yMin), x0, py(yMax), stroke, Ink.NOTE))
        // end-of-axis tick values
        fun fmt(v: Double): String {
            val r = Math.round(v * 100.0) / 100.0
            return if (r == Math.floor(r)) r.toLong().toString() else r.toString()
        }
        out.add(DrawOp.Glyphs(fmt(g.xMin), px(g.xMin), y0 + labelSize * 1.15f, labelSize * 0.8f, Face.BODY, Ink.FAINT))
        val xmaxText = fmt(g.xMax)
        out.add(DrawOp.Glyphs(xmaxText, px(g.xMax) - metrics.width(xmaxText, labelSize * 0.8f, Face.BODY), y0 + labelSize * 1.15f, labelSize * 0.8f, Face.BODY, Ink.FAINT))
        out.add(DrawOp.Glyphs(fmt(yMax), ox, py(yMax) + labelSize * 0.4f, labelSize * 0.8f, Face.BODY, Ink.FAINT))
        out.add(DrawOp.Glyphs(fmt(yMin), ox, py(yMin), labelSize * 0.8f, Face.BODY, Ink.FAINT))
        if (g.xLabel.isNotBlank()) out.add(DrawOp.Glyphs(g.xLabel, px(g.xMax) - metrics.width(g.xLabel, labelSize, Face.BODY), y0 - labelSize * 0.3f, labelSize, Face.BODY, Ink.NOTE))
        if (g.yLabel.isNotBlank()) out.add(DrawOp.Glyphs(g.yLabel, x0 + labelSize * 0.4f, py(yMax) + labelSize * 0.9f, labelSize, Face.BODY, Ink.NOTE))
        val colours = listOf(Ink.ACCENT, Ink.WARN, Ink.RESULT)
        series.forEachIndexed { idx, s ->
            var run = ArrayList<Float>()
            fun flush() {
                if (run.size >= 4) out.add(DrawOp.Poly(run.toFloatArray(), stroke * 1.3f, colours[idx % colours.size]))
                run = ArrayList()
            }
            var prev: Double? = null
            for ((x, y) in s) {
                if (y == null || abs(y) > 1e9 || y < yMin - (yMax - yMin) * 2 || y > yMax + (yMax - yMin) * 2) { flush(); prev = null; continue }
                // a jump across the window (an asymptote) breaks the curve instead of drawing a false vertical line
                if (prev != null && abs(y - prev) > (yMax - yMin) * 1.5) flush()
                run.add(px(x)); run.add(py(y.coerceIn(yMin, yMax)))
                prev = y
            }
            flush()
        }
        for ((x, y) in g.points) out.add(DrawOp.Oval(px(x), py(y), stroke * 1.8f, stroke * 1.8f, stroke, Ink.WARN, fill = true))
        return out
    }
}
