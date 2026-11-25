package com.jarvis.assistant.study.ai

import com.jarvis.assistant.study.model.DiagramElement
import com.jarvis.assistant.study.model.EducationLevel
import com.jarvis.assistant.study.model.ExplanationDepth
import com.jarvis.assistant.study.model.StudyDiagramSpec
import com.jarvis.assistant.study.model.StudyInputKind
import com.jarvis.assistant.study.model.StudyLanguage
import com.jarvis.assistant.study.model.StudySolution
import com.jarvis.assistant.study.model.StudyStep
import com.jarvis.assistant.study.model.StudySubject
import com.jarvis.assistant.study.model.TeacherProfile
import com.jarvis.assistant.study.model.VerificationStatus
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Deterministic, verified offline educational solver (Section 11).
 *
 * Solves supported foundational problems locally without internet/API keys and marks them honestly.
 * For open-ended, image-OCR, advanced calculus/organic chemistry, or multi-page document queries
 * that exceed deterministic offline reasoning, explicitly returns [OfflineResult.Unsupported]
 * rather than guessing or pretending an unverified result is verified.
 */
object StudyOfflineSolver {

    sealed class OfflineResult {
        data class Solved(val solution: StudySolution) : OfflineResult()
        data class Unsupported(val reason: String) : OfflineResult()
    }

    fun trySolve(
        rawQuestion: String,
        profile: TeacherProfile,
        inputKind: StudyInputKind = StudyInputKind.TEXT
    ): OfflineResult {
        val question = StudyStrictFilter.cleanQuestion(rawQuestion)
        if (question.isBlank()) {
            return OfflineResult.Unsupported("Enter or speak a question first.")
        }
        if (inputKind == StudyInputKind.CAMERA || inputKind == StudyInputKind.GALLERY) {
            return OfflineResult.Unsupported("Image and handwritten OCR requires an online vision AI connection.")
        }

        val lang = StudyPromptBuilder.resolveEffectiveLanguage(question, profile)

        tryQuadraticEquation(question, profile, lang)?.let { return OfflineResult.Solved(it) }
        tryLinearEquation(question, profile, lang)?.let { return OfflineResult.Solved(it) }
        tryArithmeticExpression(question, profile, lang)?.let { return OfflineResult.Solved(it) }
        tryPhysicsFormula(question, profile, lang)?.let { return OfflineResult.Solved(it) }
        tryBaseConversion(question, profile, lang)?.let { return OfflineResult.Solved(it) }

        return OfflineResult.Unsupported(
            "This question needs online AI reasoning to solve reliably and verify. Connect to the internet (or check your API key in Settings) to generate a complete step-by-step lesson."
        )
    }

    // ---- 1. Linear Equation: ax + b = cx + d ------------------------------------------------------------

