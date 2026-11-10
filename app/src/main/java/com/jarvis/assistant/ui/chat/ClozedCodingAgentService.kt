package com.jarvis.assistant.ui.chat

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.data.model.CodingToolBlock
import com.jarvis.assistant.tools.AgentToolApproval
import com.jarvis.assistant.tools.Confirmer
import com.jarvis.assistant.tools.ConfirmRequest
import com.jarvis.assistant.tools.Decision
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/** Process-level owner for a coding turn. Recreating ChatFragment does not destroy the agent loop. */
internal object ClozedCodingEngineHolder {
    @Volatile var backend: ClozedCodingAgentEngine? = null
    @Volatile var running: Boolean = false
    @Volatile var newSessionAfterCurrent: Boolean = false
    val bus = MutableSharedFlow<ClozedAgentEvent>(extraBufferCapacity = 256)
}

internal object CodingAgentPermissionBridge {
    @Volatile private var responder: Confirmer? = null
    private val waiters = CopyOnWriteArrayList<CompletableDeferred<Unit>>()
    val confirmer: Confirmer = object : Confirmer {
        override suspend fun confirm(request: ConfirmRequest): Decision {
            var active = responder
            if (active == null) {
                val signal = CompletableDeferred<Unit>()
                waiters += signal
                if (responder != null) signal.complete(Unit)
                try {
                    withTimeout(300_000L) { signal.await() }
                } catch (_: TimeoutCancellationException) {
                    return Decision.DENY
                } finally {
                    waiters.remove(signal)
                }
                active = responder
            }
            return active?.confirm(request) ?: Decision.DENY
        }
    }
    fun attach(value: Confirmer) {
        responder = value
        waiters.forEach { it.complete(Unit) }
    }
    fun detach(value: Confirmer) {
        if (responder === value) responder = null
    }
    fun hasResponder(): Boolean = responder != null
}

internal object CodingAgentUiBridge {
    @Volatile private var questionHandler: (suspend (String, List<String>) -> String?)? = null
    @Volatile var openProviderSettings: (() -> Unit)? = null
    private val waiters = CopyOnWriteArrayList<CompletableDeferred<Unit>>()

    fun attachQuestion(handler: suspend (String, List<String>) -> String?) {
        questionHandler = handler
        waiters.forEach { it.complete(Unit) }
    }

    fun detachQuestion() { questionHandler = null }

    suspend fun ask(question: String, options: List<String>): String? {
        var handler = questionHandler
        if (handler == null) {
            val signal = CompletableDeferred<Unit>()
            waiters += signal
            if (questionHandler != null) signal.complete(Unit)
            try {
                withTimeout(300_000L) { signal.await() }
            } catch (_: TimeoutCancellationException) {
                return null
            } finally {
                waiters.remove(signal)
            }
            handler = questionHandler
        }
        return handler?.invoke(question, options)
    }
}

