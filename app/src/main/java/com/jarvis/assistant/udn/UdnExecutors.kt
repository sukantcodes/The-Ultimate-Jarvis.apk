package com.jarvis.assistant.udn

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.CancellationSignal
import android.os.PowerManager
import android.provider.Settings
import android.accessibilityservice.AccessibilityService
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.jarvis.assistant.BuildConfig
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.background.WakeKeepAlive
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/** What a device reports for one capability, and why it may be unavailable. Nothing is simulated. */
data class UdnAvailability(
    val available: Boolean,
    val reason: String? = null,
    val osPermission: String? = null,
    val permissionGranted: Boolean? = null
)

sealed class UdnHandlerResult {
    data class Success(val data: JSONObject = JSONObject()) : UdnHandlerResult()
    data class Failure(val code: String, val message: String) : UdnHandlerResult()
}

/** One remote-callable function. Handlers delegate to the existing JARVIS implementations instead of duplicating them. */
interface UdnCapabilityHandler {
    val capability: String
    fun availability(context: Context): UdnAvailability
    suspend fun execute(context: Context, params: JSONObject): UdnHandlerResult
}

/**
 * Opens an Activity on behalf of a remote command. Android blocks most background activity starts, so when the app
 * is not visible this posts a tap-to-open notification (with a full-screen intent where Android allows it) and
 * reports `launched=false` rather than pretending the screen opened.
 */
object UdnActivityLauncher {
    private const val CHANNEL = "jarvis_udn_open"

    data class Outcome(val launched: Boolean, val method: String)

    fun launch(context: Context, intent: Intent, title: String, text: String): Outcome {
        val app = context.applicationContext
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val mayStartDirectly = JarvisApp.isAppForeground.value || Settings.canDrawOverlays(app)
        if (mayStartDirectly) {
            try {
                app.startActivity(intent)
                return Outcome(true, "direct")
            } catch (e: Exception) {
                Log.w("UdnLauncher", "Direct start blocked: ${e.javaClass.simpleName}")
            }
        }
        return try {
            val nm = app.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Remote open requests", NotificationManager.IMPORTANCE_HIGH))
            }
            val pi = PendingIntent.getActivity(
                app, intent.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = NotificationCompat.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_jarvis_logo)
                .setContentTitle(title).setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_CALL)
                .setAutoCancel(true).setContentIntent(pi).setFullScreenIntent(pi, true)
                .build()
            if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) return Outcome(false, "blocked")
            nm.notify(7311, n)
            Outcome(false, "notification")
        } catch (e: Exception) {
            Outcome(false, "blocked")
        }
    }
}

/** On-device approval for high-sensitivity commands. The command only runs if the person at this device taps Allow. */
object UdnConsent {
    const val ACTION_ALLOW = "com.jarvis.assistant.udn.CONSENT_ALLOW"
    const val ACTION_DENY = "com.jarvis.assistant.udn.CONSENT_DENY"
    const val EXTRA_ID = "command_id"
    private const val CHANNEL = "jarvis_udn_consent"
    private val pending = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Boolean>>()

    /** Returns true = allowed, false = denied, null = nobody answered in time. */
    suspend fun request(context: Context, commandId: String, title: String, text: String, timeoutMs: Long): Boolean? {
        val app = context.applicationContext
        val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
        pending[commandId] = deferred
        val nm = app.getSystemService(NotificationManager::class.java)
        val notificationId = 7400 + (commandId.hashCode() and 0xFF)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Remote access approval", NotificationManager.IMPORTANCE_HIGH))
            }
            fun action(a: String, req: Int) = PendingIntent.getBroadcast(
                app, req + notificationId,
                Intent(app, UdnConsentReceiver::class.java).setAction(a).putExtra(EXTRA_ID, commandId).putExtra("nid", notificationId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = NotificationCompat.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_jarvis_logo).setContentTitle(title).setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_MAX).setCategory(NotificationCompat.CATEGORY_ALARM)
                .setOngoing(true).setTimeoutAfter(timeoutMs)
                .addAction(0, "Allow", action(ACTION_ALLOW, 1000)).addAction(0, "Deny", action(ACTION_DENY, 2000))
                .build()
            if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) return false // cannot ask, so never assume yes
            nm.notify(notificationId, n)
            return withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            pending.remove(commandId)
            nm.cancel(notificationId)
        }
    }

    fun resolve(commandId: String, allow: Boolean) {
        pending[commandId]?.complete(allow)
    }
}

class UdnConsentReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(UdnConsent.EXTRA_ID) ?: return
        UdnConsent.resolve(id, intent.action == UdnConsent.ACTION_ALLOW)
        context.getSystemService(NotificationManager::class.java)?.cancel(intent.getIntExtra("nid", 0))
    }
}

private fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun phoneControl() = JarvisApp.instance.phoneControlManager

object UdnHandlers {

    private val battery = object : UdnCapabilityHandler {
        override val capability = UdnCapabilities.DEVICE_BATTERY
        override fun availability(context: Context) = UdnAvailability(true)
        override suspend fun execute(context: Context, params: JSONObject): UdnHandlerResult {
            val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return UdnHandlerResult.Failure("battery_unavailable", "Android did not report battery data.")
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) return UdnHandlerResult.Failure("battery_unavailable", "Battery level is unavailable.")
            val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            return UdnHandlerResult.Success(JSONObject().apply {
                put("percent", level * 100 / scale)
                put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
                put("plugged", i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
            })
        }
    }

    private val connectivity = object : UdnCapabilityHandler {
        override val capability = UdnCapabilities.DEVICE_CONNECTIVITY
        override fun availability(context: Context) = UdnAvailability(true)
        override suspend fun execute(context: Context, params: JSONObject): UdnHandlerResult {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            return UdnHandlerResult.Success(JSONObject().apply {
                put("connected", caps != null)
                put("type", UdnNetwork.typeOf(caps))
                put("internet_validated", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
            })
        }
    }

    private val info = object : UdnCapabilityHandler {
        override val capability = UdnCapabilities.DEVICE_INFO
        override fun availability(context: Context) = UdnAvailability(true)
        override suspend fun execute(context: Context, params: JSONObject) = UdnHandlerResult.Success(JSONObject().apply {
            put("manufacturer", Build.MANUFACTURER); put("model", Build.MODEL)
            put("android", Build.VERSION.RELEASE); put("sdk", Build.VERSION.SDK_INT)
            put("app_version", BuildConfig.VERSION_NAME)
        })
    }

    private val lock = object : UdnCapabilityHandler {
        override val capability = UdnCapabilities.DEVICE_LOCK
        override fun availability(context: Context): UdnAvailability = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.P ->
                UdnAvailability(false, "Locking needs Android 9 or newer.")
            !JarvisAccessibilityService.isRunning() ->
                UdnAvailability(false, "Turn on the JARVIS accessibility service to allow locking.", "BIND_ACCESSIBILITY_SERVICE", false)
            else -> UdnAvailability(true, null, "BIND_ACCESSIBILITY_SERVICE", true)
        }

        override suspend fun execute(context: Context, params: JSONObject): UdnHandlerResult {
            val service = JarvisAccessibilityService.instance
                ?: return UdnHandlerResult.Failure("permission_missing", "The JARVIS accessibility service is off.")
            val pm = context.getSystemService(PowerManager::class.java)
            val accepted = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            if (!accepted) return UdnHandlerResult.Failure("lock_rejected", "Android refused the lock request.")
            delay(1500)
            return if (!pm.isInteractive) {
                UdnHandlerResult.Success(JSONObject().put("screen_off", true))
            } else {
                UdnHandlerResult.Failure("lock_not_confirmed", "Android accepted the lock request but the screen is still on.")
            }
        }
    }