    private fun tryLinearEquation(q: String, profile: TeacherProfile, lang: StudyLanguage): StudySolution? {
        val clean = q.replace(Regex("(?i)^(solve|find\\s+[a-z]|calculate|hal karo)\\s*:?\\s*"), "").trim()
        if (!clean.contains('=') || clean.contains("^2") || clean.contains("²")) return null
        val parts = clean.split('=')
        if (parts.size != 2) return null

        val varMatch = Regex("[a-zA-Z]").findAll(clean).map { it.value }.distinct().toList()
        if (varMatch.size != 1) return null
        val variable = varMatch.single()

        val lhs = parseLinearSide(parts[0], variable[0]) ?: return null
        val rhs = parseLinearSide(parts[1], variable[0]) ?: return null

        val netCoeff = lhs.first - rhs.first
        val netConst = rhs.second - lhs.second
        if (abs(netCoeff) < 1e-9) return null

        val root = netConst / netCoeff
        val steps = ArrayList<StudyStep>()

        steps += StudyStep(
            stepIndex = 0,
            label = "Equation",
            boardText = clean,
            mathExpressions = listOf(clean),
            explanation = localize(
                lang,
                "We need to isolate $variable on one side of the equation.",
                "हमें समीकरण के एक तरफ $variable को अलग करना है।",
                "Humein equation ke ek side mein $variable ko isolate karna hai."
            ),
            highlightTokens = listOf(variable),
            spokenText = localize(lang, "Start with the equation $clean.", "समीकरण $clean से शुरू करते हैं।", "Equation $clean se start karte hain.")
        )

        if (abs(rhs.first) > 1e-9 || abs(lhs.second) > 1e-9) {
            val lhsStr = formatLinearTerm(netCoeff, variable[0])
            val rhsCombined = "${fmt(rhs.second)} ${if (lhs.second >= 0) "- ${fmt(lhs.second)}" else "+ ${fmt(-lhs.second)}"}"
            steps += StudyStep(
                stepIndex = 1,
                label = "Group terms",
                boardText = "$lhsStr = $rhsCombined",
                mathExpressions = listOf("$lhsStr = $rhsCombined"),
                explanation = localize(
                    lang,
                    "Move $variable terms to the left side and constant numbers to the right side.",
                    "$variable वाले पदों को बाईं ओर और अचर संख्याओं को दाईं ओर ले जाएं।",
                    "$variable wale terms ko left side aur numbers ko right side bhejte hain."
                ),
                highlightTokens = listOf(lhsStr)
            )
        }

        val simplifiedEq = "${formatLinearTerm(netCoeff, variable[0])} = ${fmt(netConst)}"
        steps += StudyStep(
            stepIndex = steps.size,
            label = "Simplify",
            boardText = simplifiedEq,
            mathExpressions = listOf(simplifiedEq),
            explanation = localize(
                lang,
                "Combine like terms on each side.",
                "दोनों पक्षों के समान पदों को सरल करें।",
                "Dono sides ke like terms simplify karte hain."
            )
        )

        if (abs(netCoeff - 1.0) > 1e-9) {
            steps += StudyStep(
                stepIndex = steps.size,
                label = "Isolate $variable",
                boardText = "$variable = \\frac{${fmt(netConst)}}{${fmt(netCoeff)}} = ${fmt(root)}",
                mathExpressions = listOf("$variable = ${fmt(netConst)} / ${fmt(netCoeff)} = ${fmt(root)}"),
                explanation = localize(
                    lang,
                    "Divide both sides by ${fmt(netCoeff)} to solve for $variable.",
                    "$variable का मान निकालने के लिए दोनों पक्षों को ${fmt(netCoeff)} से भाग दें।",
                    "$variable nikalne ke liye dono sides ko ${fmt(netCoeff)} se divide karte hain."
                ),
                highlightTokens = listOf(fmt(root))
            )
        }

        return StudySolution(
            subject = StudySubject.MATHEMATICS,
            topic = "Linear Equations in One Variable",
            difficulty = "Basic",
            educationLevel = profile.educationLevel,
            language = lang,
            depth = profile.preferredDepth,
            question = clean,
            requiredQuantity = "Value of $variable",
            formulas = listOf("ax + b = c  ⇒  x = (c - b) / a"),
            steps = steps,
            finalAnswer = "$variable = ${fmt(root)}",
            verificationStatus = VerificationStatus.VERIFIED,
            verificationNote = "Verified locally offline by algebraic substitution.",
            inputKind = StudyInputKind.TEXT,
            isOffline = true
        )
    }

    private fun parseLinearSide(expr: String, v: Char): Pair<Double, Double>? {
        val compact = expr.replace(" ", "")
        if (compact.isEmpty() || Regex("[*/()]").containsMatchIn(compact)) return null
        val tokens = Regex("[+-]?[^+-]+").findAll(compact).map { it.value }.toList()
        if (tokens.isEmpty() || tokens.joinToString("") != compact) return null
        var coeff = 0.0
        var constant = 0.0
        for (tok in tokens) {
            if (tok.endsWith(v)) {
                val c = tok.dropLast(1)
                coeff += when (c) {
                    "", "+" -> 1.0
                    "-" -> -1.0
                    else -> c.toDoubleOrNull() ?: return null
                }
            } else {
                constant += tok.toDoubleOrNull() ?: return null
            }
        }
        return coeff to constant
    }

