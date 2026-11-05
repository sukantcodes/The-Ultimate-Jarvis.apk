package com.jarvis.assistant.skills

/** A skill read from a SKILL.md style file: a name, a one-line trigger description and the step-by-step body. */
data class ParsedSkill(val name: String, val description: String, val body: String)

/**
 * Reads the playbook format used by the skill store:
 *
 *     ---
 *     name: my-skill
 *     description: One line saying WHEN to use it
 *     ---
 *     # Title
 *     steps...
 *
 * No Android classes are used here so the rules can be unit-tested.
 */
object SkillParser {
    const val MAX_DESCRIPTION = 140
    const val MAX_BODY = 12000
    private val NAME_RULE = Regex("^[a-z0-9][a-z0-9-]{1,39}$")

    fun isValidName(name: String): Boolean = NAME_RULE.matches(name)

    /** Returns null when the text has no valid front matter, so a broken download is never installed. */
    fun parse(raw: String): ParsedSkill? {
        val lines = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var start = 0
        while (start < lines.size && lines[start].isBlank()) start++
        if (start >= lines.size || lines[start].trim() != "---") return null
        var end = -1
        for (i in start + 1 until lines.size) {
            if (lines[i].trim() == "---") { end = i; break }
        }
        if (end < 0) return null

        var name = ""
        var description = ""
        var i = start + 1
        while (i < end) {
            val line = lines[i]
            i++
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim().lowercase()
            var value = line.substring(colon + 1).trim()
            // YAML block scalars (">", ">-", "|", "|-"): the text is on the indented lines that follow.
            if (value.isNotEmpty() && value.all { it == '>' || it == '|' || it == '-' || it == '+' }) {
                val parts = ArrayList<String>()
                while (i < end && (lines[i].isBlank() || lines[i].first().isWhitespace())) {
                    if (lines[i].isNotBlank()) parts.add(lines[i].trim())
                    i++
                }
                value = parts.joinToString(" ")
            }
            value = value.trim().trim('"', '\'').trim()
            if (key == "name") name = value
            if (key == "description") description = value.replace(Regex("\\s+"), " ")
        }
        if (!isValidName(name)) return null
        if (description.isBlank()) return null

        val body = lines.subList(end + 1, lines.size).joinToString("\n").trim()
        if (body.isEmpty()) return null
        return ParsedSkill(
            name = name,
            description = shorten(description, MAX_DESCRIPTION),
            body = cutBody(body)
        )
    }

    /** Shortens at a word boundary and marks the cut, so a long description never ends mid-word. */
    internal fun shorten(text: String, max: Int): String {
        if (text.length <= max) return text
        val head = text.take(max - 1)
        val space = head.lastIndexOf(' ')
        val clean = if (space > max / 2) head.substring(0, space) else head
        return clean.trimEnd(',', ';', ':', '-', ' ') + "\u2026"
    }

    /** Caps the playbook length, preferring to stop at the end of a line. */
    private fun cutBody(body: String): String {
        if (body.length <= MAX_BODY) return body
        val head = body.take(MAX_BODY)
        val newline = head.lastIndexOf('\n')
        return if (newline > MAX_BODY * 8 / 10) head.substring(0, newline).trimEnd() else head
    }

    /** "owner/repo" with letters, digits, dots, dashes and underscores only. */
    fun isValidRepo(repo: String): Boolean =
        Regex("^[A-Za-z0-9._-]{1,60}/[A-Za-z0-9._-]{1,100}$").matches(repo)

    /** Skill files must live under skills/ and be plain .md names, so an index can never point elsewhere. */
    fun isSafeSkillPath(path: String): Boolean =
        Regex("^skills/[A-Za-z0-9._-]{1,80}\\.md$").matches(path) && !path.contains("..")
}
