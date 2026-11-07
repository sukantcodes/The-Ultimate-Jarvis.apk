package com.jarvis.assistant.automation.task

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Simulates the process dying: the Nth save throws an Error (not an Exception), so nothing can catch it. */
class ProcessDied : Error("process died")

class CrashingStore(private val delegate: TaskStore, private val crashOnSave: Int) : TaskStore {
    private var saves = 0
    override fun save(record: TaskRecord) {
        saves++
        if (saves == crashOnSave) throw ProcessDied()
        delegate.save(record)
    }
    override fun load(taskId: String) = delegate.load(taskId)
    override fun loadUnfinished() = delegate.loadUnfinished()
    override fun latest() = delegate.latest()
}

class ScriptedHandler(
    override val timeoutMs: Long = 2_000L,
    private val behavior: suspend (TaskStep, Int) -> StepOutcome = { s, _ -> StepOutcome.Success(true, "state-" + s.id) }
) : StepHandler {
    val calls = ConcurrentHashMap<String, AtomicInteger>()
    val order: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())
    val recovered: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())
    @Volatile var alreadyDoneAnswer: Boolean? = null

    fun count(id: String): Int = calls[id]?.get() ?: 0

    override suspend fun execute(step: TaskStep, record: TaskRecord): StepOutcome {
        val n = calls.getOrPut(step.id) { AtomicInteger() }.incrementAndGet()
        order += step.id
        return behavior(step, n)
    }

    override suspend fun recover(step: TaskStep, record: TaskRecord, reason: String) { recovered += step.id }
    override suspend fun alreadyDone(step: TaskStep, record: TaskRecord): Boolean? = alreadyDoneAnswer
}

class TaskRunnerTest {

    private fun plan(n: Int, text: String = "secret-text-123", tweak: (TaskStep) -> TaskStep = { it }): TaskRecord {
        val steps = (1..n).map { i ->
            tweak(TaskStep(
                id = "s$i", title = "Step $i", action = "work",
                args = mapOf("text" to text),
                dependsOn = if (i == 1) emptyList() else listOf("s${i - 1}")
            ))
        }
        return TaskRecord(
            taskId = "task-" + n, objective = "Do $n things", context = mapOf("text" to text),
            steps = steps, createdAt = 1_000L
        )
    }

    private fun runner(
        store: TaskStore, h: StepHandler, config: TaskRunnerConfig = TaskRunnerConfig(retryBackoffMs = 1),
        clock: () -> Long = System::currentTimeMillis, progress: (String) -> Unit = {}
    ) = TaskRunner(store, mapOf("work" to h), config, clock, progress)

    // ---- long plans -----------------------------------------------------------------------------------------

    @Test fun seventeenSequentialStepsCompleteAndAreVerified() = runBlocking {
        val store = InMemoryTaskStore()
        val h = ScriptedHandler()
        val rec = runner(store, h).run(plan(17))
        assertEquals(TaskStatus.COMPLETED, rec.status)
        assertEquals((1..17).map { "s$it" }, h.order)
        assertTrue(rec.steps.all { it.status == StepStatus.COMPLETED })
        assertEquals("state-s17", rec.lastVerifiedState)
        assertEquals("Task completed and verified.", rec.finalMessage)
        // Finished tasks keep their outcome but drop the user's text from storage.
        val saved = store.load(rec.taskId)!!
        assertTrue(saved.context.values.all { it.isEmpty() })
        assertTrue(saved.steps.all { s -> s.args.values.all { it.isEmpty() } })
    }

    // ---- recovery ---------------------------------------------------------------------------------------------

    @Test fun oneRecoverableFailureRecoversThatStepOnly() = runBlocking {
        val h = ScriptedHandler { s, n -> if (s.id == "s5" && n == 1) StepOutcome.Retryable("stale node") else StepOutcome.Success(true, "ok-" + s.id) }
        val rec = runner(InMemoryTaskStore(), h).run(plan(16))
        assertEquals(TaskStatus.COMPLETED, rec.status)
        (1..4).forEach { assertEquals("s$it must not repeat", 1, h.count("s$it")) }
        assertEquals(2, h.count("s5"))
        (6..16).forEach { assertEquals(1, h.count("s$it")) }
        assertEquals(1, rec.steps.first { it.id == "s5" }.retries)
        assertEquals(listOf("s5"), h.recovered)
    }