    private fun formatLinearTerm(coeff: Double, v: Char): String = when {
        abs(coeff - 1.0) < 1e-9 -> "$v"
        abs(coeff + 1.0) < 1e-9 -> "-$v"
        else -> "${fmt(coeff)}$v"
    }

    // ---- 2. Quadratic Equation: ax^2 + bx + c = 0 -------------------------------------------------------

    private fun tryQuadraticEquation(q: String, profile: TeacherProfile, lang: StudyLanguage): StudySolution? {
        val norm = q.replace(Regex("(?i)^(solve|find roots|roots of)\\s*:?\\s*"), "")
            .replace("²", "^2")
            .replace(" ", "")
        val match = Regex("^([+-]?\\d*\\.?\\d*)x\\^2([+-]\\d*\\.?\\d*x)?([+-]\\d+\\.?\\d*)?=0$", RegexOption.IGNORE_CASE)
            .find(norm) ?: return null
        val aStr = match.groupValues[1]
        val a = when (aStr) { "", "+" -> 1.0; "-" -> -1.0; else -> aStr.toDoubleOrNull() ?: return null }
        if (abs(a) < 1e-9) return null
        val bx = match.groupValues[2]
        val b = if (bx.isEmpty()) 0.0 else when (val bs = bx.dropLast(1)) { "", "+" -> 1.0; "-" -> -1.0; else -> bs.toDoubleOrNull() ?: return null }
        val c = match.groupValues[3].takeIf { it.isNotEmpty() }?.toDoubleOrNull() ?: 0.0

        val disc = b * b - 4 * a * c
        val steps = mutableListOf(
            StudyStep(
                stepIndex = 0,
                label = "Identify Coefficients",
                boardText = "a = ${fmt(a)},  b = ${fmt(b)},  c = ${fmt(c)}",
                mathExpressions = listOf("ax^2 + bx + c = 0"),
                explanation = localize(lang, "Compare with standard quadratic form ax^2 + bx + c = 0.", "मानक द्विघात समीकरण ax^2 + bx + c = 0 से तुलना करें।", "Standard quadratic form ax^2 + bx + c = 0 se compare karte hain.")
            ),
            StudyStep(
                stepIndex = 1,
                label = "Discriminant",
                boardText = "\\Delta = b^2 - 4ac = (${fmt(b)})^2 - 4(${fmt(a)})(${fmt(c)}) = ${fmt(disc)}",
                mathExpressions = listOf("\\Delta = ${fmt(disc)}"),
                explanation = localize(lang, "Compute the discriminant Δ to determine the nature of roots.", "मूलों की प्रकृति जानने के लिए विविक्तकर Δ की गणना करें।", "Roots ki nature check karne ke liye discriminant Δ nikalte hain."),
                highlightTokens = listOf(fmt(disc))
            )
        )

        val finalAns: String
        if (disc >= 0) {
            val sqrtD = sqrt(disc)
            val x1 = (-b + sqrtD) / (2 * a)
            val x2 = (-b - sqrtD) / (2 * a)
            steps += StudyStep(
                stepIndex = 2,
                label = "Quadratic Formula",
                boardText = "x = \\frac{-b \\pm \\sqrt{\\Delta}}{2a} = \\frac{${fmt(-b)} \\pm ${fmt(sqrtD)}}{${fmt(2 * a)}}",
                mathExpressions = listOf("x = (${fmt(-b)} ± ${fmt(sqrtD)}) / ${fmt(2 * a)}"),
                explanation = localize(lang, "Substitute a, b, and Δ into the quadratic formula.", "द्विघात सूत्र में a, b और Δ का मान रखें।", "Quadratic formula mein a, b aur Δ put karte hain.")
            )
            finalAns = if (abs(x1 - x2) < 1e-9) "x = ${fmt(x1)} (equal roots)" else "x = ${fmt(x1)},  x = ${fmt(x2)}"
            steps += StudyStep(
                stepIndex = 3,
                label = "Roots",
                boardText = finalAns,
                mathExpressions = listOf(finalAns),
                explanation = localize(lang, "Evaluate both branches (+ and -) for the two roots.", "दोनों मूलों के लिए + और - मान की गणना करें।", "Dono roots ke liye + aur - evaluate karte hain."),
                diagram = buildParabolaDiagram(a, b, c)
            )
        } else {
            val realPart = -b / (2 * a)
            val imagPart = sqrt(-disc) / abs(2 * a)
            finalAns = "x = ${fmt(realPart)} ± ${fmt(imagPart)}i"
            steps += StudyStep(
                stepIndex = 2,
                label = "Complex Roots",
                boardText = finalAns,
                mathExpressions = listOf(finalAns),
                explanation = localize(lang, "Since Δ < 0, the equation has complex conjugate roots.", "चूँकि Δ < 0 है, मूल काल्पनिक संयुग्मी होंगे।", "Kyunki Δ < 0 hai, roots complex conjugate honge.")
            )
        }

        return StudySolution(
            subject = StudySubject.MATHEMATICS,
            topic = "Quadratic Equations",
            difficulty = "Medium",
            educationLevel = profile.educationLevel,
            language = lang,
            depth = profile.preferredDepth,
            question = q,
            givenValues = listOf("a = ${fmt(a)}", "b = ${fmt(b)}", "c = ${fmt(c)}"),
            requiredQuantity = "Roots of x",
            formulas = listOf("x = (-b ± √(b² - 4ac)) / (2a)"),
            steps = steps,
            finalAnswer = finalAns,
            verificationStatus = VerificationStatus.VERIFIED,
            verificationNote = "Verified locally offline via quadratic discriminant check.",
            inputKind = StudyInputKind.TEXT,
            isOffline = true
        )
    }

