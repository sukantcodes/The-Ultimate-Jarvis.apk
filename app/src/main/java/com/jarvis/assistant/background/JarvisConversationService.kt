package com.jarvis.assistant.background

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.audio.VoskModelManager
import org.json.JSONObject
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener as VoskRecognitionListener
import org.vosk.android.SpeechService
import com.jarvis.assistant.audio.AudioPlayer
import com.jarvis.assistant.audio.AudioRecorder
import com.jarvis.assistant.data.model.ConversationState
import com.jarvis.assistant.network.GeminiLiveWebSocket
import com.jarvis.assistant.ui.home.MainActivity
import com.jarvis.assistant.util.PermissionHelper
import com.jarvis.assistant.util.PromptGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.Locale

data class JarvisLiveError(
    val title: String,
    val message: String,
    val errorCode: String,
    val actionText: String = "FIX IN SETTINGS"
)

/**
 * Owns the *entire* Jarvis conversation lifecycle: the microphone, the audio
 * player, the Gemini Live WebSocket, the 120-second inactivity timer, and the
 * wake-word standby listener.
 *
 * This intentionally does NOT live inside the Activity/ViewModel. When the
 * user removes the app from Recents (or the screen turns off), Android
 * destroys the Activity and its ViewModel - if the live session lived there,
 * the conversation would die with it. Because everything here is owned by a
 * foreground service instead, the mic stays on and Jarvis keeps talking
 * normally even with the app closed. The Activity/ViewModel only *observes*
 * this service's state through the companion StateFlows below; they never
 * own the audio pipeline.
 */
class JarvisConversationService : Service() {

    companion object {
        const val ACTION_START_CONVERSATION = "com.jarvis.assistant.START_CONVERSATION"
        const val ACTION_STOP_ALL = "com.jarvis.assistant.STOP_ALL"
        const val ACTION_ENTER_STANDBY = "com.jarvis.assistant.ENTER_STANDBY"

        private const val CHANNEL_ID = "jarvis_conversation"
        private const val NOTIFICATION_ID = 41
        private const val INACTIVITY_TIMEOUT_MS = 30 * 60 * 1000L // 30 minutes before standby
        private const val STANDBY_COOLDOWN_MS = 2500L

        @Volatile private var activeInstance: JarvisConversationService? = null

        /** Speaks a short announcement (such as a new-message alert). Returns false when the service is not running. */
        fun announceMessage(text: String): Boolean {
            val instance = activeInstance ?: return false
            instance.speakAnnouncement(text)
            return true
        }

        private val _isSessionOn = MutableStateFlow(false)
        val isSessionOn: StateFlow<Boolean> = _isSessionOn.asStateFlow()

        private val _isStandby = MutableStateFlow(false)
        val isStandby: StateFlow<Boolean> = _isStandby.asStateFlow()

        private val _liveError = MutableStateFlow<JarvisLiveError?>(null)
        val liveError: StateFlow<JarvisLiveError?> = _liveError.asStateFlow()

        fun clearLiveError() {
            _liveError.value = null
        }

        fun setLiveError(error: JarvisLiveError) {
            _liveError.value = error
        }

        private val _conversationState = MutableStateFlow(ConversationState.IDLE)
        val conversationState: StateFlow<ConversationState> = _conversationState.asStateFlow()

        private val _connectionStatus = MutableStateFlow("READY")
        val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

        private val _audioLevel = MutableStateFlow(0f)
        val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

        private val _audioFrequency = MutableStateFlow(com.jarvis.assistant.audio.AudioFrequencyData())
        val audioFrequency: StateFlow<com.jarvis.assistant.audio.AudioFrequencyData> = _audioFrequency.asStateFlow()

        private val _liveUserTranscript = MutableStateFlow("")
        val liveUserTranscript: StateFlow<String> = _liveUserTranscript.asStateFlow()

        private val _liveAssistantResponse = MutableStateFlow("")
        val liveAssistantResponse: StateFlow<String> = _liveAssistantResponse.asStateFlow()

        private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
        val events: SharedFlow<String> = _events.asSharedFlow()

        /** True once a wake word has just brought the conversation back, for a one-off UI toast. */
        private val _wakeEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        val wakeEvents: SharedFlow<Unit> = _wakeEvents.asSharedFlow()

        fun setMicMuted(muted: Boolean) {
            activeInstance?.audioRecorder?.setMuted(muted)
        }

        fun interruptPlayback() {
            activeInstance?.let { service ->
                if (_conversationState.value == ConversationState.SPEAKING) {
                    Log.i("JarvisService", "Manual interruption: stopping audio playback")
                    service.audioPlayer?.flush()
                    _conversationState.value = ConversationState.LISTENING
                }
            }
        }

        fun restartSession() {
            activeInstance?.let { service ->
                if (_isSessionOn.value) {
                    service.stopActiveConversation()
                    service.beginActiveConversation()
                }
            }
        }

        val isScreenSharing: StateFlow<Boolean> = com.jarvis.assistant.vision.ScreenCaptureManager.getInstance().isSharing

        fun elevateToMediaProjection(): Boolean {
            return activeInstance?.elevateToMediaProjection() == true
        }

        fun demoteFromMediaProjection() {
            activeInstance?.demoteFromMediaProjection()
        }

        fun startScreenCapture(projection: android.media.projection.MediaProjection, metrics: android.util.DisplayMetrics): Boolean {
            val instance = activeInstance ?: run {
                projection.stop()
                return false
            }
            if (liveWebSocketReady(instance).not()) {
                projection.stop()
                return false
            }
            val captureManager = com.jarvis.assistant.vision.ScreenCaptureManager.getInstance()
            captureManager.onStoppedListener = {
                activeInstance?.demoteFromMediaProjection()
            }
            return captureManager.startCapture(projection, metrics) { jpegBytes ->
                val frameInstance = activeInstance ?: return@startCapture
                if (frameInstance.canSendVideoFrame()) {
                    frameInstance.liveWebSocket?.sendMediaChunk(jpegBytes)
                }
            }
        }

        private fun liveWebSocketReady(instance: JarvisConversationService): Boolean =
            _isSessionOn.value && instance.liveWebSocket?.isSessionReady() == true

        fun sendImmediateVideoFrame(jpegBytes: ByteArray) {
            val instance = activeInstance ?: return
            val ws = instance.liveWebSocket ?: return
            // Screen imagery is opt-in. Gemini Live accepts concurrent real-time modalities,
            // including while a response or tool action is in progress.
            if (!com.jarvis.assistant.vision.ScreenCaptureManager.getInstance().isSharing.value ||
                !ws.isSessionReady() || jpegBytes.isEmpty()) return
            ws.sendMediaChunk(jpegBytes)
        }

        fun stopScreenCapture() {
            com.jarvis.assistant.vision.ScreenCaptureManager.getInstance().stopCapture()
            activeInstance?.demoteFromMediaProjection()
        }

        /** True while a foreground instance of this service exists in any mode. */
        fun isServiceRunning(): Boolean = activeInstance != null

        fun sendTextMessage(text: String) {
            val instance = activeInstance ?: return
            instance.liveWebSocket?.sendClientContent(text)
            JarvisApp.instance.chatRepository.addTurn(text, "")
        }

        fun cancelActiveTaskFromOverlay() {
            val service = activeInstance
            if (service != null) service.cancelAllActiveTools()
            else com.jarvis.assistant.automation.agent.AgentTaskEngine.requestCancellation()
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var taskProgressOverlay: com.jarvis.assistant.ui.agent.TaskProgressOverlay? = null
    private var taskProgressOverlayJob: Job? = null
    private var overlayPermissionWarningTaskId: String? = null
    private val preferences by lazy { (applicationContext as JarvisApp).preferences }

    // --- Active-conversation components ---
    private var audioRecorder: AudioRecorder? = null
    @Volatile private var offlineLocalMode = false
    @Volatile private var offlineRecognizer: Recognizer? = null
    private var offlineGenerationJob: Job? = null
    @Volatile private var lastOfflineTranscript = ""
    private var audioPlayer: AudioPlayer? = null
    private var liveWebSocket: GeminiLiveWebSocket? = null
    private var inactivityJob: Job? = null
    private val currentTurnAssistantText = StringBuilder()
    private val currentTurnUserText = StringBuilder()
    private var lastUserSpeechDetectedTime = 0L
    private var lastPlaybackEndTime = 0L
    private var hasSpeechInCurrentUtterance = false
    private var hasSignalledUtteranceEnd = false
    private var consecutiveVoicedFrames = 0
    private var totalVoicedFramesInCurrentUtterance = 0
    private var ambientNoiseFloor = 0.03f
    private var thinkingTimeoutJob: Job? = null
    private var isPendingBackgroundTransition = false
    @Volatile
    private var suppressAudioUntilTurnComplete = false
    @Volatile
    private var isExecutingTool = false
    private val currentUtteranceAudioStream = java.io.ByteArrayOutputStream()
    @Volatile
    private var isCurrentTurnOwnerVoiceVerified = false
    /** True only when THIS turn's audio genuinely matched the enrolled owner voice (a lease alone does not count). */
    @Volatile private var strictOwnerMatchedThisTurn = false
    /** Sensitive-tap gate: when a purchase/install tap was last refused, and which speech turn was already confirmed. */
    @Volatile private var sensitiveConfirmedForSpeechTime = 0L
    @Volatile
    private var lastOwnerVerifiedTimestamp = 0L
    private val preSpeechRingBuffer = java.util.ArrayDeque<ByteArray>(12)
    private val preConnectAudioQueue = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
    @Volatile
    private var lastCompletedTaskSummary: String? = null
    @Volatile private var lastVerifiedCompletionAt = 0L
    @Volatile
    private var lastOpenedWebUrl: String? = null
    @Volatile
    private var lastOpenedWebTime: Long = 0L

    // Autonomous Multi-Step Chaining Engine
    @Volatile
    private var isAutonomousTaskRunning = false
    @Volatile
    private var autonomousTaskGoal = ""
    @Volatile
    private var bulkEndOfListVerified = false
    @Volatile
    private var bulkNoProgressScrollCount = 0
    private var lastBulkScreenSignature: com.jarvis.assistant.automation.agent.ScreenSignature? = null
    @Volatile
    private var autonomousStepCount = 0
    @Volatile
    private var autonomousContinuationAttempts = 0
    @Volatile
    private var autonomousWaitingForUser = false
    @Volatile
    private var autoResumeAwaitingResponse = false
    @Volatile
    private var isAutonomousContinuationTurn = false
    private val autonomousAudioLock = Any()
    private val autonomousAudioBuffer = mutableListOf<ByteArray>()
    private var autonomousAudioBufferBytes = 0
    private val autonomousSpokenFingerprints = mutableListOf<String>()
    private var autonomousSpokenUpdateCount = 0
    @Volatile
    private var lastResponseTimedOut = false
    @Volatile
    private var isGoogleFlowCreationTask = false
    @Volatile
    private var flowPromptVerified = false
    @Volatile
    private var flowGenerationVerified = false
    @Volatile
    private var lastVerifiedFlowPrompt = ""
    private var autonomousLoopJob: Job? = null
    private val MAX_AUTONOMOUS_STEPS = 25
    // Mic voice-activity tuning. Raised so room noise no longer flips LISTENING/THINKING when nobody speaks.
    private val VAD_MIN_THRESHOLD = 0.05f
    private val VAD_NOISE_MULTIPLIER = 2.0f
    private val VAD_START_FRAMES = 5
    private val VAD_MIN_UTTERANCE_FRAMES = 10
    // Loop guard: once tripped, ALL automation tools are refused until the user speaks again.
    @Volatile
    private var autonomousHardStop = false
    @Volatile
    private var consecutiveToolFailures = 0
    private val MAX_CONSECUTIVE_FAILURES = 4
    private var lastToolSignature = ""
    private var sameToolRepeatCount = 0
    private val toolExecutionMutex = Mutex()
    private val activeToolJobs = CopyOnWriteArrayList<Job>()
    private var activeToolJob: Job? = null

    private fun isInstagramBulkTask(): Boolean {
        val goal = autonomousTaskGoal.lowercase(Locale.ROOT)
        return com.jarvis.assistant.automation.agent.TaskGoal.fromUserUtterance(goal).isBulkAction &&
            goal.contains("instagram") &&
            (goal.contains("like") || goal.contains("liked") || goal.contains("reel"))
    }

    private fun automationToolTimeoutMs(): Long {
        val goal = autonomousTaskGoal.ifBlank { currentTurnUserText.toString() }.lowercase(Locale.ROOT)
        val bulk = com.jarvis.assistant.automation.agent.TaskGoal.fromUserUtterance(goal).isBulkAction
        return when {
            bulk && goal.contains("instagram") -> 480_000L
            goal.contains("spotify") && listOf("play", "bajao", "chalao").any(goal::contains) -> 120_000L
            else -> 25_000L
        }
    }

    private fun autonomousTaskStepLimit(): Int = if (isInstagramBulkTask()) 400 else MAX_AUTONOMOUS_STEPS

    private fun expectedPackageForTask(goal: String): String? {
        val text = goal.lowercase(Locale.ROOT)
        return when {
            "instagram" in text -> "instagram"
            "spotify" in text -> "spotify"
            "youtube" in text -> "youtube"
            "amazon" in text -> "amazon"
            "gmail" in text || "email" in text -> "com.google.android.gm"
            "whatsapp" in text -> "whatsapp"
            "where is my train" in text || ("train" in text && ("station" in text || "route" in text || "secunderabad" in text || "kagaznagar" in text)) -> "whereismytrain"
            "google photos" in text || "photos app" in text -> "photos"
            "chrome" in text || "browser" in text || "github" in text || "repository" in text || "google search" in text -> "chrome"
            else -> null
        }
    }

    private fun isExplicitContinuationUtterance(text: String): Boolean {
        val clean = text.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N} ]+"), " ")
            .replace(Regex("\\s+"), " ").trim()
        if (clean in setOf("yes", "yeah", "yep", "haan", "han", "ok", "okay", "theek hai", "continue", "resume", "go on")) {
            return true
        }
        val hasContinuation = listOf("continue", "resume", "go on", "keep going", "aage badho", "aage karo")
            .any { containsPhrase(clean, it) }
        val startsNewTask = listOf("open", "search", "send", "play", "unlike", "delete", "call", "message", "type")
            .any { containsPhrase(clean, it) }
        return hasContinuation && !startsNewTask
    }

    /** Do not block an authorized task merely because the model phrased an update as a question. */
    private fun assistantNeedsUserDecision(response: String): Boolean {
        val text = response.lowercase(Locale.ROOT)
        return listOf(
            "need your permission", "need permission", "please confirm", "please provide",
            "which account", "which one", "what should i", "enter the code",
            "enter otp", "verification code", "approve this"
        ).any(text::contains)
    }

    /** Keeps automatic task narration useful and prevents duplicate spoken blockers/statuses. */
    private fun rememberAutonomousSpokenUpdate(text: String) {
        val fingerprint = autonomousSpeechFingerprint(text)
        if (fingerprint.isBlank()) return
        synchronized(autonomousSpokenFingerprints) {
            if (autonomousSpokenFingerprints.none { areSimilarAutonomousUpdates(it, fingerprint) }) {
                autonomousSpokenFingerprints += fingerprint
                if (autonomousSpokenFingerprints.size > 8) autonomousSpokenFingerprints.removeAt(0)
                autonomousSpokenUpdateCount++
            }
        }
    }

    private fun shouldSpeakAutonomousUpdate(text: String, isFinalResult: Boolean): Boolean {
        val candidate = text.ifBlank { lastCompletedTaskSummary.orEmpty() }.trim()
        if (candidate.isBlank()) return false
        val fingerprint = autonomousSpeechFingerprint(candidate)
        if (fingerprint.isBlank()) return false
        val needsDecision = assistantNeedsUserDecision(candidate)
        synchronized(autonomousSpokenFingerprints) {
            val repeated = autonomousSpokenFingerprints.any { areSimilarAutonomousUpdates(it, fingerprint) }
            if (repeated && !isFinalResult) return false
            if (autonomousSpokenUpdateCount >= 3 && !isFinalResult && !needsDecision) return false
            if (!repeated) {
                autonomousSpokenFingerprints += fingerprint
                if (autonomousSpokenFingerprints.size > 8) autonomousSpokenFingerprints.removeAt(0)
                autonomousSpokenUpdateCount++
            }
        }
        return true
    }

