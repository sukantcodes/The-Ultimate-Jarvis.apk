package com.jarvis.assistant.study.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Explicit JSON serializer/deserializer for Study Board models using `org.json`.
 * Immune to R8 obfuscation/repackaging in release APKs and works identically on Android and JVM tests.
 */
object StudyJsonCodec {

    fun encodeSolution(solution: StudySolution): String {
        val root = JSONObject()
        root.put("id", solution.id)
        root.put("subject", solution.subject.name)
        root.put("topic", solution.topic)
        root.put("chapter", solution.chapter)
        root.put("difficulty", solution.difficulty)
        root.put("educationLevel", solution.educationLevel.id)
        root.put("language", solution.language.id)
        root.put("depth", solution.depth.id)
        root.put("question", solution.question)
        root.put("givenValues", stringArray(solution.givenValues))
        root.put("requiredQuantity", solution.requiredQuantity)
        root.put("formulas", stringArray(solution.formulas))
        root.put("definitions", stringArray(solution.definitions))
        root.put("steps", JSONArray().apply { solution.steps.forEach { put(encodeStep(it)) } })
        root.put("finalAnswer", solution.finalAnswer)
        root.put("verificationStatus", solution.verificationStatus.name)
        root.put("verificationNote", solution.verificationNote)
        root.put("doubts", JSONArray().apply { solution.doubts.forEach { put(encodeDoubt(it)) } })
        root.put("inputKind", solution.inputKind.name)
        if (solution.attachmentRef != null) root.put("attachmentRef", solution.attachmentRef)
        root.put("isOffline", solution.isOffline)
        root.put("isSaved", solution.isSaved)
        root.put("createdAt", solution.createdAt)
        root.put("updatedAt", solution.updatedAt)
        return root.toString()
    }

    fun decodeSolution(json: String): StudySolution? = try {
        val root = JSONObject(json)
        val stepsArr = root.optJSONArray("steps") ?: JSONArray()
        val steps = buildList {
            for (i in 0 until stepsArr.length()) {
                val obj = stepsArr.optJSONObject(i) ?: continue
                add(decodeStep(obj, i))
            }
        }
        val doubtsArr = root.optJSONArray("doubts") ?: JSONArray()
        val doubts = buildList {
            for (i in 0 until doubtsArr.length()) {
                val obj = doubtsArr.optJSONObject(i) ?: continue
                add(decodeDoubt(obj))
            }
        }
        StudySolution(
            id = root.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
            subject = StudySubject.entries.firstOrNull { it.name == root.optString("subject") }
                ?: StudySubject.fromLabel(root.optString("subject")),
            topic = root.optString("topic"),
            chapter = root.optString("chapter"),
            difficulty = root.optString("difficulty"),
            educationLevel = EducationLevel.fromId(root.optString("educationLevel")),
            language = StudyLanguage.fromId(root.optString("language")),
            depth = ExplanationDepth.fromId(root.optString("depth")),
            question = root.optString("question"),
            givenValues = readStringList(root.optJSONArray("givenValues")),
            requiredQuantity = root.optString("requiredQuantity"),
            formulas = readStringList(root.optJSONArray("formulas")),
            definitions = readStringList(root.optJSONArray("definitions")),
            steps = steps,
            finalAnswer = root.optString("finalAnswer"),
            verificationStatus = VerificationStatus.fromName(root.optString("verificationStatus")),
            verificationNote = root.optString("verificationNote"),
            doubts = doubts,
            inputKind = StudyInputKind.entries.firstOrNull { it.name == root.optString("inputKind") } ?: StudyInputKind.TEXT,
            attachmentRef = root.optString("attachmentRef").takeIf { it.isNotBlank() },
            isOffline = root.optBoolean("isOffline", false),
            isSaved = root.optBoolean("isSaved", false),
            createdAt = root.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = root.optLong("updatedAt", System.currentTimeMillis())
        )
    } catch (_: Exception) {
        null
    }

