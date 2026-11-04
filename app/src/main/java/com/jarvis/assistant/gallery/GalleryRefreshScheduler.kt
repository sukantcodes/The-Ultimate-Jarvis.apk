package com.jarvis.assistant.gallery

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

object GalleryRefreshScheduler {
    const val PREFS_NAME = "jarvis_gallery"
    const val KEY_BACKGROUND_REFRESH = "gallery_background_refresh_enabled"
    private const val PERIODIC_NAME = "jarvis_gallery_periodic_refresh"
    private const val CHANGE_NAME = "jarvis_gallery_change_refresh"

    private fun constraints() = Constraints.Builder()
        .setRequiresBatteryNotLow(true)
        .setRequiresStorageNotLow(true)
        .build()

    fun setEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_BACKGROUND_REFRESH, enabled).apply()
        val manager = WorkManager.getInstance(context.applicationContext)
        if (!enabled) {
            manager.cancelUniqueWork(PERIODIC_NAME)
            manager.cancelUniqueWork(CHANGE_NAME)
            return
        }
        val periodic = PeriodicWorkRequestBuilder<GalleryRefreshWorker>(12, TimeUnit.HOURS)
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .build()
        manager.enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodic
        )
        enqueueChangeRefresh(context)
    }

    fun enqueueChangeRefresh(context: Context) {
        val enabled = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_BACKGROUND_REFRESH, false)
        if (!enabled) return
        val request = OneTimeWorkRequestBuilder<GalleryRefreshWorker>()
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            CHANGE_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }
}
