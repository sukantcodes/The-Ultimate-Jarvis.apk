package com.jarvis.assistant.background

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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
import com.jarvis.assistant.R
import com.jarvis.assistant.audio.VoiceIntentGate
import com.jarvis.assistant.ui.home.MainActivity

/**
 * Always-on foreground service that listens for "Hey Jarvis" wake phrases using
 * Android's SpeechRecognizer in PARTIAL-result mode.
 *
 * State machine:
 *   IDLE → wake phrase detected → COMMAND_LISTEN → command received →
 *   JarvisConversationService.sendTextMessage() → (1 500 ms delay) → IDLE
 *
 * On any error, restart after 2 000 ms.
 */
class JarvisWakeWordService : Service() {

    companion object {
        private const val TAG = "JarvisWakeWordService"
        private const val CHANNEL_ID = "jarvis_wake"
        private const val NOTIFICATION_ID = 42
        private const val RESTART_DELAY_AFTER_COMMAND_MS = 1_500L
        private const val RESTART_DELAY_AFTER_ERROR_MS   = 2_000L

        @Volatile private var running = false

        fun isRunning(): Boolean = running

        fun startService(context: Context) {
            val intent = Intent(context, JarvisWakeWordService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stopService(context: Context) {
            context.stopService(Intent(context, JarvisWakeWordService::class.java))
        }
    }

    // ── State ─────────────────────────────────────────────────────────────────
    private enum class ListenMode { WAKE_WORD, COMMAND }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var currentMode = ListenMode.WAKE_WORD
    private val audioBufferLock = Any()
    private val recentAudioBuffer = java.io.ByteArrayOutputStream()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        running = true
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } catch (e: Exception) {
                Log.w(TAG, "startForeground with MICROPHONE type failed: ${e.message}")
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        Log.i(TAG, "Service started")
        mainHandler.post { startWakeWordListening() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY

    override fun onDestroy() {
        running = false
        stopRecognizer()
        Log.i(TAG, "Service stopped")
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
        currentMode = mode
        stopRecognizer()

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.w(TAG, "Speech recognition not available on this device")
            scheduleRestart(RESTART_DELAY_AFTER_ERROR_MS) { startWakeWordListening() }
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).also { sr ->
            sr.setRecognitionListener(object : RecognitionListener {

                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d(TAG, "[$mode] Ready for speech")
                }

                override fun onBeginningOfSpeech() {}
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
                override fun onEndOfSpeech() {}

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
                                // Not a wake phrase — keep listening
                                scheduleRestart(0) { startWakeWordListening() }
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

                    // ERROR_NO_MATCH / ERROR_SPEECH_TIMEOUT are benign — just restart
                    scheduleRestart(RESTART_DELAY_AFTER_ERROR_MS) { startWakeWordListening() }
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
                // Keep listening for a long time in wake-word mode
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
                // Do NOT wake up, do NOT activate command mode. Restart wake listening immediately.
                scheduleRestart(0) { startWakeWordListening() }
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
        val appMod = com.jarvis.assistant.selfaware.AppModificationEngine.processCommand(this, command)
        if (appMod is com.jarvis.assistant.selfaware.AppModificationEngine.ModificationResult.Success) {
            com.jarvis.assistant.JarvisApp.instance.chatRepository.addTurn(command, appMod.message)
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
            com.jarvis.assistant.JarvisApp.instance.chatRepository.addTurn(command, "Starting task in background…")
            AutonomousBackgroundExecutor.execute(this, command) { _, _ ->
                scheduleRestart(RESTART_DELAY_AFTER_COMMAND_MS) { startWakeWordListening() }
            }
            return
        }

        JarvisConversationService.sendTextMessage(command)
        scheduleRestart(RESTART_DELAY_AFTER_COMMAND_MS) { startWakeWordListening() }
    }

    private fun tryHandleVoiceReply(command: String): Boolean {
        val hasPending = com.jarvis.assistant.notifications.JarvisNotificationListener.hasPendingReply()
        val lower = command.lowercase(java.util.Locale.ROOT).trim()
        val isReplyIntent = lower.startsWith("reply") || lower.startsWith("send reply") || lower.startsWith("tell ")
        if (!hasPending || !isReplyIntent) return false

        val replyText = when {
            lower.startsWith("send reply") -> command.substring(10).trim().removePrefix(":").trim()
            lower.startsWith("reply") -> command.substring(5).trim().removePrefix(":").trim()
            lower.startsWith("tell ") -> {
                val afterTell = command.substring(5).trim()
                val parts = afterTell.split(" ", limit = 2)
                if (parts.size > 1) parts[1].trim() else afterTell
            }
            else -> command.trim()
        }

        if (replyText.isBlank()) return false

        val msgSettings = com.jarvis.assistant.notifications.MessageAlertSettings(this)
        val prefs = com.jarvis.assistant.JarvisApp.instance.preferences
        if (msgSettings.ownerVoiceReplyGated && prefs.isOwnerVoiceEnabled && prefs.hasOwnerVoiceProfile()) {
            val audioBytes = synchronized(audioBufferLock) { recentAudioBuffer.toByteArray() }
            val match = com.jarvis.assistant.audio.OwnerVoiceManager.verifySpeakerAudio(audioBytes, prefs)
            if (!match.isMatch) {
                Log.w(TAG, "Voice reply rejected: unauthorized speaker")
                val errMsg = "Voice verification required for message dispatch, Sir."
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
        val response = if (success) {
            "Reply dispatched to $sender: \"$replyText\", Sir."
        } else {
            "Could not dispatch reply to $sender. The notification session may have expired."
        }

        com.jarvis.assistant.JarvisApp.instance.chatRepository.addTurn(command, response)
        com.jarvis.assistant.voice.JarvisVoiceController.speakTurnResponse(
            context = this,
            turnId = "reply_sent_${System.currentTimeMillis()}",
            userPrompt = command,
            fullResponse = response
        )
        return true
    }

    private fun tryHandleHardwareTelemetry(command: String): Boolean {
        val lower = command.lowercase(java.util.Locale.ROOT).trim()
        val isTelemetryQuery = lower.contains("system report") ||
                lower.contains("system status") ||
                lower.contains("hardware report") ||
                lower.contains("hardware status") ||
                lower.contains("hardware telemetry") ||
                lower.contains("diagnostics report") ||
                lower.contains("battery status") ||
                lower.contains("battery temp") ||
                lower.contains("battery temperature") ||
                lower.contains("phone temperature") ||
                lower.contains("thermal status") ||
                lower.contains("ram status") ||
                lower.contains("ram usage") ||
                lower.contains("memory status") ||
                lower.contains("memory usage") ||
                lower.contains("cpu status") ||
                lower.contains("cpu load") ||
                lower.contains("cpu usage")

        if (!isTelemetryQuery) return false

        val response = when {
            lower.contains("battery") || lower.contains("temp") || lower.contains("thermal") -> {
                val snap = com.jarvis.assistant.telemetry.HardwareTelemetryEngine.captureSnapshot(this)
                val temp = String.format(java.util.Locale.US, "%.1f", snap.battery.temperatureCelsius)
                "Battery is at ${snap.battery.levelPercent}%, currently ${if (snap.battery.isCharging) snap.battery.chargeType else "on battery power"} at $temp degrees Celsius. Thermal levels are ${snap.battery.thermalStatus.name.lowercase(java.util.Locale.ROOT)}."
            }
            lower.contains("ram") || lower.contains("memory") -> {
                val snap = com.jarvis.assistant.telemetry.HardwareTelemetryEngine.captureSnapshot(this)
                val freeGb = String.format(java.util.Locale.US, "%.1f", snap.memory.availableBytes / (1024.0 * 1024.0 * 1024.0))
                val usedPct = (snap.memory.usedFraction * 100).toInt()
                "Memory utilization is at $usedPct% with $freeGb gigabytes available, Sir."
            }
            lower.contains("cpu") -> {
                val snap = com.jarvis.assistant.telemetry.HardwareTelemetryEngine.captureSnapshot(this)
                "CPU load is estimated at ${snap.cpu.loadPercent}% across ${snap.cpu.coreCount} cores, Sir."
            }
            else -> {
                com.jarvis.assistant.telemetry.HardwareTelemetryEngine.generateIronManSpeechReport(this)
            }
        }

        com.jarvis.assistant.JarvisApp.instance.chatRepository.addTurn(command, response)
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
        if (delayMs > 0) {
            mainHandler.postDelayed(action, delayMs)
        } else {
            mainHandler.post(action)
        }
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
