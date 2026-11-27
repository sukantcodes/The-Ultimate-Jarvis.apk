package com.jarvis.assistant.study

import com.google.gson.JsonObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * A diagram described as structured vector data in a 0..100 box (y grows downward) plus optional mathematical function graphs.
 * The layout engine orders primitives into natural live-drawing stages:
 *   1. Structural outlines
 *   2. Shaded colour fills
 *   3. Internal realistic details & directional arrows
 *   4. Anchor pins, leader callouts & collision-free external labels
 * Finally, a strict bounding-box pass guarantees every mark and label stays inside the whiteboard bounds.
 */
data class DiagramSpec(
    val title: String,
    val items: List<Item>,
    val graph: GraphSpec? = null
) {
    sealed class Item {
        abstract val ink: Ink
        abstract val layer: Int

        data class Line(
            val x1: Float, val y1: Float, val x2: Float, val y2: Float,
            val arrow: Boolean, val dashed: Boolean, override val ink: Ink,
            val doubleArrow: Boolean = false,
            val strokeScale: Float = 1f,
            override val layer: Int = 2
        ) : Item()

        data class Circle(
            val cx: Float, val cy: Float, val r: Float,
            val fill: Boolean, override val ink: Ink,
            val fillInk: Ink = ink,
            val dashed: Boolean = false,
            val shade: FillShade = FillShade.RADIAL,
            val fillAlpha: Float = 0.28f,
            val strokeScale: Float = 1f,
            override val layer: Int = 0
        ) : Item()

        data class Oval(
            val cx: Float, val cy: Float, val rx: Float, val ry: Float,
            val fill: Boolean, override val ink: Ink,
            val rotation: Float = 0f,
            val fillInk: Ink = ink,
            val dashed: Boolean = false,
            val shade: FillShade = FillShade.RADIAL,
            val fillAlpha: Float = 0.28f,
            val strokeScale: Float = 1f,
            override val layer: Int = 0
        ) : Item()

        data class Arc(
            val cx: Float, val cy: Float, val rx: Float, val ry: Float,
            val startAngle: Float, val sweepAngle: Float,
            val useCenter: Boolean, val fill: Boolean,
            override val ink: Ink,
            val fillInk: Ink = ink,
            val dashed: Boolean = false,
            val shade: FillShade = FillShade.RADIAL,
            val fillAlpha: Float = 0.26f,
            val strokeScale: Float = 1f,
            override val layer: Int = 0
        ) : Item()

        data class Rect(
            val x: Float, val y: Float, val w: Float, val h: Float,
            val fill: Boolean, override val ink: Ink,
            val rx: Float = 0f, val ry: Float = 0f,
            val fillInk: Ink = ink,
            val dashed: Boolean = false,
            val shade: FillShade = FillShade.LINEAR,
            val fillAlpha: Float = 0.24f,
            val strokeScale: Float = 1f,
            override val layer: Int = 0
        ) : Item()

        data class Poly(
            val pts: List<Float>, val closed: Boolean, override val ink: Ink,
            val smooth: Boolean = false,
            val fill: Boolean = false,
            val fillInk: Ink = ink,
            val dashed: Boolean = false,
            val arrow: Boolean = false,
            val shade: FillShade = FillShade.LINEAR,
            val fillAlpha: Float = 0.28f,
            val strokeScale: Float = 1f,
            override val layer: Int = 0
        ) : Item()

        data class Dot(
            val x: Float, val y: Float, val r: Float,
            override val ink: Ink,
            val fillInk: Ink = ink,
            val label: String = "",
            override val layer: Int = 2
        ) : Item()

        data class Label(
            val x: Float, val y: Float, val text: String, override val ink: Ink,
            val anchorX: Float? = null, val anchorY: Float? = null,
            val badge: Boolean = true,
            override val layer: Int = 3
        ) : Item()
    }

    data class GraphSpec(
        val exprs: List<String>,
        val xMin: Double, val xMax: Double,
        val yMin: Double?, val yMax: Double?,
        val points: List<Pair<Double, Double>>,
        val xLabel: String, val yLabel: String
    )

    companion object {
        private const val MAX_ITEMS = 320

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
            val graph = (o.get("graph")?.takeIf { it.isJsonObject }?.asJsonObject
                ?: o.takeIf { it.has("fn") || it.has("function") || it.has("points") })?.let { parseGraph(it) }
            if (items.isEmpty() && graph == null) return null
            return DiagramSpec(title, items, graph)
        }

        private fun JsonObject.str(k: String): String? =
            get(k)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }

        private fun JsonObject.num(vararg ks: String): Float? {
            for (k in ks) {
                val v = get(k)?.takeIf { it.isJsonPrimitive } ?: continue
                val d = if (v.asJsonPrimitive.isNumber) v.asDouble else v.asString.trim().toDoubleOrNull()
                if (d != null && d.isFinite()) return d.toFloat().coerceIn(-1000f, 1000f)
            }
            return null
        }

        private fun JsonObject.dbl(vararg ks: String): Double? = num(*ks)?.toDouble()

        private fun JsonObject.flag(k: String, default: Boolean = false): Boolean {
            val v = get(k)?.takeIf { it.isJsonPrimitive } ?: return default
            val s = v.asString.trim().lowercase()
            return when {
                s == "true" || s == "1" -> true
                s == "false" || s == "0" -> false
                else -> default
            }
        }

        private fun inkOf(name: String?): Ink = when (name?.lowercase()) {
            "accent", "blue", "cobalt", "navy" -> Ink.ACCENT
            "red", "warn", "crimson", "maroon" -> Ink.WARN
            "green", "result", "emerald", "lime" -> Ink.RESULT
            "gold", "amber", "yellow", "sun" -> Ink.GOLD
            "violet", "purple", "indigo", "lavender" -> Ink.VIOLET
            "teal", "cyan", "aqua", "turquoise" -> Ink.TEAL
            "rose", "pink", "magenta", "coral" -> Ink.ROSE
            "orange", "flame", "copper" -> Ink.ORANGE
            "brown", "wood", "earth", "sepia", "tan" -> Ink.BROWN
            "gray", "grey", "faint", "silver" -> Ink.FAINT
            "note", "slate", "muted" -> Ink.NOTE
            else -> Ink.INK
        }

        private fun shadeOf(o: JsonObject, default: FillShade): FillShade = when (o.str("shade")?.lowercase()) {
            "radial", "sphere", "glow" -> FillShade.RADIAL
            "linear", "grad", "gradient" -> FillShade.LINEAR
            "flat", "solid" -> FillShade.FLAT
            else -> default
        }

        private fun layerOf(o: JsonObject, default: Int): Int {
            o.num("layer")?.let { return it.toInt().coerceIn(0, 3) }
            return when (o.str("layer")?.lowercase()) {
                "outline", "structure", "base" -> 0
                "fill", "wash", "bg" -> 1
                "detail", "inner", "ray", "arrow", "annotation" -> 2
                "label", "callout" -> 3
                else -> default
            }
        }

        private fun parseItem(o: JsonObject): Item? {
            val ink = inkOf(o.str("color") ?: o.str("ink") ?: o.str("strokeColor"))
            val fillInk = inkOf(o.str("fillColor") ?: o.str("fill_color") ?: o.str("bg") ?: o.str("color") ?: o.str("ink"))
            val fillAlpha = (o.num("fillAlpha", "alpha") ?: 0.28f).coerceIn(0.08f, 0.78f)
            val strokeScale = (o.num("strokeWidth", "stroke", "weight") ?: 1f).coerceIn(0.45f, 3.0f)
            val kind = o.str("t")?.lowercase() ?: o.str("type")?.lowercase() ?: o.str("shape")?.lowercase() ?: return null

            return when (kind) {
                "line", "arrow", "vector", "ray", "segment" -> {
                    val x1 = o.num("x1") ?: return null
                    val y1 = o.num("y1") ?: return null
                    val x2 = o.num("x2") ?: return null
                    val y2 = o.num("y2") ?: return null
                    val isArrow = o.flag("arrow") || kind == "arrow" || kind == "vector" || kind == "ray"
                    Item.Line(
                        x1, y1, x2, y2,
                        arrow = isArrow,
                        dashed = o.flag("dashed"),
                        ink = ink,
                        doubleArrow = o.flag("doubleArrow") || o.flag("both"),
                        strokeScale = strokeScale,
                        layer = layerOf(o, if (isArrow || o.flag("dashed")) 2 else 1)
                    )
                }
                "circle" -> {
                    val fill = o.flag("fill") || o.str("fillColor") != null || o.str("fill_color") != null
                    Item.Circle(
                        cx = o.num("cx", "x") ?: return null,
                        cy = o.num("cy", "y") ?: return null,
                        r = (o.num("r") ?: return null).coerceAtLeast(0.5f),
                        fill = fill,
                        ink = ink,
                        fillInk = fillInk,
                        dashed = o.flag("dashed"),
                        shade = shadeOf(o, FillShade.RADIAL),
                        fillAlpha = fillAlpha,
                        strokeScale = strokeScale,
                        layer = layerOf(o, 0)
                    )
                }
                "oval", "ellipse" -> {
                    val fill = o.flag("fill") || o.str("fillColor") != null || o.str("fill_color") != null
                    Item.Oval(
                        cx = o.num("cx", "x") ?: return null,
                        cy = o.num("cy", "y") ?: return null,
                        rx = (o.num("rx") ?: return null).coerceAtLeast(0.5f),
                        ry = (o.num("ry") ?: return null).coerceAtLeast(0.5f),
                        fill = fill,
                        ink = ink,
                        rotation = o.num("rotate", "rotation", "angle") ?: 0f,
                        fillInk = fillInk,
                        dashed = o.flag("dashed"),
                        shade = shadeOf(o, FillShade.RADIAL),
                        fillAlpha = fillAlpha,
                        strokeScale = strokeScale,
                        layer = layerOf(o, 0)
                    )
                }
                "arc", "sector", "angle", "lens" -> {
                    val rx = (o.num("rx", "r") ?: return null).coerceAtLeast(0.5f)
                    val ry = (o.num("ry", "r") ?: rx).coerceAtLeast(0.5f)
                    val start = o.num("start", "startAngle") ?: 0f
                    val sweep = o.num("sweep", "sweepAngle") ?: 180f
                    val fill = o.flag("fill") || o.str("fillColor") != null || o.str("fill_color") != null
                    Item.Arc(
                        cx = o.num("cx", "x") ?: return null,
                        cy = o.num("cy", "y") ?: return null,
                        rx = rx,
                        ry = ry,
                        startAngle = start,
                        sweepAngle = sweep,
                        useCenter = o.flag("sector") || kind == "sector" || o.flag("useCenter"),
                        fill = fill,
                        ink = ink,
                        fillInk = fillInk,
                        dashed = o.flag("dashed"),
                        shade = shadeOf(o, FillShade.RADIAL),
                        fillAlpha = fillAlpha,
                        strokeScale = strokeScale,
                        layer = layerOf(o, if (kind == "angle") 2 else 0)
                    )
                }
                "rect", "box", "block", "roundrect", "pill", "node", "card" -> {
                    val w = o.num("w", "width") ?: return null
                    val h = o.num("h", "height") ?: return null
                    val defaultR = if (kind == "roundrect" || kind == "pill" || kind == "node" || kind == "card") min(w, h) * 0.22f else 0f
                    val rx = (o.num("rx", "radius", "r") ?: defaultR).coerceAtLeast(0f)
                    val ry = (o.num("ry", "radius", "r") ?: rx).coerceAtLeast(0f)
                    val fill = o.flag("fill") || o.str("fillColor") != null || o.str("fill_color") != null || kind == "pill" || kind == "card"
                    Item.Rect(
                        x = o.num("x") ?: return null,
                        y = o.num("y") ?: return null,
                        w = w,
                        h = h,
                        fill = fill,
                        ink = ink,
                        rx = rx,
                        ry = ry,
                        fillInk = fillInk,
                        dashed = o.flag("dashed"),
                        shade = shadeOf(o, FillShade.LINEAR),
                        fillAlpha = fillAlpha,
                        strokeScale = strokeScale,
                        layer = layerOf(o, 0)
                    )
                }
                "poly", "polygon", "polyline", "triangle", "path", "curve", "spline", "bezier", "wave" -> {
                    val pa = o.get("pts")?.takeIf { it.isJsonArray }?.asJsonArray
                        ?: o.get("points")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
                    val flat = ArrayList<Float>()
                    for (p in pa) {
                        if (p.isJsonArray && p.asJsonArray.size() >= 2) {
                            flat.add(p.asJsonArray[0].asFloat)
                            flat.add(p.asJsonArray[1].asFloat)
                        } else if (p.isJsonPrimitive && p.asJsonPrimitive.isNumber) {
                            flat.add(p.asFloat)
                        }
                    }
                    if (flat.size < 4 || flat.size % 2 != 0) return null
                    val isSmoothKind = kind == "curve" || kind == "spline" || kind == "bezier" || kind == "wave"
                    val isClosed = o.flag("closed", default = kind != "polyline" && kind != "path" && !isSmoothKind)
                    val fill = (o.flag("fill") || o.str("fillColor") != null || o.str("fill_color") != null) && isClosed
                    Item.Poly(
                        pts = flat,
                        closed = isClosed,
                        ink = ink,
                        smooth = o.flag("smooth", default = isSmoothKind),
                        fill = fill,
                        fillInk = fillInk,
                        dashed = o.flag("dashed"),
                        arrow = o.flag("arrow"),
                        shade = shadeOf(o, FillShade.LINEAR),
                        fillAlpha = fillAlpha,
                        strokeScale = strokeScale,
                        layer = layerOf(o, if (isClosed) 0 else 2)
                    )
                }
                "dot", "point", "pin" -> {
                    Item.Dot(
                        x = o.num("x", "cx") ?: return null,
                        y = o.num("y", "cy") ?: return null,
                        r = (o.num("r") ?: 1.4f).coerceIn(0.6f, 6f),
                        ink = ink,
                        fillInk = fillInk,
                        label = o.str("text") ?: o.str("label").orEmpty(),
                        layer = layerOf(o, 2)
                    )
                }
                "label", "callout", "text" -> {
                    val text = o.str("text") ?: o.str("content") ?: o.str("label") ?: return null
                    val ax = o.num("ax", "tx", "pinX")
                    val ay = o.num("ay", "ty", "pinY")
                    Item.Label(
                        x = o.num("x") ?: return null,
                        y = o.num("y") ?: return null,
                        text = text.take(64),
                        ink = ink,
                        anchorX = ax,
                        anchorY = ay,
                        badge = o.flag("badge", default = ax != null && ay != null),
                        layer = 3
                    )
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
            return GraphSpec(
                exprs = exprs.filter { it.isNotEmpty() }.take(4),
                xMin = xMin, xMax = xMax,
                yMin = o.dbl("ymin", "y_min"), yMax = o.dbl("ymax", "y_max"),
                points = pts,
                xLabel = o.str("xlabel").orEmpty(),
                yLabel = o.str("ylabel").orEmpty()
            )
        }
    }
}

