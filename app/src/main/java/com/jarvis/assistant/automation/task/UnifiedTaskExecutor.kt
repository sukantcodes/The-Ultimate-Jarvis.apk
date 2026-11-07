package com.jarvis.assistant.automation.task

import android.content.Context
import android.util.Log
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.automation.agent.AgentTaskEngine
import com.jarvis.assistant.automation.phone.PhoneControlManager
import com.jarvis.assistant.automation.typing.AccessibilityTypingHost
import com.jarvis.assistant.automation.typing.TypeIntent
import com.jarvis.assistant.automation.typing.TypeMode
import com.jarvis.assistant.automation.typing.TypingConfig
import com.jarvis.assistant.automation.typing.TypingFailure
import com.jarvis.assistant.automation.typing.TypingHost
import com.jarvis.assistant.automation.typing.TypingSession
import com.jarvis.assistant.automation.typing.TypingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * The single entry point that turns "open <app> and type <text>" (and plain typing / clearing / continuing) into
 * one continuous task on the shared [TaskRunner]. Opening the app and typing are steps of the SAME task, so the
 * exact text travels with the task and cannot be lost between navigation and typing; a failed typing step is
 * recovered in place without reopening the app or asking the user to repeat the command.
 *
 * Progress is published through the existing [AgentTaskEngine] so the current JARVIS UI shows it.
 */
