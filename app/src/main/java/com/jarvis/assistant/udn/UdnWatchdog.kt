package com.jarvis.assistant.udn

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jarvis.assistant.auth.AuthManager
import com.jarvis.assistant.auth.SupabaseConfig
import java.util.concurrent.TimeUnit

/**
 * Safety net for the network link: every ~15 minutes (Android's minimum) it re-checks membership and restarts the
 * link service if the system stopped it. Android may delay or refuse background starts (battery optimisation,
 * vendor task killers), so this improves reliability but cannot make a stopped phone reachable.
 */
class UdnWatchdog(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!SupabaseConfig.isConfigured || !AuthManager.isSignedIn) return Result.success()
        val settings = UdnSettings.get(applicationContext)
        if (settings.deviceId == null || !settings.linkEnabled) return Result.success()
        val mgr = UdnManager.get(applicationContext)
        mgr.ensureJoined()
        if (mgr.state.value.authorized) {
            UdnLinkService.start(applicationContext)
            mgr.heartbeat()
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "udn_watchdog"

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<UdnWatchdog>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            runCatching { WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req) }
        }

        fun cancel(context: Context) { runCatching { WorkManager.getInstance(context).cancelUniqueWork(NAME) } }
    }
}