object StudyDiagramLayout {

    class Result(val ops: List<DrawOp>, val width: Float, val height: Float)

    private data class LabelRect(val x: Float, val y: Float, val w: Float, val h: Float)

    private data class PreparedLabel(
        val spec: DiagramSpec.Item.Label,
        val lines: List<String>,
        val boxW: Float,
        val boxH: Float,
        val lineH: Float,
        val textSize: Float,
        val targetSpecX: Float,
        val targetSpecY: Float
    )

    /**
     * Lays out a rich layered diagram inside [w] × [maxH], ordering construction chronologically from outer structure ->
     * live shaded colour washes -> interior realistic details -> non-colliding aligned external callouts & labels, and
     * strictly clamping all geometry and text inside [0..w] so nothing can ever overflow or overlap.
     */
    fun layout(spec: DiagramSpec, w: Float, maxH: Float, metrics: TextMetrics, stroke: Float): Result {
        val rawOps = ArrayList<DrawOp>()
        val size = min(w, maxH)
        val labelSize = (size * 0.052f).coerceIn(13f, 23f)
        val g = spec.graph
        var h = 0f

        if (spec.title.isNotBlank()) {
            val titleSize = (labelSize * 1.18f).coerceAtMost(28f)
            val titleTw = metrics.width(spec.title, titleSize, Face.BODY_BOLD)
            val titleScale = if (titleTw > w - 28f) ((w - 28f) / titleTw).coerceAtLeast(0.5f) else 1f
            val ts = titleSize * titleScale
            val accentDotR = ts * 0.22f
            rawOps.add(DrawOp.Oval(accentDotR + 6f, ts * 0.72f, accentDotR, accentDotR, 1f, Ink.ACCENT, fill = true))
            rawOps.add(DrawOp.Glyphs(spec.title, accentDotR * 2f + 16f, ts, ts, Face.BODY_BOLD, Ink.ACCENT))
            rawOps.add(DrawOp.Line(4f, ts * 1.38f, min(w - 4f, metrics.width(spec.title, ts, Face.BODY_BOLD) + 38f), ts * 1.38f, max(1.5f, stroke * 0.85f), Ink.FAINT))
            h = ts * 1.85f
        }

        val itemsStartY = h
        if (spec.items.isNotEmpty()) {
            val availBoxH = if (g != null) maxH * 0.54f else (maxH - h).coerceAtLeast(w * 0.45f)
            val shapes = spec.items.filter { it !is DiagramSpec.Item.Label && it !is DiagramSpec.Item.Dot }
            val dots = spec.items.filterIsInstance<DiagramSpec.Item.Dot>()
            val labels = spec.items.filterIsInstance<DiagramSpec.Item.Label>()

            val (geomMinX, geomMinY, geomMaxX, geomMaxY) = geomBounds(spec.items)
            val geomW = (geomMaxX - geomMinX).coerceAtLeast(24f)
            val geomH = (geomMaxY - geomMinY).coerceAtLeast(24f)
            val geomMidX = (geomMinX + geomMaxX) * 0.5f

            val useRailLabels = labels.any { it.anchorX != null && it.anchorY != null } ||
                (labels.size >= 3 && labels.any { it.text.length >= 4 })

            val leftPrepared = ArrayList<PreparedLabel>()
            val rightPrepared = ArrayList<PreparedLabel>()
            val inlineLabels = ArrayList<DiagramSpec.Item.Label>()

            val maxRailTextW = (w * 0.22f).coerceAtLeast(70f)
            if (useRailLabels) {
                for (lbl in labels) {
                    val tx = lbl.anchorX ?: lbl.x
                    val ty = lbl.anchorY ?: lbl.y
                    val prep = prepareRailLabel(lbl, labelSize * 0.88f, maxRailTextW, metrics, tx, ty)
                    val goLeft = if (lbl.anchorX != null && lbl.x != tx) lbl.x < geomMidX else tx < geomMidX
                    if (goLeft) leftPrepared.add(prep) else rightPrepared.add(prep)
                }
                // If all labels clustered onto one side with >4 items, balance items near midline only when helpful
                if (leftPrepared.isEmpty() && rightPrepared.size >= 6) {
                    val byX = rightPrepared.sortedBy { it.targetSpecX }
                    val moveCount = rightPrepared.size / 2
                    leftPrepared.addAll(byX.take(moveCount))
                    rightPrepared.clear()
                    rightPrepared.addAll(byX.drop(moveCount))
                } else if (rightPrepared.isEmpty() && leftPrepared.size >= 6) {
                    val byX = leftPrepared.sortedByDescending { it.targetSpecX }
                    val moveCount = leftPrepared.size / 2
                    rightPrepared.addAll(byX.take(moveCount))
                    leftPrepared.clear()
                    leftPrepared.addAll(byX.drop(moveCount))
                }
                leftPrepared.sortBy { it.targetSpecY }
                rightPrepared.sortBy { it.targetSpecY }
            } else {
                inlineLabels.addAll(labels)
            }

            val leftColMaxW = leftPrepared.maxOfOrNull { it.boxW } ?: 0f
            val rightColMaxW = rightPrepared.maxOfOrNull { it.boxW } ?: 0f
            val gutterGap = 20f
            val artLeft = if (leftPrepared.isEmpty()) 14f else (leftColMaxW + 8f + gutterGap).coerceAtMost(w * 0.31f)
            val artRight = if (rightPrepared.isEmpty()) w - 14f else (w - rightColMaxW - 8f - gutterGap).coerceAtLeast(w * 0.69f)
            val artW = (artRight - artLeft).coerceAtLeast(w * 0.38f)

            val minGap = max(8f, labelSize * 0.42f)
            val neededLeftH = if (leftPrepared.isEmpty()) 0f else leftPrepared.sumOf { it.boxH.toDouble() }.toFloat() + (leftPrepared.size - 1) * minGap + 20f
            val neededRightH = if (rightPrepared.isEmpty()) 0f else rightPrepared.sumOf { it.boxH.toDouble() }.toFloat() + (rightPrepared.size - 1) * minGap + 20f
            val stageH = max(availBoxH, max(neededLeftH, neededRightH)).coerceAtMost(maxH - itemsStartY)
            val artAvailH = (stageH - 20f).coerceAtLeast(80f)

            val sc = min((artW - 16f) / geomW, artAvailH / geomH)
            val drawnW = geomW * sc
            val drawnH = geomH * sc
            val offX = artLeft + (artW - drawnW) * 0.5f - geomMinX * sc
            val offY = itemsStartY + (stageH - drawnH) * 0.5f - geomMinY * sc

            // Subtle studio diagram stage backdrop spanning artwork + rails
            rawOps.add(
                DrawOp.Rect(
                    2f, itemsStartY, w - 4f, stageH,
                    stroke = max(1f, stroke * 0.6f),
                    ink = Ink.FAINT,
                    fill = true,
                    rx = 16f, ry = 16f,
                    fillInk = Ink.FAINT,
                    shade = FillShade.LINEAR,
                    paintMode = PaintMode.FILL_AND_STROKE,
                    fillAlpha = 0.06f
                )
            )

            // Stage 1: Primary structural outlines (stroke-only so the viewer sees clean linework first)
            val primaryOutlines = shapes.filter { it.layer <= 1 }
            for (item in primaryOutlines) {
                rawOps.addAll(shapePass(item, sc, offX, offY, stroke, Pass.OUTLINE))
            }

            // Stage 2: Live colour washes & gradient shading across enclosed shapes
            val filledShapes = shapes.filter { isFilled(it) }
            for (item in filledShapes) {
                rawOps.addAll(shapePass(item, sc, offX, offY, stroke, Pass.FILL))
            }

            // Stage 3: Realistic interior details, secondary strokes, specular highlights, vectors & marker dots
            val detailShapes = shapes.filter { it.layer >= 2 }
            for (item in detailShapes) {
                rawOps.addAll(shapePass(item, sc, offX, offY, stroke, Pass.OUTLINE))
            }
            for (item in filledShapes) {
                specularHighlight(item, sc, offX, offY, stroke)?.let { rawOps.add(it) }
            }
            for (dot in dots) {
                val dx = offX + dot.x * sc
                val dy = offY + dot.y * sc
                val r = max(3.2f, dot.r * sc)
                rawOps.add(DrawOp.Oval(dx, dy, r, r, stroke * 0.9f, dot.ink, fill = true, fillInk = dot.fillInk, shade = FillShade.RADIAL, fillAlpha = 0.85f))
                if (dot.label.isNotBlank()) {
                    inlineLabels.add(DiagramSpec.Item.Label(dot.x + 4f, dot.y - 4f, dot.label, dot.ink, dot.x, dot.y, badge = true))
                }
            }

            // Stage 4: Aligned Two-Rail Non-Overlapping Callouts + Inline Labels
            val topBound = itemsStartY + 8f
            val bottomBound = itemsStartY + stageH - 8f
            if (leftPrepared.isNotEmpty()) {
                val flushRightEdge = (artLeft - gutterGap).coerceAtLeast(leftColMaxW + 6f)
                layoutRailColumn(
                    items = leftPrepared, isLeft = true, edgeX = flushRightEdge, minEdge = 4f, maxEdge = w - 4f,
                    sc = sc, ox = offX, oy = offY, topBound = topBound, bottomBound = bottomBound,
                    minGap = minGap, stroke = stroke, metrics = metrics, out = rawOps
                )
            }
            if (rightPrepared.isNotEmpty()) {
                val flushLeftEdge = (artRight + gutterGap).coerceAtMost(w - rightColMaxW - 6f)
                layoutRailColumn(
                    items = rightPrepared, isLeft = false, edgeX = flushLeftEdge, minEdge = 4f, maxEdge = w - 4f,
                    sc = sc, ox = offX, oy = offY, topBound = topBound, bottomBound = bottomBound,
                    minGap = minGap, stroke = stroke, metrics = metrics, out = rawOps
                )
            }
            if (inlineLabels.isNotEmpty()) {
                val placed = ArrayList<LabelRect>()
                for (lbl in inlineLabels) {
                    rawOps.addAll(
                        layoutInlineLabel(
                            lbl, sc, offX, offY, w, topBound, bottomBound, labelSize * 0.92f, stroke, metrics, placed
                        )
                    )
                }
            }

            h = itemsStartY + stageH + labelSize * 0.45f
        }

        if (g != null) {
            val gh = min((maxH - h).coerceAtLeast(w * 0.42f), w * 0.62f)
            rawOps.addAll(graph(g, 2f, h, w - 4f, gh, labelSize, stroke, metrics))
            h += gh + labelSize * 0.6f
        }

        val finalOps = normalizeIntoBox(rawOps, w, maxH, metrics)
        val measuredH = (DrawBounds.measure(finalOps, metrics)?.maxY ?: h).coerceAtLeast(h).coerceAtMost(maxH)
        return Result(finalOps, w, measuredH)
    }