    private val appLaunch = object : UdnCapabilityHandler {
        override val capability = UdnCapabilities.APP_LAUNCH
        override fun availability(context: Context) = UdnAvailability(true)
        override suspend fun execute(context: Context, params: JSONObject): UdnHandlerResult {
            val name = params.optString("app").trim()
            if (name.isEmpty() || name.length > 60) return UdnHandlerResult.Failure("bad_params", "No app name was given.")
            val pkg = phoneControl().resolvePackageForApp(name)
                ?: return UdnHandlerResult.Failure("app_not_found", "No installed app matches \"$name\".")
            val launch = context.packageManager.getLaunchIntentForPackage(pkg)
                ?: return UdnHandlerResult.Failure("app_not_launchable", "\"$name\" has no launch screen.")
            val out = UdnActivityLauncher.launch(context, launch, "Open $name", "A JARVIS device asked to open $name. Tap to open.")
            return UdnHandlerResult.Success(JSONObject().put("app", name).put("launched", out.launched).put("method", out.method))
        }
    }

    private val moduleList = object : UdnCapabilityHandler {
        override val capability = UdnCapabilities.MODULE_LIST
        override fun availability(context: Context) = UdnAvailability(true)
        override suspend fun execute(context: Context, params: JSONObject) = UdnHandlerResult.Success(
            JSONObject().put("modules", JSONArray().also { arr ->
                UdnModules.all.forEach { arr.put(JSONObject().put("id", it.id).put("label", it.label)) }
            })
        )
    }

    private val moduleOpen = object : UdnCapabilityHandler {
        override val capability = UdnCapabilities.MODULE_OPEN
        override fun availability(context: Context) = UdnAvailability(true)
        override suspend fun execute(context: Context, params: JSONObject): UdnHandlerResult {
            val module = UdnModules.byId(params.optString("module"))
                ?: return UdnHandlerResult.Failure("module_unknown", "That JARVIS module doesn't exist on this device.")
            val intent = UdnModuleIntents.build(context, module.id)
                ?: return UdnHandlerResult.Failure("module_unavailable", "${module.label} can't be opened on this device.")
            val out = UdnActivityLauncher.launch(context, intent, "Open ${module.label}", "A JARVIS device asked to open ${module.label}. Tap to open.")
            return UdnHandlerResult.Success(JSONObject().put("module", module.id).put("label", module.label).put("launched", out.launched).put("method", out.method))
        }
    }