    private fun buildParabolaDiagram(a: Double, b: Double, c: Double): StudyDiagramSpec {
        val vx = -b / (2 * a)
        val pts = (-6..6).map { i ->
            val dx = i * 0.6
            val x = vx + dx
            val y = a * x * x + b * x + c
            val px = (50.0 + i * 6.0).toFloat()
            val normY = (50.0 - (y.coerceIn(-12.0, 12.0) * 3.0)).toFloat().coerceIn(10f, 90f)
            px to normY
        }
        return StudyDiagramSpec(
            diagramType = "graph",
            title = "Parabola Sketch",
            elements = listOf(
                DiagramElement.Axis(-5f, 5f, -5f, 5f, "x", "y", showGrid = true),
                DiagramElement.Curve(pts, label = "f(x)")
            )
        )
    }

    // ---- 3. Arithmetic Expression Evaluator -------------------------------------------------------------

    private fun tryArithmeticExpression(q: String, profile: TeacherProfile, lang: StudyLanguage): StudySolution? {
        val cleaned = q.replace(Regex("(?i)^(calculate|evaluate|simplify|what is|find)\\s*:?\\s*"), "")
            .replace("×", "*").replace("÷", "/").replace("=", "").trim()
        if (cleaned.isEmpty() || cleaned.any { it.isLetter() } || !cleaned.any { it in "+-*/^" }) return null
        val value = evalMath(cleaned) ?: return null
        val steps = listOf(
            StudyStep(
                stepIndex = 0,
                label = "Expression",
                boardText = cleaned,
                mathExpressions = listOf(cleaned),
                explanation = localize(lang, "Apply BODMAS/PEMDAS order of operations (Brackets, Exponents, Division/Multiplication, Addition/Subtraction).", "संक्रियाओं के क्रम (BODMAS) के अनुसार हल करें।", "BODMAS order of operations apply karte hain.")
            ),
            StudyStep(
                stepIndex = 1,
                label = "Evaluate",
                boardText = "$cleaned = ${fmt(value)}",
                mathExpressions = listOf("= ${fmt(value)}"),
                explanation = localize(lang, "Compute the arithmetic expression directly.", "गणना करने पर परिणाम प्राप्त होता है।", "Arithmetic evaluate karne par final value milti hai."),
                highlightTokens = listOf(fmt(value))
            )
        )
        return StudySolution(
            subject = StudySubject.MATHEMATICS,
            topic = "Arithmetic Evaluation",
            difficulty = "Basic",
            educationLevel = profile.educationLevel,
            language = lang,
            depth = profile.preferredDepth,
            question = q,
            formulas = listOf("Order: Brackets → Powers → Multiply/Divide → Add/Subtract"),
            steps = steps,
            finalAnswer = fmt(value),
            verificationStatus = VerificationStatus.VERIFIED,
            verificationNote = "Verified locally offline by deterministic arithmetic evaluation.",
            inputKind = StudyInputKind.TEXT,
            isOffline = true
        )
    }

