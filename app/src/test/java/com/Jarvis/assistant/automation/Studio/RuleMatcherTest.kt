package com.jarvis.assistant.automation.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuleMatcherTest {
    private val matcher = RuleMatcher()

    @Test
    fun matchesWholeTranscriptAndCapturesContact() {
        val rule = AutomationRule(
            name = "Call contact",
            triggerPattern = "call {contact}",
            actions = listOf(AutomationAction(RuleActionType.PLACE_CALL))
        )

        val match = matcher.match(rule, "Call Rahul")

        assertEquals("rahul", match?.variables?.get("contact"))
    }

    @Test
    fun rejectsPartialTranscriptMatch() {
        val rule = AutomationRule(
            name = "Call contact",
            triggerPattern = "call {contact}",
            actions = listOf(AutomationAction(RuleActionType.PLACE_CALL))
        )

        assertNull(matcher.match(rule, "please call Rahul now"))
    }

    @Test
    fun rejectsEmptyPlaceholder() {
        val rule = AutomationRule(
            name = "Set volume",
            triggerPattern = "set volume to {level}",
            actions = listOf(AutomationAction(RuleActionType.SET_VOLUME))
        )

        assertNull(matcher.match(rule, "set volume to   "))
    }

    @Test
    fun rejectsDuplicatePlaceholderNames() {
        val rule = AutomationRule(
            name = "Repeat variable",
            triggerPattern = "{name} then {name}",
            actions = listOf(AutomationAction(RuleActionType.SPEAK))
        )

        assertNull(matcher.match(rule, "hello then hello"))
    }
}
