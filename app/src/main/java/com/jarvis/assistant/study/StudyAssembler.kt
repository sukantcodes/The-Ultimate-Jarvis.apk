package com.jarvis.assistant.study

/**
 * Validates parsed events and builds the lesson one step at a time. A step reaches the board only after it passes every
 * check here, so the board never shows malformed text, chatbot filler, a repeated step or arithmetic that does not add up.
 * Nothing is invented to fill a gap: a step that fails is reported and left out, and the lesson is marked accordingly.
 */
class StudyAssembler(private val question: String, private val level: String) {

    sealed class Update {
        data class LessonChanged(val lesson: StudyLesson) : Update()
        data class StepAdded(val step: StudyStep) : Update()
        data class StepRestyled(val step: StudyStep) : Update()
        data class FinalAnswer(val answer: String, val explanation: String) : Update()
        data class StepRejected(val reason: String, val atIndex: Int) : Update()
        data class Declined(val reason: String) : Update()
        data class DoubtTarget(val step: Int) : Update()
    }

    private var subject = StudySubject.GENERAL
    private var topic = ""
    private var verification = Verification.NOT_VERIFIED
    private var arithmeticChecks = 0
    private var uncertain = false
    private var failed = false
    private val steps = ArrayList<StudyStep>()
    private var finalAnswer = ""
    private var finalExplanation = ""
    private var declined = ""
    private var rejectedCount = 0
    private var seenMeta = false

    val lesson: StudyLesson
        get() = StudyLesson(
            subject = subject,
            topic = topic,
            question = question,
            level = level,
            steps = steps.toList(),
            finalAnswer = finalAnswer,
            finalExplanation = finalExplanation,
            verification = currentVerification(),
            complete = finalAnswer.isNotEmpty() || declined.isNotEmpty(),
            declinedReason = declined
        )

    val rejected: Int get() = rejectedCount
    val hasFinal: Boolean get() = finalAnswer.isNotEmpty()

    private fun currentVerification(): Verification = when {
        failed -> Verification.FAILED
        uncertain -> Verification.MODEL_UNCERTAIN
        arithmeticChecks > 0 -> Verification.ARITHMETIC_CHECKED
        else -> Verification.NOT_VERIFIED
    }

    fun accept(event: StudyEvent): List<Update> {
        if (declined.isNotEmpty()) return emptyList()
        return when (event) {
            is StudyEvent.Meta -> {
                if (seenMeta) return emptyList()
                seenMeta = true
                subject = StudySubject.parse(event.subject)
                topic = cleanLine(event.topic).take(80)
                listOf(Update.LessonChanged(lesson))
            }
            is StudyEvent.Ref -> listOf(Update.DoubtTarget(event.step))
            is StudyEvent.Declined -> {
                declined = event.reason.take(300)
                listOf(Update.Declined(declined))
            }
            is StudyEvent.Final -> finalOf(event)
            is StudyEvent.Step -> stepOf(event)
            is StudyEvent.Rejected -> {
                rejectedCount++
                failed = true
                listOf(Update.StepRejected(event.reason, steps.size + 1))
            }
            is StudyEvent.Stray -> emptyList() // text outside the JSON is never shown on the board
        }
    }

    private fun stepOf(e: StudyEvent.Step): List<Update> {
        if (finalAnswer.isNotEmpty()) return emptyList() // nothing is written after the final answer
        if (steps.size >= MAX_STEPS) return emptyList()
        val index = steps.size + 1
        val diagram = e.diagram?.let { DiagramSpec.parse(it) }
        var content = cleanContent(e.content)
        var explanation = cleanExplanation(e.explanation)

        if (e.diagram != null && diagram == null && content.isEmpty()) {
            rejectedCount++
            return listOf(Update.StepRejected("A diagram had nothing drawable in it.", index))
        }
        if (content.isEmpty() && diagram == null) {
            rejectedCount++
            return listOf(Update.StepRejected("A step had no content.", index))
        }
        if (isChatter(content)) {
            // filler such as "Sure, let's solve this" is dropped without counting as a failure
            if (explanation.isEmpty() || isChatter(explanation)) return emptyList()
            content = ""
        }
        if (isChatter(explanation)) explanation = ""
        if (content.isEmpty() && diagram == null) return emptyList()

        val kind = if (diagram != null && e.kind == StepKind.STEP) StepKind.DIAGRAM else e.kind
        val prose = e.proseHint || kind == StepKind.DEFINITION && !StudyMath.looksLikeMath(content) ||
            kind == StepKind.STEP && !StudyMath.looksLikeMath(content) ||
            (subject == StudySubject.ENGLISH && !StudyMath.looksLikeMath(content))

        var check = StepCheck.NOT_CHECKED
        if (!prose && kind != StepKind.CODE && kind != StepKind.DIAGRAM) {
            when (val r = StudyVerifier.check(content)) {
                is StudyVerifier.Result.Mismatch -> {
                    rejectedCount++
                    failed = true
                    return listOf(Update.StepRejected("Step $index has an arithmetic error (${r.describe()}), so it was not shown.", index))
                }
                is StudyVerifier.Result.Ok -> { check = StepCheck.ARITHMETIC_OK; arithmeticChecks++ }
                StudyVerifier.Result.NotApplicable -> Unit
            }
        }
        val dup = steps.lastOrNull()
        if (dup != null && same(dup.content, content) && dup.diagram == null && diagram == null) return emptyList()

        val step = StudyStep(
            index = index,
            kind = kind,
            content = content,
            explanation = explanation,
            diagram = diagram,
            diagramJson = if (diagram != null) e.diagram.toString() else "",
            language = e.language.take(20),
            proseMode = prose,
            check = check
        )
        steps.add(step)
        return listOf(Update.StepAdded(step))
    }

