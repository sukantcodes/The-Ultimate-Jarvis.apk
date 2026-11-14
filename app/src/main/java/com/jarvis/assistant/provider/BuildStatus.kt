package com.jarvis.assistant.provider

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where a piece of work stands. The order is the order things normally happen in. */
enum class BuildStage(val label: String) {
    IDLE("Idle"),
    CHECKING("Checking model"),
    SENT("Sent to provider"),
    GENERATING("Generating"),
    WRITING("Writing files"),
    DONE("Done"),
    FAILED("Failed"),
    STOPPED("Stopped");

    val isActive: Boolean get() = this == CHECKING || this == SENT || this == GENERATING || this == WRITING
    val isFinished: Boolean get() = this == DONE || this == FAILED || this == STOPPED
}

/**
 * A snapshot for the status card. [requestSent] is the honest answer to "did anything actually go to the provider":
 * it only turns true once the request was handed to the provider's client, so a card that stays on
 * "Checking model" shows at a glance that nothing was sent.
 */
data class BuildStatus(
    val stage: BuildStage = BuildStage.IDLE,
    val turnId: String? = null,
    val provider: String = "",
    val model: String = "",
    val requestSent: Boolean = false,
    val toolSteps: Int = 0,
    val filesWritten: List<String> = emptyList(),
    val currentTarget: String = "",
    val startedAt: Long = 0L,
    val finishedAt: Long = 0L,
    val message: String = ""
) {
    val isVisible: Boolean get() = stage != BuildStage.IDLE
    fun elapsedMs(now: Long): Long = ((if (finishedAt > 0L) finishedAt else now) - startedAt).coerceAtLeast(0L)
}

/**
 * Keeps the [BuildStatus] of the answer or build in progress. It has no Android code so it can be unit tested; the
 * chat engine calls it at each step and the ChatBox status card only reads [status].
 */
class BuildStatusTracker(private val clock: () -> Long = System::currentTimeMillis) {
    private val _status = MutableStateFlow(BuildStatus())
    val status: StateFlow<BuildStatus> = _status.asStateFlow()

    fun begin(turnId: String, provider: String, model: String) {
        _status.value = BuildStatus(stage = BuildStage.CHECKING, turnId = turnId, provider = provider, model = model, startedAt = clock(), message = "Checking that the model is ready")
    }

    /** The request was handed to the provider's client. */
    fun requestSent() {
        val s = _status.value
        if (!s.stage.isActive) return
        _status.value = s.copy(stage = BuildStage.SENT, requestSent = true, message = "Waiting for ${s.provider.ifBlank { "the provider" }} to answer")
    }

    /** Text or a tool step came back, so the provider is really working. */
    fun generating(target: String = "") {
        val s = _status.value
        if (!s.stage.isActive) return
        if (s.stage == BuildStage.WRITING) return
        _status.value = s.copy(stage = BuildStage.GENERATING, requestSent = true, currentTarget = target, message = "Generating the answer")
    }

    /** A tool ran. File tools move the card to "Writing files" and are counted. */
    fun toolStep(toolName: String, target: String, isWriting: Boolean) {
        val s = _status.value
        if (!s.stage.isActive) return
        val writes = isWriting || toolName in WRITE_TOOLS
        val files = if (writes && target.isNotBlank() && target !in s.filesWritten) s.filesWritten + target else s.filesWritten
        _status.value = s.copy(
            stage = if (writes) BuildStage.WRITING else BuildStage.GENERATING,
            requestSent = true,
            toolSteps = s.toolSteps + 1,
            filesWritten = files,
            currentTarget = target,
            message = if (writes) "Writing ${target.ifBlank { "files" }}" else "Running ${toolName.ifBlank { "a step" }}"
        )
    }

    fun done() = finish(BuildStage.DONE, "Finished")

    fun failed(reason: String) = finish(BuildStage.FAILED, reason.ifBlank { "The request failed" })

    fun stopped() = finish(BuildStage.STOPPED, "Stopped before it finished")

    fun hide() { _status.value = BuildStatus() }

    private fun finish(stage: BuildStage, message: String) {
        val s = _status.value
        if (s.stage == BuildStage.IDLE) return
        _status.value = s.copy(stage = stage, finishedAt = clock(), message = message, currentTarget = "")
    }

    private companion object {
        val WRITE_TOOLS = setOf("write_file", "append_file", "edit_file", "make_directory", "move_path", "create_document", "project_create")
    }
}