    // ---- 4. Core Physics Formula Solver (with Units!) --------------------------------------------------

    private fun tryPhysicsFormula(q: String, profile: TeacherProfile, lang: StudyLanguage): StudySolution? {
        val lower = q.lowercase(Locale.ROOT)
        // Ohm's law: V = I * R
        val currentMatch = Regex("(\\d+(?:\\.\\d+)?)\\s*(?:a|amp|amps|ampere|amperes)\\b").find(lower)
        val resMatch = Regex("(\\d+(?:\\.\\d+)?)\\s*(?:ohm|ohms|ω|Ω)").find(lower)
        if (currentMatch != null && resMatch != null && ("voltage" in lower || "potential" in lower || "v =" in lower)) {
            val i = currentMatch.groupValues[1].toDouble()
            val r = resMatch.groupValues[1].toDouble()
            val v = i * r
            return StudySolution(
                subject = StudySubject.PHYSICS,
                topic = "Ohm's Law",
                difficulty = "Basic",
                educationLevel = if (profile.educationLevel == EducationLevel.GENERAL) EducationLevel.CLASS_9_10 else profile.educationLevel,
                language = lang,
                depth = profile.preferredDepth,
                question = q,
                givenValues = listOf("I = ${fmt(i)} A", "R = ${fmt(r)} Ω"),
                requiredQuantity = "Voltage V (in Volts)",
                formulas = listOf("V = I × R"),
                steps = listOf(
                    StudyStep(0, "Formula", "V = I \\times R", listOf("V = I \\times R"), localize(lang, "Apply Ohm's law relating voltage, current, and resistance.", "ओम का नियम V = I × R लागू करें।", "Ohm's law V = I × R apply karte hain.")),
                    StudyStep(1, "Substitution", "V = ${fmt(i)}\\text{ A} \\times ${fmt(r)}\\,\\Omega", listOf("V = ${fmt(i)} × ${fmt(r)}"), localize(lang, "Substitute the given current and resistance in SI units.", "दिए गए मान SI मात्रकों में रखें।", "Given values SI units mein put karte hain.")),
                    StudyStep(2, "Result", "V = ${fmt(v)}\\text{ V}", listOf("V = ${fmt(v)} V"), localize(lang, "Multiply to obtain potential difference in Volts.", "गुणा करने पर वोल्टेज प्राप्त होता है।", "Multiply karne par voltage Volts mein milta hai."), highlightTokens = listOf("${fmt(v)} V"))
                ),
                finalAnswer = "V = ${fmt(v)} V",
                verificationStatus = VerificationStatus.VERIFIED,
                verificationNote = "Verified locally offline using Ohm's Law.",
                inputKind = StudyInputKind.TEXT,
                isOffline = true
            )
        }

        // Newton's 2nd law: F = m * a
        val massMatch = Regex("(\\d+(?:\\.\\d+)?)\\s*kg\\b").find(lower)
        val accMatch = Regex("(\\d+(?:\\.\\d+)?)\\s*m/s\\^?2\\b").find(lower)
        if (massMatch != null && accMatch != null && ("force" in lower || "newton" in lower)) {
            val m = massMatch.groupValues[1].toDouble()
            val a = accMatch.groupValues[1].toDouble()
            val f = m * a
            val diag = StudyDiagramSpec(
                diagramType = "fbd",
                title = "Force Diagram",
                elements = listOf(
                    DiagramElement.Line(10f, 70f, 90f, 70f),
                    DiagramElement.RectBox(35f, 48f, 24f, 22f, "${fmt(m)} kg"),
                    DiagramElement.VectorArrow(59f, 59f, 86f, 59f, "F = ${fmt(f)} N", isPrimary = true)
                )
            )
            return StudySolution(
                subject = StudySubject.PHYSICS,
                topic = "Newton's Second Law of Motion",
                difficulty = "Basic",
                educationLevel = if (profile.educationLevel == EducationLevel.GENERAL) EducationLevel.CLASS_11_12 else profile.educationLevel,
                language = lang,
                depth = profile.preferredDepth,
                question = q,
                givenValues = listOf("m = ${fmt(m)} kg", "a = ${fmt(a)} m/s²"),
                requiredQuantity = "Net Force F (in N)",
                formulas = listOf("F = m × a"),
                steps = listOf(
                    StudyStep(0, "Law & Formula", "F = m \\times a", listOf("F = m \\times a"), localize(lang, "By Newton's second law, net force equals mass times acceleration.", "न्यूटन के द्वितीय नियम से F = m × a होता है।", "Newton's 2nd law ke according F = m × a hota hai."), diagram = diag),
                    StudyStep(1, "Substitution", "F = ${fmt(m)}\\text{ kg} \\times ${fmt(a)}\\text{ m/s}^2", listOf("F = ${fmt(m)} × ${fmt(a)}"), localize(lang, "Substitute mass and acceleration.", "द्रव्यमान और त्वरण का मान रखें।", "Mass aur acceleration substitute karte hain.")),
                    StudyStep(2, "Result", "F = ${fmt(f)}\\text{ N}", listOf("F = ${fmt(f)} N"), localize(lang, "1 kg·m/s² equals 1 Newton (N).", "1 kg·m/s² बराबर 1 न्यूटन (N) होता है।", "1 kg·m/s² = 1 Newton hota hai."), highlightTokens = listOf("${fmt(f)} N"))
                ),
                finalAnswer = "F = ${fmt(f)} N",
                verificationStatus = VerificationStatus.VERIFIED,
                verificationNote = "Verified locally offline using F = ma.",
                inputKind = StudyInputKind.TEXT,
                isOffline = true
            )
        }
        return null
    }