    private val notify = object : UdnCapabilityHandler {
        override val capability = UdnCapabilities.NOTIFY_SHOW
        override fun availability(context: Context): UdnAvailability =
            if (NotificationManagerCompat.from(context).areNotificationsEnabled()) UdnAvailability(true, null, "POST_NOTIFICATIONS", true)
            else UdnAvailability(false, "Notifications are turned off for JARVIS on this device.", "POST_NOTIFICATIONS", false)

        override suspend fun execute(context: Context, params: JSONObject): UdnHandlerResult {
            val text = params.optString("text").trim().take(300)
            if (text.isEmpty()) return UdnHandlerResult.Failure("bad_params", "The notification had no text.")
            val title = params.optString("title").trim().take(60).ifEmpty { "JARVIS" }
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                return UdnHandlerResult.Failure("permission_missing", "Notifications are turned off for JARVIS on this device.")
            }
            val nm = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel("jarvis_udn_messages") == null) {
                nm.createNotificationChannel(NotificationChannel("jarvis_udn_messages", "Messages from your devices", NotificationManager.IMPORTANCE_DEFAULT))
            }
            nm.notify(7500 + (text.hashCode() and 0xFF), NotificationCompat.Builder(context, "jarvis_udn_messages")
                .setSmallIcon(R.drawable.ic_jarvis_logo).setContentTitle(title).setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true).build())
            return UdnHandlerResult.Success(JSONObject().put("shown", true))
        }
    }

    private fun mediaPercent(context: Context): Int {
        val am = context.getSystemService(android.media.AudioManager::class.java)
        val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return Math.round(am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) * 100f / max)
    }

    private val volume = object : UdnCapabilityHandler {
        override val capability = UdnCapabilities.SETTINGS_VOLUME
        override fun availability(context: Context) = UdnAvailability(true)
        override suspend fun execute(context: Context, params: JSONObject): UdnHandlerResult {
            val before = mediaPercent(context)
            val target = when {
                params.has("level") -> params.optInt("level").coerceIn(0, 100)
                params.has("delta") -> (before + params.optInt("delta")).coerceIn(0, 100)
                else -> return UdnHandlerResult.Failure("bad_params", "No volume level was given.")
            }
            phoneControl().setVolumePercent(target)
            val after = mediaPercent(context)
            return if (kotlin.math.abs(after - target) <= 8) {
                UdnHandlerResult.Success(JSONObject().put("before", before).put("percent", after))
            } else {
                UdnHandlerResult.Failure("volume_not_applied", "Android left the volume at $after percent.")
            }
        }
    }

    private fun brightnessPercent(context: Context): Int =
        Math.round(Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) * 100f / 255f)

    private val brightness = object : UdnCapabilityHandler {
        override val capability = UdnCapabilities.SETTINGS_BRIGHTNESS
        override fun availability(context: Context): UdnAvailability =
            if (Settings.System.canWrite(context)) UdnAvailability(true, null, "WRITE_SETTINGS", true)
            else UdnAvailability(false, "Allow \"Modify system settings\" for JARVIS on this device.", "WRITE_SETTINGS", false)

        override suspend fun execute(context: Context, params: JSONObject): UdnHandlerResult {
            if (!Settings.System.canWrite(context)) return UdnHandlerResult.Failure("permission_missing", "\"Modify system settings\" isn't allowed for JARVIS.")
            val before = brightnessPercent(context)
            val target = when {
                params.has("level") -> params.optInt("level").coerceIn(0, 100)
                params.has("delta") -> (before + params.optInt("delta")).coerceIn(0, 100)
                else -> return UdnHandlerResult.Failure("bad_params", "No brightness level was given.")
            }
            phoneControl().setBrightnessPercent(target)
            delay(300)
            val after = brightnessPercent(context)
            return if (kotlin.math.abs(after - target) <= 10) {
                UdnHandlerResult.Success(JSONObject().put("before", before).put("percent", after))
            } else {
                UdnHandlerResult.Failure("brightness_not_applied", "Android left the brightness at $after percent.")
            }
        }
    }

    private val flashlight = object : UdnCapabilityHandler {
        override val capability = UdnCapabilities.SETTINGS_FLASHLIGHT
        override fun availability(context: Context): UdnAvailability =
            if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) UdnAvailability(true)
            else UdnAvailability(false, "This device has no flashlight.")

        override suspend fun execute(context: Context, params: JSONObject): UdnHandlerResult {
            val on = params.optBoolean("on", true)
            phoneControl().setFlashlight(on)
            delay(300)
            return if (phoneControl().isTorchOn == on) UdnHandlerResult.Success(JSONObject().put("on", on))
            else UdnHandlerResult.Failure("flashlight_not_applied", "The flashlight didn't change (the camera may be in use).")
        }
    }

    private fun conversationServiceRunning(context: Context): Boolean {
        @Suppress("DEPRECATION")
        val services = context.getSystemService(ActivityManager::class.java).getRunningServices(100)
        return services.any { it.service.className == JarvisConversationService::class.java.name }
    }

    private val stopListening = object : UdnCapabilityHandler {
        override val capability = UdnCapabilities.LISTENING_STOP
        override fun availability(context: Context) = UdnAvailability(true)
        override suspend fun execute(context: Context, params: JSONObject): UdnHandlerResult {
            val wasListening = WakeKeepAlive.isEnabled(context) || conversationServiceRunning(context)
            WakeKeepAlive.setEnabled(context, false) // stops the watchdog so Android/JARVIS don't restart it
            runCatching {
                context.startService(Intent(context, JarvisConversationService::class.java).setAction(JarvisConversationService.ACTION_STOP_ALL))
            }.onFailure {
                runCatching { context.stopService(Intent(context, JarvisConversationService::class.java)) }
            }
            delay(1500)
            val stillRunning = conversationServiceRunning(context)
            return if (!stillRunning && !WakeKeepAlive.isEnabled(context)) {
                UdnHandlerResult.Success(JSONObject().put("was_listening", wasListening).put("listening", false))
            } else {
                UdnHandlerResult.Failure("stop_not_confirmed", "Background listening is still running on that device.")
            }
        }
    }

    private val location = object : UdnCapabilityHandler {
        override val capability = UdnCapabilities.LOCATION_GET
        override fun availability(context: Context): UdnAvailability = when {
            !UdnSettings.get(context).locationSharingEnabled ->
                UdnAvailability(false, "Location sharing is switched off on this device.", null, null)
            !granted(context, Manifest.permission.ACCESS_FINE_LOCATION) && !granted(context, Manifest.permission.ACCESS_COARSE_LOCATION) ->
                UdnAvailability(false, "Location permission hasn't been granted to JARVIS.", "ACCESS_FINE_LOCATION", false)
            else -> UdnAvailability(true, null, "ACCESS_FINE_LOCATION", true)
        }

        @Suppress("MissingPermission")
        override suspend fun execute(context: Context, params: JSONObject): UdnHandlerResult {
            val av = availability(context)
            if (!av.available) return UdnHandlerResult.Failure("permission_missing", av.reason ?: "Location isn't available.")
            val lm = context.getSystemService(LocationManager::class.java)
            val providers = lm.getProviders(true)
            var fix: Location? = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).firstOrNull { it in providers }
                if (provider != null) {
                    fix = withTimeoutOrNull(8_000) {
                        suspendCancellableCoroutine<Location?> { cont ->
                            val cancel = CancellationSignal()
                            cont.invokeOnCancellation { cancel.cancel() }
                            lm.getCurrentLocation(provider, cancel, Executors.newSingleThreadExecutor()) { cont.resume(it) }
                        }
                    }
                }
            }
            if (fix == null) fix = providers.mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
            val f = fix ?: return UdnHandlerResult.Failure("no_location_fix", "The device couldn't get a location fix.")
            return UdnHandlerResult.Success(JSONObject().apply {
                put("lat", f.latitude); put("lng", f.longitude); put("accuracy_m", f.accuracy.toInt())
                put("age_s", ((System.currentTimeMillis() - f.time) / 1000).coerceAtLeast(0))
            })
        }
    }

    private val camera = object : UdnCapabilityHandler {
        override val capability = UdnCapabilities.CAMERA_OPEN
        override fun availability(context: Context): UdnAvailability =
            if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) UdnAvailability(true, null, "CAMERA", granted(context, Manifest.permission.CAMERA))
            else UdnAvailability(false, "This device has no camera.")

        override suspend fun execute(context: Context, params: JSONObject): UdnHandlerResult {
            val intent = UdnModuleIntents.build(context, "camera_scan")
                ?: return UdnHandlerResult.Failure("module_unavailable", "Camera Scan can't be opened on this device.")
            val out = UdnActivityLauncher.launch(context, intent, "Camera approved", "Camera Scan was approved remotely. Tap to open it.")
            // This opens the Camera Scan screen on the device. It does not stream or transmit camera frames.
            return UdnHandlerResult.Success(JSONObject().put("launched", out.launched).put("method", out.method).put("streaming", false))
        }
    }

    private val chatAsk = object : UdnCapabilityHandler {
        override val capability = UdnCapabilities.CHAT_ASK
        override fun availability(context: Context) = UdnAvailability(false, "Remote AI questions are not implemented yet.")
        override suspend fun execute(context: Context, params: JSONObject) =
            UdnHandlerResult.Failure("capability_unavailable", "Remote AI questions are not implemented yet.")
    }

    private val routine = object : UdnCapabilityHandler {
        override val capability = UdnCapabilities.ROUTINE_RUN
        override fun availability(context: Context) = UdnAvailability(false, "Remote automation routines are not implemented yet.")
        override suspend fun execute(context: Context, params: JSONObject) =
            UdnHandlerResult.Failure("capability_unavailable", "Remote automation routines are not implemented yet.")
    }

    val all: List<UdnCapabilityHandler> = listOf(
        info, battery, connectivity, lock, appLaunch, moduleOpen, moduleList, notify,
        volume, brightness, flashlight, stopListening, location, camera, chatAsk, routine
    )

    fun find(capability: String): UdnCapabilityHandler? = all.firstOrNull { it.capability == capability }

    /** Capability + permission report sent to the server so parents see what this device can actually do right now. */
    fun report(context: Context): JSONObject {
        val caps = JSONArray()
        all.forEach { h ->
            val a = runCatching { h.availability(context) }.getOrElse { UdnAvailability(false, "Could not check this capability.") }
            caps.put(JSONObject().apply {
                put("capability", h.capability); put("available", a.available)
                a.reason?.let { put("reason", it) }; a.osPermission?.let { put("os_permission", it) }
                a.permissionGranted?.let { put("permission_granted", it) }
            })
        }
        val perms = JSONArray()
        listOf(
            Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS
        ).forEach { p -> perms.put(JSONObject().put("permission", p.substringAfterLast('.')).put("granted", granted(context, p))) }
        perms.put(JSONObject().put("permission", "ACCESSIBILITY_SERVICE").put("granted", JarvisAccessibilityService.isRunning()))
        perms.put(JSONObject().put("permission", "WRITE_SETTINGS").put("granted", Settings.System.canWrite(context)))
        return JSONObject().put("capabilities", caps).put("permissions", perms)
    }
}