    @Test fun unexpectedScreenTransitionInsertsARecoveryStepThenRetries() = runBlocking {
        val dismiss = TaskStep(id = "dismiss", title = "Dismiss dialog", action = "work")
        val h = ScriptedHandler { s, n ->
            if (s.id == "s8" && n == 1) StepOutcome.Recover("popup covered the screen", listOf(dismiss))
            else StepOutcome.Success(true, "ok-" + s.id)
        }
        val rec = runner(InMemoryTaskStore(), h).run(plan(15))
        assertEquals(TaskStatus.COMPLETED, rec.status)
        val at = h.order.indexOf("s8")
        assertEquals(listOf("s8", "dismiss", "s8", "s9"), h.order.subList(at, at + 4))
        assertEquals(2, h.count("s8"))
        assertEquals(1, h.count("dismiss"))
    }

    @Test fun exceptionsInAHandlerAreRecoveredNotFatal() = runBlocking {
        val h = ScriptedHandler { s, n ->
            if (s.id == "s2" && n == 1) throw IllegalStateException("boom")
            StepOutcome.Success(true, "ok")
        }
        val rec = runner(InMemoryTaskStore(), h).run(plan(3))
        assertEquals(TaskStatus.COMPLETED, rec.status)
        assertEquals(2, h.count("s2"))
    }

    @Test fun retriesAreBoundedAtThree() = runBlocking {
        val h = ScriptedHandler { s, _ -> if (s.id == "s2") StepOutcome.Retryable("still broken") else StepOutcome.Success(true, "ok") }
        val rec = runner(InMemoryTaskStore(), h).run(plan(5))
        assertEquals(TaskStatus.FAILED, rec.status)
        assertEquals(3, h.count("s2"))
        assertEquals(0, h.count("s3"))
        assertTrue(rec.finalMessage!!.contains("Step 2"))
        assertEquals(StepStatus.FAILED, rec.steps.first { it.id == "s2" }.status)
    }

    @Test fun unverifiedSuccessIsNeverCompletion() = runBlocking {
        val h = ScriptedHandler { _, _ -> StepOutcome.Success(false, "looked fine but not checked") }
        val rec = runner(InMemoryTaskStore(), h).run(plan(2))
        assertEquals(TaskStatus.FAILED, rec.status)
        assertTrue(rec.steps.none { it.status == StepStatus.COMPLETED })
    }

    @Test fun stepTimeoutIsARecoverableFailureAndBounded() = runBlocking {
        val h = ScriptedHandler(timeoutMs = 30) { _, _ -> delay(5_000); StepOutcome.Success(true, "never") }
        val t0 = System.currentTimeMillis()
        val rec = runner(InMemoryTaskStore(), h).run(plan(2))
        assertEquals(TaskStatus.FAILED, rec.status)
        assertEquals(3, h.count("s1"))
        assertTrue(System.currentTimeMillis() - t0 < 3_000)
    }

    @Test fun fatalOutcomeFailsImmediately() = runBlocking {
        val h = ScriptedHandler { s, _ -> if (s.id == "s2") StepOutcome.Fatal("App is not installed") else StepOutcome.Success(true, "ok") }
        val rec = runner(InMemoryTaskStore(), h).run(plan(4))
        assertEquals(TaskStatus.FAILED, rec.status)
        assertEquals("App is not installed", rec.finalMessage)
        assertEquals(1, h.count("s2"))
    }

    // ---- blockers and sensitive actions -------------------------------------------------------------------------

    @Test fun blockerPausesTheTaskForTheUser() = runBlocking {
        val h = ScriptedHandler { s, _ -> if (s.id == "s3") StepOutcome.Blocked("CAPTCHA needs you") else StepOutcome.Success(true, "ok") }
        val rec = runner(InMemoryTaskStore(), h).run(plan(5))
        assertEquals(TaskStatus.WAITING_FOR_USER, rec.status)
        assertEquals("CAPTCHA needs you", rec.finalMessage)
        assertEquals(0, h.count("s4"))
        assertEquals(StepStatus.BLOCKED, rec.steps.first { it.id == "s3" }.status)
    }

