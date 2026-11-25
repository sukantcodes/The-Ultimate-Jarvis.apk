package com.jarvis.assistant.study.model

import java.util.Locale
import java.util.UUID

/** Academic subject detected or selected for a Study Board lesson. */
enum class StudySubject(
    val displayName: String,
    val formatSubtitle: String,
    val hueOffsetDeg: Float
) {
    MATHEMATICS("Mathematics", "Steps · Equations · Graphs", 0f),
    PHYSICS("Physics", "Given · Formula · Units · Diagrams", 42f),
    CHEMISTRY("Chemistry", "Reactions · Structures · Stoichiometry", 96f),
    BIOLOGY("Biology", "Concepts · Processes · Labelled Diagrams", 145f),
    COMPUTER_SCIENCE("Computer Science", "Algorithms · Code · Flowcharts", 205f),
    ENGLISH("English", "Grammar · Sentence Structure · Literature", 265f),
    GENERAL("General Studies", "Structured Step-by-Step Solution", 315f);

    companion object {
        fun fromLabel(raw: String?): StudySubject {
            val s = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
            return when {
                s.isEmpty() -> GENERAL
                "math" in s || "algebra" in s || "calc" in s || "geom" in s || "trig" in s -> MATHEMATICS
                "phys" in s || "mech" in s || "optic" in s || "electro" in s || "thermo" in s || "kinemat" in s -> PHYSICS
                "chem" in s || "organic" in s || "inorganic" in s || "mole" in s || "reaction" in s -> CHEMISTRY
                "bio" in s || "botan" in s || "zool" in s || "genet" in s || "cell" in s || "anat" in s -> BIOLOGY
                "comp" in s || "cs" == s || "code" in s || "algo" in s || "program" in s || "data struct" in s -> COMPUTER_SCIENCE
                "eng" in s || "gram" in s || "liter" in s || "essay" in s || "prose" in s || "poetry" in s -> ENGLISH
                else -> GENERAL
            }
        }
    }
}

enum class EducationLevel(
    val id: String,
    val label: String,
    val pedagogicalDirective: String
) {
    MIDDLE_SCHOOL(
        "middle_school",
        "Classes 6–8",
        "Teach at a Classes 6–8 level: clear foundational logic, simple arithmetic/algebra, concrete examples, avoid advanced calculus or university jargon."
    ),
    CLASS_9_10(
        "class_9_10",
        "Classes 9–10",
        "Teach at a Class 9–10 (Secondary / Board) level: rigorous algebraic & geometric steps, clear NCERT-aligned terminology, proper units, avoid Class 12/university derivations unless asked."
    ),
    CLASS_11_12(
        "class_11_12",
        "Classes 11–12",
        "Teach at a Class 11–12 (Senior Secondary) level: appropriate vectors, trigonometry, calculus, physical/organic chemistry & diagrams; do not jump to university-level proofs unless requested."
    ),
    COMPETITIVE(
        "competitive",
        "JEE / NEET / Exam",
        "Teach at competitive entrance level: exact conceptual rigor, clean multi-step derivations, boundary/unit checks, and key analytical observations."
    ),
    UNDERGRADUATE(
        "undergraduate",
        "College / University",
        "Teach at undergraduate level: formal mathematical/scientific rigor, complete derivations, and precise notation."
    ),
    GENERAL(
        "general",
        "Auto (From Question)",
        "Infer the exact class/level from the complexity and terminology of the question itself."
    );

    companion object {
        fun fromId(id: String?): EducationLevel =
            entries.firstOrNull { it.id == id } ?: GENERAL
    }
}

enum class ExplanationDepth(
    val id: String,
    val label: String,
    val directive: String
) {
    SIMPLE(
        "simple",
        "Simple",
        "Keep each step crystal clear and approachable using plain reasoning and essential intuition."
    ),
    DETAILED(
        "detailed",
        "Detailed",
        "Explain every logical transition, formula substitution, and unit conversion clearly like a classroom teacher."
    ),
    ADVANCED(
        "advanced",
        "Advanced",
        "Provide deep conceptual precision, derivation insight, edge conditions, and formal mathematical/scientific justification."
    );

    companion object {
        fun fromId(id: String?): ExplanationDepth =
            entries.firstOrNull { it.id == id } ?: DETAILED
    }
}