    private enum class Pass { OUTLINE, FILL }

    private fun isFilled(item: DiagramSpec.Item): Boolean = when (item) {
        is DiagramSpec.Item.Circle -> item.fill
        is DiagramSpec.Item.Oval -> item.fill
        is DiagramSpec.Item.Arc -> item.fill
        is DiagramSpec.Item.Rect -> item.fill
        is DiagramSpec.Item.Poly -> item.fill && item.closed
        is DiagramSpec.Item.Line, is DiagramSpec.Item.Dot, is DiagramSpec.Item.Label -> false
    }

    private data class SpecBox(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float)

    private fun geomBounds(items: List<DiagramSpec.Item>): SpecBox {
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        fun inc(x: Float, y: Float) {
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
        }
        for (it in items) {
            if (it is DiagramSpec.Item.Label) {
                it.anchorX?.let { ax -> it.anchorY?.let { ay -> inc(ax, ay) } }
                continue
            }
            when (it) {
                is DiagramSpec.Item.Line -> { inc(it.x1, it.y1); inc(it.x2, it.y2) }
                is DiagramSpec.Item.Circle -> { inc(it.cx - it.r, it.cy - it.r); inc(it.cx + it.r, it.cy + it.r) }
                is DiagramSpec.Item.Oval -> { inc(it.cx - it.rx, it.cy - it.ry); inc(it.cx + it.rx, it.cy + it.ry) }
                is DiagramSpec.Item.Arc -> { inc(it.cx - it.rx, it.cy - it.ry); inc(it.cx + it.rx, it.cy + it.ry) }
                is DiagramSpec.Item.Rect -> { inc(it.x, it.y); inc(it.x + it.w, it.y + it.h) }
                is DiagramSpec.Item.Poly -> {
                    var i = 0
                    while (i + 1 < it.pts.size) { inc(it.pts[i], it.pts[i + 1]); i += 2 }
                }
                is DiagramSpec.Item.Dot -> inc(it.x, it.y)
                is DiagramSpec.Item.Label -> Unit
            }
        }
        if (!minX.isFinite()) return SpecBox(0f, 0f, 100f, 100f)
        // Expand slight margin if tiny
        if (maxX - minX < 20f) { minX -= 10f; maxX += 10f }
        if (maxY - minY < 20f) { minY -= 10f; maxY += 10f }
        return SpecBox(minX, minY, maxX, maxY)
    }

