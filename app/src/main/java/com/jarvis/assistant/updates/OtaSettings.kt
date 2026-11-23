package com.jarvis.assistant.updates

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONException
import org.json.JSONObject

/**
 * Updater preferences and the small amount of state needed to survive process death. Everything stays on the device:
 * no device identifier or preference is ever sent to the update server.
 */
class OtaSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var autoCheck: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CHECK, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CHECK, value).apply()

    /** Off by default: a large APK is never downloaded until the person taps Update. */
    var autoDownload: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DOWNLOAD, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_DOWNLOAD, value).apply()

    /** When true (the default) installation always waits for the person's approval. */
    var askBeforeInstall: Boolean
        get() = prefs.getBoolean(KEY_ASK_INSTALL, true)
        set(value) = prefs.edit().putBoolean(KEY_ASK_INSTALL, value).apply()

    var wifiOnly: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY, false)
        set(value) = prefs.edit().putBoolean(KEY_WIFI_ONLY, value).apply()

    var branch: String
        get() = prefs.getString(KEY_BRANCH, null)?.takeIf { it.isNotBlank() } ?: OtaConfig.DEFAULT_BRANCH
        set(value) = prefs.edit().putString(KEY_BRANCH, value).apply()

    var lastCheckAt: Long
        get() = prefs.getLong(KEY_LAST_CHECK, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_CHECK, value).apply()

    /** Highest version code the person postponed with "Later"; the launch popup is not shown again for it. */
    var postponedCode: Long
        get() = prefs.getLong(KEY_POSTPONED, 0L)
        set(value) = prefs.edit().putLong(KEY_POSTPONED, value).apply()

    /** Set when the person tapped Update: install as soon as the download is verified (Android still asks to confirm). */
    var installWhenReady: Boolean
        get() = prefs.getBoolean(KEY_INSTALL_WHEN_READY, false)
        set(value) = prefs.edit().putBoolean(KEY_INSTALL_WHEN_READY, value).apply()

    var lastNotifiedCode: Long
        get() = prefs.getLong(KEY_NOTIFIED, 0L)
        set(value) = prefs.edit().putLong(KEY_NOTIFIED, value).apply()

    /** DownloadManager id of the download in progress, or -1. */
    var downloadId: Long
        get() = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
        set(value) = prefs.edit().putLong(KEY_DOWNLOAD_ID, value).apply()

    /** Version code whose installation was handed to Android, so the next launch can confirm it really happened. */
    var pendingInstallCode: Long
        get() = prefs.getLong(KEY_PENDING_INSTALL, -1L)
        set(value) = prefs.edit().putLong(KEY_PENDING_INSTALL, value).apply()

    var targetRelease: OtaRelease?
        get() {
            val json = prefs.getString(KEY_TARGET, null) ?: return null
            return try {
                val obj = JSONObject(json)
                OtaFeedParser.parseRelease(obj, obj.optString("branch", ""))
            } catch (_: JSONException) {
                null
            } catch (_: OtaFormatException) {
                null
            }
        }
        set(value) {
            val editor = prefs.edit()
            if (value == null) editor.remove(KEY_TARGET) else editor.putString(KEY_TARGET, value.toJson().toString())
            editor.apply()
        }

    fun downloadAttempts(code: Long): Int = prefs.getInt(KEY_ATTEMPTS_PREFIX + code, 0)

    fun noteDownloadAttempt(code: Long) {
        prefs.edit().putInt(KEY_ATTEMPTS_PREFIX + code, downloadAttempts(code) + 1).apply()
    }

    /** Stops automatic retries of a build whose file failed verification; a manual download still works. */
    fun exhaustAutoAttempts(code: Long) {
        prefs.edit().putInt(KEY_ATTEMPTS_PREFIX + code, OtaConfig.AUTO_DOWNLOAD_MAX_ATTEMPTS).apply()
    }

    fun clearAttempts(code: Long) {
        prefs.edit().remove(KEY_ATTEMPTS_PREFIX + code).apply()
    }

    /** The last feed for [forBranch] together with its ETag, so a repeat check can cost a 304 with no body. */
    fun cachedFeed(forBranch: String): Pair<String, String?>? {
        if (prefs.getString(KEY_FEED_BRANCH, null) != forBranch) return null
        val body = prefs.getString(KEY_FEED_BODY, null) ?: return null
        return body to prefs.getString(KEY_FEED_ETAG, null)
    }

    fun cacheFeed(forBranch: String, body: String, etag: String?) {
        prefs.edit().putString(KEY_FEED_BRANCH, forBranch).putString(KEY_FEED_BODY, body)
            .putString(KEY_FEED_ETAG, etag).apply()
    }

    fun history(): List<OtaHistoryEntry> = OtaHistoryCodec.decode(prefs.getString(KEY_HISTORY, null))

    @Synchronized
    fun addHistory(entry: OtaHistoryEntry) {
        prefs.edit().putString(KEY_HISTORY, OtaHistoryCodec.encode(OtaHistoryCodec.append(history(), entry))).apply()
    }

    companion object {
        const val PREFS = "jarvis_ota"
        private const val KEY_AUTO_CHECK = "auto_check"
        private const val KEY_AUTO_DOWNLOAD = "auto_download"
        private const val KEY_ASK_INSTALL = "ask_before_install"
        private const val KEY_WIFI_ONLY = "wifi_only"
        private const val KEY_BRANCH = "branch"
        private const val KEY_LAST_CHECK = "last_check_at"
        private const val KEY_POSTPONED = "postponed_code"
        private const val KEY_INSTALL_WHEN_READY = "install_when_ready"
        private const val KEY_NOTIFIED = "notified_code"
        private const val KEY_DOWNLOAD_ID = "download_id"
        private const val KEY_PENDING_INSTALL = "pending_install_code"
        private const val KEY_TARGET = "target_release"
        private const val KEY_ATTEMPTS_PREFIX = "attempts_"
        private const val KEY_FEED_BRANCH = "feed_branch"
        private const val KEY_FEED_BODY = "feed_body"
        private const val KEY_FEED_ETAG = "feed_etag"
        private const val KEY_HISTORY = "history"
    }
}
