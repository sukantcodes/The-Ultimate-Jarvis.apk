package com.jarvis.assistant.automation.agent

import java.util.Locale

/**
 * Converts natural-language requests into ordered action outlines.
 * This mapper structures the request; the live agent must resolve targets from fresh
 * device state, perform each action, and verify it before advancing.
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
            """(?i)\b(?:after|in)\s+\d+\s*(?:seconds?|secs?|minutes?|mins?|hours?|hrs?|days?)\b"""
        ).find(source)?.value

        val actionSource = if (trigger != null) source.replace(trigger, " ", ignoreCase = true) else source
        val normalized = actionSource
            .replace(Regex("""(?i)\b(?:and then|then|after that|next)\b"""), " ; ")
            .replace(Regex("""(?i)\s+and\s+(?=(?:open|launch|play|skip|like|comment|copy|send|share|search|find|tap|click|turn|set|call|wait|download|install|delete|save|select|choose|subscribe|follow|unfollow|message|type|scroll|navigate|verify)\b)"""), " ; ")
            .replace(Regex("""[.!?]+"""), " ; ")

        val fragments = normalized.split(
            Regex(
                """;|\n|,\s*(?=(?:open|launch|play|skip|like|comment|copy|send|share|search|find|tap|click|turn|set|call|wait|download|install|delete|save|select|choose|subscribe|follow|unfollow|message|type|scroll|navigate|verify)\b)""",
                RegexOption.IGNORE_CASE
            )
        )
            .map { it.trim().trim('-', ' ') }
            .filter { it.isNotBlank() }

        val actions = fragments.flatMap { expandClause(it) }
        return TaskMap(source, actions, trigger)
    }

    private fun expandClause(fragment: String): List<MappedAction> {
        val text = fragment.lowercase(Locale.ROOT)
        val steps = when {
            "instagram" in text && ("unlike" in text || "liked" in text) ->
                listOf("Open Instagram", "Navigate to liked content", "Observe and process matching items", "Scroll and wait for more items", "Verify list end and results")
            "spotify" in text && Regex("""\b(play|listen to)\b""").containsMatchIn(text) ->
                listOf("Open Spotify", "Search for the requested track", "Select one unique match", "Verify playback")
            "youtube" in text && Regex("""\b(play|watch)\b""").containsMatchIn(text) ->
                listOf("Open YouTube", "Find the exact requested video or song", "Select one uniquely matching result", "Verify playback")
            Regex("""\b(play|watch|listen to)\b""").containsMatchIn(text) ->
                listOf("Find the exact requested song or video", "Select one uniquely matching result", "Verify that the requested media is playing")
            Regex("""\b(skip|dismiss)\b.*\b(ad|advertisement)\b|\b(ad|advertisement)\b.*\b(skip|dismiss)\b""").containsMatchIn(text) ->
                listOf("Wait and observe until the Skip Ad control is actually available", "Activate Skip Ad only when the visible control is present", "Verify that the advertisement was dismissed or playback resumed")
            Regex(
                """\b(?:thumbs up\b|(?:please\s+)?(?:tap|click|press|hit)\s+(?:the\s+)?like\b|like\s+(?:this|the)\s+(?:video|post|song)\b)""",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(fragment) ->
                listOf("Inspect the current like state", "Activate Like only if the item is not already liked", "Verify the liked state")
            Regex("""\b(comment|write a comment|post a comment)\b""").containsMatchIn(text) ->
                listOf("Open the correct comment composer", "Enter the exact comment requested by the user", "Submit the comment only when explicitly requested", "Verify that the comment is visibly posted")
            Regex("""\b(copy|get)\b.*\b(link|url)\b""").containsMatchIn(text) ->
                listOf("Open the media share or link menu", "Copy the exact media link", "Verify that the clipboard contains the expected link")
            Regex("""\b(send|share|forward)\b.*\b(whatsapp|chat|message)\b|\bwhatsapp\b.*\b(send|share|forward)\b""").containsMatchIn(text) ->
                listOf("Open WhatsApp", "Resolve the exact intended recipient", "Open and verify the correct conversation", "Send the requested content only after recipient and content are resolved", "Verify the outgoing message appears in that conversation")
            Regex("""\b(call|phone)\b""").containsMatchIn(text) ->
                listOf("Resolve the intended contact unambiguously", "Initiate the requested call through an Android-supported call action", "Verify the call state or report when Android requires user interaction")
            else -> listOf(fragment)
        }

        val condition = Regex("""(?i)\b(?:when|once|after|if)\b\s+.+$""").find(fragment)?.value?.trim()
        return steps.map { title ->
            MappedAction(
                title = title,
                description = "Resolve the required app, person, or control from fresh context; perform only this action; inspect the result before advancing." +
                    (condition?.let { " Full condition: $it. Do not act until this condition is observed." } ?: ""),
                expectedResult = "Observable evidence that this specific action succeeded",
                condition = condition
            )
        }
    }
}
