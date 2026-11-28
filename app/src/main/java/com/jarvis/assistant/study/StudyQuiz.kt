package com.jarvis.assistant.study

import com.google.gson.JsonParser
import kotlin.math.abs

/**
 * Pure-Kotlin post-explanation comprehension check & misconception clarification engine.
 * Generates short diagnostic questions directly anchored to a solved [StudyLesson], checks student answers
 * (both locally via option/numeric/expression matching and semantically via AI), and triggers targeted
 * step-by-step clarification when an answer is incorrect.
 */
data class StudyCheckQuestion(
    val id: Int,
    val question: String,
    val options: List<String> = emptyList(),
    val correctAnswer: String,
    val hint: String = "",
    val explanation: String = "",
    val relatedStep: Int? = null
)

data class StudyCheckEvaluation(
    val isCorrect: Boolean,
    val feedback: String,
    val clarifyQuestion: String = "",
    val relatedStep: Int? = null,
    val usedAi: Boolean = false
)

object StudyQuiz {

    fun generateSystemPrompt(profile: StudyProfile): String = buildString {
        appendLine("You are creating a quick, high-value post-lesson checkup right after teaching on a digital whiteboard.")
        appendLine(profile.levelHint())
        appendLine(profile.languageRule())
        appendLine("Reply with STRICT JSON only (no markdown, no code fences, no extra commentary):")
        appendLine("""{"questions":[{"question":"...","options":["A) ...","B) ...","C) ...","D) ..."],"answer":"A) exact matching option text","hint":"short nudge","explanation":"concise reason why the answer holds","step":1}]}""")
        appendLine("RULES:")
        appendLine("- Create 2 to 3 crisp diagnostic questions directly testing understanding of the concept, formula, diagram, or critical step just taught.")
        appendLine("- Keep questions short, clear, and answerable in 5-15 seconds. Avoid trick wording.")
        appendLine("- Prefer 4 crisp multiple-choice options (`A) ...` to `D) ...`), or set `options:[]` for one short numeric/formula calculation check.")
        appendLine("- Write math symbols in plain Unicode or clean notation (e.g. x², √2, θ, °) — no raw LaTeX braces unless necessary.")
        appendLine("- `step` must be the 1-based step number on the board that relates most closely (or 1).")
    }

    fun generateUserPrompt(lesson: StudyLesson): String = buildString {
        appendLine("Lesson just taught on the Study Board:")
        append(StudyPrompt.lessonDigest(lesson))
        appendLine("\nCreate 2-3 checkup questions to verify the student understood every key step.")
    }

    fun parseQuestions(raw: String): List<StudyCheckQuestion> {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyList()
        val obj = try {
            JsonParser.parseString(raw.substring(start, end + 1)).takeIf { it.isJsonObject }?.asJsonObject
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        val arr = obj.getAsJsonArray("questions") ?: return emptyList()
        val out = ArrayList<StudyCheckQuestion>(arr.size())
        for (el in arr) {
            val item = el.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val q = item.get("question")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
            val ans = (item.get("answer") ?: item.get("correct"))?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
            if (q.isBlank() || ans.isBlank()) continue
            val opts = item.getAsJsonArray("options")
                ?.mapNotNull { it.takeIf { p -> p.isJsonPrimitive }?.asString?.trim()?.takeIf { s -> s.isNotEmpty() } }
                ?.take(5)
                .orEmpty()
            val hint = item.get("hint")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
            val expl = item.get("explanation")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
            val step = item.get("step")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }?.takeIf { it > 0 }
            out.add(
                StudyCheckQuestion(
                    id = out.size + 1,
                    question = StudyAssembler.cleanContent(q),
                    options = opts.map { StudyAssembler.cleanContent(it) },
                    correctAnswer = StudyAssembler.cleanContent(ans),
                    hint = StudyAssembler.cleanExplanation(hint),
                    explanation = StudyAssembler.cleanExplanation(expl),
                    relatedStep = step
                )
            )
            if (out.size >= 4) break
        }
        return out
    }

