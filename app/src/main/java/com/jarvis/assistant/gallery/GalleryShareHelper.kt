package com.jarvis.assistant.gallery

import android.app.Activity
import android.content.ClipData
import android.content.Intent

/** Shares only media explicitly selected by the user through Android's system Sharesheet. */
object GalleryShareHelper {
    fun share(activity: Activity, items: List<GalleryMediaItem>) {
        if (items.isEmpty()) return
        val uris = items.map { it.uri }
        val shareIntent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = items.first().mimeType ?: "*/*"
                putExtra(Intent.EXTRA_STREAM, uris.first())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        }
        val clip = ClipData.newUri(activity.contentResolver, "Selected gallery media", uris.first())
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        shareIntent.clipData = clip
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        activity.startActivity(Intent.createChooser(shareIntent, "Share media"))
    }
}