    private fun wrapLabelText(text: String, size: Float, maxW: Float, metrics: TextMetrics): List<String> {
        if (metrics.width(text, size, Face.BODY_BOLD) <= maxW) return listOf(text)
        val words = text.trim().split(Regex("\\s+"))
        if (words.size <= 1) return listOf(text)
        val mid = (words.size + 1) / 2
        val l1 = words.take(mid).joinToString(" ")
        val l2 = words.drop(mid).joinToString(" ")
        return listOf(l1, l2).filter { it.isNotEmpty() }
    }

    private fun prepareRailLabel(
        lbl: DiagramSpec.Item.Label,
        baseSize: Float,
        maxTextW: Float,
        metrics: TextMetrics,
        tx: Float,
        ty: Float
    ): PreparedLabel {
        var textSize = baseSize
        var lines = wrapLabelText(lbl.text, textSize, maxTextW, metrics)
        val widest = lines.maxOf { metrics.width(it, textSize, Face.BODY_BOLD) }
        if (widest > maxTextW && maxTextW > 30f) {
            textSize = (textSize * (maxTextW / widest)).coerceAtLeast(baseSize * 0.72f)
            lines = wrapLabelText(lbl.text, textSize, maxTextW, metrics)
        }
        val lineH = textSize * 1.24f
        val padX = 9f
        val padY = 5f
        val boxW = lines.maxOf { metrics.width(it, textSize, Face.BODY_BOLD) } + padX * 2f
        val boxH = lines.size * lineH + padY * 2f
        return PreparedLabel(lbl, lines, boxW, boxH, lineH, textSize, tx, ty)
    }

