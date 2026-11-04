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
    const val MAX_BODY = 6000
    private val NAME_RULE = Regex("^[a-z0-9][a-z0-9-]{1,39}$")

    fun isValidName(name: String): Boolean = NAME_RULE.matches(name)

    /** Returns null when the text has no valid front matter, so a broken download is never installed. */
    fun parse(raw: String): ParsedSkill? {
        val lines = raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')
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
        for (i in start + 1 until end) {
            val line = lines[i]
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim().trim('"', '\'')
            if (key == "name") name = value
            if (key == "description") description = value
        }
        if (!isValidName(name)) return null
        if (description.isBlank()) return null

        val body = lines.subList(end + 1, lines.size).joinToString("\n").trim()
        if (body.isEmpty()) return null
        return ParsedSkill(
            name = name,
            description = description.take(MAX_DESCRIPTION),
            body = body.take(MAX_BODY)
        )
    }

    /** "owner/repo" with letters, digits, dots, dashes and underscores only. */
    fun isValidRepo(repo: String): Boolean =
        Regex("^[A-Za-z0-9._-]{1,60}/[A-Za-z0-9._-]{1,100}$").matches(repo)

    /** Skill files must live under skills/ and be plain .md names, so an index can never point elsewhere. */
    fun isSafeSkillPath(path: String): Boolean =
        Regex("^skills/[A-Za-z0-9._-]{1,80}\\.md$").matches(path) && !path.contains("..")
}
