package com.jarvis.assistant.automation.agent

import java.util.Locale

/**
 * Converts a natural-language request into an ordered, dependency-linked action outline.
 * This is a local structural mapper, not a claim that every action is executable:
 * the live agent must resolve each target from current UI/device state and verify it.
 */
object UniversalTaskMapper {
    data class MappedAction(
        val title: String,
        val description: String,
        val expectedResult: String,
        val condition: String? = null
    )

    data class TaskMap(
        val originalGoal: String,
        val actions: List<MappedAction>,
        val scheduledTrigger: String? = null
    )

    fun map(goal: String): TaskMap {
        val source = goal.trim()
        if (source.isBlank()) return TaskMap(source, emptyList())

        val trigger = Regex(
            """(?i)\\b(?:after|in)\\s+\\d+\\s*(?:seconds?|secs?|minutes?|mins?|hours?|hrs?|days?)\\b"""
        ).find(source)?.value

        val normalized = source
            .replace(Regex("""(?i)\\b(?:and then|then|after that|next)\\b"""), " ; ")
            .replace(Regex("""(?i)\\b(?:once|when)\\s+(?:the\\s+)?(?:skip\\s+)?button\\s+(?:appears|is available|becomes available)"""), " ; when the requested control becomes available ; ")
            .replace(Regex("""[.!?]+"""), " ; ")
        val fragments = normalized.split(';', ',', '\\n')
            .map { it.trim().trim('-', ' ') }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.ROOT) }

        val actions = fragments.mapIndexed { index, fragment ->
            val title = fragment.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                .take(180)
            val conditional = Regex("""(?i)\\b(when|once|after|if)\\b""").find(fragment)?.value
            MappedAction(
                title = title,
                description = "Resolve the required app, person, control, or event from fresh context; perform only this action; then inspect the resulting state.",
                expectedResult = "Observable evidence that this specific action succeeded",
                condition = conditional
            )
        }
        return TaskMap(source, actions, trigger)
    }
}
