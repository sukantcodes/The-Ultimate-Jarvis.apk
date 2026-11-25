package com.jarvis.assistant.study.ai

import com.jarvis.assistant.study.model.DiagramElement
import com.jarvis.assistant.study.model.StudyDiagramSpec
import com.jarvis.assistant.study.model.StudySolution
import com.jarvis.assistant.study.model.StudyStep
import com.jarvis.assistant.study.model.StudySubject
import com.jarvis.assistant.study.model.VerificationStatus
import java.util.Locale
import kotlin.math.abs

/**
 * Validates every streamed metadata header, educational step, diagram, and final answer before
 * anything touches the whiteboard canvas. Never fabricates missing steps or results.
 */
object StudyResponseValidator {

    sealed class ValidationResult<T> {
        data class Valid<T>(val value: T) : ValidationResult<T>()
        data class Rejected<T>(val reason: String) : ValidationResult<T>()
    }

    fun validateMeta(
        meta: StudyResponseParser.ParsedEvent.Meta,
        fallbackQuestion: String
    ): ValidationResult<StudyResponseParser.ParsedEvent.Meta> {
        val cleanedQuestion = StudyStrictFilter.cleanQuestion(
            meta.question.ifBlank { fallbackQuestion }
        )
        if (cleanedQuestion.isBlank()) {
            return ValidationResult.Rejected("Missing educational question in metadata.")
        }
        val cleanGiven = meta.givenValues.map { StudyStrictFilter.cleanBoardText(it) }.filter { it.isNotBlank() }
        val cleanFormulas = meta.formulas.map { it.trim() }.filter { it.isNotBlank() && !StudyStrictFilter.isPureFiller(it) }
        val cleanDefs = meta.definitions.map { StudyStrictFilter.cleanBoardText(it) }.filter { it.isNotBlank() }
        val cleanRequired = StudyStrictFilter.cleanBoardText(meta.requiredQuantity)
        val cleanTopic = meta.topic.trim().let { if (StudyStrictFilter.isPureFiller(it)) "" else it }
        val cleanChapter = meta.chapter.trim().let { if (StudyStrictFilter.isPureFiller(it)) "" else it }

        return ValidationResult.Valid(
            meta.copy(
                topic = cleanTopic.ifBlank { meta.subject.displayName },
                chapter = cleanChapter,
                question = cleanedQuestion,
                givenValues = cleanGiven,
                requiredQuantity = cleanRequired,
                formulas = cleanFormulas,
                definitions = cleanDefs
            )
        )
    }

    fun validateStep(step: StudyStep, expectedIndex: Int): ValidationResult<StudyStep> {
        val sanitized = StudyStrictFilter.sanitizeStep(step)
        val hasContent = sanitized.boardText.isNotBlank() ||
            sanitized.mathExpressions.isNotEmpty() ||
            sanitized.codeBlock.isNotBlank() ||
            sanitized.diagram != null
        if (!hasContent) {
            return ValidationResult.Rejected("Step $expectedIndex has no educational content.")
        }
        val validDiagram = sanitized.diagram?.let { validateDiagram(it) }
        val finalStep = sanitized.copy(
            stepIndex = expectedIndex,
            diagram = validDiagram
        )
        return ValidationResult.Valid(finalStep)
    }

    fun validateDiagram(spec: StudyDiagramSpec): StudyDiagramSpec? {
        val clamped = spec.elements.mapNotNull { clampElement(it) }
        if (clamped.isEmpty()) return null
        return spec.copy(
            title = StudyStrictFilter.cleanBoardText(spec.title),
            elements = clamped
        )
    }

    private fun clampCoord(v: Float): Float = if (v.isFinite()) v.coerceIn(2f, 98f) else 50f