    /**
     * Checks an answer locally when possible (MCQ letter/index/text, exact string normalization, or numeric equivalence).
     * Returns null when a free-form conceptual text answer needs AI evaluation.
     */
    fun evaluateLocal(q: StudyCheckQuestion, rawUserAnswer: String): StudyCheckEvaluation? {
        val user = rawUserAnswer.trim()
        if (user.isEmpty()) return null

        val correctIdx = resolveOptionIndex(q.options, q.correctAnswer)
        if (q.options.isNotEmpty() && correctIdx >= 0) {
            val pickedIdx = resolveOptionIndex(q.options, user)
            if (pickedIdx >= 0) {
                return if (pickedIdx == correctIdx) {
                    StudyCheckEvaluation(
                        isCorrect = true,
                        feedback = buildString {
                            append("Correct! ")
                            if (q.explanation.isNotBlank()) append(q.explanation)
                        }.trim(),
                        relatedStep = q.relatedStep
                    )
                } else {
                    val chosen = q.options[pickedIdx]
                    StudyCheckEvaluation(
                        isCorrect = false,
                        feedback = "Not quite — you chose \"$chosen\", while the correct answer is \"${q.options[correctIdx]}\". ${q.explanation}".trim(),
                        clarifyQuestion = clarificationPromptForWrong(q, chosen),
                        relatedStep = q.relatedStep
                    )
                }
            }
        }

        val normUser = normalizeText(stripOptionPrefix(user))
        val normExpected = normalizeText(stripOptionPrefix(q.correctAnswer))
        if (normUser.isNotEmpty() && normUser == normExpected) {
            return StudyCheckEvaluation(
                isCorrect = true,
                feedback = buildString {
                    append("Correct! ")
                    if (q.explanation.isNotBlank()) append(q.explanation)
                }.trim(),
                relatedStep = q.relatedStep
            )
        }

        // Numeric / arithmetic expression equivalence (e.g. "x = 5", "10/2", "5.0")
        val uVal = numericValue(user)
        val eVal = numericValue(q.correctAnswer)
        if (uVal != null && eVal != null) {
            val tol = maxOf(1e-3, abs(eVal) * 0.015)
            return if (abs(uVal - eVal) <= tol) {
                StudyCheckEvaluation(
                    isCorrect = true,
                    feedback = buildString {
                        append("Correct (${q.correctAnswer})! ")
                        if (q.explanation.isNotBlank()) append(q.explanation)
                    }.trim(),
                    relatedStep = q.relatedStep
                )
            } else {
                StudyCheckEvaluation(
                    isCorrect = false,
                    feedback = "Not quite — \"$user\" does not match ${q.correctAnswer}. ${q.explanation}".trim(),
                    clarifyQuestion = clarificationPromptForWrong(q, user),
                    relatedStep = q.relatedStep
                )
            }
        }

        return null
    }

    fun evaluateSystemPrompt(profile: StudyProfile): String = buildString {
        appendLine("You are checking a student's practice answer after teaching a lesson on the Study Board.")
        appendLine(profile.levelHint())
        appendLine(profile.languageRule())
        appendLine("Reply with STRICT JSON only (no markdown, no code fences):")
        appendLine("""{"correct":true|false,"feedback":"1-2 clear encouraging sentences","clarify_prompt":"clear focused doubt prompt if wrong so the whiteboard explains the exact misconception step-by-step"}""")
        appendLine("RULES:")
        appendLine("- Accept mathematically or conceptually equivalent answers even if phrased differently.")
        appendLine("- If wrong or incomplete, gently pinpoint the exact misconception in `feedback` and provide `clarify_prompt` summarizing what needs step-by-step clarification on the board.")
    }

