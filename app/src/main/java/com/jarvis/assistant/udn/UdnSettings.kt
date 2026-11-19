package com.jarvis.assistant.udn

import android.content.Context
import android.content.SharedPreferences

/** Local, per-device UDN preferences. None of these hold secrets; the private key never leaves the Android Keystore. */
class UdnSettings private constructor(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences("jarvis_udn", Context.MODE_PRIVATE)

    var deviceId: String?
        get() = prefs.getString("device_id", null)
        set(v) { prefs.edit().apply { if (v == null) remove("device_id") else putString("device_id", v) }.apply() }

    /** The user answered the one-time "join the network?" prompt (either way). */
    var enrollmentPrompted: Boolean
        get() = prefs.getBoolean("enrollment_prompted", false)
        set(v) = prefs.edit().putBoolean("enrollment_prompted", v).apply()

    /** Master switch: when off, this device refuses every remote command, whatever the server says. */
    var remoteControlEnabled: Boolean
        get() = prefs.getBoolean("remote_control", true)
        set(v) = prefs.edit().putBoolean("remote_control", v).apply()

    /** Location sharing is opt-in; location.get is reported unavailable until the user turns this on. */
    var locationSharingEnabled: Boolean
        get() = prefs.getBoolean("location_sharing", false)
        set(v) = prefs.edit().putBoolean("location_sharing", v).apply()

    /** Keep the background network link (foreground service) running. Enabled by the user in the UDN screen. */
    var linkEnabled: Boolean
        get() = prefs.getBoolean("link_enabled", true)
        set(v) = prefs.edit().putBoolean("link_enabled", v).apply()

    var lastCapabilityHash: String?
        get() = prefs.getString("cap_hash", null)
        set(v) = prefs.edit().putString("cap_hash", v).apply()

    var lastKnownStatus: String?
        get() = prefs.getString("last_status", null)
        set(v) = prefs.edit().putString("last_status", v).apply()

    fun clearEnrollment() {
        prefs.edit().remove("device_id").remove("cap_hash").remove("last_status").apply()
    }

    companion object {
        @Volatile private var instance: UdnSettings? = null
        fun get(context: Context): UdnSettings =
            instance ?: synchronized(this) { instance ?: UdnSettings(context).also { instance = it } }
    }
}
