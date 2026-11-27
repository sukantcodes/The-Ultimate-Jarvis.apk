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
        "english" -> "Write every board line, explanation, and spoken \"say\" field in clear, natural English."
        "hindi" -> "Write every board line, explanation, and spoken \"say\" field in natural Hindi (Devanagari script). Keep math formulas, symbols, and numbers intact in \"content\" and spell them phonetically as natural Hindi words inside \"say\"."
        "hinglish" -> "Write every board line, explanation, and spoken \"say\" field in natural Hinglish: conversational Hindi spoken in Roman script with clear English technical terms."
        "auto", "" -> "Detect the student's language from the question and write EVERY explanation and spoken \"say\" field in that exact language smoothly (use simple English if the question is purely math symbols)."
        else -> "Write every board line, explanation, and spoken \"say\" field fluently in ${language.trim()}. Keep formulas, symbols, and numbers intact in \"content\", and write \"say\" as warm, natural human speech in ${language.trim()}."
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
    /** "Auto" follows the language of the question. Any other language the model knows works; these are the quick choices. */
    val LANGUAGES = listOf(
        "Auto", "English", "Hindi", "Hinglish", "Bengali", "Marathi", "Gujarati", "Tamil", "Telugu", "Kannada", "Malayalam",
        "Punjabi", "Urdu", "Odia", "Assamese", "Nepali", "Spanish", "French", "German", "Portuguese", "Arabic", "Indonesian",
        "Japanese", "Korean", "Chinese"
    )

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
OUTPUT FORMAT (strict): reply with raw JSON objects only, one per line (no markdown, no code fences, no prose outside JSON).
1. Meta first: {"type":"meta","subject":"Mathematics|Physics|Chemistry|Biology|Computer Science|English|General","topic":"short topic","level":"level"}
2. Steps in teaching order (EVERY step MUST include "say":"complete, natural spoken teacher explanation"):
   - {"type":"given","content":"...","say":"..."} / {"type":"required","content":"...","say":"..."}
   - {"type":"formula","content":"...","explanation":"...","say":"..."}
   - {"type":"definition","content":"Term: clear meaning","explanation":"...","say":"..."}
   - {"type":"step","content":"...","explanation":"...","say":"..."}
   - {"type":"code","content":"code","lang":"python","explanation":"...","say":"..."} (only when code is requested)
   - {"type":"diagram","content":"","explanation":"...","diagram":{...},"say":"..."}
3. Last line: {"type":"final","answer":"...","say":"complete spoken wrap-up stating the exact final result clearly","confidence":"high|medium|low"}
If unsolvable: {"type":"declined","reason":"..."}

