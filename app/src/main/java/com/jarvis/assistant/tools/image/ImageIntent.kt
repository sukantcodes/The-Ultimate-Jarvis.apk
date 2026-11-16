package com.jarvis.assistant.tools.image

/**
 * Decides, from the words the person typed, whether they are asking for a new picture or a change to the picture
 * they attached. Understands English, Hindi, Hinglish and Bengali. Deliberately careful: a request to write code
 * about images, or a question about an attached picture, is not an image request and goes to the normal chat.
 */
object ImageIntent {
    enum class Kind { NONE, GENERATE, EDIT }

    private val NOUNS = listOf(
        "image", "images", "picture", "pictures", "pic", "pics", "photo", "photos", "photograph", "logo", "logos", "wallpaper", "wallpapers",
        "poster", "illustration", "artwork", "drawing", "sketch", "painting", "avatar", "icon", "thumbnail", "banner", "meme", "sticker", "portrait",
        "tasveer", "tasvir", "tasbir", "chhobi", "chobi", "dp", "pfp",
        "इमेज", "तस्वीर", "फोटो", "फ़ोटो", "चित्र", "लोगो", "वॉलपेपर", "पोस्टर", "ছবি", "লোগো", "ওয়ালপেপার", "চিত্র"
    )
    private val CREATE_VERBS = listOf(
        "generate", "create", "make", "draw", "design", "paint", "produce", "render", "imagine", "sketch", "give me", "show me",
        "bana", "banao", "banado", "banade", "banaiye", "banao na", "bnao", "bna do", "dikhao",
        "बना", "बनाओ", "बनाइए", "बनादो", "दिखाओ", "आँक", "आंक", "বানাও", "বানিয়ে", "আঁকো", "এঁকে", "তৈরি", "দেখাও"
    )
    private val EDIT_WORDS = listOf(
        "edit", "change", "remove", "delete", "erase", "add", "replace", "swap", "turn", "convert", "transform", "enhance", "improve", "upscale",
        "restyle", "colorize", "colorise", "recolor", "recolour", "blur", "retouch", "brighten", "darken", "sharpen", "cartoon", "anime", "ghibli",
        "background", "filter", "put", "place", "wear", "outfit", "hair", "sky", "fix", "clean", "restore", "remaster", "cutout", "cut out",
        "make it", "make this", "make the", "make him", "make her", "make them", "make me", "look like", "style",
        "badal", "badlo", "badal do", "hata", "hatao", "laga", "lagao", "kar do", "kardo", "karo", "sudhar", "saaf", "rang",
        "बदल", "हटा", "लगा", "कर दो", "करदो", "सुधार", "रंग", "बैकग्राउंड",
        "বদলাও", "সরাও", "পরিবর্তন", "লাগাও", "মুছে", "ব্যাকগ্রাউন্ড"
    )
    private val QUESTION_START = Regex(
        """^\s*(what|who|where|when|why|which|how|is|are|does|do you|can you tell|describe|explain|analy[sz]e|summari[sz]e|read|extract|ocr|translate|tell me|identify|count|kya|kaun|kab|kaha|kahan|kyun|kaise|batao|bata|क्या|कौन|कब|कहाँ|क्यों|कैसे|बताओ|কি|কী|কে|কেন|কিভাবে)\b""",
        RegexOption.IGNORE_CASE
    )
    private val CODE_WORDS = Regex(
        """\b(code|coding|script|function|program|api|library|component|widget|slider|gallery|upload|picker|imageview|bug|error|exception|html|css|javascript|python|kotlin|java|react|android|app|application|website|webpage|web page|server|database|prompt)\b""",
        RegexOption.IGNORE_CASE
    )
    private val STRONG_GENERATE = Regex(
        """\b(image|picture|photo|pic|logo|wallpaper|poster|illustration|drawing|painting|portrait|avatar|sticker|meme|icon|banner|thumbnail)s?\s+(of|for|showing|with|where)\b""",
        RegexOption.IGNORE_CASE
    )

    fun classify(text: String, hasImageAttachment: Boolean): Kind {
        val t = text.trim()
        if (t.isEmpty() || t.length > 1500) return Kind.NONE
        val lower = t.lowercase()
        if (hasImageAttachment) {
            // A question about the picture ("what is this?") stays a question.
            if (QUESTION_START.containsMatchIn(lower)) return Kind.NONE
            return if (EDIT_WORDS.any { containsWord(lower, it) }) Kind.EDIT else Kind.NONE
        }
        if (QUESTION_START.containsMatchIn(lower)) return Kind.NONE
        val hasNoun = NOUNS.any { containsWord(lower, it) }
        if (!hasNoun) return Kind.NONE
        val hasVerb = CREATE_VERBS.any { containsWord(lower, it) }
        if (!hasVerb) return Kind.NONE
        if (CODE_WORDS.containsMatchIn(lower) && !STRONG_GENERATE.containsMatchIn(lower)) return Kind.NONE
        if (lower.startsWith("how to") || lower.startsWith("how do") || lower.startsWith("kaise")) return Kind.NONE
        return Kind.GENERATE
    }

    /** Whole-word (or whole-phrase) match that also works for scripts without spaces-delimited Latin word edges. */
    private fun containsWord(text: String, word: String): Boolean {
        var from = 0
        while (true) {
            val i = text.indexOf(word, from)
            if (i < 0) return false
            val before = if (i == 0) ' ' else text[i - 1]
            val after = if (i + word.length >= text.length) ' ' else text[i + word.length]
            val latin = word.all { it.code < 0x250 }
            if (!latin || (!before.isLetterOrDigit() && !after.isLetterOrDigit())) return true
            // Allow a plural "s" or a Hindi/Bengali suffix directly after a Latin noun ("photos", "logos").
            if (latin && !before.isLetterOrDigit() && after == 's' && (i + word.length + 1 >= text.length || !text[i + word.length + 1].isLetterOrDigit())) return true
            from = i + 1
        }
    }
}
