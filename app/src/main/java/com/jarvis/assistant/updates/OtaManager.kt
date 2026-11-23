package com.jarvis.assistant.updates

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.Settings
import com.jarvis.assistant.BuildConfig
import com.jarvis.assistant.auth.SupabaseConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

sealed class OtaState {
    data object Idle : OtaState()
    data object Checking : OtaState()
    data class UpToDate(val message: String) : OtaState()
    data class Available(val release: OtaRelease) : OtaState()
    data class Downloading(
        val release: OtaRelease,
        val bytes: Long,
        val total: Long,
        val bytesPerSecond: Double,
        val note: String?
    ) : OtaState()
    data class Verifying(val release: OtaRelease) : OtaState()
    data class Ready(val release: OtaRelease) : OtaState()
    data class NeedsInstallPermission(val release: OtaRelease) : OtaState()
    data class Installing(val release: OtaRelease) : OtaState()
    data class Failed(val message: String, val release: OtaRelease?, val canRetry: Boolean) : OtaState()
}

/**
 * Orchestrates the whole OTA lifecycle: check the metadata feed, download the APK from GitHub Releases with Android's
 * DownloadManager (resumable and independent of this process), verify it completely, then install it in place through
 * PackageInstaller. Durable facts live in [OtaSettings] so the flow recovers after the process is killed.
 *
 * All state changes happen under one mutex. Functions ending in `Locked` assume the lock is held and never take it.
 */
object OtaManager {
    private const val OTA_DIR = "ota"
    private const val SPACE_MARGIN_BYTES = 64L * 1024L * 1024L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private val meter = SpeedMeter()
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val _state = MutableStateFlow<OtaState>(OtaState.Idle)
    val state: StateFlow<OtaState> = _state.asStateFlow()

    private val _feed = MutableStateFlow<OtaFeed?>(null)
    val feed: StateFlow<OtaFeed?> = _feed.asStateFlow()

    /** True while the Software Updates screen is on screen; lets the poller run faster and skips redundant notifications. */
    @Volatile
    var uiVisible: Boolean = false

    @Volatile
    private var restored = false
    private var pollJob: Job? = null

    private val installedCode: Long get() = BuildConfig.VERSION_CODE.toLong()

    // ---- Public API -----------------------------------------------------------------------------------------

    /** Loads persisted state (cached feed, running or finished download) without blocking the caller. */
    fun restoreAsync(context: Context) {
        val app = context.applicationContext
        scope.launch { ensureRestored(app) }
    }

    /** Asks the metadata feed whether a newer build exists. Automatic checks stay silent when something is already in progress. */
    suspend fun check(context: Context, manual: Boolean) {
        val app = context.applicationContext
        ensureRestored(app)
        val settings = OtaSettings(app)
        lock.withLock {
            val previous = _state.value
            val busy = previous is OtaState.Downloading || previous is OtaState.Verifying || previous is OtaState.Installing
            if (!busy) _state.value = OtaState.Checking
            val feed = try {
                fetchFeed(settings)
            } catch (error: Exception) {
                if (!busy) {
                    _state.value = if (manual) {
                        OtaState.Failed(friendlyNetworkError(error), previous.releaseOrNull(), canRetry = true)
                    } else {
                        if (previous is OtaState.Checking) OtaState.Idle else previous
                    }
                }
                return@withLock
            }
            settings.lastCheckAt = System.currentTimeMillis()
            _feed.value = feed
            if (busy) return@withLock
            applyFeedLocked(app, settings, feed, manual)
        }
    }

    /** Starts (or restarts) the download of the newest offered build. */
    suspend fun download(context: Context) {
        val app = context.applicationContext
        ensureRestored(app)
        val settings = OtaSettings(app)
        lock.withLock {
            val release = _state.value.releaseOrNull() ?: settings.targetRelease ?: _feed.value?.release ?: return@withLock
            if (OtaPolicy.availability(release, installedCode, Build.VERSION.SDK_INT) != OtaPolicy.Availability.AVAILABLE) return@withLock
            startDownloadLocked(app, settings, release, auto = false)
        }
    }

