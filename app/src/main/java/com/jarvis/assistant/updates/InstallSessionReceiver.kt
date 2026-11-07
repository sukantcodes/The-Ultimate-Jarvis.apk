package com.jarvis.assistant.updates

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast

class InstallSessionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_STATUS) return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirmation != null) {
                    confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirmation) }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Toast.makeText(context, "JARVIS update installed. Restart JARVIS when prompted.", Toast.LENGTH_LONG).show()
            }
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?.take(180) ?: "Android could not install this update."
                Toast.makeText(context, "JARVIS update not installed: " + message, Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        const val ACTION_STATUS = "com.jarvis.assistant.action.UPDATE_INSTALL_STATUS"
    }
}
