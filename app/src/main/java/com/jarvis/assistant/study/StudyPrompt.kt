package com.jarvis.assistant.study

import com.google.gson.JsonParser

/** What the Study Board knows about the learner. Every field is optional; "auto" means "work it out from the question". */
data class StudyProfile(
    val level: String = "auto",
    val language: String = "auto",
    val depth: Depth = Depth.AUTO,
    val userAge: String = ""
) {
    enum class Depth(val label: String) { AUTO("Auto"), SIMPLE("Simple"), DETAILED("Detailed"), ADVANCED("Advanced") }

    /** A concrete level description for the prompt, or a rule for the model to infer one. */
    fun levelHint(): String {
        val explicit = level.trim()
        if (explicit.isNotEmpty() && !explicit.equals("auto", true)) return "Student level: $explicit. Teach at exactly this level."
        val fromAge = StudyLevels.fromAge(userAge)
        val ageNote = if (fromAge != null) " The student's age suggests about $fromAge; use it only if the question itself gives no clearer sign." else ""
        return "Student level: not set. Infer it from the question (class or grade named, syllabus, notation, difficulty); " +
            "when nothing is clear, choose a Class 9-12 school level, never a university derivation.$ageNote"
    }

    fun languageRule(): String = when (language.trim().lowercase()) {
        "english" -> "Write every explanation in simple English."
        "hindi" -> "Write every explanation in Hindi (Devanagari script). Keep formulas, symbols and numbers as they are."
        "hinglish" -> "Write every explanation in Hinglish: Hindi spoken naturally in Roman letters, with common English technical terms."
        "auto", "" -> "Write explanations in the language the student used in the question; if the question is only maths or symbols, use simple English."
        else -> "Write every explanation in ${language.trim()}. Keep formulas, symbols and numbers as they are."
    }

    fun depthRule(): String = when (depth) {
        Depth.SIMPLE -> "Depth: simple. Fewest steps that stay correct, one very short reason each."
        Depth.DETAILED -> "Depth: detailed. Show every algebraic or logical move, give the reason for each, define each term the first time it is used."
        Depth.ADVANCED -> "Depth: advanced. Concise, rigorous steps; assume strong prior knowledge; include the underlying principle where it matters."
        Depth.AUTO -> "Depth: match the question. A routine problem gets a short solution; a conceptual question gets the key idea and one example. Longer is not better."
    }
}

object StudyLevels {
    val CHOICES = listOf("Auto", "Class 6", "Class 7", "Class 8", "Class 9", "Class 10", "Class 11", "Class 12", "Undergraduate")
    val LANGUAGES = listOf("Auto", "English", "Hindi", "Hinglish")

    /** School class from an age in years (age 6 starts Class 1), or "college level" for adults. Null when the age is unusable. */
    fun fromAge(age: String): String? {
        val n = age.trim().toIntOrNull() ?: return null
        return when {
            n < 6 || n > 90 -> null
            n <= 17 -> "Class ${(n - 5).coerceIn(1, 12)}"
            else -> "college level"
        }
    }
}

object StudyPrompt {

