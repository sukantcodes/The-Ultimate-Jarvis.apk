package com.jarvis.assistant.automation.typing

import java.util.Locale

/** How the requested text relates to what is already in the field. */
enum class TypeMode {
    /** Keep existing content and add the text after it. */
    APPEND,
    /** Replace everything in the field with the text. */
    REPLACE,
    /** Empty the field; no text is typed. */
    CLEAR,
    /** "Continue typing from where you stopped" / bare "type here": resume the pending typing task. */
    CONTINUE
}

/**
 * A parsed typing request. [text] is the user's exact wording (original case, punctuation and script),
 * never lower-cased, trimmed of outer whitespace only. [needsText] is true when the user referred to text
 * they did not actually supply ("type this in WhatsApp") so the caller must ask instead of guessing.
 */
data class TypeIntent(
    val text: String,
    val app: String? = null,
    val mode: TypeMode = TypeMode.APPEND,
    val needsText: Boolean = false
) {
    /** True when the request names an app to open first (one continuous open → type task). */
    val opensApp: Boolean get() = !app.isNullOrBlank()
}

/**
 * Understands English and natural Hinglish typing commands. It works on the ORIGINAL utterance because the
 * generic [com.jarvis.assistant.automation.command.CommandParser] lower-cases its input, which would corrupt
 * the text the user asked to be typed.
 *
 * Returns null for anything that is not clearly a typing request so existing commands (search, play, send
 * message, open app) keep their current behaviour.
 */
object TypeCommandParser {

    private val WAKE_PREFIX = Regex("(?is)^(?:hey|hi|hello|ok|okay)?\\s*jarvis[,\\s]+")
    private val POLITE_PREFIX = Regex("(?is)^(?:please\\s+|can you\\s+|could you\\s+|just\\s+)+")
    private val PRONOUNS = setOf("this", "it", "that", "ye", "yeh", "isse", "isko", "is")

    /**
     * Words that identify an app after "in/into/on". Only used to decide where the TEXT ends when a user says
     * "type hello in whatsapp"; typing itself never depends on this list and works in any app.
     */
    private val KNOWN_APPS = setOf(
        "whatsapp", "telegram", "chrome", "youtube", "gmail", "instagram", "facebook", "messenger", "twitter",
        "x", "linkedin", "snapchat", "signal", "slack", "discord", "reddit", "spotify", "notes", "keep",
        "google keep", "messages", "sms", "docs", "google docs", "drive", "browser", "firefox", "edge",
        "brave", "amazon", "flipkart", "swiggy", "zomato", "jarvis", "chatgpt", "gemini", "outlook", "teams"
    )

    private const val VERB = "(?:type|write|enter|input|likho|likh\\s+do|likh\\s+dena)"
    private const val JOIN = "(?:and|then|aur|phir|fir|and\\s+then|aur\\s+phir)"

    fun parse(raw: String): TypeIntent? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        s = WAKE_PREFIX.replace(s, "").trim()
        s = POLITE_PREFIX.replace(s, "").trim()
        if (s.isEmpty()) return null

