package com.jarvis.assistant.provider

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class StepState { RUNNING, DONE, FAILED }

/** One thing Jarvis did while answering: a tool call with what was asked of it and what came back. */
data class ActivityStep(
    val id: String,
    val toolName: String,
    val target: String,
    val language: String,
    val state: StepState,
    val request: String,
    val response: String? = null
)

/** Everything Jarvis did for one chat turn. The ChatBox draws this inside that turn's message. */
data class TurnActivity(
    val turnId: String,
    val startedAt: Long,
    val endedAt: Long = 0L,
    val steps: List<ActivityStep> = emptyList(),
    /** 0..100 while the work reports progress, -1 when it does not. */
    val percent: Int = -1,
    val failed: Boolean = false
) {
    val running: Boolean get() = endedAt == 0L
    fun elapsedMs(now: Long): Long = ((if (endedAt > 0L) endedAt else now) - startedAt).coerceAtLeast(0L)
}

/**
 * Keeps the live activity of the latest chat turns. It has no Android code so it can be unit tested; the chat engine
 * writes to it and the ChatBox only reads it.
 */
class WorkActivityStore(private val clock: () -> Long = System::currentTimeMillis, private val maxTurns: Int = 40) {
    private val _all = MutableStateFlow<Map<String, TurnActivity>>(emptyMap())
    val all: StateFlow<Map<String, TurnActivity>> = _all.asStateFlow()

    private var seq = 0

    fun get(turnId: String): TurnActivity? = _all.value[turnId]

    fun begin(turnId: String) {
        put(TurnActivity(turnId = turnId, startedAt = clock()))
    }

    /** A tool step started (isWriting = true) or finished (false). A finish settles the matching running step. */
    fun onStep(turnId: String, step: LiveToolStep) {
        val cur = get(turnId) ?: return
        if (!cur.running) return
        val steps = cur.steps.toMutableList()
        if (step.isWriting) {
            // A new step starting means any step still marked running was left behind; close it quietly.
            for (i in steps.indices) if (steps[i].state == StepState.RUNNING) steps[i] = steps[i].copy(state = StepState.DONE)
            val request = buildString {
                append(step.toolName).append(' ').append(step.target.take(300))
                if (step.toolName in WRITE_TOOLS && step.codeOrOutput.isNotBlank()) append("\n\n").append(step.codeOrOutput)
            }
            steps += ActivityStep("s${++seq}", step.toolName, step.target, step.language, StepState.RUNNING, request)
        } else {
            val idx = steps.indexOfLast { it.state == StepState.RUNNING && it.toolName == step.toolName && it.target == step.target }
            if (idx >= 0) {
                val writes = step.toolName in WRITE_TOOLS || step.toolName in QUIET_TOOLS
                steps[idx] = steps[idx].copy(
                    state = if (step.failed) StepState.FAILED else StepState.DONE,
                    response = if (writes) (if (step.failed) "The tool reported an error." else null) else step.codeOrOutput
                )
            }
        }
        put(cur.copy(steps = steps))
    }

    fun setProgress(turnId: String, percent: Int) {
        val cur = get(turnId) ?: return
        if (!cur.running || cur.percent == percent) return
        put(cur.copy(percent = percent.coerceIn(0, 100)))
    }

    fun finish(turnId: String, failed: Boolean) {
        val cur = get(turnId) ?: return
        if (!cur.running) return
        val steps = cur.steps.map { if (it.state == StepState.RUNNING) it.copy(state = if (failed) StepState.FAILED else StepState.DONE) else it }
        put(cur.copy(endedAt = clock(), failed = failed, steps = steps, percent = -1))
    }

    private fun put(a: TurnActivity) {
        val map = LinkedHashMap(_all.value)
        map.remove(a.turnId)
        map[a.turnId] = a
        while (map.size > maxTurns) map.remove(map.keys.first())
        _all.value = map
    }

    private companion object {
        val WRITE_TOOLS = setOf("write_file", "edit_file")
        val QUIET_TOOLS = setOf("make_directory", "delete_path", "move_path")
    }
}