CONTENT RULES
- Classroom whiteboard style: Never write greetings, filler, summaries or markdown (`**`, `*`, `#`, `` ` ``, ```, `>`). Highlight key phrases via `Term: meaning`, crisp points via `• ` or `1. `, and typed objects (`given`/`formula`/`definition`/`step`/`diagram`/`final`).
- Math LaTeX inside "content"/"answer" only: \frac{a}{b}, x^{2}, x_{1}, \sqrt{x}, \times, \cdot, \pm, \leq, \geq, \neq, \approx, \rightarrow, \pi, \theta, \Delta, \alpha, \ce{H2O}. No dollar signs, no \[, \textbf, or \color.
- One move per step; every number must follow from prior steps; check equations/atom counts/signs carefully.
- "say" (REQUIRED on EVERY step, diagram, code, formula, and final): warm, human teacher voice in the exact lesson language explaining EVERY detail written or drawn in that block (read equations as natural words like "x squared", walk through diagram parts/labels clearly, explain code logic simply—never output LaTeX/markdown syntax or chatbot filler).
- "explanation": concise reasoning shown under the block (why the move works or what the diagram illustrates).

DIAGRAMS in 0-100 coords (artwork centered inside x=18..82, y=14..86 so external labels fit cleanly in left/right margins):
  {"title":"Short Title","items":[...],"graph":{"fn":"x^2-4","xmin":-4,"xmax":4,"xlabel":"x","ylabel":"y"}}
Primitives ("t"):
  - "curve"/"poly": {"t":"curve","pts":[[x,y],...],"smooth":true,"closed":true|false,"fill":true,"color":"ink","fillColor":"rose","shade":"radial"|"linear","layer":"outline"|"detail"}
  - "oval"/"circle": {"t":"oval","cx":..,"cy":..,"rx":..,"ry":..,"rotate":deg,"fill":true,"color":"violet","fillColor":"violet","shade":"radial"}
  - "arc": {"t":"arc","cx":..,"cy":..,"rx":..,"ry":..,"start":deg,"sweep":deg,"fill":false,"color":"teal"}
  - "roundrect"/"rect": {"t":"roundrect","x":..,"y":..,"w":..,"h":..,"rx":3,"fill":true,"color":"teal","fillColor":"teal","shade":"linear"}
  - "arrow"/"line": {"t":"arrow","x1":..,"y1":..,"x2":..,"y2":..,"dashed":false,"color":"gold"}
  - "label": {"t":"label","ax":pointX,"ay":pointY,"x":labelX,"y":labelY,"text":"Part Name","color":"ink"}
Diagram Standards:
  - Build detailed, domain-accurate artwork (16-45 layered shapes: silhouette, chambers/organs/components, textures/rays/arrows/bonds/nodes) with purposeful colors ("ink","red","blue","green","gold","violet","teal","rose","orange","brown","gray") + "fill":true + "shade":"radial"|"linear".
  - Place ALL external labels at x=6..20 or x=80..94, sorted top-to-bottom, with accurate "ax","ay" pins pointing to the target sub-part.
  - Math curves: use "graph" with function array + optional key intersection "points".
"""

    fun lessonSystem(profile: StudyProfile): String =
        "You are the engine of a classroom whiteboard that teaches one question at a time.\n" +
            profile.levelHint() + "\n" + profile.languageRule() + "\n" + profile.depthRule() + "\n" + OUTPUT_RULES

    fun lessonUser(question: String, memory: String = "", hasCanonicalDiagram: Boolean = false): String {
        val sb = StringBuilder()
        if (memory.isNotBlank()) sb.append(memory.trim()).append("\n\n")
        sb.append("Question:\n").append(question.trim())
        if (hasCanonicalDiagram) {
            sb.append("\n\n[FAST STUDIO DIAGRAM HINT: Immediately after meta, output your FIRST step as {\"type\":\"diagram\",\"content\":\"\",\"explanation\":\"...\",\"diagram\":{\"title\":\"").append(question.trim().take(48).replace("\"", "")).append("\",\"canonical\":true}} so the high-resolution studio diagram renders live at once, then write concise, crystal-clear teaching steps explaining every labelled part and the final answer.]")
        }
        return sb.toString()
    }

    fun doubtSystem(profile: StudyProfile): String =
        "You are the engine of a classroom whiteboard answering a student's doubt about a solution already on the board.\n" +
            profile.levelHint() + "\n" + profile.languageRule() + "\n" + profile.depthRule() + "\n" +
            "The solution on the board is fixed and is never rewritten. Answer only the doubt, using as few steps as needed (usually 1-4), " +
            "and do not restart the solution.\n" +
            "First line: {\"type\":\"ref\",\"step\":N} where N is the board step number the doubt is about (omit it when the doubt is about the whole solution).\n" +
            "Then the board steps that answer the doubt (every step with complete spoken \"say\"), then {\"type\":\"final\",\"answer\":\"...\",\"say\":\"...\",\"confidence\":\"high|medium|low\"} where the answer is the one-line resolution.\n" +
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

    fun doubtUser(lesson: StudyLesson, doubt: String, selectedStep: Int?, memory: String = ""): String =
        (if (memory.isNotBlank()) memory.trim() + "\n\n" else "") + "Solution on the board:\n" + lessonDigest(lesson) + "\n" +
            (if (selectedStep != null) "The student tapped step $selectedStep.\n" else "") +
            "Student's doubt:\n" + doubt.trim()

    const val EXTRACT_SYSTEM = """You read a photograph or a page of study material and copy out the questions on it exactly.
Reply with one JSON object only: {"questions":[{"text":"...","confidence":"high|medium|low","has_diagram":true|false,"note":""}],"unreadable":false}
- Copy each question word for word, including numbers, units, options (A, B, C, D) and symbols. Write maths in LaTeX (\frac{a}{b}, x^{2}, \sqrt{x}).
- If a question refers to a figure or the image mainly shows a diagram/formula/graph without printed question text, set has_diagram:true, describe the figure in "text" (e.g. "Explain or solve the shown diagram: ...") with "confidence":"medium", and list labelled parts in "note".
- Include handwritten questions. If part of a question is smudged or cut off, copy what is readable, put "[unclear]" where text is missing, and set confidence to "low".
- Do not solve anything. If the image is completely blank or blurry noise, return {"questions":[],"unreadable":true}.
- Output nothing but the JSON object."""

    const val EXTRACT_USER = "Copy out the question(s) or study figure on this page."
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
            val text = (o.get("text") ?: o.get("question") ?: o.get("content"))
                ?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
            if (text.isEmpty()) continue
            val conf = o.get("confidence")?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.lowercase() ?: "low"
            val diagram = o.get("has_diagram")?.takeIf { it.isJsonPrimitive }?.let {
                val p = it.asJsonPrimitive
                if (p.isBoolean) p.asBoolean else p.asString.equals("true", true)
            } ?: false
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
