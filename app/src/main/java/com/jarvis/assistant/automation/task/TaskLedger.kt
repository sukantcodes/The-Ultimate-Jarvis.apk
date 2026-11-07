package com.jarvis.assistant.automation.task

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class StepStatus { PENDING, RUNNING, COMPLETED, FAILED, BLOCKED, CANCELLED }

enum class TaskStatus { PLANNED, RUNNING, WAITING_FOR_USER, COMPLETED, FAILED, CANCELLED;
    val terminal: Boolean get() = this == COMPLETED || this == FAILED || this == CANCELLED
}

/** One unit of work. [action] selects the handler; [args] carry values such as the exact text to type. */
data class TaskStep(
    val id: String,
    val title: String,
    val action: String,
    val args: Map<String, String> = emptyMap(),
    val dependsOn: List<String> = emptyList(),
    /** False for sends, calls, payments, deletes, submits: these are never blindly repeated. */
    val idempotent: Boolean = true,
    /** True when the user must approve before the step runs. */
    val requiresConfirmation: Boolean = false,
    val status: StepStatus = StepStatus.PENDING,
    val retries: Int = 0,
    val maxRetries: Int = 3,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val result: String? = null,
    val failure: String? = null
)

/** Everything needed to resume a task after the process dies. */
data class TaskRecord(
    val taskId: String,
    val objective: String,
    /** Original task context (app, exact text, mode, ...). Scrubbed when the task reaches a terminal state. */
    val context: Map<String, String>,
    val steps: List<TaskStep>,
    val status: TaskStatus = TaskStatus.PLANNED,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    val lastVerifiedState: String? = null,
    val finalMessage: String? = null
) {
    val currentStep: TaskStep?
        get() = steps.firstOrNull { it.status == StepStatus.RUNNING || it.status == StepStatus.BLOCKED }
            ?: steps.firstOrNull { it.status == StepStatus.PENDING }

    val completed: List<TaskStep> get() = steps.filter { it.status == StepStatus.COMPLETED }
    val pending: List<TaskStep> get() = steps.filter { it.status == StepStatus.PENDING }
    val failed: List<TaskStep> get() = steps.filter { it.status == StepStatus.FAILED }
    val blocked: List<TaskStep> get() = steps.filter { it.status == StepStatus.BLOCKED }

    /** Next pending step whose dependencies are all verified complete. */
    fun nextRunnable(): TaskStep? {
        val done = completed.map { it.id }.toSet()
        return steps.firstOrNull { it.status == StepStatus.PENDING && done.containsAll(it.dependsOn) }
    }

    fun withStep(updated: TaskStep): TaskRecord = copy(steps = steps.map { if (it.id == updated.id) updated else it })

    /** Compact state handed to the AI before it chooses its next action. Contains no typed text. */
    fun promptSummary(): String {
        val cur = currentStep
        val index = cur?.let { steps.indexOf(it) + 1 } ?: steps.size
        return buildString {
            append("TASK_STATE id=").append(taskId.take(8))
            append(" status=").append(status)
            append(" step=").append(index).append('/').append(steps.size)
            cur?.let { append(" current=\"").append(it.title).append("\" (").append(it.status).append(')') }
            if (completed.isNotEmpty()) append(" completed=[").append(completed.joinToString { it.title }).append(']')
            if (pending.isNotEmpty()) append(" pending=[").append(pending.joinToString { it.title }).append(']')
            if (blocked.isNotEmpty()) append(" blocked=[").append(blocked.joinToString { "${it.title}: ${it.failure.orEmpty()}" }).append(']')
            if (failed.isNotEmpty()) append(" failed=[").append(failed.joinToString { "${it.title}: ${it.failure.orEmpty()}" }).append(']')
            lastVerifiedState?.let { append(" last_verified=\"").append(it).append('"') }
            append(". Do not redo completed steps; continue from the current step.")
        }
    }

    /** Terminal tasks keep their outcome but drop user-supplied values (typed text etc.). */
    fun scrubbed(): TaskRecord = copy(
        context = context.mapValues { "" },
        steps = steps.map { s -> s.copy(args = s.args.mapValues { "" }) }
    )
}

/** JSON persistence for [TaskRecord]. */
object TaskCodec {
    fun encode(r: TaskRecord): String = JSONObject().apply {
        put("taskId", r.taskId)
        put("objective", r.objective)
        put("context", JSONObject(r.context))
        put("status", r.status.name)
        put("createdAt", r.createdAt)
        put("updatedAt", r.updatedAt)
        put("lastVerifiedState", r.lastVerifiedState ?: JSONObject.NULL)
        put("finalMessage", r.finalMessage ?: JSONObject.NULL)
        put("steps", JSONArray().also { arr -> r.steps.forEach { arr.put(encodeStep(it)) } })
    }.toString()

