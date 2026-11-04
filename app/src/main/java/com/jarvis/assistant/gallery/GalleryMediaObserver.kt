package com.jarvis.assistant.gallery

import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore

/** Watches MediaStore only while the gallery screen is visible. */
class GalleryMediaObserver(
    private val resolver: ContentResolver,
    private val onChanged: () -> Unit
) {
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            onChanged()
        }
    }

    fun register() {
        runCatching {
            resolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                observer
            )
            resolver.registerContentObserver(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                true,
                observer
            )
        }
    }

    fun unregister() {
        runCatching { resolver.unregisterContentObserver(observer) }
    }
}
