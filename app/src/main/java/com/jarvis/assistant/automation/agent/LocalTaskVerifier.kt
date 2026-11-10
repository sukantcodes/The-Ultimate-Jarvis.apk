package com.jarvis.assistant.automation.agent

import java.util.Locale

/** Conservative local verification for Android settings toggles. */
object LocalTaskVerifier {
    data class ObservedElement(
        val label: String, val isToggle: Boolean, val isChecked: Boolean,
        val centerX: Int, val centerY: Int
    )
    data class Result(val applicable: Boolean, val verified: Boolean, val reason: String)
    private data class ToggleIntent(val targetTokens: Set<String>, val desiredChecked: Boolean)

    private val enableVerbs = setOf("enable", "activate", "turn on", "switch on")
    private val disableVerbs = setOf("disable", "deactivate", "turn off", "switch off")
    private val ignoredTokens = setOf(
        "the", "a", "an", "please", "jarvis", "setting", "settings", "android",
        "in", "under", "from", "on", "off", "enable", "disable", "activate",
        "deactivate", "turn", "switch"
    )

    fun verifyToggle(goal: String, elements: List<ObservedElement>): Result {
        val intent = parseToggleIntent(goal)
            ?: return Result(false, false, "No settings-toggle intent detected")
        val direct = elements.filter { it.isToggle && containsAllTokens(it.label, intent.targetTokens) }
        val toggle = when {
            direct.size == 1 -> direct.single()
            direct.size > 1 -> return Result(true, false, "More than one toggle matches the requested setting; refusing to guess")
            else -> {
                val labels = elements.filter { !it.isToggle && containsAllTokens(it.label, intent.targetTokens) }
                if (labels.size != 1) return Result(
                    true, false,
                    if (labels.isEmpty()) "The requested setting label is not uniquely visible; its state cannot be verified"
                    else "Multiple visible setting labels match the request; refusing to guess"
                )
                val label = labels.single()
                elements.filter {
                    it.isToggle && it.centerX >= label.centerX &&
                        kotlin.math.abs(it.centerY - label.centerY) <= MAX_ROW_DISTANCE_PX
                }.minByOrNull {
                    kotlin.math.abs(it.centerY - label.centerY) * 4 + (it.centerX - label.centerX)
                } ?: return Result(true, false, "The setting label is visible, but its associated switch is not identifiable")
            }
        }
        val matches = toggle.isChecked == intent.desiredChecked
        val requested = if (intent.desiredChecked) "enabled" else "disabled"
        val actual = if (toggle.isChecked) "enabled" else "disabled"
        return Result(
            true, matches,
            if (matches) "Verified locally: the requested setting is $actual"
            else "The requested setting is $actual, but the goal requires it to be $requested"
        )
    }

    private fun parseToggleIntent(goal: String): ToggleIntent? {
        val clean = goal.trim().lowercase(Locale.ROOT)
        val verb = (enableVerbs + disableVerbs).sortedByDescending { it.length }.firstOrNull { phrase ->
            Regex("""(?<![\p{L}\p{N}])${Regex.escape(phrase)}(?![\p{L}\p{N}])""").containsMatchIn(clean)
        } ?: return null
        val pattern = Regex("""(?<![\p{L}\p{N}])${Regex.escape(verb)}(?![\p{L}\p{N}])\s+(?:the\s+)?(.+)$""")
        val raw = pattern.find(clean)?.groupValues?.getOrNull(1)
            ?.replace(Regex("""\s+(?:in|under|from)\s+(?:the\s+)?(?:android\s+)?settings?$"""), "")
            ?.trim().orEmpty()
        val tokens = raw.split(Regex("""[^\p{L}\p{N}]+"""))
            .filter { it.length >= 2 && it !in ignoredTokens }.toSet()
        if (tokens.isEmpty()) return null
        return ToggleIntent(tokens, verb in enableVerbs)
    }

    private fun containsAllTokens(label: String, tokens: Set<String>): Boolean {
        if (tokens.isEmpty()) return false
        val words = label.lowercase(Locale.ROOT)
            .split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotBlank() }.toSet()
        return tokens.all { it in words }
    }

    private const val MAX_ROW_DISTANCE_PX = 120
}
