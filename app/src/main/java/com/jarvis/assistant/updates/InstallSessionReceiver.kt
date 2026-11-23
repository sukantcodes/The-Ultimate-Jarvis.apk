package com.jarvis.assistant.updates

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/** Receives PackageInstaller's progress for an update started by [OtaInstaller] and passes it to [OtaManager]. */
class InstallSessionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_STATUS) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirmation = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            if (confirmation != null) OtaManager.onConfirmationNeeded(context.applicationContext, confirmation)
            return
        }
        OtaManager.onInstallStatus(
            context.applicationContext, status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        )
    }

    companion object {
        const val ACTION_STATUS = "com.jarvis.assistant.action.UPDATE_INSTALL_STATUS"
    }
}
