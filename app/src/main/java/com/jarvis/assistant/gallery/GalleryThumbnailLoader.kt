package com.jarvis.assistant.gallery

import android.content.ContentResolver
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import android.widget.ImageView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Loads small on-device previews without decoding full-resolution media into the list. */
class GalleryThumbnailLoader(private val resolver: ContentResolver) {
    private val executor: ExecutorService = Executors.newFixedThreadPool(2)

    fun load(item: GalleryMediaItem, target: ImageView) {
        target.tag = item.uri.toString()
        target.setImageDrawable(null)
        executor.execute {
            val bitmap = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    resolver.loadThumbnail(item.uri, Size(240, 180), null)
                } else {
                    @Suppress("DEPRECATION")
                    if (item.type == GalleryMediaType.VIDEO) {
                        MediaStore.Video.Thumbnails.getThumbnail(
                            resolver, item.id, MediaStore.Video.Thumbnails.MINI_KIND, null
                        )
                    } else {
                        MediaStore.Images.Thumbnails.getThumbnail(
                            resolver, item.id, MediaStore.Images.Thumbnails.MINI_KIND, null
                        )
                    }
                }
            }.getOrNull()
            target.post {
                if (target.tag == item.uri.toString()) {
                    if (bitmap != null) target.setImageBitmap(bitmap)
                } else {
                    bitmap?.recycle()
                }
            }
        }
    }

    fun shutdown() {
        executor.shutdownNow()
    }
}
