package com.jarvis.assistant.ui.studyboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Base64
import com.jarvis.assistant.provider.ChatAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * Safe, single-read handling of picked images, camera photos, and PDFs. Copies picked streams once into
 * app cache so transient Android content-URI permissions or HEIF/WebP/JPEG decoding never fail mid-read.
 */
object StudyMedia {

    private const val MAX_IMAGE_SIDE = 1800
    private const val MAX_RAW_IMAGE_BYTES = 40L * 1024 * 1024
    private const val MAX_PDF_BYTES = 60L * 1024 * 1024
    private const val MAX_INLINE_PDF_BYTES = 12L * 1024 * 1024

    class MediaException(message: String) : Exception(message)

    data class LoadedImage(
        val attachment: ChatAttachment.Image,
        val previewFile: File
    )

    suspend fun imageAttachment(context: Context, uri: Uri): ChatAttachment.Image =
        loadImage(context, uri).attachment

    /**
     * Copies [uri] once to local cache, decodes (with [ImageDecoder] fallback on API 28+ for HEIC/WebP), normalizes
     * rotation and resolution, saves a preview JPEG file, and encodes Base64 JPEG for the vision AI model.
     */
    suspend fun loadImage(context: Context, uri: Uri): LoadedImage = withContext(Dispatchers.IO) {
        val dir = captureDir(context)
        val rawTemp = File(dir, "raw_${System.currentTimeMillis()}.bin")
        val previewFile = File(dir, "preview_${System.currentTimeMillis()}.jpg")
        try {
            copyUriToFile(context, uri, rawTemp)
            val decoded = decodeFileSafely(rawTemp)
            var bmp = decoded.first
            val alreadyRotated = decoded.second
            val rotation = if (alreadyRotated) 0 else exifRotation(rawTemp)
            val longest = maxOf(bmp.width, bmp.height)
            if (longest > MAX_IMAGE_SIDE || rotation != 0) {
                val m = Matrix()
                if (longest > MAX_IMAGE_SIDE) {
                    val f = MAX_IMAGE_SIDE.toFloat() / longest
                    m.postScale(f, f)
                }
                if (rotation != 0) m.postRotate(rotation.toFloat())
                val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (out !== bmp) bmp.recycle()
                bmp = out
            }
            val bos = ByteArrayOutputStream()
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, 88, bos)) {
                throw MediaException("Could not encode the selected image.")
            }
            val jpegBytes = bos.toByteArray()
            previewFile.writeBytes(jpegBytes)
            bmp.recycle()
            val attachment = ChatAttachment.Image("image/jpeg", Base64.encodeToString(jpegBytes, Base64.NO_WRAP))
            LoadedImage(attachment, previewFile)
        } catch (e: MediaException) {
            previewFile.delete()
            throw e
        } catch (e: OutOfMemoryError) {
            previewFile.delete()
            throw MediaException("This image is too large for this phone's memory. Try a smaller photo.")
        } catch (e: SecurityException) {
            previewFile.delete()
            throw MediaException("Permission to read the selected image expired. Please pick it again.")
        } catch (e: Exception) {
            previewFile.delete()
            throw MediaException("Could not read the image: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            rawTemp.delete()
        }
    }

    private fun copyUriToFile(context: Context, uri: Uri, dest: File) {
        val resolver = context.contentResolver
        val stream = try {
            resolver.openInputStream(uri) ?: resolver.openAssetFileDescriptor(uri, "r")?.createInputStream()
        } catch (e: SecurityException) {
            throw MediaException("Android denied permission to open that image.")
        } catch (e: Exception) {
            throw MediaException("Could not open the selected image.")
        } ?: throw MediaException("Could not open the selected image.")

        var total = 0L
        stream.use { input ->
            dest.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_RAW_IMAGE_BYTES) {
                        throw MediaException("This image file is too large (over 40 MB).")
                    }
                    out.write(buf, 0, n)
                }
            }
        }
        if (total <= 0L) throw MediaException("The selected image file is empty.")
    }

    private fun decodeFileSafely(file: File): Pair<Bitmap, Boolean> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_SIDE * 2) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bmp = BitmapFactory.decodeFile(file.absolutePath, opts)
            if (bmp != null) return bmp to false
        }
        if (Build.VERSION.SDK_INT >= 28) {
            val bmp = runCatching {
                val src = ImageDecoder.createSource(file)
                ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val w = info.size.width
                    val h = info.size.height
                    val longest = maxOf(w, h)
                    if (longest > MAX_IMAGE_SIDE && longest > 0) {
                        val scale = MAX_IMAGE_SIDE.toFloat() / longest
                        decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                    }
                }
            }.getOrNull()
            if (bmp != null) return bmp to true
        }
        throw MediaException("This file could not be decoded as an image. Try a JPG, PNG, or screenshot.")
    }

    /** Safely decodes a cached preview bitmap scaled to [maxSide] px for confirmation dialogs. */
    fun decodePreviewBitmap(path: String?, maxSide: Int = 720): Bitmap? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        if (!file.exists()) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        }.getOrNull()
    }

    private fun exifRotation(file: File): Int = try {
        when (ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (_: Exception) { 0 }

    /** Copies a picked PDF into the app cache (streamed, size-capped) so it can be rendered page by page. */
    suspend fun importPdf(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "study_pdf").apply { mkdirs() }
        dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 6 * 3600_000L) it.delete() }
        val out = File(dir, "doc_${System.currentTimeMillis()}.pdf")
        var total = 0L
        try {
            val stream = context.contentResolver.openInputStream(uri)
                ?: context.contentResolver.openAssetFileDescriptor(uri, "r")?.createInputStream()
                ?: throw MediaException("Could not open the PDF.")
            stream.use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_PDF_BYTES) throw MediaException("This PDF is larger than 60 MB. Split it or pick a smaller file.")
                        o.write(buf, 0, n)
                    }
                }
            }
        } catch (e: MediaException) { out.delete(); throw e }
        catch (e: IOException) { out.delete(); throw MediaException("Could not read the PDF: ${e.message ?: "unknown error"}") }
        if (pageCountOrNull(out) == null) { out.delete(); throw MediaException("This PDF could not be opened. It may be damaged or password-protected.") }
        out
    }

    private fun pageCountOrNull(file: File): Int? = try {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { it.pageCount } }
    } catch (_: Exception) { null }

    suspend fun pageCount(file: File): Int = withContext(Dispatchers.IO) { pageCountOrNull(file) ?: 0 }

    /** Renders one page (0-based) at roughly [width] px wide on a white background. */
    suspend fun renderPage(file: File, page: Int, width: Int): Bitmap = withContext(Dispatchers.IO) {
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { r ->
                    if (page !in 0 until r.pageCount) throw MediaException("That page does not exist.")
                    r.openPage(page).use { pg ->
                        val w = width.coerceIn(200, 2000)
                        val h = (w.toFloat() * pg.height / pg.width).toInt().coerceIn(200, 3200)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        Canvas(bmp).drawColor(Color.WHITE)
                        pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp
                    }
                }
            }
        } catch (e: OutOfMemoryError) {
            throw MediaException("Not enough memory to open this page.")
        } catch (e: SecurityException) {
            throw MediaException("This PDF is password-protected.")
        }
    }

    suspend fun pageWithPreview(context: Context, file: File, page: Int): LoadedImage {
        val bmp = renderPage(file, page, 1500)
        return withContext(Dispatchers.IO) {
            val previewFile = File(captureDir(context), "pdf_p${page}_${System.currentTimeMillis()}.jpg")
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 88, bos)
            val bytes = bos.toByteArray()
            previewFile.writeBytes(bytes)
            bmp.recycle()
            LoadedImage(ChatAttachment.Image("image/jpeg", Base64.encodeToString(bytes, Base64.NO_WRAP)), previewFile)
        }
    }

    suspend fun pageAttachment(file: File, page: Int): ChatAttachment.Image {
        val bmp = renderPage(file, page, 1500)
        return withContext(Dispatchers.IO) {
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, bos)
            bmp.recycle()
            ChatAttachment.Image("image/jpeg", Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP))
        }
    }

    /** The whole PDF as an attachment, for questions about the entire document. Refuses files too big to send safely. */
    suspend fun wholePdfAttachment(file: File): ChatAttachment.Pdf = withContext(Dispatchers.IO) {
        if (file.length() > MAX_INLINE_PDF_BYTES) throw MediaException("This PDF is too large to send whole (limit 12 MB). Pick the pages you need instead.")
        ChatAttachment.Pdf(file.name, Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
    }

    private fun captureDir(context: Context): File = File(context.cacheDir, "study_capture").apply {
        mkdirs()
        listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 3600_000L) it.delete() }
    }

    fun newCaptureFile(context: Context): File =
        File(captureDir(context), "capture_${System.currentTimeMillis()}.jpg")
}