enum class StudyLanguage(
    val id: String,
    val label: String,
    val speechLocaleTag: String,
    val directive: String
) {
    AUTO(
        "auto",
        "Auto",
        "en-IN",
        "Use the same language as the user's question (English, Hindi, or Hinglish)."
    ),
    ENGLISH(
        "english",
        "English",
        "en-IN",
        "Write board explanations and spoken narration in clear, natural English."
    ),
    HINDI(
        "hindi",
        "Hindi (हिन्दी)",
        "hi-IN",
        "Write explanations and spoken narration in natural Hindi (Devanagari script), while keeping standard mathematical/chemical/code symbols intact."
    ),
    HINGLISH(
        "hinglish",
        "Hinglish",
        "en-IN",
        "Write explanations and spoken narration in natural conversational Hinglish (Roman Hindi mixed naturally with English technical terms, e.g., 'Dono sides se 5 subtract karte hain'), keeping math equations exact."
    );

    companion object {
        fun fromId(id: String?): StudyLanguage =
            entries.firstOrNull { it.id == id } ?: AUTO

        fun fromGlobalLanguagePref(pref: String?): StudyLanguage {
            val clean = pref?.trim()?.lowercase(Locale.ROOT).orEmpty()
            return when {
                clean.contains("hinglish") -> HINGLISH
                clean.contains("hindi") || clean.contains("हिन्दी") -> HINDI
                clean.contains("english") -> ENGLISH
                else -> AUTO
            }
        }
    }
}

enum class StudyInputKind(val label: String) {
    TEXT("Text"),
    VOICE("Voice"),
    CAMERA("Camera"),
    GALLERY("Gallery"),
    PDF("PDF")
}

enum class VerificationStatus(val label: String) {
    VERIFIED("Verified"),
    PARTIAL("Streaming"),
    UNVERIFIED_OFFLINE("Offline Limit"),
    UNCERTAIN("Check Needed"),
    FAILED("Failed");

    companion object {
        fun fromName(name: String?): VerificationStatus =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: UNCERTAIN
    }
}

/**
 * Vector diagram primitives in a normalized 0..100 coordinate canvas so diagrams render identically
 * on high-DPI phones, tablets, landscape boards, PNG snapshots, and exported A4 PDFs.
 */
sealed class DiagramElement {
    data class Axis(
        val xMin: Float = -5f,
        val xMax: Float = 5f,
        val yMin: Float = -5f,
        val yMax: Float = 5f,
        val xLabel: String = "x",
        val yLabel: String = "y",
        val showGrid: Boolean = true
    ) : DiagramElement()

    data class Curve(
        val points: List<Pair<Float, Float>>,
        val label: String = "",
        val closed: Boolean = false
    ) : DiagramElement()

    data class Line(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val dashed: Boolean = false,
        val label: String = ""
    ) : DiagramElement()

    data class VectorArrow(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val label: String = "",
        val isPrimary: Boolean = true
    ) : DiagramElement()

    data class CircleArc(
        val cx: Float,
        val cy: Float,
        val radius: Float,
        val startAngleDeg: Float = 0f,
        val sweepAngleDeg: Float = 360f,
        val label: String = "",
        val filled: Boolean = false
    ) : DiagramElement()

    data class RectBox(
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float,
        val label: String = "",
        val rounded: Boolean = true
    ) : DiagramElement()

    data class DiamondNode(
        val cx: Float,
        val cy: Float,
        val w: Float,
        val h: Float,
        val label: String = ""
    ) : DiagramElement()

    data class ChemBond(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val order: Int = 1,
        val label: String = ""
    ) : DiagramElement()

    data class ChemRing(
        val cx: Float,
        val cy: Float,
        val radius: Float,
        val sides: Int = 6,
        val aromatic: Boolean = false,
        val vertexLabels: List<String> = emptyList()
    ) : DiagramElement()

    data class TextLabel(
        val x: Float,
        val y: Float,
        val text: String,
        val emphasis: Boolean = false
    ) : DiagramElement()
}

