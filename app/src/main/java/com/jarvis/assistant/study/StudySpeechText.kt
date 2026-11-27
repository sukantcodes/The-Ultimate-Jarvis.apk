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
    private val BN = Words("সমান", "যোগ", "বিয়োগ", "গুণ", "বাই", "ভাগ", "বর্গ", "ঘন", "পাওয়ার", "বর্গমূল", "প্লাস বা মাইনাস", "ছোট বা সমান", "বড় বা সমান", "সমান নয়", "প্রায়", "পাওয়া যায়", "শতাংশ", "ডিগ্রি", "উত্তর হলো")
    private val TA = Words("சமம்", "கூட்டல்", "கழித்தல்", "பெருக்கல்", "வகுத்தல்", "வகுத்தல்", "வர்க்கம்", "கனம்", "அடுக்கு", "வர்க்கமூலம்", "பிளஸ் அல்லது மைனஸ்", "சிறியது அல்லது சமம்", "பெரியது அல்லது சமம்", "சமமில்லை", "தோராயமாக", "தருகிறது", "சதவீதம்", "டிகிரி", "விடை")
    private val TE = Words("సమానం", "కూడిక", "తీసివేత", "గుణకారం", "భాగహారం", "భాగహారం", "వర్గం", "ఘనం", "ఘాతం", "వర్గమూలం", "ప్లస్ లేదా మైనస్", "తక్కువ లేదా సమానం", "ఎక్కువ లేదా సమానం", "సమానం కాదు", "సుమారు", "వస్తుంది", "శాతం", "డిగ్రీలు", "సమాధానం")
    private val MR = Words("बरोबर", "अधिक", "वजा", "गुणिले", "भागिले", "भागिले", "चा वर्ग", "चा घन", "चा घात", "वर्गमूळ", "अधिक किंवा वजा", "पेक्षा लहान किंवा बरोबर", "पेक्षा मोठे किंवा बरोबर", "बरोबर नाही", "सुमारे", "मिळते", "टक्के", "अंश", "उत्तर आहे")
    private val GU = Words("બરાબર", "વત્તા", "ઓછા", "ગુણ્યા", "ભાગ્યા", "ભાગ્યા", "નો વર્ગ", "નો ઘન", "ની ઘાત", "વર્ગમૂળ", "વત્તા અથવા ઓછા", "નાનું અથવા બરાબર", "મોટું અથવા બરાબર", "બરાબર નથી", "આશરે", "મળે છે", "ટકા", "ડિગ્રી", "જવાબ છે")
    private val KN = Words("ಸಮ", "ಕೂಡು", "ಕಳೆ", "ಗುಣಿಸು", "ಭಾಗಿಸು", "ಭಾಗಿಸು", "ವರ್ಗ", "ಘನ", "ಘಾತ", "ವರ್ಗಮೂಲ", "ಪ್ಲಸ್ ಅಥವಾ ಮೈನಸ್", "ಕಡಿಮೆ ಅಥವಾ ಸಮ", "ಹೆಚ್ಚು ಅಥವಾ ಸಮ", "ಸಮವಲ್ಲ", "ಸುಮಾರು", "ನೀಡುತ್ತದೆ", "ಶೇಕಡಾ", "ಡಿಗ್ರಿ", "ಉತ್ತರ")
    private val ML = Words("സമം", "പ്ലസ്", "മൈനസ്", "ഗുണം", "ഹരണം", "ഹരണം", "വർഗ്ഗം", "ഘനം", "ഘാതം", "വർഗ്ഗമൂലം", "പ്ലസ് അല്ലെങ്കിൽ മൈനസ്", "ചെറുത് അല്ലെങ്കിൽ സമം", "വലുത് അല്ലെങ്കിൽ സമം", "സമമല്ല", "ഏകദേശം", "ലഭിക്കുന്നു", "ശതമാനം", "ഡിഗ്രി", "ഉത്തരം")
    private val PA = Words("ਬਰਾਬਰ", "ਜਮ੍ਹਾਂ", "ਘਟਾਓ", "ਗੁਣਾ", "ਬਟਾ", "ਭਾਗ", "ਦਾ ਵਰਗ", "ਦਾ ਘਣ", "ਦੀ ਘਾਤ", "ਵਰਗਮੂਲ", "ਜਮ੍ਹਾਂ ਜਾਂ ਘਟਾਓ", "ਛੋਟਾ ਜਾਂ ਬਰਾਬਰ", "ਵੱਡਾ ਜਾਂ ਬਰਾબર", "ਬਰਾਬਰ ਨਹੀਂ", "ਲੱਗਭੱਗ", "ਮਿਲਦਾ ਹੈ", "ਪ੍ਰਤੀਸ਼ਤ", "ਡਿਗਰੀ", "ਉੱਤਰ ਹੈ")
    private val UR = Words("برابر ہے", "جمع", "منفی", "ضرب", "بٹا", "تقسیم", "مربع", "مکعب", "قوت", "جذر المربع", "جمع یا منفی", "کم یا برابر", "زیادہ یا برابر", "برابر نہیں", "تقریباً", "ملتا ہے", "فیصد", "ڈگری", "جواب ہے")
    private val ES = Words("es igual a", "más", "menos", "por", "sobre", "dividido por", "al cuadrado", "al cubo", "elevado a", "raíz cuadrada de", "más o menos", "menor o igual que", "mayor o igual que", "no es igual a", "aproximadamente", "da", "por ciento", "grados", "La respuesta es")
    private val FR = Words("égale", "plus", "moins", "fois", "sur", "divisé par", "au carré", "au cube", "puissance", "racine carrée de", "plus ou moins", "inférieur ou égal à", "supérieur ou égal à", "différent de", "environ", "donne", "pour cent", "degrés", "La réponse est")
    private val DE = Words("ist gleich", "plus", "minus", "mal", "durch", "geteilt durch", "zum Quadrat", "hoch drei", "hoch", "Quadratwurzel aus", "plus oder minus", "kleiner oder gleich", "größer oder gleich", "ungleich", "ungefähr", "ergibt", "Prozent", "Grad", "Die Antwort ist")
    private val AR = Words("يساوي", "زائد", "ناقص", "ضرب", "على", "مقسوم على", "تربيع", "تكعيب", "أس", "الجذر التربيعي لـ", "زائد أو ناقص", "أصغر من أو يساوي", "أكبر من أو يساوي", "لا يساوي", "تقريباً", "يعطي", "بالمئة", "درجة", "الإجابة هي")

    private fun wordsFor(language: String, sample: String = ""): Words {
        val norm = language.trim().lowercase()
        return when (norm) {
            "hindi", "bhojpuri", "maithili", "nepali", "sanskrit" -> HI
            "hinglish" -> HG
            "bengali", "bangla", "assamese" -> BN
            "tamil" -> TA
            "telugu" -> TE
            "marathi" -> MR
            "gujarati" -> GU
            "kannada" -> KN
            "malayalam" -> ML
            "punjabi" -> PA
            "urdu" -> UR
            "spanish" -> ES
            "french" -> FR
            "german" -> DE
            "arabic" -> AR
            "auto", "" -> detectWordsFromScript(sample)
            else -> EN
        }
    }

    private fun detectWordsFromScript(sample: String): Words {
        for (ch in sample) {
            when (ch) {
                in '\u0900'..'\u097F' -> return HI
                in '\u0980'..'\u09FF' -> return BN
                in '\u0A00'..'\u0A7F' -> return PA
                in '\u0A80'..'\u0AFF' -> return GU
                in '\u0B80'..'\u0BFF' -> return TA
                in '\u0C00'..'\u0C7F' -> return TE
                in '\u0C80'..'\u0CFF' -> return KN
                in '\u0D00'..'\u0D7F' -> return ML
                in '\u0600'..'\u06FF' -> return AR
            }
        }
        return EN
    }

    fun answerPrefix(language: String): String = wordsFor(language).answerIs

    fun verbalize(raw: String, language: String = "english"): String {
        val w = wordsFor(language, raw)
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
