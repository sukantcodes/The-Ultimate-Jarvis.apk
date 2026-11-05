package com.jarvis.assistant.owner

import android.content.Context
import android.os.Build
import com.google.gson.JsonObject
import com.jarvis.assistant.BuildConfig
import com.jarvis.assistant.auth.AuthManager
import com.jarvis.assistant.auth.AuthResult
import com.jarvis.assistant.auth.SupabaseConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Reports a minimal device record for signed-in accounts so the owner dashboard has data. Sent:
 * a random per-install ID, device model, Android version, JARVIS version, and the event type.
 * Never sent: IMEI, SIM, phone number, Android ID, contacts, messages, files, audio, or location.
 * The IP address is NOT sent by the app; the server reads it from the connection. Guests (no account)
 * are never reported.
 */
object DeviceTelemetry {
    private const val PREFS = "jarvis_install"
    private const val KEY_INSTALL_ID = "install_id"
    private const val KEY_LAST_OPEN = "last_open_report_ms"
    private const val OPEN_INTERVAL_MS = 6L * 60 * 60 * 1000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private lateinit var appContext: Context

    fun init(context: Context) { appContext = context.applicationContext }

    /** Random ID created on first use. Not hardware-derived; cleared when app data is cleared. */
    private fun installId(): String {
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_INSTALL_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_INSTALL_ID, it).apply()
        }
    }

    /** Called when an account session is established. */
    fun onSignedIn() = report("sign_in")

    /** Called on app start; at most once per [OPEN_INTERVAL_MS]. */
    fun onAppOpen() {
        if (!::appContext.isInitialized) return
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_OPEN, 0L) < OPEN_INTERVAL_MS) return
        if (report("app_open")) prefs.edit().putLong(KEY_LAST_OPEN, now).apply()
    }

    private fun report(event: String): Boolean {
        if (!::appContext.isInitialized || !SupabaseConfig.isConfigured || !AuthManager.isSignedIn) return false
        scope.launch {
            try {
                val token = (AuthManager.validAccessToken() as? AuthResult.Success)?.value ?: return@launch
                val body = JsonObject().apply {
                    addProperty("install_id", installId())
                    addProperty("device_model", Build.MODEL ?: "")
                    addProperty("android_version", Build.VERSION.RELEASE ?: "")
                    addProperty("app_version", BuildConfig.VERSION_NAME)
                    addProperty("event", event)
                }
                val req = Request.Builder()
                    .url(SupabaseConfig.url + "/functions/v1/device-telemetry")
                    .header("apikey", SupabaseConfig.publishableKey)
                    .header("Authorization", "Bearer $token")
                    .post(body.toString().toRequestBody(jsonType))
                    .build()
                http.newCall(req).execute().close()
            } catch (_: Exception) {
                // Analytics must never affect the app; failures are silent.
            }
        }
        return true
    }
}