    /**
     * Distributes sorted vertical slots cleanly within [topBound..bottomBound] with guaranteed [minGap] spacing so
     * NO two labels can overlap or collapse on one another.
     */
    private fun solveVerticalSlots(items: List<PreparedLabel>, sc: Float, oy: Float, topBound: Float, bottomBound: Float, minGap: Float): FloatArray {
        val n = items.size
        val ys = FloatArray(n)
        if (n == 0) return ys
        val avail = (bottomBound - topBound).coerceAtLeast(1f)
        val totalNeeded = items.sumOf { it.boxH.toDouble() }.toFloat() + (n - 1) * minGap
        val effectiveGap = if (totalNeeded <= avail) minGap else ((avail - items.sumOf { it.boxH.toDouble() }.toFloat()) / max(1, n - 1)).coerceAtLeast(2f)

        // Start near each anatomical anchor Y, centered vertically
        for (i in 0 until n) {
            val anchorY = oy + items[i].targetSpecY * sc
            ys[i] = (anchorY - items[i].boxH * 0.5f).coerceIn(topBound, max(topBound, bottomBound - items[i].boxH))
        }
        // Forward sweep: strictly preserve non-overlapping order
        for (i in 1 until n) {
            val minY = ys[i - 1] + items[i - 1].boxH + effectiveGap
            if (ys[i] < minY) ys[i] = minY
        }
        // Backward sweep: pull cleanly inside bottomBound without squashing gaps
        val lastLimit = bottomBound - items[n - 1].boxH
        if (ys[n - 1] > lastLimit) ys[n - 1] = lastLimit
        for (i in n - 2 downTo 0) {
            val maxY = ys[i + 1] - items[i].boxH - effectiveGap
            if (ys[i] > maxY) ys[i] = maxY
        }
        // Clamp top guard
        if (ys[0] < topBound) {
            ys[0] = topBound
            for (i in 1 until n) {
                val minY = ys[i - 1] + items[i - 1].boxH + effectiveGap
                if (ys[i] < minY) ys[i] = minY
            }
        }
        return ys
    }

