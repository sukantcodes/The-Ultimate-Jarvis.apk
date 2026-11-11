package com.jarvis.assistant.tools.tasks

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.jarvis.assistant.provider.ProviderErrorKind
import com.jarvis.assistant.provider.ProviderException
import com.jarvis.assistant.tools.*
import com.jarvis.assistant.tools.builtin.int
import com.jarvis.assistant.tools.builtin.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.File

enum class StepStatus { PENDING, RUNNING, DONE, FAILED, SKIPPED }
enum class TaskState { PLANNING, RUNNING, PAUSED, DONE, FAILED, CANCELLED }

data class TaskStep(val index: Int, val title: String, val status: StepStatus = StepStatus.PENDING, val note: String = "")
data class TaskRecord(val id: String, val goal: String, val steps: List<TaskStep>, val state: TaskState, val segments: Int, val updatedAt: Long, val log: List<String> = emptyList()) {
    val remaining get() = steps.filter { it.status == StepStatus.PENDING || it.status == StepStatus.RUNNING }
    fun summary() = "Task ${id}: $goal [${state.name.lowercase()}]\n" + steps.joinToString("\n") { "${it.index}. [${it.status.name.lowercase()}] ${it.title}" + if (it.note.isNotBlank()) " — ${it.note}" else "" }
}

/** Saves task plans as one small JSON file each, so work survives the app being closed and can be resumed. */
class TaskStore(private val dir: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    @Synchronized fun save(t: TaskRecord) { dir.mkdirs(); val f = File(dir, "${t.id}.json"); val tmp = File(dir, "${t.id}.tmp"); tmp.writeText(gson.toJson(t.copy(updatedAt = clock()))); if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() } }
    @Synchronized fun load(id: String): TaskRecord? = try { File(dir, "$id.json").takeIf { it.isFile }?.let { gson.fromJson(it.readText(), TaskRecord::class.java) } } catch (e: Exception) { null }
    @Synchronized fun list(): List<TaskRecord> = dir.listFiles { f -> f.extension == "json" }?.mapNotNull { runCatching { gson.fromJson(it.readText(), TaskRecord::class.java) }.getOrNull() }?.sortedByDescending { it.updatedAt }.orEmpty()
    @Synchronized fun create(goal: String, steps: List<String>): TaskRecord = TaskRecord("t${clock()}", goal.take(300), steps.take(50).mapIndexed { i, s -> TaskStep(i + 1, s.take(200)) }, TaskState.RUNNING, 0, clock()).also { save(it) }
    @Synchronized fun update(id: String, f: (TaskRecord) -> TaskRecord): TaskRecord? = load(id)?.let { f(it) }?.also { save(it) }
    @Synchronized fun delete(id: String) = File(dir, "$id.json").delete()
}

/** Runs one stretch of model+tool work for a task. In the app this is the tool-calling loop. */
fun interface SegmentRunner { suspend fun run(prompt: String, onActivity: (String) -> Unit): String }

class RunLimits(val maxSegments: Int = 20, val maxRetriesPerSegment: Int = 3, val maxMillis: Long = 30 * 60_000L, val baseDelayMs: Long = 2_000L)

/**
 * Keeps working on a task until every step is finished, one stretch at a time. After each stretch the plan is saved.
 * A temporary failure (rate limit, network, server) is retried with a growing pause; a permanent one (rejected key,
 * billing) stops and is reported. It stops on cancellation, on the time/segment budget, and when a stretch makes no
 * progress twice in a row, so it can never spin forever. Everything done is visible in the task's log.
 */