    suspend fun cancelDownload(context: Context) {
        val app = context.applicationContext
        ensureRestored(app)
        val settings = OtaSettings(app)
        lock.withLock {
            val target = settings.targetRelease
            pollJob?.cancel()
            discardDownloadLocked(app, settings, target)
            if (target != null) log(settings, target, "cancelled", "Download cancelled")
            _state.value = if (target != null) OtaState.Available(target) else OtaState.Idle
        }
    }

    /** Verifies the downloaded APK once more and hands it to Android's installer. Android asks for the final confirmation. */
    suspend fun install(context: Context) {
        val app = context.applicationContext
        ensureRestored(app)
        val settings = OtaSettings(app)
        lock.withLock { installLocked(app, settings) }
    }

    /** Intent that opens Android's "install unknown apps" screen for JARVIS. */
    fun installPermissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // ---- Callbacks from receivers --------------------------------------------------------------------------

    /** DownloadManager says a download finished (possibly while JARVIS was closed). [done] releases the receiver. */
    fun onDownloadComplete(context: Context, downloadId: Long, done: () -> Unit) {
        val app = context.applicationContext
        scope.launch {
            try {
                ensureRestored(app)
                val settings = OtaSettings(app)
                lock.withLock {
                    if (settings.downloadId != downloadId) return@withLock
                    pollOnceLocked(app, settings)
                }
            } finally {
                done()
            }
        }
    }