    private fun layoutRailColumn(
        items: List<PreparedLabel>,
        isLeft: Boolean,
        edgeX: Float,
        minEdge: Float,
        maxEdge: Float,
        sc: Float,
        ox: Float,
        oy: Float,
        topBound: Float,
        bottomBound: Float,
        minGap: Float,
        stroke: Float,
        metrics: TextMetrics,
        out: MutableList<DrawOp>
    ) {
        val ys = solveVerticalSlots(items, sc, oy, topBound, bottomBound, minGap)
        items.forEachIndexed { idx, p ->
            val by = ys[idx]
            val bx = if (isLeft) {
                (edgeX - p.boxW).coerceIn(minEdge, max(minEdge, maxEdge - p.boxW))
            } else {
                edgeX.coerceIn(minEdge, max(minEdge, maxEdge - p.boxW))
            }
            val labelInk = if (p.spec.ink == Ink.INK) Ink.NOTE else p.spec.ink
            val ax = ox + p.targetSpecX * sc
            val ay = oy + p.targetSpecY * sc
            val connectX = if (isLeft) bx + p.boxW else bx
            val connectY = by + p.boxH * 0.5f

            // Precision target pin dot with clean outline + angled leader + flush horizontal rail tick
            val dotR = max(3.0f, stroke * 1.35f)
            out.add(DrawOp.Oval(ax, ay, dotR, dotR, max(1f, stroke * 0.7f), labelInk, fill = true, fillInk = labelInk, shade = FillShade.FLAT, paintMode = PaintMode.FILL_AND_STROKE, fillAlpha = 0.90f))
            val tickLen = 10f
            val kneeX = if (isLeft) max(connectX + tickLen, ax + (connectX - ax) * 0.45f) else min(connectX - tickLen, ax + (connectX - ax) * 0.45f)
            if (isLeft && kneeX < ax && kneeX > connectX + 4f || !isLeft && kneeX > ax && kneeX < connectX - 4f) {
                out.add(DrawOp.Line(ax, ay, kneeX, connectY, max(1.1f, stroke * 0.62f), Ink.NOTE))
                out.add(DrawOp.Line(kneeX, connectY, connectX, connectY, max(1.1f, stroke * 0.62f), Ink.NOTE))
            } else {
                out.add(DrawOp.Line(ax, ay, connectX, connectY, max(1.1f, stroke * 0.62f), Ink.NOTE))
            }

            // Rounded callout badge + sharp label lines
            out.add(
                DrawOp.Rect(
                    bx, by, p.boxW, p.boxH,
                    stroke = max(1f, stroke * 0.55f),
                    ink = labelInk,
                    fill = true,
                    rx = 10f, ry = 10f,
                    fillInk = labelInk,
                    shade = FillShade.FLAT,
                    paintMode = PaintMode.FILL_AND_STROKE,
                    fillAlpha = 0.12f
                )
            )
            var ly = by + 5f + metrics.ascent(p.textSize, Face.BODY_BOLD)
            for (line in p.lines) {
                val tw = metrics.width(line, p.textSize, Face.BODY_BOLD)
                val lx = if (isLeft) (bx + p.boxW - 9f - tw) else (bx + 9f)
                out.add(DrawOp.Glyphs(line, lx, ly, p.textSize, Face.BODY_BOLD, labelInk))
                ly += p.lineH
            }
        }
    }

    private fun layoutInlineLabel(
        lbl: DiagramSpec.Item.Label,
        sc: Float,
        ox: Float,
        oy: Float,
        w: Float,
        topBound: Float,
        bottomBound: Float,
        labelSize: Float,
        stroke: Float,
        metrics: TextMetrics,
        placed: MutableList<LabelRect>
    ): List<DrawOp> {
        val out = ArrayList<DrawOp>()
        val tw = metrics.width(lbl.text, labelSize, Face.BODY_BOLD)
        val padX = 8f
        val padY = 4f
        val boxW = tw + padX * 2f
        val boxH = labelSize * 1.38f + padY * 2f
        val rawCx = ox + lbl.x * sc
        val rawCy = oy + lbl.y * sc
        var bx = (rawCx - boxW / 2f).coerceIn(6f, max(6f, w - boxW - 6f))
        var by = (rawCy - boxH / 2f).coerceIn(topBound, max(topBound, bottomBound - boxH))

        for (attempt in 0 until 12) {
            val hit = placed.firstOrNull { r ->
                bx < r.x + r.w + 6f && bx + boxW + 6f > r.x && by < r.y + r.h + 6f && by + boxH + 6f > r.y
            } ?: break
            val down = hit.y + hit.h + 6f
            val up = hit.y - boxH - 6f
            by = when {
                down + boxH <= bottomBound -> down
                up >= topBound -> up
                else -> (by + boxH + 4f).coerceIn(topBound, max(topBound, bottomBound - boxH))
            }
        }
        placed.add(LabelRect(bx, by, boxW, boxH))

        val labelInk = if (lbl.ink == Ink.INK) Ink.NOTE else lbl.ink
        val baseline = by + padY + metrics.ascent(labelSize, Face.BODY_BOLD)
        if (lbl.badge) {
            out.add(
                DrawOp.Rect(
                    bx, by, boxW, boxH,
                    stroke = max(1f, stroke * 0.55f),
                    ink = labelInk,
                    fill = true,
                    rx = boxH * 0.42f, ry = boxH * 0.42f,
                    fillInk = labelInk,
                    shade = FillShade.FLAT,
                    paintMode = PaintMode.FILL_AND_STROKE,
                    fillAlpha = 0.13f
                )
            )
        }
        out.add(DrawOp.Glyphs(lbl.text, bx + padX, baseline, labelSize, Face.BODY_BOLD, labelInk))
        return out
    }