    @Test fun nonIdempotentStepIsNeverBlindlyRepeated() = runBlocking {
        val store = InMemoryTaskStore()
        val h = ScriptedHandler { s, n -> if (s.id == "s2" && n == 1) StepOutcome.Retryable("send timed out") else StepOutcome.Success(true, "ok") }
        val r = runner(store, h)
        val first = r.run(plan(3) { if (it.id == "s2") it.copy(idempotent = false) else it })
        assertEquals(TaskStatus.WAITING_FOR_USER, first.status)
        assertEquals(1, h.count("s2"))
        assertEquals(0, h.count("s3"))
        val done = r.confirmAndContinue(first.taskId)!!
        assertEquals(TaskStatus.COMPLETED, done.status)
        assertEquals(2, h.count("s2"))
    }

    @Test fun nonIdempotentStepThatProvablyHappenedIsNotRepeated() = runBlocking {
        val h = ScriptedHandler { s, _ -> if (s.id == "s2") StepOutcome.Retryable("no ack") else StepOutcome.Success(true, "ok") }
        h.alreadyDoneAnswer = true
        val rec = runner(InMemoryTaskStore(), h).run(plan(3) { if (it.id == "s2") it.copy(idempotent = false) else it })
        assertEquals(TaskStatus.COMPLETED, rec.status)
        assertEquals(1, h.count("s2"))
    }

    @Test fun nonIdempotentStepThatProvablyDidNotHappenMayRetry() = runBlocking {
        val h = ScriptedHandler { s, n -> if (s.id == "s2" && n == 1) StepOutcome.Retryable("no ack") else StepOutcome.Success(true, "ok") }
        h.alreadyDoneAnswer = false
        val rec = runner(InMemoryTaskStore(), h).run(plan(3) { if (it.id == "s2") it.copy(idempotent = false) else it })
        assertEquals(TaskStatus.COMPLETED, rec.status)
        assertEquals(2, h.count("s2"))
    }

    @Test fun confirmationGateStopsBeforeTheActionUntilApproved() = runBlocking {
        val store = InMemoryTaskStore()
        val h = ScriptedHandler()
        val r = runner(store, h)
        val first = r.run(plan(3) { if (it.id == "s2") it.copy(requiresConfirmation = true, idempotent = false) else it })
        assertEquals(TaskStatus.WAITING_FOR_USER, first.status)
        assertEquals("the action must not run before approval", 0, h.count("s2"))
        val done = r.confirmAndContinue(first.taskId)!!
        assertEquals(TaskStatus.COMPLETED, done.status)
        assertEquals(1, h.count("s2"))
    }

    // ---- process death and restoration ---------------------------------------------------------------------------

    private fun crashDuring(stepIndex: Int, delegate: InMemoryTaskStore, record: TaskRecord, h: ScriptedHandler, now: Long) {
        // saves: #1 initial, then per step i: RUNNING checkpoint = 2i, COMPLETED = 2i + 1. Crash on the COMPLETED save.
        val store = CrashingStore(delegate, crashOnSave = 2 * stepIndex + 1)
        try {
            runBlocking { runner(store, h, clock = { now }).run(record) }
            throw AssertionError("the simulated crash did not happen")
        } catch (_: ProcessDied) { }
    }

    @Test fun interruptedTaskAutoResumesFromTheLastCheckpoint() = runBlocking {
        val delegate = InMemoryTaskStore()
        val h1 = ScriptedHandler()
        crashDuring(6, delegate, plan(12), h1, now = 1_000L)
        assertEquals(StepStatus.RUNNING, delegate.latest()!!.steps.first { it.id == "s6" }.status)

        val h2 = ScriptedHandler()
        val rec = runner(delegate, h2, clock = { 1_000L }).resumeUnfinished()!!
        assertEquals(TaskStatus.COMPLETED, rec.status)
        (1..5).forEach { assertEquals("s$it was already verified before the crash", 0, h2.count("s$it")) }
        assertEquals("the interrupted idempotent step runs once more", 1, h2.count("s6"))
        (7..12).forEach { assertEquals(1, h2.count("s$it")) }
    }

