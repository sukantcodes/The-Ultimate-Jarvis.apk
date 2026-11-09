package com.jarvis.assistant.automation.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class AgentTaskEngineTest {

    @Test
    fun instagramPlanHasOrderedDependenciesAndOnlyVerifiedStepsAdvance() {
        val context = AgentTaskEngine.begin("Instagram unlike all my liked reels", "unit-test-plan")
        val initial = AgentTaskEngine.taskState.value
        assertNotNull(initial)
        assertEquals("Internal task map created", initial!!.steps.first().title)
        assertEquals(TaskStepStatus.COMPLETED, initial.steps.first().status)
        assertEquals("Open Instagram", initial.steps.first { it.stepId == initial.currentStepId }.title)

        assertFalse(AgentTaskEngine.completeStepByTitle("Navigate to liked content", "page seen", verified = true))
        assertTrue(AgentTaskEngine.completeStepByTitle("Open Instagram", "foreground verified", verified = true))
        assertTrue(AgentTaskEngine.completeStepByTitle("Navigate to liked content", "liked list identified", verified = true))
        assertEquals("Observe and process matching items", AgentTaskEngine.taskState.value?.steps
            ?.first { it.stepId == AgentTaskEngine.taskState.value?.currentStepId }?.title)
        assertEquals(context.steps.size, AgentTaskEngine.taskState.value?.steps?.size)
    }

    @Test
    fun completionRequiresEvidenceAndBulkIdentitiesAreDeduplicated() {
        val context = AgentTaskEngine.begin("Instagram unlike all liked reels", "unit-test-completion")
        assertFalse(AgentTaskEngine.finish(AgentTaskEngine.State.COMPLETED))
        assertTrue(AgentTaskEngine.markItemProcessed("reel:one", verified = true))
        assertFalse(AgentTaskEngine.markItemProcessed("reel:one", verified = true))
        assertFalse(AgentTaskEngine.markItemProcessed("reel:two", verified = false))
        assertTrue(AgentTaskEngine.completeStepByTitle("Open Instagram", "foreground verified", verified = true))
        assertTrue(AgentTaskEngine.completeStepByTitle("Navigate to liked content", "liked list identified", verified = true))
        assertTrue(AgentTaskEngine.completeStepByTitle("Observe and process matching items", "one item verified", verified = true))
        assertTrue(AgentTaskEngine.completeStepByTitle("Scroll and wait for more items", "stable end observed", verified = true))
        assertTrue(AgentTaskEngine.completeStepByTitle("Verify list end and results", "final evidence recorded", verified = true))
        AgentTaskEngine.recordCompletionEvidence(AgentTaskEngine.CompletionEvidence.END_REACHED, "stable end evidence")
        assertTrue(AgentTaskEngine.finish(AgentTaskEngine.State.COMPLETED))
        val state = AgentTaskEngine.taskState.value
        assertEquals(100, state?.progressPercentage)
        assertEquals(1, state?.processedItems?.size)
        assertEquals("stable end evidence", state?.finalVerificationResult)
        assertEquals(AgentTaskEngine.State.COMPLETED.name, state?.currentStatus)
        assertEquals(context.taskId, state?.taskId)
    }

    @Test
    fun spotifyPlanTracksRealStepProgressRatherThanFakeTime() {
        AgentTaskEngine.begin("Spotify play Midnight City", "unit-test-spotify")
        var state = AgentTaskEngine.taskState.value!!
        assertEquals("Open Spotify", state.steps.first { it.stepId == state.currentStepId }.title)
        assertEquals(20, state.progressPercentage)
        assertTrue(AgentTaskEngine.completeStepByTitle("Open Spotify", "foreground verified", verified = true))
        state = AgentTaskEngine.taskState.value!!
        assertEquals("Search for the requested track", state.steps.first { it.stepId == state.currentStepId }.title)
        assertTrue(AgentTaskEngine.completeStepByTitle("Search for the requested track", "unique match found", verified = true))
        assertTrue(AgentTaskEngine.completeStepByTitle("Select one unique match", "selected", verified = true))
        assertTrue(AgentTaskEngine.completeStepByTitle("Verify playback", "playing state visible", verified = true))
        AgentTaskEngine.recordCompletionEvidence(AgentTaskEngine.CompletionEvidence.VERIFIED_GOAL, "track and playback confirmed")
        assertTrue(AgentTaskEngine.finish(AgentTaskEngine.State.COMPLETED))
        assertEquals(100, AgentTaskEngine.taskState.value?.progressPercentage)
    }

    @Test
    fun verifiedWebsiteOpenAdvancesTheMappedOpenStep() {
        AgentTaskEngine.begin("Open Spotify and play Midnight City", "unit-test-open-website")
        AgentTaskEngine.recordVerifiedStep("open_website", "Destination page is visible", verified = true)
        val state = AgentTaskEngine.taskState.value!!
        assertEquals("Find the exact requested song or video", state.steps.first { it.stepId == state.currentStepId }.title)
        assertEquals(TaskStepStatus.COMPLETED, state.steps.first { it.title == "Open Spotify" }.status)
    }

    @Test
    fun autonomousLoopStopsAtConfiguredIterationLimit() = runBlocking {
        AgentTaskEngine.begin("Run bounded autonomous loop", "unit-test-loop-limit")
        var calls = 0

        val outcome = AgentTaskEngine.runAutonomousLoop(maxIterations = 2) { _, _ ->
            calls++
            true
        }

        assertEquals(2, calls)
        assertEquals(2, outcome.iterations)
        assertTrue(outcome.reachedLimit)
        assertFalse(outcome.cancelled)
    }

    @Test
    fun autonomousLoopCanFinishBeforeConfiguredIterationLimit() = runBlocking {
        AgentTaskEngine.begin("Run early stopping autonomous loop", "unit-test-loop-early-stop")
        var calls = 0

        val outcome = AgentTaskEngine.runAutonomousLoop(maxIterations = 5) { _, _ ->
            calls++
            false
        }

        assertEquals(1, calls)
        assertEquals(1, outcome.iterations)
        assertFalse(outcome.reachedLimit)
        assertFalse(outcome.cancelled)
    }

    @Test
    fun replacingPlanPreservesIntentionallyRepeatedSteps() {
        AgentTaskEngine.begin("Open Spotify and like two separate playlists", "unit-test-repeated-plan")
        assertTrue(AgentTaskEngine.completeStepByTitle("Open Spotify", "foreground verified", verified = true))

        AgentTaskEngine.replacePlan(listOf("Open Spotify", "Like playlist one", "Like playlist two"))

        val steps = AgentTaskEngine.taskState.value!!.steps
        assertEquals(4, steps.size) // includes the completed internal task-map record
        assertEquals(2, steps.count { it.title.startsWith("Like playlist") })
        assertEquals(steps.size, steps.map { it.stepId }.distinct().size)
        assertEquals(TaskStepStatus.COMPLETED, steps.first { it.title == "Open Spotify" }.status)
    }

    @Test
    fun pauseAndResumeAreReflectedInTheAuthoritativeSnapshot() {
        AgentTaskEngine.begin("Open Spotify", "unit-test-pause")
        assertTrue(AgentTaskEngine.pauseTask())
        assertTrue(AgentTaskEngine.isPaused.value)
        assertEquals(AgentTaskEngine.State.PAUSED.name, AgentTaskEngine.taskState.value?.currentStatus)
        assertTrue(AgentTaskEngine.resumeTask())
        assertFalse(AgentTaskEngine.isPaused.value)
        assertEquals(AgentTaskEngine.State.OBSERVING.name, AgentTaskEngine.taskState.value?.currentStatus)
    }
}