class UnifiedTaskExecutor(
    private val context: Context,
    private val phone: PhoneControlManager,
    private val store: TaskStore = PrefsTaskStore(context),
    private val host: TypingHost = AccessibilityTypingHost(),
    private val typingConfig: TypingConfig = TypingConfig(),
    private val runnerConfig: TaskRunnerConfig = TaskRunnerConfig()
) {
    private companion object {
        const val TAG = "UnifiedTaskExecutor"
        const val ACTION_OPEN_APP = "OPEN_APP"
        const val ACTION_TYPE_TEXT = "TYPE_TEXT"
        val BROWSERS = listOf("chrome", "browser", "firefox", "brave", "opera", "edge", "duckduckgo", "vivaldi")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var runner: TaskRunner? = null
    @Volatile private var lastAnnounced = ""
    @Volatile private var activeJob: Deferred<TaskRecord?>? = null

    /** Latest execution state for the AI to read before it picks its next action; null when there is none. */
    fun stateSummary(): String? {
        val rec = runner?.current ?: store.latest() ?: return null
        val recent = System.currentTimeMillis() - rec.updatedAt < 10 * 60_000L
        return if (!rec.status.terminal || recent) rec.promptSummary() else null
    }

    fun isRunning(): Boolean = runner?.current?.status == TaskStatus.RUNNING

    /** Cancels a running task immediately (interrupts any in-flight typing). Returns true when something was cancelled. */
    fun cancel(): Boolean {
        val r = runner ?: return false
        if (r.current?.status != TaskStatus.RUNNING) return false
        r.cancel()
        activeJob?.cancel()
        return true
    }

    /** Called once at startup: resumes an unfinished recent task after a process restart. */
    fun resumeAfterRestart() {
        scope.launch {
            try {
                val r = newRunner()
                runner = r
                r.resumeUnfinished()   // silent: nothing is announced at startup
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "resume failed: ${e.javaClass.simpleName}")
            }
        }
    }

    /** Runs [block] as a cancellable job; a cancel() by the user yields the cancelled record instead of throwing. */
    private suspend fun runTracked(r: TaskRunner, fallback: TaskRecord, block: suspend () -> TaskRecord?): TaskRecord {
        val job: Deferred<TaskRecord?> = scope.async { block() }
        activeJob = job
        return try {
            job.await() ?: fallback
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) throw e      // the caller itself was cancelled
            r.current ?: fallback
        } finally {
            activeJob = null
        }
    }

    suspend fun execute(intent: TypeIntent): String = withContext(Dispatchers.Default) {
        if (JarvisAccessibilityService.instance == null) {
            return@withContext "I need the JARVIS accessibility service turned on to type for you. Please enable it in Settings."
        }
        if (isRunning()) return@withContext "I'm still working on the previous task. Say cancel to stop it."

        if (intent.mode == TypeMode.CONTINUE) return@withContext resumePending()
        if (intent.needsText) return@withContext "Tell me the exact text you want me to type."
        if (intent.mode != TypeMode.CLEAR && intent.text.isBlank()) {
            return@withContext "Tell me the exact text you want me to type."
        }

        val record = plan(intent)
        val r = newRunner()
        runner = r
        beginAgentTask(record)
        report(runTracked(r, record) { r.run(record) })
    }

    /** "Type here" / "continue typing": resume the task that is waiting, with the original text still attached. */
    private suspend fun resumePending(): String {
        val waiting = store.loadUnfinished()
        if (waiting == null || waiting.status != TaskStatus.WAITING_FOR_USER) {
            return "There is no typing task waiting. Tell me what you want me to type."
        }
        val r = newRunner()
        runner = r
        beginAgentTask(waiting)
        val done = runTracked(r, waiting) { r.confirmAndContinue(waiting.taskId) }
        return report(done)
    }

    // --- planning ------------------------------------------------------------------------------------

    private fun plan(intent: TypeIntent): TaskRecord {
        val now = System.currentTimeMillis()
        val steps = ArrayList<TaskStep>()
        var expectedPackage = ""
        if (intent.opensApp) {
            expectedPackage = phone.resolvePackageForApp(intent.app!!).orEmpty()
            steps += TaskStep(
                id = "open", title = "Opening ${intent.app}", action = ACTION_OPEN_APP,
                args = mapOf("app" to intent.app, "package" to expectedPackage)
            )
        }
        val foreground = JarvisAccessibilityService.instance?.foregroundPackageName().orEmpty()
        val browserTarget = (expectedPackage.ifBlank { foreground }).let { pkg -> BROWSERS.any { pkg.contains(it, true) } }
        steps += TaskStep(
            id = "type",
            title = if (intent.mode == TypeMode.CLEAR) "Clearing the text field" else "Typing your text",
            action = ACTION_TYPE_TEXT,
            args = mapOf(
                "text" to intent.text,
                "mode" to intent.mode.name,
                "package" to expectedPackage,
                "addressBar" to browserTarget.toString()
            ),
            dependsOn = if (intent.opensApp) listOf("open") else emptyList(),
            maxRetries = 2   // the session already retries internally; one extra recovery here
        )
        val objective = buildString {
            append(if (intent.mode == TypeMode.CLEAR) "Clear the text field" else "Type the requested text")
            if (intent.opensApp) append(" in ").append(intent.app)
        }
        return TaskRecord(
            taskId = UUID.randomUUID().toString(),
            objective = objective,
            context = mapOf("app" to intent.app.orEmpty(), "mode" to intent.mode.name),
            steps = steps,
            createdAt = now
        )
    }

    // --- runner wiring -------------------------------------------------------------------------------

    private fun newRunner(): TaskRunner = TaskRunner(
        store = store,
        handlers = mapOf(ACTION_OPEN_APP to OpenAppHandler(), ACTION_TYPE_TEXT to TypeTextHandler()),
        config = runnerConfig,
        onProgress = ::announce
    )

    private fun announce(message: String) {
        if (message == lastAnnounced) return
        lastAnnounced = message
        try { AgentTaskEngine.updateActivity(message) } catch (_: Exception) { }
    }

    private fun beginAgentTask(record: TaskRecord) {
        try {
            AgentTaskEngine.begin(record.objective, "typing")
            AgentTaskEngine.replacePlan(record.steps.map { it.title })
            announce("Understanding your request...")
        } catch (e: Exception) {
            Log.w(TAG, "agent engine unavailable: ${e.javaClass.simpleName}")
        }
    }

    /** Turns the final record into the sentence JARVIS says. Never claims success unless the task is COMPLETED. */
    private fun report(rec: TaskRecord): String {
        try {
            when (rec.status) {
                TaskStatus.COMPLETED -> {
                    AgentTaskEngine.recordCompletionEvidence(AgentTaskEngine.CompletionEvidence.VERIFIED_GOAL, "Text read back from the field")
                    AgentTaskEngine.finish(AgentTaskEngine.State.COMPLETED)
                }
                TaskStatus.WAITING_FOR_USER -> {
                    AgentTaskEngine.failCurrentStep(rec.finalMessage ?: "Waiting for you", blocked = true)
                }
                TaskStatus.CANCELLED -> AgentTaskEngine.finish(AgentTaskEngine.State.CANCELLED, rec.finalMessage)
                else -> AgentTaskEngine.finish(AgentTaskEngine.State.FAILED, rec.finalMessage)
            }
        } catch (_: Exception) { }
        return when (rec.status) {
            TaskStatus.COMPLETED -> {
                val app = rec.context["app"].orEmpty()
                val where = if (app.isNotBlank()) " in $app" else ""
                if (rec.context["mode"] == TypeMode.CLEAR.name) "I cleared the field$where."
                else "Done. I typed your text$where and confirmed it on screen. I haven't pressed send or enter."
            }
            TaskStatus.WAITING_FOR_USER ->
                (rec.finalMessage ?: "I need your help to continue.") +
                    " When it's ready, say \"type here\" and I'll continue without you repeating the text."
            TaskStatus.CANCELLED -> "Okay, I stopped."
            else -> rec.finalMessage ?: "I couldn't finish typing."
        }
    }

    // --- step handlers -------------------------------------------------------------------------------

    private inner class OpenAppHandler : StepHandler {
        override val timeoutMs: Long = 25_000L

        override suspend fun execute(step: TaskStep, record: TaskRecord): StepOutcome {
            val app = step.args["app"].orEmpty()
            val pkg = step.args["package"].orEmpty()
            val svc = JarvisAccessibilityService.instance ?: return StepOutcome.Blocked("The accessibility service is not running.")
            val before = svc.foregroundPackageName().orEmpty()

            // Idempotent: if the app is already in front, do not relaunch (a relaunch can reset focus).
            if (pkg.isNotBlank() && before.contains(pkg, true)) {
                return StepOutcome.Success(true, "foreground=$before", "Already open.")
            }
            val result = withContext(Dispatchers.Main) { phone.openApp(app) }
            val failed = result.startsWith("Failed", true) || result.startsWith("Unable", true) ||
                result.contains("could not", true) || result.contains("couldn't", true)
            if (failed) return StepOutcome.Fatal("I couldn't open $app. It may not be installed.")

            val deadline = System.currentTimeMillis() + 10_000L
            while (System.currentTimeMillis() < deadline) {
                val fg = svc.foregroundPackageName().orEmpty()
                val inFront = if (pkg.isNotBlank()) fg.contains(pkg, true)
                else fg.isNotBlank() && fg != before && fg != context.packageName
                if (inFront) return StepOutcome.Success(true, "foreground=$fg", result)
                host.awaitUiChange(250)
            }
            return StepOutcome.Retryable("$app did not come to the foreground")
        }
    }

    private inner class TypeTextHandler : StepHandler {
        override val timeoutMs: Long = typingConfig.overallDeadlineMs + 5_000L

        override suspend fun execute(step: TaskStep, record: TaskRecord): StepOutcome {
            val mode = runCatching { TypeMode.valueOf(step.args["mode"].orEmpty()) }.getOrDefault(TypeMode.APPEND)
            val session = TypingSession(
                host = host,
                text = step.args["text"].orEmpty(),            // the exact text, carried by the task itself
                mode = mode,
                expectedPackage = step.args["package"]?.takeIf { it.isNotBlank() },
                allowAddressBar = step.args["addressBar"] == "true",
                config = typingConfig,
                isCancelled = { runner?.isCancelRequested == true },
                onState = { announce(friendly(it)) }
            )
            val r = session.run()
            val fg = JarvisAccessibilityService.instance?.foregroundPackageName().orEmpty()
            return when {
                r.success -> StepOutcome.Success(true, "text verified in $fg", "Text verified")
                r.failure == TypingFailure.PROTECTED_FIELD -> StepOutcome.Blocked(r.message)
                r.failure == TypingFailure.NO_EDITABLE_FIELD ->
                    StepOutcome.Blocked("${r.message} This app may not expose its text field to accessibility. Tap the field yourself.")
                r.failure == TypingFailure.BLANK_TEXT || r.failure == TypingFailure.CANCELLED -> StepOutcome.Fatal(r.message)
                else -> StepOutcome.Retryable(r.message)
            }
        }

        /** Re-inspect before a retry; the session relocates the field itself, nothing is retyped here. */
        override suspend fun recover(step: TaskStep, record: TaskRecord, reason: String) {
            host.awaitUiChange(300)
        }
    }

    private fun friendly(state: TypingState): String = when (state) {
        TypingState.NAVIGATING -> "Opening application..."
        TypingState.LOCATING_FIELD, TypingState.REQUESTING_FOCUS -> "Finding text field..."
        TypingState.WAITING_FOR_READY_FIELD -> "Waiting for input readiness..."
        TypingState.TYPING -> "Typing requested text..."
        TypingState.VERIFYING -> "Verifying entered text..."
        TypingState.RECOVERY -> "Recovering from an error..."
        else -> lastAnnounced
    }
}
