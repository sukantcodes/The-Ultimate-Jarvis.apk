package com.jarvis.assistant.gallery

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Refreshes a bounded, local metadata cache. It never reads file contents or uploads media. */
class GalleryRefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(
            GalleryRefreshScheduler.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        if (!prefs.getBoolean(GalleryRefreshScheduler.KEY_BACKGROUND_REFRESH, false)) {
            return Result.success()
        }
        if (!GalleryPermissionManager.hasAnyMediaAccess(applicationContext)) {
            return Result.success()
        }

        return try {
            val items = MediaStoreGalleryRepository(applicationContext.contentResolver)
                .loadRecent(500)
            val rows = items.map { item ->
                GalleryIndexEntity(
                    mediaUri = item.uri.toString(),
                    displayName = item.displayName,
                    mimeType = item.mimeType,
                    dateAddedSeconds = item.dateAddedSeconds,
                    sizeBytes = item.sizeBytes,
                    mediaType = item.type.name,
                    durationMs = item.durationMs
                )
            }
            if (rows.isNotEmpty()) {
                GalleryIndexDatabase.getInstance(applicationContext).galleryDao().upsert(rows)
            }
            Result.success()
        } catch (_: SecurityException) {
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