        parseClear(s)?.let { return it }
        parseContinue(s)?.let { return it }
        parseOpenThenType(s)?.let { return it }
        parseReplace(s)?.let { return it }
        parseTypeInApp(s)?.let { return it }
        parseSearchBox(s)?.let { return it }
        parsePlain(s)?.let { return it }
        return null
    }

    // --- individual shapes -------------------------------------------------------------------------

    private fun parseClear(s: String): TypeIntent? {
        val en = Regex("(?is)^(?:clear|erase|empty|delete)\\s+(?:this|the|current)?\\s*(?:text\\s*)?(?:field|box|text|input|search\\s*(?:box|bar|field))$")
        val hi = Regex("(?is)^(?:ye|yeh|is|isse|this)?\\s*(?:field|text|box)?\\s*(?:ko\\s+)?(?:clear|saaf|khali|mita|hata)\\s*(?:karo|kar\\s+do|kardo|do)?$")
        return if (en.matches(s) || (hi.matches(s) && s.contains(Regex("(?is)clear|saaf|khali|mita|hata")) &&
                s.contains(Regex("(?is)field|text|box")))) {
            TypeIntent(text = "", mode = TypeMode.CLEAR)
        } else null
    }

    private fun parseContinue(s: String): TypeIntent? {
        val lower = s.lowercase(Locale.ROOT).trimEnd('.', '!', '?', ',', ' ')
        val cont = Regex("^(?:continue|resume|keep)\\s+typing(?:\\s+from\\s+where\\s+you\\s+(?:stopped|left\\s+off))?$")
        val hi = Regex("^(?:typing\\s+continue\\s+karo|jahan\\s+ruke\\s+the\\s+wahan\\s+se\\s+(?:type\\s+karo|likho)|aage\\s+type\\s+karo|type\\s+here|yahan\\s+type\\s+karo|yaha\\s+type\\s+karo|yahan\\s+likho)$")
        return if (cont.matches(lower) || hi.matches(lower)) TypeIntent(text = "", mode = TypeMode.CONTINUE) else null
    }

    /** "open chrome and type android development", Hinglish "chrome kholo aur android development type karo". */
    private fun parseOpenThenType(s: String): TypeIntent? {
        Regex("(?is)^(?:open|launch|start)\\s+(?:the\\s+)?(.+?)\\s+(?:app\\s+)?$JOIN\\s+$VERB\\s+(.+)$")
            .find(s)?.let { m ->
                val app = cleanApp(m.groupValues[1])
                val body = m.groupValues[2]
                if (app.isNotBlank() && body.isNotBlank()) return build(app, body)
            }
        Regex("(?is)^(?:open|launch|start)\\s+(?:the\\s+)?(.+?)\\s+(?:app\\s+)?$JOIN\\s+(.+?)\\s+(?:type\\s+(?:karo|kar\\s+do|kardo)|likho|likh\\s+do)$")
            .find(s)?.let { m ->
                val app = cleanApp(m.groupValues[1])
                val body = m.groupValues[2]
                if (app.isNotBlank() && body.isNotBlank()) return build(app, body, bodyAlreadyText = true)
            }
        // Hinglish: "<app> kholo aur <text> type karo / likho"
        Regex("(?is)^(.+?)\\s+(?:kholo|khol\\s+do|kholna|open\\s+karo|open\\s+kar\\s+do)\\s+$JOIN\\s+(.+?)\\s+(?:type\\s+(?:karo|kar\\s+do|kardo)|likho|likh\\s+do)$")
            .find(s)?.let { m ->
                val app = cleanApp(m.groupValues[1])
                val body = m.groupValues[2]
                if (app.isNotBlank() && body.isNotBlank()) return build(app, body, bodyAlreadyText = true)
            }
        // Hinglish: "<app> kholo aur type karo <text>"
        Regex("(?is)^(.+?)\\s+(?:kholo|khol\\s+do|open\\s+karo)\\s+$JOIN\\s+type\\s+(?:karo|kar\\s+do)\\s+(.+)$")
            .find(s)?.let { m ->
                val app = cleanApp(m.groupValues[1])
                val body = m.groupValues[2]
                if (app.isNotBlank() && body.isNotBlank()) return build(app, body, bodyAlreadyText = true)
            }
        return null
    }

    private fun parseReplace(s: String): TypeIntent? {
        val patterns = listOf(
            Regex("(?is)^(?:replace|change|overwrite)\\s+(?:the\\s+)?(?:existing\\s+|current\\s+)?(?:text|content)\\s+(?:with|to)\\s+(.+)$"),
            Regex("(?is)^select\\s+all\\s+(?:and|then)\\s+$VERB\\s+(.+)$"),
            Regex("(?is)^(?:fill|fill\\s+in|fill\\s+up)\\s+(?:this\\s+|the\\s+)?(?:field|box|input)\\s+with\\s+(.+)$"),
            Regex("(?is)^(?:purana\\s+text\\s+hata\\s+ke|purane\\s+text\\s+ko\\s+badal\\s+ke)\\s+$VERB\\s*(?:karo)?\\s+(.+)$")
        )
        for (p in patterns) {
            val m = p.find(s) ?: continue
            val body = m.groupValues[1]
            if (body.isNotBlank()) return TypeIntent(text = clean(body), mode = TypeMode.REPLACE)
        }
        return null
    }

    /** "type hello in whatsapp", "write this in telegram". The app is only split off when it is a recognised app. */
    private fun parseTypeInApp(s: String): TypeIntent? {
        val m = Regex("(?is)^$VERB\\s+(.+?)\\s+(?:in|into|on|inside)\\s+(?:the\\s+)?([\\p{L}\\p{N}][\\p{L}\\p{N} .&_-]{0,30}?)(?:\\s+app)?$")
            .find(s) ?: return null
        val body = m.groupValues[1]
        val app = cleanApp(m.groupValues[2])
        if (app.lowercase(Locale.ROOT) !in KNOWN_APPS) return null
        return build(app, body, bodyAlreadyText = true)
    }

    /** "enter android in the search box" - types into the search field of the current screen. */
    private fun parseSearchBox(s: String): TypeIntent? {
        val m = Regex("(?is)^$VERB\\s+(.+?)\\s+(?:in|into)\\s+(?:the\\s+)?search\\s*(?:box|bar|field)$").find(s) ?: return null
        val body = m.groupValues[1]
        if (body.isBlank()) return null
        return TypeIntent(text = clean(body), mode = TypeMode.APPEND)
    }

    private fun parsePlain(s: String): TypeIntent? {
        Regex("(?is)^(?:type|likho|likh\\s+do|likh\\s+dena)\\s+(?:karo\\s+)?(.+)$").find(s)?.let { m ->
            val body = m.groupValues[1]
            // "type karo" alone is the bare Hinglish verb; "type of music" is not a typing request.
            if (body.isNotBlank() && !isBareVerb(body) && !body.startsWith("of ", ignoreCase = true)) return build(null, body)
        }
        Regex("(?is)^(.+?)\\s+(?:type\\s+(?:karo|kar\\s+do|kardo)|likho|likh\\s+do|likh\\s+dena)$").find(s)?.let { m ->
            val body = m.groupValues[1]
            if (body.isNotBlank()) return build(null, body, bodyAlreadyText = true)
        }
        return null
    }

    // --- helpers -----------------------------------------------------------------------------------

    private fun isBareVerb(body: String): Boolean =
        body.trim().lowercase(Locale.ROOT).trimEnd('.', '!', '?') in setOf("here", "yahan", "yaha", "karo", "kar do")

    @Suppress("UNUSED_PARAMETER")
    private fun build(app: String?, body: String, bodyAlreadyText: Boolean = false): TypeIntent {
        val text = clean(body)
        val lower = text.lowercase(Locale.ROOT).trimEnd('.', '!', '?', ',', ' ')
        val pronoun = lower in PRONOUNS
        return TypeIntent(
            text = if (pronoun) "" else text,
            app = app?.takeIf { it.isNotBlank() },
            mode = TypeMode.APPEND,
            needsText = pronoun
        )
    }

    /** Trim only outer whitespace and one pair of wrapping quotes; never touch the inner text. */
    private fun clean(body: String): String {
        var t = body.trim()
        if (t.length >= 2) {
            val pairs = listOf('"' to '"', '\'' to '\'', '“' to '”', '‘' to '’')
            for ((o, c) in pairs) {
                if (t.first() == o && t.last() == c) { t = t.substring(1, t.length - 1).trim(); break }
            }
        }
        return t
    }

    private fun cleanApp(raw: String): String =
        raw.trim().replace(Regex("(?is)\\s+app$"), "").replace(Regex("(?is)^the\\s+"), "").trim()
}
