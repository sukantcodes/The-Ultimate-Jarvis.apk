package com.jarvis.assistant.study

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * The teacher's memory of this study session: what the student asked, what the answers were and which doubts came up.
 * It is handed to the model with each new question so "do the same with 5", "why did we do that again" or "the formula from
 * before" work like they do with a real teacher. It is compact (a few hundred words at most), stored on the phone only, and
 * contains nothing but the student's own study questions and the board's answers.
 */
class StudyMemory(private val maxTurns: Int = 8, private val maxChars: Int = 3000) {

    enum class Kind { LESSON, DOUBT }

    data class Turn(
        val kind: Kind,
        val subject: String,
        val topic: String,
        val question: String,
        /** The final answer (or the resolution of a doubt). */
        val answer: String,
        /** The formulas and key results used, kept short. */
        val keyPoints: String,
        val at: Long
    )

    private val turns = ArrayList<Turn>()

    val size: Int get() = turns.size
    val isEmpty: Boolean get() = turns.isEmpty()
    fun all(): List<Turn> = turns.toList()
    fun last(): Turn? = turns.lastOrNull()

    fun clear() = turns.clear()

    fun add(turn: Turn) {
        if (turn.question.isBlank()) return
        // asking the same thing again replaces the older note instead of filling the memory with copies
        turns.removeAll { it.kind == turn.kind && same(it.question, turn.question) }
        turns.add(turn.copy(
            question = turn.question.trim().take(400),
            answer = turn.answer.trim().take(300),
            keyPoints = turn.keyPoints.trim().take(420)
        ))
        while (turns.size > maxTurns) turns.removeAt(0)
    }

    /** The text the model sees. Empty when there is nothing to remember. Oldest first, trimmed from the oldest end. */
    fun block(exceptQuestion: String? = null): String {
        val shown = if (exceptQuestion == null) turns else turns.filterNot { it.kind == Kind.LESSON && same(it.question, exceptQuestion) }
        if (shown.isEmpty()) return ""
        val lines = ArrayList<String>()
        for ((i, t) in shown.withIndex()) {
            val sb = StringBuilder()
            sb.append(i + 1).append(". ").append(if (t.kind == Kind.DOUBT) "Doubt" else "Question").append(": ").append(oneLine(t.question))
            val where = listOf(t.subject, t.topic).filter { it.isNotBlank() }.joinToString(" / ")
            if (where.isNotEmpty()) sb.append("  [").append(where).append(']')
            if (t.keyPoints.isNotBlank()) sb.append("\n   Used: ").append(oneLine(t.keyPoints))
            if (t.answer.isNotBlank()) sb.append("\n   Answer: ").append(oneLine(t.answer))
            lines.add(sb.toString())
        }
        var start = 0
        fun total() = lines.subList(start, lines.size).sumOf { it.length + 1 }
        while (start < lines.size - 1 && total() > maxChars) start++
        return "EARLIER IN THIS STUDY SESSION (oldest first):\n" + lines.subList(start, lines.size).joinToString("\n") + "\n" +
            "Use this only to understand references such as \"again\", \"the same with 5\", \"that formula\" or \"the previous question\", " +
            "and to avoid re-teaching what the student has already been shown. Do not repeat it, do not mention it, and never treat it as part of the new question."
    }

    fun toJson(): String {
        val a = JsonArray()
        for (t in turns) a.add(JsonObject().apply {
            addProperty("k", t.kind.name); addProperty("s", t.subject); addProperty("t", t.topic)
            addProperty("q", t.question); addProperty("a", t.answer); addProperty("p", t.keyPoints); addProperty("at", t.at)
        })
        return JsonObject().apply { addProperty("v", 1); add("turns", a) }.toString()
    }

    /** Loads a saved memory. A damaged or unknown save is ignored and leaves the memory empty. */
    fun load(json: String?) {
        turns.clear()
        if (json.isNullOrBlank()) return
        try {
            val o = JsonParser.parseString(json).asJsonObject
            if ((o.get("v")?.asInt ?: 0) != 1) return
            for (e in o.getAsJsonArray("turns")) {
                val t = e.asJsonObject
                fun s(k: String) = t.get(k)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                add(Turn(
                    kind = runCatching { Kind.valueOf(s("k")) }.getOrDefault(Kind.LESSON),
                    subject = s("s"), topic = s("t"), question = s("q"), answer = s("a"), keyPoints = s("p"),
                    at = t.get("at")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
                ))
            }
        } catch (_: Exception) {
            turns.clear()
        }
    }

    private fun oneLine(s: String) = s.replace(Regex("\\s+"), " ").trim()
    private fun same(a: String, b: String) = oneLine(a).equals(oneLine(b), ignoreCase = true)

    companion object {
        /** A note about a finished lesson: its question, answer and the formulas it used. */
        fun turnFrom(lesson: StudyLesson, now: Long): Turn {
            val key = lesson.steps.filter { it.kind == StepKind.FORMULA || it.kind == StepKind.DEFINITION }
                .take(3).joinToString("; ") { it.content.replace('\n', ' ') }
            return Turn(Kind.LESSON, lesson.subject.label, lesson.topic, lesson.question, lesson.finalAnswer, key, now)
        }

        fun turnFromDoubt(lesson: StudyLesson?, doubt: StudyDoubt, now: Long): Turn {
            val resolution = doubt.steps.lastOrNull()?.content.orEmpty()
            val ref = doubt.stepRef?.let { "about step $it of \"${(lesson?.question ?: "").take(80)}\"" }.orEmpty()
            return Turn(Kind.DOUBT, lesson?.subject?.label.orEmpty(), lesson?.topic.orEmpty(), doubt.question, resolution, ref, now)
        }
    }
}
