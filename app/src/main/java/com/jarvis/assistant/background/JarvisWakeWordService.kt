package com.jarvis.assistant.background

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.audio.VoiceIntentGate
import com.jarvis.assistant.ui.home.MainActivity

/**
 * Always-on foreground service that listens for "Hey Jarvis" wake phrases using
 * Android's SpeechRecognizer in PARTIAL-result mode.
 *
 * Explicit State machine:
 *   IDLE -> INITIALIZING -> LISTENING -> PROCESSING -> SPEAKING -> ERROR
 *
 * Features:
 * 1. Half-duplex guard: Pauses microphone capture while assistant audio/TTS is playing
 *    and enforces a 300ms acoustic echo guard delay after playback completion.
 * 2. Infinite mic loop elimination: Guaranteed 400ms backoff on restarts and clean halt
 *    on consecutive RECOGNIZER_BUSY / INSUFFICIENT_PERMISSIONS errors.
 * 3. Android 14-16+ Foreground Service microphone runtime permission safety.
 */
class JarvisWakeWordService : Service() {

    companion object {
        private const val TAG = "JarvisWakeWordService"
        private const val CHANNEL_ID = "jarvis_wake"
        private const val NOTIFICATION_ID = 42
        private const val RESTART_DELAY_AFTER_COMMAND_MS = 1_500L
        private const val RESTART_DELAY_AFTER_ERROR_MS   = 2_000L
        private const val MIN_BACKOFF_DELAY_MS          = 400L
        private const val ECHO_GUARD_DELAY_MS           = 300L
        private const val MAX_CONSECUTIVE_BUSY_ERRORS   = 2
        private const val MAX_CONSECUTIVE_TOTAL_ERRORS  = 5

        @Volatile private var running = false

        fun isRunning(): Boolean = running

        fun startService(context: Context) {
            val hasMicPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasMicPermission) {
                Log.w(TAG, "Cannot start JarvisWakeWordService: RECORD_AUDIO permission is not granted")
                return
            }

            try {
                val intent = Intent(context, JarvisWakeWordService::class.java)
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to startForegroundService JarvisWakeWordService", e)
            }
        }

