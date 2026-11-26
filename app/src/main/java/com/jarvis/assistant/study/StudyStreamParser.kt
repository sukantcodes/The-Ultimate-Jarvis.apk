package com.jarvis.assistant.study

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Turns streamed model text into [StudyEvent]s as soon as each JSON object is complete. It does not care whether the model
 * wrote one object per line, pretty-printed objects, or put several on one line, and it ignores code fences and prose.
 * Anything that is not a complete, well-formed object becomes a [StudyEvent.Rejected] or [StudyEvent.Stray]; it never throws.
 */
class StudyStreamParser {

    private val buffer = StringBuilder()
    private val stray = StringBuilder()
    private var depth = 0
    private var inString = false
    private var escaped = false

    fun feed(chunk: String): List<StudyEvent> {
        if (chunk.isEmpty()) return emptyList()
        val out = ArrayList<StudyEvent>()
        for (ch in chunk) {
            if (depth == 0) {
                if (ch == '{') {
                    flushStray(out)
                    depth = 1
                    inString = false
                    escaped = false
                    buffer.setLength(0)
                    buffer.append(ch)
                } else if (stray.length < MAX_STRAY) {
                    stray.append(ch)
                }
                continue
            }
            buffer.append(ch)
            if (inString) {
                if (escaped) escaped = false
                else if (ch == '\\') escaped = true
                else if (ch == '"') inString = false
            } else when (ch) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        out.addAll(classify(buffer.toString()))
                        buffer.setLength(0)
                    }
                }
            }
            if (depth > 0 && buffer.length > MAX_OBJECT) {
                out.add(StudyEvent.Rejected("An object in the answer was too large.", buffer.take(80).toString()))
                buffer.setLength(0)
                depth = 0
                inString = false
                escaped = false
            }
        }
        return out
    }

    /** Call once when the stream has ended. Reports a half-written object instead of silently dropping it. */
    fun finish(): List<StudyEvent> {
        val out = ArrayList<StudyEvent>()
        if (depth > 0) {
            out.add(StudyEvent.Rejected("The answer stopped in the middle of a step.", buffer.take(80).toString()))
            buffer.setLength(0)
            depth = 0
        }
        flushStray(out)
        return out
    }

    private fun flushStray(out: MutableList<StudyEvent>) {
        val text = stray.toString().replace("```json", "").replace("```", "").trim()
        stray.setLength(0)
        if (text.isNotEmpty() && text.any { it.isLetterOrDigit() }) out.add(StudyEvent.Stray(text.take(200)))
    }

    companion object {
        private const val MAX_OBJECT = 24_000
        private const val MAX_STRAY = 4_000

        fun classify(raw: String): List<StudyEvent> {
            val element: JsonElement = try {
                JsonParser.parseString(raw)
            } catch (e: Exception) {
                return listOf(StudyEvent.Rejected("A step was not valid JSON.", raw.take(80)))
            }
            if (!element.isJsonObject) return listOf(StudyEvent.Rejected("Expected a JSON object.", raw.take(80)))
            return classifyObject(element.asJsonObject, raw)
        }

        private fun classifyObject(o: JsonObject, raw: String): List<StudyEvent> {
            // One big object with a "steps" array: accept it by expanding it into the same events.
            val stepsArray = o.get("steps")
            if (stepsArray != null && stepsArray.isJsonArray) {
                val events = ArrayList<StudyEvent>()
                if (o.has("subject") || o.has("topic")) events.add(metaOf(o))
                for (item in stepsArray.asJsonArray) {
                    if (item.isJsonObject) events.addAll(classifyObject(item.asJsonObject, raw))
                    else events.add(StudyEvent.Rejected("A step was not an object.", item.toString().take(80)))
                }
                val answer = text(o, "final_answer", "answer", "finalAnswer")
                if (answer.isNotEmpty()) events.add(finalOf(o, answer))
                return events
            }
            val type = text(o, "type", "t").lowercase()
            val hasContent = o.has("content") || o.has("text") || o.has("expression") || o.has("math")
            val kindFromType = StepKind.parse(type)
            return when {
                type == "meta" || (type.isEmpty() && !hasContent && (o.has("subject") || o.has("topic")) && !o.has("answer") && !o.has("final_answer")) ->
                    listOf(metaOf(o))
                type == "ref" || (type.isEmpty() && o.has("step") && !hasContent && !o.has("answer")) -> {
                    val n = o.get("step")?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.let {
                        if (it.isNumber) it.asInt else it.asString.trim().toIntOrNull()
                    }
                    if (n != null) listOf(StudyEvent.Ref(n)) else listOf(StudyEvent.Rejected("A reference had no step number.", raw.take(80)))
                }
                type == "final" || type == "answer" || (type.isEmpty() && !hasContent && (o.has("answer") || o.has("final_answer"))) -> {
                    val answer = text(o, "answer", "final_answer", "finalAnswer", "content")
                    if (answer.isEmpty()) listOf(StudyEvent.Rejected("The final answer was empty.", raw.take(80)))
                    else listOf(finalOf(o, answer))
                }
                type == "declined" || type == "error" || type == "cannot_solve" || type == "unsolvable" ->
                    listOf(StudyEvent.Declined(text(o, "reason", "message", "content").ifEmpty { "The AI could not solve this reliably." }))
                hasContent || kindFromType != null -> {
                    val kind = StepKind.parse(text(o, "kind", "role")) ?: kindFromType ?: StepKind.STEP
                    val content = text(o, "content", "text", "expression", "math")
                    val diagram = o.get("diagram")?.takeIf { it.isJsonObject }?.asJsonObject
                    val mode = text(o, "mode", "format").lowercase()
                    listOf(
                        StudyEvent.Step(
                            kind = kind,
                            content = content,
                            explanation = text(o, "explanation", "explain", "why", "note"),
                            diagram = diagram,
                            language = text(o, "lang", "language"),
                            proseHint = mode == "text" || mode == "prose",
                            spoken = text(o, "say", "spoken", "speech", "narration")
                        )
                    )
                }
                else -> listOf(StudyEvent.Rejected("An object had no recognised fields.", raw.take(80)))
            }
        }

        private fun metaOf(o: JsonObject) = StudyEvent.Meta(
            subject = text(o, "subject"),
            topic = text(o, "topic", "chapter"),
            level = text(o, "level", "class")
        )

        private fun finalOf(o: JsonObject, answer: String) = StudyEvent.Final(
            answer = answer,
            explanation = text(o, "explanation", "explain"),
            confidence = text(o, "confidence").lowercase(),
            spoken = text(o, "say", "spoken", "speech", "narration")
        )

        /** Reads the first present key as text. Arrays of lines are joined with newlines; numbers are written as they are. */
        private fun text(o: JsonObject, vararg keys: String): String {
            for (k in keys) {
                val v = o.get(k) ?: continue
                if (v.isJsonNull) continue
                if (v.isJsonPrimitive) return v.asString.trim()
                if (v.isJsonArray) return v.asJsonArray.joinToString("\n") { if (it.isJsonPrimitive) it.asString else it.toString() }.trim()
            }
            return ""
        }
    }
}
