package com.jarvis.assistant.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingProgressTest {

    @Test
    fun startsAtIntroWithEverythingPending() {
        val p = OnboardingProgress()
        assertEquals(OnboardingStep.INTRO, p.current)
        assertFalse(p.finished)
        OnboardingStep.values().forEach { assertEquals(StepState.PENDING, p.stateOf(it)) }
    }

    @Test
    fun completeRecordsStateAndAdvances() {
        val p = OnboardingProgress().complete(OnboardingStep.INTRO)
        assertEquals(StepState.COMPLETED, p.stateOf(OnboardingStep.INTRO))
        assertEquals(OnboardingStep.PERMISSIONS, p.current)
    }

    @Test
    fun skipRecordsSkippedAndAdvances() {
        val p = OnboardingProgress(current = OnboardingStep.PROFILE).skip(OnboardingStep.PROFILE)
        assertEquals(StepState.SKIPPED, p.stateOf(OnboardingStep.PROFILE))
        assertEquals(OnboardingStep.AI, p.current)
    }

    @Test
    fun finalStepDoesNotAdvancePastTheEnd() {
        val p = OnboardingProgress(current = OnboardingStep.FINAL).complete(OnboardingStep.FINAL)
        assertEquals(OnboardingStep.FINAL, p.current)
    }

    @Test
    fun backStopsAtIntro() {
        assertEquals(OnboardingStep.INTRO, OnboardingProgress().back().current)
        assertEquals(OnboardingStep.AI, OnboardingProgress(current = OnboardingStep.ACCESSIBILITY).back().current)
    }

    @Test
    fun finishMarksUnreachedStepsSkippedAndKeepsCompletedOnes() {
        val p = OnboardingProgress()
            .complete(OnboardingStep.INTRO)
            .complete(OnboardingStep.PERMISSIONS)
            .finish()
        assertTrue(p.finished)
        assertEquals(StepState.COMPLETED, p.stateOf(OnboardingStep.PERMISSIONS))
        assertEquals(StepState.SKIPPED, p.stateOf(OnboardingStep.AI))
        assertEquals(StepState.SKIPPED, p.stateOf(OnboardingStep.VOICE))
        assertEquals(StepState.COMPLETED, p.stateOf(OnboardingStep.FINAL))
    }

    @Test
    fun encodeDecodeRoundTrips() {
        val original = OnboardingProgress()
            .complete(OnboardingStep.INTRO)
            .skip(OnboardingStep.PERMISSIONS)
            .goTo(OnboardingStep.ACCESSIBILITY)
        val decoded = OnboardingProgress.decode(original.encode())
        assertEquals(original, decoded)
    }

    @Test
    fun finishedProgressRoundTrips() {
        val finished = OnboardingProgress().finish()
        assertEquals(finished, OnboardingProgress.decode(finished.encode()))
    }

    @Test
    fun decodeIsTolerantOfGarbage() {
        assertEquals(OnboardingProgress(), OnboardingProgress.decode(null))
        assertEquals(OnboardingProgress(), OnboardingProgress.decode(""))
        assertEquals(OnboardingProgress(), OnboardingProgress.decode("???;;==;c=;s=:,:"))
        val partial = OnboardingProgress.decode("v1;c=nonsense;f=1;s=intro:COMPLETED,bogus:COMPLETED,ai:WEIRD")
        assertEquals(OnboardingStep.INTRO, partial.current)
        assertTrue(partial.finished)
        assertEquals(StepState.COMPLETED, partial.stateOf(OnboardingStep.INTRO))
        assertEquals(StepState.PENDING, partial.stateOf(OnboardingStep.AI))
    }

    @Test
    fun stepIdsAreUniqueAndResolvable() {
        val ids = OnboardingStep.values().map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        OnboardingStep.values().forEach { assertEquals(it, OnboardingStep.fromId(it.id)) }
    }

    @Test
    fun onlyIntroAndFinalAreUnskippable() {
        val locked = OnboardingStep.values().filter { !it.skippable }
        assertEquals(listOf(OnboardingStep.INTRO, OnboardingStep.FINAL), locked)
    }
}

class OnboardingGateTest {
    @Test
    fun newUsersGetTheTutorial() {
        assertEquals(LaunchState.PENDING, OnboardingGate.initialState(legacyWelcomeAlreadySeen = false))
    }

    @Test
    fun existingUsersAreNotInterrupted() {
        assertEquals(LaunchState.DONE, OnboardingGate.initialState(legacyWelcomeAlreadySeen = true))
    }
}