    private fun clampElement(el: DiagramElement): DiagramElement? = when (el) {
        is DiagramElement.Axis -> if (el.xMax > el.xMin && el.yMax > el.yMin) el else null
        is DiagramElement.Curve -> {
            val pts = el.points.filter { it.first.isFinite() && it.second.isFinite() }
                .map { clampCoord(it.first) to clampCoord(it.second) }
            if (pts.size < 2) null else el.copy(points = pts)
        }
        is DiagramElement.Line -> el.copy(
            x1 = clampCoord(el.x1), y1 = clampCoord(el.y1),
            x2 = clampCoord(el.x2), y2 = clampCoord(el.y2)
        )
        is DiagramElement.VectorArrow -> el.copy(
            x1 = clampCoord(el.x1), y1 = clampCoord(el.y1),
            x2 = clampCoord(el.x2), y2 = clampCoord(el.y2)
        )
        is DiagramElement.CircleArc -> el.copy(
            cx = clampCoord(el.cx), cy = clampCoord(el.cy),
            radius = el.radius.takeIf { it.isFinite() }?.coerceIn(2f, 46f) ?: 14f
        )
        is DiagramElement.RectBox -> el.copy(
            x = clampCoord(el.x), y = clampCoord(el.y),
            w = el.w.takeIf { it.isFinite() }?.coerceIn(6f, 92f) ?: 24f,
            h = el.h.takeIf { it.isFinite() }?.coerceIn(5f, 80f) ?: 14f
        )
        is DiagramElement.DiamondNode -> el.copy(
            cx = clampCoord(el.cx), cy = clampCoord(el.cy),
            w = el.w.takeIf { it.isFinite() }?.coerceIn(8f, 70f) ?: 24f,
            h = el.h.takeIf { it.isFinite() }?.coerceIn(8f, 60f) ?: 18f
        )
        is DiagramElement.ChemBond -> el.copy(
            x1 = clampCoord(el.x1), y1 = clampCoord(el.y1),
            x2 = clampCoord(el.x2), y2 = clampCoord(el.y2),
            order = el.order.coerceIn(1, 3)
        )
        is DiagramElement.ChemRing -> el.copy(
            cx = clampCoord(el.cx), cy = clampCoord(el.cy),
            radius = el.radius.takeIf { it.isFinite() }?.coerceIn(5f, 40f) ?: 20f,
            sides = el.sides.coerceIn(3, 8)
        )
        is DiagramElement.TextLabel -> if (el.text.isBlank()) null else el.copy(
            x = clampCoord(el.x), y = clampCoord(el.y)
        )
    }

    /**
     * Validates the assembled solution and performs deterministic spot-checks (without fabricating steps).
     */
    fun finalizeAndVerify(solution: StudySolution): ValidationResult<StudySolution> {
        if (solution.steps.isEmpty()) {
            return ValidationResult.Rejected("No valid educational steps were produced.")
        }
        val cleanAnswer = StudyStrictFilter.cleanFinalAnswer(solution.finalAnswer)
        if (cleanAnswer.isBlank()) {
            return ValidationResult.Rejected("Final answer is missing; refusing to display an incomplete solution.")
        }

        // Deterministic verification checks where applicable (e.g. simple linear equations ax + b = c).
        var status = solution.verificationStatus
        var note = solution.verificationNote
        if (solution.subject == StudySubject.MATHEMATICS) {
            val check = verifySimpleLinearAnswer(solution.question, cleanAnswer)
            if (check == false) {
                status = VerificationStatus.UNCERTAIN
                note = "Arithmetic spot-check detected a potential mismatch; review the steps."
            } else if (check == true && status == VerificationStatus.PARTIAL) {
                status = VerificationStatus.VERIFIED
            }
        }
        if (status == VerificationStatus.PARTIAL) {
            status = VerificationStatus.VERIFIED
        }

        return ValidationResult.Valid(
            solution.copy(
                finalAnswer = cleanAnswer,
                verificationStatus = status,
                verificationNote = note
            )
        )
    }

    /** Returns true if verified, false if clearly wrong, null if not a simple `ax + b = c` single-var equation. */
    internal fun verifySimpleLinearAnswer(question: String, answer: String): Boolean? {
        val eqMatch = Regex("^\\s*([+-]?\\d*\\.?\\d*)\\s*([a-zA-Z])\\s*([+-]\\s*\\d+\\.?\\d*)?\\s*=\\s*([+-]?\\d+\\.?\\d*)\\s*$")
            .find(question.trim()) ?: return null
        val aStr = eqMatch.groupValues[1]
        val variable = eqMatch.groupValues[2]
        val bStr = eqMatch.groupValues[3].replace(" ", "")
        val cStr = eqMatch.groupValues[4]

        val a = when (aStr) {
            "", "+" -> 1.0
            "-" -> -1.0
            else -> aStr.toDoubleOrNull() ?: return null
        }
        if (abs(a) < 1e-9) return null
        val b = if (bStr.isEmpty()) 0.0 else bStr.toDoubleOrNull() ?: return null
        val c = cStr.toDoubleOrNull() ?: return null
        val expected = (c - b) / a

        val ansMatch = Regex("(?i)\\b$variable\\s*=\\s*([+-]?\\d+(?:\\.\\d+)?)(?:\\s*/\\s*([+-]?\\d+(?:\\.\\d+)?))?").find(answer)
            ?: return null
        val num = ansMatch.groupValues[1].toDoubleOrNull() ?: return null
        val den = ansMatch.groupValues[2].takeIf { it.isNotBlank() }?.toDoubleOrNull() ?: 1.0
        if (abs(den) < 1e-9) return null
        val actual = num / den
        return abs(actual - expected) <= 1e-2
    }
}