    private fun shapePass(item: DiagramSpec.Item, sc: Float, ox: Float, oy: Float, stroke: Float, pass: Pass): List<DrawOp> {
        fun X(v: Float) = ox + v * sc
        fun Y(v: Float) = oy + v * sc
        val mode = if (pass == Pass.FILL) PaintMode.FILL_ONLY else PaintMode.STROKE_ONLY
        return when (item) {
            is DiagramSpec.Item.Line -> {
                if (pass == Pass.FILL) return emptyList()
                val sw = stroke * item.strokeScale
                val out = ArrayList<DrawOp>()
                out.add(DrawOp.Line(X(item.x1), Y(item.y1), X(item.x2), Y(item.y2), sw, item.ink, item.dashed))
                if (item.arrow) addArrowHead(out, X(item.x1), Y(item.y1), X(item.x2), Y(item.y2), sw, item.ink)
                if (item.doubleArrow) addArrowHead(out, X(item.x2), Y(item.y2), X(item.x1), Y(item.y1), sw, item.ink)
                out
            }
            is DiagramSpec.Item.Circle -> {
                val sw = stroke * item.strokeScale * if (item.layer == 0) 1.12f else 1f
                listOf(
                    DrawOp.Oval(
                        X(item.cx), Y(item.cy), item.r * sc, item.r * sc, sw,
                        item.ink, fill = item.fill, dashed = item.dashed, rotation = 0f,
                        fillInk = item.fillInk, shade = item.shade, paintMode = mode, fillAlpha = item.fillAlpha
                    )
                )
            }
            is DiagramSpec.Item.Oval -> {
                val sw = stroke * item.strokeScale * if (item.layer == 0) 1.12f else 1f
                listOf(
                    DrawOp.Oval(
                        X(item.cx), Y(item.cy), item.rx * sc, item.ry * sc, sw,
                        item.ink, fill = item.fill, dashed = item.dashed, rotation = item.rotation,
                        fillInk = item.fillInk, shade = item.shade, paintMode = mode, fillAlpha = item.fillAlpha
                    )
                )
            }
            is DiagramSpec.Item.Arc -> {
                val sw = stroke * item.strokeScale
                listOf(
                    DrawOp.Arc(
                        X(item.cx), Y(item.cy), item.rx * sc, item.ry * sc,
                        item.startAngle, item.sweepAngle, sw,
                        item.ink, fill = item.fill, useCenter = item.useCenter, dashed = item.dashed,
                        fillInk = item.fillInk, shade = item.shade, paintMode = mode, fillAlpha = item.fillAlpha
                    )
                )
            }
            is DiagramSpec.Item.Rect -> {
                val sw = stroke * item.strokeScale * if (item.layer == 0) 1.12f else 1f
                listOf(
                    DrawOp.Rect(
                        X(item.x), Y(item.y), item.w * sc, item.h * sc, sw,
                        item.ink, fill = item.fill, rx = item.rx * sc, ry = item.ry * sc,
                        dashed = item.dashed, fillInk = item.fillInk, shade = item.shade,
                        paintMode = mode, fillAlpha = item.fillAlpha
                    )
                )
            }
            is DiagramSpec.Item.Poly -> {
                val p = FloatArray(item.pts.size)
                for (i in p.indices step 2) { p[i] = X(item.pts[i]); p[i + 1] = Y(item.pts[i + 1]) }
                val sw = stroke * item.strokeScale * if (item.layer == 0) 1.12f else 1f
                val out = ArrayList<DrawOp>()
                out.add(
                    DrawOp.Poly(
                        p, sw, item.ink, closed = item.closed, fill = item.fill, dashed = item.dashed,
                        smooth = item.smooth, fillInk = item.fillInk, shade = item.shade,
                        paintMode = mode, fillAlpha = item.fillAlpha
                    )
                )
                if (pass == Pass.OUTLINE && item.arrow && p.size >= 4) {
                    val n = p.size
                    addArrowHead(out, p[n - 4], p[n - 3], p[n - 2], p[n - 1], sw, item.ink)
                }
                out
            }
            is DiagramSpec.Item.Dot, is DiagramSpec.Item.Label -> emptyList()
        }
    }

    private fun specularHighlight(item: DiagramSpec.Item, sc: Float, ox: Float, oy: Float, stroke: Float): DrawOp? {
        if (item.layer > 1) return null
        return when (item) {
            is DiagramSpec.Item.Circle -> if (item.fill && item.shade == FillShade.RADIAL && item.r * sc >= 22f) {
                DrawOp.Arc(
                    ox + item.cx * sc, oy + item.cy * sc,
                    item.r * sc * 0.72f, item.r * sc * 0.72f,
                    -145f, 48f, max(1.2f, stroke * 0.65f), Ink.FAINT,
                    fill = false, paintMode = PaintMode.STROKE_ONLY
                )
            } else null
            is DiagramSpec.Item.Oval -> if (item.fill && item.shade == FillShade.RADIAL && abs(item.rotation) < 1f && min(item.rx, item.ry) * sc >= 22f) {
                DrawOp.Arc(
                    ox + item.cx * sc, oy + item.cy * sc,
                    item.rx * sc * 0.72f, item.ry * sc * 0.72f,
                    -145f, 45f, max(1.2f, stroke * 0.65f), Ink.FAINT,
                    fill = false, paintMode = PaintMode.STROKE_ONLY
                )
            } else null
            else -> null
        }
    }

    private fun addArrowHead(out: MutableList<DrawOp>, x1: Float, y1: Float, x2: Float, y2: Float, stroke: Float, ink: Ink) {
        if (hypot(x2 - x1, y2 - y1) < 2f) return
        val a = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
        val len = max(8f, stroke * 4.4f)
        for (d in listOf(2.65, -2.65)) {
            out.add(DrawOp.Line(x2, y2, x2 + (len * cos(a + d)).toFloat(), y2 + (len * sin(a + d)).toFloat(), stroke, ink))
        }
    }

    /** Guarantees 100% of every mark & label lies strictly inside [2..w-2] horizontally and <= maxH vertically. */
    private fun normalizeIntoBox(ops: List<DrawOp>, w: Float, maxH: Float, metrics: TextMetrics): List<DrawOp> {
        val box = DrawBounds.measure(ops, metrics) ?: return ops
        val usableW = (w - 8f).coerceAtLeast(40f)
        val usableH = (maxH - 8f).coerceAtLeast(40f)
        val needScaleX = if (box.width > usableW) usableW / box.width else 1f
        val needScaleY = if (box.maxY > usableH) usableH / box.maxY else 1f
        val s = min(1f, min(needScaleX, needScaleY))
        var out = ops
        if (s < 0.999f) {
            out = out.map { it.scaled(box.minX, box.minY, s) }
        }
        val b2 = DrawBounds.measure(out, metrics) ?: return out
        var dx = 0f
        if (b2.minX < 2f) dx = 2f - b2.minX
        else if (b2.maxX > w - 2f) dx = (w - 2f) - b2.maxX
        val dy = if (b2.minY < 2f) 2f - b2.minY else 0f
        if (abs(dx) > 0.2f || abs(dy) > 0.2f) {
            out = out.map { it.moved(dx, dy) }
        }
        return out
    }

    private fun niceStep(span: Double, targetTicks: Int): Double {
        if (span <= 0.0) return 1.0
        val raw = span / max(1, targetTicks)
        val pow10 = 10.0.pow(floor(log10(raw)))
        val norm = raw / pow10
        val mult = when {
            norm < 1.5 -> 1.0
            norm < 3.5 -> 2.0
            norm < 7.5 -> 5.0
            else -> 10.0
        }
        return mult * pow10
    }

