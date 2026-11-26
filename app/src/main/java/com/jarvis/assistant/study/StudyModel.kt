package com.jarvis.assistant.study

/*
 * Study Board core model. This package has no Android imports so the whole teaching pipeline (stream parser, validator,
 * arithmetic check, math layout, board layout) can be unit tested on a plain JVM.
 */

enum class StudySubject(val label: String) {
    MATHEMATICS("Mathematics"),
    PHYSICS("Physics"),
    CHEMISTRY("Chemistry"),
    BIOLOGY("Biology"),
    COMPUTER_SCIENCE("Computer Science"),
    ENGLISH("English"),
    GENERAL("General");

    companion object {
        fun parse(text: String?): StudySubject {
            val t = text?.lowercase()?.trim().orEmpty()
            return when {
                t.isEmpty() -> GENERAL
                "math" in t || "algebra" in t || "calculus" in t || "geometry" in t || "trigonometry" in t || "गणित" in t -> MATHEMATICS
                "phys" in t || "भौतिक" in t -> PHYSICS
                "chem" in t || "रसायन" in t -> CHEMISTRY
                "bio" in t || "जीव" in t -> BIOLOGY
                "computer" in t || "program" in t || "coding" in t || "informatics" in t || t == "cs" -> COMPUTER_SCIENCE
                "english" in t || "grammar" in t || "literature" in t -> ENGLISH
                else -> GENERAL
            }
        }
    }
}

enum class StepKind(val caption: String) {
    GIVEN("Given"),
    REQUIRED("To find"),
    FORMULA("Formula"),
    DEFINITION("Definition"),
    STEP(""),
    CODE("Code"),
    DIAGRAM(""),
    RESULT("");

    companion object {
        fun parse(text: String?): StepKind? {
            return when (text?.trim()?.lowercase()?.replace(" ", "_")) {
                "given", "data", "known" -> GIVEN
                "required", "find", "to_find", "unknown", "asked" -> REQUIRED
                "formula", "law", "equation", "theorem" -> FORMULA
                "definition", "define", "term", "concept" -> DEFINITION
                "step", "calc", "calculation", "work", "explain", "text", "rule", "note" -> STEP
                "code", "program", "snippet", "output" -> CODE
                "diagram", "figure", "graph", "drawing" -> DIAGRAM
                "result", "intermediate", "answer_step" -> RESULT
                else -> null
            }
        }
    }
}

/** Only arithmetic that was actually recomputed is ever marked as checked. Nothing else claims verification. */
enum class StepCheck { NOT_CHECKED, ARITHMETIC_OK }

enum class Verification(val label: String) {
    NOT_VERIFIED("Not independently verified"),
    ARITHMETIC_CHECKED("Arithmetic re-checked"),
    MODEL_UNCERTAIN("AI is not fully sure"),
    FAILED("Could not be verified")
}

data class StudyStep(
    val index: Int,
    val kind: StepKind,
    val content: String,
    val explanation: String,
    val diagram: DiagramSpec? = null,
    /** The diagram exactly as the model described it, kept so a saved lesson can be redrawn at any size. */
    val diagramJson: String = "",
    val language: String = "",
    val proseMode: Boolean = false,
    val check: StepCheck = StepCheck.NOT_CHECKED,
    /** True when this step is also the final answer, so the board highlights it instead of repeating it. */
    val isFinalHighlight: Boolean = false
)

data class StudyLesson(
    val subject: StudySubject = StudySubject.GENERAL,
    val topic: String = "",
    val question: String = "",
    val level: String = "",
    val steps: List<StudyStep> = emptyList(),
    val finalAnswer: String = "",
    val finalExplanation: String = "",
    val verification: Verification = Verification.NOT_VERIFIED,
    val complete: Boolean = false,
    val declinedReason: String = ""
)

/** A follow-up question asked about the finished lesson. The original lesson is never changed by a doubt. */
data class StudyDoubt(
    val question: String,
    val stepRef: Int?,
    val steps: List<StudyStep>,
    val createdAt: Long
)

/** What the model wrote, after the stream parser has split the text into objects. */
sealed class StudyEvent {
    data class Meta(val subject: String, val topic: String, val level: String) : StudyEvent()
    data class Ref(val step: Int) : StudyEvent()
    data class Step(
        val kind: StepKind,
        val content: String,
        val explanation: String,
        val diagram: com.google.gson.JsonObject?,
        val language: String,
        val proseHint: Boolean
    ) : StudyEvent()
    data class Final(val answer: String, val explanation: String, val confidence: String) : StudyEvent()
    data class Declined(val reason: String) : StudyEvent()
    data class Rejected(val reason: String, val raw: String) : StudyEvent()
    /** Text the model wrote outside any JSON object. It is never shown on the board. */
    data class Stray(val text: String) : StudyEvent()
}
