package com.jarvis.assistant.study

/**
 * Pure-Kotlin router determining whether an incoming typed or spoken prompt on the Study Board
 * is an answer to an active checkup quiz, a follow-up doubt about the current explanation,
 * or a fresh new lesson/question.
 */
object StudyIntentRouter {

    enum class Intent { NEW_QUESTION, DOUBT, QUIZ_ANSWER }

    private val EXPLICIT_NEW = Regex(
        "^(?:please\\s+)?(?:new\\s+question|next\\s+question|another\\s+(?:question|problem|topic)|fresh\\s+(?:question|topic)|different\\s+(?:question|topic)|start\\s+over\\s+with|forget\\s+(?:that|this|previous)|naya\\s+sawal|agla\\s+sawal|dusra\\s+sawal)\\b",
        RegexOption.IGNORE_CASE
    )

    private val STANDALONE_NEW_START = Regex(
        "^(?:please\\s+|can\\s+you\\s+|could\\s+you\\s+|now\\s+)?(?:solve|evaluate|calculate|simplify|factori[sz]e|differentiate|integrate|plot|graph|derive|prove|define|explain|teach\\s+me|draw(?:\\s+a)?(?:\\s+labelled|\\s+labeled|\\s+neat)?(?:\\s+diagram)?(?:\\s+of)?|sketch|write\\s+(?:a\\s+)?(?:program|code|function|algorithm))\\b",
        RegexOption.IGNORE_CASE
    )

    private val DOUBT_ANCHORS = Regex(
        "\\b(this|that|it|here|above|earlier|previous|step\\s*\\d+|first\\s+step|second\\s+step|third\\s+step|last\\s+step|final\\s+step|didn'?t\\s+understand|don'?t\\s+understand|not\\s+clear|confused|clarify|simpler|memory\\s+trick|why\\s+did|why\\s+is|why\\s+not|how\\s+did|how\\s+come|samajh\\s+nahi|kya\\s+hua|ise\\s+|is\\s+step|ye\\s+kaise)\\b",
        RegexOption.IGNORE_CASE
    )

    private val QUIZ_ANSWER_HINT = Regex(
        "^(?:(?:my\\s+)?answer\\s*(?:is|=|:)?\\s*.+|(?:option|choice)\\s+[a-d1-4]|\\(?[a-d]\\)?|(?:is\\s+it|maybe|i\\s+think)\\s+.+)$",
        RegexOption.IGNORE_CASE
    )

    private val STOP_WORDS = setOf(
        "what", "when", "where", "which", "whom", "whose", "why", "how", "explain", "tell", "show", "give",
        "draw", "solve", "find", "make", "with", "from", "into", "about", "that", "this", "the", "and",
        "for", "are", "was", "were", "can", "could", "would", "should", "will", "please", "diagram",
        "labelled", "labeled", "question", "step", "steps", "example", "detail", "detailed"
    )

    fun classify(
        query: String,
        hasLesson: Boolean,
        activeQuestion: String = "",
        activeTopic: String = "",
        explicitMode: String = "auto",
        quizActive: Boolean = false
    ): Intent {
        val q = query.trim()
        if (q.isEmpty()) return Intent.NEW_QUESTION

        val mode = explicitMode.trim().lowercase()
        if (mode == "quiz") return Intent.QUIZ_ANSWER
        if (mode == "doubt" && hasLesson) return Intent.DOUBT
        if (mode == "new") return Intent.NEW_QUESTION

        if (quizActive && !STANDALONE_NEW_START.containsMatchIn(q) && !EXPLICIT_NEW.containsMatchIn(q)) {
            if (QUIZ_ANSWER_HINT.matches(q)) return Intent.QUIZ_ANSWER
            if (q.length <= 24 && StudyExpression.evaluate(q.substringAfterLast('=').trim()) != null && !q.contains('?')) {
                return Intent.QUIZ_ANSWER
            }
        }

        if (!hasLesson) return Intent.NEW_QUESTION
        if (EXPLICIT_NEW.containsMatchIn(q)) return Intent.NEW_QUESTION
        if (DOUBT_ANCHORS.containsMatchIn(q)) return Intent.DOUBT

        // Contextual follow-up without an explicit unrelated new problem
        if (!STANDALONE_NEW_START.containsMatchIn(q)) {
            return Intent.DOUBT
        }

        // Query starts with a teaching verb ("solve...", "draw...", "explain...") — check whether it continues the active topic
        val qKeywords = keywords(q)
        val lessonKeywords = keywords("$activeQuestion $activeTopic")
        val sharesTopic = qKeywords.any { it in lessonKeywords }
        val referencesVisualOfCurrent = Regex("\\bdiagram\\b", RegexOption.IGNORE_CASE).containsMatchIn(q) &&
            Regex("\\b(for\\s+this|of\\s+this|of\\s+it|show\\s+this|clarify)\\b", RegexOption.IGNORE_CASE).containsMatchIn(q)

        return if (sharesTopic || referencesVisualOfCurrent) Intent.DOUBT else Intent.NEW_QUESTION
    }

    private fun keywords(text: String): Set<String> =
        text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .asSequence()
            .map { it.trim() }
            .filter { it.length >= 3 && it !in STOP_WORDS }
            .toSet()
}