    /** Android needs the person to confirm the installation. */
    fun onConfirmationNeeded(context: Context, confirmation: Intent) {
        val app = context.applicationContext
        confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(confirmation) }
        if (!uiVisible) OtaNotifier.confirmInstall(app, Intent(confirmation))
    }

    /** Final install result reported by PackageInstaller (other than "needs confirmation"). */
    fun onInstallStatus(context: Context, status: Int, message: String?) {
        val app = context.applicationContext
        scope.launch {
            ensureRestored(app)
            val settings = OtaSettings(app)
            lock.withLock {
                OtaNotifier.clearInstallPrompts(app)
                val release = settings.targetRelease
                when (status) {
                    PackageInstaller.STATUS_SUCCESS -> {
                        // The updated app starts in a new process; onPackageReplaced records the result there.
                    }
                    PackageInstaller.STATUS_FAILURE_ABORTED -> {
                        settings.pendingInstallCode = -1L
                        log(settings, release, "cancelled", "Installation was cancelled")
                        _state.value = if (release != null && isReadyFile(app, settings, release)) OtaState.Ready(release)
                        else release?.let { OtaState.Available(it) } ?: OtaState.Idle
                    }
                    else -> {
                        settings.pendingInstallCode = -1L
                        val text = describeInstallFailure(status, message)
                        log(settings, release, "failed", text)
                        _state.value = OtaState.Failed(text, release, canRetry = true)
                        if (!uiVisible) OtaNotifier.failed(app, text)
                    }
                }
            }
        }
    }

    /** Runs in the freshly updated process: confirms the install happened, records it and clears the downloaded file. */
    fun onPackageReplaced(context: Context) {
        val app = context.applicationContext
        val settings = OtaSettings(app)
        val pending = settings.pendingInstallCode
        val target = settings.targetRelease
        if (pending > 0L && pending == installedCode) {
            val release = target
            log(settings, release, "installed", "Updated in place; app data kept")
        }
        cleanupAfterInstall(app, settings)
        OtaNotifier.clearInstallPrompts(app)
    }

    // ---- Restore --------------------------------------------------------------------------------------------

    private suspend fun ensureRestored(app: Context) {
        if (restored) return
        lock.withLock {
            if (restored) return@withLock
            restored = true
            restoreLocked(app, OtaSettings(app))
        }
    }

    private suspend fun restoreLocked(app: Context, settings: OtaSettings) {
        var target = settings.targetRelease
        if (target != null && target.versionCode <= installedCode) {
            // That build is already installed (or older): nothing left to do but tidy up.
            cleanupAfterInstall(app, settings)
            target = null
        }
        settings.cachedFeed(settings.branch)?.let { (body, _) ->
            try {
                _feed.value = OtaFeedParser.parse(body)
            } catch (_: Exception) {
                // A cached feed that no longer validates is ignored; the next check replaces it.
            }
        }
        if (target != null) {
            val id = settings.downloadId
            if (id >= 0L) {
                val status = queryDownload(app, id)
                if (status != null) {
                    _state.value = OtaState.Downloading(target, status.bytes, totalOf(status, target), 0.0, noteFor(status))
                    pollOnceLocked(app, settings)
                    if (settings.downloadId >= 0L) startPolling(app)
                    return
                }
                settings.downloadId = -1L
            }
            if (isReadyFile(app, settings, target)) {
                _state.value = OtaState.Ready(target)
                return
            }
            _state.value = OtaState.Available(target)
            return
        }
        val feedRelease = _feed.value?.release
        if (OtaPolicy.availability(feedRelease, installedCode, Build.VERSION.SDK_INT) == OtaPolicy.Availability.AVAILABLE) {
            _state.value = OtaState.Available(feedRelease!!)
        }
    }

    // ---- Feed -----------------------------------------------------------------------------------------------

    private suspend fun fetchFeed(settings: OtaSettings): OtaFeed = withContext(Dispatchers.IO) {
        var branch = settings.branch
        for (attempt in 0..1) {
            val cached = settings.cachedFeed(branch)
            val builder = Request.Builder()
                .url(SupabaseConfig.url + "/functions/v1/ota-updates?branch=" + URLEncoder.encode(branch, "UTF-8"))
                .header("Accept", "application/json")
                .get()
            if (SupabaseConfig.publishableKey.isNotBlank()) builder.header("apikey", SupabaseConfig.publishableKey)
            val etag = cached?.second
            if (etag != null) builder.header("If-None-Match", etag)
            http.newCall(builder.build()).execute().use { response ->
                when {
                    response.code == 304 && cached != null -> return@withContext OtaFeedParser.parse(cached.first)
                    response.isSuccessful -> {
                        val body = response.body?.string() ?: throw IOException("The update server sent an empty response.")
                        val parsed = OtaFeedParser.parse(body)
                        settings.cacheFeed(branch, body, response.header("ETag"))
                        return@withContext parsed
                    }
                    response.code == 404 && attempt == 0 && branch != OtaConfig.DEFAULT_BRANCH -> {
                        // The chosen branch is no longer offered; fall back to the default branch.
                        branch = OtaConfig.DEFAULT_BRANCH
                        settings.branch = branch
                    }
                    else -> throw IOException("Update server returned HTTP " + response.code + ".")
                }
            }
        }
        throw IOException("The update server did not return a usable feed.")
    }

    private suspend fun applyFeedLocked(app: Context, settings: OtaSettings, feed: OtaFeed, manual: Boolean) {
        val release = feed.release
        when (OtaPolicy.availability(release, installedCode, Build.VERSION.SDK_INT)) {
            OtaPolicy.Availability.NO_RELEASE ->
                _state.value = OtaState.UpToDate("No updates have been published for " + feed.branch + " yet.")
            OtaPolicy.Availability.UP_TO_DATE ->
                _state.value = OtaState.UpToDate(
                    if (feed.branch == feed.defaultBranch) "JARVIS is up to date."
                    else "No newer build than the installed one on " + feed.branch + "."
                )
            OtaPolicy.Availability.NEEDS_NEWER_ANDROID ->
                _state.value = OtaState.Failed("JARVIS ${release?.versionName} needs a newer version of Android.", release, canRetry = false)
            OtaPolicy.Availability.AVAILABLE -> {
                val offered = release ?: return
                val target = settings.targetRelease
                if (!OtaPolicy.isSameBuild(target, offered)) {
                    // A newer build supersedes any older partial or finished download.
                    pollJob?.cancel()
                    discardDownloadLocked(app, settings, target)
                    settings.targetRelease = offered
                }
                if (isReadyFile(app, settings, offered)) {
                    _state.value = OtaState.Ready(offered)
                    return
                }
                val active = settings.downloadId >= 0L && OtaPolicy.isSameBuild(settings.targetRelease, offered)
                if (active) {
                    startPolling(app)
                    return
                }
                _state.value = OtaState.Available(offered)
                val autoStart = OtaPolicy.shouldAutoDownload(
                    settings.autoDownload, OtaPolicy.Availability.AVAILABLE, false, settings.downloadAttempts(offered.versionCode)
                )
                if (!autoStart && !manual && OtaPolicy.notifyAllowed(settings.lastNotifiedCode, offered)) {
                    OtaNotifier.available(app, offered)
                    settings.lastNotifiedCode = offered.versionCode
                    log(settings, offered, "available", "Update announced")
                }
                if (autoStart) startDownloadLocked(app, settings, offered, auto = true)
            }
        }
    }

    // ---- Download -------------------------------------------------------------------------------------------

    private suspend fun startDownloadLocked(app: Context, settings: OtaSettings, release: OtaRelease, auto: Boolean) {
        if (isReadyFile(app, settings, release)) {
            _state.value = OtaState.Ready(release)
            return
        }
        val manager = downloadManager(app)
        val existingId = settings.downloadId
        if (existingId >= 0L && OtaPolicy.isSameBuild(settings.targetRelease, release)) {
            val status = queryDownload(app, existingId)
            if (status != null && (status.status == DownloadManager.STATUS_RUNNING ||
                    status.status == DownloadManager.STATUS_PENDING || status.status == DownloadManager.STATUS_PAUSED)
            ) {
                // The same build is already downloading: never start a second copy.
                _state.value = OtaState.Downloading(release, status.bytes, totalOf(status, release), 0.0, noteFor(status))
                startPolling(app)
                return
            }
        }
        if (existingId >= 0L) runCatching { manager.remove(existingId) }
        settings.downloadId = -1L

        val dir = otaDir(app)
        if (dir == null) {
            _state.value = OtaState.Failed("Storage isn't available for the update right now.", release, canRetry = true)
            return
        }
        val destination = File(dir, release.assetName)
        if (destination.exists()) destination.delete()
        if (dir.usableSpace < release.sizeBytes + SPACE_MARGIN_BYTES) {
            _state.value = OtaState.Failed(
                "Not enough free storage. JARVIS needs about " + OtaFormat.bytes(release.sizeBytes + SPACE_MARGIN_BYTES) + " free.",
                release, canRetry = true
            )
            return
        }
        val networks = if (settings.wifiOnly) DownloadManager.Request.NETWORK_WIFI
        else DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE
        val id = try {
            manager.enqueue(
                DownloadManager.Request(Uri.parse(release.apkUrl))
                    .setTitle("JARVIS " + release.versionName)
                    .setDescription("Software update")
                    .setMimeType("application/vnd.android.package-archive")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setAllowedNetworkTypes(networks)
                    .setAllowedOverRoaming(!settings.wifiOnly)
                    .setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, "$OTA_DIR/${release.assetName}")
            )
        } catch (error: Exception) {
            _state.value = OtaState.Failed("Android could not start the download: " + (error.message ?: "unknown error"), release, canRetry = true)
            return
        }
        settings.targetRelease = release
        settings.downloadId = id
        settings.noteDownloadAttempt(release.versionCode)
        meter.reset()
        _state.value = OtaState.Downloading(release, 0L, release.sizeBytes, 0.0, if (settings.wifiOnly) "Waiting for Wi-Fi…" else "Starting download…")
        log(settings, release, "download_started", if (auto) "Automatic download" else "Manual download")
        startPolling(app)
    }

    private fun startPolling(app: Context) {
        pollJob?.cancel()
        meter.reset()
        pollJob = scope.launch {
            val settings = OtaSettings(app)
            while (isActive) {
                val finished = lock.withLock { pollOnceLocked(app, settings) }
                if (finished) break
                delay(if (uiVisible) 500L else 4000L)
            }
        }
    }

    /** Reads the DownloadManager row once and moves the state forward. Returns true when there is nothing more to wait for. */
    private suspend fun pollOnceLocked(app: Context, settings: OtaSettings): Boolean {
        val id = settings.downloadId
        val target = settings.targetRelease
        if (id < 0L || target == null) return true
        val status = queryDownload(app, id)
        if (status == null) {
            // The download was removed from outside the app (for example from system Downloads).
            settings.downloadId = -1L
            if (_state.value is OtaState.Downloading) _state.value = OtaState.Available(target)
            return true
        }
        return when (status.status) {
            DownloadManager.STATUS_SUCCESSFUL -> {
                finishDownloadLocked(app, settings, target)
                true
            }
            DownloadManager.STATUS_FAILED -> {
                val text = describeDownloadFailure(status.reason)
                runCatching { downloadManager(app).remove(id) }
                settings.downloadId = -1L
                log(settings, target, "failed", text)
                _state.value = OtaState.Failed(text, target, canRetry = true)
                true
            }
            else -> {
                val speed = meter.sample(status.bytes, SystemClock.elapsedRealtime())
                _state.value = OtaState.Downloading(target, status.bytes, totalOf(status, target), speed, noteFor(status))
                false
            }
        }
    }

    private suspend fun finishDownloadLocked(app: Context, settings: OtaSettings, release: OtaRelease) {
        _state.value = OtaState.Verifying(release)
        val file = apkFile(app, release)
        val problem = withContext(Dispatchers.IO) {
            if (file == null) "The downloaded file is missing."
            else OtaVerifier.verifyFile(file, release) ?: OtaVerifier.verifyArchive(app, file, release)
        }
        // The download record is deliberately not removed on success: DownloadManager.remove() also deletes the file.
        settings.downloadId = -1L
        if (problem != null) {
            file?.delete()
            settings.exhaustAutoAttempts(release.versionCode)
            log(settings, release, "failed", problem)
            _state.value = OtaState.Failed(problem, release, canRetry = true)
            if (!uiVisible) OtaNotifier.failed(app, problem)
            return
        }
        settings.clearAttempts(release.versionCode)
        log(settings, release, "downloaded", "Downloaded and verified")
        _state.value = OtaState.Ready(release)
        if (!uiVisible) OtaNotifier.ready(app, release)
        settings.lastNotifiedCode = maxOf(settings.lastNotifiedCode, release.versionCode)
        if (!settings.askBeforeInstall) installLocked(app, settings)
    }

    private fun discardDownloadLocked(app: Context, settings: OtaSettings, target: OtaRelease?) {
        val id = settings.downloadId
        if (id >= 0L) runCatching { downloadManager(app).remove(id) }
        settings.downloadId = -1L
        if (target != null) apkFile(app, target)?.let { if (it.exists()) it.delete() }
    }

    // ---- Install --------------------------------------------------------------------------------------------

    private suspend fun installLocked(app: Context, settings: OtaSettings) {
        val release = settings.targetRelease ?: return
        if (OtaPolicy.availability(release, installedCode, Build.VERSION.SDK_INT) != OtaPolicy.Availability.AVAILABLE) return
        if (!app.packageManager.canRequestPackageInstalls()) {
            _state.value = OtaState.NeedsInstallPermission(release)
            if (uiVisible) runCatching { app.startActivity(installPermissionIntent(app)) }
            return
        }
        val file = apkFile(app, release)
        if (file == null || !file.isFile) {
            _state.value = OtaState.Available(release)
            return
        }
        _state.value = OtaState.Installing(release)
        val problem = withContext(Dispatchers.IO) {
            OtaVerifier.verifyFile(file, release) ?: OtaVerifier.verifyArchive(app, file, release)
        }
        if (problem != null) {
            file.delete()
            log(settings, release, "failed", problem)
            _state.value = OtaState.Failed(problem, release, canRetry = true)
            return
        }
        when (val outcome = withContext(Dispatchers.IO) { OtaInstaller.commit(app, file, release, settings.askBeforeInstall) }) {
            OtaInstaller.Outcome.Committed -> settings.pendingInstallCode = release.versionCode
            is OtaInstaller.Outcome.Failed -> {
                log(settings, release, "failed", outcome.message)
                _state.value = OtaState.Failed(outcome.message, release, canRetry = true)
            }
        }
    }

    // ---- Helpers --------------------------------------------------------------------------------------------

    private data class DownloadStatus(val status: Int, val reason: Int, val bytes: Long, val total: Long)

    private fun downloadManager(app: Context): DownloadManager =
        app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    private fun queryDownload(app: Context, id: Long): DownloadStatus? {
        val cursor = downloadManager(app).query(DownloadManager.Query().setFilterById(id)) ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            return DownloadStatus(
                status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
                bytes = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            )
        }
    }

    private fun totalOf(status: DownloadStatus, release: OtaRelease): Long = if (status.total > 0L) status.total else release.sizeBytes

    private fun noteFor(status: DownloadStatus): String? = when (status.status) {
        DownloadManager.STATUS_PENDING -> "Starting download…"
        DownloadManager.STATUS_PAUSED -> when (status.reason) {
            DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Waiting for Wi-Fi…"
            DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Waiting for a network connection…"
            DownloadManager.PAUSED_WAITING_TO_RETRY -> "Connection interrupted — resuming…"
            else -> "Paused"
        }
        else -> null
    }

    private fun describeDownloadFailure(reason: Int): String = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "There isn't enough free storage for the update."
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "Storage isn't available right now."
        DownloadManager.ERROR_CANNOT_RESUME -> "The interrupted download couldn't be resumed. Tap Retry to restart it."
        DownloadManager.ERROR_HTTP_DATA_ERROR, DownloadManager.ERROR_TOO_MANY_REDIRECTS ->
            "The connection was interrupted. Tap Retry."
        DownloadManager.ERROR_FILE_ERROR, DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "The update file couldn't be saved."
        in 400..599 -> "GitHub returned an error (HTTP $reason). Try again later."
        else -> "The download failed. Tap Retry."
    }

    private fun describeInstallFailure(status: Int, message: String?): String {
        val detail = message?.takeIf { it.isNotBlank() }?.take(140)
        val base = when (status) {
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "Android blocked the installation."
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "The update conflicts with the installed app."
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "The update isn't compatible with this phone or the installed app."
            PackageInstaller.STATUS_FAILURE_INVALID -> "Android rejected the update file."
            PackageInstaller.STATUS_FAILURE_STORAGE -> "There isn't enough storage to install the update."
            else -> "Android couldn't install the update."
        }
        return if (detail == null) base else "$base ($detail)"
    }

    private fun friendlyNetworkError(error: Exception): String = when (error) {
        is OtaFormatException -> error.message ?: "The update feed was not valid."
        is java.net.UnknownHostException, is java.net.SocketTimeoutException, is java.net.ConnectException ->
            "Couldn't reach the update server. Check your connection and try again."
        else -> error.message?.take(140) ?: "Couldn't check for updates."
    }

    private fun OtaState.releaseOrNull(): OtaRelease? = when (this) {
        is OtaState.Available -> release
        is OtaState.Downloading -> release
        is OtaState.Verifying -> release
        is OtaState.Ready -> release
        is OtaState.NeedsInstallPermission -> release
        is OtaState.Installing -> release
        is OtaState.Failed -> release
        else -> null
    }

    private fun otaDir(app: Context): File? {
        val base = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
        val dir = File(base, OTA_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        return dir
    }

    private fun apkFile(app: Context, release: OtaRelease): File? = otaDir(app)?.let { File(it, release.assetName) }

    /** A finished download has the exact published size and no download is still writing it. */
    private fun isReadyFile(app: Context, settings: OtaSettings, release: OtaRelease): Boolean {
        if (settings.downloadId >= 0L) return false
        val file = apkFile(app, release) ?: return false
        return file.isFile && file.length() == release.sizeBytes
    }

    private fun cleanupAfterInstall(app: Context, settings: OtaSettings) {
        val id = settings.downloadId
        if (id >= 0L) runCatching { downloadManager(app).remove(id) }
        otaDir(app)?.listFiles()?.forEach { runCatching { it.delete() } }
        settings.downloadId = -1L
        settings.pendingInstallCode = -1L
        settings.targetRelease?.let { settings.clearAttempts(it.versionCode) }
        settings.targetRelease = null
    }

    private fun log(settings: OtaSettings, release: OtaRelease?, event: String, detail: String) {
        settings.addHistory(
            OtaHistoryEntry(
                System.currentTimeMillis(), release?.versionCode ?: 0L, release?.versionName ?: "",
                release?.branch ?: settings.branch, event, detail
            )
        )
    }
}
