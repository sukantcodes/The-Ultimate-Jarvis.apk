package com.jarvis.assistant.automation.task

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** Result of one step attempt. A step is only complete after [Success] with `verified = true`. */
sealed class StepOutcome {
    /** [verified] must reflect a real post-action check; an unverified success is treated as a failure to retry. */
    data class Success(val verified: Boolean, val state: String, val message: String = "") : StepOutcome()
    /** Recoverable: re-inspect and try this step again (bounded by the step's retry limit). */
    data class Retryable(val reason: String) : StepOutcome()
    /** Unexpected state: insert [prerequisites] (e.g. dismiss a dialog) before this step, then retry it. */
    data class Recover(val reason: String, val prerequisites: List<TaskStep>) : StepOutcome()
    /** A genuine blocker needs the user (login, CAPTCHA, permission, protected field). The task waits. */
    data class Blocked(val reason: String) : StepOutcome()
    /** Not recoverable. The task fails with this reason. */
    data class Fatal(val reason: String) : StepOutcome()
}

interface StepHandler {
    val timeoutMs: Long get() = 30_000L
    suspend fun execute(step: TaskStep, record: TaskRecord): StepOutcome
    /** Called before a retry: re-inspect the screen, refresh stale nodes. Must not repeat the action itself. */
    suspend fun recover(step: TaskStep, record: TaskRecord, reason: String) {}
    /** For a non-idempotent step interrupted mid-run: true = it already happened, false = it did not, null = unknown. */
    suspend fun alreadyDone(step: TaskStep, record: TaskRecord): Boolean? = null
}

data class TaskRunnerConfig(
    /** Safety ceilings, not goals. Large by default so long workflows are never cut short; callers may change them. */
    val maxStepExecutions: Int = 500,
    val deadlineMs: Long = 30 * 60_000L,
    val maxResumeAgeMs: Long = 10 * 60_000L,
    val retryBackoffMs: Long = 250L
)

/**
 * The looping engine. Runs an ordered plan to completion: picks the next ready step, executes it, verifies the
 * result, checkpoints, and continues. A failed step is recovered in place (completed steps are never repeated);
 * non-idempotent steps are never blindly retried; blockers pause the task for the user; cancellation and a
 * deadline always end the loop. Every transition is persisted so an eligible task resumes after a restart.
 */