    private fun graph(
        g: DiagramSpec.GraphSpec,
        ox: Float, oy: Float, w: Float, h: Float,
        labelSize: Float, stroke: Float, metrics: TextMetrics
    ): List<DrawOp> {
        val out = ArrayList<DrawOp>()
        val padL = labelSize * 2.2f
        val padR = labelSize * 1.1f
        val padT = labelSize * 1.2f
        val padB = labelSize * 1.8f
        val pw = (w - padL - padR).coerceAtLeast(40f)
        val ph = (h - padT - padB).coerceAtLeast(40f)

        val series = ArrayList<List<Pair<Double, Double?>>>()
        val n = 220
        for (expr in g.exprs) {
            val s = ArrayList<Pair<Double, Double?>>(n + 1)
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
        fun py(y: Double) = oy + padT + (ph - ((y - yMin) / (yMax - yMin) * ph)).toFloat()
        fun fmt(v: Double): String {
            val r = Math.round(v * 100.0) / 100.0
            return if (abs(r) < 1e-9) "0" else if (r == Math.floor(r)) r.toLong().toString() else r.toString()
        }

        // Plot frame & coordinate grid
        out.add(DrawOp.Rect(ox, oy, w, h, max(1f, stroke * 0.65f), Ink.FAINT, fill = true, rx = 14f, ry = 14f, fillInk = Ink.FAINT, shade = FillShade.FLAT, fillAlpha = 0.06f))

        val gxStep = niceStep(g.xMax - g.xMin, 5)
        var gx = ceil(g.xMin / gxStep) * gxStep
        val tickSize = (labelSize * 0.74f).coerceIn(10f, 18f)
        val x0 = if (g.xMin <= 0 && g.xMax >= 0) px(0.0) else px(g.xMin)
        val y0 = if (yMin <= 0 && yMax >= 0) py(0.0) else py(yMin)

        while (gx <= g.xMax + gxStep * 0.001) {
            val xx = px(gx)
            out.add(DrawOp.Line(xx, oy + padT, xx, oy + padT + ph, max(0.8f, stroke * 0.45f), Ink.FAINT))
            val lbl = fmt(gx)
            val tw = metrics.width(lbl, tickSize, Face.BODY)
            if (abs(xx - x0) > tickSize * 0.8f || g.xMin > 0 || g.xMax < 0) {
                val tx = (xx - tw / 2f).coerceIn(ox + padL * 0.4f, ox + w - tw - 4f)
                out.add(DrawOp.Glyphs(lbl, tx, (y0 + tickSize * 1.15f).coerceAtMost(oy + h - 4f), tickSize, Face.BODY, Ink.NOTE))
            }
            gx += gxStep
        }

        val gyStep = niceStep(yMax - yMin, 4)
        var gy = ceil(yMin / gyStep) * gyStep
        while (gy <= yMax + gyStep * 0.001) {
            val yy = py(gy)
            out.add(DrawOp.Line(ox + padL, yy, ox + padL + pw, yy, max(0.8f, stroke * 0.45f), Ink.FAINT))
            val lbl = fmt(gy)
            val tw = metrics.width(lbl, tickSize, Face.BODY)
            out.add(DrawOp.Glyphs(lbl, (ox + padL - tw - 6f).coerceAtLeast(ox + 4f), yy + tickSize * 0.35f, tickSize, Face.BODY, Ink.NOTE))
            gy += gyStep
        }

        // Main zero axes with arrows
        out.add(DrawOp.Line(px(g.xMin), y0, px(g.xMax), y0, stroke, Ink.INK))
        addArrowHead(out, px(g.xMin), y0, px(g.xMax), y0, stroke * 0.9f, Ink.INK)
        out.add(DrawOp.Line(x0, py(yMin), x0, py(yMax), stroke, Ink.INK))
        addArrowHead(out, x0, py(yMin), x0, py(yMax), stroke * 0.9f, Ink.INK)

        if (g.xLabel.isNotBlank()) {
            val xtw = metrics.width(g.xLabel, labelSize, Face.BODY_BOLD)
            out.add(DrawOp.Glyphs(g.xLabel, (px(g.xMax) - xtw - 4f).coerceAtLeast(ox + padL), (y0 - labelSize * 0.35f).coerceAtLeast(oy + padT + labelSize), labelSize, Face.BODY_BOLD, Ink.ACCENT))
        }
        if (g.yLabel.isNotBlank()) {
            out.add(DrawOp.Glyphs(g.yLabel, (x0 + labelSize * 0.45f).coerceAtMost(ox + w - 40f), py(yMax) + labelSize * 0.9f, labelSize, Face.BODY_BOLD, Ink.ACCENT))
        }

        val colours = listOf(Ink.ACCENT, Ink.WARN, Ink.RESULT, Ink.VIOLET)
        series.forEachIndexed { idx, s ->
            val ink = colours[idx % colours.size]
            var run = ArrayList<Float>()
            fun flush() {
                if (run.size >= 4) out.add(DrawOp.Poly(run.toFloatArray(), stroke * 1.45f, ink, smooth = true))
                run = ArrayList()
            }
            var prev: Double? = null
            for ((x, y) in s) {
                if (y == null || abs(y) > 1e9 || y < yMin - (yMax - yMin) * 2 || y > yMax + (yMax - yMin) * 2) {
                    flush(); prev = null; continue
                }
                if (prev != null && abs(y - prev) > (yMax - yMin) * 1.5) flush()
                run.add(px(x)); run.add(py(y.coerceIn(yMin, yMax)))
                prev = y
            }
            flush()
        }

        // Legend for multiple functions
        if (g.exprs.size > 1) {
            var ly = oy + padT + tickSize
            g.exprs.forEachIndexed { idx, ex ->
                val ink = colours[idx % colours.size]
                val lbl = "y = $ex"
                val lw = metrics.width(lbl, tickSize, Face.BODY_BOLD)
                val lx = (ox + padL + pw - lw - 24f).coerceAtLeast(ox + padL + 8f)
                out.add(DrawOp.Oval(lx, ly - tickSize * 0.3f, 4.5f, 4.5f, 1f, ink, fill = true))
                out.add(DrawOp.Glyphs(lbl, lx + 10f, ly, tickSize, Face.BODY_BOLD, ink))
                ly += tickSize * 1.3f
            }
        }

        for ((x, y) in g.points) {
            val cx = px(x); val cy = py(y)
            val r = max(4.5f, stroke * 1.9f)
            out.add(DrawOp.Oval(cx, cy, r, r, stroke, Ink.WARN, fill = true, fillInk = Ink.WARN, shade = FillShade.RADIAL, fillAlpha = 0.9f))
        }
        return out
    }
}