    fun encodeStep(step: StudyStep): JSONObject = JSONObject().apply {
        put("stepIndex", step.stepIndex)
        put("label", step.label)
        put("boardText", step.boardText)
        put("mathExpressions", stringArray(step.mathExpressions))
        put("explanation", step.explanation)
        put("highlightTokens", stringArray(step.highlightTokens))
        step.diagram?.let { put("diagram", encodeDiagram(it)) }
        put("codeBlock", step.codeBlock)
        put("codeLanguage", step.codeLanguage)
        put("spokenText", step.spokenText)
        put("isBookmarked", step.isBookmarked)
        put("isVerified", step.isVerified)
    }

    fun decodeStep(obj: JSONObject, fallbackIndex: Int = 0): StudyStep = StudyStep(
        stepIndex = obj.optInt("stepIndex", fallbackIndex),
        label = obj.optString("label"),
        boardText = obj.optString("boardText").ifBlank { obj.optString("content") },
        mathExpressions = readStringList(obj.optJSONArray("mathExpressions") ?: obj.optJSONArray("math_expressions")),
        explanation = obj.optString("explanation"),
        highlightTokens = readStringList(obj.optJSONArray("highlightTokens") ?: obj.optJSONArray("highlight_tokens")),
        diagram = (obj.optJSONObject("diagram") ?: obj.optJSONObject("diagram_instructions"))?.let { decodeDiagram(it) },
        codeBlock = obj.optString("codeBlock").ifBlank { obj.optString("code_block") },
        codeLanguage = obj.optString("codeLanguage").ifBlank { obj.optString("code_language") },
        spokenText = obj.optString("spokenText").ifBlank { obj.optString("spoken_text") },
        isBookmarked = obj.optBoolean("isBookmarked", false),
        isVerified = obj.optBoolean("isVerified", true)
    )

    fun encodeDoubt(d: StudyDoubtEntry): JSONObject = JSONObject().apply {
        put("id", d.id)
        if (d.targetStepIndex != null) put("targetStepIndex", d.targetStepIndex)
        put("targetFocus", d.targetFocus)
        put("question", d.question)
        put("boardSummary", d.boardSummary)
        put("explanation", d.explanation)
        put("mathExpressions", stringArray(d.mathExpressions))
        d.diagram?.let { put("diagram", encodeDiagram(it)) }
        put("timestamp", d.timestamp)
    }

    fun decodeDoubt(obj: JSONObject): StudyDoubtEntry = StudyDoubtEntry(
        id = obj.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
        targetStepIndex = if (obj.has("targetStepIndex") && !obj.isNull("targetStepIndex")) obj.optInt("targetStepIndex") else null,
        targetFocus = obj.optString("targetFocus"),
        question = obj.optString("question"),
        boardSummary = obj.optString("boardSummary"),
        explanation = obj.optString("explanation"),
        mathExpressions = readStringList(obj.optJSONArray("mathExpressions")),
        diagram = obj.optJSONObject("diagram")?.let { decodeDiagram(it) },
        timestamp = obj.optLong("timestamp", System.currentTimeMillis())
    )

