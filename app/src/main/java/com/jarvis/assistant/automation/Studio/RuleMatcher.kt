package com.jarvis.assistant.automation.studio

import java.util.Locale

/**
 * Matches simple voice templates such as:
 *   "call {contact}"
 *   "set volume to {level}"
 *   "message {contact} {message}"
 *
 * Templates are anchored to the whole normalized transcript to avoid accidental
 * partial matches. Placeholder captures are non-greedy except the final placeholder.
 */
class RuleMatcher(
    private val maxTranscriptLength: Int = 1_000,
    private val maxTemplateLength: Int = 250
) {
    fun match(rule: AutomationRule, transcript: String): RuleMatch? {
        if (!rule.enabled || rule.triggerType != RuleTriggerType.VOICE_COMMAND) return null
        val input = normalize(transcript)
        val template = normalize(rule.triggerPattern)
        if (input.isBlank() || template.isBlank()) return null
        if (input.length > maxTranscriptLength || template.length > maxTemplateLength) return null

        val compiled = compileTemplate(template) ?: return null
        val found = compiled.regex.matchEntire(input) ?: return null
        val values = linkedMapOf<String, String>()
        compiled.names.forEachIndexed { index, name ->
            val value = found.groups[index + 1]?.value?.trim().orEmpty()
            if (value.isBlank()) return null
            values[name] = value
        }
        return RuleMatch(rule, values, input)
    }

    private data class CompiledTemplate(val regex: Regex, val names: List<String>)

    private fun compileTemplate(template: String): CompiledTemplate? {
        val names = mutableListOf<String>()
        val regex = StringBuilder("^")
        var index = 0
        while (index < template.length) {
            if (template[index] == '{') {
                val close = template.indexOf('}', index + 1)
                if (close < 0) return null
                val name = template.substring(index + 1, close).trim()
                if (!name.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,39}"))) return null
                if (name in names) return null
                names += name
                // All captures are bounded. The final variable may contain spaces.
                regex.append(if (close == template.lastIndex) "(.{1,200})" else "(.{1,100}?)")
                index = close + 1
            } else {
                val start = index
                while (index < template.length && template[index] != '{') index++
                regex.append(Regex.escape(template.substring(start, index)))
            }
        }
        regex.append("$")
        return try {
            CompiledTemplate(Regex(regex.toString(), RegexOption.IGNORE_CASE), names)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun normalize(value: String): String =
        value.trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("[\\p{Punct}&&[^{}]]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
