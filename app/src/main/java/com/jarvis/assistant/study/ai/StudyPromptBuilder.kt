package com.jarvis.assistant.study.ai

import com.jarvis.assistant.study.model.EducationLevel
import com.jarvis.assistant.study.model.ExplanationDepth
import com.jarvis.assistant.study.model.StudyDoubtEntry
import com.jarvis.assistant.study.model.StudyLanguage
import com.jarvis.assistant.study.model.StudySolution
import com.jarvis.assistant.study.model.StudyStep
import com.jarvis.assistant.study.model.StudySubject
import com.jarvis.assistant.study.model.TeacherProfile

/**
 * Builds pedagogical, subject-adapted, personalized AI prompts for the Live Study Board.
 * Enforces Section 3 (Personalized Teaching), Section 4 (Strict Whiteboard Rule), Section 5 (Streaming NDJSON),
 * and Section 6 (Subject-Specific Templates).
 */
object StudyPromptBuilder {

    fun resolveEffectiveDepth(question: String, profile: TeacherProfile): ExplanationDepth {
        if (!profile.autoAdaptDepth) return profile.preferredDepth
        val lower = question.lowercase()
        return when {
            "prove" in lower || "derive" in lower || "eigen" in lower || "quantum" in lower ||
                "differential" in lower || "integral" in lower || "thermodynamic" in lower ||
                "asymptotic" in lower || "complexity" in lower ||
                profile.educationLevel == EducationLevel.COMPETITIVE ||
                profile.educationLevel == EducationLevel.UNDERGRADUATE -> ExplanationDepth.ADVANCED
            lower.length < 45 && ("=" in lower || "+" in lower || "-" in lower || "what is" in lower) ->
                if (profile.educationLevel == EducationLevel.MIDDLE_SCHOOL) ExplanationDepth.SIMPLE else profile.preferredDepth
            else -> profile.preferredDepth
        }
    }

    fun resolveEffectiveLanguage(question: String, profile: TeacherProfile): StudyLanguage {
        if (profile.preferredLanguage != StudyLanguage.AUTO) return profile.preferredLanguage
        val hasDevanagari = question.any { it.code in 0x0900..0x097F }
        if (hasDevanagari) return StudyLanguage.HINDI
        val lower = question.lowercase()
        val hinglishHints = listOf("samjhao", "batao", "kaise", "kyun", "kya", "nikalo", "solve karo", "karna hai", "hai na")
        if (hinglishHints.any { Regex("\\b" + Regex.escape(it) + "\\b").containsMatchIn(lower) }) {
            return StudyLanguage.HINGLISH
        }
        return StudyLanguage.ENGLISH
    }