class TaskEngine(private val store: TaskStore, private val runner: SegmentRunner, private val limits: RunLimits = RunLimits(), private val clock: () -> Long = System::currentTimeMillis,
                 private val pause: suspend (Long) -> Unit = { delay(it) }) {

    suspend fun run(taskId: String, onProgress: (TaskRecord) -> Unit = {}): TaskRecord {
        val started = clock(); var stalls = 0
        var t = store.load(taskId) ?: throw IllegalArgumentException("No such task.")
        t = store.update(taskId) { it.copy(state = TaskState.RUNNING, log = it.log + "Started or resumed.") }!!
        while (true) {
            try { currentCoroutineContext().ensureActive() } catch (e: CancellationException) { store.update(taskId) { it.copy(state = TaskState.CANCELLED, log = it.log + "Cancelled by the user.") }; throw e }
            if (t.remaining.isEmpty()) return finish(taskId, TaskState.DONE, "All steps finished.", onProgress)
            if (t.segments >= limits.maxSegments) return finish(taskId, TaskState.PAUSED, "Paused after ${limits.maxSegments} stretches of work. Resume to keep going.", onProgress)
            if (clock() - started > limits.maxMillis) return finish(taskId, TaskState.PAUSED, "Paused: the time budget was used. Resume to keep going.", onProgress)
            val before = t.steps.map { it.status }
            var attempt = 0; var output: String? = null
            while (output == null) {
                attempt++
                try { output = runner.run(promptFor(t), { a -> onProgress(t.copy(log = t.log + a)) }) }
                catch (e: CancellationException) { store.update(taskId) { it.copy(state = TaskState.CANCELLED, log = it.log + "Cancelled by the user.") }; throw e }
                catch (e: ProviderException) {
                    val transient = e.error.kind in setOf(ProviderErrorKind.RATE_LIMITED, ProviderErrorKind.UNAVAILABLE, ProviderErrorKind.TIMEOUT, ProviderErrorKind.STREAM_INTERRUPTED)
                    if (!transient) return finish(taskId, TaskState.FAILED, "Stopped: ${e.error.message}", onProgress)
                    if (attempt > limits.maxRetriesPerSegment) return finish(taskId, TaskState.PAUSED, "Paused after $attempt tries: ${e.error.message}. Resume later.", onProgress)
                    store.update(taskId) { it.copy(log = it.log + "Temporary problem (${e.error.kind.name.lowercase()}); retrying ($attempt).") }
                    pause(limits.baseDelayMs * (1L shl (attempt - 1)))
                } catch (e: Exception) { return finish(taskId, TaskState.FAILED, "Stopped: ${e.javaClass.simpleName}: ${e.message?.take(120)}", onProgress) }
            }
            // The model records progress through task_update; re-read the saved plan.
            t = store.update(taskId) { it.copy(segments = it.segments + 1, log = it.log + "Stretch ${it.segments + 1}: ${output.take(160).replace('\n', ' ')}") }!!
            onProgress(t)
            stalls = if (t.steps.map { it.status } == before) stalls + 1 else 0
            if (stalls >= 2) return finish(taskId, TaskState.PAUSED, "Paused: two stretches in a row made no progress on the plan.", onProgress)
        }
    }

    private fun finish(id: String, state: TaskState, note: String, cb: (TaskRecord) -> Unit): TaskRecord = store.update(id) { it.copy(state = state, log = it.log + note) }!!.also(cb)

    private fun promptFor(t: TaskRecord) = "Continue this task. Goal: ${t.goal}\n\nCurrent plan:\n${t.summary()}\n\n" +
        "Do the next unfinished step(s) with your tools, then call task_update for each step you finish (status done, failed or skipped, with a short note). " +
        "Do not repeat steps already done. When everything is finished, say so briefly."
}

