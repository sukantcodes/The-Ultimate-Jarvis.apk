package com.jarvis.assistant.offline

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import android.telephony.SmsManager
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.util.AppLauncher
import com.jarvis.assistant.util.SimCallHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Runs an [OfflineIntent] on the phone with no network and no language model, and returns the sentence
 * JARVIS should speak. Everything here is a direct Android call, so the reply is ready as soon as the
 * action finishes.
 *
 * Things that need the service's own state (unlocking with owner-voice checks, going to standby,
 * stopping speech) are handed in through [Hooks].
 */
class OfflineActionExecutor(private val context: Context, private val hooks: Hooks) {

    interface Hooks {
        /** Unlock the screen, applying the owner-voice rules. Returns the sentence to speak. */
        suspend fun unlock(): String
        fun goStandby()
        fun stopSpeaking()
    }

    private val app: Context = context.applicationContext

    /**
     * Returns the spoken reply, or null when the request should go to the chat model instead
     * (e.g. "go to the market" matched the open-app pattern but there is no such app).
     */
    suspend fun execute(intent: OfflineIntent): String? {
        return try {
            when (intent) {
                is OfflineIntent.LockScreen -> lock()
                is OfflineIntent.UnlockScreen -> hooks.unlock()
                is OfflineIntent.GoStandby -> {
                    hooks.goStandby()
                    "Okay, I'll be in standby. Say Hey Jarvis when you need me."
                }
                is OfflineIntent.StopSpeaking -> {
                    hooks.stopSpeaking()
                    ""
                }
                is OfflineIntent.TellTime -> {
                    val fmt = SimpleDateFormat("h:mm a", Locale.getDefault())
                    "It's ${fmt.format(Date())}."
                }
                is OfflineIntent.TellDate -> {
                    val fmt = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault())
                    "Today is ${fmt.format(Date())}."
                }
                is OfflineIntent.TellBattery -> battery()
                is OfflineIntent.Media -> media(intent.action)
                is OfflineIntent.Navigate -> navigate(intent.gesture)
                is OfflineIntent.Scroll -> scroll(intent)
                is OfflineIntent.Tap -> tap(intent.label)
                is OfflineIntent.TypeText -> typeText(intent.text)
                is OfflineIntent.OpenApp -> openApp(intent)
                is OfflineIntent.OpenSettings -> openSettings(intent)
                is OfflineIntent.SetTimer -> setTimer(intent.seconds)
                is OfflineIntent.SetAlarm -> setAlarm(intent)
                is OfflineIntent.DoNotDisturb -> doNotDisturb(intent.on)
                is OfflineIntent.BatterySaver -> batterySaver(intent.on)
                is OfflineIntent.CallContact -> callContact(intent.target)
                is OfflineIntent.CallControl -> callControl(intent.action)
                is OfflineIntent.SendSms -> sendSms(intent.contact, intent.body)
            }
        } catch (error: Exception) {
            Log.e(TAG, "Offline action failed: $intent", error)
            "Sorry, that didn't work: ${error.message ?: "unknown error"}."
        }
    }

    private fun accessibility(): JarvisAccessibilityService? = JarvisAccessibilityService.instance

    private val needAccessibility = "Please turn on JARVIS Accessibility in Android settings so I can do that."

    private fun startActivity(intent: Intent): Boolean = try {
        app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (error: Exception) {
        Log.w(TAG, "startActivity failed: ${error.message}")
        false
    }

    // ---- Lock, info, media ---------------------------------------------------------------------

    private suspend fun lock(): String = withContext(Dispatchers.Main) {
        val acc = accessibility()
        when {
            acc == null -> needAccessibility
            Build.VERSION.SDK_INT < Build.VERSION_CODES.P -> "Locking the screen needs Android 9 or newer."
            acc.lockScreenNow() -> "Screen locked."
            else -> "Android didn't lock the screen. Please check the JARVIS Accessibility permission."
        }
    }

    private fun battery(): String {
        val bm = app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val status = app.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return if (level in 0..100) {
            "Battery is at $level percent${if (charging) " and charging" else ""}."
        } else {
            "I couldn't read the battery level."
        }
    }

    private fun media(action: String): String {
        val key = when (action) {
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            else -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        }
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        return when (action) {
            "pause" -> "Paused."
            "play" -> "Playing."
            "next" -> "Next track."
            else -> "Previous track."
        }
    }

    // ---- Screen control (accessibility) ---------------------------------------------------------

    private suspend fun navigate(gesture: String): String = withContext(Dispatchers.Main) {
        val acc = accessibility() ?: return@withContext needAccessibility
        val ok = when (gesture) {
            "quick_settings" -> acc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            "screenshot" ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    acc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
                } else {
                    false
                }
            else -> acc.performSystemGesture(gesture)
        }
        if (!ok) return@withContext "I couldn't do that on this phone."
        when (gesture) {
            "home" -> "Home."
            "back" -> "Going back."
            "recents" -> "Here are your recent apps."
            "notifications" -> "Notifications."
            "quick_settings" -> "Quick settings."
            else -> "Screenshot taken."
        }
    }

    private suspend fun scroll(intent: OfflineIntent.Scroll): String = withContext(Dispatchers.Main) {
        val acc = accessibility() ?: return@withContext needAccessibility
        var ok = false
        repeat(intent.repeat.coerceIn(1, 8)) {
            ok = acc.performSmartScroll(intent.direction.ifEmpty { "down" }, intent.distance) || ok
            if (intent.repeat > 1) delay(250)
        }
        if (ok) "Scrolled." else "I couldn't scroll here."
    }

    private suspend fun tap(label: String): String = withContext(Dispatchers.Main) {
        val acc = accessibility() ?: return@withContext needAccessibility
        if (acc.clickNodeWithText(label)) "Tapped $label." else "I can't see $label on the screen."
    }

    private suspend fun typeText(text: String): String = withContext(Dispatchers.Main) {
        val acc = accessibility() ?: return@withContext needAccessibility
        if (acc.typeText(text)) "Typed it." else "Tap a text box first, then I can type."
    }

    // ---- Apps and settings ----------------------------------------------------------------------

    private suspend fun openApp(intent: OfflineIntent.OpenApp): String? = withContext(Dispatchers.Main) {
        when (val result = AppLauncher.openAppResult(app, intent.name)) {
            is AppLauncher.OpenAppResult.Success -> "Opening ${result.app.label}."
            is AppLauncher.OpenAppResult.MultipleFound ->
                "I found ${result.matches.take(3).joinToString(" and ") { it.label }}. Which one?"
            is AppLauncher.OpenAppResult.NotFound ->
                if (intent.explicit) "I couldn't find an app called ${intent.name}." else null
            is AppLauncher.OpenAppResult.Failure -> "I couldn't open ${intent.name}."
        }
    }

    private fun openSettings(intent: OfflineIntent.OpenSettings): String {
        val wantsToggle = intent.toggle != null
        val onOff = if (intent.toggle == true) "on" else "off"
        val q = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        // Android 10+ does not let apps flip Wi-Fi, mobile data or airplane mode directly; the
        // system panels put the switch one tap away.
        val action: String = when (intent.page) {
            "main" -> Settings.ACTION_SETTINGS
            "wifi" -> if (wantsToggle && q) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            "nfc" -> if (wantsToggle && q) Settings.Panel.ACTION_NFC else Settings.ACTION_NFC_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "developer" -> Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
            "mobile_network" -> if (wantsToggle && q) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_DATA_ROAMING_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            "security" -> Settings.ACTION_SECURITY_SETTINGS
            "privacy" -> Settings.ACTION_PRIVACY_SETTINGS
            "language" -> Settings.ACTION_LOCALE_SETTINGS
            "date" -> Settings.ACTION_DATE_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "notifications" -> Settings.ACTION_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        val name = intent.page.replace('_', ' ')
        if (!startActivity(Intent(action))) {
            if (!startActivity(Intent(Settings.ACTION_SETTINGS))) return "I couldn't open settings."
            return "Opening settings."
        }
        return if (wantsToggle) "Opening $name. Tap the switch to turn it $onOff." else "Opening $name settings."
    }

    // ---- Timer, alarm, modes -------------------------------------------------------------------

    private fun setTimer(seconds: Int): String {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "JARVIS timer")
        if (!startActivity(intent)) return "I couldn't set a timer on this phone."
        return "Timer set for ${describeDuration(seconds)}."
    }

    private fun describeDuration(total: Int): String {
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        val parts = ArrayList<String>()
        if (h > 0) parts.add("$h hour${if (h == 1) "" else "s"}")
        if (m > 0) parts.add("$m minute${if (m == 1) "" else "s"}")
        if (s > 0) parts.add("$s second${if (s == 1) "" else "s"}")
        return parts.joinToString(" ")
    }

    private fun setAlarm(intent: OfflineIntent.SetAlarm): String {
        var hour = intent.hour
        if (intent.ambiguous) {
            // No am/pm said: take whichever of the two readings comes next.
            val now = Calendar.getInstance()
            val nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
            val base = hour % 12
            val am = base * 60 + intent.minute
            val pm = (base + 12) * 60 + intent.minute
            val candidates = listOf(am, pm).map { if (it > nowMinutes) it - nowMinutes else it + 1440 - nowMinutes }
            hour = if (candidates[0] <= candidates[1]) base else base + 12
        }
        val alarm = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, intent.minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "JARVIS alarm")
        if (!startActivity(alarm)) return "I couldn't set an alarm on this phone."
        val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, intent.minute) }
        return "Alarm set for ${SimpleDateFormat("h:mm a", Locale.getDefault()).format(cal.time)}."
    }

    private fun doNotDisturb(on: Boolean): String {
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            return "I need Do Not Disturb access first. Please allow JARVIS on the screen I just opened."
        }
        nm.setInterruptionFilter(
            if (on) NotificationManager.INTERRUPTION_FILTER_NONE else NotificationManager.INTERRUPTION_FILTER_ALL
        )
        return if (on) "Do Not Disturb is on." else "Do Not Disturb is off."
    }

    private fun batterySaver(on: Boolean): String {
        return try {
            Settings.Global.putInt(app.contentResolver, "low_power", if (on) 1 else 0)
            if (on) "Battery saver is on." else "Battery saver is off."
        } catch (_: SecurityException) {
            // Needs a permission only adb can grant, so open the page instead.
            startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
            "Opening battery saver. Tap the switch to turn it ${if (on) "on" else "off"}."
        }
    }

    // ---- Calls and messages ---------------------------------------------------------------------

    private suspend fun callContact(target: String): String = withContext(Dispatchers.Main) {
        JarvisApp.instance.phoneControlManager.makePhoneCall(target)
    }

    private suspend fun callControl(action: String): String = withContext(Dispatchers.Main) {
        val acc = accessibility() ?: return@withContext needAccessibility
        when (acc.pressCallControl(action)) {
            "OK" -> when (action) {
                "end" -> "Call ended."
                "speaker_on" -> "Speaker on."
                "speaker_off" -> "Speaker off."
                "mute" -> "Muted."
                "unmute" -> "Unmuted."
                "hold" -> "Call on hold."
                else -> "Call resumed."
            }
            "ALREADY" -> "It's already that way."
            "NO_SCREEN" -> "I can't see the call screen right now."
            "NOT_FOUND" -> "I can't find that button on the call screen."
            else -> "That didn't work."
        }
    }

    private suspend fun sendSms(contact: String, body: String): String = withContext(Dispatchers.Main) {
        val number = when (val lookup = SimCallHelper.lookupContact(app, contact)) {
            is SimCallHelper.ContactLookup.Found -> lookup.number
            is SimCallHelper.ContactLookup.Multiple ->
                return@withContext "Which $contact? I found ${lookup.names.take(3).joinToString(" or ")}."
            is SimCallHelper.ContactLookup.NoPermission ->
                return@withContext "I need contacts permission to find $contact."
            is SimCallHelper.ContactLookup.NoMatch ->
                return@withContext "I couldn't find $contact in your contacts."
        }
        val canSend = ContextCompat.checkSelfPermission(app, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        if (canSend) {
            val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                app.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION") SmsManager.getDefault()
            }
            val parts = sms.divideMessage(body)
            sms.sendMultipartTextMessage(number, null, parts, null, null)
            "Message sent to $contact."
        } else {
            // No SMS permission yet: fill in the message and let the person press send.
            val compose = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).putExtra("sms_body", body)
            if (startActivity(compose)) {
                "I opened the message to $contact. Tap send. Allow SMS for JARVIS in app permissions and I'll send it myself next time."
            } else {
                "I couldn't open the messages app."
            }
        }
    }

    companion object {
        private const val TAG = "OfflineActionExecutor"
    }
}