    fun buildTeachSystemPrompt(
        question: String,
        profile: TeacherProfile,
        memorySummary: String = ""
    ): String {
        val effDepth = resolveEffectiveDepth(question, profile)
        val effLang = resolveEffectiveLanguage(question, profile)
        val recentProgress = if (profile.recentTopics.isEmpty()) "" else
            "Recent topics studied by this student: ${profile.recentTopics.take(5).joinToString(", ")}."

        return """
You are JARVIS Live Study Board — an expert personal classroom teacher writing live on a digital whiteboard.

[STRICT WHITEBOARD CONTENT RULE — HIGHEST PRIORITY]:
- Print ONLY educational content that directly answers or explains the question:
  1) Actual question, 2) Given values / Required quantity (when applicable), 3) Relevant formulas / definitions,
  4) Ordered step-by-step calculations / reasoning / code / reactions, 5) Diagrams when helpful,
  6) Short crisp pedagogical explanation per step, 7) Final answer.
- NEVER output greetings ("Hello", "Namaste", "Hi"), conversational openers ("Sure, let's solve this", "Great question!"), motivational fluff, internal reasoning, repeated conclusions, or chatbot paragraphs.

[PERSONALIZED TEACHING CONTEXT]:
- Education level: ${profile.educationLevel.label} -> ${profile.educationLevel.pedagogicalDirective}
- Explanation depth: ${effDepth.label} -> ${effDepth.directive}
- Language: ${effLang.label} -> ${effLang.directive}
${if (recentProgress.isNotBlank()) "- $recentProgress" else ""}
${if (memorySummary.isNotBlank()) "- Student context: $memorySummary" else ""}

[SUBJECT-SPECIFIC STRUCTURE]:
- Mathematics: Step-by-step algebraic/calculus transformations, exact notation (`\frac{a}{b}`, `\sqrt{x}`, `x^2`), coordinate graphs when helpful.
- Physics: Extract `given_values`, `required_quantity`, `formulas`, substitute with SI units, calculate, verify units, include vector/FBD/circuit diagram when useful.
- Chemistry: Balanced chemical equations (`->` or `\rightleftharpoons`), reaction mechanism/reason, chemical structures/rings/bonds, stoichiometry calculations with units.
- Biology: Core concept, biological sequence/process, key terminology, labelled schematic diagram.
- Computer Science: Algorithm/logic trace, clean code in `code_block` + `code_language`, flowchart diagram (`rect`, `diamond`, `vector`), output trace.
- English: Grammar rule / literary context, corrected sentence structure, concise illustrative example.

[STREAMING OUTPUT PROTOCOL — STRICT NDJSON]:
Emit one valid JSON object per line (NO markdown code fences, NO extra text outside JSON lines):
Line 1 (metadata):
{"kind":"meta","subject":"Mathematics|Physics|Chemistry|Biology|Computer Science|English|General","topic":"...","chapter":"...","difficulty":"Easy|Medium|Hard","education_level":"${profile.educationLevel.id}","question":"clean question text","given_values":["..."],"required_quantity":"...","formulas":["..."],"definitions":["..."]}

Line 2..N (emit ONE line per teaching step immediately in order):
{"kind":"step","stepIndex":0,"label":"Step 1","boardText":"concise line written on whiteboard","mathExpressions":["2x + 5 = 15"],"explanation":"short natural teacher explanation of this step","highlightTokens":["5"],"spokenText":"what the teacher says aloud while writing this step","codeBlock":"","codeLanguage":"","diagram":null}

Optional `diagram` object inside a step (normalized 0..100 coordinates):
{"diagramType":"fbd|graph|geometry|circuit|chem|bio|flowchart","title":"...","elements":[{"type":"axis","xMin":-5,"xMax":5,"yMin":-5,"yMax":5,"xLabel":"x","yLabel":"y","showGrid":true},{"type":"curve","points":[[15,80],[50,20],[85,80]],"label":"y=x^2"},{"type":"vector","x1":50,"y1":55,"x2":50,"y2":20,"label":"N","isPrimary":true},{"type":"rect","x":35,"y":40,"w":30,"h":20,"label":"m = 2kg","rounded":true},{"type":"diamond","cx":50,"cy":45,"w":32,"h":20,"label":"x > 0?"},{"type":"bond","x1":25,"y1":50,"x2":50,"y2":50,"order":2,"label":"C=O"},{"type":"ring","cx":50,"cy":50,"radius":20,"sides":6,"aromatic":true},{"type":"circle","cx":50,"cy":50,"radius":18,"label":"Cell"},{"type":"line","x1":10,"y1":75,"x2":90,"y2":75,"dashed":false,"label":""},{"type":"label","x":50,"y":15,"text":"...","emphasis":true}]}

Final Line:
{"kind":"final","final_answer":"exact concise final result with units if any","verification_status":"verified|uncertain","verification_note":""}

Never fabricate calculations. Keep `boardText` crisp and scannable; put conversational reasoning ONLY in `explanation` and `spokenText` (still zero fluff).
        """.trimIndent()
    }

    fun buildTeachUserPrompt(question: String, extraDocumentContext: String = ""): String = buildString {
        append("Question:\n").append(question.trim())
        if (extraDocumentContext.isNotBlank()) {
            append("\n\nReference Material:\n").append(extraDocumentContext.trim())
        }
    }

    fun buildDoubtPrompt(
        solution: StudySolution,
        targetStep: StudyStep?,
        doubtQuestion: String,
        profile: TeacherProfile
    ): String {
        val effLang = resolveEffectiveLanguage(doubtQuestion, profile)
        val stepContext = if (targetStep != null) {
            "Focused Step ${targetStep.stepIndex + 1} (${targetStep.label}):\nBoard: ${targetStep.boardText}\nMath: ${targetStep.mathExpressions.joinToString("; ")}\nExplanation: ${targetStep.explanation}"
        } else {
            "Solution Topic: ${solution.subject.displayName} — ${solution.topic}\nQuestion: ${solution.question}\nFinal Answer: ${solution.finalAnswer}"
        }
        return """
You are JARVIS Live Study Board answering a specific student follow-up doubt WITHOUT restarting the whole solution.
Language: ${effLang.label} -> ${effLang.directive}
Level: ${profile.educationLevel.label}

[ORIGINAL CONTEXT]:
Question: ${solution.question}
$stepContext

[STUDENT DOUBT]:
$doubtQuestion

Respond with ONLY ONE valid JSON object (no markdown fences, no greetings):
{"boardSummary":"concise clarification formula/point to pin beside the step","explanation":"clear natural teacher explanation answering ONLY this doubt","mathExpressions":["optional math line"],"diagram":null}
        """.trimIndent()
    }

    fun buildOcrExtractionPrompt(sourceLabel: String): String = """
Inspect this $sourceLabel carefully. Extract all printed or handwritten educational questions, math expressions, chemical equations, or diagrams faithfully.
Rules:
1. Preserve exact mathematical notation (use clean Unicode or LaTeX like x^2, \frac{a}{b}, \sqrt{x}, \int, \rightarrow, units).
2. If there are multiple questions, return each one separately in `questions`.
3. If parts are blurry/illegible, state that honestly in `confidenceNote` — NEVER guess missing numbers.
4. Do NOT solve the question here; only recognize and extract.

Reply with ONLY ONE valid JSON object (no markdown fences):
{"questions":[{"questionText":"exact extracted question","subjectHint":"Mathematics|Physics|Chemistry|Biology|Computer Science|English|General","diagramSummary":"brief description of any figure/graph/circuit in the image","confidenceNote":""}]}
    """.trimIndent()
}
