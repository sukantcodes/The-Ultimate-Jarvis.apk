package com.jarvis.assistant.study

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Saves and restores a lesson as compact JSON. Only the structured lesson is stored (text, steps, diagram descriptions);
 * the board is laid out again on load, so no screenshots or large images are ever kept.
 */
object StudyLessonCodec {

    const val VERSION = 1

    fun toJson(lesson: StudyLesson, doubts: List<StudyDoubt> = emptyList(), bookmarks: Set<Int> = emptySet()): String {
        val o = JsonObject()
        o.addProperty("v", VERSION)
        o.addProperty("subject", lesson.subject.name)
        o.addProperty("topic", lesson.topic)
        o.addProperty("question", lesson.question)
        o.addProperty("level", lesson.level)
        o.addProperty("final", lesson.finalAnswer)
        o.addProperty("finalExplanation", lesson.finalExplanation)
        o.addProperty("verification", lesson.verification.name)
        o.addProperty("complete", lesson.complete)
        o.addProperty("declined", lesson.declinedReason)
        o.add("steps", stepsToJson(lesson.steps))
        val d = JsonArray()
        for (x in doubts) d.add(JsonObject().apply {
            addProperty("q", x.question); if (x.stepRef != null) addProperty("ref", x.stepRef)
            addProperty("at", x.createdAt); add("steps", stepsToJson(x.steps))
        })
        o.add("doubts", d)
        o.add("bookmarks", JsonArray().apply { bookmarks.sorted().forEach { add(it) } })
        return o.toString()
    }

    class Restored(val lesson: StudyLesson, val doubts: List<StudyDoubt>, val bookmarks: Set<Int>)

    /** Returns null for anything that is not a lesson this version wrote; a damaged save is never half-loaded. */
    fun fromJson(text: String): Restored? {
        return try {
            val o = JsonParser.parseString(text).takeIf { it.isJsonObject }?.asJsonObject ?: return null
            val v = o.get("v")?.asInt ?: return null
            if (v > VERSION) return null
            val steps = stepsFromJson(o.get("steps")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null)
            val lesson = StudyLesson(
                subject = runCatching { StudySubject.valueOf(o.str("subject")) }.getOrDefault(StudySubject.GENERAL),
                topic = o.str("topic"), question = o.str("question"), level = o.str("level"),
                steps = steps, finalAnswer = o.str("final"), finalExplanation = o.str("finalExplanation"),
                verification = runCatching { Verification.valueOf(o.str("verification")) }.getOrDefault(Verification.NOT_VERIFIED),
                complete = o.get("complete")?.asBoolean ?: false, declinedReason = o.str("declined")
            )
            val doubts = ArrayList<StudyDoubt>()
            o.get("doubts")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { e ->
                val d = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                doubts.add(StudyDoubt(d.str("q"), d.get("ref")?.takeIf { it.isJsonPrimitive }?.asInt,
                    stepsFromJson(d.get("steps")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()), d.get("at")?.asLong ?: 0L))
            }
            val marks = HashSet<Int>()
            o.get("bookmarks")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { if (it.isJsonPrimitive) marks.add(it.asInt) }
            Restored(lesson, doubts, marks)
        } catch (_: Exception) {
            null
        }
    }

    private fun JsonObject.str(k: String): String = get(k)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    private fun stepsToJson(steps: List<StudyStep>): JsonArray {
        val a = JsonArray()
        for (s in steps) a.add(JsonObject().apply {
            addProperty("i", s.index); addProperty("k", s.kind.name); addProperty("c", s.content); addProperty("e", s.explanation)
            if (s.diagramJson.isNotEmpty()) addProperty("d", s.diagramJson)
            if (s.language.isNotEmpty()) addProperty("l", s.language)
            addProperty("p", s.proseMode); addProperty("chk", s.check.name); addProperty("f", s.isFinalHighlight)
        })
        return a
    }

    private fun stepsFromJson(a: JsonArray): List<StudyStep> {
        val out = ArrayList<StudyStep>()
        for (e in a) {
            val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val dj = o.str("d")
            val diagram = if (dj.isNotEmpty()) runCatching { DiagramSpec.parse(JsonParser.parseString(dj).asJsonObject) }.getOrNull() else null
            out.add(StudyStep(
                index = o.get("i")?.asInt ?: (out.size + 1),
                kind = runCatching { StepKind.valueOf(o.str("k")) }.getOrDefault(StepKind.STEP),
                content = o.str("c"), explanation = o.str("e"), diagram = diagram, diagramJson = if (diagram != null) dj else "",
                language = o.str("l"), proseMode = o.get("p")?.asBoolean ?: false,
                check = runCatching { StepCheck.valueOf(o.str("chk")) }.getOrDefault(StepCheck.NOT_CHECKED),
                isFinalHighlight = o.get("f")?.asBoolean ?: false
            ))
        }
        return out
    }
}
