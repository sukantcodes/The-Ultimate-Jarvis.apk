package com.jarvis.assistant.ui.studyboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Base64
import com.jarvis.assistant.provider.ChatAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/** Safe handling of photographs and PDFs: everything is downscaled, streamed and recycled so big files cannot exhaust memory. */
object StudyMedia {

    private const val MAX_IMAGE_SIDE = 1800
    private const val MAX_PDF_BYTES = 60L * 1024 * 1024
    private const val MAX_INLINE_PDF_BYTES = 12L * 1024 * 1024

    class MediaException(message: String) : Exception(message)

    /** Reads an image, fixes its rotation, shrinks it so the longest side is at most 1800 px and returns it as JPEG for the AI. */
    suspend fun imageAttachment(context: Context, uri: Uri): ChatAttachment.Image = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: throw MediaException("Could not open the image.")
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw MediaException("This file is not a readable image.")
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_SIDE * 2) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: throw MediaException("Could not decode the image.")
            val rotation = resolver.openInputStream(uri)?.use { exifRotation(it) } ?: 0
            var bmp = decoded
            val longest = maxOf(bmp.width, bmp.height)
            if (longest > MAX_IMAGE_SIDE || rotation != 0) {
                val m = Matrix()
                if (longest > MAX_IMAGE_SIDE) { val f = MAX_IMAGE_SIDE.toFloat() / longest; m.postScale(f, f) }
                if (rotation != 0) m.postRotate(rotation.toFloat())
                val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (out !== bmp) bmp.recycle()
                bmp = out
            }
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, bos)
            bmp.recycle()
            ChatAttachment.Image("image/jpeg", Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP))
        } catch (e: OutOfMemoryError) {
            throw MediaException("This image is too large for this phone's memory. Try a smaller photo.")
        } catch (e: IOException) {
            throw MediaException("Could not read the image: ${e.message ?: "unknown error"}")
        }
    }

    private fun exifRotation(stream: java.io.InputStream): Int = try {
        when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
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
            context.contentResolver.openInputStream(uri)?.use { input ->
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
            } ?: throw MediaException("Could not open the PDF.")
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

    fun newCaptureFile(context: Context): File {
        val dir = File(context.cacheDir, "study_capture").apply { mkdirs() }
        dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 3600_000L) it.delete() }
        return File(dir, "capture_${System.currentTimeMillis()}.jpg")
    }
}