    fun encodeDiagram(spec: StudyDiagramSpec): JSONObject = JSONObject().apply {
        put("diagramType", spec.diagramType)
        put("title", spec.title)
        put("elements", JSONArray().apply {
            spec.elements.forEach { el ->
                val o = JSONObject()
                when (el) {
                    is DiagramElement.Axis -> {
                        o.put("type", "axis")
                        o.put("xMin", el.xMin.toDouble()); o.put("xMax", el.xMax.toDouble())
                        o.put("yMin", el.yMin.toDouble()); o.put("yMax", el.yMax.toDouble())
                        o.put("xLabel", el.xLabel); o.put("yLabel", el.yLabel); o.put("showGrid", el.showGrid)
                    }
                    is DiagramElement.Curve -> {
                        o.put("type", "curve")
                        o.put("points", JSONArray().apply {
                            el.points.forEach { (px, py) ->
                                put(JSONArray().put(px.toDouble()).put(py.toDouble()))
                            }
                        })
                        o.put("label", el.label); o.put("closed", el.closed)
                    }
                    is DiagramElement.Line -> {
                        o.put("type", "line")
                        o.put("x1", el.x1.toDouble()); o.put("y1", el.y1.toDouble())
                        o.put("x2", el.x2.toDouble()); o.put("y2", el.y2.toDouble())
                        o.put("dashed", el.dashed); o.put("label", el.label)
                    }
                    is DiagramElement.VectorArrow -> {
                        o.put("type", "vector")
                        o.put("x1", el.x1.toDouble()); o.put("y1", el.y1.toDouble())
                        o.put("x2", el.x2.toDouble()); o.put("y2", el.y2.toDouble())
                        o.put("label", el.label); o.put("isPrimary", el.isPrimary)
                    }
                    is DiagramElement.CircleArc -> {
                        o.put("type", "circle")
                        o.put("cx", el.cx.toDouble()); o.put("cy", el.cy.toDouble()); o.put("radius", el.radius.toDouble())
                        o.put("startAngleDeg", el.startAngleDeg.toDouble()); o.put("sweepAngleDeg", el.sweepAngleDeg.toDouble())
                        o.put("label", el.label); o.put("filled", el.filled)
                    }
                    is DiagramElement.RectBox -> {
                        o.put("type", "rect")
                        o.put("x", el.x.toDouble()); o.put("y", el.y.toDouble())
                        o.put("w", el.w.toDouble()); o.put("h", el.h.toDouble())
                        o.put("label", el.label); o.put("rounded", el.rounded)
                    }
                    is DiagramElement.DiamondNode -> {
                        o.put("type", "diamond")
                        o.put("cx", el.cx.toDouble()); o.put("cy", el.cy.toDouble())
                        o.put("w", el.w.toDouble()); o.put("h", el.h.toDouble())
                        o.put("label", el.label)
                    }
                    is DiagramElement.ChemBond -> {
                        o.put("type", "bond")
                        o.put("x1", el.x1.toDouble()); o.put("y1", el.y1.toDouble())
                        o.put("x2", el.x2.toDouble()); o.put("y2", el.y2.toDouble())
                        o.put("order", el.order); o.put("label", el.label)
                    }
                    is DiagramElement.ChemRing -> {
                        o.put("type", "ring")
                        o.put("cx", el.cx.toDouble()); o.put("cy", el.cy.toDouble())
                        o.put("radius", el.radius.toDouble()); o.put("sides", el.sides)
                        o.put("aromatic", el.aromatic); o.put("vertexLabels", stringArray(el.vertexLabels))
                    }
                    is DiagramElement.TextLabel -> {
                        o.put("type", "label")
                        o.put("x", el.x.toDouble()); o.put("y", el.y.toDouble())
                        o.put("text", el.text); o.put("emphasis", el.emphasis)
                    }
                }
                put(o)
            }
        })
    }