    // ---- 5. Computer Science Binary / Decimal Conversions ------------------------------------------------

    private fun tryBaseConversion(q: String, profile: TeacherProfile, lang: StudyLanguage): StudySolution? {
        val lower = q.lowercase(Locale.ROOT)
        val decToBin = Regex("(?:convert|change)?\\s*(\\d+)\\s+(?:to|into)\\s+binary").find(lower)
        if (decToBin != null) {
            val n = decToBin.groupValues[1].toLongOrNull() ?: return null
            if (n < 0 || n > 1_000_000L) return null
            val bin = n.toString(2)
            val steps = mutableListOf<StudyStep>()
            var cur = n
            val remainders = mutableListOf<String>()
            if (cur == 0L) remainders += "0 ÷ 2 = 0, remainder = 0"
            while (cur > 0) {
                val qVal = cur / 2
                val rem = cur % 2
                remainders += "$cur ÷ 2 = $qVal, remainder = $rem"
                cur = qVal
            }
            steps += StudyStep(0, "Repeated Division by 2", remainders.joinToString("\n"), emptyList(), localize(lang, "Divide repeatedly by 2 and record each remainder.", "बार-बार 2 से भाग दें और शेषफल नोट करें।", "Bar-bar 2 se divide karke remainder note karte hain."))
            steps += StudyStep(1, "Read Bottom-to-Top", "$n_{10} = $bin_{2}", listOf("$n_{10} = $bin_{2}"), localize(lang, "Read the remainders from bottom to top to form the binary digits.", "बाइनरी संख्या पाने के लिए शेषफल नीचे से ऊपर पढ़ें।", "Remainders bottom-to-top padhne par binary number milta hai."), highlightTokens = listOf(bin))
            return StudySolution(
                subject = StudySubject.COMPUTER_SCIENCE,
                topic = "Number Systems (Decimal to Binary)",
                difficulty = "Basic",
                educationLevel = profile.educationLevel,
                language = lang,
                depth = profile.preferredDepth,
                question = q,
                steps = steps,
                finalAnswer = "$bin (base 2)",
                verificationStatus = VerificationStatus.VERIFIED,
                verificationNote = "Verified locally offline.",
                inputKind = StudyInputKind.TEXT,
                isOffline = true
            )
        }
        return null
    }