object UdnNetwork {
    fun typeOf(caps: NetworkCapabilities?): String = when {
        caps == null -> "unknown"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        else -> "other"
    }
}

/** Maps JARVIS module ids to the real screens in this app. */
object UdnModuleIntents {
    fun build(context: Context, moduleId: String): Intent? {
        fun main(tab: Int) = Intent(context, com.jarvis.assistant.ui.home.MainActivity::class.java)
            .putExtra(com.jarvis.assistant.ui.home.MainActivity.EXTRA_OPEN_TAB, tab)
        return when (moduleId) {
            "chatbox" -> main(com.jarvis.assistant.ui.home.MainActivity.TAB_CHAT)
            "settings" -> main(com.jarvis.assistant.ui.home.MainActivity.TAB_SETTINGS)
            "home" -> Intent(context, com.jarvis.assistant.ui.jarvishome.HomeActivity::class.java)
            "automation_studio" -> Intent(context, com.jarvis.assistant.studio.ui.AutomationStudioActivity::class.java)
            "camera_scan" -> Intent(context, com.jarvis.assistant.ui.scan.CameraScanActivity::class.java)
            "memories" -> Intent(context, com.jarvis.assistant.ui.memory.MemoryActivity::class.java)
            "terminal" -> Intent(context, com.jarvis.assistant.ui.terminal.JarvisTerminalActivity::class.java)
            "alarms" -> Intent(context, com.jarvis.assistant.ui.clock.AlarmsActivity::class.java)
            "skill_store" -> Intent(context, com.jarvis.assistant.ui.skills.SkillStoreActivity::class.java)
            "device_network" -> Intent(context, com.jarvis.assistant.ui.udn.UdnActivity::class.java)
            else -> null
        }
    }
}
