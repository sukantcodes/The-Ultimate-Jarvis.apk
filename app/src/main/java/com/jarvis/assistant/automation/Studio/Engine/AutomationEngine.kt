package com.jarvis.assistant.automation.studio.engine

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.automation.phone.PhoneControlManager
import com.jarvis.assistant.automation.studio.model.*
import com.jarvis.assistant.automation.studio.receiver.AutomationAlarmReceiver
import com.jarvis.assistant.automation.studio.repository.AutomationRuleRepository
import com.jarvis.assistant.util.AppLauncher
import com.jarvis.assistant.util.SpeechAnnouncer
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class AutomationEngine(private val context: Context) {

    companion object {
        private const val TAG = "AutomationEngine"
        const val CHANNEL_AUTOMATION = "jarvis_automation_channel"
        const val EXTRA_RULE_ID = "extra_automation_rule_id"
        const val EXTRA_TRIGGER_SOURCE = "extra_trigger_source"
        const val ACTION_EXECUTE_RULE = "com.jarvis.assistant.automation.EXECUTE_RULE"

        @Volatile
        private var instance: AutomationEngine? = null

        fun getInstance(context: Context): AutomationEngine {
            return instance ?: synchronized(this) {
                instance ?: AutomationEngine(context.applicationContext).also { instance = it }
            }
        }
    }

    private val appContext = context.applicationContext
    private val repository = AutomationRuleRepository.getInstance(appContext)
    private val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
    private val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    private val speechAnnouncer = SpeechAnnouncer(appContext)
    private val phoneControlManager = PhoneControlManager(appContext)
    private val lastExecutionMap = ConcurrentHashMap<String, Long>()
    private val notificationIdCounter = AtomicInteger(5000)
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_AUTOMATION,
                "JARVIS Automation Studio",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Executes automated system alerts, scheduled tasks, and reminder triggers."
                enableLights(true)
                lightColor = android.graphics.Color.CYAN
                enableVibration(true)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    // ==========================================
    // SCHEDULE MANAGEMENT
    // ==========================================

    fun scheduleRule(rule: AutomationRule) {
        if (!rule.isEnabled || rule.trigger.type != TriggerType.SCHEDULED) {
            cancelSchedule(rule.id)
            return
        }

        val alarm = alarmManager ?: run {
            Log.w(TAG, "AlarmManager not available on this device.")
            return
        }

        val triggerTimeMillis = calculateNextTriggerMillis(rule.trigger)
        if (triggerTimeMillis <= System.currentTimeMillis()) {
            Log.w(TAG, "Calculated trigger time is in the past for rule: ${rule.title}")
            return
        }

        val pendingIntent = createAlarmPendingIntent(rule.id) ?: run {
            Log.e(TAG, "Failed to create PendingIntent for rule: ${rule.id}")
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarm.canScheduleExactAlarms()) {
                    alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTimeMillis, pendingIntent)
                    Log.i(TAG, "Exact alarm scheduled for rule '${rule.title}' at timestamp: $triggerTimeMillis")
                } else {
                    alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTimeMillis, pendingIntent)
                    Log.w(TAG, "Inexact alarm scheduled (SCHEDULE_EXACT_ALARM permission not granted) for: ${rule.title}")
                }
            } else {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTimeMillis, pendingIntent)
                Log.i(TAG, "Exact alarm scheduled for rule '${rule.title}' at: $triggerTimeMillis")
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException scheduling alarm for ${rule.title}: ${e.message}, falling back to setAndAllowWhileIdle")
            try {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTimeMillis, pendingIntent)
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback alarm scheduling also failed: ${e2.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling alarm for rule '${rule.title}'", e)
        }
    }

    fun cancelSchedule(ruleId: String) {
        val alarm = alarmManager ?: return
        val pendingIntent = PendingIntent.getBroadcast(
            appContext,
            ruleId.hashCode(),
            Intent(appContext, AutomationAlarmReceiver::class.java).apply {
                action = ACTION_EXECUTE_RULE
                putExtra(EXTRA_RULE_ID, ruleId)
            },
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarm.cancel(pendingIntent)
            pendingIntent.cancel()
            Log.d(TAG, "Cancelled alarm schedule for rule: $ruleId")
        }
    }

    fun rescheduleAllScheduledRules() {
        val rules = repository.getAllRules().filter { it.isEnabled && it.trigger.type == TriggerType.SCHEDULED }
        Log.i(TAG, "Rescheduling ${rules.size} scheduled automation rules...")
        for (rule in rules) {
            scheduleRule(rule)
        }
    }

    private fun createAlarmPendingIntent(ruleId: String): PendingIntent? {
        val intent = Intent(appContext, AutomationAlarmReceiver::class.java).apply {
            action = ACTION_EXECUTE_RULE
            putExtra(EXTRA_RULE_ID, ruleId)
            putExtra(EXTRA_TRIGGER_SOURCE, "SCHEDULE")
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(appContext, ruleId.hashCode(), intent, flags)
    }

    fun calculateNextTriggerMillis(trigger: AutomationTrigger): Long {
        val now = Calendar.getInstance()

        return when (trigger.scheduleType) {
            ScheduleType.ONE_TIME -> {
                if (trigger.oneTimeEpochMillis > now.timeInMillis) {
                    trigger.oneTimeEpochMillis
                } else {
                    // Scheduled hour:minute today or tomorrow
                    val target = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, trigger.scheduledHour)
                        set(Calendar.MINUTE, trigger.scheduledMinute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    if (target.timeInMillis <= now.timeInMillis) {
                        target.add(Calendar.DAY_OF_YEAR, 1)
                    }
                    target.timeInMillis
                }
            }

            ScheduleType.DAILY -> {
                val target = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, trigger.scheduledHour)
                    set(Calendar.MINUTE, trigger.scheduledMinute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                if (target.timeInMillis <= now.timeInMillis) {
                    target.add(Calendar.DAY_OF_YEAR, 1)
                }
                target.timeInMillis
            }

            ScheduleType.WEEKDAYS -> {
                val days = if (trigger.scheduledDaysOfWeek.isEmpty()) {
                    listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY)
                } else {
                    trigger.scheduledDaysOfWeek
                }

                var closestMillis = Long.MAX_VALUE

                for (dayOfWeek in days) {
                    val candidate = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, trigger.scheduledHour)
                        set(Calendar.MINUTE, trigger.scheduledMinute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    val currentDay = candidate.get(Calendar.DAY_OF_WEEK)
                    var daysUntil = (dayOfWeek - currentDay + 7) % 7
                    if (daysUntil == 0 && candidate.timeInMillis <= now.timeInMillis) {
                        daysUntil = 7
                    }
                    candidate.add(Calendar.DAY_OF_YEAR, daysUntil)
                    if (candidate.timeInMillis > now.timeInMillis && candidate.timeInMillis < closestMillis) {
                        closestMillis = candidate.timeInMillis
                    }
                }

                if (closestMillis == Long.MAX_VALUE) {
                    // Fallback to daily calculation
                    val fallback = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, trigger.scheduledHour)
                        set(Calendar.MINUTE, trigger.scheduledMinute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                        add(Calendar.DAY_OF_YEAR, 1)
                    }
                    fallback.timeInMillis
                } else {
                    closestMillis
                }
            }
        }
    }

    // ==========================================
    // EXECUTION ENGINE
    // ==========================================

    fun executeRule(ruleId: String, triggerSource: String = "Automated") {
        val rule = repository.getRuleById(ruleId) ?: run {
            Log.w(TAG, "Rule not found for execution: $ruleId")
            return
        }

        if (!rule.isEnabled) {
            Log.d(TAG, "Rule '$ruleId' is disabled. Skipping execution.")
            return
        }

        // De-duplication check: prevent multiple triggers of the same rule within 4 seconds
        val lastExecuted = lastExecutionMap[ruleId] ?: 0L
        val now = System.currentTimeMillis()
        if (now - lastExecuted < 4000L) {
            Log.w(TAG, "Debounce: Rule '${rule.title}' triggered too quickly (${now - lastExecuted}ms ago). Skipping.")
            return
        }
        lastExecutionMap[ruleId] = now

        Log.i(TAG, "Executing Automation Rule: '${rule.title}' via source: $triggerSource with ${rule.actions.size} actions")

        // Execute actions sequentially on main thread
        mainHandler.post {
            for (action in rule.actions) {
                try {
                    executeSingleAction(action, rule.title)
                } catch (e: Exception) {
                    Log.e(TAG, "Error executing action in rule '${rule.title}': ${e.message}", e)
                }
            }
        }

        // Record execution in repository
        repository.recordExecution(rule.id, now)

        // If this was a recurring scheduled rule, schedule the next occurrence
        if (rule.trigger.type == TriggerType.SCHEDULED && rule.trigger.scheduleType != ScheduleType.ONE_TIME) {
            scheduleRule(rule)
        }
    }

    private fun executeSingleAction(action: AutomationAction, ruleTitle: String) {
        when (action.type) {
            ActionType.SPEAK_TTS -> {
                val message = action.ttsMessage.ifBlank { "Executing automation: $ruleTitle" }
                Log.d(TAG, "Speaking TTS: $message")
                speechAnnouncer.say(message)
            }

            ActionType.SHOW_NOTIFICATION -> {
                val title = action.notificationTitle.ifBlank { "JARVIS • $ruleTitle" }
                val body = action.notificationBody.ifBlank { "Automation completed successfully." }
                showAutomationNotification(title, body)
            }

            ActionType.OPEN_APP -> {
                val pkg = action.appPackageName.ifBlank { "com.google.android.youtube" }
                Log.d(TAG, "Launching app: $pkg")
                AppLauncher.launchApp(appContext, pkg)
            }

            ActionType.CREATE_REMINDER -> {
                val title = action.reminderTitle.ifBlank { "JARVIS Reminder: $ruleTitle" }
                val notes = action.reminderNotes.ifBlank { "Scheduled task reminder triggered." }
                showAutomationNotification("⏰ Reminder: $title", notes)
                speechAnnouncer.say("Reminder alert, Sir: $title")
            }

            ActionType.CONTROL_FEATURE -> {
                when (action.featureType) {
                    FeatureType.FLASHLIGHT_ON -> phoneControlManager.setFlashlight(true)
                    FeatureType.FLASHLIGHT_OFF -> phoneControlManager.setFlashlight(false)
                    FeatureType.SET_VOLUME -> phoneControlManager.setVolumePercent(action.featureValue)
                    FeatureType.SET_BRIGHTNESS -> phoneControlManager.setBrightnessPercent(action.featureValue)
                    FeatureType.TOGGLE_BLUETOOTH -> toggleBluetoothFeature()
                }
            }
        }
    }

    private fun showAutomationNotification(title: String, message: String) {
        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val notifId = notificationIdCounter.incrementAndGet()

        val notification = NotificationCompat.Builder(appContext, CHANNEL_AUTOMATION)
            .setSmallIcon(R.drawable.ic_jarvis_logo)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSound(soundUri)
            .setAutoCancel(true)
            .setColor(android.graphics.Color.parseColor("#06B6D4"))
            .build()

        notificationManager?.notify(notifId, notification)
    }

    private fun toggleBluetoothFeature() {
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter != null) {
                if (adapter.isEnabled) {
                    speechAnnouncer.say("Bluetooth is currently active, Sir.")
                } else {
                    speechAnnouncer.say("Bluetooth is currently disabled.")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Bluetooth status check failed: ${e.message}")
        }
    }

    // ==========================================
    // VOICE TRIGGER EVALUATION
    // ==========================================

    fun evaluateVoiceTrigger(spokenTranscript: String): Boolean {
        if (spokenTranscript.isBlank()) return false

        val normalizedInput = normalizePhrase(spokenTranscript)
        val voiceRules = repository.getAllRules().filter { it.isEnabled && it.trigger.type == TriggerType.VOICE_COMMAND }

        for (rule in voiceRules) {
            for (phrase in rule.trigger.voicePhrases) {
                val normalizedPhrase = normalizePhrase(phrase)
                val matches = if (rule.trigger.voiceMatchExact) {
                    normalizedInput == normalizedPhrase
                } else {
                    normalizedInput.contains(normalizedPhrase) || normalizedPhrase.contains(normalizedInput)
                }

                if (matches) {
                    Log.i(TAG, "Voice command matched rule '${rule.title}' with phrase: '$phrase'")
                    executeRule(rule.id, "VOICE: '$phrase'")
                    return true
                }
            }
        }
        return false
    }

    private fun normalizePhrase(text: String): String {
        return text.lowercase()
            .replace(Regex("[.,?!;:'\"()\\[\\]{}]"), "")
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    // ==========================================
    // DEVICE EVENT EVALUATION
    // ==========================================

    fun evaluateDeviceEvent(eventType: DeviceEventType, batteryPercent: Int = -1) {
        val eventRules = repository.getAllRules().filter {
            it.isEnabled && it.trigger.type == TriggerType.DEVICE_EVENT && it.trigger.deviceEventType == eventType
        }

        for (rule in eventRules) {
            when (eventType) {
                DeviceEventType.POWER_CONNECTED,
                DeviceEventType.POWER_DISCONNECTED -> {
                    executeRule(rule.id, "DEVICE_EVENT: $eventType")
                }

                DeviceEventType.BATTERY_LOW -> {
                    if (batteryPercent in 1..rule.trigger.batteryLevelThreshold) {
                        executeRule(rule.id, "BATTERY_LOW ($batteryPercent%)")
                    }
                }

                DeviceEventType.BATTERY_FULL -> {
                    if (batteryPercent >= rule.trigger.batteryLevelThreshold) {
                        executeRule(rule.id, "BATTERY_FULL ($batteryPercent%)")
                    }
                }
            }
        }
    }
}
