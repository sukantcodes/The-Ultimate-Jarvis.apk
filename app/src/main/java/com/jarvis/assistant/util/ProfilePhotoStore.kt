package com.jarvis.assistant.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Copies a photo chosen with the Android Photo Picker into the app's private storage.
 *
 * Photo Picker grants are temporary, so saving the picked content URI would leave the profile card blank after a
 * restart. A small private copy keeps the existing user card working without any storage permission.
 */
object ProfilePhotoStore {
    private const val DIR = "profile"
    private const val PREFIX = "avatar_"
    private const val TARGET_EDGE = 640

    /** Returns a file URI string for the new copy, or null when the image cannot be read. */
    suspend fun importPicked(context: Context, source: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val resolver = context.applicationContext.contentResolver

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= TARGET_EDGE) sample *= 2
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = resolver.openInputStream(source)?.use {
                BitmapFactory.decodeStream(it, null, decodeOptions)
            } ?: return@withContext null

            val rotation = resolver.openInputStream(source)?.use { exifRotation(it) } ?: 0
            val upright = if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            } else decoded

            val edge = minOf(upright.width, upright.height)
            val square = Bitmap.createBitmap(upright, (upright.width - edge) / 2, (upright.height - edge) / 2, edge, edge)
            val finalBitmap =
                if (edge > TARGET_EDGE) Bitmap.createScaledBitmap(square, TARGET_EDGE, TARGET_EDGE, true) else square

            val dir = File(context.applicationContext.filesDir, DIR).apply { mkdirs() }
            val out = File(dir, "$PREFIX${System.currentTimeMillis()}.jpg")
            FileOutputStream(out).use { finalBitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            Uri.fromFile(out).toString()
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    /** Removes avatar copies that are no longer the saved one, so replaced photos do not pile up. */
    fun cleanup(context: Context, keepUris: Collection<String>) {
        try {
            val dir = File(context.applicationContext.filesDir, DIR)
            val keep = keepUris.mapNotNull { Uri.parse(it).path }.toSet()
            dir.listFiles()?.forEach { file ->
                if (file.name.startsWith(PREFIX) && file.absolutePath !in keep) file.delete()
            }
        } catch (_: Exception) {
        }
    }

    private fun exifRotation(stream: java.io.InputStream): Int = try {
        when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (_: Exception) {
        0
    }
}
