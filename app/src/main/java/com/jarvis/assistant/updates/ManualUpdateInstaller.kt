package com.jarvis.assistant.updates

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object ManualUpdateInstaller {
    private const val MAX_APK_BYTES = 250L * 1024L * 1024L
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.MINUTES)
        .retryOnConnectionFailure(true)
        .build()

    sealed class State {
        data class Message(val text: String, val progress: Int? = null, val busy: Boolean = true) : State()
        data class Failure(val text: String) : State()
        data object InstallerLaunched : State()
    }

    suspend fun install(context: Context, onState: (State) -> Unit) {
        val app = context.applicationContext
        suspend fun publish(state: State) = withContext(Dispatchers.Main) { onState(state) }
        withContext(Dispatchers.IO) {
            var apkFile: File? = null
            try {
                publish(State.Message("Checking the latest JARVIS release…"))
                check(com.jarvis.assistant.auth.SupabaseConfig.isConfigured) {
                    "JARVIS update service is not configured in this build."
                }
                val base = com.jarvis.assistant.auth.SupabaseConfig.url.trimEnd('/')
                val expectedHost = Uri.parse(base).host
                    ?: throw IllegalStateException("Update server configuration is invalid.")
                val endpoint = base + "/functions/v1/app-updates?version_code=" +
                    com.jarvis.assistant.BuildConfig.VERSION_CODE + "&channel=stable"
                val request = Request.Builder().url(endpoint)
                    .header("apikey", com.jarvis.assistant.auth.SupabaseConfig.publishableKey)
                    .header("Accept", "application/json").get().build()

                val payload = http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("Update server returned HTTP " + response.code + ".")
                    JSONObject(response.body?.string() ?: throw IllegalStateException("Empty update response."))
                }
                if (!payload.optBoolean("update_available", false)) {
                    publish(State.Failure("JARVIS is already up to date."))
                    return@withContext
                }

                val release = payload.optJSONObject("release")
                    ?: throw IllegalStateException("Release details are missing.")
                val versionCode = release.optLong("version_code", -1L)
                val versionName = release.optString("version_name", "").take(40)
                val minimumSupported = release.optLong("min_supported_version_code", 1L)
                val notes = release.optString("release_notes", "").take(4000)
                val apkUrl = release.optString("apk_url", "")
                val expectedHash = release.optString("sha256", "").uppercase()

                check(versionCode > com.jarvis.assistant.BuildConfig.VERSION_CODE.toLong()) {
                    "The published release is not newer than this installation."
                }
                check(minimumSupported > 0L && minimumSupported <= versionCode && versionName.isNotBlank()) {
                    "Release version metadata is invalid."
                }
                val uri = Uri.parse(apkUrl)
                check(uri.scheme == "https" && uri.host == expectedHost && uri.userInfo == null && uri.port == -1 &&
                    uri.encodedPath.orEmpty().startsWith("/storage/v1/object/public/jarvis-releases/")) {
                    "The APK download address is not trusted."
                }
                check(expectedHash.matches(Regex("[A-F0-9]{64}"))) {
                    "The release checksum is missing or invalid."
                }

                publish(State.Message("Downloading JARVIS " + versionName + "…", 0))
                apkFile = File(app.cacheDir, "jarvis-update-" + versionCode + ".apk")
                val downloadRequest = Request.Builder().url(apkUrl)
                    .header("Accept", "application/vnd.android.package-archive").get().build()
                http.newCall(downloadRequest).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("APK download failed (HTTP " + response.code + ").")
                    val body = response.body ?: throw IllegalStateException("The APK download was empty.")
                    val declaredLength = body.contentLength()
                    check(declaredLength <= MAX_APK_BYTES) { "The APK exceeds the allowed download size." }
                    var total = 0L
                    FileOutputStream(apkFile!!).use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                check(total <= MAX_APK_BYTES) { "The APK exceeds the allowed download size." }
                                output.write(buffer, 0, count)
                                if (declaredLength > 0L) {
                                    val percent = ((total * 100L) / declaredLength).toInt().coerceIn(0, 99)
                                    publish(State.Message("Downloading JARVIS " + versionName + "…", percent))
                                }
                            }
                        }
                        output.fd.sync()
                    }
                    check(total > 0L && (declaredLength <= 0L || total == declaredLength)) {
                        "The APK download was incomplete."
                    }
                }

                publish(State.Message("Verifying APK checksum…"))
                val actualHash = FileInputStream(apkFile!!).use { input ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                    digest.digest().joinToString("") { "%02X".format(it) }
                }
                check(actualHash == expectedHash) {
                    "APK checksum verification failed. Installation was blocked."
                }

                publish(State.Message("Verifying package, version and signing certificate…"))
                val archiveInfo = archivePackageInfo(app.packageManager, apkFile!!)
                    ?: throw IllegalStateException("Android could not read the downloaded APK.")
                check(archiveInfo.packageName == app.packageName) { "This APK is not a JARVIS update." }
                val archiveVersion = if (Build.VERSION.SDK_INT >= 28) archiveInfo.longVersionCode
                    else archiveInfo.versionCode.toLong()
                check(archiveVersion == versionCode &&
                    archiveVersion > com.jarvis.assistant.BuildConfig.VERSION_CODE.toLong()) {
                    "The APK version does not match the published release."
                }
                val installedInfo = installedPackageInfo(app.packageManager, app.packageName)
                check(certificateDigests(installedInfo).isNotEmpty() &&
                    certificateDigests(installedInfo) == certificateDigests(archiveInfo)) {
                    "Signing certificate mismatch. Android installation was blocked."
                }

                if (Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
                    apkFile!!.delete()
                    publish(State.Failure("Allow JARVIS to install updates in Android settings, then return and tap Install Update again."))
                    withContext(Dispatchers.Main) {
                        runCatching {
                            app.startActivity(
                                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + app.packageName))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                    return@withContext
                }

                publish(State.Message("Opening Android's installation confirmation…"))
                val packageInstaller = app.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(app.packageName)
                    setSize(apkFile!!.length())
                }
                val sessionId = packageInstaller.createSession(params)
                try {
                    packageInstaller.openSession(sessionId).use { session ->
                        FileInputStream(apkFile!!).use { input ->
                            session.openWrite("jarvis-update.apk", 0L, apkFile!!.length()).use { output ->
                                input.copyTo(output, 64 * 1024)
                                session.fsync(output)
                            }
                        }
                        val statusIntent = Intent(app, InstallSessionReceiver::class.java)
                            .setAction(InstallSessionReceiver.ACTION_STATUS)
                        val flags = PendingIntentFlags.mutableUpdateCurrent()
                        val pending = android.app.PendingIntent.getBroadcast(app, sessionId, statusIntent, flags)
                        session.commit(pending.intentSender)
                    }
                } catch (failure: Exception) {
                    runCatching { packageInstaller.abandonSession(sessionId) }
                    throw failure
                }
                apkFile!!.delete()
                publish(State.InstallerLaunched)
            } catch (error: Exception) {
                apkFile?.delete()
                publish(State.Failure(error.message ?: "Could not prepare this update. Please try again."))
            }
        }
    }

    private object PendingIntentFlags {
        fun mutableUpdateCurrent(): Int =
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) android.app.PendingIntent.FLAG_MUTABLE
                else android.app.PendingIntent.FLAG_IMMUTABLE)
    }

    @Suppress("DEPRECATION")
    private fun archivePackageInfo(pm: PackageManager, file: File): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
            else PackageManager.GET_SIGNATURES
        return pm.getPackageArchiveInfo(file.absolutePath, flags)
    }

    @Suppress("DEPRECATION")
    private fun installedPackageInfo(pm: PackageManager, packageName: String): PackageInfo {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
            else PackageManager.GET_SIGNATURES
        return pm.getPackageInfo(packageName, flags)
    }

    @Suppress("DEPRECATION")
    private fun certificateDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners
            else info.signatures
        return signatures.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { byte -> "%02X".format(byte) }
        }.toSet()
    }
}