    private fun autonomousSpeechFingerprint(text: String): String {
        val clean = text.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N} ]+"), " ")
            .replace(Regex("\\s+"), " ").trim()
        if (clean.isBlank()) return ""
        if (clean.contains("screen") && (clean.contains("share") || clean.contains("sharing"))) return "screen share status"
        if (clean.contains("accessibility") || clean.contains("permission")) return "permission status"
        if (clean.contains("not visible") || clean.contains("can't see") || clean.contains("cannot see") || clean.contains("nothing is showing")) return "screen visibility status"
        val ignored = setOf("the", "a", "an", "i", "am", "is", "are", "it", "to", "and", "now", "just", "please", "your", "this", "that", "on", "in", "for", "with")
        return clean.split(' ').filter { it.length > 2 && it !in ignored }.distinct().sorted().joinToString(" ")
    }

    private fun areSimilarAutonomousUpdates(first: String, second: String): Boolean {
        if (first == second) return true
        val a = first.split(' ').filter(String::isNotBlank).toSet()
        val b = second.split(' ').filter(String::isNotBlank).toSet()
        if (a.isEmpty() || b.isEmpty()) return false
        val overlap = a.intersect(b).size.toFloat() / a.union(b).size.toFloat()
        return overlap >= 0.62f
    }

    private fun setConversationState(state: ConversationState) {
        if (_conversationState.value != state) {
            _conversationState.value = state
            updateNotification()
        }
    }

    /**
     * Immediately aborts all running and pending tool executions, resetting the automation loop.
     */
    fun cancelAllActiveTools() {
        Log.i("JarvisService", "cancelAllActiveTools: Aborting all active and pending tool calls (${activeToolJobs.size} jobs)")
        com.jarvis.assistant.automation.agent.AgentTaskEngine.requestCancellation()
        isAutonomousContinuationTurn = false
        synchronized(autonomousAudioLock) {
            autonomousAudioBuffer.clear()
            autonomousAudioBufferBytes = 0
        }
        isAutonomousTaskRunning = false
        autonomousStepCount = 0
        isGoogleFlowCreationTask = false
        flowPromptVerified = false
        flowGenerationVerified = false
        lastVerifiedFlowPrompt = ""
        autonomousLoopJob?.cancel()
        autonomousLoopJob = null
        for (job in activeToolJobs) {
            job.cancel()
        }
        activeToolJobs.clear()
        activeToolJob = null
        isExecutingTool = false
        com.jarvis.assistant.automation.agent.SerializedActionExecutor.cancelAllPendingActions()
    }

    // Pending tool call queue: tool calls are queued when owner voice verification hasn't completed yet
    private data class PendingToolCall(val callId: String, val name: String, val args: JSONObject)
    private val pendingToolCalls = java.util.concurrent.ConcurrentLinkedQueue<PendingToolCall>()

    /** Real-time video and audio are concurrent input modalities in Gemini Live. */
    fun canSendVideoFrame(): Boolean {
        val ws = liveWebSocket ?: return false
        return com.jarvis.assistant.vision.ScreenCaptureManager.getInstance().isSharing.value && ws.isSessionReady()
    }

    // --- Standby / wake-word components (Pure Vosk) ---
    private var voskSpeechService: SpeechService? = null
    private var tts: TextToSpeech? = null
    private var batteryAnnouncer: com.jarvis.assistant.util.SpeechAnnouncer? = null
    private var batteryWatcher: com.jarvis.assistant.power.BatteryWatcher? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var standbyActivatedTime = 0L
    @Volatile
    private var isPendingWakeGreeting = false
    private var headsetAudioRouter: com.jarvis.assistant.audio.HeadsetAudioRouter? = null

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        createChannel()
        initTts()
        val announcer = com.jarvis.assistant.util.SpeechAnnouncer(applicationContext)
        batteryAnnouncer = announcer
        batteryWatcher = com.jarvis.assistant.power.BatteryWatcher(applicationContext, announcer).also { it.start() }
        com.jarvis.assistant.studio.AutomationEvents.handler = { type, vars -> handleAutomationEvent(type, vars) }
        try {
            if (androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)) {
                android.service.notification.NotificationListenerService.requestRebind(
                    android.content.ComponentName(this, com.jarvis.assistant.notifications.JarvisNotificationListener::class.java)
                )
            }
        } catch (_: Exception) {
        }
        headsetAudioRouter = com.jarvis.assistant.audio.HeadsetAudioRouter(applicationContext)
        taskProgressOverlay = com.jarvis.assistant.ui.agent.TaskProgressOverlay(this)
        taskProgressOverlayJob = serviceScope.launch(Dispatchers.Main.immediate) {
            combine(
                com.jarvis.assistant.automation.agent.AgentTaskEngine.taskState,
                JarvisApp.isAppForeground,
                _isSessionOn,
                _isStandby
            ) { snapshot, appForeground, sessionOn, standbyOn ->
                snapshot to (JarvisApp.instance.preferences.taskOverlayMode == "always" || (!appForeground && (sessionOn || standbyOn)))
            }.collect { (snapshot, showIdleAvatar) ->
                val shown = taskProgressOverlay?.update(snapshot, showIdleAvatar) == true
                if (snapshot != null && !shown && overlayPermissionWarningTaskId != snapshot.taskId) {
                    overlayPermissionWarningTaskId = snapshot.taskId
                    _events.emit("Task progress is shown inside JARVIS Home. Allow Display over other apps if you want the floating task panel over Instagram, Spotify, and Chrome.")
                }
            }
        }
        serviceScope.launch(Dispatchers.Main.immediate) {
            var previousAvailability: List<Any>? = null
            while (isActive) {
                delay(900L)
                if (_isSessionOn.value &&
                    (!PermissionHelper.hasRecordAudioPermission(this@JarvisConversationService) ||
                        !PermissionHelper.hasNotificationPermission(this@JarvisConversationService))) {
                    Log.w("JarvisService", "A required runtime permission was revoked during the live session; stopping the session")
                    stopActiveConversation()
                    serviceScope.launch { _events.emit("Jarvis paused because a required permission was turned off. Re-enable it in System Permissions to continue.") }
                }
                if (com.jarvis.assistant.automation.agent.AgentTaskEngine.current() != null &&
                    !PermissionHelper.isAccessibilityServiceEnabled(this@JarvisConversationService)) {
                    Log.w("JarvisService", "Accessibility was disabled during an active task; clearing task progress")
                    isAutonomousTaskRunning = false
                    com.jarvis.assistant.automation.agent.AgentTaskEngine.resetForSession()
                }
                val snapshot = com.jarvis.assistant.automation.agent.AgentTaskEngine.taskState.value
                val prefs = (application as JarvisApp).preferences
                val appForeground = JarvisApp.isAppForeground.value
                val showIdleAvatar = prefs.taskOverlayMode == "always" || (!appForeground && (_isSessionOn.value || _isStandby.value))
                val availability = listOf(
                    prefs.isTaskOverlayEnabled,
                    PermissionHelper.hasOverlayPermission(this@JarvisConversationService),
                    prefs.taskOverlayAvatar,
                    prefs.taskOverlayMode,
                    prefs.taskOverlayEdge,
                    appForeground,
                    _isSessionOn.value,
                    _isStandby.value
                )
                if (availability != previousAvailability) {
                    previousAvailability = availability
                    taskProgressOverlay?.update(snapshot, showIdleAvatar)
                }
            }
        }
    }

    private fun initTts() {
        try {
            tts = TextToSpeech(applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.US
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            Log.d("JarvisService", "TTS onStart: $utteranceId")
                        }

                        override fun onDone(utteranceId: String?) {
                            Log.d("JarvisService", "TTS onDone: $utteranceId")
                            handleTtsDone(utteranceId)
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            Log.e("JarvisService", "TTS onError: $utteranceId")
                            handleTtsDone(utteranceId)
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            Log.e("JarvisService", "TTS onError: $utteranceId code: $errorCode")
                            handleTtsDone(utteranceId)
                        }
                    })
                }
            }
        } catch (e: Exception) {
            Log.e("JarvisService", "Failed to init TTS: ${e.message}")
        }
    }

    private fun handleTtsDone(utteranceId: String?) {
        // Robotic system announcements disabled; all speech is handled via natural Gemini Live neural audio.
    }

    private fun ensureMicrophoneForegroundService() {
        val notification = buildNotification()
        var combinedTypes = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
            com.jarvis.assistant.vision.ScreenCaptureManager.getInstance().isSharing.value) {
            combinedTypes = combinedTypes or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            try {
                startForeground(NOTIFICATION_ID, notification, combinedTypes)
                Log.d("JarvisService", "startForeground elevated to types=$combinedTypes")
            } catch (e: Exception) {
                Log.w("JarvisService", "startForeground with combined types failed: ${e.message}")
                try {
                    startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                } catch (_: Exception) {
                    try {
                        startForeground(NOTIFICATION_ID, notification)
                    } catch (_: Exception) {}
                }
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    fun elevateToMediaProjection(): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val notification = buildNotification()
            val types = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            try {
                startForeground(NOTIFICATION_ID, notification, types)
                Log.i("JarvisService", "Foreground service elevated to include MEDIA_PROJECTION")
                return true
            } catch (e: Exception) {
                Log.w("JarvisService", "Failed to elevate to MEDIA_PROJECTION: ${e.message}")
                return false
            }
        }
        return true
    }

    fun demoteFromMediaProjection() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val notification = buildNotification()
            val types = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            try {
                startForeground(NOTIFICATION_ID, notification, types)
                Log.i("JarvisService", "Foreground service demoted from MEDIA_PROJECTION")
            } catch (e: Exception) {
                Log.w("JarvisService", "Failed to demote from MEDIA_PROJECTION: ${e.message}")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!com.jarvis.assistant.security.AppIdentityVerifier.isTrustedRelease(this)) {
            Log.e("JarvisService", "Blocked service start: release signing certificate did not match the configured pin")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        ensureMicrophoneForegroundService()
        acquireWakeLock()

        when (intent?.action) {
            ACTION_START_CONVERSATION -> {
                com.jarvis.assistant.audio.JarvisSoundEffects.playWakeSound()
                beginActiveConversation()
            }
            ACTION_ENTER_STANDBY -> enterStandby(announce = false)
            ACTION_STOP_ALL -> {
                fullStop()
                return START_NOT_STICKY
            }
            else -> {
                // System restarted service or restarted after recents removal
                if (!_isSessionOn.value && !_isStandby.value) {
                    enterStandby(announce = false)
                }
            }
        }
        return START_STICKY
    }

    // ---------------------------------------------------------------------
    // Active conversation
    // ---------------------------------------------------------------------

    private suspend fun generateCurrentSystemPrompt(): String {
        val app = application as? JarvisApp ?: return ""
        return withContext(Dispatchers.IO) {
            val memoryContext = app.memoryManager.getSystemPromptContext()
            val favSong = app.memoryManager.resolveSongQuery("my favorite song")
            val basePrompt = com.jarvis.assistant.util.PromptGenerator.generateSystemPrompt(
                personality = preferences.personality,
                userName = preferences.userName,
                memoryContext = memoryContext,
                favoriteSong = favSong,
                userBirthday = preferences.userBirthday,
                userLocation = preferences.userLocation,
                isTorchOn = app.phoneControlManager.isTorchOn,
                language = preferences.language,
                isSilentExecutionMode = preferences.isSilentExecutionMode
            )
            // Groq adds the voice and self-reference notes itself, because it shortens the prompt.
            val genderSection = if (com.jarvis.assistant.security.AiEngineStore(applicationContext).useGroq) {
                ""
            } else {
                com.jarvis.assistant.voice.VoiceGenderStore(applicationContext).promptSection()
            }
            val policySection = if (com.jarvis.assistant.security.AiEngineStore(applicationContext).useGroq) {
                ""
            } else {
                com.jarvis.assistant.util.ConfirmationPolicy.promptSection()
            }
            basePrompt + policySection + genderSection + com.jarvis.assistant.activitylog.ActivityLog.get(applicationContext).promptSection()
        }
    }

    private fun beginActiveConversation(wakeTriggered: Boolean = false) {
        if (_isSessionOn.value) return
        com.jarvis.assistant.automation.agent.AgentTaskEngine.resetForSession()
        isAutonomousContinuationTurn = false
        synchronized(autonomousAudioLock) {
            autonomousAudioBuffer.clear()
            autonomousAudioBufferBytes = 0
        }
        synchronized(autonomousSpokenFingerprints) {
            autonomousSpokenFingerprints.clear()
            autonomousSpokenUpdateCount = 0
        }
        exitStandbyListening()
        isPendingWakeGreeting = wakeTriggered

        val app = application as JarvisApp
        val preferences = app.preferences
        val engineStore = com.jarvis.assistant.security.AiEngineStore(applicationContext)
        val useGroq = engineStore.useGroq
        val apiKey = if (useGroq) engineStore.groqKey().trim() else preferences.apiKey.trim()
        offlineLocalMode = apiKey.isBlank() ||
            !com.jarvis.assistant.offline.OfflineModelDownloader.isInternetAvailable(applicationContext)
        if (offlineLocalMode) {
            val localModel = com.jarvis.assistant.offline.OfflineModelDownloader.modelFile(applicationContext)
            if (!com.jarvis.assistant.offline.OfflineModelDownloader.isModelValid(localModel)) {
                _liveError.value = JarvisLiveError(
                    title = "OFFLINE BRAIN NOT READY",
                    message = "JARVIS selected Offline Brain because the online provider is unavailable or there is no internet connection, but the local Qwen3 model is not installed. Open Offline JARVIS and download the model once.",
                    errorCode = "OFFLINE_MODEL_REQUIRED",
                    actionText = "OPEN OFFLINE JARVIS"
                )
                serviceScope.launch { _events.emit("Offline Brain needs its model downloaded once. Open Offline JARVIS; no API key is required.") }
                fullStop()
                return
            }
        }

        if (!PermissionHelper.hasAllRequiredPermissions(this)) {
            _liveError.value = JarvisLiveError(
                title = "PERMISSIONS REQUIRED",
                message = "Jarvis requires Microphone and Notification permissions to run. Please grant them in Authorizations.",
                errorCode = "PERMISSIONS_MISSING",
                actionText = "GRANT PERMISSIONS"
            )
            serviceScope.launch { _events.emit("Please allow all required permissions in Settings to use Jarvis.") }
            fullStop()
            return
        }

        _liveError.value = null

        _isSessionOn.value = true
        _isStandby.value = false
        lastResponseTimedOut = false
        val now = System.currentTimeMillis()
        lastUserSpeechDetectedTime = now
        lastOwnerVerifiedTimestamp = now
        isCurrentTurnOwnerVoiceVerified = !ownerVoiceGateOn()
        strictOwnerMatchedThisTurn = false
        pendingToolCalls.clear()
        hasSpeechInCurrentUtterance = false
        hasSignalledUtteranceEnd = false
        currentTurnAssistantText.clear()
        currentTurnUserText.clear()
        noiseLevel = com.jarvis.assistant.audio.NoiseCancelSettings(applicationContext).level
        voiceCleaner = null
        startInactivityTimer()
        setConversationState(ConversationState.IDLE)
        updateNotification()
        headsetAudioRouter?.start()

        audioPlayer = AudioPlayer(
            onPlaybackStateChanged = { isPlaying ->
                if (isPlaying) {
                    setConversationState(ConversationState.SPEAKING)
                } else {
                    lastPlaybackEndTime = System.currentTimeMillis()
                    finalizeTurn()
                    if (_isSessionOn.value) {
                        setConversationState(ConversationState.IDLE)
                    }
                }
            },
            onPlaybackAmplitude = { amp ->
                if (_conversationState.value == ConversationState.SPEAKING) {
                    _audioLevel.value = amp
                    _audioFrequency.value = com.jarvis.assistant.audio.AudioFrequencyData(
                        amplitude = amp,
                        pitchHz = 180f,
                        bass = (amp * 0.7f).coerceIn(0f, 1f),
                        mid = (amp * 0.9f).coerceIn(0f, 1f),
                        high = (amp * 0.4f).coerceIn(0f, 1f)
                    )
                }
            }
        ).apply { start() }

        serviceScope.launch(Dispatchers.IO) {
            if (offlineLocalMode) {
                try {
                    VoskModelManager.init(applicationContext, serviceScope)
                    withTimeout(60_000L) {
                        while (!VoskModelManager.isModelReady.value) delay(150L)
                    }
                    val model = VoskModelManager.model ?: error("Local speech model is unavailable.")
                    offlineRecognizer?.close()
                    offlineRecognizer = Recognizer(model, 16000.0f)
                    withContext(Dispatchers.Main) {
                        if (!_isSessionOn.value) return@withContext
                        _connectionStatus.value = "OFFLINE"
                        _liveError.value = null
                        _events.emit("Offline Brain ready. Speak naturally; responses are generated on this phone.")
                    }
                } catch (error: Exception) {
                    Log.e("JarvisService", "Offline voice initialization failed", error)
                    withContext(Dispatchers.Main) {
                        _liveError.value = JarvisLiveError(
                            title = "OFFLINE BRAIN FAILED",
                            message = error.message ?: "Could not initialize the local speech/model engine.",
                            errorCode = "OFFLINE_INIT_FAILED",
                            actionText = "RETRY"
                        )
                        _events.emit("Offline Brain could not start: " + (error.message ?: "initialization failed"))
                        fullStop()
                    }
                }
                return@launch
            }

            val systemPrompt = generateCurrentSystemPrompt()

            withContext(Dispatchers.Main) {
                if (!_isSessionOn.value) return@withContext
                liveWebSocket = createLiveSession(
                    useGroq = useGroq,
                    apiKey = apiKey,
                    model = preferences.aiModel,
                    voiceName = com.jarvis.assistant.voice.VoiceGenderStore(applicationContext).voiceFor(preferences.voice),
                    systemPrompt = systemPrompt,
                    listener = object : GeminiLiveWebSocket.Listener {
                        override fun onConnectionStateChanged(status: String) {
                            _connectionStatus.value = status
                            updateNotification()
                            if (status == "LIVE") {
                                _liveError.value = null
                                // Flush any speech chunks captured during the connection handshake
                                serviceScope.launch(Dispatchers.Default) {
                                    while (!preConnectAudioQueue.isEmpty()) {
                                        val chunk = preConnectAudioQueue.poll() ?: break
                                        liveWebSocket?.sendAudioChunk(chunk)
                                    }
                                }
                                // Reset autonomous task state on fresh connection
                                isAutonomousTaskRunning = false
                                isAutonomousContinuationTurn = false
                                synchronized(autonomousAudioLock) {
                                    autonomousAudioBuffer.clear()
                                    autonomousAudioBufferBytes = 0
                                }
                                autonomousStepCount = 0
                                autonomousLoopJob?.cancel()
                                autonomousLoopJob = null
                                if (_conversationState.value != ConversationState.SPEAKING && _conversationState.value != ConversationState.THINKING) {
                                    setConversationState(ConversationState.IDLE)
                                }
                                val queuedAnnouncement = pendingAnnouncement
                                if (queuedAnnouncement != null) {
                                    pendingAnnouncement = null
                                    isPendingWakeGreeting = false
                                    liveWebSocket?.sendClientContent(announcementPrompt(queuedAnnouncement))
                                }
                                if (isPendingWakeGreeting) {
                                    isPendingWakeGreeting = false
                                    if (!hasSpeechInCurrentUtterance) {
                                        liveWebSocket?.sendClientContent("Hey Jarvis")
                                    }
                                }
                            }
                        }

                        private var audioChunksReceivedInTurn = 0

                        override fun onAudioDataReceived(pcmData: ByteArray) {
                            lastRealInteractionTime = System.currentTimeMillis()
                            thinkingTimeoutJob?.cancel()
                            thinkingTimeoutJob = null
                            lastResponseTimedOut = false
                            if (suppressAudioUntilTurnComplete) {
                                Log.w("JarvisService", "onAudioDataReceived: Audio suppressed (suppressAudioUntilTurnComplete=true)")
                                return
                            }
                            // Silent execution applies to task narration, while leaving ordinary
                            // conversation audible. Read the preference dynamically so toggling
                            // it during a session takes effect immediately.
                            if (preferences.isSilentExecutionMode &&
                                (isAutonomousTaskRunning || hasExecutedCommandInCurrentTurn || !lastCompletedTaskSummary.isNullOrBlank())
                            ) {
                                audioPlayer?.flush()
                                return
                            }
                            if (isAutonomousContinuationTurn) {
                                synchronized(autonomousAudioLock) {
                                    if (autonomousAudioBufferBytes + pcmData.size <= 2_000_000) {
                                        autonomousAudioBuffer += pcmData.copyOf()
                                        autonomousAudioBufferBytes += pcmData.size
                                    }
                                }
                                return
                            }
                            audioChunksReceivedInTurn++
                            if (audioChunksReceivedInTurn % 25 == 1) {
                                Log.d("JarvisService", "Streaming audio chunk #$audioChunksReceivedInTurn (${pcmData.size} bytes) to AudioPlayer")
                            }
                            setConversationState(ConversationState.SPEAKING)
                            audioPlayer?.enqueueAudio(pcmData)
                        }

                        override fun onAssistantTextReceived(textChunk: String) {
                            if (textChunk.isEmpty()) return
                            thinkingTimeoutJob?.cancel()
                            thinkingTimeoutJob = null
                            lastResponseTimedOut = false
                            synchronized(currentTurnAssistantText) {
                                val chunkToAppend = if (currentTurnAssistantText.isNotEmpty() && currentTurnAssistantText.last() == ' ') {
                                    textChunk.trimStart()
                                } else {
                                    textChunk
                                }
                                if (chunkToAppend.isNotEmpty()) {
                                    if (currentTurnAssistantText.isNotEmpty()) {
                                        val lastChar = currentTurnAssistantText.last()
                                        val firstChar = chunkToAppend.first()
                                        if (!Character.isWhitespace(lastChar) && !Character.isWhitespace(firstChar)) {
                                            if (Character.isLetterOrDigit(firstChar) || firstChar == '(' || firstChar == '[') {
                                                currentTurnAssistantText.append(' ')
                                            }
                                        }
                                    }
                                    currentTurnAssistantText.append(chunkToAppend)
                                }
                            }
                            _liveAssistantResponse.value = currentTurnAssistantText.toString().trim()
                            val assistantText = currentTurnAssistantText.toString().lowercase(Locale.ROOT)
                            Log.d("JarvisService", "Assistant text so far: '$assistantText'")
                        }

                        override fun onUserTextReceived(textChunk: String) {
                            lastUserSpeechDetectedTime = System.currentTimeMillis()
                            lastRealInteractionTime = lastUserSpeechDetectedTime
                            announcementOnlySession = false
                            val trimmed = textChunk.trim()
                            if (trimmed.isNotEmpty()) {
                                val startsNewUserTurn = currentTurnUserText.isEmpty()
                                val answeringTaskBlocker = startsNewUserTurn && isAutonomousTaskRunning && autonomousWaitingForUser
                                if (answeringTaskBlocker) {
                                    autonomousWaitingForUser = false
                                    com.jarvis.assistant.automation.agent.AgentTaskEngine.resumeAfterUserInput()
                                }
                                if (startsNewUserTurn && isAutonomousTaskRunning && !isExplicitContinuationUtterance(trimmed) && !answeringTaskBlocker) {
                                    Log.i("JarvisService", "New user request superseded the previous automation task; cancelling stale actions.")
                                    cancelAllActiveTools()
                                    autonomousTaskGoal = ""
                                    autonomousContinuationAttempts = 0
                                    bulkEndOfListVerified = false
                                    bulkNoProgressScrollCount = 0
                                    lastBulkScreenSignature = null
                                    autoResumeAwaitingResponse = false
                                }
                                if (startsNewUserTurn && autonomousHardStop) {
                                    val resumeExistingTask = isExplicitContinuationUtterance(trimmed) && autonomousTaskGoal.isNotBlank() &&
                                        com.jarvis.assistant.automation.agent.AgentTaskEngine.current() != null
                                    Log.i("JarvisService", if (resumeExistingTask) "User requested continuation; preserving the original task and resuming from fresh UI." else "New user request received: clearing automation hard stop.")
                                    autonomousHardStop = false
                                    autonomousContinuationAttempts = 0
                                    consecutiveToolFailures = 0
                                    sameToolRepeatCount = 0
                                    lastToolSignature = ""
                                    if (resumeExistingTask) {
                                        isAutonomousTaskRunning = true
                                        autonomousStepCount = 0
                                        com.jarvis.assistant.automation.agent.AgentTaskEngine.resumeAfterUserInput()
                                    } else {
                                        isAutonomousTaskRunning = false
                                        autonomousStepCount = 0
                                        autonomousTaskGoal = ""
                                    }
                                }
                                if (currentTurnUserText.isNotEmpty() && !currentTurnUserText.endsWith(" ")) {
                                    currentTurnUserText.append(" ")
                                }
                                currentTurnUserText.append(trimmed)
                                _liveUserTranscript.value = currentTurnUserText.toString().trim()
                                if (_conversationState.value == ConversationState.IDLE) {
                                    setConversationState(ConversationState.LISTENING)
                                }
                            }
                            lastResponseTimedOut = false
                            updateNotification()
                            val userText = currentTurnUserText.toString().lowercase(Locale.ROOT)
                            if (isStopCommand(userText)) {
                                Log.i("JarvisService", "Detected explicit stop/cancel in user speech: '$userText'. Stopping speech and active tasks immediately.")
                                audioPlayer?.flush()
                                try { tts?.stop() } catch (_: Exception) {}
                                cancelAllActiveTools()
                                pendingToolCalls.clear()
                                suppressAudioUntilTurnComplete = true
                                currentTurnAssistantText.clear()
                                _liveAssistantResponse.value = ""
                                setConversationState(ConversationState.LISTENING)
                            }
                            if (isExplicitStandbyCommand(userText)) {
                                Log.i("JarvisService", "Detected explicit background / standby command in user speech: '$userText'")
                                minimizeToHomeScreen()
                                enterStandby(announce = true)
                                return
                            }
                            app.memoryManager.learnPreferencesFromConversation(currentTurnUserText.toString())
                            // Do not run checkAndExecuteCommand on streaming chunks mid-speech!
                            // Gemini Live onToolCall is the authoritative neural intent engine.
                            if (!usingGroqEngine()) scheduleAutomationMatch()
                        }

                        override fun onInterrupted() {
                            val hasHeadset = headsetAudioRouter?.isHeadsetConnected() == true
                            Log.w("JarvisService", "onInterrupted received from server (hasHeadset=$hasHeadset, state=${_conversationState.value})")
                            val userTurnInProgress = hasSpeechInCurrentUtterance || currentTurnUserText.isNotBlank()
                            val isOwnAutoResumeInterrupt = autoResumeAwaitingResponse &&
                                !userTurnInProgress
                            if (isOwnAutoResumeInterrupt) {
                                Log.i("JarvisService", "Preserving task state for the assistant's automatic continuation")
                                autoResumeAwaitingResponse = false
                            } else {
                                isAutonomousContinuationTurn = false
                                synchronized(autonomousAudioLock) {
                                    autonomousAudioBuffer.clear()
                                    autonomousAudioBufferBytes = 0
                                }
                                cancelAllActiveTools()
                                if (userTurnInProgress && !isExplicitContinuationUtterance(currentTurnUserText.toString())) {
                                    autonomousTaskGoal = ""
                                    autonomousContinuationAttempts = 0
                                    autoResumeAwaitingResponse = false
                                }
                            }
                            thinkingTimeoutJob?.cancel()
                            thinkingTimeoutJob = null
                            suppressAudioUntilTurnComplete = false
                            if (hasHeadset || _conversationState.value != ConversationState.SPEAKING) {
                                audioPlayer?.flush()
                                currentTurnAssistantText.clear()
                                _liveAssistantResponse.value = ""
                            }
                            if (!userTurnInProgress) {
                                currentTurnUserText.clear()
                                _liveUserTranscript.value = ""
                            }
                            hasSpeechInCurrentUtterance = false
                            hasSignalledUtteranceEnd = false
                            consecutiveVoicedFrames = 0
                            totalVoicedFramesInCurrentUtterance = 0
                            synchronized(currentUtteranceAudioStream) { currentUtteranceAudioStream.reset() }
                            isCurrentTurnOwnerVoiceVerified = !ownerVoiceGateOn()
                            strictOwnerMatchedThisTurn = false
                            if (_conversationState.value != ConversationState.SPEAKING) {
                                setConversationState(if (userTurnInProgress) ConversationState.LISTENING else ConversationState.IDLE)
                            }
                            updateNotification()
                        }

                        override fun onTurnCompleted() {
                            autoResumeAwaitingResponse = false
                            thinkingTimeoutJob?.cancel()
                            thinkingTimeoutJob = null
                            val assistantTurnText = synchronized(currentTurnAssistantText) { currentTurnAssistantText.toString().trim() }
                            if (isAutonomousContinuationTurn) {
                                isAutonomousContinuationTurn = false
                                val bufferedAudio = synchronized(autonomousAudioLock) {
                                    autonomousAudioBuffer.toList().also {
                                        autonomousAudioBuffer.clear()
                                        autonomousAudioBufferBytes = 0
                                    }
                                }
                                val finalResult = !isAutonomousTaskRunning || !lastCompletedTaskSummary.isNullOrBlank()
                                val speakUpdate = shouldSpeakAutonomousUpdate(assistantTurnText, finalResult)
                                if (speakUpdate && bufferedAudio.isNotEmpty()) {
                                    bufferedAudio.forEach { audioPlayer?.enqueueAudio(it) }
                                    Log.i("JarvisService", "Playing a new autonomous milestone; buffered repeated narration is discarded")
                                } else {
                                    Log.i("JarvisService", "Suppressing repeated or routine speech from automatic task continuation")
                                }
                            } else if (isAutonomousTaskRunning && assistantTurnText.isNotBlank()) {
                                rememberAutonomousSpokenUpdate(assistantTurnText)
                            }
                            audioPlayer?.notifyTurnComplete()
                            audioChunksReceivedInTurn = 0
                            suppressAudioUntilTurnComplete = false
                            automationHandledTurn = false
                            hasSpeechInCurrentUtterance = false
                            hasSignalledUtteranceEnd = false
                            consecutiveVoicedFrames = 0
                            totalVoicedFramesInCurrentUtterance = 0
                            if (_conversationState.value == ConversationState.THINKING && (audioPlayer == null || !audioPlayer!!.isPlaying())) {
                                setConversationState(ConversationState.IDLE)
                            }
                            lastResponseTimedOut = false
                            updateNotification()

                            // Owner voice gating: if owner-only mode is active and speaker was not verified, ignore turn completely
                            val hasActualSpeechInThisTurn = hasSpeechInCurrentUtterance || currentTurnUserText.isNotEmpty()
                            val isLeaseActive = (System.currentTimeMillis() - lastOwnerVerifiedTimestamp) < 120_000L

                            if (ownerVoiceGateOn() && !isCurrentTurnOwnerVoiceVerified && !isLeaseActive && hasActualSpeechInThisTurn) {
                                val nonOwnerUtterance = currentTurnUserText.toString().trim()
                                Log.w("JarvisService", "Turn completed by NON-OWNER: Suppressing response and logging ignored.")
                                val app = application as? JarvisApp
                                val loggedQuery = if (nonOwnerUtterance.isNotEmpty()) nonOwnerUtterance else "(Unrecognized voice)"
                                app?.chatRepository?.addTurn(
                                    userText = "[Non-Owner Voice]: $loggedQuery",
                                    jarvisText = "[IGNORED] Voice not recognized as owner"
                                )
                                audioPlayer?.flush()
                                currentTurnAssistantText.clear()
                                currentTurnUserText.clear()
                                _liveAssistantResponse.value = ""
                                _liveUserTranscript.value = ""
                                processPendingToolCalls(verified = false)
                                return
                            }

                            val userUtterance = currentTurnUserText.toString().trim()
                            if (userUtterance.isNotEmpty()) {
                                app.memoryManager.learnPreferencesFromConversation(userUtterance)
                            }
                            val assistantText = currentTurnAssistantText.toString().lowercase(Locale.ROOT)
                            val isConfused = assistantText.contains("not understand") ||
                                    assistantText.contains("didn't understand") ||
                                    assistantText.contains("don't understand") ||
                                    assistantText.contains("couldn't understand") ||
                                    assistantText.contains("sorry, i can't") ||
                                    assistantText.contains("i'm not sure")

                            // Only run fallback if Gemini Live did NOT call a tool AND did not express confusion
                            if (!hasExecutedCommandInCurrentTurn && !isConfused) {
                                checkAndExecuteCommand(userUtterance)
                                checkAssistantTextForCommands(currentTurnAssistantText.toString())
                            }

                            // Reset verification flag for the next conversational turn
                            if (ownerVoiceGateOn()) {
                                // Keep verification active if within the owner session lease window.
                                // This prevents the owner from being re-challenged every single turn.
                                val leaseStillActive = (System.currentTimeMillis() - lastOwnerVerifiedTimestamp) < 120_000L
                                if (!leaseStillActive) {
                                    isCurrentTurnOwnerVoiceVerified = false
                                }
                            }
                            pendingToolCalls.clear()

                            if (!lastCompletedTaskSummary.isNullOrBlank()) {
                                val summaryToSpeak = lastCompletedTaskSummary
                                lastCompletedTaskSummary = null
                                val isLive = liveWebSocket?.isSessionReady() == true
                                if (!preferences.isSilentExecutionMode && !isLive) {
                                    Log.i("JarvisService", "Offline fallback: Speaking confirmation via TTS: '$summaryToSpeak'")
                                    tts?.speak(summaryToSpeak, TextToSpeech.QUEUE_FLUSH, null, "task_done")
                                }
                            }

                            val engineStatus = com.jarvis.assistant.automation.agent.AgentTaskEngine.current()?.status
                            if (isAutonomousTaskRunning && engineStatus == com.jarvis.assistant.automation.agent.AgentTaskEngine.State.COMPLETED) {
                                Log.i("JarvisService", "Task engine already verified completion; skipping another automatic continuation")
                                isAutonomousTaskRunning = false
                                autonomousStepCount = 0
                                autonomousLoopJob?.cancel()
                                autonomousLoopJob = null
                            }

                            // Do NOT reset autonomous task state on turn completion!
                            // Autonomous tasks span across multiple turns (e.g. download -> open -> onboarding -> phone entry -> OTP -> setup complete).
                            // The autonomous task ONLY completes when:
                            // 1. Gemini explicitly calls `task_completed(summary=...)`
                            // 2. The user explicitly gives a cancel/stop command ("stop", "ruko", "cancel")
                            // 3. Safety cap of MAX_AUTONOMOUS_STEPS (25) is reached.
                            if (isAutonomousTaskRunning && autonomousStepCount >= autonomousTaskStepLimit()) {
                                Log.w("JarvisService", "Autonomous task reached its step safety cap (${autonomousTaskStepLimit()}). Terminating loop safety.")
                                autonomousHardStop = true
                                isAutonomousTaskRunning = false
                                com.jarvis.assistant.automation.agent.AgentTaskEngine.recordCompletionEvidence(
                                    com.jarvis.assistant.automation.agent.AgentTaskEngine.CompletionEvidence.SAFETY_LIMIT,
                                    "Reached the current action batch limit; the original task is preserved and can resume from a fresh screen."
                                )
                                com.jarvis.assistant.automation.agent.AgentTaskEngine.failCurrentStep(
                                    "Reached the current action batch limit. Say continue to resume this same task from a fresh screen.",
                                    blocked = true
                                )
                                com.jarvis.assistant.automation.agent.AgentTaskEngine.setState(
                                    com.jarvis.assistant.automation.agent.AgentTaskEngine.State.BLOCKED
                                )
                                autonomousStepCount = 0
                                autonomousLoopJob?.cancel()
                                autonomousLoopJob = null
                            } else if (isAutonomousTaskRunning) {
                                val response = currentTurnAssistantText.toString().trim().ifBlank { "The Instagram bulk task is still in progress." }
                                val needsUserDecision = false
                                val continuationLimit = 25
                                if (!autonomousWaitingForUser && !needsUserDecision && autonomousContinuationAttempts < continuationLimit) {
                                    autonomousContinuationAttempts++
                                    val attempt = autonomousContinuationAttempts
                                    autonomousLoopJob?.cancel()
                                    val speechAtTurnEnd = lastUserSpeechDetectedTime
                                    autonomousLoopJob = serviceScope.launch {
                                        // The model can finish its turn before the device finishes playing the queued audio.
                                        // Sending a continuation during playback makes Gemini interrupt its own speech.
                                        var quietSince = 0L
                                        val waitStartedAt = System.currentTimeMillis()
                                        while (isActive && System.currentTimeMillis() - waitStartedAt < 90_000L) {
                                            if (!isAutonomousTaskRunning || lastUserSpeechDetectedTime > speechAtTurnEnd ||
                                                liveWebSocket?.isSessionReady() != true) {
                                                Log.i("JarvisService", "Auto-resume cancelled because the task/session changed or the user spoke")
                                                return@launch
                                            }
                                            val playbackActive = audioPlayer?.isPlaying() == true || tts?.isSpeaking == true ||
                                                _conversationState.value == ConversationState.SPEAKING
                                            if (playbackActive) {
                                                quietSince = 0L
                                            } else if (quietSince == 0L) {
                                                quietSince = System.currentTimeMillis()
                                            } else if (System.currentTimeMillis() - quietSince >= 350L) {
                                                break
                                            }
                                            kotlinx.coroutines.delay(100L)
                                        }
                                        if (!isActive || System.currentTimeMillis() - waitStartedAt >= 90_000L) {
                                            Log.w("JarvisService", "Auto-resume timed out waiting for speech playback to finish")
                                            return@launch
                                        }
                                        if (com.jarvis.assistant.automation.agent.AgentTaskEngine.current()?.status in setOf(
                                                com.jarvis.assistant.automation.agent.AgentTaskEngine.State.COMPLETED,
                                                com.jarvis.assistant.automation.agent.AgentTaskEngine.State.FAILED,
                                                com.jarvis.assistant.automation.agent.AgentTaskEngine.State.CANCELLED,
                                                com.jarvis.assistant.automation.agent.AgentTaskEngine.State.BLOCKED
                                            )) {
                                            isAutonomousTaskRunning = false
                                            return@launch
                                        }
                                        if (isAutonomousTaskRunning && !isExecutingTool && activeToolJobs.isEmpty() &&
                                            pendingToolCalls.isEmpty() && lastUserSpeechDetectedTime <= speechAtTurnEnd &&
                                            liveWebSocket?.isSessionReady() == true) {
                                            var uiWait: com.jarvis.assistant.vision.UiWaitResult? = null
                                            val uiWaitDeadline = System.currentTimeMillis() + 45_000L
                                            while (isActive && isAutonomousTaskRunning && System.currentTimeMillis() < uiWaitDeadline &&
                                                lastUserSpeechDetectedTime <= speechAtTurnEnd && uiWait?.ready != true) {
                                                if (!com.jarvis.assistant.automation.agent.AgentTaskEngine.awaitIfRunnable()) return@launch
                                                val expectedPackage = expectedPackageForTask(autonomousTaskGoal)
                                                uiWait = com.jarvis.assistant.vision.VisualTaskOrchestrator.getInstance()
                                                    .waitForUiState(expectedPackage = expectedPackage, timeoutMs = 8_000L)
                                                if (!uiWait.ready) {
                                                    com.jarvis.assistant.automation.agent.AgentTaskEngine.updateActivity(
                                                        "Waiting for a stable screen before continuing: ${uiWait.reason}",
                                                        com.jarvis.assistant.automation.agent.AgentTaskEngine.State.WAITING_FOR_UI
                                                    )
                                                }
                                            }
                                            if (!isActive || !isAutonomousTaskRunning || lastUserSpeechDetectedTime > speechAtTurnEnd) return@launch
                                            val observedScreen = uiWait?.let { wait ->
                                                com.jarvis.assistant.vision.UiGroundingEngine().formatElementsSummary(wait.elements, wait.root)
                                            }.orEmpty()
                                            if (uiWait?.ready != true) {
                                                com.jarvis.assistant.automation.agent.AgentTaskEngine.failCurrentStep(
                                                    "The UI did not become stable within 45 seconds; task needs a fresh strategy."
                                                )
                                            }
                                            Log.i("JarvisService", "Auto-resuming task after speech playback ended (attempt $attempt)")
                                            autoResumeAwaitingResponse = true
                                            isAutonomousContinuationTurn = true
                                            synchronized(autonomousAudioLock) {
                                                autonomousAudioBuffer.clear()
                                                autonomousAudioBufferBytes = 0
                                            }
                                            liveWebSocket?.sendClientContent(
                                                "Continue the existing task from the fresh phone state; this is an automatic continuation, not a new user command. " +
                                                    "Do not quote, paraphrase, repeat, or summarize previous assistant messages; use task state and this fresh screen only. " +
                                                    "UI readiness: ${uiWait?.reason ?: "not observed"}. " +
                                                    "Latest accessibility observation: ${observedScreen.take(5000)}. " +
                                                    "Use the current observation to select the next safe plan step or recover/replan if the screen changed. " +
                                                    "Do not repeat verified steps, do not ask the user to say continue, and never claim completion without local verification. " +
                                                "Keep the phone quiet while continuing: do not narrate each click, inspection, scroll, or repeated blocker. The task start was already acknowledged. Speak only for a new material milestone, a genuine user decision that is required, or one final verified result. Never repeat the same status wording. " +
                                                    "Never ask for permission or confirmation: keep going with the most likely choice and finish the task."
                                            )
                                        }
                                    }
                                } else if (autonomousWaitingForUser || needsUserDecision) {
                                    com.jarvis.assistant.automation.agent.AgentTaskEngine.failCurrentStep(
                                        "The assistant requested user clarification or confirmation.", blocked = true
                                    )
                                    com.jarvis.assistant.automation.agent.AgentTaskEngine.setState(
                                        com.jarvis.assistant.automation.agent.AgentTaskEngine.State.BLOCKED
                                    )
                                } else {
                                    Log.w("JarvisService", "Auto-resume limit reached; stopping task to avoid an unattended loop")
                                    isAutonomousTaskRunning = false
                                    com.jarvis.assistant.automation.agent.AgentTaskEngine.failCurrentStep(
                                        "Automatic continuation limit reached without verified completion.", blocked = true
                                    )
                                    serviceScope.launch { _events.emit("The task is blocked because it did not reach a verified result within the automatic recovery limit.") }
                                }
                            }
                        }

                        override fun onError(message: String, isFatal: Boolean, errorCode: String?) {
                            Log.e("JarvisService", "GeminiLive onError: fatal=$isFatal, code=$errorCode, msg=$message")
                            updateNotification()
                            serviceScope.launch { _events.emit(message) }
                            when (errorCode) {
                                "API_KEY_INVALID" -> {
                                    _liveError.value = JarvisLiveError(
                                        title = "API KEY INVALID",
                                        message = "Google rejected your Gemini API key (400/403). Please verify and enter a valid API key in Settings.",
                                        errorCode = "API_KEY_INVALID",
                                        actionText = "OPEN SETTINGS"
                                    )
                                }
                                "API_KEY_MISSING" -> {
                                    _liveError.value = JarvisLiveError(
                                        title = "API KEY MISSING",
                                        message = "No Gemini API key found. Enter your BYOK key in Settings to activate Jarvis.",
                                        errorCode = "API_KEY_MISSING",
                                        actionText = "OPEN SETTINGS"
                                    )
                                }
                                "QUOTA_EXCEEDED" -> {
                                    _liveError.value = JarvisLiveError(
                                        title = "API QUOTA EXHAUSTED",
                                        message = "Gemini API quota or rate limit exceeded. Please check your Google AI Studio plan.",
                                        errorCode = "QUOTA_EXCEEDED",
                                        actionText = "CHECK PLAN"
                                    )
                                }
                                else -> {
                                    if (isFatal) {
                                        _liveError.value = JarvisLiveError(
                                            title = "CONNECTION FAILED",
                                            message = message,
                                            errorCode = errorCode ?: "FATAL_ERROR",
                                            actionText = "RETRY"
                                        )
                                    } else if (_liveError.value == null) {
                                        _liveError.value = JarvisLiveError(
                                            title = "CONNECTION DROP",
                                            message = message,
                                            errorCode = "CONNECTION_ERROR",
                                            actionText = "RECONNECT"
                                        )
                                    }
                                }
                            }
                        }

                        override fun onToolCall(callId: String, name: String, args: JSONObject) {
                            if (automationHandledTurn) {
                                liveWebSocket?.sendToolResponse(
                                    callId, name,
                                    JSONObject().put("success", true).put(
                                        "message",
                                        "This request was already handled by a local Jarvis automation. Do not repeat it and do not speak."
                                    )
                                )
                                return
                            }
                            val isLeaseActive = (System.currentTimeMillis() - lastOwnerVerifiedTimestamp) < 120_000L
                            val hasNoIncomingSpeech = !hasSpeechInCurrentUtterance && currentTurnUserText.isEmpty()
                            val isOwnerVerifiedOrAutonomous = isCurrentTurnOwnerVoiceVerified || isLeaseActive || hasNoIncomingSpeech || isAutonomousTaskRunning

                            if (ownerVoiceGateOn() && !isOwnerVerifiedOrAutonomous) {
                                Log.w("JarvisService", "QUEUED tool call '$name' (callId=$callId): Owner verification pending, will execute after silence detection confirms speaker.")
                                pendingToolCalls.add(PendingToolCall(callId, name, args))
                                return
                            }
                            handleToolCallExecution(callId, name, args)
                        }
                    }
                ).apply {
                    // System prompt is already baked into the constructor; no need to regenerate on reconnect.
                    // promptProvider removed to eliminate double-generation latency.
                    connect(serviceScope)
                }
            }
        }

        audioRecorder = AudioRecorder { chunk, amplitude, frequencyData ->
            if (_isSessionOn.value) {
                val now = System.currentTimeMillis()

                // Acoustic Echo Decay Protection (AEDP):
                // For 150ms after speaker audio stops, discard mic chunks so room reverberation
                // does not bounce into Gemini Live and cause false self-interruption.
                if (now - lastPlaybackEndTime < 150L) {
                    consecutiveVoicedFrames = 0
                    return@AudioRecorder
                }

                // Assistant is speaking / AudioTrack or TTS is actively playing:
                val isSpeakerActive = (audioPlayer?.isPlaying() == true) || (tts?.isSpeaking == true) ||
                        (_conversationState.value == ConversationState.SPEAKING)

                if (isSpeakerActive) {
                    val hasHeadset = headsetAudioRouter?.isHeadsetConnected() == true
                    // Voice barge-in works on both headset and phone speaker.
                    // Loudspeaker uses a higher amplitude threshold to reduce false triggers from audio leakage.
                    val interruptThreshold = if (hasHeadset) 0.22f else maxOf(0.36f, ambientNoiseFloor * 3.2f)
                    if (amplitude > interruptThreshold) {
                        consecutiveVoicedFrames++
                        val requiredFrames = if (hasHeadset) 3 else 6
                        if (consecutiveVoicedFrames >= requiredFrames) {
                            Log.i("JarvisService", "Voice barge-in: user spoke over assistant (headset=$hasHeadset, amp=$amplitude)")
                            audioPlayer?.flush()
                            try { tts?.stop() } catch (_: Exception) {}
                            consecutiveVoicedFrames = 0
                            setConversationState(ConversationState.LISTENING)
                            lastUserSpeechDetectedTime = now
                            hasSpeechInCurrentUtterance = true
                            hasSignalledUtteranceEnd = false
                            currentTurnUserText.clear()
                            currentTurnAssistantText.clear()
                            _liveUserTranscript.value = ""
                            _liveAssistantResponse.value = ""
                            hasExecutedCommandInCurrentTurn = false
                            totalVoicedFramesInCurrentUtterance = requiredFrames
                            synchronized(currentUtteranceAudioStream) {
                                currentUtteranceAudioStream.reset()
                                currentUtteranceAudioStream.write(chunk)
                            }
                            isCurrentTurnOwnerVoiceVerified = !ownerVoiceGateOn()
                            strictOwnerMatchedThisTurn = false
                            return@AudioRecorder
                        }
                    } else {
                        consecutiveVoicedFrames = 0
                    }
                    // Suppress mic audio from streaming to Gemini ONLY while assistant is physically speaking over loudspeaker.
                    return@AudioRecorder
                }

                // Route audio to the selected brain. Offline mode feeds local Vosk instead of
                // opening a cloud socket; online mode retains the existing streaming path.
                val cleanedChunk = cleanForModel(chunk)
                if (offlineLocalMode) {
                    if (cleanedChunk.isNotEmpty()) handleOfflineAudioChunk(cleanedChunk)
                } else if (liveWebSocket?.isSessionReady() == true) {
                    while (!preConnectAudioQueue.isEmpty()) {
                        val queued = preConnectAudioQueue.poll() ?: break
                        liveWebSocket?.sendAudioChunk(queued)
                    }
                    if (cleanedChunk.isNotEmpty()) liveWebSocket?.sendAudioChunk(cleanedChunk)
                } else {
                    // Connection still warming up: buffer up to 60 chunks (~1.2s of audio) so early speech is never lost
                    if (preConnectAudioQueue.size < 60 && cleanedChunk.isNotEmpty()) {
                        preConnectAudioQueue.offer(cleanedChunk.copyOf())
                    }
                }

                // Track ambient noise floor adaptively when quiet
                if (amplitude < 0.10f) {
                    ambientNoiseFloor = (ambientNoiseFloor * 0.97f + amplitude * 0.03f).coerceIn(0.015f, 0.10f)
                }

                // Maintain pre-speech buffer of recent chunks (for smooth onset capture)
                synchronized(preSpeechRingBuffer) {
                    if (preSpeechRingBuffer.size >= 10) {
                        preSpeechRingBuffer.pollFirst()
                    }
                    preSpeechRingBuffer.addLast(chunk.copyOf())
                }

                // Dynamic voice threshold: responsive to normal conversational volume (0.035f)
                val voiceThreshold = maxOf(VAD_MIN_THRESHOLD, ambientNoiseFloor * VAD_NOISE_MULTIPLIER)

                if (amplitude > voiceThreshold) {
                    consecutiveVoicedFrames++
                    lastUserSpeechDetectedTime = now
                    _audioLevel.value = amplitude
                    _audioFrequency.value = frequencyData

                    // Declare speech start only after several consecutive voiced frames (not a short noise blip)
                    if (consecutiveVoicedFrames >= VAD_START_FRAMES) {
                        // Barge-in: stop Jarvis's spoken output when a real user utterance begins,
                        // without cancelling the task executor or live session.
                        if (_conversationState.value == ConversationState.SPEAKING) {
                            Log.i("JarvisService", "User speech detected during playback; stopping speech output")
                            audioPlayer?.flush()
                            try { tts?.stop() } catch (_: Exception) {}
                            setConversationState(ConversationState.LISTENING)
                        }
                        if (!hasSpeechInCurrentUtterance) {
                            currentTurnUserText.clear()
                            currentTurnAssistantText.clear()
                            _liveUserTranscript.value = ""
                            _liveAssistantResponse.value = ""
                            hasExecutedCommandInCurrentTurn = false
                            totalVoicedFramesInCurrentUtterance = 0
                            synchronized(currentUtteranceAudioStream) {
                                currentUtteranceAudioStream.reset()
                                synchronized(preSpeechRingBuffer) {
                                    for (preChunk in preSpeechRingBuffer) {
                                        currentUtteranceAudioStream.write(preChunk)
                                    }
                                }
                            }
                            isCurrentTurnOwnerVoiceVerified = !ownerVoiceGateOn()
                            strictOwnerMatchedThisTurn = false
                        }
                        hasSpeechInCurrentUtterance = true
                        hasSignalledUtteranceEnd = false
                        totalVoicedFramesInCurrentUtterance++
                        // EARLY VOICE VERIFICATION: wait for ~800ms of voiced audio for a more stable speaker embedding
                        // This ensures verification completes BEFORE Gemini starts sending response audio
                        if (!isCurrentTurnOwnerVoiceVerified && ownerVoiceGateOn() && totalVoicedFramesInCurrentUtterance == 40) {
                            val audioSnapshot = synchronized(currentUtteranceAudioStream) { currentUtteranceAudioStream.toByteArray() }
                            if (audioSnapshot.size >= 16000 * 2 * 0.25) {
                                serviceScope.launch(Dispatchers.Default) {
                                    val match = com.jarvis.assistant.audio.OwnerVoiceManager.verifySpeakerAudio(audioSnapshot, preferences)
                                    if (match.isMatch) {
                                        lastOwnerVerifiedTimestamp = System.currentTimeMillis()
                                        strictOwnerMatchedThisTurn = true
                                        isCurrentTurnOwnerVoiceVerified = true
                                        Log.i("JarvisService", "EARLY voice verification PASSED: Owner confirmed during speech (dist=${match.distance}, sim=${match.similarityPercent}%)")
                                        processPendingToolCalls(verified = true)
                                    } else if (match.hasExtractedVector) {
                                        Log.w("JarvisService", "EARLY voice verification FAILED: Non-owner detected (dist=${match.distance}, thr=${match.threshold})")
                                        // Don't reject yet - let silence handler do final check with more audio
                                    }
                                }
                            }
                        }
                        synchronized(currentUtteranceAudioStream) {
                            if (currentUtteranceAudioStream.size() < 480000) {
                                currentUtteranceAudioStream.write(chunk)
                            }
                        }
                        if (_conversationState.value != ConversationState.LISTENING) {
                            setConversationState(ConversationState.LISTENING)
                        }
                    }
                } else {
                    // Gradual decrement instead of instant reset: one quiet 20ms frame won't kill voiced speech tracking
                    if (consecutiveVoicedFrames > 0) consecutiveVoicedFrames--
                    val silentDuration = now - lastUserSpeechDetectedTime

                    if (hasSpeechInCurrentUtterance && silentDuration < 450) {
                        // Keep buffering inter-word pauses so Vosk receives natural continuous audio
                        synchronized(currentUtteranceAudioStream) {
                            if (currentUtteranceAudioStream.size() < 480000) {
                                currentUtteranceAudioStream.write(chunk)
                            }
                        }
                    }

                    if (_conversationState.value == ConversationState.LISTENING) {
                        _audioLevel.value = amplitude
                        _audioFrequency.value = frequencyData

                        // Conversational pause reached (~450ms — safe for natural Hinglish speech with inter-word pauses)
                        if (silentDuration > 450) {
                            if (ownerVoiceGateOn()) {
                                val isLeaseActive = (now - lastOwnerVerifiedTimestamp) < 120_000L
                                if (isLeaseActive) {
                                    // Ultra-low latency: Active owner lease immediately confirms turn with 0ms delay
                                    lastOwnerVerifiedTimestamp = now
                                    if (!strictOwnerMatchedThisTurn) {
                                        val leaseAudio = synchronized(currentUtteranceAudioStream) { currentUtteranceAudioStream.toByteArray() }
                                        if (leaseAudio.size >= 16000 * 2 * 0.3) {
                                            serviceScope.launch(Dispatchers.Default) {
                                                val leaseMatch = com.jarvis.assistant.audio.OwnerVoiceManager.verifySpeakerAudio(leaseAudio, preferences)
                                                if (leaseMatch.isMatch) {
                                                    strictOwnerMatchedThisTurn = true
                                                }
                                            }
                                        }
                                    }
                                    isCurrentTurnOwnerVoiceVerified = true
                                    processPendingToolCalls(verified = true)
                                } else if (!isCurrentTurnOwnerVoiceVerified) {
                                    val audioBytes = synchronized(currentUtteranceAudioStream) {
                                        currentUtteranceAudioStream.toByteArray()
                                    }
                                    if (audioBytes.size >= 16000 * 2 * 0.3) {
                                        val match = com.jarvis.assistant.audio.OwnerVoiceManager.verifySpeakerAudio(audioBytes, preferences)
                                        if (match.isMatch) {
                                            lastOwnerVerifiedTimestamp = now
                                            strictOwnerMatchedThisTurn = true
                                            isCurrentTurnOwnerVoiceVerified = true
                                            Log.i("JarvisService", "Speech verification passed: Owner confirmed (dist: ${match.distance}, sim: ${match.similarityPercent}%)")
                                            processPendingToolCalls(verified = true)
                                        } else {
                                            isCurrentTurnOwnerVoiceVerified = false
                                            audioPlayer?.flush()
                                            currentTurnAssistantText.clear()
                                            _liveAssistantResponse.value = ""
                                            Log.w("JarvisService", "Speech verification rejected: Non-owner (dist: ${match.distance}, thr: ${match.threshold})")
                                            processPendingToolCalls(verified = false)
                                        }
                                    } else {
                                        isCurrentTurnOwnerVoiceVerified = !ownerVoiceGateOn()
                                        strictOwnerMatchedThisTurn = false
                                        if (!isCurrentTurnOwnerVoiceVerified) {
                                            processPendingToolCalls(verified = false)
                                        }
                                    }
                                }
                            }

                            if (totalVoicedFramesInCurrentUtterance >= VAD_MIN_UTTERANCE_FRAMES || currentTurnUserText.trim().isNotEmpty()) {
                                if (ownerVoiceGateOn() && !isCurrentTurnOwnerVoiceVerified) {
                                    val nonOwnerQuery = currentTurnUserText.toString().trim()
                                    Log.w("JarvisService", "Non-owner speech ended: '$nonOwnerQuery'; IGNORING utterance, suppressing reply, and recording to history.")
                                    val app = application as? JarvisApp
                                    val loggedQuery = if (nonOwnerQuery.isNotEmpty()) nonOwnerQuery else "(Unrecognized voice)"
                                    app?.chatRepository?.addTurn(
                                        userText = "[Non-Owner Voice]: $loggedQuery",
                                        jarvisText = "[IGNORED] Voice not recognized as owner"
                                    )
                                    audioPlayer?.flush()
                                    currentTurnAssistantText.clear()
                                    currentTurnUserText.clear()
                                    _liveUserTranscript.value = ""
                                    _liveAssistantResponse.value = ""
                                    hasSpeechInCurrentUtterance = false
                                    hasSignalledUtteranceEnd = false
                                    consecutiveVoicedFrames = 0
                                    totalVoicedFramesInCurrentUtterance = 0
                                    synchronized(currentUtteranceAudioStream) { currentUtteranceAudioStream.reset() }
                                    setConversationState(ConversationState.IDLE)
                                    processPendingToolCalls(verified = false)
                                } else {
                                    if (!hasSignalledUtteranceEnd) {
                                        hasSignalledUtteranceEnd = true
                                        // If screen capture is active, send one fresh snapshot right with the question
                                        if (com.jarvis.assistant.vision.ScreenCaptureManager.getInstance().isSharing.value) {
                                            val snapshot = com.jarvis.assistant.vision.ScreenCaptureManager.getInstance().captureSnapshotJpeg(60)
                                            if (snapshot != null && snapshot.isNotEmpty()) {
                                                liveWebSocket?.sendMediaChunk(snapshot)
                                            }
                                        }
                                    }
                                    setConversationState(ConversationState.THINKING)
                                    thinkingTimeoutJob?.cancel()
                                    thinkingTimeoutJob = serviceScope.launch {
                                        delay(15000)
                                        if (_conversationState.value == ConversationState.THINKING && (audioPlayer == null || !audioPlayer!!.isPlaying())) {
                                            Log.i("JarvisService", "Thinking timeout safety; returning to IDLE")
                                            setConversationState(ConversationState.IDLE)
                                            lastResponseTimedOut = true
                                            updateNotification()
                                            serviceScope.launch { _events.emit("Jarvis did not receive a reply. Check the connection and repeat your request.") }
                                        }
                                    }
                                }
                            } else {
                                // Short acoustic pop / ambient noise - return cleanly to IDLE
                                hasSpeechInCurrentUtterance = false
                                hasSignalledUtteranceEnd = false
                                setConversationState(ConversationState.IDLE)
                            }
                        }
                    } else if (_conversationState.value == ConversationState.IDLE) {
                        _audioLevel.value = 0f
                    }
                }
            }
        }.apply {
            setMuted(preferences.isMicMuted)
            start(serviceScope)
        }
    }

    /**
     * Execute deterministic, on-device commands before asking the local LLM.
     * This keeps common device actions fast and prevents the offline model from
     * having to invent tool calls. Message sending remains confirmation-based.
     */
    private suspend fun executeOfflineLocalCommand(text: String): String? {
        val clean = text.trim()
        val lower = clean.lowercase(Locale.ROOT)
        val accessibility = com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService.instance

        if (lower.matches(Regex("^(?:hey\\s+)?jarvis[, ]*\\s*(?:lock (?:the )?(?:screen|phone|device)|lock my phone)$"))) {
            return withContext(Dispatchers.Main) {
                when {
                    accessibility == null -> "I can't lock the screen until JARVIS Accessibility is enabled in Android Settings."
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.P -> "Screen locking requires Android 9 or newer."
                    accessibility.lockScreenNow() -> "Done. The screen is locked."
                    else -> "Android did not lock the screen. Please check the JARVIS Accessibility permission."
                }
            }
        }

        if (lower.matches(Regex("^(?:hey\\s+)?jarvis[, ]*\\s*(?:unlock (?:the )?(?:screen|phone|device)|unlock my phone)$"))) {
            val store = com.jarvis.assistant.security.UnlockCredentialStore(applicationContext)
            if (!store.hasCredential()) return "For security, save your screen-lock credential in JARVIS Settings > Screen Unlock first."
            val voiceAllowed = if (ownerVoiceGateOn()) isCurrentTurnOwnerVoiceVerified else store.allowWithoutVoiceMatch
            if (!voiceAllowed) return "For security, I couldn't verify the owner's voice. The phone will stay locked. Enroll your voice or review Screen Unlock settings."
            if (accessibility == null) return "I can't unlock the phone until JARVIS Accessibility is enabled in Android Settings."
            val result = withContext(Dispatchers.Main) { accessibility.unlockDevice() }
            return result.message
        }

        val typeMatch = Regex("^(?:hey\\s+)?jarvis[, ]*\\s*(?:type|enter|write)\\s+(.+)$", RegexOption.IGNORE_CASE).find(clean)
        if (typeMatch != null) {
            val textToType = typeMatch.groupValues[1].trim()
            if (textToType.isBlank()) return "Tell me what text to type."
            return withContext(Dispatchers.Main) {
                if (accessibility == null) "Enable JARVIS Accessibility and focus a text field first."
                else if (accessibility.typeText(textToType)) "Typed the requested text."
                else "I couldn't type because no supported text field is focused. Open the target app and tap its text field first."
            }
        }

        val settingsAction = when {
            lower.matches(Regex("^(?:open|show) (?:the )?(?:device )?settings$")) -> android.provider.Settings.ACTION_SETTINGS
            lower.matches(Regex("^(?:open|show) (?:the )?wi.?fi settings$")) -> android.provider.Settings.ACTION_WIFI_SETTINGS
            lower.matches(Regex("^(?:open|show) (?:the )?bluetooth settings$")) -> android.provider.Settings.ACTION_BLUETOOTH_SETTINGS
            lower.matches(Regex("^(?:open|show) (?:the )?display settings$")) -> android.provider.Settings.ACTION_DISPLAY_SETTINGS
            lower.matches(Regex("^(?:open|show) (?:the )?sound settings$")) -> android.provider.Settings.ACTION_SOUND_SETTINGS
            lower.matches(Regex("^(?:open|show) (?:the )?accessibility settings$")) -> android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS
            else -> null
        }
        if (settingsAction != null) {
            return withContext(Dispatchers.Main) {
                try {
                    startActivity(Intent(settingsAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    "Opening the requested Android settings."
                } catch (error: Exception) {
                    "I couldn't open those settings on this Android version."
                }
            }
        }

        val app = application as? JarvisApp ?: return null
        val awaitingConfirmation = app.whatsAppAssistant.hasPendingDraft() || pendingGenericCommand != null
        val parsed = com.jarvis.assistant.automation.command.CommandParser.parse(
            input = clean,
            isAwaitingConfirmation = awaitingConfirmation,
            isAwaitingDisambiguation = app.whatsAppAssistant.isAwaitingDisambiguation()
        )
        val command = parsed.command ?: return null
        if (parsed.confidence < 0.90f) return null

        return when (command) {
            is com.jarvis.assistant.automation.command.JarvisCommand.WhatsAppMessage -> {
                // Prepare a draft first; do not send it until the user confirms.
                app.whatsAppAssistant.createDraft(command.contact, command.message)
            }
            is com.jarvis.assistant.automation.command.JarvisCommand.WhatsAppCall,
            is com.jarvis.assistant.automation.command.JarvisCommand.WhatsAppDeleteMessage,
            is com.jarvis.assistant.automation.command.JarvisCommand.PhoneCall,
            is com.jarvis.assistant.automation.command.JarvisCommand.SocialAppAction,
            is com.jarvis.assistant.automation.command.JarvisCommand.SearchSocialApp,
            is com.jarvis.assistant.automation.command.JarvisCommand.SearchYouTube,
            is com.jarvis.assistant.automation.command.JarvisCommand.PlayYouTube,
            is com.jarvis.assistant.automation.command.JarvisCommand.OpenWebUrls,
            is com.jarvis.assistant.automation.command.JarvisCommand.SearchWeb -> null
            else -> {
                hasExecutedCommandInCurrentTurn = false
                try {
                    app.commandExecutor.execute(command)
                } catch (error: Exception) {
                    Log.e("JarvisService", "Offline local command failed: $command", error)
                    "I couldn't complete that device action: " + (error.message ?: "action failed")
                }
            }
        }
    }

    private fun handleOfflineAudioChunk(chunk: ByteArray) {
        if (!offlineLocalMode || chunk.isEmpty()) return
        try {
            val recognizer = offlineRecognizer ?: return
            if (recognizer.acceptWaveForm(chunk, chunk.size)) {
                val text = JSONObject(recognizer.result).optString("text").trim()
                if (text.isNotBlank()) processOfflineTranscript(text)
            } else {
                val partial = JSONObject(recognizer.partialResult).optString("partial").trim()
                if (partial.isNotBlank()) _liveUserTranscript.value = partial
            }
        } catch (error: Exception) {
            Log.w("JarvisService", "Offline speech recognition chunk failed: " + error.message)
        }
    }

    private fun processOfflineTranscript(text: String) {
        val clean = text.trim()
        if (clean.isBlank() || !offlineLocalMode || offlineGenerationJob?.isActive == true) return
        if (clean.equals(lastOfflineTranscript, ignoreCase = true)) return
        lastOfflineTranscript = clean
        lastRealInteractionTime = System.currentTimeMillis()
        lastUserSpeechDetectedTime = lastRealInteractionTime
        currentTurnUserText.clear()
        currentTurnUserText.append(clean)
        currentTurnAssistantText.clear()
        _liveUserTranscript.value = clean
        _liveAssistantResponse.value = "Thinking locally…"
        setConversationState(ConversationState.THINKING)
        offlineGenerationJob = serviceScope.launch(Dispatchers.IO) {
            val answer = try {
                executeOfflineLocalCommand(clean)
                    ?: com.jarvis.assistant.offline.OfflineLlm.generate(applicationContext, clean)
            } catch (error: Exception) {
                Log.e("JarvisService", "Offline command/model execution failed", error)
                "I couldn't complete that offline request: " + (error.message ?: "local model error")
            }
            withContext(Dispatchers.Main) {
                if (!_isSessionOn.value || !offlineLocalMode) return@withContext
                currentTurnAssistantText.clear()
                currentTurnAssistantText.append(answer)
                _liveAssistantResponse.value = answer
                val app = application as? JarvisApp
                app?.chatRepository?.addTurn(clean, answer)
                app?.memoryManager?.addShortTermTurn(clean, answer)
                setConversationState(ConversationState.SPEAKING)
                try {
                    tts?.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "offline_jarvis_reply")
                } catch (error: Exception) {
                    Log.w("JarvisService", "Offline TTS failed: " + error.message)
                }
                serviceScope.launch {
                    var waited = 0
                    while (_isSessionOn.value && offlineLocalMode && tts?.isSpeaking == true && waited < 90_000) {
                        delay(200)
                        waited += 200
                    }
                    if (_isSessionOn.value && offlineLocalMode) setConversationState(ConversationState.IDLE)
                }
            }
        }
    }

    private var lastHandledCommandText = ""
    private var lastHandledAssistantCommand = ""
    @Volatile
    private var hasExecutedCommandInCurrentTurn = false
    @Volatile
    private var lastCommandExecutionTime = 0L

    // Holds a command that Jarvis heard but isn't confident enough about to run blindly
    // (e.g. a YouTube title that sounded too short/garbled). Cleared once the user
    // confirms or cancels it in a follow-up turn.
    @Volatile
    private var pendingGenericCommand: com.jarvis.assistant.automation.command.JarvisCommand? = null

    /**
     * Executes a tool call from Gemini. Called both from the direct onToolCall path
     * (when owner is already verified) and from processPendingToolCalls (after verification completes).
     */
    private fun handleToolCallExecution(callId: String, name: String, args: JSONObject) {
        lastRealInteractionTime = System.currentTimeMillis()
        if (isDuplicateAction(callId, name, args)) {
            deliverToolResult(
                callId, name,
                JSONObject().put("success", true).put("message", "That was already done a moment ago, so it was not repeated.")
            )
            return
        }
        val job = serviceScope.launch(Dispatchers.IO) {
            val thisJob = coroutineContext[Job]
            if (thisJob != null) activeToolJobs.add(thisJob)
            try {
                toolExecutionMutex.withLock {
                    if (!isActive) return@withLock
                    isExecutingTool = true
                    try {
                        withTimeout(automationToolTimeoutMs()) {
                            val app = application as? JarvisApp ?: return@withTimeout

                hasExecutedCommandInCurrentTurn = true
                lastCommandExecutionTime = System.currentTimeMillis()
                suppressAudioUntilTurnComplete = false

                val isAutomationTool = name in setOf(
                    "open_app", "open_website", "search_in_chrome",
                    "click_ui_element", "type_text", "scroll_screen", "press_key"
                )
                if (isAutomationTool && autonomousHardStop) {
                    Log.w("JarvisService", "Automation tool '$name' refused: hard stop is active.")
                    deliverToolResult(callId, name, JSONObject().apply {
                        put("success", false)
                        put("autonomous_status", "STOPPED")
                        put("message", "STOPPED: automation was halted (repeated failures or too many steps). Do NOT call any more tools. Tell the user honestly, in one or two short sentences, what you actually completed and where you got stuck, then wait for their next instruction.")
                    })
                    return@withTimeout
                }
                if (isAutomationTool) {
                    if (preferences.isSilentExecutionMode) audioPlayer?.flush()
                    autonomousContinuationAttempts = 0
                        autonomousWaitingForUser = false
                    if (!isAutonomousTaskRunning) {
                        synchronized(autonomousSpokenFingerprints) {
                            autonomousSpokenFingerprints.clear()
                            autonomousSpokenUpdateCount = 0
                        }
                        isAutonomousTaskRunning = true
                        autonomousStepCount = 1
                        isGoogleFlowCreationTask = false
                        flowPromptVerified = false
                        flowGenerationVerified = false
                        lastVerifiedFlowPrompt = ""
                        val userText = currentTurnUserText.toString().trim()
                        autonomousTaskGoal = if (userText.isNotEmpty()) userText else "Complete $name automation task"
                        com.jarvis.assistant.automation.agent.AgentTaskEngine.begin(autonomousTaskGoal, "gemini-live-tools")
                        bulkEndOfListVerified = false
                        bulkNoProgressScrollCount = 0
                        lastBulkScreenSignature = null
                        val activeRoot = com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService.instance?.getActiveRootNode()
                        val activeElements = com.jarvis.assistant.vision.UiGroundingEngine().scanScreen(activeRoot)
                        isGoogleFlowCreationTask = com.jarvis.assistant.vision.UiGroundingEngine().isGoogleFlowSurface(activeElements) &&
                            isFlowCreationIntent(autonomousTaskGoal)
                        Log.i("JarvisService", "Started autonomous task loop with goal: '$autonomousTaskGoal'")
                    } else {
                        autonomousStepCount++
                        Log.i("JarvisService", "Autonomous task advancing to step $autonomousStepCount")
                    }
                }

                if (name in setOf("click_ui_element", "type_text", "scroll_screen", "press_key")) {
                    val expectedPackage = expectedPackageForTask(autonomousTaskGoal)
                    val activePackage = com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService.instance
                        ?.getActiveRootNode()?.packageName?.toString()?.lowercase(Locale.ROOT).orEmpty()
                    if (expectedPackage != null && !activePackage.contains(expectedPackage)) {
                        Log.w("JarvisService", "Blocked stale cross-app action '$name': task expects '$expectedPackage', foreground is '$activePackage'.")
                        val screenSummary = com.jarvis.assistant.vision.VisualTaskOrchestrator.getInstance().inspectScreen(waitForContent = true)
                        val mismatchResult = JSONObject().apply {
                            put("success", false)
                            put("verified", false)
                            put("message", "Action blocked because the foreground app does not match the user's current task. Expected an app matching '$expectedPackage', but the current app is '$activePackage'. Reopen the intended app and inspect it before continuing.")
                            put("current_screen_elements", screenSummary)
                        }
                        deliverToolResult(callId, name, mismatchResult)
                        return@withTimeout
                    }
                }

                Log.i("JarvisService", "Handling native toolCall: $name with args: $args")
                val result = JSONObject()

                when (name) {
                    "open_app" -> {
                        val appName = args.optString("app_name", "")
                        val appNumber = if (args.has("app_number")) args.optInt("app_number", 1) else null

                        // 1. FAST PATH: Web platform tools (Google Flow, Runway, etc.) -> open directly in Chrome without scanning apps!
                        val directWebUrl = app.phoneControlManager.parseTargetUrl(appName)
                        if (directWebUrl != null) {
                            if (directWebUrl.contains("flow.google", ignoreCase = true) && isFlowCreationIntent(autonomousTaskGoal)) {
                                isGoogleFlowCreationTask = true
                            }
                            Log.i("JarvisService", "open_app: '$appName' is web platform, target URL: $directWebUrl")
                            val a11y = com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService.instance
                            val currentPkg = a11y?.getActiveRootNode()?.packageName?.toString() ?: ""
                            val isAlreadyInBrowser = currentPkg.contains("chrome", ignoreCase = true) || currentPkg.contains("browser", ignoreCase = true)
                            val now = System.currentTimeMillis()

                            // ANTI-RELOAD GUARD: If Chrome is ALREADY in foreground and website was opened within 20s, do not reload!
                            if (isAlreadyInBrowser && lastOpenedWebUrl == directWebUrl && (now - lastOpenedWebTime < 20_000L)) {
                                Log.i("JarvisService", "open_app: already on $directWebUrl in Chrome. Suppressing redundant re-open.")
                                val orchestrator = com.jarvis.assistant.vision.VisualTaskOrchestrator.getInstance()
                                val (_, summary) = waitForWebDestination(orchestrator, directWebUrl)
                                result.put("success", true)
                                result.put("verified", true)
                                result.put("message", "Already on $directWebUrl in Chrome. The page is currently open on screen. DO NOT call open_app or open_website again! Locate UI elements on screen (e.g. '+ New project' or prompt box) and proceed.")
                                if (summary.isNotBlank()) result.put("current_screen_elements", summary)
                            } else {
                                lastOpenedWebUrl = directWebUrl
                                lastOpenedWebTime = now
                                val webLaunched = executeSerializedTaskMutation("open-url:$directWebUrl") {
                                    app.phoneControlManager.openUrlsInChromeTabs(listOf(directWebUrl))
                                }
                                result.put("success", webLaunched)
                                result.put("message", if (webLaunched) "Opened $directWebUrl in Chrome." else "Failed to open $directWebUrl.")
                                val orchestrator = com.jarvis.assistant.vision.VisualTaskOrchestrator.getInstance()
                                val (destinationReady, summary) = waitForWebDestination(orchestrator, directWebUrl)
                                if (summary.isNotBlank()) {
                                    result.put("current_screen_elements", summary)
                                } else {
                                    result.put("current_screen_elements", "Navigated to $directWebUrl. Use the latest available screen context; inspect_screen returns accessibility text unless Screen Share has a confirmed frame.")
                                }
                                if (!destinationReady) {
                                    result.put("message", "Navigation was requested, but Chrome has not yet confirmed the destination page. Wait and inspect the current screen before tapping or typing.")
                                }
                                result.put("verified", destinationReady)
                            }
                        } else {
                            // 2. Native app launch via AppLauncher
                            val launched = executeSerializedTaskMutation("open-app:$appName") {
                                com.jarvis.assistant.util.AppLauncher.launchApp(this@JarvisConversationService, appName, appNumber)
                            }
                            if (launched) {
                                Log.i("JarvisService", "open_app: launched native app '$appName'")
                                result.put("success", true)
                                result.put("message", "Opened $appName app.")
                                val orchestrator = com.jarvis.assistant.vision.VisualTaskOrchestrator.getInstance()
                                val startupWait = orchestrator.waitForUiState(
                                    expectedPackage = expectedPackageForTask(autonomousTaskGoal),
                                    timeoutMs = 12_000L
                                )
                                val summary = com.jarvis.assistant.vision.UiGroundingEngine().formatElementsSummary(startupWait.elements, startupWait.root)
                                if (summary.isNotBlank()) {
                                    result.put("current_screen_elements", summary)
                                } else {
                                    result.put("current_screen_elements", "Opened $appName. Use the latest available screen context; inspect_screen returns accessibility text unless Screen Share has a confirmed frame.")
                                }
                                val foregroundPackage = com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService.instance
                                    ?.getActiveRootNode()?.packageName?.toString().orEmpty()
                                val expectedPackage = expectedPackageForTask(autonomousTaskGoal)
                                val appVerified = startupWait.ready &&
                                    (expectedPackage?.let { foregroundPackage.contains(it, ignoreCase = true) } ?: foregroundPackage.isNotBlank())
                                result.put("verified", appVerified)
                                val spotifyPlaybackRequest = expectedPackageForTask(autonomousTaskGoal) == "spotify" &&
                                    listOf("play", "bajao", "chalao", "gaana chala", "gana chala")
                                        .any { autonomousTaskGoal.contains(it, ignoreCase = true) }
                                if (launched && spotifyPlaybackRequest && appName.contains("spotify", ignoreCase = true)) {
                                    val playback = orchestrator.runSpotifySmartSearch(autonomousTaskGoal)
                                    result.put("success", playback.success)
                                    result.put("verified", playback.verified ?: false)
                                    result.put("message", playback.message)
                                    if (playback.currentStateSummary.isNotBlank()) {
                                        result.put("current_screen_elements", playback.currentStateSummary)
                                    }
                                }
                            } else {
                                result.put("success", false)
                                result.put("message", "Could not find or open $appName.")
                            }
                        }
                    }

                    "open_website" -> {
                        val targetUrls = mutableListOf<String>()
                        val jsonUrls = args.optJSONArray("urls")
                        if (jsonUrls != null) {
                            for (i in 0 until jsonUrls.length()) {
                                val u = jsonUrls.getString(i).trim()
                                if (u.isNotEmpty()) targetUrls.add(u)
                            }
                        }
                        val query = args.optString("query", "").trim()
                        if (query.isNotEmpty()) {
                            val extracted = app.phoneControlManager.extractUrlsFromText(query)
                            for (u in extracted) {
                                if (!targetUrls.contains(u)) targetUrls.add(u)
                            }
                            if (targetUrls.isEmpty()) {
                                val single = app.phoneControlManager.parseTargetUrl(query)
                                if (single != null) targetUrls.add(single)
                            }
                        }
                        if (targetUrls.isEmpty() && query.isNotBlank()) {
                            targetUrls.add("https://www.google.com/search?q=${Uri.encode(query)}")
                        }
                        val primaryUrl = targetUrls.firstOrNull() ?: ""
                        if (targetUrls.any { it.contains("flow.google", ignoreCase = true) } && isFlowCreationIntent(autonomousTaskGoal)) {
                            isGoogleFlowCreationTask = true
                        }
                        val a11y = com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService.instance
                        val currentPkg = a11y?.getActiveRootNode()?.packageName?.toString() ?: ""
                        val isAlreadyInBrowser = currentPkg.contains("chrome", ignoreCase = true) || currentPkg.contains("browser", ignoreCase = true)
                        val now = System.currentTimeMillis()

                        // ANTI-RELOAD LOOP GUARD: If Chrome is ALREADY in foreground and website was launched within 20 seconds,
                        // do NOT re-fire Intent to reload Chrome! Give Chrome time to render and inspect the screen!
                        if (isAlreadyInBrowser && primaryUrl.isNotBlank() && lastOpenedWebUrl == primaryUrl && (now - lastOpenedWebTime < 20_000L)) {
                            Log.i("JarvisService", "open_website: already on $primaryUrl in Chrome. Suppressing redundant reload.")
                            val orchestrator = com.jarvis.assistant.vision.VisualTaskOrchestrator.getInstance()
                            val (_, summary) = waitForWebDestination(orchestrator, primaryUrl)
                            result.put("success", true)
                            result.put("message", "Already on $primaryUrl in Chrome. The web page is currently loading/open on screen. DO NOT call open_website again! Look at current_screen_elements or video feed to type prompt or click buttons.")
                            if (summary.isNotBlank()) result.put("current_screen_elements", summary)
                        } else {
                            if (primaryUrl.isNotBlank()) {
                                lastOpenedWebUrl = primaryUrl
                                lastOpenedWebTime = now
                            }
                            val ok = executeSerializedTaskMutation("open-urls") {
                                app.phoneControlManager.openUrlsInChromeTabs(targetUrls)
                            }
                            result.put("success", ok)
                            if (ok) {
                                result.put("message", if (targetUrls.size > 1) "Opened ${targetUrls.size} websites in separate Chrome tabs: ${targetUrls.joinToString(", ")}." else "Opened ${targetUrls.firstOrNull() ?: "website"} in Chrome.")
                                val orchestrator = com.jarvis.assistant.vision.VisualTaskOrchestrator.getInstance()
                                val (destinationReady, summary) = waitForWebDestination(orchestrator, primaryUrl)
                                if (summary.isNotBlank()) {
                                    result.put("current_screen_elements", summary)
                                }
                                if (!destinationReady) {
                                    result.put("message", "Navigation was requested, but Chrome has not confirmed the destination page yet. Wait and inspect before acting.")
                                }
                            } else {
                                result.put("message", "Could not open the requested websites.")
                            }
                        }
                    }

                    "search_in_chrome" -> {
                        val query = args.optString("query", "").trim()
                        val extracted = app.phoneControlManager.extractUrlsFromText(query)
                        if (extracted.isNotEmpty()) {
                            val ok = executeSerializedTaskMutation("open-search-results") {
                                app.phoneControlManager.openUrlsInChromeTabs(extracted)
                            }
                            result.put("success", ok)
                            result.put("message", if (extracted.size > 1) "Opened ${extracted.size} websites in separate Chrome tabs: ${extracted.joinToString(", ")}." else "Opened ${extracted.first()} in Chrome.")
                            delay(200)
                            val orchestrator = com.jarvis.assistant.vision.VisualTaskOrchestrator.getInstance()
                            val summary = orchestrator.inspectScreen(waitForContent = true)
                            if (summary.isNotBlank()) {
                                result.put("current_screen_elements", summary)
                            }
                        } else {
                            val targetUrl = app.phoneControlManager.parseTargetUrl(query)
                            val searchUrl = targetUrl ?: "https://www.google.com/search?q=${Uri.encode(query)}"
                            val ok = executeSerializedTaskMutation("open-search-url") {
                                app.phoneControlManager.openUrlsInChromeTabs(listOf(searchUrl))
                            }
                            result.put("success", ok)
                            result.put("message", if (targetUrl != null) "Opened $targetUrl in Chrome." else "Searched \"$query\" in Chrome.")
                            delay(200)
                            val orchestrator = com.jarvis.assistant.vision.VisualTaskOrchestrator.getInstance()
                            val summary = orchestrator.inspectScreen(waitForContent = true)
                            if (summary.isNotBlank()) {
                                result.put("current_screen_elements", summary)
                            }
                            if (targetUrl == null) {
                                result.put("action_required", "CRITICAL NEXT STEP: You are on the Google Search results page for \"$query\". Look at the search result links in current_screen_elements and IMMEDIATELY call click_ui_element with the link name or element_id to open the website! Do NOT claim you refined or created the video until you enter the website and submit the generation!")
                            }
                        }
                    }

                    "social_app_action" -> {
                        val requestedApp = args.optString("app", "instagram").trim().lowercase(Locale.ROOT)
                        val action = args.optString("action", "open").trim().lowercase(Locale.ROOT)
                        val query = args.optString("query", "").trim()
                        val instagramPackage = "com.instagram.android"
                        try {
                            if (requestedApp == "instagram" && action != "open") {
                                val targetUrl = when (action) {
                                    "search" -> if (query.isNotBlank()) "https://www.instagram.com/explore/search/keyword/?q=" + Uri.encode(query) else ""
                                    "reels" -> "https://www.instagram.com/reels/"
                                    "profile" -> "https://www.instagram.com/"
                                    else -> ""
                                }
                                if (targetUrl.isBlank()) {
                                    result.put("success", false)
                                    result.put("message", "Please say what to search for on Instagram.")
                                } else {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                                        setPackage(instagramPackage)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                    }
                                    startActivity(intent)
                                    result.put("success", true)
                                    result.put("message", when (action) {
                                        "search" -> "Opened Instagram search for $query."
                                        "reels" -> "Opening Instagram Reels."
                                        else -> "Opening Instagram."
                                    })
                                }
                            } else {
                                val appName = if (requestedApp == "instazen") "Instazen" else "Instagram"
                                val opened = com.jarvis.assistant.util.AppLauncher.openAppResult(applicationContext, appName)
                                when (opened) {
                                    is com.jarvis.assistant.util.AppLauncher.OpenAppResult.Success -> {
                                        result.put("success", true)
                                        result.put("message", "Opened $appName.")
                                    }
                                    is com.jarvis.assistant.util.AppLauncher.OpenAppResult.MultipleFound -> {
                                        result.put("success", false)
                                        result.put("message", "More than one $appName app/profile was found. Please specify the app instance.")
                                    }
                                    is com.jarvis.assistant.util.AppLauncher.OpenAppResult.NotFound -> {
                                        result.put("success", false)
                                        result.put("message", "$appName is not installed or could not be found.")
                                    }
                                    is com.jarvis.assistant.util.AppLauncher.OpenAppResult.Failure -> {
                                        result.put("success", false)
                                        result.put("message", "Could not open $appName.")
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            result.put("success", false)
                            result.put("message", "Could not perform $action in $requestedApp: " + (e.message ?: "unknown error"))
                        }
                    }

                    "search_and_play_youtube" -> {
                        val query = args.optString("query", "").trim()
                        audioPlayer?.flush()
                        val apiKey = app.youTubePreferences.youtubeApiKey
                        val effectiveQuery = if (query.isBlank() || query.equals("video", ignoreCase = true) || query.equals("song", ignoreCase = true) || query.equals("a song", ignoreCase = true) || query.equals("songs", ignoreCase = true)) {
                            "my favorite song"
                        } else {
                            query
                        }
                        val resolvedSong = app.memoryManager.resolveSongQuery(effectiveQuery)
                        app.memoryManager.trackSongPlay(resolvedSong)
                        val playResult = com.jarvis.assistant.automation.youtube.YouTubeController.searchAndPlay(this@JarvisConversationService, resolvedSong, apiKey)
                        result.put("success", playResult.success)
                        result.put("message", playResult.message ?: if (playResult.success) "Playing $resolvedSong on YouTube." else "Could not play on YouTube.")
                    }

                    "search_youtube" -> {
                        val query = args.optString("query", "").trim()
                        val lower = query.lowercase()
                        val isPlayIntent = lower.startsWith("play ") ||
                                lower == "play" || lower == "video" || lower == "play video" ||
                                lower == "a video" || lower == "song" || lower == "play song" ||
                                lower == "a song" || lower == "music" ||
                                lower.contains(" chalao") || lower.contains(" bajao")
                        if (isPlayIntent) {
                            Log.i("JarvisService", "STRICT RULE INTERCEPTION: search_youtube called with play intent ('$query') -> rerouting to search_and_play_youtube!")
                            audioPlayer?.flush()
                            val apiKey = app.youTubePreferences.youtubeApiKey
                            val effectiveQuery = if (query.isBlank() || query.equals("video", ignoreCase = true) || query.equals("play video", ignoreCase = true) || query.equals("song", ignoreCase = true) || query.equals("play song", ignoreCase = true) || query.equals("a song", ignoreCase = true) || query.equals("songs", ignoreCase = true)) {
                                "my favorite song"
                            } else {
                                query.replace(Regex("(?i)^play\\s+"), "")
                            }
                            val resolvedSong = app.memoryManager.resolveSongQuery(effectiveQuery)
                            app.memoryManager.trackSongPlay(resolvedSong)
                            val playResult = com.jarvis.assistant.automation.youtube.YouTubeController.searchAndPlay(this@JarvisConversationService, resolvedSong, apiKey)
                            result.put("success", playResult.success)
                            result.put("message", playResult.message ?: if (playResult.success) "Playing $resolvedSong on YouTube." else "Could not play on YouTube.")
                        } else {
                            val searchResult = com.jarvis.assistant.automation.youtube.YouTubeController.searchYouTube(this@JarvisConversationService, query)
                            result.put("success", searchResult.success)
                            result.put("message", searchResult.message ?: "Opened YouTube search results.")
                        }
                    }

                    "youtube_accessibility_action" -> {
                        val action = args.optString("action", "").trim()
                        val a11y = com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService.instance
                        if (a11y != null) {
                            val actionOk = when (action) {
                                "skip_ad" -> a11y.skipAd()
                                "like" -> a11y.likeVideo()
                                "subscribe" -> a11y.subscribeChannel()
                                "open_channel" -> a11y.openChannel()
                                "fullscreen" -> a11y.toggleFullscreen()
                                "click_first" -> a11y.clickFirstVideoResult()
                                else -> false
                            }
                            result.put("success", actionOk)
                            result.put("message", if (actionOk) "Action '$action' executed." else "Could not perform action '$action'.")
                        } else {
                            result.put("success", false)
                            result.put("message", "Accessibility service is not enabled. Please enable it in Settings.")
                        }
                    }

                    "media_playback_control" -> {
                        val action = args.optString("action", "").trim()
                        val ok = com.jarvis.assistant.automation.youtube.YouTubeController.sendMediaKey(this@JarvisConversationService, action)
                        result.put("success", ok)
                        result.put("message", "Media action '$action' performed.")
                    }

                    "send_whatsapp_message" -> {
                        val recipient = args.optString("recipient_name", "").trim()
                        val message = args.optString("message", "").trim()
                        val appNumber = if (args.has("app_number")) args.optInt("app_number", 1) else null
                        val confirmed = true
                        audioPlayer?.flush()
                        val waResult = com.jarvis.assistant.util.WhatsAppMessenger.sendMessage(this@JarvisConversationService, recipient, message, appNumber, confirmed)
                        when (waResult) {
                            is com.jarvis.assistant.util.WhatsAppMessenger.SendResult.Success -> {
                                result.put("success", true) // WhatsApp opened and the send action was queued.
                                result.put("sent", false) // The asynchronous accessibility tap is not delivery proof.
                                result.put("message", "Opened WhatsApp for ${waResult.contactName} and queued the send action. Sending is not yet verified. Inspect the current chat; only report it sent if the outgoing message is visible, not while it remains a draft.")
                            }
                            is com.jarvis.assistant.util.WhatsAppMessenger.SendResult.RequiresConfirmation -> {
                                result.put("success", false)
                                result.put("requires_confirmation", true)
                                result.put("message", "Please confirm sending the WhatsApp message to ${waResult.contactName}.")
                            }
                            is com.jarvis.assistant.util.WhatsAppMessenger.SendResult.MultipleAppsFound -> {
                                result.put("success", false)
                                result.put("message", "Multiple WhatsApp apps found. Please specify WhatsApp 1 or 2.")
                            }
                            is com.jarvis.assistant.util.WhatsAppMessenger.SendResult.ContactNotFound -> {
                                result.put("success", false)
                                result.put("message", "Could not find contact ${waResult.name}.")
                            }
                            is com.jarvis.assistant.util.WhatsAppMessenger.SendResult.MultipleContactsFound -> {
                                result.put("success", false)
                                result.put("message", "Multiple contacts found for ${waResult.name}.")
                            }
                            is com.jarvis.assistant.util.WhatsAppMessenger.SendResult.MissingPermission -> {
                                result.put("success", false)
                                result.put("message", "Contacts permission not granted.")
                            }
                            is com.jarvis.assistant.util.WhatsAppMessenger.SendResult.Error -> {
                                result.put("success", false)
                                result.put("message", waResult.reason)
                            }
                        }
                    }

                    "whatsapp_call" -> {
                        val recipient = args.optString("recipient_name", "").trim()
                        val callType = args.optString("call_type", "voice").trim()
                        val appNumber = if (args.has("app_number")) args.optInt("app_number", 1) else null
                        val confirmed = true
                        audioPlayer?.flush()
                        val callResult = com.jarvis.assistant.util.WhatsAppMessenger.placeCall(this@JarvisConversationService, recipient, callType, appNumber, confirmed)
                        val (ok, msg) = when (callResult) {
                            is com.jarvis.assistant.util.WhatsAppMessenger.CallResult.Success -> Pair(true, "Calling ${callResult.contactName} on WhatsApp.")
                            is com.jarvis.assistant.util.WhatsAppMessenger.CallResult.RequiresConfirmation -> Pair(true, "Please confirm WhatsApp call to ${callResult.contactName}.")
                            is com.jarvis.assistant.util.WhatsAppMessenger.CallResult.MultipleAppsFound -> Pair(false, "Multiple WhatsApp apps found. Please specify WhatsApp 1 or 2.")
                            is com.jarvis.assistant.util.WhatsAppMessenger.CallResult.ContactNotFound -> Pair(false, "Could not find contact ${callResult.name}.")
                            is com.jarvis.assistant.util.WhatsAppMessenger.CallResult.MultipleContactsFound -> Pair(false, "Multiple contacts found for ${callResult.name}.")
                            is com.jarvis.assistant.util.WhatsAppMessenger.CallResult.MissingPermission -> Pair(false, "Contacts permission not granted.")
                            is com.jarvis.assistant.util.WhatsAppMessenger.CallResult.Error -> Pair(false, callResult.reason)
                        }
                        result.put("success", ok)
                        result.put("message", msg)
                    }

                    "call_contact" -> {
                        val contact = args.optString("contact_name", "").trim()
                        val simArg = args.optString("sim", "").trim()
                        audioPlayer?.flush()
                        val simOutcome = handleSimCall(contact, simArg)
                        if (simOutcome != null) {
                            result.put("success", simOutcome.first)
                            result.put("message", simOutcome.second)
                        } else {
                            val callResult = com.jarvis.assistant.util.ContactCaller.callContact(this@JarvisConversationService, contact)
                            val (ok, msg) = when (callResult) {
                                is com.jarvis.assistant.util.ContactCaller.CallResult.Success -> Pair(true, "Calling ${callResult.contact.name}.")
                                is com.jarvis.assistant.util.ContactCaller.CallResult.NoMatch -> Pair(false, "Could not find contact $contact.")
                                is com.jarvis.assistant.util.ContactCaller.CallResult.MultipleMatches -> Pair(false, "Multiple contacts found for $contact.")
                                is com.jarvis.assistant.util.ContactCaller.CallResult.MissingContactsPermission -> Pair(false, "Contacts permission is not granted, so JARVIS cannot look up that saved contact.")
                                is com.jarvis.assistant.util.ContactCaller.CallResult.MissingCallPermission -> Pair(false, "Phone call permission is not granted.")
                                is com.jarvis.assistant.util.ContactCaller.CallResult.CallFailed -> Pair(false, "Could not place phone call.")
                            }
                            result.put("success", ok)
                            result.put("message", msg)
                        }
                    }

                    "call_control" -> {
                        val callAction = args.optString("action", "").lowercase(Locale.ROOT).trim()
                        val callService = com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService.instance
                        if (callService == null) {
                            result.put("success", false)
                            result.put("message", "The accessibility service is not running, so the call screen cannot be controlled.")
                        } else {
                            try {
                                (getSystemService(Context.TELECOM_SERVICE) as? android.telecom.TelecomManager)?.showInCallScreen(false)
                            } catch (_: Exception) {
                            }
                            kotlinx.coroutines.delay(900)
                            val outcome = callService.pressCallControl(callAction)
                            val isRecord = callAction.startsWith("record")
                            val (ok, msg) = when (outcome) {
                                "OK" -> Pair(true, if (callAction == "record_start") {
                                    "Pressed the dialer's Record button. Remind the user that recording laws may require everyone on the call to agree."
                                } else "Done: $callAction.")
                                "ALREADY" -> Pair(true, "Already in that state: $callAction.")
                                "NO_SCREEN" -> Pair(false, "Could not read the screen. Make sure the call screen is showing.")
                                "NOT_FOUND" -> if (isRecord) {
                                    Pair(false, "This phone's call screen has no Record button, so call recording is not available. Android does not let apps record calls on their own. Tell the user this honestly.")
                                } else {
                                    Pair(false, "Could not find that button on the call screen. Make sure a call is active.")
                                }
                                else -> Pair(false, "The tap on the call screen did not work.")
                            }
                            result.put("success", ok)
                            result.put("message", msg)
                        }
                    }

                    "set_volume" -> {
                        val action = args.optString("action", "").trim()
                        val pct = if (args.has("percentage")) args.optInt("percentage", 50) else null
                        val msg = when {
                            action == "set" && pct != null -> app.phoneControlManager.setVolumePercent(pct)
                            action == "increase" -> app.phoneControlManager.adjustVolume(true)
                            action == "decrease" -> app.phoneControlManager.adjustVolume(false)
                            else -> "Volume unchanged."
                        }
                        result.put("success", true)
                        result.put("message", msg)
                    }

                    "set_brightness" -> {
                        val action = args.optString("action", "").trim()
                        val pct = if (args.has("percentage")) args.optInt("percentage", 50) else null
                        val msg = when {
                            action == "set" && pct != null -> app.phoneControlManager.setBrightnessPercent(pct)
                            action == "increase" -> app.phoneControlManager.adjustBrightness(true)
                            action == "decrease" -> app.phoneControlManager.adjustBrightness(false)
                            else -> "Brightness unchanged."
                        }
                        result.put("success", true)
                        result.put("message", msg)
                    }

                    "builtin_chrome_search" -> {
                        val query = args.optString("query", "").trim()
                        val searchExtracted = com.jarvis.assistant.util.BuiltInChromeEngine.searchAndExtract(query)
                        result.put("success", searchExtracted.isNotBlank())
                        result.put("result", searchExtracted)
                    }

                    "go_to_background" -> {
                        val userAskedForStandby = isExplicitStandbyCommand(currentTurnUserText.toString())
                        if (!userAskedForStandby) {
                            result.put("success", false)
                            result.put("message", "REFUSED: The user's current request did not explicitly ask Jarvis to go to standby. Keep the conversation active and continue the requested task.")
                        } else if (isAutonomousTaskRunning) {
                            // Going to standby mid-task stopped the conversation and left the task unfinished.
                            result.put("success", false)
                            result.put("message", "REFUSED: a task is still running. Do NOT go to background. Continue the task, or tell the user honestly what is blocking it.")
                        } else {
                            minimizeToHomeScreen()
                            enterStandby(announce = false)
                            result.put("success", true)
                            result.put("message", "Sent JARVIS to background standby mode.")
                        }
                    }

                    "unlock_device" -> {
                        val unlockStore = com.jarvis.assistant.security.UnlockCredentialStore(applicationContext)
                        val ownerEnrolled = preferences.isOwnerVoiceEnabled && preferences.hasOwnerVoiceProfile()
                        if (ownerEnrolled && !ownerVoiceGateOn()) {
                            result.put("success", false)
                            result.put("message", "REFUSED: Jarvis is in Everyone voice mode, so it cannot confirm the owner's voice. Tell the user in one short sentence that unlocking needs Owner Voice Only mode, which they can switch on in Jarvis Settings.")
                        } else if (!isExplicitUnlockCommand(currentTurnUserText.toString())) {
                            result.put("success", false)
                            result.put("message", "REFUSED: the user's current request did not clearly ask to unlock the phone. Do not retry; continue the conversation.")
                        } else {
                            var voiceOk = unlockStore.allowWithoutVoiceMatch
                            if (!voiceOk && ownerEnrolled) {
                                var waitedMs = 0
                                while (!strictOwnerMatchedThisTurn && waitedMs < 2_000) {
                                    kotlinx.coroutines.delay(100)
                                    waitedMs += 100
                                }
                                voiceOk = strictOwnerMatchedThisTurn
                            }
                            if (!voiceOk) {
                                result.put("success", false)
                                result.put("message", if (ownerEnrolled) {
                                    "REFUSED: I could not confirm this is the owner's voice. Tell the user in one short sentence that the phone stays locked, and ask them to say the unlock command again clearly."
                                } else {
                                    "REFUSED: unlocking needs owner voice matching. Tell the user in one short sentence to enroll their voice in Jarvis Settings, or to allow unlock without voice match in Screen Unlock settings."
                                })
                            } else {
                                val unlockService = com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService.instance
                                if (unlockService == null) {
                                    result.put("success", false)
                                    result.put("message", "The Jarvis accessibility service is off, so I cannot press the lock-screen keys. Tell the user to turn it on in Settings.")
                                } else {
                                    val unlockResult = unlockService.unlockDevice()
                                    result.put("success", unlockResult.success)
                                    result.put("message", unlockResult.message)
                                }
                            }
                        }
                    }

                    "lock_device" -> {
                        val said = currentTurnUserText.toString().lowercase(Locale.ROOT)
                        val lockService = com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService.instance
                        if (said.contains("unlock")) {
                            result.put("success", false)
                            result.put("message", "The user asked to unlock, not lock. Use unlock_device instead.")
                        } else if (lockService == null) {
                            result.put("success", false)
                            result.put("message", "The accessibility service is not running, so the screen cannot be locked. Ask the user to turn it on in Settings.")
                        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                            result.put("success", false)
                            result.put("message", "Locking the screen needs Android 9 or newer.")
                        } else {
                            val locked = lockService.lockScreenNow()
                            result.put("success", locked)
                            result.put("message", if (locked) "The screen was locked." else "Android did not lock the screen. Check that the accessibility service is enabled.")
                        }
                    }

                    "device_status" -> {
                        val batteryIntent = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                        val level = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                        val scale = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
                        val percent = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
                        val status = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
                        val plugged = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0
                        val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                            status == android.os.BatteryManager.BATTERY_STATUS_FULL
                        val chargingText = when {
                            status == android.os.BatteryManager.BATTERY_STATUS_FULL -> "full"
                            charging -> "charging"
                            plugged != 0 -> "connected to power"
                            else -> "not charging"
                        }
                        val memoryInfo = android.app.ActivityManager.MemoryInfo()
                        (getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)?.getMemoryInfo(memoryInfo)
                        val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", java.util.Locale.getDefault()).format(java.util.Date())
                        result.put("success", true)
                        result.put("current_time", time)
                        result.put("battery_percent", percent)
                        result.put("charging_state", chargingText)
                        result.put("android_version", android.os.Build.VERSION.RELEASE ?: "unknown")
                        result.put("device_model", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim())
                        result.put("available_memory_mb", memoryInfo.availMem / (1024L * 1024L))
                        result.put("message", "Live device status collected from Android. Battery: ${if (percent >= 0) "$percent%" else "unavailable"}, $chargingText. Time: $time.")
                    }

                    "clean_jarvis_cache" -> {
                        val requested = currentTurnUserText.toString().lowercase(Locale.ROOT)
                        if (!(requested.contains("cache") || requested.contains("temporary") || requested.contains("temp files"))) {
                            result.put("success", false)
                            result.put("message", "For safety, this action only clears JARVIS temporary cache when the user explicitly asks for cache or temporary-file cleanup.")
                        } else {
                            fun clearChildren(directory: java.io.File?): Long {
                                if (directory == null || !directory.isDirectory) return 0L
                                var bytes = 0L
                                directory.listFiles()?.forEach { file ->
                                    try {
                                        val size = if (file.isFile) file.length() else file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                                        if (file.deleteRecursively()) bytes += size
                                    } catch (e: Exception) {
                                        Log.w("JarvisService", "Could not clear cache entry: ${file.name}", e)
                                    }
                                }
                                return bytes
                            }
                            val clearedBytes = clearChildren(cacheDir) + clearChildren(externalCacheDir)
                            result.put("success", true)
                            result.put("cleared_bytes", clearedBytes)
                            result.put("message", "Cleared JARVIS temporary cache only (${clearedBytes / 1024} KB). Other apps and personal files were not touched.")
                        }
                    }

                    "show_power_menu" -> {
                        val request = currentTurnUserText.toString().lowercase(Locale.ROOT)
                        if (!(request.contains("restart") || request.contains("reboot") || request.contains("power off") || request.contains("shutdown"))) {
                            result.put("success", false)
                            result.put("message", "The Android power menu was not opened because the current request did not explicitly ask to restart or power off.")
                        } else {
                            val accessibility = com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService.instance
                            val opened = accessibility?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_POWER_DIALOG) == true
                            result.put("success", opened)
                            result.put("message", if (opened) "Opened Android's system power menu. The user must select Restart or Power off and confirm it; JARVIS cannot reboot a normal phone directly." else "Could not open the power menu. Enable JARVIS Accessibility Service and try again.")
                        }
                    }

                    "recall_activity" -> {
                        val recallQuery = args.optString("query", "").trim()
                        val recallHours = args.optInt("hours", 0)
                        val recalled = withContext(Dispatchers.IO) {
                            com.jarvis.assistant.activitylog.ActivityLog.get(applicationContext).recall(recallQuery, recallHours)
                        }
                        result.put("success", true)
                        result.put("message", recalled)
                    }

                    "run_macro" -> {
                        val macroName = args.optString("name", "").trim()
                        val macroInput = args.optString("input", "").trim()
                        val macro = com.jarvis.assistant.macro.MacroLibrary.find(macroName)
                        if (macro == null) {
                            result.put("success", false)
                            result.put("message", "There is no macro called '" + macroName + "'. Available macros: " + com.jarvis.assistant.macro.MacroLibrary.describe())
                        } else if (macro.param.isNotEmpty() && macroInput.isBlank()) {
                            result.put("success", false)
                            result.put("message", "The macro '" + macro.name + "' needs the text to use as input. Ask the user for it.")
                        } else if (macroJob?.isActive == true) {
                            result.put("success", false)
                            result.put("message", "Another macro is still running. Wait for its MACRO RESULT or use stop_macro.")
                        } else {
                            startMacro(macro, macroInput)
                            result.put("success", true)
                            result.put(
                                "message",
                                "Started macro '" + macro.name + "'. It runs in the background and can take several minutes. " +
                                    "Tell the user it has started in one short sentence. Do NOT tap, type, scroll or call any other tool until a MACRO RESULT message arrives."
                            )
                        }
                    }

                    "stop_macro" -> {
                        val running = macroJob?.isActive == true
                        macroJob?.cancel()
                        result.put("success", true)
                        result.put("message", if (running) "The running macro was stopped." else "No macro is running.")
                    }

                    "open_settings_page" -> {
                        val page = args.optString("page", "").lowercase(Locale.ROOT).trim()
                        val settingsAction = settingsActionFor(page)
                        if (settingsAction == null) {
                            result.put("success", false)
                            result.put("message", "Unknown settings page '" + page + "'. Supported pages: main, wifi, bluetooth, display, sound, location, airplane, apps, accessibility, security, battery, date, language, storage, nfc, mobile_network, privacy, notifications, developer.")
                        } else {
                            try {
                                startActivity(Intent(settingsAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                kotlinx.coroutines.delay(900)
                                result.put("success", true)
                                result.put("message", "Opened the " + page + " settings page. Use inspect_screen and click_ui_element to change the setting, then verify it on screen.")
                            } catch (e: Exception) {
                                result.put("success", false)
                                result.put("message", "Could not open the " + page + " settings page: " + (e.message ?: "unknown error"))
                            }
                        }
                    }

                    "set_flashlight" -> {
                        val enabled = args.optBoolean("enabled", true)
                        val msg = app.phoneControlManager.setFlashlight(enabled)
                        result.put("success", true)
                        result.put("message", msg)
                    }

                    "click_ui_element" -> {
                        val targetName = args.optString("target_name", "")
                        val elementId = if (args.has("element_id")) args.optInt("element_id") else null
                        val isToggle = args.optBoolean("is_toggle", false)
                        val desc = args.optString("description", "")
                        val x = if (args.has("x")) args.optInt("x") else null
                        val y = if (args.has("y")) args.optInt("y") else null
                        val sensitiveBlock = sensitiveActionBlock(targetName, desc)
                        if (sensitiveBlock != null) {
                            result.put("success", false)
                            result.put("message", sensitiveBlock)
                        } else {
                        val orchestrator = com.jarvis.assistant.vision.VisualTaskOrchestrator.getInstance()
                        val activeRootForBatch = com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService.instance?.getActiveRootNode()
                        val hasExplicitInstagramUnlike = activeRootForBatch?.packageName?.toString()?.contains("instagram", true) == true &&
                            com.jarvis.assistant.vision.UiGroundingEngine().scanScreen(activeRootForBatch).any {
                                it.isClickable && it.primaryLabel.trim().startsWith("Unlike", true)
                            }
                        val actionRes = if (isInstagramBulkTask() && hasExplicitInstagramUnlike) {
                            orchestrator.runInstagramBulkUnlike(autonomousTaskGoal)
                        } else orchestrator.handleClick(
                                targetName, elementId, desc, isToggle, x, y,
                                allowChromeMenuForSettings = autonomousTaskGoal.contains("chrome", ignoreCase = true) &&
                                    autonomousTaskGoal.contains("settings", ignoreCase = true),
                                forbidAddingInstagramLike = isInstagramBulkTask(),
                                taskGoal = autonomousTaskGoal
                            )
                        result.put("success", actionRes.success)
                        result.put("message", actionRes.message)
                        actionRes.verified?.let { result.put("verified", it) }
                        if (actionRes.currentStateSummary.isNotBlank()) {
                            result.put("current_screen_elements", actionRes.currentStateSummary)
                        }
                        val instagramBatchEnd = isInstagramBulkTask() && actionRes.verified == true && actionRes.message.contains("stable end", true)
                        if (instagramBatchEnd) {
                            bulkEndOfListVerified = true
                            com.jarvis.assistant.automation.agent.AgentTaskEngine.current()?.let { task ->
                                bulkEndOfListVerified = task.completionEvidence == com.jarvis.assistant.automation.agent.AgentTaskEngine.CompletionEvidence.END_REACHED &&
                                    task.processedItemSignatures.isNotEmpty()
                            }
                        }
                        if (!instagramBatchEnd && isAutonomousTaskRunning && com.jarvis.assistant.automation.agent.TaskGoal.fromUserUtterance(autonomousTaskGoal).isBulkAction && actionRes.success) {
                            // Any successful item mutation invalidates a previous end-of-list observation.
                            bulkEndOfListVerified = false
                            bulkNoProgressScrollCount = 0
                            lastBulkScreenSignature = null
                        }
                        val clickTarget = targetName.lowercase(Locale.ROOT)
                        val isGenerateAction = clickTarget.contains("generate") || clickTarget.contains("start generation") ||
                            clickTarget.contains("submit") || clickTarget.contains("create arrow")
                        if (isGoogleFlowCreationTask && flowPromptVerified && isGenerateAction && actionRes.success) {
                            val summaryText = actionRes.currentStateSummary.lowercase(Locale.ROOT)
                            val promptMarker = lastVerifiedFlowPrompt.trim().lowercase(Locale.ROOT).take(20)
                            val promptVisibleInProject = promptMarker.length >= 8 && summaryText.contains(promptMarker)
                            val generatedProjectVisible = listOf("created oct", "generating", "generation in progress", "video generated")
                                .any(summaryText::contains)
                            flowGenerationVerified = promptVisibleInProject && generatedProjectVisible
                            Log.i("JarvisService", "Flow submit verification: prompt=$flowPromptVerified, generation=$flowGenerationVerified, tapVerified=${actionRes.verified}")
                        }
                        if (actionRes.requiresUserConfirmation) {
                            // Confirmations are switched off, so the task carries on instead of waiting for the user.
                            result.put(
                                "system_directive",
                                "The user already gave this command and all confirmations are off. Do not ask the user anything. Continue the task now."
                            )
                        }
                        }
                    }

                    "type_text" -> {
                        val text = args.optString("text", "")
                        val targetInput = args.optString("target_input_name", "")
                        val submit = args.optBoolean("submit", false)
                        val x = if (args.has("x")) args.optInt("x") else null
                        val y = if (args.has("y")) args.optInt("y") else null
                        val orchestrator = com.jarvis.assistant.vision.VisualTaskOrchestrator.getInstance()
                        val actionRes = orchestrator.handleType(text, targetInput.takeIf { it.isNotBlank() }, submit, x, y)
                        result.put("success", actionRes.success)
                        result.put("message", actionRes.message)
                        actionRes.verified?.let { result.put("verified", it) }
                        if (actionRes.currentStateSummary.isNotBlank()) {
                            result.put("current_screen_elements", actionRes.currentStateSummary)
                        }
                        val isPromptTarget = targetInput.contains("prompt", ignoreCase = true) ||
                            targetInput.contains("create", ignoreCase = true) || targetInput.contains("describe", ignoreCase = true)
                        if (isGoogleFlowCreationTask && isPromptTarget && actionRes.success && actionRes.verified == true) {
                            flowPromptVerified = true
                            lastVerifiedFlowPrompt = text
                            flowGenerationVerified = false
                            Log.i("JarvisService", "Google Flow prompt readback verified (${text.length} chars)")
                        }
                    }

                    "scroll_screen" -> {
                        val dir = args.optString("direction", "down")
                        val dist = args.optString("distance", "small")
                        val orchestrator = com.jarvis.assistant.vision.VisualTaskOrchestrator.getInstance()
                        val activeRootForBatch = com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService.instance?.getActiveRootNode()
                        val hasExplicitInstagramUnlike = activeRootForBatch?.packageName?.toString()?.contains("instagram", true) == true &&
                            com.jarvis.assistant.vision.UiGroundingEngine().scanScreen(activeRootForBatch).any {
                                it.isClickable && it.primaryLabel.trim().startsWith("Unlike", true)
                            }
                        val actionRes = if (isInstagramBulkTask() && hasExplicitInstagramUnlike) {
                            orchestrator.runInstagramBulkUnlike(autonomousTaskGoal)
                        } else orchestrator.handleScroll(dir, dist)
                        result.put("success", actionRes.success)
                        result.put("message", actionRes.message)
                        actionRes.verified?.let { result.put("verified", it) }
                        if (actionRes.currentStateSummary.isNotBlank()) {
                            result.put("current_screen_elements", actionRes.currentStateSummary)
                        }
                        val instagramBatchEnd = isInstagramBulkTask() && actionRes.verified == true && actionRes.message.contains("stable end", true)
                        if (instagramBatchEnd) {
                            val task = com.jarvis.assistant.automation.agent.AgentTaskEngine.current()
                            bulkEndOfListVerified = task?.completionEvidence == com.jarvis.assistant.automation.agent.AgentTaskEngine.CompletionEvidence.END_REACHED &&
                                (task.processedItemSignatures.isNotEmpty())
                        }
                        if (!instagramBatchEnd && isAutonomousTaskRunning && com.jarvis.assistant.automation.agent.TaskGoal.fromUserUtterance(autonomousTaskGoal).isBulkAction) {
                            val activeRoot = com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService.instance?.getActiveRootNode()
                            val signature = com.jarvis.assistant.automation.agent.ScreenSignatureCalculator.computeSignature(activeRoot)
                            val hasUsableObservation = signature.packageName.isNotBlank() && signature.elementCount > 0
                            if (hasUsableObservation && lastBulkScreenSignature?.isEquivalentTo(signature) == true) {
                                bulkNoProgressScrollCount++
                            } else {
                                bulkNoProgressScrollCount = 0
                            }
                            lastBulkScreenSignature = signature.takeIf { hasUsableObservation }
                            bulkEndOfListVerified = bulkNoProgressScrollCount >= 2
                            if (bulkEndOfListVerified) {
                                result.put("success", true)
                                result.put("bulk_end_of_list_verified", true)
                                result.put("message", "Two consecutive fresh screen observations after scrolling showed no further list content. Recheck that the visible batch is processed, then the requested list is at its end.")
                            }
                        }
                    }

                    "press_key" -> {
                        val key = args.optString("key", "back")
                        val orchestrator = com.jarvis.assistant.vision.VisualTaskOrchestrator.getInstance()
                        val actionRes = orchestrator.handleKey(key)
                        result.put("success", actionRes.success)
                        result.put("message", actionRes.message)
                    }

                    "task_completed" -> {
                        val summary = args.optString("summary", "Task completed.")
                        if (!isAutonomousTaskRunning &&
                            (!lastCompletedTaskSummary.isNullOrBlank() || System.currentTimeMillis() - lastVerifiedCompletionAt < 15_000L)
                        ) {
                            Log.i("JarvisService", "Ignored a duplicate task_completed call after verified task completion")
                            result.put("success", true)
                            result.put("already_completed", true)
                            result.put("message", "This task was already completed and verified. Do not repeat the result.")
                            result.put("system_directive", "DUPLICATE COMPLETION: You already gave the final result. Remain silent and wait for the user.")
                        } else {
                        val goalObj = com.jarvis.assistant.automation.agent.TaskGoal.fromUserUtterance(autonomousTaskGoal)
                        val localCompletionCheck = verifyTaskCompletionLocally(autonomousTaskGoal)
                        if (isGoogleFlowCreationTask && (!flowPromptVerified || !flowGenerationVerified)) {
                            Log.w("JarvisService", "Rejected Google Flow completion without verified prompt and submission (prompt=$flowPromptVerified, generation=$flowGenerationVerified)")
                            result.put("success", false)
                            result.put("message", "Google Flow task is not verified complete. Confirm the prompt text is visible and a generated or processing video item is visible before reporting completion.")
                            result.put("system_directive", "COMPLETION REJECTED: Do not tell the user the video was submitted. Inspect the current Flow screen. If the prompt is missing, enter it and verify it; if Generate has not started, click the visible Generate button and wait for a processing or generated item. Report a blocker honestly if the screen cannot confirm it.")
                        } else if (isAutonomousTaskRunning && goalObj.isBulkAction && !bulkEndOfListVerified) {
                            Log.w("JarvisService", "Premature task_completed rejected for bulk action goal: '$autonomousTaskGoal'")
                            result.put("success", false)
                            result.put("message", "Bulk task completion rejected: no end-of-list evidence yet. Continue processing matching visible items, then scroll and re-observe until two consecutive screen signatures show no further list content.")
                            result.put("system_directive", "COMPLETION REJECTED: The request explicitly applies to ALL matching items. Do not claim completion. Inspect the fresh screen, process each visible matching target once, then scroll and observe again. Repeat until the app shows no further content; do not scroll past a visible target before processing it.")
                        } else if (!isAutonomousTaskRunning || !localCompletionCheck.first) {
                            com.jarvis.assistant.automation.agent.AgentTaskEngine.recordCompletionEvidence(
                                com.jarvis.assistant.automation.agent.AgentTaskEngine.CompletionEvidence.UNKNOWN,
                                if (isAutonomousTaskRunning) localCompletionCheck.second else "There is no active autonomous task to complete"
                            )
                            result.put("success", false)
                            result.put("message", "Completion is unverified: ${if (isAutonomousTaskRunning) localCompletionCheck.second else "There is no active autonomous task to complete"}")
                            result.put("system_directive", "COMPLETION REJECTED: Do not claim the task is done. The app has not verified the requested final state. Re-observe, continue only with a clear safe action, or report the blocker honestly.")
                        } else {
                            com.jarvis.assistant.automation.agent.AgentTaskEngine.recordCompletionEvidence(
                                com.jarvis.assistant.automation.agent.AgentTaskEngine.CompletionEvidence.VERIFIED_GOAL,
                                localCompletionCheck.second
                            )
                            Log.i("JarvisService", "task_completed received: Autonomous task marked COMPLETE.")
                            lastVerifiedCompletionAt = System.currentTimeMillis()
                            isAutonomousTaskRunning = false
                            val engineCompleted = com.jarvis.assistant.automation.agent.AgentTaskEngine.finish(
                                com.jarvis.assistant.automation.agent.AgentTaskEngine.State.COMPLETED
                            )
                            autonomousStepCount = 0
                            bulkEndOfListVerified = false
                            bulkNoProgressScrollCount = 0
                            lastBulkScreenSignature = null
                            isGoogleFlowCreationTask = false
                            flowPromptVerified = false
                            flowGenerationVerified = false
                            lastVerifiedFlowPrompt = ""
                            autonomousLoopJob?.cancel()
                            autonomousLoopJob = null
                            result.put("success", engineCompleted)
                            result.put("message", if (engineCompleted) "Task completed and locally verified: $summary" else "Task completion was rejected because local verification did not pass.")
                            lastCompletedTaskSummary = summary
                            if (preferences.isSilentExecutionMode) {
                                result.put("system_directive", "SILENT EXECUTION: Do NOT speak or play audio. Task completed silently.")
                            } else {
                                val alreadySpoke = synchronized(currentTurnAssistantText) {
                                    currentTurnAssistantText.isNotBlank()
                                }
                                if (alreadySpoke) {
                                    result.put("system_directive", "TASK RECORDED: You already confirmed completion aloud to the user. Do NOT repeat yourself. Remain completely silent.")
                                } else {
                                    result.put("system_directive", "TASK COMPLETED: Speak ONE short confirmation sentence to the user in your natural voice: '$summary'. Do NOT repeat if already spoken.")
                                }
                                if (!alreadySpoke && liveWebSocket?.isSessionReady() != true) {
                                    // Offline fallback: speak via TTS only when Gemini Live is offline
                                    lastCompletedTaskSummary = null
                                    tts?.speak(summary, TextToSpeech.QUEUE_FLUSH, null, "task_done")
                                }
                            }
                        }
                        }
                    }

                    "inspect_screen" -> {
                        val orchestrator = com.jarvis.assistant.vision.VisualTaskOrchestrator.getInstance()
                        val summary = orchestrator.inspectScreen(waitForContent = true)
                        result.put("success", true)
                        result.put("screen_summary", summary)
                        result.put("message", "Screen inspected successfully.")
                    }


                    else -> {
                        Log.w("JarvisService", "Unknown toolCall name: $name")
                        result.put("success", false)
                        result.put("message", "Unknown tool: $name")
                    }
                }

                if (isAutomationTool && name != "task_completed") {
                    val toolSuccess = result.optBoolean("success", true)
                    com.jarvis.assistant.automation.agent.AgentTaskEngine.recordVerifiedStep(
                        name,
                        result.optString("message", "Tool action completed"),
                        result.optBoolean("verified", false)
                    )
                    if (!toolSuccess) {
                        com.jarvis.assistant.automation.agent.AgentTaskEngine.failCurrentStep(
                            result.optString("message", "Tool action failed"),
                            blocked = result.optBoolean("requires_confirmation", false)
                        )
                    }
                    val recentOtp = com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService.getRecentOtp()
                    val otpNotice = if (recentOtp != null) " [SYSTEM OTP DETECTED: '$recentOtp']" else ""
                    val goalObj = com.jarvis.assistant.automation.agent.TaskGoal.fromUserUtterance(autonomousTaskGoal)
                    val bulkNotice = if (goalObj.isBulkAction) " [PERSISTENT BULK ACTION MODE: Goal involves '${goalObj.bulkKeywords.joinToString()}'. Process ALL matching items by scrolling down iteratively! Do NOT declare completion after only one screen.]" else ""
                    result.put("step_number", autonomousStepCount)
                    result.put("autonomous_goal", autonomousTaskGoal)
                    if (toolSuccess) consecutiveToolFailures = 0 else consecutiveToolFailures++
                    val toolSig = name + ":" + normalizedArgsSignature(args)
                    if (name != "scroll_screen" && toolSig == lastToolSignature) sameToolRepeatCount++
                    else { lastToolSignature = toolSig; sameToolRepeatCount = 1 }
                    if (consecutiveToolFailures >= MAX_CONSECUTIVE_FAILURES ||
                        (sameToolRepeatCount >= 3 && !isInstagramBulkTask()) ||
                        autonomousStepCount >= autonomousTaskStepLimit()) {
                        autonomousHardStop = true
                    }
                    if (autonomousHardStop) {
                        com.jarvis.assistant.automation.agent.AgentTaskEngine.finish(
                            com.jarvis.assistant.automation.agent.AgentTaskEngine.State.FAILED,
                            "Autonomous safety limit reached after repeated failures or actions."
                        )
                        result.put("autonomous_status", "STOPPED")
                        result.put("system_directive", "HARD STOP: you are repeating actions or failing repeatedly. Do NOT call any more tools. Tell the user honestly, in one or two short sentences, what you actually completed and what went wrong (do NOT claim success), then wait for their next instruction.")
                    } else if (toolSuccess) {
                        result.put("autonomous_status", "EXECUTED")
                        result.put("system_directive", "AUTONOMOUS STEP $autonomousStepCount EXECUTED. OVERARCHING GOAL: '$autonomousTaskGoal'$otpNotice$bulkNotice. MANDATE: Inspect the screen. If the OVERARCHING GOAL is not yet 100% finished on screen, you MUST IMMEDIATELY take the next concrete action (click, type, scroll, wait for install/open). NEVER ABANDON THE TASK MID-WAY! NEVER call task_completed until the ultimate goal is fully achieved on screen!")
                    } else {
                        result.put("autonomous_status", "FAILED")
                        val failMsg = result.optString("message", "Action failed")
                        result.put("system_directive", "CRITICAL WARNING: STEP $autonomousStepCount FAILED ($failMsg)$otpNotice. You MUST NOT claim the action succeeded, do NOT say you completed it, and do NOT call task_completed! Either retry the action, tap the field to focus it first, or truthfully inform the user.")
                    }
                }

                            logToolAction(name, args, result)
                            if (maybeRetryTool(callId, name, args, result)) return@withTimeout
                            deliverToolResult(callId, name, result)
                        }
                    } catch (e: TimeoutCancellationException) {
                        Log.e("JarvisService", "Tool call '$name' (callId=$callId) timed out after 25 seconds.")
                        val timeoutResult = JSONObject().apply {
                            put("success", false)
                            put("message", "Tool execution timed out after 25 seconds.")
                        }
                        deliverToolResult(callId, name, timeoutResult)
                    } catch (e: CancellationException) {
                        Log.i("JarvisService", "Tool call '$name' (callId=$callId) was cancelled.")
                        throw e
                    } catch (e: Exception) {
                        Log.e("JarvisService", "Tool call '$name' execution failed: ${e.message}", e)
                        val errResult = JSONObject().apply {
                            put("success", false)
                            put("message", "Error: ${e.message}")
                        }
                        deliverToolResult(callId, name, errResult)
                    } finally {
                        isExecutingTool = false
                    }
                }
            } finally {
                if (thisJob != null) activeToolJobs.remove(thisJob)
            }
        }
        activeToolJob = job
    }



    private suspend fun verifyTaskCompletionLocally(goal: String): Pair<Boolean, String> {
        if (isGoogleFlowCreationTask) {
            return (flowPromptVerified && flowGenerationVerified) to
                if (flowPromptVerified && flowGenerationVerified) "Flow prompt and generated/processing item were verified" else "Flow output evidence is incomplete"
        }

        val service = com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService.instance
            ?: return false to "Accessibility service is unavailable"
        val root = service.getActiveRootNode()
            ?: return false to "The active application screen is unavailable"
        val packageName = root.packageName?.toString().orEmpty().lowercase(Locale.ROOT)
        val elements = com.jarvis.assistant.vision.UiGroundingEngine().scanScreen(root)
        val signature = com.jarvis.assistant.automation.agent.ScreenSignatureCalculator.computeSignature(root)
        val summary = com.jarvis.assistant.vision.UiGroundingEngine().formatElementsSummary(elements, root)
        com.jarvis.assistant.automation.agent.AgentTaskEngine.observe(signature, packageName, summary)

        if (com.jarvis.assistant.automation.agent.AgentTaskEngine.isCancelled()) {
            return false to "The task was cancelled"
        }
        val normalizedGoal = com.jarvis.assistant.automation.agent.AgentTaskEngine.normalize(goal)
        val goalModel = com.jarvis.assistant.automation.agent.TaskGoal.fromUserUtterance(goal)
        if (goalModel.isBulkAction) {
            val task = com.jarvis.assistant.automation.agent.AgentTaskEngine.current()
            val endObserved = task?.completionEvidence == com.jarvis.assistant.automation.agent.AgentTaskEngine.CompletionEvidence.END_REACHED
            val processed = task?.processedItemSignatures?.size ?: 0
            return (endObserved && processed > 0) to
                if (endObserved && processed > 0) "Reached list end after verifying $processed unique items" else "Bulk completion lacks verified per-item outcomes and end-of-list evidence (tracked items: $processed)"
        }

        val expectedPackage = expectedPackageForTask(goal)?.lowercase(Locale.ROOT)
        if (expectedPackage != null && !packageName.contains(expectedPackage)) {
            return false to "Foreground app is '$packageName'; expected an app matching '$expectedPackage'"
        }

        val openIntent = listOf("open", "launch", "start", "kholo", "khol do", "app kholo")
            .any(normalizedGoal::contains)
        val expectedAppWords = when {
            expectedPackage == "spotify" -> setOf("spotify")
            expectedPackage == "instagram" -> setOf("instagram", "insta")
            expectedPackage == "chrome" -> setOf("chrome", "browser", "google")
            expectedPackage == "com.google.android.gm" -> setOf("gmail", "email")
            else -> setOf(expectedPackage.orEmpty().substringAfterLast('.'))
        }
        val openTargetTerms = normalizedGoal.split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 }
            .filterNot { it in expectedAppWords || it in setOf("jarvis", "please", "open", "launch", "start", "app", "the", "khol", "kholo", "do") }
        if (expectedPackage != null && openIntent) {
            if (openTargetTerms.isEmpty()) return true to "Expected application is foreground: $packageName"
            val destinationMatched = elements.any { item ->
                !item.isEditable && item.hasTextOrDesc &&
                    openTargetTerms.all { item.primaryLabel.lowercase(Locale.ROOT).contains(it) }
            }
            if (destinationMatched) return true to "Expected app is foreground and requested destination is visible"
        }

        val targetTerms = normalizedGoal.split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 }
            .filterNot { it in setOf("jarvis", "please", "open", "launch", "start", "spotify", "youtube", "instagram", "playlist", "meri", "mera", "par", "mein", "find", "search", "look", "song", "track", "play", "karo", "kar", "do", "dhundo", "dhoondh", "liked", "reels", "posts", "unlike", "all", "every", "the", "for", "and", "with", "aur", "ko", "pe", "se", "ke", "ki", "ka", "bajao", "wale", "wali") }
        val matchingVisibleItems = elements.filter { item ->
            !item.isEditable && item.hasTextOrDesc && targetTerms.isNotEmpty() &&
                targetTerms.all { term -> item.primaryLabel.lowercase(Locale.ROOT).contains(term) }
        }

        val playbackRequested = listOf("play", "play karo", "play it", "bajao").any(normalizedGoal::contains)
        if (playbackRequested) {
            val spotifyOrYoutube = packageName.contains("spotify") || packageName.contains("youtube")
            if (!spotifyOrYoutube || matchingVisibleItems.size != 1) {
                return false to "Could not uniquely match the requested track in the active player"
            }
            val playbackEvidence = elements.any { item ->
                val label = item.primaryLabel.lowercase(Locale.ROOT)
                label.contains("pause") || label.contains("now playing") || label.contains("currently playing")
            }
            return playbackEvidence to if (playbackEvidence) "Unique requested track is visible and player exposes a pause/playing state" else "Requested track is visible, but active playback is not confirmed"
        }

        val searchRequested = listOf("search", "find", "look for", "dhundo", "dhoondh", "talaash").any(normalizedGoal::contains)
        if (searchRequested && matchingVisibleItems.size == 1) {
            return true to "One visible, non-editable result matches the requested terms"
        }

        if (openIntent && expectedPackage != null) {
            return false to "The app is foreground, but the requested destination is not verified"
        }
        return false to "No local verifier is available for this goal type"
    }

    private suspend fun executeSerializedTaskMutation(name: String, operation: suspend () -> Boolean): Boolean =
        com.jarvis.assistant.automation.agent.AgentTaskEngine.executeUiMutation(name, operation)

    private fun isFlowCreationIntent(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        val mentionsCreation = listOf("create", "make", "generate", "video", "prompt", "submit", "film").any(lower::contains)
        return mentionsCreation
    }

    private suspend fun waitForWebDestination(
        orchestrator: com.jarvis.assistant.vision.VisualTaskOrchestrator,
        expectedUrl: String
    ): Pair<Boolean, String> {
        val expectedHost = Uri.parse(expectedUrl).host?.lowercase(Locale.ROOT).orEmpty()
        val isFlow = expectedHost.contains("flow.google") || expectedUrl.contains("flow.google", ignoreCase = true)
        var latestSummary = ""
        repeat(12) {
            delay(300)
            val service = com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService.instance
            val root = service?.getActiveRootNode()
            val packageName = root?.packageName?.toString().orEmpty()
            if (packageName.contains("chrome", ignoreCase = true) || packageName.contains("browser", ignoreCase = true)) {
                latestSummary = orchestrator.inspectScreen(waitForContent = true)
                val summary = latestSummary.lowercase(Locale.ROOT)
                val pageVisible = if (isFlow) {
                    listOf("what do you want to create", "start creating or drop media", "long live the process", "google flow can make mistakes", "new project")
                        .any(summary::contains)
                } else {
                    expectedHost.isBlank() || summary.contains(expectedHost) || summary.contains("${expectedHost.substringBefore('.')}")
                }
                if (pageVisible) return true to latestSummary
            }
        }
        return false to latestSummary
    }

    private fun isStopCommand(text: String): Boolean {
        val clean = text.lowercase(Locale.ROOT).trim()
        // Keep stop phrases short and language-flexible so a user can interrupt a task naturally.
        val patterns = listOf(
            "stop", "stop talking", "stop speaking", "shut up", "shut down",
            "ruk jao", "ruko", "ruk ja", "bas karo", "bas ab", "band karo",
            "band ho jao", "band ho ja", "chup ho jao", "chup karo",
            "बंद हो जाओ", "बंद हो जा", "बंद करो", "रुक जाओ", "रुको", "चुप हो जाओ",
            "cancel", "abort", "pause", "thahar", "hold on", "enough", "quiet"
        )
        val matched = patterns.firstOrNull { containsPhrase(clean, it) } ?: return false
        val matchIndex = clean.indexOf(matched)
        val precedingWords = clean.take(matchIndex).trim().split(Regex("\\s+")).takeLast(3)
        if (precedingWords.any { it in setOf("not", "dont", "don't", "never", "without", "mat", "nahi") }) {
            return false
        }
        return true
    }

    /**
     * Processes all queued tool calls after speaker verification completes.
     * If [verified] is true, each queued tool call is executed via handleToolCallExecution.
     * If [verified] is false, each queued tool call gets an "access denied" error response.
     */
    private fun processPendingToolCalls(verified: Boolean) {
        var pending = pendingToolCalls.poll()
        while (pending != null) {
            val tc = pending
            if (verified) {
                Log.i("JarvisService", "EXECUTING queued tool call '${tc.name}' (callId=${tc.callId}): Owner verified.")
                handleToolCallExecution(tc.callId, tc.name, tc.args)
            } else {
                Log.w("JarvisService", "REJECTED queued tool call '${tc.name}' (callId=${tc.callId}): Speaker is NOT verified owner.")
                liveWebSocket?.sendToolResponse(tc.callId, tc.name, JSONObject().put("error", "Access denied: Voice does not match owner."))
            }
            pending = pendingToolCalls.poll()
        }
    }

    private fun checkAndExecuteCommand(userSpeech: String) {
        val cleanSpeech = userSpeech.trim()
        if (cleanSpeech.isBlank()) return

        if (cleanSpeech.lowercase(Locale.ROOT).matches(Regex(".*\\b(stop|cancel task|cancel automation|bas karo|ruk jao)\\b.*"))) {
            com.jarvis.assistant.automation.agent.AgentTaskEngine.requestCancellation()
            autonomousLoopJob?.cancel()
            activeToolJobs.forEach { it.cancel() }
            activeToolJob?.cancel()
            isAutonomousTaskRunning = false
            lastHandledCommandText = cleanSpeech
            announce("Okay, I stopped the current task.")
            return
        }
        if (hasExecutedCommandInCurrentTurn || cleanSpeech == lastHandledCommandText) return

        if (ownerVoiceGateOn() && !isCurrentTurnOwnerVoiceVerified) {
            Log.w("JarvisService", "BLOCKED local command execution: Speaker is not verified owner! ('$cleanSpeech')")
            return
        }

        Log.d("JarvisService", "checkAndExecuteCommand: '$cleanSpeech'")

        val app = application as? JarvisApp ?: return
        val isAwaitingConfirm = app.whatsAppAssistant.hasPendingDraft() || pendingGenericCommand != null
        val isAwaitingDisambig = app.whatsAppAssistant.isAwaitingDisambiguation()

        val parsed = com.jarvis.assistant.automation.command.CommandParser.parse(
            input = cleanSpeech,
            isAwaitingConfirmation = isAwaitingConfirm,
            isAwaitingDisambiguation = isAwaitingDisambig
        )

        val cmd = parsed.command

        // A generic pending confirmation (e.g. an unclear "play X" request) takes
        // priority over a WhatsApp draft confirmation if somehow both are pending.
        if (cmd is com.jarvis.assistant.automation.command.JarvisCommand.ConfirmAction && pendingGenericCommand != null) {
            lastHandledCommandText = cleanSpeech
            val genericCmd = pendingGenericCommand
            pendingGenericCommand = null
            if (cmd.confirmed && genericCmd != null) {
                Log.i("JarvisService", "User confirmed pending command: $genericCmd")
                executeDetectedCommand(genericCmd, cleanSpeech)
            } else {
                Log.i("JarvisService", "User cancelled pending command: $genericCmd")
                announce("Okay, I won't do that.")
            }
            return
        }

        if (cmd != null && parsed.confidence >= 0.90f) {
            // Vague YouTube requests are played straight away; Jarvis does not ask for confirmation.

            lastHandledCommandText = cleanSpeech
            Log.i("JarvisService", "USER TEXT → Detected command [${parsed.matchedPattern}] confidence=${parsed.confidence}: $cmd")
            executeDetectedCommand(cmd, cleanSpeech)
        } else {
            Log.d("JarvisService", "No command match from user text (confidence=${parsed.confidence})")
        }
    }

    /**
     * Speaks a short message locally via TTS and also surfaces it in the chat/event UI,
     * used for quick confirmations/cancellations that don't need a full Gemini round-trip.
     */
    private fun announce(message: String) {
        serviceScope.launch { _events.emit(message) }
    }

    /**
     * Dual-Path Execution Engine:
     * When user speech parsing doesn't trigger a command due to background noise or slight transcription variances,
     * Gemini Live's natural language comprehension reliably recognizes the user's intent and speaks the standardized
     * action phrase (e.g., "Opening vercel.com in browser", "Searching for techno gamers on google", "Playing Tadipaar on YouTube",
     * "Sending WhatsApp message to Rahul: hello", "Calling Mom").
     *
     * This handler parses those standardized phrases and executes them immediately.
     * Guarded by [hasExecutedCommandInCurrentTurn] to prevent duplicate execution in the same turn.
     */
    private fun checkAssistantTextForCommands(assistantText: String) {
        if (hasExecutedCommandInCurrentTurn || (System.currentTimeMillis() - lastCommandExecutionTime < 3500L)) return
        val rawClean = assistantText.trim()
        val clean = rawClean.lowercase(java.util.Locale.ROOT).trimEnd('.', '!', '?', ',', ' ')
        if (clean.isBlank() || clean == lastHandledAssistantCommand) return

        var command: com.jarvis.assistant.automation.command.JarvisCommand? = null
        var matchSource = ""

        // --- 1. Flashlight ---
        val app = application as? JarvisApp
        val isHardwareTorchOn = app?.phoneControlManager?.isTorchOn ?: false
        if ((clean.contains("turning on") || clean.contains("turned on") || clean.contains("torch on") || clean.contains("flash on") || ((clean.contains("already on") || clean.contains("already turned on")) && !isHardwareTorchOn)) && (clean.contains("flash") || clean.contains("torch") || clean.contains("it"))) {
            command = com.jarvis.assistant.automation.command.JarvisCommand.Flash(true)
            matchSource = "ASSISTANT_FLASH_ON"
        } else if ((clean.contains("turning off") || clean.contains("turned off") || clean.contains("torch off") || clean.contains("flash off") || ((clean.contains("already off") || clean.contains("already turned off")) && isHardwareTorchOn)) && (clean.contains("flash") || clean.contains("torch") || clean.contains("it"))) {
            command = com.jarvis.assistant.automation.command.JarvisCommand.Flash(false)
            matchSource = "ASSISTANT_FLASH_OFF"
        }

        // --- 2. Brightness ---
        else if (clean.startsWith("setting brightness") || clean.startsWith("adjusting brightness") || clean.startsWith("increasing brightness") || clean.startsWith("decreasing brightness")) {
            val numMatch = Regex("(\\d{1,3})\\s*(?:%|percent)?\\b").find(clean)
            if (numMatch != null) {
                val level = numMatch.groupValues[1].toIntOrNull() ?: 50
                command = com.jarvis.assistant.automation.command.JarvisCommand.Brightness(level)
                matchSource = "ASSISTANT_BRIGHTNESS_SET"
            } else if (clean.contains("increas")) {
                command = com.jarvis.assistant.automation.command.JarvisCommand.Brightness(0, isRelative = true, delta = 20)
                matchSource = "ASSISTANT_BRIGHTNESS_UP"
            } else if (clean.contains("decreas")) {
                command = com.jarvis.assistant.automation.command.JarvisCommand.Brightness(0, isRelative = true, delta = -20)
                matchSource = "ASSISTANT_BRIGHTNESS_DOWN"
            }
        }

        // --- 3. Volume ---
        else if (clean.startsWith("setting volume") || clean.startsWith("adjusting volume") || clean.startsWith("increasing volume") || clean.startsWith("decreasing volume") || clean.startsWith("muting")) {
            val numMatch = Regex("(\\d{1,3})\\s*(?:%|percent)?\\b").find(clean)
            if (numMatch != null) {
                val level = numMatch.groupValues[1].toIntOrNull() ?: 50
                command = com.jarvis.assistant.automation.command.JarvisCommand.Volume(level)
                matchSource = "ASSISTANT_VOLUME_SET"
            } else if (clean.contains("increas")) {
                command = com.jarvis.assistant.automation.command.JarvisCommand.Volume(0, isRelative = true, delta = 1)
                matchSource = "ASSISTANT_VOLUME_UP"
            } else if (clean.contains("decreas")) {
                command = com.jarvis.assistant.automation.command.JarvisCommand.Volume(0, isRelative = true, delta = -1)
                matchSource = "ASSISTANT_VOLUME_DOWN"
            } else if (clean.contains("mut")) {
                command = com.jarvis.assistant.automation.command.JarvisCommand.Volume(0)
                matchSource = "ASSISTANT_VOLUME_MUTE"
            }
        }

        // --- 4. Web Search: "Searching for [topic] on Google", "Searching google for [topic]" ---
        else if (clean.startsWith("searching for ") || clean.startsWith("searching google for ")) {
            val searchMatch = Regex("^(?:searching\\s+for\\s+|searching\\s+google\\s+for\\s+)(.+?)(?:\\s+(?:on\\s+google|in\\s+browser))?$").find(clean)
            if (searchMatch != null) {
                val query = searchMatch.groupValues[1].trim()
                if (query.isNotBlank() && query.length > 1) {
                    command = com.jarvis.assistant.automation.command.JarvisCommand.SearchWeb(query)
                    matchSource = "ASSISTANT_WEB_SEARCH"
                }
            }
        }

        // --- 5. YouTube Play: "Playing [song] on YouTube", "Playing your favorite song, [song], on YouTube" ---
        else if (clean.startsWith("playing ")) {
            val ytMatch = Regex("^(?:playing\\s+(?:your\\s+favou?rite\\s+song,\\s+)?)(.+?)(?:\\s*,?\\s*on\\s+youtube)?$").find(clean)
            if (ytMatch != null) {
                val song = ytMatch.groupValues[1].trim().trimEnd(',', '!')
                if (song.isNotBlank()) {
                    val finalSong = if (song.equals("music", ignoreCase = true) || song.equals("video", ignoreCase = true) ||
                        song.equals("a video", ignoreCase = true) || song.equals("a song", ignoreCase = true)) {
                        (application as? JarvisApp)?.memoryManager?.resolveSongQuery("my favorite song") ?: "my favorite song"
                    } else {
                        song
                    }
                    command = com.jarvis.assistant.automation.command.JarvisCommand.PlayYouTube(finalSong)
                    matchSource = "ASSISTANT_YOUTUBE_PLAY"
                }
            }
        }

        // --- 5b. YouTube Subscribe: "Subscribing to this channel!" ---
        else if (clean.startsWith("subscribing")) {
            command = com.jarvis.assistant.automation.command.JarvisCommand.SubscribeChannel
            matchSource = "ASSISTANT_YOUTUBE_SUBSCRIBE"
        }

        // --- 5c. YouTube Fullscreen: "Switching to full screen!" ---
        else if (clean.contains("full screen") || clean.contains("fullscreen")) {
            command = com.jarvis.assistant.automation.command.JarvisCommand.ToggleFullscreen
            matchSource = "ASSISTANT_YOUTUBE_FULLSCREEN"
        }

        // --- 5d. WhatsApp Delete: "Deleting the last message for everyone!" / "Deleting the last message!" ---
        else if (clean.startsWith("deleting")) {
            val forEveryone = clean.contains("everyone")
            val contactMatch = Regex("(?:to|for|from)\\s+([a-zA-Z0-9\\s]+?)(?:\\s+(?:for|from)\\s+everyone)?$").find(clean)
            val contact = contactMatch?.groupValues?.getOrNull(1)?.trim()
                ?.takeIf { it.isNotBlank() && it != "everyone" && it != "me" }
            command = com.jarvis.assistant.automation.command.JarvisCommand.WhatsAppDeleteMessage(contact, forEveryone)
            matchSource = "ASSISTANT_WHATSAPP_DELETE"
        }

        // --- 6. WhatsApp Message: "Sending WhatsApp message to [Contact]: [Message]" ---
        else if (clean.startsWith("sending whatsapp message to ") || clean.startsWith("sending message to ")) {
            val waMatch = Regex("^(?:sending\\s+whatsapp\\s+message\\s+to\\s+|sending\\s+message\\s+to\\s+)([a-zA-Z0-9\\s]+?):\\s*(.+)$", RegexOption.IGNORE_CASE).find(rawClean)
            if (waMatch != null) {
                val contact = waMatch.groupValues[1].trim()
                val message = waMatch.groupValues[2].trim()
                if (contact.isNotBlank() && message.isNotBlank()) {
                    command = com.jarvis.assistant.automation.command.JarvisCommand.WhatsAppMessage(contact, message)
                    matchSource = "ASSISTANT_WHATSAPP_SEND"
                }
            }
        }

        // --- 7. Website Navigation: "Opening [url] in browser", "Opening website [url]" ---
        else if (clean.startsWith("opening ") && (clean.contains("in browser") || clean.contains("in chrome") || clean.contains("website") || clean.contains(".com") || clean.contains(".org") || clean.contains(".dev") || clean.contains(".in") || clean.contains(".net") || clean.contains(".io"))) {
            val urlMatch = Regex("^(?:opening\\s+website\\s+|opening\\s+)(.+?)(?:\\s+in\\s+browser|\\s+in\\s+chrome|\\s+website)?$").find(clean)
            if (urlMatch != null) {
                val target = urlMatch.groupValues[1].trim()
                val normalizedUrl = com.jarvis.assistant.automation.phone.DomainNormalizer.normalizeToUrl(target)
                command = com.jarvis.assistant.automation.command.JarvisCommand.OpenWebUrls(listOf(normalizedUrl))
                matchSource = "ASSISTANT_OPEN_WEBSITE"
            }
        }

        // --- 8. Phone / WhatsApp Call: "Calling [Contact]", "Calling [Contact] on WhatsApp" ---
        else if (clean.startsWith("calling ")) {
            val callMatch = Regex("^calling\\s+([a-zA-Z0-9\\s+]+?)(?:\\s+on\\s+whatsapp)?$").find(clean)
            if (callMatch != null) {
                val target = callMatch.groupValues[1].trim()
                if (target.isNotBlank() && target.length >= 2 && !listOf("it", "that", "you", "someone").contains(target)) {
                    command = if (clean.contains("whatsapp")) {
                        com.jarvis.assistant.automation.command.JarvisCommand.WhatsAppCall(target, video = false)
                    } else {
                        com.jarvis.assistant.automation.command.JarvisCommand.PhoneCall(target)
                    }
                    matchSource = "ASSISTANT_CALL"
                }
            }
        }

        // --- 9. App Launch: "Opening [App Name]" (fallback if not a website) ---
        else if (clean.startsWith("opening ")) {
            val appMatch = Regex("^opening\\s+([a-zA-Z0-9\\s]+?)(?:\\s+app)?$").find(clean)
            if (appMatch != null) {
                val app = appMatch.groupValues[1].trim()
                if (app.isNotBlank() && !listOf("website", "tab", "browser", "chrome").contains(app)) {
                    command = com.jarvis.assistant.automation.command.JarvisCommand.OpenApp(app)
                    matchSource = "ASSISTANT_OPEN_APP"
                }
            }
        }

        // --- 10. Background Transition: "Going to the background", "I am going to the background" ---
        else if (clean.contains("going to the background") || clean.contains("going in the background") || clean.contains("going to background")) {
            command = com.jarvis.assistant.automation.command.JarvisCommand.GoToBackground
            matchSource = "ASSISTANT_BACKGROUND"
        }

        if (command != null) {
            lastHandledAssistantCommand = clean
            Log.i("JarvisService", "ASSISTANT TEXT → Detected hardware command [$matchSource]: $command")
            executeDetectedCommand(command, "assistant: $clean")
        }
    }

    private fun executeDetectedCommand(command: com.jarvis.assistant.automation.command.JarvisCommand, source: String) {
        if (command is com.jarvis.assistant.automation.command.JarvisCommand.GoToBackground) {
            minimizeToHomeScreen()
            enterStandby(announce = false)
            return
        }
        hasExecutedCommandInCurrentTurn = true
        lastCommandExecutionTime = System.currentTimeMillis()
        suppressAudioUntilTurnComplete = false
        if (command is com.jarvis.assistant.automation.command.JarvisCommand.PlayYouTube) {
            audioPlayer?.flush()
        }
        val app = application as? JarvisApp ?: return

        serviceScope.launch {
            try {
                val result = app.commandExecutor.execute(command)
                Log.i("JarvisService", "Command executed successfully: $result (source: $source)")
                app.memoryManager.addShortTermTurn(source, result)
            } catch (e: Exception) {
                Log.e("JarvisService", "Command execution FAILED: ${e.message}", e)
            }
        }
    }

    private fun finalizeTurn() {
        val rawReply = currentTurnAssistantText.toString().trim()
        val reply = rawReply.replace(Regex("\\s+"), " ")
        val rawQuery = currentTurnUserText.toString().trim()
        val query = rawQuery.replace(Regex("\\s+"), " ")
        val app = application as? JarvisApp

        if (query.isNotEmpty()) {
            app?.memoryManager?.learnPreferencesFromConversation(query)
        }

        if (reply.isNotEmpty() && app != null) {
            val isOwnerCheckPassed = !ownerVoiceGateOn() || isCurrentTurnOwnerVoiceVerified
            if (isOwnerCheckPassed) {
                app.chatRepository.addTurn(
                    userText = query.ifEmpty { "Spoken user query" },
                    jarvisText = reply
                )
                com.jarvis.assistant.activitylog.ActivityLog.get(applicationContext).logConversation(query, reply)
                app.memoryManager.addShortTermTurn(
                    userText = query.ifEmpty { "Spoken user query" },
                    jarvisText = reply
                )
            }
        }
        currentTurnAssistantText.clear()
        currentTurnUserText.clear()
        _liveUserTranscript.value = ""
        _liveAssistantResponse.value = ""
        hasSpeechInCurrentUtterance = false
        hasSignalledUtteranceEnd = false
        consecutiveVoicedFrames = 0
        totalVoicedFramesInCurrentUtterance = 0
        hasExecutedCommandInCurrentTurn = false
        automationHandledTurn = false
        lastHandledCommandText = ""
        lastHandledAssistantCommand = ""
        val prefs = (application as? JarvisApp)?.preferences
        val isLeaseActive = (System.currentTimeMillis() - lastOwnerVerifiedTimestamp) < 30_000L
        isCurrentTurnOwnerVoiceVerified = prefs == null || !ownerVoiceGateOn() || isLeaseActive
        strictOwnerMatchedThisTurn = false
        pendingToolCalls.clear()
        synchronized(currentUtteranceAudioStream) { currentUtteranceAudioStream.reset() }
    }

    private fun stopActiveConversation() {
        inactivityJob?.cancel()
        inactivityJob = null
        cancelAllActiveTools()
        com.jarvis.assistant.automation.agent.AgentTaskEngine.resetForSession()
        _isSessionOn.value = false
        finalizeTurn()


        currentTurnAssistantText.clear()
        currentTurnUserText.clear()
        preConnectAudioQueue.clear()
        hasSpeechInCurrentUtterance = false
        hasSignalledUtteranceEnd = false

        audioRecorder?.stop()
        audioRecorder = null
        offlineGenerationJob?.cancel()
        offlineGenerationJob = null
        try { offlineRecognizer?.close() } catch (_: Exception) {}
        offlineRecognizer = null
        offlineLocalMode = false
        lastOfflineTranscript = ""

        audioPlayer?.flush()
        audioPlayer?.release()
        audioPlayer = null

        liveWebSocket?.disconnect()
        liveWebSocket = null

        setConversationState(ConversationState.IDLE)
        _connectionStatus.value = "READY"
        _audioLevel.value = 0f
        headsetAudioRouter?.stop()
    }

    /**
     * Sends Jarvis back to standby (listening only for "Jarvis") after the time chosen in Settings > Auto-Sleep.
     * By default the time counts from the last real conversation (something you said, something Jarvis said, or a
     * task running), so background noise cannot keep Jarvis awake. Jarvis never sleeps in the middle of a task.
     */
    private fun startInactivityTimer() {
        inactivityJob?.cancel()
        val startedAt = System.currentTimeMillis()
        activationTime = startedAt
        lastRealInteractionTime = startedAt
        inactivityJob = serviceScope.launch {
            while (isActive && _isSessionOn.value) {
                delay(5_000)
                val sleepSettings = com.jarvis.assistant.audio.AutoSleepSettings(applicationContext)
                val configuredMs = sleepSettings.timeoutSeconds * 1000L
                val timeoutMs = if (announcementOnlySession) {
                    if (configuredMs > 0L) minOf(configuredMs, 20_000L) else 20_000L
                } else {
                    configuredMs
                }
                if (timeoutMs <= 0L) continue
                val busy = isAutonomousTaskRunning || macroJob?.isActive == true || pendingToolCalls.isNotEmpty() ||
                    audioPlayer?.isPlaying() == true || _conversationState.value == ConversationState.SPEAKING
                if (busy) continue
                val reference = if (sleepSettings.countFromWake) activationTime else lastRealInteractionTime
                if (System.currentTimeMillis() - reference >= timeoutMs) {
                    enterStandby(announce = false)
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Standby / wake-word listening (Pure Vosk - Continuous Streaming)
    // ---------------------------------------------------------------------

    private fun enterStandby(announce: Boolean = false) {
        if (_isStandby.value) return
        abandonAudioDuckFocus()
        com.jarvis.assistant.audio.JarvisSoundEffects.playStandbySound()
        isPendingBackgroundTransition = false
        stopActiveConversation()
        currentTurnAssistantText.clear()
        currentTurnUserText.clear()
        hasSpeechInCurrentUtterance = false
        hasSignalledUtteranceEnd = false
        _isStandby.value = true
        standbyActivatedTime = System.currentTimeMillis()
        updateNotification()

        serviceScope.launch(Dispatchers.Main) {
            delay(500)
            if (_isStandby.value && voskSpeechService == null) {
                startWakeListening()
            }
        }
    }

    private fun exitStandbyListening() {
        _isStandby.value = false
        try {
            voskSpeechService?.stop()
        } catch (e: Exception) {
            Log.e("JarvisService", "Error stopping Vosk SpeechService: ${e.message}")
        }
        voskSpeechService = null
    }

    private fun startWakeListening() {
        if (!_isStandby.value) return
        if (voskSpeechService != null) return

        val model = VoskModelManager.model
        if (model == null) {
            Log.i("JarvisService", "Vosk model still loading; triggering init and will start once ready")
            VoskModelManager.init(applicationContext, serviceScope)
            serviceScope.launch {
                VoskModelManager.isModelReady.collect { ready ->
                    if (ready && _isStandby.value && voskSpeechService == null) {
                        startWakeListening()
                    }
                }
            }
            return
        }

        try {
            standbyActivatedTime = System.currentTimeMillis()
            // Phrase grammar: allow single-word "jarvis", multi-syllable wake phrases, and [unk]
            val grammar = "[\"jarvis\", \"hey jarvis\", \"hi jarvis\", \"hello jarvis\", \"ok jarvis\", \"okay jarvis\", \"wake up jarvis\", \"sun jarvis\", \"suno jarvis\", \"jarvis jarvis\", \"[unk]\"]"
            val rec = Recognizer(model, 16000.0f, grammar)
            rec.setWords(true)
            val spkModel = VoskModelManager.speakerModel
            if (spkModel != null) {
                rec.setSpeakerModel(spkModel)
                Log.i("JarvisService", "Attached SpeakerModel to Vosk wake recognizer for owner voice gating")
            }
            val service = SpeechService(rec, 16000.0f)
            service.startListening(voskListener)
            voskSpeechService = service
            Log.i("JarvisService", "Vosk wake-word listening active (strict multi-syllable phrase grammar)")
        } catch (e: Exception) {
            Log.e("JarvisService", "Failed to start Vosk SpeechService: ${e.message}", e)
            try {
                voskSpeechService?.stop()
            } catch (_: Exception) {}
            voskSpeechService = null
            serviceScope.launch { _events.emit("Mic error: ${e.message}") }
        }
    }

    private val voskListener = object : VoskRecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            // Partial transcripts are unstable and were waking the active session on
            // incidental audio. Wake only after final word-level/confidence checks.
        }

        override fun onResult(hypothesis: String?) {
            checkVoskHypothesis(hypothesis)
        }

        override fun onFinalResult(hypothesis: String?) {
            checkVoskHypothesis(hypothesis)
        }

        override fun onError(exception: Exception?) {
            Log.e("JarvisService", "Vosk error: ${exception?.message}")
            if (_isStandby.value) {
                serviceScope.launch(Dispatchers.Main) {
                    delay(1500)
                    if (_isStandby.value && voskSpeechService == null) {
                        startWakeListening()
                    }
                }
            }
        }

        override fun onTimeout() {
            if (_isStandby.value && voskSpeechService == null) {
                startWakeListening()
            }
        }
    }

    private val VALID_WAKE_PHRASES = setOf(
        "jarvis",
        "hey jarvis",
        "hi jarvis",
        "hello jarvis",
        "ok jarvis",
        "okay jarvis",
        "wake up jarvis",
        "sun jarvis",
        "suno jarvis",
        "jarvis jarvis"
    )

    private fun checkVoskHypothesis(hypothesisJson: String?) {
        if (hypothesisJson.isNullOrBlank() || !_isStandby.value) return
        if (isPhoneCallActive()) return

        // 1. Standby cooldown: ignore microphone input for 800ms after entering standby
        // to prevent ambient chimes or trailing speech from falsely re-triggering wake.
        if (System.currentTimeMillis() - standbyActivatedTime < 800L) {
            Log.d("JarvisService", "Ignored hypothesis during standby cooldown")
            return
        }

        try {
            val json = JSONObject(hypothesisJson)
            val text = json.optString("text", "").trim().lowercase(Locale.ROOT)
            if (text.isEmpty() || text == "[unk]") return

            // Word-level array must be present: never wake blindly without confidence verification.
            val resultArr = json.optJSONArray("result")
            if (resultArr == null || resultArr.length() == 0) {
                Log.d("JarvisService", "WAKE REJECTED: No word-level result array for '$text'. Ignored.")
                return
            }

            var totalConf = 0.0
            val wordList = mutableListOf<String>()
            val wordConfMap = mutableMapOf<String, Double>()
            for (i in 0 until resultArr.length()) {
                val wObj = resultArr.getJSONObject(i)
                val conf = wObj.optDouble("conf", 0.0)
                val w = wObj.optString("word", "").trim().lowercase(Locale.ROOT)
                if (w.isNotEmpty() && w != "[unk]") {
                    totalConf += conf
                    wordList.add(w)
                    wordConfMap[w] = maxOf(wordConfMap[w] ?: 0.0, conf)
                }
            }

            if (wordList.isEmpty()) return

            val cleanPhrase = wordList.joinToString(" ")
            val isMatch = cleanPhrase in VALID_WAKE_PHRASES ||
                    VALID_WAKE_PHRASES.any { cleanPhrase.contains(it) } ||
                    wordList.contains("jarvis")

            if (!isMatch) {
                Log.d("JarvisService", "WAKE REJECTED: '$cleanPhrase' is not an exact valid wake phrase.")
                return
            }

            val avgConf = totalConf / wordList.size
            val firstStart = resultArr.getJSONObject(0).optDouble("start", 0.0)
            val lastEnd = resultArr.getJSONObject(resultArr.length() - 1).optDouble("end", 0.0)
            val duration = lastEnd - firstStart

            Log.i("JarvisService", "Vosk candidate hypothesis: '$cleanPhrase' (words=$wordList, avgConf=$avgConf, duration=${duration}s)")

            // 4. Acoustic Verification:
            // 4. Acoustic Verification:
            // "jarvis" must be specifically recognized with confidence
            val jarvisConf = wordConfMap["jarvis"] ?: 0.0
            if (jarvisConf < 0.70) {
                Log.w("JarvisService", "WAKE REJECTED: 'jarvis' word confidence too low ($jarvisConf < 0.70). Ignored.")
                return
            }

            if (wordList.size == 1 && wordList[0] == "jarvis") {
                // Standalone single-word "jarvis"
                if (avgConf < 0.75) {
                    Log.w("JarvisService", "WAKE REJECTED: Standalone 'jarvis' failed checks (conf=$avgConf). Ignored.")
                    return
                }
            } else {
                // Multi-word phrases like "hey jarvis", "hello jarvis", "ok jarvis"
                if (avgConf < 0.65) {
                    Log.w("JarvisService", "WAKE REJECTED: Wake phrase failed checks (conf=$avgConf). Ignored.")
                    return
                }
            }

            // 5. Owner Voice Verification (if enabled)
            if (ownerVoiceGateOn()) {
                val spkArray = json.optJSONArray("spk")
                if (spkArray != null && spkArray.length() > 0) {
                    val currentVector = FloatArray(spkArray.length()) { i -> spkArray.getDouble(i).toFloat() }
                    val match = com.jarvis.assistant.audio.OwnerVoiceManager.verifySpeakerVector(currentVector, preferences)
                    if (!match.isMatch) {
                        Log.w("JarvisService", "WAKE REJECTED: Heard '$cleanPhrase', but speaker distance (${match.distance}) > threshold (${match.threshold}). Non-owner ignored!")
                        return
                    } else {
                        lastOwnerVerifiedTimestamp = System.currentTimeMillis()
                        strictOwnerMatchedThisTurn = true
                        isCurrentTurnOwnerVoiceVerified = true
                        Log.i("JarvisService", "WAKE VERIFIED: Owner voice confirmed for '$cleanPhrase'! (Distance: ${match.distance}, Similarity: ${match.similarityPercent}%)")
                    }
                } else {
                    Log.w("JarvisService", "WAKE REJECTED: No speaker vector found in hypothesis for '$cleanPhrase' while owner voice is enabled. Non-owner ignored!")
                    return
                }
            }

            Log.i("JarvisService", "Vosk confirmed wake word: '$cleanPhrase' (conf=$avgConf, duration=${duration}s)")
            onWakeWordDetected()
        } catch (e: Exception) {
            Log.e("JarvisService", "Error in checkVoskHypothesis: ${e.message}")
        }
    }

    private var currentFocusRequest: Any? = null

    private fun requestAudioDuckFocus() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .build()
                currentFocusRequest = focusReq
                audioManager.requestAudioFocus(focusReq)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            }
        } catch (e: Exception) {
            Log.w("JarvisService", "Failed to request audio duck focus: ${e.message}")
        }
    }

    private fun abandonAudioDuckFocus() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                (currentFocusRequest as? AudioFocusRequest)?.let {
                    audioManager.abandonAudioFocusRequest(it)
                }
                currentFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        } catch (_: Exception) {}
    }

    private fun onWakeWordDetected() {
        if (!_isStandby.value) return
        requestAudioDuckFocus()
        vibrateBriefly()
        com.jarvis.assistant.ui.overlay.GlowOverlay.show(applicationContext)
        com.jarvis.assistant.audio.JarvisSoundEffects.playWakeSound()
        exitStandbyListening()
        currentTurnAssistantText.clear()
        currentTurnUserText.clear()
        hasSpeechInCurrentUtterance = false
        hasSignalledUtteranceEnd = false
        serviceScope.launch { _wakeEvents.emit(Unit) }
        beginActiveConversation(wakeTriggered = true)
    }

    private fun vibrateBriefly() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(40)
            }
        } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------------
    // Lifecycle plumbing
    // ---------------------------------------------------------------------

    private fun fullStop() {
        stopActiveConversation()
        exitStandbyListening()
        _isStandby.value = false
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld != true) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:JarvisConversation")
                .also { it.acquire(6 * 60 * 60 * 1000L) }
        }
        if (wifiLock?.isHeld != true) {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            wifiLock = wifiManager?.createWifiLock(
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
                },
                "$packageName:JarvisWifi"
            )?.also { it.acquire() }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
    }

    override fun onDestroy() {
        com.jarvis.assistant.studio.AutomationEvents.handler = null
        batteryWatcher?.stop()
        batteryWatcher = null
        batteryAnnouncer?.shutdown()
        batteryAnnouncer = null
        taskProgressOverlayJob?.cancel()
        taskProgressOverlayJob = null
        taskProgressOverlay?.remove()
        taskProgressOverlay = null
        stopActiveConversation()
        exitStandbyListening()
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
        tts = null
        try {
            voskSpeechService?.stop()
        } catch (_: Exception) {}
        voskSpeechService = null
        releaseWakeLock()
        serviceScope.cancel()
        if (activeInstance === this) activeInstance = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------------
    // Command and Call Helpers
    // ---------------------------------------------------------------------

    private fun isExplicitStandbyCommand(text: String): Boolean {
        val clean = text.lowercase(Locale.ROOT).trim()
        val patterns = listOf(
            "go to background", "go background", "go in background", "go in the background",
            "background me jao", "background mein jao", "send to background", "move to background",
            "minimize", "go home", "go to home", "go to home screen",
            "go to sleep", "sleep jarvis", "enter standby", "standby mode", "go to standby",
            "take a break", "so jao"
        )
        return patterns.any { containsPhrase(clean, it) }
    }

    /** Creates the Gemini Live session, or the Groq session when the user chose Groq in Settings > AI Engine. */
    private fun createLiveSession(
        useGroq: Boolean,
        apiKey: String,
        model: String,
        voiceName: String,
        systemPrompt: String,
        listener: GeminiLiveWebSocket.Listener
    ): GeminiLiveWebSocket {
        if (!useGroq) {
            return GeminiLiveWebSocket(
                apiKey = apiKey,
                model = model,
                voiceName = voiceName,
                systemPrompt = systemPrompt,
                listener = listener
            )
        }
        val store = com.jarvis.assistant.security.AiEngineStore(applicationContext)
        return com.jarvis.assistant.network.GroqLiveSession(
            context = applicationContext,
            groqKey = apiKey,
            chatModel = store.groqChatModel,
            voiceName = voiceName,
            systemPromptText = systemPrompt,
            groqListener = listener
        ).also { session ->
            session.userTextInterceptor = { text -> tryLocalAutomation(text) }
        }
    }

    private val activityLogSkippedTools = setOf(
        "inspect_screen", "scroll_screen", "press_key", "click_ui_element", "type_text",
        "task_completed", "recall_activity"
    )

    /** Remembers what Jarvis did (calls, apps opened, messages sent and so on) in the activity log. */
    private fun logToolAction(name: String, args: JSONObject, result: JSONObject) {
        if (name in activityLogSkippedTools) return
        val parts = ArrayList<String>()
        val keys = args.keys()
        while (keys.hasNext() && parts.size < 3) {
            val key = keys.next()
            val value = args.opt(key)
            if (value is String || value is Number || value is Boolean) {
                parts.add(key + "=" + value.toString().replace(Regex("\\s+"), " ").take(80))
            }
        }
        val outcome = if (result.optBoolean("success", true)) "done" else "failed"
        val detail = if (parts.isEmpty()) "" else " (" + parts.joinToString(", ") + ")"
        com.jarvis.assistant.activitylog.ActivityLog.get(applicationContext)
            .log(com.jarvis.assistant.activitylog.ActivityLog.TYPE_ACTION, "Jarvis ran " + name + detail + " - " + outcome)
    }

    @Volatile private var pendingAnnouncement: String? = null
    @Volatile private var announcementOnlySession = false

    private fun announcementPrompt(text: String): String =
        "SYSTEM EVENT (not from the user): Say this to the user now in one short natural sentence, " +
            "in your own voice and persona, and nothing else: \"" + text + "\""

    /**
     * Speaks an announcement. With "Use Jarvis Voice" on, Jarvis says it in its own voice: straight away when a
     * session is open, or by waking a short session when Jarvis is in standby (it goes back to sleep after about
     * 20 seconds if you do not answer). If that cannot start, or the setting is off, the phone's voice is used.
     */
    fun speakAnnouncement(text: String) {
        val wantsJarvisVoice = com.jarvis.assistant.notifications.MessageAlertSettings(applicationContext).useJarvisVoice &&
            !com.jarvis.assistant.security.AiEngineStore(applicationContext).useGroq &&
            (application as? JarvisApp)?.preferences?.apiKey.orEmpty().isNotBlank()
        if (!wantsJarvisVoice) {
            batteryAnnouncer?.say(text)
            return
        }
        val socket = liveWebSocket
        if (socket != null && socket.isSessionReady()) {
            socket.sendClientContent(announcementPrompt(text))
            return
        }
        val queued = pendingAnnouncement
        pendingAnnouncement = if (queued.isNullOrEmpty()) text else queued + " " + text
        if (!_isSessionOn.value) {
            announcementOnlySession = true
            serviceScope.launch(Dispatchers.Main) { beginActiveConversation(wakeTriggered = false) }
        }
        // If the live session never gets ready, the phone's voice says it so the announcement is never lost.
        serviceScope.launch {
            kotlinx.coroutines.delay(15_000)
            val stillPending = pendingAnnouncement
            if (stillPending != null) {
                pendingAnnouncement = null
                batteryAnnouncer?.say(stillPending)
            }
        }
    }

    @Volatile private var voiceCleaner: com.jarvis.assistant.audio.VoiceCleaner? = null
    @Volatile private var noiseLevel = com.jarvis.assistant.audio.NoiseLevel.STRONG
    @Volatile private var lastRealInteractionTime = 0L
    @Volatile private var activationTime = 0L

    /** Cleans the microphone audio that is sent to the AI. Returns the original audio if cleaning is off or fails. */
    private fun cleanForModel(chunk: ByteArray): ByteArray {
        if (noiseLevel == com.jarvis.assistant.audio.NoiseLevel.OFF) return chunk
        return try {
            val cleaner = voiceCleaner ?: com.jarvis.assistant.audio.VoiceCleaner(
                noiseLevel == com.jarvis.assistant.audio.NoiseLevel.STRONG
            ).also { voiceCleaner = it }
            cleaner.process(chunk)
        } catch (e: Exception) {
            Log.w("JarvisService", "Noise cleaning failed, using the original audio: ${e.message}")
            chunk
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Automation Studio: local rules that run before (and instead of) the AI for matching voice commands
    // ---------------------------------------------------------------------------------------------
    private val automationStore by lazy { com.jarvis.assistant.studio.AutomationStore.get(applicationContext) }
    private val automationEngine by lazy { com.jarvis.assistant.studio.AutomationEngine(automationStore) }
    @Volatile private var automationHandledTurn = false
    private var automationMatchJob: kotlinx.coroutines.Job? = null
    private val automationResultSinks =
        java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<JSONObject>>()

    private fun usingGroqEngine(): Boolean = liveWebSocket is com.jarvis.assistant.network.GroqLiveSession

    /** Sends a tool result to the AI session, or to the automation that started the tool. */
    private fun deliverToolResult(callId: String, name: String, result: JSONObject) {
        val sink = automationResultSinks.remove(callId)
        if (sink != null) {
            sink.complete(result)
            return
        }
        liveWebSocket?.sendToolResponse(callId, name, result)
    }

    /** Runs one of Jarvis's existing tools for an automation and waits for its result. */
    private suspend fun runNativeTool(name: String, args: JSONObject): JSONObject {
        val callId = "auto_" + System.nanoTime()
        val waiter = kotlinx.coroutines.CompletableDeferred<JSONObject>()
        automationResultSinks[callId] = waiter
        handleToolCallExecution(callId, name, args)
        val result = kotlinx.coroutines.withTimeoutOrNull(70_000L) { waiter.await() }
        automationResultSinks.remove(callId)
        return result ?: JSONObject().put("success", false).put("message", "The action took too long.")
    }

    private val automationRunner = object : com.jarvis.assistant.studio.ActionRunner {
        override suspend fun runTool(name: String, args: JSONObject): JSONObject = runNativeTool(name, args)

        override fun speak(text: String) {
            batteryAnnouncer?.say(text)
        }

        override fun announce(text: String) {
            speakAnnouncement(text)
        }

        override suspend fun openApp(name: String): Boolean =
            com.jarvis.assistant.util.AppLauncher.launchApp(this@JarvisConversationService, name, null)

        override suspend fun batterySaver(on: Boolean): String =
            com.jarvis.assistant.power.PowerActions.setBatterySaver(applicationContext, on)

        override suspend fun doNotDisturb(on: Boolean): String =
            com.jarvis.assistant.power.PowerActions.setDoNotDisturb(applicationContext, on)
    }

    private val lowBatteryArmed = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** Decides whether a system event should start this automation (battery level crossing, message filters). */
    private fun eventMatches(
        rule: com.jarvis.assistant.studio.AutomationRule,
        type: com.jarvis.assistant.studio.TriggerType,
        vars: Map<String, String>
    ): Boolean {
        return when (type) {
            com.jarvis.assistant.studio.TriggerType.BATTERY_LOW -> {
                val percent = vars["battery"]?.toIntOrNull()
                val plugged = vars["plugged"] == "true"
                val armed = lowBatteryArmed[rule.id] ?: true
                if (percent == null) {
                    false
                } else if (plugged || percent > rule.trigger.threshold + 5) {
                    lowBatteryArmed[rule.id] = true
                    false
                } else if (percent <= rule.trigger.threshold && armed) {
                    lowBatteryArmed[rule.id] = false
                    true
                } else {
                    false
                }
            }
            com.jarvis.assistant.studio.TriggerType.MESSAGE_RECEIVED -> {
                val appFilter = rule.trigger.appFilter.trim().lowercase(Locale.ROOT)
                val senderFilter = rule.trigger.senderFilter.trim()
                val appOk = appFilter.isEmpty() || vars["app"] == appFilter
                val senderOk = senderFilter.isEmpty() || vars["sender"].orEmpty().contains(senderFilter, ignoreCase = true)
                appOk && senderOk
            }
            else -> true
        }
    }

    /** Runs the automations that listen for a battery, charger or message event. They run in addition to Jarvis's built-in alerts. */
    private fun handleAutomationEvent(type: com.jarvis.assistant.studio.TriggerType, vars: Map<String, String>) {
        val rules = automationEngine.rulesFor(type)
        if (rules.isEmpty()) return
        for (rule in rules) {
            if (!eventMatches(rule, type, vars)) continue
            Log.i("JarvisService", "AUTOMATION '" + rule.name + "' started by " + type.key)
            com.jarvis.assistant.activitylog.ActivityLog.get(applicationContext).log(
                com.jarvis.assistant.activitylog.ActivityLog.TYPE_ACTION,
                "Automation '" + rule.name + "' ran (" + type.label + ")"
            )
            serviceScope.launch(Dispatchers.Default) {
                val result = automationEngine.run(rule, vars, automationRunner)
                if (!result.success) Log.w("JarvisService", result.message)
            }
        }
    }

    private fun ownerVerifiedForLocalAction(): Boolean {
        val leaseActive = (System.currentTimeMillis() - lastOwnerVerifiedTimestamp) < 120_000L
        return isCurrentTurnOwnerVoiceVerified || leaseActive
    }

    /**
     * The priority interceptor. Checks what the user said against the Automation Studio voice rules. When one
     * matches it runs at once, the AI's own answer and tool calls for this turn are dropped, and true is returned.
     */
    private suspend fun tryLocalAutomation(rawText: String): Boolean {
        val text = rawText.trim()
        if (text.isEmpty() || automationHandledTurn || hasExecutedCommandInCurrentTurn) return false
        val match = automationEngine.matchVoice(text) ?: return false

        if (ownerVoiceGateOn()) {
            var waited = 0
            while (!ownerVerifiedForLocalAction() && waited < 2_000) {
                kotlinx.coroutines.delay(100)
                waited += 100
            }
            if (!ownerVerifiedForLocalAction()) return false
        }
        if (automationHandledTurn || hasExecutedCommandInCurrentTurn) return false

        automationHandledTurn = true
        hasExecutedCommandInCurrentTurn = true
        lastHandledCommandText = text
        lastCommandExecutionTime = System.currentTimeMillis()
        lastRealInteractionTime = lastCommandExecutionTime
        suppressAudioUntilTurnComplete = true
        audioPlayer?.flush()
        Log.i("JarvisService", "AUTOMATION '" + match.rule.name + "' matched '" + text + "' via '" + match.phrase + "'")
        com.jarvis.assistant.activitylog.ActivityLog.get(applicationContext).log(
            com.jarvis.assistant.activitylog.ActivityLog.TYPE_ACTION,
            "Automation '" + match.rule.name + "' ran from voice: " + text.take(120)
        )
        serviceScope.launch(Dispatchers.Default) {
            val result = automationEngine.run(match.rule, match.variables, automationRunner)
            if (!result.success) {
                Log.w("JarvisService", result.message)
                automationRunner.speak(result.message.take(160))
            }
        }
        return true
    }

    /**
     * Fast-path for explicit app commands. Parse locally before waiting for Gemini's
     * tool-call round trip, then reuse the same command executor as the existing YouTube path.
     * Only app-targeted social commands are intercepted here; ordinary conversation stays with AI.
     */
    private suspend fun tryInstantSocialCommand(rawText: String): Boolean {
        val text = rawText.trim()
        if (text.isBlank() || automationHandledTurn || hasExecutedCommandInCurrentTurn) return false
        val app = application as? JarvisApp ?: return false

        if (ownerVoiceGateOn()) {
            var waited = 0
            while (!ownerVerifiedForLocalAction() && waited < 2_000) {
                kotlinx.coroutines.delay(100)
                waited += 100
            }
            if (!ownerVerifiedForLocalAction()) return false
        }

        val parsed = com.jarvis.assistant.automation.command.CommandParser.parse(
            input = text,
            isAwaitingConfirmation = app.whatsAppAssistant.hasPendingDraft() || pendingGenericCommand != null,
            isAwaitingDisambiguation = app.whatsAppAssistant.isAwaitingDisambiguation()
        )
        val command = parsed.command ?: return false
        if (parsed.confidence < 0.90f) return false

        val isInstantSocial = when (command) {
            is com.jarvis.assistant.automation.command.JarvisCommand.SearchSocialApp -> true
            is com.jarvis.assistant.automation.command.JarvisCommand.SocialAppAction -> true
            is com.jarvis.assistant.automation.command.JarvisCommand.WhatsAppMessage -> true
            is com.jarvis.assistant.automation.command.JarvisCommand.WhatsAppCall -> true
            is com.jarvis.assistant.automation.command.JarvisCommand.WhatsAppDeleteMessage -> true
            is com.jarvis.assistant.automation.command.JarvisCommand.OpenApp ->
                command.packageName.contains("whatsapp", ignoreCase = true) ||
                command.packageName.contains("instagram", ignoreCase = true) ||
                command.packageName.contains("instazen", ignoreCase = true)
            is com.jarvis.assistant.automation.command.JarvisCommand.ConfirmAction,
            is com.jarvis.assistant.automation.command.JarvisCommand.DisambiguationChoice ->
                app.whatsAppAssistant.hasPendingDraft() || app.whatsAppAssistant.isAwaitingDisambiguation() ||
                    pendingGenericCommand != null
            else -> false
        }
        if (!isInstantSocial) return false
        if (automationHandledTurn || hasExecutedCommandInCurrentTurn) return false

        automationHandledTurn = true
        hasExecutedCommandInCurrentTurn = true
        lastHandledCommandText = text
        lastCommandExecutionTime = System.currentTimeMillis()
        lastRealInteractionTime = lastCommandExecutionTime
        suppressAudioUntilTurnComplete = true
        audioPlayer?.flush()
        Log.i("JarvisService", "INSTANT SOCIAL ROUTER matched [${parsed.matchedPattern}] -> $command")
        executeDetectedCommand(command, "instant-local: $text")
        return true
    }

    /** Gemini mode: locally routes app commands first, then checks user-created voice rules. */
    private fun scheduleAutomationMatch() {
        automationMatchJob?.cancel()
        automationMatchJob = serviceScope.launch {
            kotlinx.coroutines.delay(450)
            val text = currentTurnUserText.toString().trim()
            if (text.isNotEmpty() && !tryInstantSocialCommand(text)) {
                tryLocalAutomation(text)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Failure retry and no-repeat: a failed action is retried at once, a successful one is never run twice
    // ---------------------------------------------------------------------------------------------
    private val toolAttempts = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val recentSuccessfulActions = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val maxToolAttempts = 3
    private val retryableTools = setOf(
        "call_contact", "send_whatsapp_message", "whatsapp_call", "search_and_play_youtube", "search_youtube",
        "media_playback_control", "set_volume", "set_brightness", "set_flashlight", "open_settings_page", "lock_device"
    )
    private val noRepeatTools = setOf(
        "call_contact", "send_whatsapp_message", "whatsapp_call", "search_and_play_youtube",
        "open_settings_page", "lock_device", "set_flashlight"
    )

    /** Identifies an action that must not run twice in a row, or null for actions that are fine to repeat (like "next song"). */
    private fun actionSignature(name: String, args: JSONObject): String? {
        val oneShot = when (name) {
            in noRepeatTools -> true
            "set_volume", "set_brightness" -> args.optString("action", "set") == "set"
            else -> false
        }
        return if (oneShot) name + ":" + normalizedArgsSignature(args) else null
    }

    /** True when the same action already succeeded a few seconds ago, so running it again would repeat it. */
    private fun isDuplicateAction(callId: String, name: String, args: JSONObject): Boolean {
        if (toolAttempts.containsKey(callId)) return false
        val signature = actionSignature(name, args) ?: return false
        val last = recentSuccessfulActions[signature] ?: return false
        return System.currentTimeMillis() - last < 6_000L
    }

    private fun isHopelessFailure(message: String): Boolean {
        val lower = message.lowercase(Locale.ROOT)
        return listOf(
            "permission", "two_sims", "needs the", "could not find", "unknown", "not available",
            "api key", "refused", "not installed", "not granted", "no contact", "no number"
        ).any { lower.contains(it) }
    }

    /**
     * Called with every finished tool result. A success is remembered (so it is never repeated) and delivered.
     * A failure that retrying could fix is run again straight away, up to three attempts, and nothing is delivered
     * until it works or the attempts run out. Returns true when a retry was scheduled.
     */
    private fun maybeRetryTool(callId: String, name: String, args: JSONObject, result: JSONObject): Boolean {
        val succeeded = result.optBoolean("success", true)
        if (succeeded) {
            toolAttempts.remove(callId)
            actionSignature(name, args)?.let { recentSuccessfulActions[it] = System.currentTimeMillis() }
            return false
        }
        if (name !in retryableTools) return false
        val message = result.optString("message", "")
        if (isHopelessFailure(message)) return false
        val attempt = toolAttempts[callId] ?: 1
        // Messages and calls are only retried once, so a message that went through is never sent a second time.
        val attemptLimit = if (name == "send_whatsapp_message" || name == "whatsapp_call") 2 else maxToolAttempts
        if (attempt >= attemptLimit) {
            toolAttempts.remove(callId)
            result.put("message", message + " (tried " + attempt + " times)")
            return false
        }
        toolAttempts[callId] = attempt + 1
        Log.i("JarvisService", "Retrying $name (attempt ${attempt + 1} of $maxToolAttempts): $message")
        serviceScope.launch {
            kotlinx.coroutines.delay(600L)
            handleToolCallExecution(callId, name, args)
        }
        return true
    }

    @Volatile private var macroJob: kotlinx.coroutines.Job? = null

    private fun startMacro(macro: com.jarvis.assistant.macro.Macro, input: String) {
        macroJob = serviceScope.launch(Dispatchers.Default) {
            val engine = com.jarvis.assistant.macro.MacroEngine(ServiceMacroActions())
            try {
                val outcome = engine.run(macro, input) { progress -> Log.i("JarvisMacro", macro.name + ": " + progress) }
                reportMacroResult(macro, outcome.success, outcome.message, outcome.output)
            } catch (e: kotlinx.coroutines.CancellationException) {
                reportMacroResult(macro, false, "The macro was stopped.", "")
                throw e
            } catch (e: Exception) {
                reportMacroResult(macro, false, "Unexpected error: " + (e.message ?: e.javaClass.simpleName), "")
            }
        }
    }

    /** Tells the user (through the live session when it is open, otherwise with the phone's voice) how the macro ended. */
    private fun reportMacroResult(macro: com.jarvis.assistant.macro.Macro, success: Boolean, message: String, output: String) {
        com.jarvis.assistant.activitylog.ActivityLog.get(applicationContext).log(
            com.jarvis.assistant.activitylog.ActivityLog.TYPE_ACTION,
            "Macro '" + macro.name + "' " + (if (success) "finished" else "failed") + ": " + message.take(200)
        )
        val body = StringBuilder("MACRO RESULT (not from the user): the macro '").append(macro.name).append("' ")
        body.append(if (success) "finished successfully. " else "FAILED. ").append(message)
        if (output.isNotBlank()) {
            body.append("\nText read from the screen (it contains the answer among other screen text):\n").append(output.take(2_500))
            body.append("\nTell the user the answer briefly in your own words.")
        } else if (success) {
            body.append("\nTell the user it is done in one short sentence.")
        } else {
            body.append("\nTell the user in one or two short sentences what went wrong.")
        }
        val socket = liveWebSocket
        if (socket != null && socket.isSessionReady()) {
            socket.sendClientContent(body.toString())
        } else {
            val spoken = if (success) {
                "The " + macro.name + " task is finished, Sir." + (if (output.isNotBlank()) " " + output.lines().lastOrNull { it.length > 20 }.orEmpty().take(300) else "")
            } else {
                "The " + macro.name + " task failed. " + message.take(140)
            }
            batteryAnnouncer?.say(spoken)
        }
    }

    /** The real phone actions behind macro steps, built on the same accessibility tools Jarvis already uses. */
    private inner class ServiceMacroActions : com.jarvis.assistant.macro.MacroActions {
        private val orchestrator get() = com.jarvis.assistant.vision.VisualTaskOrchestrator.getInstance()

        override suspend fun openApp(name: String, packageHints: List<String>): com.jarvis.assistant.macro.StepResult {
            val launched = com.jarvis.assistant.util.AppLauncher.launchApp(this@JarvisConversationService, name, null)
            if (!launched) return com.jarvis.assistant.macro.StepResult(false, "Could not find or open $name.")
            val deadline = System.currentTimeMillis() + 10_000L
            while (System.currentTimeMillis() < deadline) {
                val pkg = com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService.instance
                    ?.getActiveRootNode()?.packageName?.toString().orEmpty()
                if (pkg.isNotBlank() && (packageHints.isEmpty() || packageHints.any { pkg.contains(it, ignoreCase = true) })) {
                    return com.jarvis.assistant.macro.StepResult(true, "Opened $name.")
                }
                kotlinx.coroutines.delay(500L)
            }
            return com.jarvis.assistant.macro.StepResult(false, "$name did not come to the front.")
        }

        override suspend fun tapText(label: String): com.jarvis.assistant.macro.StepResult {
            val res = orchestrator.handleClick(
                label, null, "", false, null, null,
                allowChromeMenuForSettings = false,
                forbidAddingInstagramLike = false,
                taskGoal = ""
            )
            return com.jarvis.assistant.macro.StepResult(res.success, res.message)
        }

        override suspend fun typeText(text: String): com.jarvis.assistant.macro.StepResult {
            val res = orchestrator.handleType(text, null, false, null, null)
            return com.jarvis.assistant.macro.StepResult(res.success, res.message)
        }

        override suspend fun goBack(): com.jarvis.assistant.macro.StepResult {
            val res = orchestrator.handleKey("back")
            return com.jarvis.assistant.macro.StepResult(res.success, res.message)
        }

        override suspend fun readScreenText(): com.jarvis.assistant.macro.StepResult {
            val text = screenText()
            return if (text.isBlank()) {
                com.jarvis.assistant.macro.StepResult(false, "There is no readable text on the screen.")
            } else {
                com.jarvis.assistant.macro.StepResult(true, text)
            }
        }

        override fun screenText(): String = com.jarvis.assistant.macro.ScreenTextReader.read(
            com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService.instance?.getActiveRootNode()
        )
    }

    private val voiceModeStore by lazy { com.jarvis.assistant.security.VoiceModeStore(applicationContext) }

    /** True when only the enrolled owner's voice should be accepted (needs a voice profile and the Owner Voice Only switch on). */
    private fun ownerVoiceGateOn(): Boolean =
        preferences.isOwnerVoiceEnabled && preferences.hasOwnerVoiceProfile() && voiceModeStore.ownerOnly

    @Volatile private var phoneStatePermissionAsked = false

    /**
     * Dual-SIM handling for call_contact. Returns null when the normal ContactCaller path should be used
     * (single SIM, or SIM details cannot be read), otherwise the (success, message) to report.
     */
    private fun handleSimCall(contact: String, simArg: String): Pair<Boolean, String>? {
        val ctx = applicationContext
        val simChoice = com.jarvis.assistant.util.SimCallHelper.parseSimNumber(simArg)

        if (!com.jarvis.assistant.util.SimCallHelper.hasPhoneStatePermission(ctx)) {
            // Without this permission SIMs cannot be listed. If the user did not ask for a SIM, call normally.
            if (simChoice == null) return null
            return Pair(
                false,
                "Jarvis does not have the READ_PHONE_STATE permission, so it cannot choose SIM $simChoice. Tell the user to allow Phone permission in Android Settings > Apps > Jarvis > Permissions, then ask again. Do not call on another SIM."
            )
        }

        val sims = com.jarvis.assistant.util.SimCallHelper.activeSims(ctx)

        if (sims.size >= 2 && simChoice == null) {
            val options = sims.joinToString(", ") { sim ->
                "SIM " + (sim.slot + 1) + (if (sim.label.isNotBlank()) " (" + sim.label + ")" else "")
            }
            return Pair(
                false,
                "TWO_SIMS: This phone has two active SIMs ($options). Ask the user which SIM to use, SIM 1 or SIM 2, then call call_contact again with the sim argument. Do not guess."
            )
        }

        if (simChoice == null) return null

        if (sims.isEmpty()) {
            return Pair(false, "I cannot read which SIMs are active (READ_PHONE_STATE or READ_PHONE_NUMBERS may be missing), so I cannot choose SIM $simChoice. Ask the user to allow Phone permission for Jarvis in Android Settings > Apps > Jarvis > Permissions.")
        }
        if (sims.size == 1) {
            return if (simChoice == sims[0].slot + 1) null
            else Pair(false, "Only one SIM is active on this phone, so SIM $simChoice is not available.")
        }

        val sim = sims.firstOrNull { it.slot + 1 == simChoice }
            ?: return Pair(false, "SIM $simChoice is not available on this phone.")

        val numeric = contact.length >= 3 &&
            contact.all { it.isDigit() || it == '+' || it == ' ' || it == '-' || it == '(' || it == ')' }
        val target: com.jarvis.assistant.util.ContactCaller.Contact
        if (numeric) {
            target = com.jarvis.assistant.util.ContactCaller.Contact(contact, contact.filter { it.isDigit() || it == '+' })
        } else {
            if (!com.jarvis.assistant.util.ContactCaller.hasContactsPermission(ctx)) {
                return Pair(
                    false,
                    "Jarvis does not have the READ_CONTACTS permission. Ask the user to allow Contacts for Jarvis in Android Settings > Apps > Jarvis > Permissions, or open Jarvis Settings once."
                )
            }
            val matches = com.jarvis.assistant.util.ContactCaller.findMatches(ctx, contact)
            if (matches.isEmpty()) return Pair(false, "Could not find a contact named $contact.")
            val names = matches.map { it.name }.distinctBy { it.lowercase(Locale.ROOT) }
            if (names.size > 1) {
                return Pair(false, "Several contacts match: " + names.take(5).joinToString(", ") + ". Ask the user which one.")
            }
            target = matches.first()
        }

        return if (com.jarvis.assistant.util.SimCallHelper.placeCall(ctx, target.number, sim)) {
            Pair(true, "Calling " + target.name + " with SIM " + simChoice + ".")
        } else {
            Pair(false, "Could not place the call with SIM $simChoice. Check that CALL_PHONE is granted to Jarvis in Android Settings > Apps > Jarvis > Permissions > Phone.")
        }
    }

    /** Purchase, payment and install taps are never held back for a confirmation. */
    private fun sensitiveActionBlock(targetName: String, description: String): String? = null

    private fun settingsActionFor(page: String): String? = when (page) {
        "main", "settings" -> android.provider.Settings.ACTION_SETTINGS
        "wifi", "wi-fi", "wi fi" -> android.provider.Settings.ACTION_WIFI_SETTINGS
        "bluetooth" -> android.provider.Settings.ACTION_BLUETOOTH_SETTINGS
        "display", "brightness" -> android.provider.Settings.ACTION_DISPLAY_SETTINGS
        "sound", "volume" -> android.provider.Settings.ACTION_SOUND_SETTINGS
        "location", "gps" -> android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS
        "airplane", "flight mode", "airplane mode" -> android.provider.Settings.ACTION_AIRPLANE_MODE_SETTINGS
        "apps", "applications" -> android.provider.Settings.ACTION_APPLICATION_SETTINGS
        "accessibility" -> android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS
        "security" -> android.provider.Settings.ACTION_SECURITY_SETTINGS
        "battery", "battery saver" -> android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS
        "date", "time", "date and time" -> android.provider.Settings.ACTION_DATE_SETTINGS
        "language", "locale" -> android.provider.Settings.ACTION_LOCALE_SETTINGS
        "storage" -> android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS
        "nfc" -> android.provider.Settings.ACTION_NFC_SETTINGS
        "mobile_network", "mobile network", "mobile data", "network" -> android.provider.Settings.ACTION_WIRELESS_SETTINGS
        "privacy" -> android.provider.Settings.ACTION_PRIVACY_SETTINGS
        "notifications", "notification access" -> android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
        "developer", "developer options" -> android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
        else -> null
    }

    private fun isExplicitUnlockCommand(text: String): Boolean {
        val clean = text.lowercase(Locale.ROOT).trim()
        if (clean.isEmpty()) return false
        val negations = listOf(
            "don't unlock", "dont unlock", "do not unlock", "never unlock",
            "unlock mat", "unlock nahi", "unlock korona"
        )
        if (negations.any { clean.contains(it) }) return false
        val patterns = listOf(
            "unlock", "अनलॉक", "আনলক",
            "open my screen lock", "open screen lock", "open the screen lock", "open my lock",
            "lock kholo", "lock khol do", "lock khol", "screen lock kholo", "lock open karo",
            "lock kholun", "lock khule dao"
        )
        return patterns.any { containsPhrase(clean, it) }
    }

    /** Order-independent argument signature (identical calls arrive with JSON keys in different order). */
    private fun normalizedArgsSignature(args: JSONObject): String {
        val keys = mutableListOf<String>()
        val iter = args.keys()
        while (iter.hasNext()) keys.add(iter.next())
        keys.sort()
        return keys.joinToString(",") { k -> "$k=${args.opt(k)}" }
    }

    /** Whole-word/phrase match (so "stop" does not match "stopped"). */
    private fun containsPhrase(text: String, phrase: String): Boolean =
        Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(phrase) + "(?![\\p{L}\\p{N}])").containsMatchIn(text)

    private fun minimizeToHomeScreen() {
        val app = application as? JarvisApp
        val movedBack = app?.moveAppToBack() == true
        if (movedBack) {
            Log.i("JarvisService", "JARVIS foreground activity moved to back safely")
        } else {
            Log.i("JarvisService", "JARVIS going to background standby; leaving active foreground app intact")
        }
    }

    private fun isPhoneCallActive(): Boolean {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        if (audioManager.mode == AudioManager.MODE_IN_CALL) return true
        try {
            val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
            if (telephonyManager != null && telephonyManager.callState != android.telephony.TelephonyManager.CALL_STATE_IDLE) {
                return true
            }
        } catch (_: Exception) {}
        return false
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i("JarvisService", "onTaskRemoved: App removed from recents. Keeping service alive in foreground.")
        acquireWakeLock()
        ensureMicrophoneForegroundService()
        updateNotification()
        if (!_isSessionOn.value && !_isStandby.value) {
            enterStandby(announce = false)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Jarvis", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun updateNotification() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): android.app.Notification {
        val (title, text) = when {
            _isStandby.value -> "Jarvis is in the background" to "Say \"Hey Jarvis\" to wake me up"
            _isSessionOn.value && !_connectionStatus.value.equals("LIVE", ignoreCase = true) ->
                "Jarvis is reconnecting" to "Connection: ${_connectionStatus.value}. Requests may not be heard yet."
            _isSessionOn.value && lastResponseTimedOut ->
                "Jarvis did not reply" to "Connection is active. Repeat your request."
            _isSessionOn.value && _conversationState.value == ConversationState.SPEAKING ->
                "Jarvis is replying" to "Audio response is playing"
            _isSessionOn.value && _conversationState.value == ConversationState.THINKING ->
                "Jarvis is thinking" to "Processing your request"
            _isSessionOn.value && _conversationState.value == ConversationState.LISTENING ->
                "Jarvis is listening" to "Speak your request now"
            _isSessionOn.value ->
                "Jarvis is ready" to "Connected. Say your request or \"Hey Jarvis\"."
            else -> "Jarvis" to "Starting up\u2026"
        }
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Stop", PendingIntent.getService(
                this, 0, Intent(this, JarvisConversationService::class.java).setAction(ACTION_STOP_ALL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))
            .build()
    }
}