class TaskRunner(
    private val store: TaskStore,
    private val handlers: Map<String, StepHandler>,
    private val config: TaskRunnerConfig = TaskRunnerConfig(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val onProgress: (String) -> Unit = {}
) {
    @Volatile private var cancelRequested = false
    @Volatile private var lastProgress = ""
    @Volatile var current: TaskRecord? = null
        private set

    fun cancel() { cancelRequested = true }
    val isCancelRequested: Boolean get() = cancelRequested

    private fun progress(message: String) {
        if (message == lastProgress) return          // no repetitive progress lines
        lastProgress = message
        onProgress(message)
    }

    private fun persist(record: TaskRecord): TaskRecord {
        val stamped = record.copy(updatedAt = clock())
        val toSave = if (stamped.status.terminal) stamped.scrubbed() else stamped
        store.save(toSave)
        current = stamped
        return stamped
    }

    /**
     * After a process restart. A task interrupted moments ago (within [autoResumeWindowMs]) continues from its last
     * verified checkpoint; an older one is kept, with its original context, as WAITING_FOR_USER so it never starts
     * acting on an unexpected screen by itself. Tasks older than [TaskRunnerConfig.maxResumeAgeMs] expire.
     */
    suspend fun resumeUnfinished(autoResumeWindowMs: Long = 45_000L): TaskRecord? {
        val saved = store.loadUnfinished() ?: return null
        if (saved.status == TaskStatus.WAITING_FOR_USER) return saved
        val age = clock() - saved.updatedAt
        if (age > config.maxResumeAgeMs) {
            persist(saved.copy(status = TaskStatus.CANCELLED, finalMessage = "Expired before it could resume."))
            return null
        }
        val normalized = normalizeInterrupted(saved)
        if (age > autoResumeWindowMs) {
            return persist(normalized.copy(status = TaskStatus.WAITING_FOR_USER,
                finalMessage = "This task was interrupted when JARVIS restarted."))
        }
        return run(normalized)
    }

    /** A step that was RUNNING when the process died: repeat only when that is provably safe. */
    private suspend fun normalizeInterrupted(r: TaskRecord): TaskRecord {
        var rec = r
        for (s in r.steps.filter { it.status == StepStatus.RUNNING }) {
            val done = if (s.idempotent) null else handlers[s.action]?.alreadyDone(s, rec)
            rec = rec.withStep(
                when {
                    s.idempotent || done == false -> s.copy(status = StepStatus.PENDING)
                    done == true -> s.copy(status = StepStatus.COMPLETED, completedAt = clock(), result = "completed before restart")
                    else -> s.copy(status = StepStatus.BLOCKED, failure = "Interrupted mid-action; confirm before I repeat it.")
                }
            )
        }
        return rec
    }

    /** The user approved a blocked/confirmation step: allow it to run, then continue the task. */
    suspend fun confirmAndContinue(taskId: String): TaskRecord? {
        val rec = store.load(taskId) ?: return null
        if (rec.status.terminal) return rec
        // Interrupted (not blocked) tasks have nothing to approve: just continue from the checkpoint.
        val step = rec.blocked.firstOrNull() ?: return run(rec)
        val approved = rec.copy(
            context = rec.context + ("confirmed:${step.id}" to "true"),
            steps = rec.steps.map { if (it.id == step.id) it.copy(status = StepStatus.PENDING, failure = null) else it }
        )
        return run(approved)
    }

    suspend fun run(initial: TaskRecord): TaskRecord {
        cancelRequested = false
        lastProgress = ""
        val startedAt = clock()
        var executions = 0
        var rec = persist(initial.copy(status = TaskStatus.RUNNING))
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                if (cancelRequested) return finishCancelled(rec)
                if (clock() - startedAt > config.deadlineMs) {
                    return persist(rec.copy(status = TaskStatus.FAILED, finalMessage = "The task ran past its time limit."))
                }
                if (executions >= config.maxStepExecutions) {
                    return persist(rec.copy(status = TaskStatus.FAILED, finalMessage = "The task reached its step safety limit."))
                }

                val step = rec.nextRunnable()
                if (step == null) {
                    rec = when {
                        rec.steps.all { it.status == StepStatus.COMPLETED } -> {
                            progress("Task completed.")
                            persist(rec.copy(status = TaskStatus.COMPLETED, finalMessage = "Task completed and verified."))
                        }
                        rec.blocked.isNotEmpty() -> persist(rec.copy(status = TaskStatus.WAITING_FOR_USER,
                            finalMessage = rec.blocked.first().failure))
                        else -> persist(rec.copy(status = TaskStatus.FAILED,
                            finalMessage = rec.failed.firstOrNull()?.failure ?: "No runnable step remained."))
                    }
                    return rec
                }

                if (step.requiresConfirmation && rec.context["confirmed:${step.id}"] != "true") {
                    rec = persist(rec.withStep(step.copy(status = StepStatus.BLOCKED, failure = "Needs your confirmation: ${step.title}"))
                        .copy(status = TaskStatus.WAITING_FOR_USER, finalMessage = "Needs your confirmation: ${step.title}"))
                    return rec
                }

                val handler = handlers[step.action]
                if (handler == null) {
                    rec = persist(rec.withStep(step.copy(status = StepStatus.FAILED, failure = "No handler for ${step.action}"))
                        .copy(status = TaskStatus.FAILED, finalMessage = "I don't know how to do: ${step.title}"))
                    return rec
                }

                executions++
                progress(step.title)
                // Checkpoint BEFORE acting so a crash mid-step is detectable on resume.
                rec = persist(rec.withStep(step.copy(status = StepStatus.RUNNING, startedAt = clock())))

                val outcome = try {
                    withTimeoutOrNull(handler.timeoutMs) { handler.execute(step, rec) }
                        ?: StepOutcome.Retryable("${step.title} timed out")
                } catch (c: CancellationException) {
                    throw c
                } catch (e: Exception) {
                    StepOutcome.Retryable("${e.javaClass.simpleName} while ${step.title}")
                }

                rec = when (outcome) {
                    is StepOutcome.Success ->
                        if (outcome.verified) persist(
                            rec.withStep(step.copy(status = StepStatus.COMPLETED, completedAt = clock(), result = outcome.message, failure = null))
                                .copy(lastVerifiedState = outcome.state)
                        ) else retryOrFail(rec, step, handler, "result was not verified")
                    is StepOutcome.Retryable -> retryOrFail(rec, step, handler, outcome.reason)
                    is StepOutcome.Recover -> {
                        val withPrereqs = rec.copy(steps = rec.steps.flatMap {
                            if (it.id == step.id) outcome.prerequisites + it else listOf(it)
                        })
                        retryOrFail(withPrereqs, step, handler, outcome.reason)
                    }
                    is StepOutcome.Blocked -> {
                        progress(outcome.reason)
                        return persist(rec.withStep(step.copy(status = StepStatus.BLOCKED, failure = outcome.reason))
                            .copy(status = TaskStatus.WAITING_FOR_USER, finalMessage = outcome.reason))
                    }
                    is StepOutcome.Fatal -> return persist(
                        rec.withStep(step.copy(status = StepStatus.FAILED, failure = outcome.reason))
                            .copy(status = TaskStatus.FAILED, finalMessage = outcome.reason)
                    )
                }
                if (rec.status.terminal || rec.status == TaskStatus.WAITING_FOR_USER) return rec
            }
        } catch (c: CancellationException) {
            current?.let { finishCancelled(it) }
            throw c
        }
    }

    /**
     * Recovers one failed step in place. Retries are bounded (the step's [TaskStep.maxRetries], 3 by default);
     * a non-idempotent step is handed to the user instead of being repeated blindly.
     */
    private suspend fun retryOrFail(rec: TaskRecord, step: TaskStep, handler: StepHandler, reason: String): TaskRecord {
        val retries = step.retries + 1
        if (!step.idempotent) {
            val done = handler.alreadyDone(step, rec)
            if (done == true) return persist(rec.withStep(step.copy(status = StepStatus.COMPLETED, completedAt = clock(), result = "already done")))
            if (done == null) {
                progress("Waiting for your confirmation before repeating: ${step.title}")
                return persist(rec.withStep(step.copy(status = StepStatus.BLOCKED, retries = retries,
                    failure = "$reason. This action cannot be safely repeated; confirm to try again."))
                    .copy(status = TaskStatus.WAITING_FOR_USER, finalMessage = reason))
            }
        }
        if (retries >= step.maxRetries) {
            return persist(rec.withStep(step.copy(status = StepStatus.FAILED, retries = retries, failure = reason))
                .copy(status = TaskStatus.FAILED, finalMessage = "I couldn't finish \"${step.title}\": $reason"))
        }
        progress("Recovering from an error...")
        handler.recover(step, rec, reason)
        delay(config.retryBackoffMs)
        progress("Continuing task...")
        return persist(rec.withStep(step.copy(status = StepStatus.PENDING, retries = retries, failure = reason)))
    }

    private fun finishCancelled(rec: TaskRecord): TaskRecord {
        progress("Task cancelled.")
        val cancelled = rec.copy(
            steps = rec.steps.map {
                if (it.status == StepStatus.COMPLETED || it.status == StepStatus.FAILED) it else it.copy(status = StepStatus.CANCELLED)
            },
            status = TaskStatus.CANCELLED, finalMessage = "Task cancelled."
        )
        return persist(cancelled)
    }
}
