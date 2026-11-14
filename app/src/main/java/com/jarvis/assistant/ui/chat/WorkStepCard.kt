package com.jarvis.assistant.ui.chat

import com.jarvis.assistant.provider.LiveWorkProgress

data class WorkStepCard(
    val id: String,
    val type: WorkActionType = WorkActionType.CODE_SYNTHESIS,
    val title: String,
    val status: WorkStepStatus = WorkStepStatus.IN_PROGRESS,
    val codeSnippet: String? = null,
    val requestCode: String? = null,
    val responseCode: String? = null,
    var isExpanded: Boolean = false
)

enum class WorkStepStatus { IN_PROGRESS, DONE, ERROR }

val LiveWorkProgress.stepName: String
    get() = if (target.isNotBlank()) {
        "${actionText.ifBlank { "Task" }}: $target"
    } else if (actionText.isNotBlank()) {
        actionText
    } else {
        "Working…"
    }

val LiveWorkProgress.codeSnippet: String?
    get() = codeOrOutput.takeIf { it.isNotBlank() }