    private fun encodeStep(s: TaskStep) = JSONObject().apply {
        put("id", s.id); put("title", s.title); put("action", s.action)
        put("args", JSONObject(s.args))
        put("dependsOn", JSONArray(s.dependsOn))
        put("idempotent", s.idempotent); put("requiresConfirmation", s.requiresConfirmation)
        put("status", s.status.name); put("retries", s.retries); put("maxRetries", s.maxRetries)
        put("startedAt", s.startedAt ?: JSONObject.NULL)
        put("completedAt", s.completedAt ?: JSONObject.NULL)
        put("result", s.result ?: JSONObject.NULL)
        put("failure", s.failure ?: JSONObject.NULL)
    }

    fun decode(json: String): TaskRecord? = try {
        val o = JSONObject(json)
        val steps = o.getJSONArray("steps").let { a -> (0 until a.length()).map { decodeStep(a.getJSONObject(it)) } }
        TaskRecord(
            taskId = o.getString("taskId"),
            objective = o.getString("objective"),
            context = o.getJSONObject("context").toStringMap(),
            steps = steps,
            status = TaskStatus.valueOf(o.getString("status")),
            createdAt = o.getLong("createdAt"),
            updatedAt = o.getLong("updatedAt"),
            lastVerifiedState = o.optStringOrNull("lastVerifiedState"),
            finalMessage = o.optStringOrNull("finalMessage")
        )
    } catch (_: Exception) { null }

    private fun decodeStep(o: JSONObject) = TaskStep(
        id = o.getString("id"), title = o.getString("title"), action = o.getString("action"),
        args = o.getJSONObject("args").toStringMap(),
        dependsOn = o.getJSONArray("dependsOn").let { a -> (0 until a.length()).map { a.getString(it) } },
        idempotent = o.getBoolean("idempotent"), requiresConfirmation = o.getBoolean("requiresConfirmation"),
        status = StepStatus.valueOf(o.getString("status")), retries = o.getInt("retries"), maxRetries = o.getInt("maxRetries"),
        startedAt = if (o.isNull("startedAt")) null else o.getLong("startedAt"),
        completedAt = if (o.isNull("completedAt")) null else o.getLong("completedAt"),
        result = o.optStringOrNull("result"), failure = o.optStringOrNull("failure")
    )

    private fun JSONObject.optStringOrNull(k: String): String? = if (isNull(k)) null else getString(k)
    private fun JSONObject.toStringMap(): Map<String, String> =
        keys().asSequence().associateWith { getString(it) }
}

/** Where task progress lives so it survives process death. */
interface TaskStore {
    fun save(record: TaskRecord)
    fun load(taskId: String): TaskRecord?
    /** The most recent task that is still unfinished, if any. */
    fun loadUnfinished(): TaskRecord?
    fun latest(): TaskRecord?
}

class InMemoryTaskStore : TaskStore {
    private val map = LinkedHashMap<String, TaskRecord>()
    @Synchronized override fun save(record: TaskRecord) { map[record.taskId] = TaskCodec.decode(TaskCodec.encode(record)) ?: record }
    @Synchronized override fun load(taskId: String) = map[taskId]
    @Synchronized override fun loadUnfinished() = map.values.lastOrNull { !it.status.terminal }
    @Synchronized override fun latest() = map.values.lastOrNull()
}

/** App-private SharedPreferences store: one record per task, only the latest few are kept. */
class PrefsTaskStore(context: Context) : TaskStore {
    private val prefs = context.applicationContext.getSharedPreferences("jarvis_task_ledger", Context.MODE_PRIVATE)

    @Synchronized override fun save(record: TaskRecord) {
        val order = prefs.getString(KEY_ORDER, "").orEmpty().split(',').filter { it.isNotBlank() }.toMutableList()
        order.remove(record.taskId); order.add(record.taskId)
        val ed = prefs.edit()
        while (order.size > KEEP) ed.remove(KEY_PREFIX + order.removeAt(0))
        ed.putString(KEY_PREFIX + record.taskId, TaskCodec.encode(record))
        ed.putString(KEY_ORDER, order.joinToString(","))
        ed.apply()
    }

    @Synchronized override fun load(taskId: String): TaskRecord? =
        prefs.getString(KEY_PREFIX + taskId, null)?.let { TaskCodec.decode(it) }

    @Synchronized override fun latest(): TaskRecord? =
        prefs.getString(KEY_ORDER, "").orEmpty().split(',').lastOrNull { it.isNotBlank() }?.let { load(it) }

    @Synchronized override fun loadUnfinished(): TaskRecord? = latest()?.takeIf { !it.status.terminal }

    private companion object { const val KEY_PREFIX = "task_"; const val KEY_ORDER = "order"; const val KEEP = 5 }
}
