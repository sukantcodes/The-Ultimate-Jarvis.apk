package com.jarvis.assistant.clock

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.jarvis.assistant.R

/**
 * The thing that actually rings. It is a foreground service, so an alarm keeps sounding with JARVIS closed, in the
 * background, or after the app process was killed and started again by the alarm. No Activity has to stay open: the ring
 * screen only shows what this service is doing and sends it Stop / Snooze.
 *
 * Several alarms: if a second alarm arrives while one rings, the first is put aside as a snooze (so it comes back) and the
 * new one rings. Only one alarm sounds at a time, and a repeated delivery of the same alarm is ignored.
 */
class AlarmRingService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val sound by lazy { AlarmSoundPlayer(this) }
    private var wakeLock: PowerManager.WakeLock? = null
    private var focusRequest: AudioFocusRequest? = null
    private var currentId = -1L
    private var volume = 1f

    private val ramp = object : Runnable {
        override fun run() {
            volume = (volume + 0.05f).coerceAtMost(1f)
            sound.setVolume(volume)
            if (volume < 1f && currentId >= 0L) handler.postDelayed(this, 1_000L)
        }
    }
    private val timeout = Runnable { onSilenceTimeout() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val engine = ClockServices.engine(this)
        val id = intent?.getLongExtra(AlarmScheduler.EXTRA_ID, -1L) ?: -1L
        try {
            when (intent?.action) {
                ACTION_STOP -> { keepForeground(engine); stopRinging(engine, id) }
                ACTION_SNOOZE -> { keepForeground(engine); snoozeRinging(engine, id) }
                else -> startRinging(engine, id)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ring service failed: ${e.message}", e)
            if (currentId < 0L) finishService()
        }
        return START_REDELIVER_INTENT
    }

    // ---- Start ----------------------------------------------------------------------------------

    private fun startRinging(engine: ClockEngine, id: Long) {
        val alarm = engine.alarm(id)
        if (alarm == null) {
            // Android requires a notification after startForegroundService, even if there is nothing to ring.
            if (currentId < 0L) { enterForeground(AlarmNotifications.idle(this)); finishService() }
            return
        }
        if (currentId == id) return // the same alarm delivered twice

        val settings = engine.settings()
        enterForeground(AlarmNotifications.ringing(this, alarm, settings))

        if (currentId >= 0L) {
            // Another alarm is already ringing: put it aside as a snooze so it is not lost, then ring this one.
            val old = currentId
            silence()
            engine.snooze(old, ignoreLimit = true)
        }

        currentId = id
        RingState.set(id)
        acquireWakeLock(settings)
        playSound(alarm, settings)
        if (alarm.vibrate || sound.playingUri == null) vibrate()
        if (settings.silenceAfterMinutes > 0) handler.postDelayed(timeout, settings.silenceAfterMinutes * 60_000L)
    }

    private fun playSound(alarm: AlarmItem, settings: ClockSettings) {
        if (alarm.toneMode == ToneMode.SILENT) return
        requestAudioFocus()
        volume = if (settings.gradualVolume) 0.15f else 1f
        val candidates = ToneCatalog.playbackCandidates(this, alarm.toneMode, alarm.toneUri)
        val started = sound.start(candidates, loop = true, volume = volume)
        if (!started) {
            Log.w(TAG, "No alarm sound could be played for alarm ${alarm.id}; vibrating instead.")
            RingState.set(alarm.id, getString(R.string.clock_tone_could_not_play))
        } else if (settings.gradualVolume) {
            handler.postDelayed(ramp, 1_000L)
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        try {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator == null || !vibrator.hasVibrator()) return
            val effect = VibrationEffect.createWaveform(longArrayOf(0L, 700L, 500L), 0)
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            vibrator.vibrate(effect, attrs)
        } catch (e: Exception) {
            Log.w(TAG, "Vibration failed: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun cancelVibration() {
        try {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            vibrator?.cancel()
        } catch (_: Exception) {}
    }

    private fun requestAudioFocus() {
        try {
            val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .build()
            audio.requestAudioFocus(request)
            focusRequest = request
        } catch (_: Exception) {}
    }

    private fun abandonAudioFocus() {
        try {
            val request = focusRequest ?: return
            (getSystemService(Context.AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(request)
        } catch (_: Exception) {}
        focusRequest = null
    }

    private fun acquireWakeLock(settings: ClockSettings) {
        try {
            if (wakeLock?.isHeld == true) return
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            val minutes = if (settings.silenceAfterMinutes > 0) settings.silenceAfterMinutes else MAX_RING_MINUTES
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:alarm-ring").apply {
                acquire((minutes + 1) * 60_000L)
            }
        } catch (_: Exception) {}
    }

    // ---- Stop, snooze, timeout ------------------------------------------------------------------

    private fun stopRinging(engine: ClockEngine, id: Long) {
        // A button on an old notification must not stop a different alarm that is ringing now.
        if (currentId >= 0L && id >= 0L && id != currentId) return
        val target = if (id >= 0L) id else currentId
        silence()
        if (target >= 0L) engine.dismiss(target)
        finishService()
    }

    private fun snoozeRinging(engine: ClockEngine, id: Long) {
        if (currentId >= 0L && id >= 0L && id != currentId) return
        val target = if (id >= 0L) id else currentId
        if (target < 0L) { finishService(); return }
        when (val result = engine.snooze(target)) {
            is ClockResult.Ok -> {
                val at = result.nextRingMillis
                silence()
                if (at != null) {
                    val zdt = java.time.ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(at), java.time.ZoneId.systemDefault())
                    val text = getString(R.string.clock_snoozed_until, ClockFormat.time(this, zdt.hour, zdt.minute, engine.settings()))
                    Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()
                }
                finishService()
            }
            is ClockResult.Failed -> {
                // Keep ringing and tell the ring screen why (for example the snooze limit was reached).
                RingState.set(currentId, ClockMessages.forFailure(this, result))
            }
        }
    }

    private fun onSilenceTimeout() {
        val id = currentId
        if (id < 0L) return
        val engine = ClockServices.engine(this)
        val alarm = engine.alarm(id)
        silence()
        engine.dismiss(id)
        if (alarm != null) AlarmNotifications.postMissed(this, alarm, engine.settings())
        finishService()
    }

    /** Stops the sound, vibration and timers. Does not end the service. */
    private fun silence() {
        handler.removeCallbacks(ramp)
        handler.removeCallbacks(timeout)
        sound.stop()
        cancelVibration()
        abandonAudioFocus()
        currentId = -1L
    }

    private fun finishService() {
        silence()
        RingState.clear()
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---- Foreground helpers ---------------------------------------------------------------------

    private fun keepForeground(engine: ClockEngine) {
        val alarm = if (currentId >= 0L) engine.alarm(currentId) else null
        enterForeground(if (alarm != null) AlarmNotifications.ringing(this, alarm, engine.settings()) else AlarmNotifications.idle(this))
    }

    private fun enterForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(AlarmNotifications.ID_RING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(AlarmNotifications.ID_RING, notification)
        }
    }

    override fun onDestroy() {
        silence()
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        RingState.clear()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AlarmRingService"
        const val ACTION_RING = "com.jarvis.assistant.clock.RING"
        const val ACTION_STOP = "com.jarvis.assistant.clock.STOP"
        const val ACTION_SNOOZE = "com.jarvis.assistant.clock.SNOOZE"
        private const val MAX_RING_MINUTES = 30

        fun start(context: Context, alarmId: Long) {
            val intent = Intent(context, AlarmRingService::class.java)
                .setAction(ACTION_RING)
                .putExtra(AlarmScheduler.EXTRA_ID, alarmId)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Sends Stop or Snooze for whatever is ringing. Works from the ring screen, the AI and the notification. */
        fun send(context: Context, action: String, alarmId: Long = -1L) {
            val intent = Intent(context, AlarmRingService::class.java)
                .setAction(action)
                .putExtra(AlarmScheduler.EXTRA_ID, alarmId)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
