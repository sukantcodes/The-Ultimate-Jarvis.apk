package com.jarvis.assistant.tts.model

/**
 * Lifecycle and progress states for neural model downloads and extraction.
 */
sealed class DownloadState {
    data object Idle : DownloadState()

    data class Downloading(
        val progress: Int,
        val bytesDownloaded: Long,
        val totalBytes: Long
    ) : DownloadState()

    data class Extracting(
        val currentFile: String = ""
    ) : DownloadState()

    data object Ready : DownloadState()

    data class Error(
        val message: String,
        val throwable: Throwable? = null
    ) : DownloadState()
}