    @Test fun staleInterruptedTaskWaitsForTheUserAndKeepsItsText() = runBlocking {
        val delegate = InMemoryTaskStore()
        crashDuring(4, delegate, plan(8), ScriptedHandler(), now = 1_000L)

        val h2 = ScriptedHandler()
        val r2 = runner(delegate, h2, clock = { 1_000L + 120_000L })
        val waiting = r2.resumeUnfinished()!!
        assertEquals(TaskStatus.WAITING_FOR_USER, waiting.status)
        assertEquals("nothing may run by itself on a stale screen", 0, h2.order.size)
        assertEquals("the exact text must survive the restart", "secret-text-123", delegate.load(waiting.taskId)!!.context["text"])

        val done = r2.confirmAndContinue(waiting.taskId)!!
        assertEquals(TaskStatus.COMPLETED, done.status)
        (1..3).forEach { assertEquals(0, h2.count("s$it")) }
    }

    @Test fun veryOldTasksExpireInsteadOfResuming() = runBlocking {
        val delegate = InMemoryTaskStore()
        crashDuring(3, delegate, plan(6), ScriptedHandler(), now = 1_000L)
        val h2 = ScriptedHandler()
        val result = runner(delegate, h2, clock = { 1_000L + 11 * 60_000L }).resumeUnfinished()
        assertNull(result)
        assertEquals(TaskStatus.CANCELLED, delegate.latest()!!.status)
        assertEquals(0, h2.order.size)
    }

    @Test fun interruptedNonIdempotentStepIsBlockedUnlessProvenDone() = runBlocking {
        val tweak: (TaskStep) -> TaskStep = { if (it.id == "s4") it.copy(idempotent = false) else it }

        val d1 = InMemoryTaskStore()
        crashDuring(4, d1, plan(6, tweak = tweak), ScriptedHandler(), now = 1_000L)
        val h2 = ScriptedHandler()
        val blocked = runner(d1, h2, clock = { 1_000L }).resumeUnfinished()!!
        assertEquals(TaskStatus.WAITING_FOR_USER, blocked.status)
        assertEquals("never repeat an action that may already have happened", 0, h2.count("s4"))

        val d2 = InMemoryTaskStore()
        crashDuring(4, d2, plan(6, tweak = tweak), ScriptedHandler(), now = 1_000L)
        val h3 = ScriptedHandler().also { it.alreadyDoneAnswer = true }
        val done = runner(d2, h3, clock = { 1_000L }).resumeUnfinished()!!
        assertEquals(TaskStatus.COMPLETED, done.status)
        assertEquals(0, h3.count("s4"))
    }

    // ---- cancellation and safeguards -------------------------------------------------------------------------------

    @Test fun cancelRequestStopsBeforeTheNextStep() = runBlocking {
        val store = InMemoryTaskStore()
        lateinit var r: TaskRunner
        val h = ScriptedHandler { s, _ ->
            if (s.id == "s3") r.cancel()
            StepOutcome.Success(true, "ok")
        }
        r = runner(store, h)
        val rec = r.run(plan(8))
        assertEquals(TaskStatus.CANCELLED, rec.status)
        assertEquals(0, h.count("s4"))
        assertEquals(StepStatus.COMPLETED, rec.steps.first { it.id == "s3" }.status)
        assertEquals(StepStatus.CANCELLED, rec.steps.first { it.id == "s4" }.status)
        assertEquals(TaskStatus.CANCELLED, store.latest()!!.status)
    }

    @Test fun cancellingTheCoroutineInterruptsARunningStepAndPersistsCancelled() = runBlocking {
        val store = InMemoryTaskStore()
        val started = CompletableDeferred<Unit>()
        val h = ScriptedHandler(timeoutMs = 60_000) { _, _ -> started.complete(Unit); delay(60_000); StepOutcome.Success(true, "never") }
        val job = async { runner(store, h).run(plan(4)) }
        started.await()
        job.cancel()
        try { job.await() } catch (_: CancellationException) { }
        assertEquals(TaskStatus.CANCELLED, store.latest()!!.status)
        assertEquals(1, h.order.size)
    }

    @Test fun deadlineEndsALongTask() = runBlocking {
        val h = ScriptedHandler { _, _ -> delay(30); StepOutcome.Success(true, "ok") }
        val cfg = TaskRunnerConfig(deadlineMs = 80, retryBackoffMs = 1)
        val rec = runner(InMemoryTaskStore(), h, config = cfg).run(plan(50))
        assertEquals(TaskStatus.FAILED, rec.status)
        assertTrue(rec.finalMessage!!.contains("time limit"))
        assertTrue(h.order.size < 50)
    }