    private fun finalOf(e: StudyEvent.Final): List<Update> {
        if (finalAnswer.isNotEmpty()) return emptyList() // a repeated conclusion is ignored
        val answer = cleanContent(e.answer)
        if (answer.isEmpty() || isChatter(answer)) {
            rejectedCount++
            return listOf(Update.StepRejected("The final answer was empty.", steps.size + 1))
        }
        if (e.confidence == "low" || e.confidence == "uncertain" || e.confidence == "unsure") uncertain = true
        // A final answer that only restates the last step highlights that step instead of being written twice.
        val last = steps.lastOrNull()
        val out = ArrayList<Update>()
        if (last != null && same(last.content, answer)) {
            val hi = last.copy(isFinalHighlight = true)
            steps[steps.size - 1] = hi
            out.add(Update.StepRestyled(hi))
            finalAnswer = answer
            finalExplanation = ""
            out.add(Update.FinalAnswer(answer, ""))
            return out
        }
        // An answer that contains a number the work never produced would be a made-up result; flag it, never hide it.
        finalAnswer = answer
        finalExplanation = cleanExplanation(e.explanation)
        out.add(Update.FinalAnswer(answer, finalExplanation))
        return out
    }

    /** Called when the stream ends. Never invents a missing final answer. */
    fun finish(streamCompleted: Boolean): List<Update> {
        if (declined.isNotEmpty() || finalAnswer.isNotEmpty()) return emptyList()
        if (steps.isEmpty()) return listOf(Update.Declined("No solution could be generated for this question."))
        if (!streamCompleted || finalAnswer.isEmpty()) {
            failed = true
            return listOf(
                Update.StepRejected("The solution stopped before its final answer, so it is not complete.", steps.size + 1),
                Update.LessonChanged(lesson)
            )
        }
        return emptyList()
    }

    companion object {
        const val MAX_STEPS = 60

        private val CHATTER = listOf(
            Regex("^(sure|okay|ok|alright|certainly|of course|absolutely|great|good question|hello|hi|hey|namaste)\\b[\\s,.!:;-]*", RegexOption.IGNORE_CASE),
            Regex("\\b(let'?s (solve|begin|start|look|see|dive|break|work)|let me (solve|explain|help|show|walk)|i('ll| will| can| hope)|here('s| is) (the|a|your) (solution|answer|explanation)|hope this helps|feel free|happy to help|great question|good luck|you('re| are) doing)\\b", RegexOption.IGNORE_CASE),
            Regex("\\b(as an ai|language model|in conclusion|to summari[sz]e|thank you|keep (it up|practicing|going))\\b", RegexOption.IGNORE_CASE)
        )

        /** True when the text is greeting, commentary or encouragement rather than lesson content. */
        fun isChatter(text: String): Boolean {
            val t = text.trim()
            if (t.isEmpty()) return false
            if (t.length > 160 && !CHATTER[1].containsMatchIn(t) && !CHATTER[2].containsMatchIn(t)) return false
            if (CHATTER[0].containsMatchIn(t) && t.length < 80 && !t.contains('=')) return true
            return (CHATTER[1].containsMatchIn(t) || CHATTER[2].containsMatchIn(t)) && !t.contains('=')
        }

        fun cleanLine(s: String) = s.replace(Regex("\\s+"), " ").trim()

        /** Strips markdown, math fences and stray dollar signs so board text is plain lesson content. */
        fun cleanContent(raw: String): String {
            var s = raw.trim()
            s = s.removePrefix("```").removeSuffix("```").trim()
            s = s.replace("\\[", "").replace("\\]", "").replace("\\(", "").replace("\\)", "")
            s = s.replace("$$", "").replace("$", "")
            s = s.replace("**", "").replace("__", "")
            s = s.lines().joinToString("\n") { it.trimEnd() }.trim()
            return s.take(1200)
        }

        fun cleanExplanation(raw: String): String {
            var s = raw.trim().replace("**", "").replace("$$", "").replace("$", "")
            s = s.replace(Regex("^[-*•]\\s+"), "")
            return cleanLine(s).take(320)
        }

        fun same(a: String, b: String): Boolean {
            fun n(s: String) = s.lowercase().replace(Regex("[\\s\\\\{}$]"), "")
            return n(a) == n(b)
        }
    }
}
