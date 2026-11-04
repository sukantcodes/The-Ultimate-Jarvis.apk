package com.jarvis.assistant.gallery

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Locally cached media metadata only; the original media remains in MediaStore. */
@Entity(
    tableName = "gallery_media",
    indices = [Index(value = ["mediaUri"], unique = true), Index(value = ["dateAddedSeconds"])]
)
data class GalleryIndexEntity(
    @PrimaryKey val mediaUri: String,
    val displayName: String,
    val mimeType: String?,
    val dateAddedSeconds: Long,
    val sizeBytes: Long,
    val mediaType: String,
    val durationMs: Long?
)