    fun decodeDiagram(obj: JSONObject): StudyDiagramSpec? {
        val type = obj.optString("diagramType").ifBlank { obj.optString("type") }.ifBlank { "diagram" }
        val title = obj.optString("title")
        val arr = obj.optJSONArray("elements") ?: return null
        val elements = buildList {
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                parseElement(e)?.let { add(it) }
            }
        }
        if (elements.isEmpty()) return null
        return StudyDiagramSpec(type, title, elements)
    }

    private fun parseElement(e: JSONObject): DiagramElement? {
        val t = e.optString("type").lowercase()
        return when (t) {
            "axis", "axes" -> DiagramElement.Axis(
                xMin = e.optDouble("xMin", -5.0).toFloat(),
                xMax = e.optDouble("xMax", 5.0).toFloat(),
                yMin = e.optDouble("yMin", -5.0).toFloat(),
                yMax = e.optDouble("yMax", 5.0).toFloat(),
                xLabel = e.optString("xLabel", "x"),
                yLabel = e.optString("yLabel", "y"),
                showGrid = e.optBoolean("showGrid", true)
            )
            "curve", "polyline", "polygon", "graph" -> {
                val ptsArr = e.optJSONArray("points") ?: return null
                val pts = buildList {
                    for (j in 0 until ptsArr.length()) {
                        val pair = ptsArr.optJSONArray(j)
                        if (pair != null && pair.length() >= 2) {
                            add(pair.optDouble(0).toFloat() to pair.optDouble(1).toFloat())
                        } else {
                            val ptObj = ptsArr.optJSONObject(j)
                            if (ptObj != null) add(ptObj.optDouble("x").toFloat() to ptObj.optDouble("y").toFloat())
                        }
                    }
                }
                if (pts.size < 2) return null
                DiagramElement.Curve(pts, e.optString("label"), e.optBoolean("closed", t == "polygon"))
            }
            "line", "segment" -> DiagramElement.Line(
                x1 = e.optDouble("x1", 10.0).toFloat(),
                y1 = e.optDouble("y1", 50.0).toFloat(),
                x2 = e.optDouble("x2", 90.0).toFloat(),
                y2 = e.optDouble("y2", 50.0).toFloat(),
                dashed = e.optBoolean("dashed", false),
                label = e.optString("label")
            )
            "vector", "arrow" -> DiagramElement.VectorArrow(
                x1 = e.optDouble("x1", 50.0).toFloat(),
                y1 = e.optDouble("y1", 50.0).toFloat(),
                x2 = e.optDouble("x2", 80.0).toFloat(),
                y2 = e.optDouble("y2", 50.0).toFloat(),
                label = e.optString("label"),
                isPrimary = e.optBoolean("isPrimary", true)
            )
            "circle", "arc" -> DiagramElement.CircleArc(
                cx = e.optDouble("cx", 50.0).toFloat(),
                cy = e.optDouble("cy", 50.0).toFloat(),
                radius = e.optDouble("radius", 18.0).toFloat().coerceAtLeast(1f),
                startAngleDeg = e.optDouble("startAngleDeg", 0.0).toFloat(),
                sweepAngleDeg = e.optDouble("sweepAngleDeg", 360.0).toFloat(),
                label = e.optString("label"),
                filled = e.optBoolean("filled", false)
            )
            "rect", "box", "rectbox", "process", "block" -> DiagramElement.RectBox(
                x = e.optDouble("x", 20.0).toFloat(),
                y = e.optDouble("y", 20.0).toFloat(),
                w = e.optDouble("w", 30.0).toFloat().coerceAtLeast(4f),
                h = e.optDouble("h", 18.0).toFloat().coerceAtLeast(4f),
                label = e.optString("label"),
                rounded = e.optBoolean("rounded", true)
            )
            "diamond", "decision" -> DiagramElement.DiamondNode(
                cx = e.optDouble("cx", 50.0).toFloat(),
                cy = e.optDouble("cy", 50.0).toFloat(),
                w = e.optDouble("w", 28.0).toFloat().coerceAtLeast(6f),
                h = e.optDouble("h", 20.0).toFloat().coerceAtLeast(6f),
                label = e.optString("label")
            )
            "bond", "chembond" -> DiagramElement.ChemBond(
                x1 = e.optDouble("x1", 25.0).toFloat(),
                y1 = e.optDouble("y1", 50.0).toFloat(),
                x2 = e.optDouble("x2", 65.0).toFloat(),
                y2 = e.optDouble("y2", 50.0).toFloat(),
                order = e.optInt("order", 1).coerceIn(1, 3),
                label = e.optString("label")
            )
            "ring", "chemring" -> DiagramElement.ChemRing(
                cx = e.optDouble("cx", 50.0).toFloat(),
                cy = e.optDouble("cy", 50.0).toFloat(),
                radius = e.optDouble("radius", 22.0).toFloat().coerceAtLeast(4f),
                sides = e.optInt("sides", 6).coerceIn(3, 8),
                aromatic = e.optBoolean("aromatic", false),
                vertexLabels = readStringList(e.optJSONArray("vertexLabels"))
            )
            "label", "text" -> {
                val txt = e.optString("text").ifBlank { e.optString("label") }
                if (txt.isBlank()) null else DiagramElement.TextLabel(
                    x = e.optDouble("x", 50.0).toFloat(),
                    y = e.optDouble("y", 50.0).toFloat(),
                    text = txt,
                    emphasis = e.optBoolean("emphasis", false)
                )
            }
            else -> null
        }
    }

    fun stringArray(list: List<String>): JSONArray = JSONArray().apply {
        list.forEach { put(it) }
    }

    fun readStringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val v = arr.optString(i).trim()
                if (v.isNotEmpty() && v != "null") add(v)
            }
        }
    }
}
