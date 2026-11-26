package com.jarvis.assistant.study

/** Turns board text into something a speech engine reads naturally ("x^{2} = 4" becomes "x squared equals 4"). */
object StudySpeechText {

    private class Words(
        val equals: String, val plus: String, val minus: String, val times: String, val over: String, val dividedBy: String,
        val squared: String, val cubed: String, val power: String, val sqrt: String, val pm: String, val leq: String, val geq: String,
        val neq: String, val approx: String, val arrow: String, val percent: String, val degrees: String, val answerIs: String
    )

    private val EN = Words("equals", "plus", "minus", "times", "over", "divided by", "squared", "cubed", "to the power", "square root of",
        "plus or minus", "is less than or equal to", "is greater than or equal to", "is not equal to", "is approximately", "gives", "percent", "degrees", "The answer is")
    private val HI = Words("बराबर", "जमा", "घटा", "गुणा", "बटा", "भाग", "का वर्ग", "का घन", "घात", "वर्गमूल", "धन या ऋण", "छोटा या बराबर", "बड़ा या बराबर", "बराबर नहीं", "लगभग", "से मिलता है", "प्रतिशत", "डिग्री", "उत्तर है")
    private val HG = Words("equal", "plus", "minus", "guna", "upon", "bhaag", "ka square", "ka cube", "power", "square root of", "plus ya minus", "chhota ya barabar", "bada ya barabar", "barabar nahi", "lagbhag", "se milta hai", "percent", "degree", "Answer hai")

    private fun wordsFor(language: String) = when (language.trim().lowercase()) { "hindi" -> HI; "hinglish" -> HG; else -> EN }

    fun answerPrefix(language: String): String = wordsFor(language).answerIs

    fun verbalize(raw: String, language: String = "english"): String {
        val w = wordsFor(language)
        var s = raw
        repeat(4) { s = s.replace(Regex("\\\\d?frac\\s*\\{([^{}]*)\\}\\s*\\{([^{}]*)\\}")) { m -> " ${m.groupValues[1]} ${w.over} ${m.groupValues[2]} " } }
        s = s.replace(Regex("\\\\sqrt\\s*\\{([^{}]*)\\}")) { m -> " ${w.sqrt} ${m.groupValues[1]} " }
        s = s.replace(Regex("\\^\\s*\\{?\\s*2\\s*\\}?"), " ${w.squared} ")
        s = s.replace(Regex("\\^\\s*\\{?\\s*3\\s*\\}?"), " ${w.cubed} ")
        s = s.replace(Regex("\\^\\s*\\{([^{}]*)\\}")) { m -> " ${w.power} ${m.groupValues[1]} " }
        s = s.replace(Regex("\\^\\s*(-?\\w)")) { m -> " ${w.power} ${m.groupValues[1]} " }
        s = s.replace(Regex("_\\s*\\{?([^{}\\s]*)\\}?")) { m -> " ${m.groupValues[1]} " }
        val map = linkedMapOf(
            "\\times" to " ${w.times} ", "\\cdot" to " ${w.times} ", "\\pm" to " ${w.pm} ", "\\leq" to " ${w.leq} ", "\\geq" to " ${w.geq} ",
            "\\neq" to " ${w.neq} ", "\\approx" to " ${w.approx} ", "\\rightarrow" to " ${w.arrow} ", "\\Rightarrow" to " ${w.arrow} ", "\\to" to " ${w.arrow} ",
            "\\Delta" to " delta ", "\\pi" to " pi ", "\\theta" to " theta ", "\\alpha" to " alpha ", "\\beta" to " beta ", "\\gamma" to " gamma ",
            "\\lambda" to " lambda ", "\\mu" to " mu ", "\\omega" to " omega ", "\\sigma" to " sigma ", "\\rho" to " rho ", "\\phi" to " phi ",
            "×" to " ${w.times} ", "÷" to " ${w.dividedBy} ", "≤" to " ${w.leq} ", "≥" to " ${w.geq} ", "≠" to " ${w.neq} ",
            "≈" to " ${w.approx} ", "→" to " ${w.arrow} ", "±" to " ${w.pm} ", "°" to " ${w.degrees} ", "%" to " ${w.percent} ",
            "−" to " ${w.minus} ", "=" to " ${w.equals} ", "+" to " ${w.plus} ", "*" to " ${w.times} "
        )
        for ((k, v) in map) s = s.replace(k, v)
        // a "/" between numbers or letters is a division; a "-" between two operands is a minus
        s = s.replace(Regex("(?<=[\\w)])\\s*/\\s*(?=[\\w(])"), " ${w.dividedBy} ")
        s = s.replace(Regex("(?<=[\\w)])\\s+-\\s+(?=[\\w(])"), " ${w.minus} ")
        s = s.replace(Regex("(?<=[\\w)])-(?=[\\d(])"), " ${w.minus} ")
        s = s.replace(Regex("\\\\[a-zA-Z]+"), " ").replace("\\", " ").replace("{", " ").replace("}", " ").replace("$", " ").replace("^", " ").replace("_", " ")
        return s.replace(Regex("\\s+"), " ").trim()
    }
}
