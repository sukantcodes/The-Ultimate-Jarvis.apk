package com.jarvis.assistant.ui.chat

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.jarvis.assistant.tools.image.ImageGenProtocol
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Saves a picture from the chat into the phone's gallery (Pictures/JARVIS) and shares it. */
object ImageExporter {
    private val saved = ConcurrentHashMap<String, Uri>()

    /** The gallery address of [file], saving it first if needed. Null when it could not be saved. */
    fun saveToGallery(context: Context, file: File): Uri? {
        saved[file.absolutePath]?.let { return it }
        if (!file.isFile) return null
        val resolver = context.applicationContext.contentResolver
        val mime = ImageGenProtocol.mimeFor(file.name)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/JARVIS")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "JARVIS").also { it.mkdirs() }
                put("_data", File(dir, file.name).path)
            }
        }
        val uri = try { resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) } catch (_: Exception) { null } ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: throw java.io.IOException("no stream")
            if (Build.VERSION.SDK_INT >= 29) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            }
            saved[file.absolutePath] = uri
            uri
        } catch (_: Exception) {
            try { resolver.delete(uri, null, null) } catch (_: Exception) { }
            null
        }
    }

    /** Opens the share sheet for a picture. Returns false when the picture could not be prepared. */
    fun share(context: Context, file: File): Boolean {
        val uri = saveToGallery(context, file) ?: return false
        val send = Intent(Intent.ACTION_SEND).apply {
            type = ImageGenProtocol.mimeFor(file.name)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            context.startActivity(Intent.createChooser(send, "Share picture").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Exception) { false }
    }
}