        fun stopService(context: Context) {
            try {
                context.stopService(Intent(context, JarvisWakeWordService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stopService JarvisWakeWordService", e)
            }
        }
    }

    // ── State Machine ──────────────────────────────────────────────────────────
    enum class WakeState {
        IDLE,
        INITIALIZING,
        LISTENING,
        PROCESSING,
        SPEAKING,
        ERROR
    }

    private enum class ListenMode { WAKE_WORD, COMMAND }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var currentMode = ListenMode.WAKE_WORD
    private var currentState = WakeState.IDLE

    private var consecutiveBusyErrors = 0
    private var consecutiveTotalErrors = 0

    private val audioBufferLock = Any()
    private val recentAudioBuffer = java.io.ByteArrayOutputStream()

    private val speakingListener: (Boolean) -> Unit = { isSpeaking ->
        mainHandler.post {
            if (!running) return@post
            if (isSpeaking) {
                Log.d(TAG, "Half-duplex guard: Output actively speaking. Suspending microphone capture.")
                mainHandler.removeCallbacks(resumeListeningRunnable)
                transitionTo(WakeState.SPEAKING)
                stopRecognizer()
            } else {
                if (currentState == WakeState.SPEAKING) {
                    Log.d(TAG, "Half-duplex guard: Output finished. Resuming listening after ${ECHO_GUARD_DELAY_MS}ms.")
                    mainHandler.removeCallbacks(resumeListeningRunnable)
                    mainHandler.postDelayed(resumeListeningRunnable, ECHO_GUARD_DELAY_MS)
                }
            }
        }
    }

    private val resumeListeningRunnable: Runnable = Runnable {
        if (!running) return@Runnable
        if (com.jarvis.assistant.voice.JarvisVoiceController.isSpeaking()) {
            Log.d(TAG, "Speech output still active; postponing listening resumption.")
            mainHandler.postDelayed(resumeListeningRunnable, ECHO_GUARD_DELAY_MS)
            return@Runnable
        }
        consecutiveBusyErrors = 0
        startWakeWordListening()
    }

    private fun transitionTo(newState: WakeState) {
        if (currentState != newState) {
            currentState = newState
            Log.d(TAG, "WakeState -> $newState")
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        running = true
        transitionTo(WakeState.IDLE)

        val hasMicPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasMicPermission) {
            Log.e(TAG, "Cannot run JarvisWakeWordService: RECORD_AUDIO permission missing. Stopping service.")
            running = false
            stopSelf()
            return
        }

        createNotificationChannel()
        val notification = buildNotification()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Log.i(TAG, "Service started in foreground with microphone type")
            com.jarvis.assistant.voice.JarvisVoiceController.addSpeakingStateListener(speakingListener)
            mainHandler.post { startWakeWordListening() }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException during startForeground: ${e.message}", e)
            running = false
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Exception during startForeground: ${e.message}", e)
            running = false
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY

    override fun onDestroy() {
        running = false
        mainHandler.removeCallbacksAndMessages(null)
        try {
            com.jarvis.assistant.voice.JarvisVoiceController.removeSpeakingStateListener(speakingListener)
        } catch (_: Exception) {}
        stopRecognizer()
        transitionTo(WakeState.IDLE)
        Log.i(TAG, "Service stopped and recognizer released")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr?.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "JARVIS Wake Word",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Persistent listener for 'Hey Jarvis' wake phrase"
                    setShowBadge(false)
                }
                mgr?.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification() = run {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("JARVIS Listening")
            .setContentText("Say 'Hey Jarvis' to activate")
            .setSmallIcon(R.drawable.ic_jarvis_logo)
            .setOngoing(true)
            .setContentIntent(tapIntent)
            .setSilent(true)
            .build()
    }

    // ── SpeechRecognizer plumbing ─────────────────────────────────────────────

    private fun stopRecognizer() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null
    }

    /**
     * Build a fresh SpeechRecognizer and start listening.
     * [mode] controls how the results are interpreted.
     */
    private fun startListeningForMode(mode: ListenMode) {
        if (!running) return

        if (com.jarvis.assistant.voice.JarvisVoiceController.isSpeaking()) {
            Log.d(TAG, "[$mode] Speech playback is currently active; withholding mic acquisition.")
            transitionTo(WakeState.SPEAKING)
            mainHandler.removeCallbacks(resumeListeningRunnable)
            mainHandler.postDelayed(resumeListeningRunnable, ECHO_GUARD_DELAY_MS)
            return
        }

        currentMode = mode
        stopRecognizer()
        transitionTo(WakeState.INITIALIZING)

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.w(TAG, "Speech recognition not available on this device")
            scheduleRestart(RESTART_DELAY_AFTER_ERROR_MS) { startWakeWordListening() }
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).also { sr ->
            sr.setRecognitionListener(object : RecognitionListener {

                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d(TAG, "[$mode] Ready for speech")
                    consecutiveBusyErrors = 0
                    consecutiveTotalErrors = 0
                    transitionTo(WakeState.LISTENING)
                }

                override fun onBeginningOfSpeech() {
                    transitionTo(WakeState.PROCESSING)
                }

                override fun onRmsChanged(rmsdB: Float) {}

                override fun onBufferReceived(buffer: ByteArray?) {
                    if (buffer != null && buffer.isNotEmpty()) {
                        synchronized(audioBufferLock) {
                            if (recentAudioBuffer.size() > 96000) {
                                recentAudioBuffer.reset()
                            }
                            recentAudioBuffer.write(buffer)
                        }
                    }
                }

                override fun onEndOfSpeech() {
                    transitionTo(WakeState.PROCESSING)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull() ?: return

                    // Full-duplex barge-in: If output is currently playing/speaking and user speaks wake or interrupt phrase
                    if (com.jarvis.assistant.voice.JarvisVoiceController.isSpeaking()) {
                        if (VoiceIntentGate.hasWakePrefix(partial) || com.jarvis.assistant.voice.JarvisVoiceController.shouldInterruptForPhrase(partial)) {
                            Log.i(TAG, "Barge-in interrupted output on partial: '$partial'")
                            com.jarvis.assistant.voice.JarvisVoiceController.interruptIfSpeaking("Wake word spoken during output")
                        }
                    }

                    if (currentMode == ListenMode.WAKE_WORD && VoiceIntentGate.hasWakePrefix(partial)) {
                        Log.i(TAG, "Wake phrase detected (partial): '$partial'")
                        onWakePhraseDetected()
                    }
                }

                override fun onResults(results: Bundle?) {
                    val texts = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?: emptyList<String>()
                    val best = texts.firstOrNull().orEmpty().trim()

                    // Full-duplex barge-in: If output is currently playing/speaking and user speaks wake or interrupt phrase
                    if (com.jarvis.assistant.voice.JarvisVoiceController.isSpeaking()) {
                        if (VoiceIntentGate.hasWakePrefix(best) || com.jarvis.assistant.voice.JarvisVoiceController.shouldInterruptForPhrase(best)) {
                            Log.i(TAG, "Barge-in interrupted output on final: '$best'")
                            com.jarvis.assistant.voice.JarvisVoiceController.interruptIfSpeaking("Wake word spoken during output")
                        }
                    }

                    when (currentMode) {
                        ListenMode.WAKE_WORD -> {
                            if (VoiceIntentGate.hasWakePrefix(best)) {
                                Log.i(TAG, "Wake phrase confirmed (final): '$best'")
                                onWakePhraseDetected()
                            } else {
                                // Not a wake phrase — keep listening with safe backoff delay
                                scheduleRestart(MIN_BACKOFF_DELAY_MS) { startWakeWordListening() }
                            }
                        }
                        ListenMode.COMMAND -> {
                            if (best.isNotBlank()) {
                                Log.i(TAG, "Command received: '$best'")
                                onCommandReceived(best)
                            } else {
                                Log.d(TAG, "Empty command — restarting wake word listening")
                                scheduleRestart(RESTART_DELAY_AFTER_COMMAND_MS) { startWakeWordListening() }
                            }
                        }
                    }
                }

                override fun onError(error: Int) {
                    val errorName = speechErrorName(error)
                    Log.w(TAG, "[$mode] SpeechRecognizer error: $error ($errorName)")

                    consecutiveTotalErrors++
                    if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                        error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                    ) {
                        consecutiveBusyErrors++
                    }

                    if (consecutiveBusyErrors >= MAX_CONSECUTIVE_BUSY_ERRORS) {
                        Log.e(TAG, "Halting wake word listener after $consecutiveBusyErrors consecutive busy/permission errors to prevent infinite mic loop.")
                        stopRecognizer()
                        transitionTo(WakeState.ERROR)
                        return
                    }

                    if (consecutiveTotalErrors >= MAX_CONSECUTIVE_TOTAL_ERRORS) {
                        Log.e(TAG, "Halting wake word listener after $consecutiveTotalErrors consecutive errors to protect mic hardware.")
                        stopRecognizer()
                        transitionTo(WakeState.ERROR)
                        return
                    }

                    val delay = maxOf(MIN_BACKOFF_DELAY_MS, RESTART_DELAY_AFTER_ERROR_MS)
                    scheduleRestart(delay) { startWakeWordListening() }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            if (mode == ListenMode.WAKE_WORD) {
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3_000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3_000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1_000L)
            }
        }

        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "startListening threw: ${e.message}")
            scheduleRestart(RESTART_DELAY_AFTER_ERROR_MS) { startWakeWordListening() }
        }
    }

    private fun startWakeWordListening() {
        Log.d(TAG, "Starting wake-word listening")
        synchronized(audioBufferLock) {
            recentAudioBuffer.reset()
        }
        startListeningForMode(ListenMode.WAKE_WORD)
    }

    private fun startCommandListening() {
        Log.d(TAG, "Starting command listening")
        startListeningForMode(ListenMode.COMMAND)
    }

    // ── Wake + Command logic ───────────────────────────────────────────────────

    private fun onWakePhraseDetected() {
        if (com.jarvis.assistant.voice.JarvisVoiceController.isSpeaking()) {
            com.jarvis.assistant.voice.JarvisVoiceController.interruptIfSpeaking("Wake word spoken during output")
        }

        val prefs = com.jarvis.assistant.JarvisApp.instance.preferences
        if (prefs.isOwnerVoiceEnabled && prefs.hasOwnerVoiceProfile()) {
            val audioBytes = synchronized(audioBufferLock) { recentAudioBuffer.toByteArray() }
            val match = com.jarvis.assistant.audio.OwnerVoiceManager.verifyOwnerForWakeWord(audioBytes, prefs)
            if (!match.isMatch) {
                Log.w(TAG, "Wake word rejected: stranger voice")
                scheduleRestart(MIN_BACKOFF_DELAY_MS) { startWakeWordListening() }
                return
            }
            Log.i(TAG, "Wake word accepted: owner verified (dist=${match.distance}, sim=${match.similarityPercent}%)")
        }

        vibrate(50)

        // Start the conversation service if it isn't running
        if (!JarvisConversationService.isServiceRunning()) {
            JarvisConversationService.startTextChatSession(this)
        }

        // Immediately switch to command-listening mode
        startCommandListening()
    }

    private fun onCommandReceived(command: String) {
        if (com.jarvis.assistant.voice.JarvisVoiceController.shouldInterruptForPhrase(command)) {
            com.jarvis.assistant.voice.JarvisVoiceController.interruptIfSpeaking("Stop phrase received in command: $command")
        }

        // 1. Check Autonomous Project Creation (Websites, Games, Apps)
        if (AutonomousProjectOrchestrator.isProjectWorkflowActive() ||
            com.jarvis.assistant.util.MultilingualCommandHelper.isProjectCreationRequest(command)) {
            val handled = AutonomousProjectOrchestrator.handleVoiceTurn(this, command)
            if (handled) {
                scheduleRestart(RESTART_DELAY_AFTER_COMMAND_MS) { startWakeWordListening() }
                return
            }
        }

        val appMod = com.jarvis.assistant.selfaware.AppModificationEngine.processCommand(this, command)
        if (appMod is com.jarvis.assistant.selfaware.AppModificationEngine.ModificationResult.Success) {
            com.jarvis.assistant.voice.JarvisVoiceController.speakTurnResponse(
                context = this,
                turnId = "self_mod_${System.currentTimeMillis()}",
                userPrompt = command,
                fullResponse = appMod.message
            )
            scheduleRestart(RESTART_DELAY_AFTER_COMMAND_MS) { startWakeWordListening() }
            return
        }

        if (tryHandleVoiceReply(command)) {
            scheduleRestart(RESTART_DELAY_AFTER_COMMAND_MS) { startWakeWordListening() }
            return
        }

        if (tryHandleHardwareTelemetry(command)) {
            scheduleRestart(RESTART_DELAY_AFTER_COMMAND_MS) { startWakeWordListening() }
            return
        }

        if (AutonomousBackgroundExecutor.isActionableTask(command)) {
            AutonomousBackgroundExecutor.execute(this, command) { _, _ ->
                scheduleRestart(RESTART_DELAY_AFTER_COMMAND_MS) { startWakeWordListening() }
            }
            return
        }

        // Conversational voice command: activate live voice session instead of dumping into text ChatBox
        val service = JarvisConversationService.activeInstance
        if (service != null) {
            service.beginActiveConversation(wakeTriggered = true)
        } else {
            JarvisConversationService.startVoiceService(this)
        }
        scheduleRestart(RESTART_DELAY_AFTER_COMMAND_MS) { startWakeWordListening() }
    }

    private fun tryHandleVoiceReply(command: String): Boolean {
        val hasPending = com.jarvis.assistant.notifications.JarvisNotificationListener.hasPendingReply()
        if (!hasPending) return false

        val (isReplyIntent, replyText) = com.jarvis.assistant.util.MultilingualCommandHelper.parseVoiceReply(command)
        if (!isReplyIntent || replyText.isBlank()) return false

        val lang = com.jarvis.assistant.util.MultilingualCommandHelper.resolveLanguage(this, command)
        val msgSettings = com.jarvis.assistant.notifications.MessageAlertSettings(this)
        val prefs = com.jarvis.assistant.JarvisApp.instance.preferences
        if (msgSettings.ownerVoiceReplyGated && prefs.isOwnerVoiceEnabled && prefs.hasOwnerVoiceProfile()) {
            val audioBytes = synchronized(audioBufferLock) { recentAudioBuffer.toByteArray() }
            val match = com.jarvis.assistant.audio.OwnerVoiceManager.verifySpeakerAudio(audioBytes, prefs)
            if (!match.isMatch) {
                Log.w(TAG, "Voice reply rejected: unauthorized speaker")
                val errMsg = com.jarvis.assistant.util.MultilingualCommandHelper.getLocalizedVoiceAuthRequired(lang)
                com.jarvis.assistant.JarvisApp.instance.chatRepository.addTurn(command, errMsg)
                com.jarvis.assistant.voice.JarvisVoiceController.speakTurnResponse(
                    context = this,
                    turnId = "reply_err_${System.currentTimeMillis()}",
                    userPrompt = command,
                    fullResponse = errMsg
                )
                return true
            }
        }

        val sender = com.jarvis.assistant.notifications.JarvisNotificationListener.getPendingSender() ?: "sender"
        val success = com.jarvis.assistant.notifications.JarvisNotificationListener.sendVoiceReply(this, replyText)
        val response = com.jarvis.assistant.util.MultilingualCommandHelper.getLocalizedVoiceReplyConfirm(sender, replyText, success, lang)

        com.jarvis.assistant.voice.JarvisVoiceController.speakTurnResponse(
            context = this,
            turnId = "reply_sent_${System.currentTimeMillis()}",
            userPrompt = command,
            fullResponse = response
        )
        return true
    }

    private fun tryHandleHardwareTelemetry(command: String): Boolean {
        val helper = com.jarvis.assistant.util.MultilingualCommandHelper
        val isSystem = helper.isSystemReport(command)
        val isBattery = helper.isBatteryQuery(command)
        val isMemory = helper.isMemoryQuery(command)
        val isCpu = helper.isCpuQuery(command)

        if (!isSystem && !isBattery && !isMemory && !isCpu) return false

        val lang = helper.resolveLanguage(this, command)
        val snap = com.jarvis.assistant.telemetry.HardwareTelemetryEngine.captureSnapshot(this)

        val response = when {
            isBattery -> com.jarvis.assistant.telemetry.HardwareTelemetryEngine.getBatterySpeechReport(snap, lang)
            isMemory -> com.jarvis.assistant.telemetry.HardwareTelemetryEngine.getMemorySpeechReport(snap, lang)
            isCpu -> com.jarvis.assistant.telemetry.HardwareTelemetryEngine.getCpuSpeechReport(snap, lang)
            else -> com.jarvis.assistant.telemetry.HardwareTelemetryEngine.generateIronManSpeechReport(snap, lang)
        }

        com.jarvis.assistant.voice.JarvisVoiceController.speakTurnResponse(
            context = this,
            turnId = "telemetry_${System.currentTimeMillis()}",
            userPrompt = command,
            fullResponse = response
        )
        return true
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun scheduleRestart(delayMs: Long, action: () -> Unit) {
        stopRecognizer()
        val effectiveDelay = maxOf(delayMs, MIN_BACKOFF_DELAY_MS)
        mainHandler.postDelayed(action, effectiveDelay)
    }

    @Suppress("DEPRECATION")
    private fun vibrate(durationMs: Long) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                v?.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                v?.vibrate(durationMs)
            }
        } catch (_: Exception) {}
    }

    private fun speechErrorName(error: Int) = when (error) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT       -> "NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NETWORK               -> "NETWORK"
        SpeechRecognizer.ERROR_AUDIO                 -> "AUDIO"
        SpeechRecognizer.ERROR_SERVER                -> "SERVER"
        SpeechRecognizer.ERROR_CLIENT                -> "CLIENT"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT        -> "SPEECH_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH              -> "NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY       -> "RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS"
        else -> "UNKNOWN($error)"
    }
}
