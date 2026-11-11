package com.jarvis.assistant.tools.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume

/** On-device text recognition (ML Kit, Latin script). Runs offline; nothing is uploaded. Other scripts are not recognised. */
class MlKitOcrEngine : OcrEngine {
    override suspend fun recognize(bytes: ByteArray, mime: String): String? {
        val bmp = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } ?: return null
        return try { recognize(bmp) } finally { bmp.recycle() }
    }

    suspend fun recognize(bmp: Bitmap): String? = suspendCancellableCoroutine { cont ->
        val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        rec.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { r -> rec.close(); cont.resume(r.text.takeIf { it.isNotBlank() }) }
            .addOnFailureListener { rec.close(); cont.resume(null) }
        cont.invokeOnCancellation { rec.close() }
    }
}

/** Draws PDF pages with Android's PdfRenderer. */
class AndroidPdfRenderer : PdfPageRenderer {
    override suspend fun pageCount(pdf: File): Int = withContext(Dispatchers.IO) {
        try { ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { it.pageCount } } } catch (e: Exception) { 0 }
    }

    override suspend fun renderPage(pdf: File, pageIndex: Int, maxDimension: Int): ByteArray? = withContext(Dispatchers.IO) {
        try {
            ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { r ->
                    if (pageIndex !in 0 until r.pageCount) return@withContext null
                    r.openPage(pageIndex).use { page ->
                        val scale = maxDimension.toFloat() / maxOf(page.width, page.height)
                        val bmp = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray().also { bmp.recycle() }
                    }
                }
            }
        } catch (e: Exception) { null }
    }
}

/** OCR for scanned PDFs: draws each page and reads it. Page count is capped and the result says if pages were skipped. */
object PdfOcr {
    class Result(val text: String, val pagesRead: Int, val pagesTotal: Int, val pagesFailed: Int)

    suspend fun run(pdf: File, ocr: OcrEngine = MlKitOcrEngine(), renderer: PdfPageRenderer = AndroidPdfRenderer(), maxPages: Int = 20, onPage: (Int, Int) -> Unit = { _, _ -> }): Result {
        val total = renderer.pageCount(pdf); val limit = minOf(total, maxPages)
        val sb = StringBuilder(); var failed = 0; var read = 0
        for (i in 0 until limit) {
            onPage(i + 1, limit)
            val png = renderer.renderPage(pdf, i, 1800)
            val t = png?.let { ocr.recognize(it, "image/png") }
            if (t == null) failed++ else { read++; sb.append("[Page ${i + 1}]\n").append(t.trim()).append("\n\n") }
        }
        return Result(sb.toString().trim(), read, total, failed)
    }
}