    const val OUTPUT_RULES = """
OUTPUT FORMAT (strict). Reply with JSON objects only, one object per line, nothing else: no greeting, no markdown, no code fences, no text outside the objects.
First line:  {"type":"meta","subject":"Mathematics|Physics|Chemistry|Biology|Computer Science|English|General","topic":"short topic","level":"the level you are teaching at"}
Then one object per board step, in teaching order:
  {"type":"given","content":"..."}                      (physics/chemistry data, only when the question has data)
  {"type":"required","content":"..."}                   (the quantity asked for, only when useful)
  {"type":"formula","content":"...","explanation":"..."}
  {"type":"definition","content":"term: meaning","explanation":"..."} (only a term the solution needs)
  {"type":"step","content":"...","explanation":"..."}   (a calculation or a short statement)
  {"type":"code","content":"code lines","lang":"python"}
  {"type":"diagram","content":"","explanation":"...","diagram":{...}}
Last line: {"type":"final","answer":"...","confidence":"high|medium|low"}
If the question cannot be solved reliably: {"type":"declined","reason":"..."} and nothing else.

CONTENT RULES
- The board is a whiteboard, not a chat. Never write greetings, introductions, "let's solve this", encouragement, apologies, summaries or advice.
- "content" is only what a teacher would write on the board: the formula, the equation, the substitution, the result. Use LaTeX for maths: \frac{a}{b}, x^{2}, x_{1}, \sqrt{x}, \times, \cdot, \pm, \leq, \geq, \neq, \approx, \rightarrow, \pi, \theta, \Delta, \alpha, \ce{H2O}. No dollar signs, no \[ \]. Plain words are allowed only for definitions, English answers and short statements.
- One idea per step. A calculation step shows the equation after the move, e.g. "2x = 15 - 5" then "2x = 10" then "x = \frac{10}{2} = 5". Every number you write must come from the work above it: never skip a calculation and never state a result you did not compute.
- "explanation" is one short sentence saying what is done and why, e.g. "Subtract 5 from both sides." Omit it when the content is obvious. Do not repeat the content in the explanation.
- Physics numericals: given, required, formula, substitution with units, calculation, result with unit. Chemistry: balanced equations (check atom counts), reaction type, calculations with units. Biology: key terms and processes, with a labelled diagram when the question asks to draw or label. Computer science: the code, then the trace or output. English: the rule, corrected sentence, one example.
- The final object's "answer" is the answer only (value with unit, balanced equation, corrected sentence, or one-line conclusion). Do not write the final answer in an earlier object unless it is the last calculation step, and never repeat it twice.
- If you are not sure of a result, set "confidence":"low". If the question is ambiguous or unreadable, use "declined" with the reason.

DIAGRAMS: describe shapes in a 0-100 box (x right, y down), never pixels:
  {"title":"...","items":[{"t":"line","x1":10,"y1":80,"x2":90,"y2":80},{"t":"arrow","x1":..,"y1":..,"x2":..,"y2":..,"color":"red"},{"t":"circle","cx":..,"cy":..,"r":..},{"t":"rect","x":..,"y":..,"w":..,"h":..},{"t":"polygon","pts":[[x,y],[x,y],[x,y]]},{"t":"label","x":..,"y":..,"text":"A"}],"graph":{"fn":"x^2-4","xmin":-4,"xmax":4}}
Use "graph" for a function plot (y in terms of x; ^ for powers) and "items" for geometry, forces, circuits, cell/organ drawings and flowcharts. Label every important part. Draw only what the question needs.
"""

    fun lessonSystem(profile: StudyProfile): String =
        "You are the engine of a classroom whiteboard that teaches one question at a time.\n" +
            profile.levelHint() + "\n" + profile.languageRule() + "\n" + profile.depthRule() + "\n" + OUTPUT_RULES

    fun lessonUser(question: String): String = "Question:\n" + question.trim()

    fun doubtSystem(profile: StudyProfile): String =
        "You are the engine of a classroom whiteboard answering a student's doubt about a solution already on the board.\n" +
            profile.levelHint() + "\n" + profile.languageRule() + "\n" + profile.depthRule() + "\n" +
            "The solution on the board is fixed and is never rewritten. Answer only the doubt, using as few steps as needed (usually 1-4), " +
            "and do not restart the solution.\n" +
            "First line: {\"type\":\"ref\",\"step\":N} where N is the board step number the doubt is about (omit it when the doubt is about the whole solution).\n" +
            "Then the board steps that answer the doubt, then {\"type\":\"final\",\"answer\":\"...\",\"confidence\":\"high|medium|low\"} where the answer is the one-line resolution.\n" +
            "If the doubt shows that a step on the board is actually wrong, say so plainly in the first step and give the corrected step.\n" +
            OUTPUT_RULES.substringAfter("CONTENT RULES").let { "CONTENT RULES$it" }

    /** The lesson so far, in a compact form the doubt prompt can quote. */
    fun lessonDigest(lesson: StudyLesson): String {
        val sb = StringBuilder()
        sb.append("Question: ").append(lesson.question).append('\n')
        for (s in lesson.steps) {
            sb.append("Step ").append(s.index).append(" [").append(s.kind.name.lowercase()).append("]: ").append(s.content.replace('\n', ' '))
            if (s.explanation.isNotEmpty()) sb.append("  (").append(s.explanation).append(')')
            sb.append('\n')
        }
        if (lesson.finalAnswer.isNotEmpty()) sb.append("Final answer: ").append(lesson.finalAnswer).append('\n')
        return sb.toString().take(12_000)
    }

    fun doubtUser(lesson: StudyLesson, doubt: String, selectedStep: Int?): String =
        "Solution on the board:\n" + lessonDigest(lesson) + "\n" +
            (if (selectedStep != null) "The student tapped step $selectedStep.\n" else "") +
            "Student's doubt:\n" + doubt.trim()

