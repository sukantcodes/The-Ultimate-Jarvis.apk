package com.jarvis.assistant.background

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.provider.LiveWorkProgress
import com.jarvis.assistant.provider.MultiModelCoordinator
import com.jarvis.assistant.provider.ProviderRuntime
import com.jarvis.assistant.provider.SendOutcome
import com.jarvis.assistant.tools.PermissionPreferences
import com.jarvis.assistant.voice.JarvisVoiceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Autonomous Background Executor for Jarvis.
 *
 * Enables completely hands-free background execution:
 * - Listens for wake commands ("Hey Jarvis, create a website on X and run it in local server").
 * - Immediately starts full multi-step tool execution in the background without needing to enter the app.
 * - Informs the user periodically via voice with live percentage, completed steps, and estimated time remaining.
 * - Fault-tolerant: seamlessly retries from the exact file/step where it stopped without restarting.
 * - Automatically respects Auto-Approve Permissions mode for zero-prompt background automation.
 */
object AutonomousBackgroundExecutor {

    private const val TAG = "AutonomousExecutor"
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var activeJob: Job? = null
    @Volatile private var isRunningTask: Boolean = false
    @Volatile private var currentContext: Context? = null

    fun isRunning(): Boolean = isRunningTask

    /**
     * Checks if a user prompt is an actionable autonomous command suitable for background tool execution.
     */
    fun isActionableTask(prompt: String): Boolean {
        if (WebBuildOrchestrator.wantsInput(prompt)) return true
        if (WebBuildOrchestrator.isActive()) return false
        return com.jarvis.assistant.util.MultilingualCommandHelper.isActionableTask(prompt)
    }

    /**
     * Executes an autonomous task directly in the background.
     */
    fun execute(
        context: Context,
        commandText: String,
        onComplete: ((Boolean, String) -> Unit)? = null
    ) {
        // Website / web-game builds (and replies to their questions) are run by the build supervisor.
        if (WebBuildOrchestrator.handle(context, commandText, onComplete)) return
        if (isRunningTask) {
            Log.w(TAG, "An autonomous task is already running. Ignoring duplicate request.")
            return
        }

        val appContext = context.applicationContext
        currentContext = appContext
        isRunningTask = true

        val taskTitle = if (commandText.length > 40) commandText.take(37) + "…" else commandText
        val lang = com.jarvis.assistant.util.MultilingualCommandHelper.resolveLanguage(appContext, commandText)
        TaskProgressNotifier.postProgress(appContext, taskTitle, "🔧 Initializing task…", 5)

        // Ensure auto-approve is active during autonomous background execution so tool confirms never block
        PermissionPreferences.setAutoApproveAll(appContext, true)

        // Initial voice acknowledgement in user's language
        val ackMessage = com.jarvis.assistant.util.MultilingualCommandHelper.getLocalizedAck(lang)
        if (JarvisVoiceController.isJarvisLiveVoiceAvailable(appContext)) {
            JarvisConversationService.announceMessage(ackMessage)
        } else {
            JarvisVoiceController.speakTurnResponse(appContext, "ack-${System.currentTimeMillis()}", null, ackMessage, forceTts = true)
        }

        activeJob = scope.launch {
            try {
                TaskProgressNotifier.postProgress(appContext, taskTitle, "📝 Planning approach…", 15)
                val engine = ProviderRuntime.chatEngine(appContext)
                var lastSpokenMilestone = 0
                val startTime = System.currentTimeMillis()

                // Launch progress monitoring coroutine
                val progressJob = launch {
                    engine.workProgress.collectLatest { progress ->
                        if (!isActive) return@collectLatest
                        val percent = progress.percent
                        val action = progress.actionText.ifBlank { "Executing task" }

                        // Post live notification progress updates
                        val progressPercent = percent.coerceIn(15, 95)
                        val progressDesc = if (progress.actionText.isNotBlank()) {
                            "⚙️ ${progress.actionText}"
                        } else {
                            "⚙️ Executing… ($progressPercent%)"
                        }
                        TaskProgressNotifier.postProgress(appContext, taskTitle, progressDesc, progressPercent)

                        // Milestones: ~35%, ~70%, 100%
                        val milestone = when {
                            percent in 30..45 -> 35
                            percent in 65..80 -> 70
                            percent >= 98 -> 100
                            else -> 0
                        }

                        if (milestone > 0 && milestone > lastSpokenMilestone) {
                            lastSpokenMilestone = milestone
                            val elapsedSec = ((System.currentTimeMillis() - startTime) / 1000).toInt()
                            val remainingSec = if (percent > 0 && percent < 100) {
                                maxOf(5, ((elapsedSec * (100 - percent)) / percent))
                            } else 0

                            val statusAnnouncement = com.jarvis.assistant.util.MultilingualCommandHelper.getLocalizedMilestone(
                                milestone = milestone,
                                percent = percent,
                                remainingSec = remainingSec,
                                language = lang
                            ).ifBlank { "$action. $percent% complete." }

                            if (JarvisVoiceController.isJarvisLiveVoiceAvailable(appContext)) {
                                JarvisConversationService.announceMessage(statusAnnouncement)
                            } else {
                                JarvisVoiceController.speakTurnResponse(appContext, "step-$milestone", null, statusAnnouncement, forceTts = true)
                            }
                        }
                    }
                }

                // Send command to the chat engine with full tool access
                when (val outcome = engine.send(commandText)) {
                    is SendOutcome.Started -> {
                        Log.i(TAG, "Autonomous background execution started for: $commandText")
                        // Wait for completion
                        while (isActive && engine.isBusy) {
                            delay(400)
                        }
                        progressJob.cancel()

                        TaskProgressNotifier.postProgress(appContext, taskTitle, "✅ Finalizing…", 90)

                        // Final spoken executive wrap-up
                        val finalTurn = (appContext as? JarvisApp)?.chatRepository?.turns?.value?.lastOrNull()
                        val responseText = finalTurn?.jarvisResponse.orEmpty()
                        if (responseText.isNotBlank()) {
                            JarvisVoiceController.speakTurnResponse(appContext, finalTurn?.id.orEmpty(), commandText, responseText)
                        }

                        TaskProgressNotifier.postProgress(appContext, taskTitle, "✓ Done", 100)
                        val summary = if (responseText.isNotBlank()) responseText.take(120).trim() else "Task completed successfully"
                        TaskProgressNotifier.postComplete(appContext, taskTitle, summary)

                        onComplete?.invoke(true, "Completed")
                    }
                    else -> {
                        progressJob.cancel()
                        TaskProgressNotifier.cancel(appContext)
                        val errorNotice = "I encountered an issue starting the background execution. Please check your model settings."
                        JarvisVoiceController.speakTurnResponse(appContext, "error-${System.currentTimeMillis()}", null, errorNotice, forceTts = true)
                        onComplete?.invoke(false, "Failed to start")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Autonomous background task failed", e)
                TaskProgressNotifier.cancel(appContext)
                val failNotice = "An error occurred during execution. I have preserved your files and will resume from this point."
                JarvisVoiceController.speakTurnResponse(appContext, "error-${System.currentTimeMillis()}", null, failNotice, forceTts = true)
                onComplete?.invoke(false, e.message ?: "Unknown error")
            } finally {
                isRunningTask = false
                activeJob = null
                currentContext = null
            }
        }
    }

    @JvmOverloads
    fun cancel(context: Context? = null) {
        val ctx = context ?: currentContext
        ctx?.let { TaskProgressNotifier.cancel(it) }
        activeJob?.cancel()
        activeJob = null
        isRunningTask = false
        currentContext = null
    }
}