    fun evaluateUserPrompt(lesson: StudyLesson, q: StudyCheckQuestion, userAnswer: String): String = buildString {
        appendLine("Topic: ${lesson.topic} (${lesson.subject.label})")
        appendLine("Check Question: ${q.question}")
        if (q.options.isNotEmpty()) appendLine("Options: ${q.options.joinToString(" | ")}")
        appendLine("Expected Answer: ${q.correctAnswer}")
        if (q.explanation.isNotBlank()) appendLine("Reason: ${q.explanation}")
        appendLine("Student's Answer: ${userAnswer.trim()}")
    }

    fun parseEvaluation(raw: String, q: StudyCheckQuestion, userAnswer: String): StudyCheckEvaluation? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val obj = try {
            JsonParser.parseString(raw.substring(start, end + 1)).takeIf { it.isJsonObject }?.asJsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        val correctEl = obj.get("correct")?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
        val ok = if (correctEl.isBoolean) correctEl.asBoolean else correctEl.asString.equals("true", true)
        val fb = obj.get("feedback")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        val clarify = obj.get("clarify_prompt")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        return StudyCheckEvaluation(
            isCorrect = ok,
            feedback = fb.ifBlank {
                if (ok) "Correct! ${q.explanation}".trim()
                else "Not quite — the right answer is ${q.correctAnswer}. ${q.explanation}".trim()
            },
            clarifyQuestion = if (ok) "" else clarify.ifBlank { clarificationPromptForWrong(q, userAnswer) },
            relatedStep = q.relatedStep,
            usedAi = true
        )
    }

    fun clarificationPromptForWrong(q: StudyCheckQuestion, userAnswer: String): String = buildString {
        append("Check question clarification: \"${q.question}\". ")
        append("I answered \"${userAnswer.trim()}\", which is incorrect (correct answer: \"${q.correctAnswer}\"). ")
        append("Please explain clearly step-by-step why \"${userAnswer.trim()}\" is wrong and how to arrive at \"${q.correctAnswer}\"")
        if (q.relatedStep != null) append(" (connecting back to step ${q.relatedStep})")
        append(".")
    }

    private fun resolveOptionIndex(options: List<String>, input: String): Int {
        if (options.isEmpty()) return -1
        val trimmed = input.trim()
        val lower = trimmed.lowercase()

        // Direct exact or stripped match
        val normIn = normalizeText(stripOptionPrefix(trimmed))
        options.forEachIndexed { idx, opt ->
            if (opt.equals(trimmed, ignoreCase = true) || normalizeText(stripOptionPrefix(opt)) == normIn) return idx
        }

        // Letter reference: "A", "option b", "(c)", "d)"
        val letterMatch = Regex("^(?:option\\s+|choice\\s+)?\\(?([a-d])\\)?[.)\\s]*$", RegexOption.IGNORE_CASE).find(lower)
            ?: Regex("^([a-d])[.):\\s]", RegexOption.IGNORE_CASE).find(lower)
        if (letterMatch != null) {
            val idx = letterMatch.groupValues[1].lowercase().first() - 'a'
            if (idx in options.indices) return idx
        }

        // Ordinal/numeric option index: "1", "2", "option 3", "first", "second"
        val numMatch = Regex("^(?:option\\s+)?([1-4])$").find(lower)
        if (numMatch != null) {
            val idx = numMatch.groupValues[1].toInt() - 1
            if (idx in options.indices) return idx
        }
        return -1
    }

    private fun stripOptionPrefix(s: String): String =
        s.replace(Regex("^\\(?[A-Da-d]\\)[\\s.:-]*|^[A-Da-d][.):\\s-]+"), "").trim()

    private fun normalizeText(s: String): String =
        s.lowercase().replace(Regex("[\\s.,;:!?\"'`]+"), " ").trim()

    private fun numericValue(raw: String): Double? {
        val afterEq = raw.substringAfterLast('=').trim()
        val candidate = afterEq.replace(Regex("\\s+(cm|m|km|mm|kg|g|s|ms|hz|n|j|w|v|a|ohm|deg|degrees?)$", RegexOption.IGNORE_CASE), "").trim()
        StudyExpression.evaluate(candidate)?.let { return it }
        return candidate.toDoubleOrNull()
    }
}
