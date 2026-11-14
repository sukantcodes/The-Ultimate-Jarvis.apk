package com.jarvis.assistant.background

import android.content.Context
import com.jarvis.assistant.provider.EngineState
import com.jarvis.assistant.provider.ProviderRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Follows ONE job that Jarvis handed to the Chatbox. It never starts, retries, resumes or improves anything: the
 * Chatbox model does the work, and this only remembers how it is going so Jarvis can answer "how much is done?"
 * and say once, in the same conversation, that the work finished.
 */
object ChatboxHandoffTracker {

    enum class Phase { NONE, WORKING, DONE, STOPPED, FAILED }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var phase = Phase.NONE
    @Volatile private var providerLabel = ""
    @Volatile private var userStopped = false
    @Volatile private var watcher: Job? = null
    @Volatile private var progressWatcher: Job? = null

    fun isWorking(): Boolean = phase == Phase.WORKING

    /** Called when the owner taps pause/stop in the Chatbox, so the end of the work is read as "stopped", not "done". */
    fun noteUserStop() {
        if (phase == Phase.WORKING) userStopped = true
    }

    /**
     * Starts following the job that was just sent. [onFinished] runs once, only when the work really finished
     * ([Phase.DONE]); a stop or a failure is kept quiet until the owner asks.
     */
    fun begin(context: Context, label: String, onFinished: (Phase) -> Unit) {
        val engine = ProviderRuntime.chatEngine(context.applicationContext)
        watcher?.cancel()
        progressWatcher?.cancel()
        phase = Phase.WORKING
        providerLabel = label
        userStopped = false
        watcher = scope.launch {
            val end = engine.state.first { it !is EngineState.Streaming }
            phase = when {
                userStopped -> Phase.STOPPED
                end is EngineState.Failed -> Phase.FAILED
                else -> Phase.DONE
            }
            progressWatcher?.cancel()
            if (phase == Phase.DONE) onFinished(phase)
        }
    }

    /** What Jarvis may say when the owner asks how the work is going. Honest about every state. */
    fun statusText(context: Context): String {
        val progress = ProviderRuntime.chatEngine(context.applicationContext).workProgress.value
        return when (phase) {
            Phase.NONE -> "Nothing has been handed to the ChatBox in this session."
            Phase.WORKING -> {
                val doing = listOf(progress.actionText, progress.target).filter { it.isNotBlank() }.joinToString(" ")
                "The ChatBox model ($providerLabel) is still working" +
                    (if (progress.percent in 1..99) ", about ${progress.percent}% done" else "") +
                    (if (doing.isNotBlank()) ". Right now: $doing." else ".")
            }
            Phase.DONE -> "The ChatBox work is finished."
            Phase.STOPPED -> "The ChatBox work was stopped by the owner. It was not restarted."
            Phase.FAILED -> "The ChatBox work stopped with an error. It was not retried; the owner can retry it in the ChatBox."
        }
    }

    /** One short spoken line, in the language the owner is speaking, for when the work has finished. */
    fun completionLine(language: String): String {
        val l = language.lowercase(Locale.ROOT)
        return when {
            l.contains("bengali") || l.contains("bangla") -> "স্যার, আপনার কাজ শেষ হয়েছে আর প্রিভিউ খুলে গেছে।"
            l.contains("hinglish") -> "Sir, aapka kaam complete ho gaya hai aur preview khul gaya hai."
            l.contains("hindi") -> "सर, आपका काम पूरा हो गया है और प्रीव्यू खुल गया है।"
            else -> "Sir, your work is complete and the preview is open."
        }
    }
}