class TaskTools(private val store: TaskStore) {
    fun all(): List<Tool> = listOf(Plan(), Update(), Status())
    inner class Plan : Tool {
        override val spec = ToolSpec("task_plan", "Create a saved plan for a long task: a goal and an ordered list of steps. Returns the task id.", listOf(ToolParam("goal", required = true), ToolParam("steps", "array", "Step titles in order.", required = true)), Risk.WRITE, "tasks")
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult { val steps = args.getAsJsonArray("steps")?.map { it.asString }.orEmpty(); if (steps.isEmpty()) return ToolResult.Error("steps is required"); val t = store.create(args.str("goal").orEmpty(), steps); return ToolResult.Ok("Created ${t.id}.\n${t.summary()}") }
    }
    inner class Update : Tool {
        override val spec = ToolSpec("task_update", "Mark a step of a saved task as done, failed, skipped or running, with a short note.", listOf(ToolParam("task_id", required = true), ToolParam("step", "integer", required = true), ToolParam("status", required = true, enumValues = listOf("done", "failed", "skipped", "running", "pending")), ToolParam("note")), Risk.WRITE, "tasks")
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val status = StepStatus.values().firstOrNull { it.name.equals(args.str("status"), true) } ?: return ToolResult.Error("Unknown status")
            val n = args.int("step") ?: return ToolResult.Error("step is required")
            val t = store.update(args.str("task_id").orEmpty()) { r -> r.copy(steps = r.steps.map { if (it.index == n) it.copy(status = status, note = args.str("note")?.take(200) ?: it.note) else it }) } ?: return ToolResult.Error("No such task.")
            return if (t.steps.none { it.index == n }) ToolResult.Error("No step $n.") else ToolResult.Ok(t.summary())
        }
    }
    inner class Status : Tool {
        override val spec = ToolSpec("task_status", "Show saved tasks, or one task by id.", listOf(ToolParam("task_id")), Risk.READ, "tasks")
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult { val id = args.str("task_id"); return if (id != null) store.load(id)?.let { ToolResult.Ok(it.summary() + "\n\nLog:\n" + it.log.takeLast(15).joinToString("\n")) } ?: ToolResult.Error("No such task.") else ToolResult.Ok(store.list().joinToString("\n\n") { it.summary() }.ifBlank { "No tasks." }) }
    }
}

/** Starts, watches and stops background task runs. Runs live in [scope]; the plan is saved after every stretch, so a killed app can resume. */
class TaskRunner(private val store: TaskStore, private val scope: kotlinx.coroutines.CoroutineScope, private val runner: () -> SegmentRunner,
                 private val limits: RunLimits = RunLimits(), private val onFinished: (TaskRecord) -> Unit = {}) {
    private val jobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    fun isRunning(id: String) = jobs[id]?.isActive == true

    @Synchronized fun start(id: String): String? {
        if (store.load(id) == null) return "No such task."
        if (isRunning(id)) return "That task is already running."
        jobs[id] = scope.launch(kotlinx.coroutines.Dispatchers.Default) {
            try { onFinished(TaskEngine(store, runner(), limits).run(id)) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                store.update(id) { it.copy(state = TaskState.FAILED, log = it.log + "Stopped: ${e.javaClass.simpleName}") }
            }
        }
        return null
    }
    fun cancel(id: String): Boolean { val j = jobs[id] ?: return false; j.cancel(); return true }
}

fun TaskTools.runTools(runner: TaskRunner, store: TaskStore): List<Tool> = listOf(
    object : Tool {
        override val spec = ToolSpec("task_run", "Start (or resume) working on a saved task in the background, stretch by stretch, until its steps are done. Returns at once; check with task_status.", listOf(ToolParam("task_id", required = true)), Risk.EXECUTE, "tasks")
        override fun describe(args: JsonObject) = "Run task ${args.str("task_id")} in the background"
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult = runner.start(args.str("task_id").orEmpty())?.let { ToolResult.Error(it) } ?: ToolResult.Ok("Task started. It works in the background and saves progress after each stretch; ask for task_status to see it.")
    },
    object : Tool {
        override val spec = ToolSpec("task_cancel", "Stop a running background task. Its plan stays saved and can be resumed.", listOf(ToolParam("task_id", required = true)), Risk.WRITE, "tasks")
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult = if (runner.cancel(args.str("task_id").orEmpty())) ToolResult.Ok("Cancelling.") else ToolResult.Error("That task is not running.")
    }
)