    const val EXTRACT_SYSTEM = """You read a photograph or a page of study material and copy out the questions on it exactly.
Reply with one JSON object only: {"questions":[{"text":"...","confidence":"high|medium|low","has_diagram":true|false,"note":""}],"unreadable":false}
- Copy each question word for word, including numbers, units, options (A, B, C, D) and symbols. Write maths in LaTeX (\frac{a}{b}, x^{2}, \sqrt{x}).
- If a question refers to a figure, set has_diagram and describe the labelled parts in "note"; never guess values you cannot see.
- Include handwritten questions. If part of a question is smudged or cut off, copy what is readable, put "[unclear]" where text is missing, and set confidence to "low".
- Do not solve anything. Do not add questions that are not on the page. If nothing readable is present, return {"questions":[],"unreadable":true}.
- Output nothing but the JSON object."""

    const val EXTRACT_USER = "Copy out the question(s) on this page."
}

data class ExtractedQuestion(val text: String, val confidence: String, val hasDiagram: Boolean, val note: String) {
    /** Anything not clearly read must be shown to the student for checking before it is solved. */
    val needsConfirmation: Boolean get() = confidence != "high" || text.contains("[unclear]") || hasDiagram
}

object StudyExtraction {
    /** Parses the extraction reply. Returns an empty list (never a guess) when the reply is not the expected JSON. */
    fun parse(reply: String): List<ExtractedQuestion> {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyList()
        val root = try { JsonParser.parseString(reply.substring(start, end + 1)) } catch (_: Exception) { return emptyList() }
        if (!root.isJsonObject) return emptyList()
        val arr = root.asJsonObject.get("questions")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        val out = ArrayList<ExtractedQuestion>()
        for (e in arr) {
            val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val text = o.get("text")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
            if (text.isEmpty()) continue
            val conf = o.get("confidence")?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.lowercase() ?: "low"
            val diagram = o.get("has_diagram")?.takeIf { it.isJsonPrimitive }?.let { it.asString.equals("true", true) } ?: false
            out.add(ExtractedQuestion(text, if (conf in setOf("high", "medium", "low")) conf else "low", diagram, o.get("note")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()))
            if (out.size >= 40) break
        }
        return out
    }
}

object StudyDoubts {
    private val ORDINALS = mapOf(
        "first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5, "sixth" to 6, "seventh" to 7, "eighth" to 8, "ninth" to 9, "tenth" to 10,
        "last" to -1, "final" to -1,
        "पहला" to 1, "पहले" to 1, "दूसरा" to 2, "दूसरे" to 2, "तीसरा" to 3, "तीसरे" to 3, "चौथा" to 4, "चौथे" to 4, "पांचवा" to 5, "पाँचवा" to 5, "आखिरी" to -1, "आख़िरी" to -1,
        "pehla" to 1, "pehle" to 1, "dusra" to 2, "doosra" to 2, "teesra" to 3, "tisra" to 3, "chautha" to 4, "panchva" to 5, "aakhri" to -1
    )

    /**
     * Which board step a spoken or typed doubt points at. An explicit number or ordinal in the text wins over the step the
     * learner tapped; the tapped step is used when the text names none; null means the doubt is about the whole solution.
     */
    fun resolveStep(text: String, tapped: Int?, stepCount: Int): Int? {
        if (stepCount <= 0) return null
        val t = text.lowercase()
        Regex("(?:step|स्टेप|चरण|line|लाइन)\\s*(?:no\\.?|number|नंबर)?\\s*(\\d{1,2})").find(t)?.let { m ->
            val n = m.groupValues[1].toInt()
            if (n in 1..stepCount) return n
        }
        Regex("(\\d{1,2})\\s*(?:st|nd|rd|th)\\s*(?:step|line)").find(t)?.let { m ->
            val n = m.groupValues[1].toInt()
            if (n in 1..stepCount) return n
        }
        for ((word, n) in ORDINALS) {
            if (Regex("(^|[^\\p{L}])" + Regex.escape(word) + "($|[^\\p{L}])").containsMatchIn(t) && (t.contains("step") || t.contains("स्टेप") || t.contains("line") || t.contains("लाइन") || t.contains("चरण"))) {
                val idx = if (n == -1) stepCount else n
                if (idx in 1..stepCount) return idx
            }
        }
        if (tapped != null && tapped in 1..stepCount) return tapped
        return null
    }
}
