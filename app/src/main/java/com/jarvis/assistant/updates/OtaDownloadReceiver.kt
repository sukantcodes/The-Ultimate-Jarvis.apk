package com.jarvis.assistant.updates

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * DownloadManager announces a finished download even when JARVIS was closed. The APK is verified and the person is
 * told it is ready; nothing is ever installed from here without the approval setting allowing it.
 */
class OtaDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (id < 0L) return
        val pending = goAsync()
        OtaManager.onDownloadComplete(context.applicationContext, id) { pending.finish() }
    }
}
