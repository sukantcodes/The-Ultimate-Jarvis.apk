package com.jarvis.assistant.study.ai

import com.jarvis.assistant.study.model.StudyStep
import java.util.Locale

/**
 * Enforces Section 4 (Strict Whiteboard Content Rule):
 * Strips greetings, chatbot filler, internal AI reasoning, motivational lines, and repeated conclusions
 * so the digital whiteboard prints ONLY direct educational content.
 */
object StudyStrictFilter {

    private val conversationalOpenerPatterns = listOf(
        Regex("^(?:hello|hi+|hey|namaste|greetings|welcome|good (?:morning|afternoon|evening))\\b[!.,:\\s-]*", RegexOption.IGNORE_CASE),
        Regex("^(?:sure|certainly|of course|absolutely|alright|okay|ok|great question|good question|nice question|let'?s see|let me explain|let'?s solve(?: this)?(?: step[- ]by[- ]step)?|let'?s break(?: this)? down|step[- ]by[- ]step(?: solution)?|i'?d be happy to help|i can help(?: you)? with that|here is (?:the|your) (?:step-by-step )?(?:solution|answer|explanation))\\b[!.,:\\s-]*", RegexOption.IGNORE_CASE),
        Regex("^(?:haan|hanji|bilkul|chaliye|aaiye|dekhte hain|chalo solve karte hain|toh chaliye)\\b[!.,:\\s-]*", RegexOption.IGNORE_CASE)
    )

    private val pureFillerLinePatterns = listOf(
        Regex("^(?:hope this helps|happy learning|keep practicing|keep studying|good luck|you'?ve got this|feel free to ask(?: any doubts| if you have questions)?|let me know if you need (?:more help|anything else)|does that make sense)[!.]*$", RegexOption.IGNORE_CASE),
        Regex("^(?:as an ai|as a language model|internal reasoning|thinking process|let me double[- ]?check|wait,? let me verify).*$", RegexOption.IGNORE_CASE),
        Regex("^(?:introduction|conclusion|final thoughts|additional notes|summary of what we learned)\\s*:?$", RegexOption.IGNORE_CASE),
        Regex("^(?:thank you|thanks|shukriya|dhanyavaad)[!.]*$", RegexOption.IGNORE_CASE)
    )

    private val fillerSentencePrefix = listOf(
        "sure, let's ", "sure! ", "certainly, ", "of course, ", "great question! ",
        "here's how we solve ", "let's work through ", "now let's carefully ",
        "as an ai, ", "hope that clears it up", "hope this helps"
    )

    fun cleanBoardText(raw: String): String {
        if (raw.isBlank()) return ""
        val lines = raw.replace("\r\n", "\n").lines()
        val kept = ArrayList<String>(lines.size)
        for (line in lines) {
            var current = line.trim()
            if (current.isEmpty()) continue
            if (isPureFiller(current)) continue
            current = stripLeadingOpeners(current)
            if (current.isBlank() || isPureFiller(current)) continue
            kept.add(current)
        }
        return kept.joinToString("\n").trim()
    }

    fun cleanExplanation(raw: String): String {
        if (raw.isBlank()) return ""
        var text = raw.replace("\r\n", "\n").trim()
        // Remove conversational framing sentences at start/end while keeping genuine pedagogical reasoning.
        val lines = text.lines().mapNotNull { line ->
            val trimmed = stripLeadingOpeners(line.trim())
            if (trimmed.isBlank() || isPureFiller(trimmed)) null else trimmed
        }
        text = lines.joinToString(" ").replace(Regex("\\s+"), " ").trim()
        for (prefix in fillerSentencePrefix) {
            if (text.lowercase(Locale.ROOT).startsWith(prefix)) {
                text = text.substring(prefix.length).trim()
            }
        }
        return if (isPureFiller(text)) "" else text
    }

    fun cleanQuestion(raw: String): String {
        val cleaned = raw.replace("\r\n", "\n")
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !isPureFiller(it) }
            .joinToString("\n")
            .trim()
        return stripLeadingOpeners(cleaned).ifBlank { raw.trim() }
    }

    fun cleanFinalAnswer(raw: String): String {
        val cleaned = cleanBoardText(raw)
        return cleaned
            .removePrefix("Final Answer:")
            .removePrefix("Final Answer :")
            .removePrefix("Answer:")
            .trim()
    }

    fun sanitizeStep(step: StudyStep): StudyStep {
        val cleanedBoard = cleanBoardText(step.boardText)
        val cleanedExplanation = cleanExplanation(step.explanation)
        val cleanedSpoken = cleanExplanation(step.spokenText)
        val cleanedMath = step.mathExpressions.map { it.trim() }.filter { it.isNotEmpty() && !isPureFiller(it) }
        val cleanedLabel = step.label.trim().let { if (isPureFiller(it)) "" else it }
        return step.copy(
            label = cleanedLabel,
            boardText = cleanedBoard,
            mathExpressions = cleanedMath,
            explanation = cleanedExplanation,
            spokenText = cleanedSpoken
        )
    }

    private fun stripLeadingOpeners(input: String): String {
        var text = input.trim()
        var changed = true
        while (changed && text.isNotEmpty()) {
            changed = false
            for (pattern in conversationalOpenerPatterns) {
                val m = pattern.find(text)
                if (m != null && m.range.first == 0 && m.value.isNotEmpty()) {
                    // Never strip if it would destroy a mathematical equation or question word like "Hello" inside quotes.
                    val rest = text.substring(m.range.last + 1).trimStart()
                    if (rest.isNotEmpty()) {
                        text = rest
                        changed = true
                    } else if (m.value.length == text.length) {
                        return ""
                    }
                }
            }
        }
        return text
    }

    fun isPureFiller(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return true
        if (pureFillerLinePatterns.any { it.matches(trimmed) }) return true
        val lower = trimmed.lowercase(Locale.ROOT)
        return fillerSentencePrefix.any { lower == it.trim() }
    }
}