data class StudyDiagramSpec(
    val diagramType: String,
    val title: String = "",
    val elements: List<DiagramElement> = emptyList()
)

data class StudyStep(
    val stepIndex: Int,
    val label: String = "",
    val boardText: String,
    val mathExpressions: List<String> = emptyList(),
    val explanation: String = "",
    val highlightTokens: List<String> = emptyList(),
    val diagram: StudyDiagramSpec? = null,
    val codeBlock: String = "",
    val codeLanguage: String = "",
    val spokenText: String = "",
    val isBookmarked: Boolean = false,
    val isVerified: Boolean = true
) {
    /** Compact spoken script aligned with the step being drawn. */
    fun narrationText(): String {
        if (spokenText.isNotBlank()) return spokenText.trim()
        val exp = explanation.trim()
        val board = boardText.trim()
        return when {
            exp.isNotBlank() -> exp
            board.isNotBlank() -> board
            else -> ""
        }
    }
}

data class StudyDoubtEntry(
    val id: String = UUID.randomUUID().toString(),
    val targetStepIndex: Int? = null,
    val targetFocus: String = "",
    val question: String,
    val boardSummary: String,
    val explanation: String,
    val mathExpressions: List<String> = emptyList(),
    val diagram: StudyDiagramSpec? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class StudySolution(
    val id: String = UUID.randomUUID().toString(),
    val subject: StudySubject = StudySubject.GENERAL,
    val topic: String = "",
    val chapter: String = "",
    val difficulty: String = "",
    val educationLevel: EducationLevel = EducationLevel.GENERAL,
    val language: StudyLanguage = StudyLanguage.AUTO,
    val depth: ExplanationDepth = ExplanationDepth.DETAILED,
    val question: String = "",
    val givenValues: List<String> = emptyList(),
    val requiredQuantity: String = "",
    val formulas: List<String> = emptyList(),
    val definitions: List<String> = emptyList(),
    val steps: List<StudyStep> = emptyList(),
    val finalAnswer: String = "",
    val verificationStatus: VerificationStatus = VerificationStatus.PARTIAL,
    val verificationNote: String = "",
    val doubts: List<StudyDoubtEntry> = emptyList(),
    val inputKind: StudyInputKind = StudyInputKind.TEXT,
    val attachmentRef: String? = null,
    val isOffline: Boolean = false,
    val isSaved: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class ExtractedQuestionCandidate(
    val index: Int,
    val questionText: String,
    val pageNumber: Int? = null,
    val subjectHint: StudySubject = StudySubject.GENERAL,
    val diagramSummary: String = "",
    val confidenceNote: String = ""
)

data class TeacherProfile(
    val educationLevel: EducationLevel = EducationLevel.GENERAL,
    val preferredLanguage: StudyLanguage = StudyLanguage.AUTO,
    val preferredDepth: ExplanationDepth = ExplanationDepth.DETAILED,
    val autoAdaptDepth: Boolean = true,
    val voiceEnabled: Boolean = false,
    val voiceLanguage: StudyLanguage = StudyLanguage.AUTO,
    val writingSpeed: Float = 1.0f,
    val showExplanations: Boolean = true,
    val showGrid: Boolean = true,
    val recentTopics: List<String> = emptyList(),
    val completedSessionsCount: Int = 0
)

/** Incremental streaming events emitted while a solution is being generated & validated. */
sealed class StudyStreamEvent {
    data class Metadata(
        val subject: StudySubject,
        val topic: String,
        val chapter: String,
        val difficulty: String,
        val inferredLevel: EducationLevel,
        val question: String,
        val givenValues: List<String>,
        val requiredQuantity: String,
        val formulas: List<String>,
        val definitions: List<String>
    ) : StudyStreamEvent()

    data class StepValidated(val step: StudyStep) : StudyStreamEvent()

    data class Completed(val solution: StudySolution) : StudyStreamEvent()

    data class Error(val message: String, val isOfflineLimitation: Boolean = false) : StudyStreamEvent()
}

enum class BoardPlaybackStatus {
    IDLE,
    STREAMING,
    PLAYING,
    PAUSED,
    COMPLETED,
    ERASING,
    ERROR
}