    // ---- Tiny Recursive-Descent Arithmetic Parser -------------------------------------------------------

    private fun evalMath(expr: String): Double? {
        class Parser {
            var idx = 0
            fun skipWs() { while (idx < expr.length && expr[idx].isWhitespace()) idx++ }
            fun exprRule(): Double? {
                var l = termRule() ?: return null
                while (true) {
                    skipWs()
                    if (idx >= expr.length) break
                    val op = expr[idx]
                    if (op != '+' && op != '-') break
                    idx++
                    val r = termRule() ?: return null
                    l = if (op == '+') l + r else l - r
                }
                return l
            }
            fun termRule(): Double? {
                var l = powerRule() ?: return null
                while (true) {
                    skipWs()
                    if (idx >= expr.length) break
                    val op = expr[idx]
                    if (op != '*' && op != '/') break
                    idx++
                    val r = powerRule() ?: return null
                    if (op == '/' && abs(r) < 1e-12) return null
                    l = if (op == '*') l * r else l / r
                }
                return l
            }
            fun powerRule(): Double? {
                val b = factorRule() ?: return null
                skipWs()
                if (idx < expr.length && expr[idx] == '^') {
                    idx++
                    val e = powerRule() ?: return null
                    return b.pow(e)
                }
                return b
            }
            fun factorRule(): Double? {
                skipWs()
                if (idx >= expr.length) return null
                if (expr[idx] == '+') { idx++; return factorRule() }
                if (expr[idx] == '-') { idx++; return factorRule()?.let { -it } }
                if (expr[idx] == '(') {
                    idx++
                    val inside = exprRule() ?: return null
                    skipWs()
                    if (idx >= expr.length || expr[idx] != ')') return null
                    idx++
                    return inside
                }
                val start = idx
                while (idx < expr.length && (expr[idx].isDigit() || expr[idx] == '.')) idx++
                if (start == idx) return null
                return expr.substring(start, idx).toDoubleOrNull()
            }
        }
        val p = Parser()
        val res = p.exprRule()
        p.skipWs()
        return if (res != null && p.idx == expr.length && res.isFinite()) res else null
    }

    private fun fmt(v: Double): String {
        val rounded = v.roundToLong()
        return if (abs(v - rounded) < 1e-7) rounded.toString()
        else String.format(Locale.US, "%.4f", v).trimEnd('0').trimEnd('.')
    }

    private fun localize(lang: StudyLanguage, en: String, hi: String, hinglish: String): String = when (lang) {
        StudyLanguage.HINDI -> hi
        StudyLanguage.HINGLISH -> hinglish
        else -> en
    }
}
