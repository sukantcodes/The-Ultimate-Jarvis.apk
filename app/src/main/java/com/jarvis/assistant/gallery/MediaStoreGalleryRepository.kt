package com.jarvis.assistant.gallery

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import android.database.Cursor

enum class GalleryMediaType { IMAGE, VIDEO }

data class GalleryMediaItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val mimeType: String?,
    val dateAddedSeconds: Long,
    val durationMs: Long?,
    val sizeBytes: Long,
    val type: GalleryMediaType,
    val albumName: String?
)

/** Scoped-storage MediaStore reader. It never writes or deletes user media. */
class MediaStoreGalleryRepository(private val resolver: ContentResolver) {
    fun loadRecent(limit: Int = 150): List<GalleryMediaItem> {
        val safeLimit = limit.coerceIn(1, 500)
        return loadImages(safeLimit) + loadVideos(safeLimit)
            .sortedByDescending { it.dateAddedSeconds }
            .take(safeLimit)
    }

    fun searchByName(query: String, limit: Int = 150): List<GalleryMediaItem> {
        val clean = query.trim()
        if (clean.isEmpty()) return loadRecent(limit)
        val safeLimit = limit.coerceIn(1, 500)
        return (loadImages(safeLimit, clean) + loadVideos(safeLimit, clean))
            .sortedByDescending { it.dateAddedSeconds }
            .take(safeLimit)
    }

    private fun loadImages(limit: Int, nameQuery: String? = null): List<GalleryMediaItem> =
        query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            GalleryMediaType.IMAGE,
            limit,
            nameQuery,
            null
        )

    private fun loadVideos(limit: Int, nameQuery: String? = null): List<GalleryMediaItem> =
        query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            GalleryMediaType.VIDEO,
            limit,
            nameQuery,
            MediaStore.Video.Media.DURATION
        )

    private fun query(
        collection: Uri,
        type: GalleryMediaType,
        limit: Int,
        nameQuery: String?,
        durationColumn: String?
    ): List<GalleryMediaItem> {
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.SIZE)
            if (durationColumn != null) add(durationColumn)
            add("bucket_display_name")
        }.toTypedArray()
        val selection = if (nameQuery.isNullOrBlank()) null else
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        val args = if (selection == null) null else arrayOf("%$nameQuery%")
        val sort = "${MediaStore.MediaColumns.DATE_ADDED} DESC LIMIT ${limit.coerceIn(1, 500)}"
        val result = mutableListOf<GalleryMediaItem>()
        val cursor: Cursor = resolver.query(collection, projection, selection, args, sort) ?: return result
        cursor.use {
            val idIndex = it.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameIndex = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeIndex = it.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val dateIndex = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val sizeIndex = it.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val durationIndex = durationColumn?.let(it::getColumnIndex)
            val albumIndex = it.getColumnIndex("bucket_display_name")
            while (it.moveToNext()) {
                val id = it.getLong(idIndex)
                result += GalleryMediaItem(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    displayName = it.getString(nameIndex) ?: "Unnamed media",
                    mimeType = it.getString(mimeIndex),
                    dateAddedSeconds = it.getLong(dateIndex),
                    durationMs = if (durationIndex != null && durationIndex >= 0) it.getLong(durationIndex) else null,
                    sizeBytes = it.getLong(sizeIndex),
                    type = type,
                    albumName = if (albumIndex >= 0) it.getString(albumIndex) else null
                )
            }
        }
        return result
    }
}
