package com.jarvis.assistant.updates

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File
import java.io.FileInputStream

/**
 * Hands a verified APK to Android's PackageInstaller as an in-place update of this same app. The existing app data,
 * login, settings and conversations are kept by Android itself; JARVIS is never uninstalled.
 */
object OtaInstaller {
    sealed class Outcome {
        data object Committed : Outcome()
        data class Failed(val message: String) : Outcome()
    }

    fun commit(context: Context, file: File, release: OtaRelease, askUser: Boolean): Outcome {
        val app = context.applicationContext
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(
                    if (askUser) PackageInstaller.SessionParams.USER_ACTION_REQUIRED
                    else PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
                )
            }
        }
        var sessionId = -1
        return try {
            sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                FileInputStream(file).use { input ->
                    session.openWrite("jarvis-${release.versionCode}.apk", 0L, file.length()).use { output ->
                        input.copyTo(output, 256 * 1024)
                        session.fsync(output)
                    }
                }
                val statusIntent = Intent(app, InstallSessionReceiver::class.java).setAction(InstallSessionReceiver.ACTION_STATUS)
                // The installer fills in the result extras, so this PendingIntent must be mutable (and explicit).
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(app, sessionId, statusIntent, flags)
                session.commit(pending.intentSender)
            }
            Outcome.Committed
        } catch (error: Exception) {
            if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
            Outcome.Failed(error.message?.take(160) ?: "Android could not start the installation.")
        }
    }
}
