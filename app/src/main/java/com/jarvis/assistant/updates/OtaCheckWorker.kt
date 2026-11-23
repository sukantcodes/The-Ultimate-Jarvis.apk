package com.jarvis.assistant.updates

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Quiet background check, at most every six hours and only with a network. The feed answer is cached with an ETag,
 * so a check with nothing new costs one tiny request; the update itself downloads through Android's DownloadManager.
 */
class OtaCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        try {
            OtaManager.check(applicationContext, manual = false)
        } catch (_: Exception) {
            // A failed background check is simply tried again at the next interval.
        }
        return Result.success()
    }
}

object OtaScheduler {
    private const val WORK_NAME = "jarvis_ota_check"
    private const val INTERVAL_HOURS = 6L

    /** Schedules or cancels the periodic check to match the "Automatic checking" setting. Safe to call repeatedly. */
    fun ensure(context: Context) {
        val app = context.applicationContext
        runCatching {
            val manager = WorkManager.getInstance(app)
            if (OtaSettings(app).autoCheck) {
                val request = PeriodicWorkRequestBuilder<OtaCheckWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
                manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            } else {
                manager.cancelUniqueWork(WORK_NAME)
            }
        }
    }
}