class ClozedCodingAgentService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var engine: ClozedCodingAgentEngine? = null
    private var work: Job? = null
    private var pendingTurnId = ""
    private val response = StringBuilder()
    private val reasoning = StringBuilder()
    private val tools = ArrayList<CodingToolBlock>()
    private var plan = ""

    override fun onCreate() {
        super.onCreate()
        createChannel()
        AgentToolApproval.confirmer = CodingAgentPermissionBridge.confirmer
        startAgentForeground("Preparing coding workspace…")
        val backend = ClozedCodingAgentEngine(
            applicationContext,
            ::onAgentEvent,
            { question, options -> CodingAgentUiBridge.ask(question, options) },
            { CodingAgentUiBridge.openProviderSettings?.invoke() }
        )
        engine = backend
        ClozedCodingEngineHolder.backend = backend
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                if (work?.isActive == true) {
                    engine?.interrupt()
                    updateNotification("Stopping after the current request…")
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
            ACTION_NEW_SESSION -> {
                if (work?.isActive == true) {
                    ClozedCodingEngineHolder.newSessionAfterCurrent = true
                    engine?.interrupt()
                    updateNotification("Finishing current task before starting a new session…")
                } else {
                    engine?.newSession()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
            ACTION_SUBMIT -> {
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                val turnId = intent.getStringExtra(EXTRA_TURN_ID).orEmpty()
                if (text.isBlank() || turnId.isBlank()) return START_NOT_STICKY
                if (work?.isActive == true) {
                    JarvisApp.instance.chatRepository.finishPending(turnId, "The coding agent is already working on another request.")
                    return START_NOT_STICKY
                }
                pendingTurnId = turnId
                response.setLength(0)
                reasoning.setLength(0)
                tools.clear()
                plan = ""
                ClozedCodingEngineHolder.running = true
                updateNotification("Working in /storage/emulated/0/ClozedProjects")
                val backend = engine ?: return START_NOT_STICKY
                work = scope.launch {
                    try {
                        backend.submit(text)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        appendNotice("Coding agent failed: ${error.message ?: error.javaClass.simpleName}")
                    } finally {
                        if (ClozedCodingEngineHolder.newSessionAfterCurrent) {
                            backend.newSession()
                            ClozedCodingEngineHolder.newSessionAfterCurrent = false
                        }
                        ClozedCodingEngineHolder.running = false
                        if (pendingTurnId == turnId) {
                            JarvisApp.instance.chatRepository.finishPending(turnId, response.toString())
                            pendingTurnId = ""
                        }
                        updateNotification("Coding turn finished")
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf(startId)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    @Synchronized
    private fun onAgentEvent(event: ClozedAgentEvent) {
        ClozedCodingEngineHolder.bus.tryEmit(event)
        when (event.type) {
            "text" -> {
                response.append(event.text)
                JarvisApp.instance.chatRepository.updatePending(pendingTurnId, response.toString())
            }
            "reasoning" -> {
                reasoning.append(event.text)
                JarvisApp.instance.chatRepository.updatePendingCoding(pendingTurnId, reasoning.toString(), tools, plan)
            }
            "tool_start" -> {
                tools += CodingToolBlock(event.id, event.name, event.input, "", true, false)
                JarvisApp.instance.chatRepository.updatePendingCoding(pendingTurnId, reasoning.toString(), tools, plan)
            }
            "tool_end" -> {
                val index = tools.indexOfLast { it.id == event.id }
                if (index >= 0) tools[index] = tools[index].copy(output = event.output, running = false, ok = event.ok)
                JarvisApp.instance.chatRepository.updatePendingCoding(pendingTurnId, reasoning.toString(), tools, plan)
            }
            "todos" -> {
                plan = event.text
                JarvisApp.instance.chatRepository.updatePendingCoding(pendingTurnId, reasoning.toString(), tools, plan)
            }
            "session_transcript" -> {
                if (response.isNotEmpty()) response.append("\n\n")
                response.append(event.text)
                JarvisApp.instance.chatRepository.updatePending(pendingTurnId, response.toString())
            }
            "notice", "sessions" -> appendNotice(event.text)
            "state" -> updateNotification(
                when (event.text) {
                    "thinking" -> "Thinking…"
                    "working" -> "Running project tools…"
                    else -> "Finishing…"
                }
            )
        }
    }

    @Synchronized
    private fun appendNotice(text: String) {
        if (text.isBlank() || pendingTurnId.isBlank()) return
        if (response.isNotEmpty()) response.append("\n\n")
        response.append("> ⚠ ").append(text)
        JarvisApp.instance.chatRepository.updatePending(pendingTurnId, response.toString())
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "JARVIS Coding Agent", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setContentTitle("JARVIS Coding Agent")
        .setContentText(text)
        .setOngoing(ClozedCodingEngineHolder.running)
        .setOnlyAlertOnce(true)
        .addAction(
            android.R.drawable.ic_media_pause,
            "Stop",
            PendingIntent.getService(
                this, 7001,
                Intent(this, ClozedCodingAgentService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    private fun startAgentForeground(text: String) {
        val note = notification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, note)
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        engine?.interrupt()
        engine = null
        ClozedCodingEngineHolder.backend = null
        ClozedCodingEngineHolder.running = false
        if (!CodingAgentPermissionBridge.hasResponder() && AgentToolApproval.confirmer === CodingAgentPermissionBridge.confirmer) {
            AgentToolApproval.confirmer = null
        }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "jarvis_coding_agent"
        private const val NOTIFICATION_ID = 4821
        private const val ACTION_SUBMIT = "com.jarvis.assistant.coding.SUBMIT"
        private const val ACTION_STOP = "com.jarvis.assistant.coding.STOP"
        private const val ACTION_NEW_SESSION = "com.jarvis.assistant.coding.NEW_SESSION"
        private const val EXTRA_TEXT = "coding_text"
        private const val EXTRA_TURN_ID = "coding_turn_id"

        fun submit(context: android.content.Context, text: String, turnId: String) {
            val intent = Intent(context, ClozedCodingAgentService::class.java)
                .setAction(ACTION_SUBMIT)
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_TURN_ID, turnId)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: android.content.Context) {
            if (ClozedCodingEngineHolder.running) {
                context.startService(Intent(context, ClozedCodingAgentService::class.java).setAction(ACTION_STOP))
            }
        }

        fun newSession(context: android.content.Context) {
            context.startService(Intent(context, ClozedCodingAgentService::class.java).setAction(ACTION_NEW_SESSION))
        }

        fun isRunning(): Boolean = ClozedCodingEngineHolder.running
    }
}
