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
2. Steps in teaching order (EVERY step MUST include "say":"expressive, warm classroom mentor speech"):
   - {"type":"given","content":"...","say":"..."} / {"type":"required","content":"...","say":"..."}
   - {"type":"formula","content":"...","explanation":"...","say":"..."}
   - {"type":"definition","content":"Term: clear meaning","explanation":"...","say":"..."}
   - {"type":"step","content":"...","explanation":"...","say":"..."}
   - {"type":"code","content":"code","lang":"python","explanation":"...","say":"..."} (only when code is requested)
   - {"type":"diagram","content":"","explanation":"...","diagram":{...},"say":"..."}
   - Always include at least one dedicated takeaway / memory-trick block ("Key Point & Memory Trick: ...") when teaching any formula, law, process, or tricky step!
3. Last line: {"type":"final","answer":"...","say":"warm, encouraging closing mentor recap celebrating progress, reinforcing the golden memory trick, and stating the final answer clearly","confidence":"high|medium|low"}
If unsolvable: {"type":"declined","reason":"..."}

CONTENT RULES
- Classroom whiteboard style: Never write greetings, filler, summaries or markdown (`**`, `*`, `#`, `` ` ``, ```, `>`). Highlight key phrases via `Term: meaning`, crisp points via `• ` or `1. `, and typed objects (`given`/`formula`/`definition`/`step`/`diagram`/`final`).
- Math LaTeX inside "content"/"answer" only: \frac{a}{b}, x^{2}, x_{1}, \sqrt{x}, \times, \cdot, \pm, \leq, \geq, \neq, \approx, \rightarrow, \pi, \theta, \Delta, \alpha, \ce{H2O}. No dollar signs, no \[, \textbf, or \color.
- One move per step; every number must follow from prior steps; check equations/atom counts/signs carefully.
- "say" (REQUIRED on EVERY step, diagram, code, formula, and final): teach like a passionate, caring, expressive human classroom teacher in the active lesson language — make the student feel personally guided, NEVER sound robotic or dry:
  * Read and explain EVERY written term, equation, diagram label, and code move completely aloud (speak math naturally like "x squared" or "divided by two", never LaTeX/markdown syntax).
  * Weave abundant warm, varied human teacher expressions naturally throughout steps (localized to the active language):
    - Encouraging openers & focus cues ("Alright, stay right with me here — you're doing great!", "Watch closely on this step, this is where it gets really interesting!")
    - Gentle mid-explanation check-ins ("I hope you understood this part well! Let that sink in for a second before we move ahead...", "Makes sense so far, right?", "Take a breath and notice why this happens...")
    - Curiosity & warning callouts ("Here's a super important exam trap to watch out for!", "Look at how cleanly those two pieces fit together!")
  * Explicitly explain the MOST IMPORTANT takeaway in each topic AND share an easy, memorable shortcut, analogy, or mnemonic trick ("Key Point & Memory Trick: ...") so the student remembers it effortlessly!
  * Before introducing EVERY new step or topic transition, briefly explain how it connects to the previous step and what comes next ("Now remember what we got in the last step? Let's carry that forward to...").
- "explanation": concise reasoning shown under the block (include the key exam point or short memory trick cleanly here as well).

DIAGRAMS in 0-100 coords (keep external callouts in x=6..16 or x=84..94 and artwork in x=18..82; for box/node/circuit/geometry diagrams with embedded text or inline labels use x=8..92):
  {"title":"Short Title","items":[...],"graph":{"fn":"x^2-4","xmin":-4,"xmax":4,"xlabel":"x","ylabel":"y"}}
Primitives ("t"):
  - "path": {"t":"path","d":"M 25 40 C 25 20, 75 20, 75 40 ... Z","fill":true,"color":"ink","fillColor":"rose","shade":"radial","layer":"outline"} (supports M/L/H/V/C/S/Q/T/A/Z SVG paths!)
  - "curve"/"poly": {"t":"curve","pts":[[x,y],...],"smooth":true,"closed":true|false,"fill":true,"color":"ink","fillColor":"rose","shade":"radial"|"linear","layer":"outline"|"detail","text":"optional inside label"}
  - "oval"/"circle": {"t":"oval","cx":..,"cy":..,"rx":..,"ry":..,"rotate":deg,"fill":true,"color":"violet","fillColor":"violet","shade":"radial","text":"optional inside label"}
  - "arc": {"t":"arc","cx":..,"cy":..,"rx":..,"ry":..,"start":deg,"sweep":deg,"fill":false,"color":"teal"}
  - "roundrect"/"rect": {"t":"roundrect","x":..,"y":..,"w":..,"h":..,"rx":3,"fill":true,"color":"teal","fillColor":"teal","shade":"linear","text":"Box Title"}
  - "arrow"/"line"/"curved_arrow": {"t":"arrow","x1":..,"y1":..,"x2":..,"y2":..,"dashed":false,"color":"gold","text":"optional"} (use "curved_arrow" with cx,cy for curved flow)
  - STEM components: "resistor", "capacitor", "inductor", "battery", "switch", "diode", "bulb", "meter" ("symbol":"A"|"V"|"G"), "ground", "spring", "gear", "pulley", "diamond", "cylinder", "flask", "burner", "thermometer", "condenser", "dimension", "angle"
  - "label": {"t":"label","x":..,"y":..,"text":"Part Name","color":"ink","place":"inside"|"inline"|"callout","ax":pointX,"ay":pointY}
Diagram Standards:
  - Build crisp, richly detailed, geometrically aligned artwork (20-55 layered shapes: smooth Bezier paths, precision STEM components, textures/rays/arrows/nodes) with purposeful colors ("ink","red","blue","green","gold","violet","teal","rose","orange","brown","gray") + "fill":true + "shade":"radial"|"linear".
  - Put node/layer/box names INSIDE shapes via "text" (or "place":"inside"); put vertex/angle/equation tags via "place":"inline"; put external anatomical/apparatus callouts at x=8..14 or x=86..92 with exact "ax","ay" anchors sorted top-to-bottom.
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
        "You are the engine of a classroom whiteboard answering a student's follow-up doubt about a lesson already on the board.\n" +
            profile.levelHint() + "\n" + profile.languageRule() + "\n" + profile.depthRule() + "\n" +
            "OUTPUT FORMAT (strict): reply with raw JSON objects only, one per line (no markdown, no code fences, no prose outside JSON), starting optionally with {\"type\":\"ref\",\"step\":N}, followed by teaching blocks (\"definition\", \"formula\", \"given\", \"step\", \"code\", \"diagram\"), and ending with {\"type\":\"final\",...}.\n" +
            "CRITICAL DOUBT TEACHING RULES:\n" +
            "- The solution already on the board stays intact. Do NOT restart the whole earlier problem unless the student explicitly asks to re-solve from scratch, AND NEVER jump directly to a bare final answer!\n" +
            "- Explain the doubt thoroughly step-by-step in clear teaching blocks (`definition`, `formula`, `step`, `diagram`, `code`) tailored specifically to what the student is confused about or asking (usually 2-6 steps so every 'why' and 'how' is crystal clear, ending with a takeaway).\n" +
            "- DIAGRAMS IN DOUBTS: Whenever the student asks to draw/show a diagram OR whenever a visual diagram, sketch, ray path, geometry figure, circuit, flowchart, or graph makes the doubt clearer, INCLUDE a dedicated `{\"type\":\"diagram\",\"content\":\"\",\"explanation\":\"...\",\"diagram\":{...},\"say\":\"...\"}` step before the takeaway!\n" +
            "- First line: {\"type\":\"ref\",\"step\":N} where N is the board step number the doubt is about (omit when the doubt is about the whole solution or a new angle).\n" +
            "- Every teaching step MUST include rich, warm classroom teacher speech in \"say\" (explaining every written line, formula, and diagram label aloud with check-ins and memory tricks).\n" +
            "- Last line: {\"type\":\"final\",\"answer\":\"concise takeaway answering the doubt\",\"explanation\":\"key point to remember\",\"say\":\"warm encouraging closing recap\",\"confidence\":\"high|medium|low\"}.\n" +
            "- If the doubt reveals an error in an earlier board step, state it clearly in the first step and show the corrected step.\n" +
            OUTPUT_RULES.substringAfter("CONTENT RULES").let { "CONTENT RULES$it" }

    /** The lesson (and any prior doubts) in a compact form the doubt prompt can quote. */
    fun lessonDigest(lesson: StudyLesson, doubts: List<StudyDoubt> = emptyList()): String {
        val sb = StringBuilder()
        sb.append("Question: ").append(lesson.question).append('\n')
        for (s in lesson.steps) {
            sb.append("Step ").append(s.index).append(" [").append(s.kind.name.lowercase()).append("]: ").append(s.content.replace('\n', ' '))
            if (s.diagram != null && s.diagram.title.isNotBlank()) sb.append(" [Diagram: ").append(s.diagram.title).append(']')
            if (s.explanation.isNotEmpty()) sb.append("  (").append(s.explanation).append(')')
            sb.append('\n')
        }
        if (lesson.finalAnswer.isNotEmpty()) sb.append("Final answer: ").append(lesson.finalAnswer).append('\n')
        if (doubts.isNotEmpty()) {
            sb.append("\nEarlier doubts already answered on the board:\n")
            for ((idx, d) in doubts.takeLast(3).withIndex()) {
                sb.append("Doubt ${idx + 1}: ").append(d.question.replace('\n', ' ')).append('\n')
                for (ds in d.steps.takeLast(4)) {
                    val txt = ds.content.ifBlank { ds.diagram?.title ?: ds.explanation }.replace('\n', ' ')
                    if (txt.isNotBlank()) sb.append("  - ").append(txt.take(220)).append('\n')
                }
            }
        }
        return sb.toString().take(12_000)
    }

    fun doubtUser(
        lesson: StudyLesson,
        doubt: String,
        selectedStep: Int?,
        memory: String = "",
        doubts: List<StudyDoubt> = emptyList(),
        hasCanonicalDiagram: Boolean = false
    ): String {
        val sb = StringBuilder()
        if (memory.isNotBlank()) sb.append(memory.trim()).append("\n\n")
        sb.append("Solution on the board:\n").append(lessonDigest(lesson, doubts)).append('\n')
        if (selectedStep != null) sb.append("The student tapped step $selectedStep.\n")
        sb.append("Student's doubt:\n").append(doubt.trim())
        if (hasCanonicalDiagram) {
            sb.append("\n\n[FAST STUDIO DIAGRAM HINT: Include a {\"type\":\"diagram\",\"content\":\"\",\"explanation\":\"...\",\"diagram\":{\"title\":\"").append(doubt.trim().take(48).replace("\"", "")).append("\",\"canonical\":true},\"say\":\"...\"} step in your doubt explanation along with clear step-by-step teaching blocks.]")
        }
        return sb.toString()
    }

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
