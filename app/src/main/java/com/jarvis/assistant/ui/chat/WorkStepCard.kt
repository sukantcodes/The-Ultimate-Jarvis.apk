package com.jarvis.assistant.ui.chat

import com.jarvis.assistant.provider.LiveWorkProgress

data class WorkStepCard(
    val id: String,
    val type: WorkActionType,
    val title: String,
    val status: WorkStepStatus,
    val codeSnippet: String? = null
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
    get() = null