    @Test fun stepExecutionCeilingIsConfigurable() = runBlocking {
        val h = ScriptedHandler()
        val rec = runner(InMemoryTaskStore(), h, config = TaskRunnerConfig(maxStepExecutions = 3, retryBackoffMs = 1)).run(plan(6))
        assertEquals(TaskStatus.FAILED, rec.status)
        assertEquals(3, h.order.size)
        assertTrue(rec.finalMessage!!.contains("safety limit"))
        val roomy = runner(InMemoryTaskStore(), ScriptedHandler(), config = TaskRunnerConfig(maxStepExecutions = 500)).run(plan(60))
        assertEquals("dozens of steps are fine by default", TaskStatus.COMPLETED, roomy.status)
    }

    @Test fun unknownActionAndUnmetDependencyFailInsteadOfHanging() = runBlocking {
        val r = TaskRunner(InMemoryTaskStore(), emptyMap(), TaskRunnerConfig(retryBackoffMs = 1))
        assertEquals(TaskStatus.FAILED, r.run(plan(2)).status)

        val orphan = plan(2).let { p -> p.copy(steps = p.steps.map { if (it.id == "s2") it.copy(dependsOn = listOf("ghost")) else it }) }
        val rec = runner(InMemoryTaskStore(), ScriptedHandler()).run(orphan)
        assertEquals(TaskStatus.FAILED, rec.status)
    }

    // ---- progress, state for the AI, persistence format ---------------------------------------------------------------

    @Test fun progressMessagesAreNotRepeated() = runBlocking {
        val seen = mutableListOf<String>()
        val h = ScriptedHandler { s, n -> if (s.id == "s2" && n == 1) StepOutcome.Retryable("hiccup") else StepOutcome.Success(true, "ok") }
        runner(InMemoryTaskStore(), h, progress = { seen.add(it) }).run(plan(3))
        assertTrue(seen.contains("Recovering from an error..."))
        assertTrue(seen.contains("Continuing task..."))
        assertEquals("Task completed.", seen.last())
        seen.zipWithNext().forEach { (a, b) -> assertFalse("duplicate progress: $a", a == b) }
    }

    @Test fun promptSummaryTellsTheAiWhereTheTaskIsWithoutLeakingTypedText() {
        val rec = plan(4).let { p ->
            p.copy(
                status = TaskStatus.RUNNING, lastVerifiedState = "foreground=com.whatsapp",
                steps = p.steps.mapIndexed { i, s -> if (i < 2) s.copy(status = StepStatus.COMPLETED) else s }
            )
        }
        val summary = rec.promptSummary()
        assertTrue(summary, summary.contains("step=3/4"))
        assertTrue(summary.contains("completed=[Step 1, Step 2]"))
        assertTrue(summary.contains("pending=[Step 3, Step 4]") || summary.contains("pending=[Step 4]"))
        assertTrue(summary.contains("foreground=com.whatsapp"))
        assertFalse("typed text must not be copied into AI state", summary.contains("secret-text-123"))
    }

    @Test fun codecRoundTripsEveryFieldIncludingUnicodeAndNulls() {
        val rec = TaskRecord(
            taskId = "abc", objective = "नमस्ते “quoted” \n newline", context = mapOf("text" to "😊 ünï", "app" to ""),
            steps = listOf(
                TaskStep("a", "A", "work", mapOf("k" to "v"), emptyList(), true, false, StepStatus.COMPLETED, 1, 3, 10L, 20L, "r", null),
                TaskStep("b", "B", "work", emptyMap(), listOf("a"), false, true, StepStatus.BLOCKED, 0, 2, null, null, null, "why")
            ),
            status = TaskStatus.WAITING_FOR_USER, createdAt = 5L, updatedAt = 6L, lastVerifiedState = null, finalMessage = "msg"
        )
        assertEquals(rec, TaskCodec.decode(TaskCodec.encode(rec)))
        assertNull(TaskCodec.decode("not json"))
        assertNotNull(TaskCodec.decode(TaskCodec.encode(rec.copy(finalMessage = null))))
    }
}
