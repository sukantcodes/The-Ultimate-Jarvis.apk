package com.jarvis.assistant.study.input

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.background.JarvisWakeWordService
import com.jarvis.assistant.study.ai.StudyAIEngine
import com.jarvis.assistant.study.model.ExtractedQuestionCandidate
import com.jarvis.assistant.study.model.StudyLanguage
import com.jarvis.assistant.tools.files.ContextPacker
import com.jarvis.assistant.tools.files.DocumentExtractor
import com.jarvis.assistant.tools.files.ExtractOptions
import com.jarvis.assistant.tools.files.ExtractedDocument
import com.jarvis.assistant.tools.files.FileSource
import com.jarvis.assistant.util.SpeechRecognizerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * Handles unified Study Board inputs cleanly off the main thread:
 * - Spoken voice questions & doubts (with safe wake-word mic handover)
 * - Camera photographs & Gallery images (memory-safe bounded decode, EXIF correction, preview & OCR)
 * - Large / multi-page PDF documents (selectable text + bounded page rendering via PdfRenderer)
 */
class StudyInputManager(
    private val context: Context,
    private val aiEngine: StudyAIEngine
) {

    data class ProcessedImage(
        val jpegBytes: ByteArray,
        val mimeType: String,
        val previewBitmap: Bitmap,
        val sourceRef: String
    )

    data class PdfPageSummary(
        val pageNumber: Int,
        val text: String
    )

    data class PdfInspection(
        val fileName: String,
        val sourceUri: Uri,
        val cacheFilePath: String,
        val pageCount: Int,
        val pages: List<PdfPageSummary>,
        val fullContextSummary: String,
        val needsOcr: Boolean,
        val warnings: List<String>
    )

    interface VoiceListener {
        fun onReady()
        fun onPartialText(text: String)
        fun onRmsChanged(level: Float)
        fun onFinalResult(text: String)
        fun onError(message: String)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var speechRecognizer: SpeechRecognizer? = null
    private var pausedWakeWord: Boolean = false
    private var recognizerAttempts: List<String?> = emptyList()
    private var recognizerAttemptIdx: Int = 0
    private var activeVoiceLanguage: StudyLanguage = StudyLanguage.AUTO
    private var activeVoiceListener: VoiceListener? = null
    private var lastPartialTranscript: String = ""

    fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    fun startVoiceInput(language: StudyLanguage, listener: VoiceListener) {
        if (!hasRecordAudioPermission()) {
            listener.onError("Microphone permission is needed for voice questions.")
            return
        }
        if (!SpeechRecognizerFactory.available(appContext)) {
            listener.onError("Speech recognition service is unavailable on this device.")
            return
        }
        stopVoiceInternal(restoreWakeWord = false)

        pausedWakeWord = JarvisWakeWordService.isRunning()
        if (pausedWakeWord) {
            JarvisWakeWordService.stopService(appContext)
        }

        activeVoiceLanguage = language
        activeVoiceListener = listener
        lastPartialTranscript = ""
        recognizerAttempts = SpeechRecognizerFactory.candidates(appContext)
        recognizerAttemptIdx = 0

        // Brief delay lets the background wake-word AudioRecord release the mic hardware cleanly.
        mainHandler.postDelayed({ startAttempt() }, if (pausedWakeWord) 220L else 20L)
    }

    fun stopVoiceInput() {
        stopVoiceInternal(restoreWakeWord = true)
    }

    private fun startAttempt() {
        val listener = activeVoiceListener ?: return
        val componentPkg = recognizerAttempts.getOrNull(recognizerAttemptIdx)
        mainHandler.post {
            runCatching {
                val sr = SpeechRecognizerFactory.create(appContext, componentPkg)
                speechRecognizer = sr
                sr.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        listener.onReady()
                    }

                    override fun onBeginningOfSpeech() = Unit

                    override fun onRmsChanged(rmsdB: Float) {
                        val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                        listener.onRmsChanged(normalized)
                    }

                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit

                    override fun onError(error: Int) {
                        if (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                            if (recognizerAttemptIdx + 1 < recognizerAttempts.size) {
                                recognizerAttemptIdx++
                                releaseRecognizerOnly()
                                startAttempt()
                                return
                            }
                        }
                        if (lastPartialTranscript.isNotBlank() &&
                            (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                        ) {
                            val recovered = lastPartialTranscript
                            stopVoiceInternal(restoreWakeWord = true)
                            listener.onFinalResult(recovered)
                            return
                        }
                        stopVoiceInternal(restoreWakeWord = true)
                        listener.onError(describeSpeechError(error))
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val best = matches?.firstOrNull()?.trim().orEmpty().ifBlank { lastPartialTranscript }
                        stopVoiceInternal(restoreWakeWord = true)
                        if (best.isBlank()) {
                            listener.onError("Didn't catch a clear question. Please speak closer or type it.")
                        } else {
                            listener.onFinalResult(best)
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val partial = matches?.firstOrNull()?.trim().orEmpty()
                        if (partial.isNotBlank()) {
                            lastPartialTranscript = partial
                            listener.onPartialText(partial)
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, activeVoiceLanguage.speechLocaleTag)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, activeVoiceLanguage.speechLocaleTag)
                }
                sr.startListening(intent)
            }.onFailure { err ->
                stopVoiceInternal(restoreWakeWord = true)
                listener.onError("Voice recognition failed: ${err.message ?: "unknown error"}")
            }
        }
    }

    private fun releaseRecognizerOnly() {
        runCatching {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        }
        speechRecognizer = null
    }

    private fun stopVoiceInternal(restoreWakeWord: Boolean) {
        activeVoiceListener = null
        mainHandler.post {
            releaseRecognizerOnly()
            if (restoreWakeWord && pausedWakeWord) {
                pausedWakeWord = false
                if (hasRecordAudioPermission()) {
                    runCatching { JarvisConversationService.startWakeWordMonitoring(appContext) }
                }
            }
        }
    }

    /**
     * Loads and downsamples an image URI safely on [Dispatchers.IO] without holding full camera
     * resolution in memory, fixes EXIF rotation, produces a bounded preview bitmap and JPEG payload.
     */
    suspend fun loadAndPrepareImage(uri: Uri): Result<ProcessedImage> = withContext(Dispatchers.IO) {
        runCatching {
            val mime = appContext.contentResolver.getType(uri)?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
            val exifRotation = readExifRotation(uri)
            val decoded = decodeBoundedBitmap(uri, maxDimension = 1440)
                ?: throw IllegalStateException("Could not decode the selected image.")
            val upright = rotateBitmapIfNeeded(decoded, exifRotation)
            if (upright !== decoded) decoded.recycle()

            val jpegBytes = compressJpegBounded(upright, maxBytes = 880_000)
            val preview = createPreviewBitmap(upright, maxDimension = 640)
            if (preview !== upright) upright.recycle()

            val name = queryDisplayName(uri) ?: "study_photo.jpg"
            ProcessedImage(
                jpegBytes = jpegBytes,
                mimeType = if (mime == "image/png") "image/png" else "image/jpeg",
                previewBitmap = preview,
                sourceRef = "$name|$uri"
            )
        }
    }

    suspend fun extractQuestionsFromImage(image: ProcessedImage, sourceLabel: String): StudyAIEngine.OcrResult =
        aiEngine.extractQuestionsFromImage(image.jpegBytes, image.mimeType, sourceLabel)

    /**
     * Streams a potentially large PDF safely to a private cache file, counts pages via [PdfRenderer],
     * extracts selectable text per page via JARVIS's [DocumentExtractor], and returns inspectable
     * metadata so even 100+ page textbooks stay low-memory.
     */
    suspend fun inspectPdf(uri: Uri): Result<PdfInspection> = withContext(Dispatchers.IO) {
        runCatching {
            val fileName = queryDisplayName(uri) ?: "document.pdf"
            val cacheDir = File(appContext.cacheDir, PDF_CACHE_DIR).apply { mkdirs() }
            cleanupOldPdfCache(cacheDir)

            val tempFile = File(cacheDir, "study_${System.currentTimeMillis()}.pdf")
            var totalBytes = 0L
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { out ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        totalBytes += n
                        if (totalBytes > MAX_PDF_FILE_BYTES) {
                            throw IllegalStateException("PDF exceeds ${MAX_PDF_FILE_BYTES / (1024 * 1024)} MB safety limit.")
                        }
                        out.write(buf, 0, n)
                    }
                }
            } ?: throw IllegalStateException("Unable to open PDF file.")

            val pageCount = countPdfPages(tempFile)
            val extractBytes = readCappedBytes(tempFile, MAX_TEXT_EXTRACT_BYTES)
            val extractedDoc: ExtractedDocument = DocumentExtractor().extract(
                FileSource(fileName, "application/pdf", extractBytes),
                ExtractOptions(extractArchiveText = false)
            )
            val pages = parseDocumentPages(extractedDoc, pageCount)
            val packedSummary = ContextPacker.pack(extractedDoc, query = null, budgetChars = 14_000).text

            PdfInspection(
                fileName = fileName,
                sourceUri = uri,
                cacheFilePath = tempFile.absolutePath,
                pageCount = max(pageCount, extractedDoc.pageCount.coerceAtLeast(1)),
                pages = pages,
                fullContextSummary = packedSummary,
                needsOcr = extractedDoc.needsOcr || pages.all { it.text.isBlank() },
                warnings = extractedDoc.warnings
            )
        }
    }

    /**
     * Renders a single PDF page onto a white-backed bounded bitmap (`<= 1280px` width) using
     * [PdfRenderer] and recycles the full bitmap immediately after compressing JPEG bytes for OCR.
     */
    suspend fun renderPdfPageForOcr(
        cacheFilePath: String,
        pageOneBased: Int
    ): Result<ProcessedImage> = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(cacheFilePath)
            if (!file.exists()) throw IllegalStateException("Cached PDF is no longer available. Please re-import.")
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    val zeroIndex = (pageOneBased - 1).coerceIn(0, (renderer.pageCount - 1).coerceAtLeast(0))
                    renderer.openPage(zeroIndex).use { page ->
                        val scale = (1280f / max(1, page.width)).coerceAtMost(2.2f).coerceAtLeast(0.75f)
                        val targetW = (page.width * scale).toInt().coerceIn(320, 1440)
                        val targetH = (page.height * scale).toInt().coerceIn(320, 1920)
                        val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                        try {
                            Canvas(bmp).drawColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.PAGE_RENDER_MODE_FOR_DISPLAY)
                            val bytes = compressJpegBounded(bmp, maxBytes = 880_000)
                            val thumb = createPreviewBitmap(bmp, maxDimension = 540)
                            ProcessedImage(
                                jpegBytes = bytes,
                                mimeType = "image/jpeg",
                                previewBitmap = thumb,
                                sourceRef = "${file.name}#page=$pageOneBased"
                            )
                        } finally {
                            bmp.recycle()
                        }
                    }
                }
            }
        }
    }

    suspend fun extractQuestionsFromPdfPage(
        inspection: PdfInspection,
        pageOneBased: Int
    ): StudyAIEngine.OcrResult {
        val pageText = inspection.pages.firstOrNull { it.pageNumber == pageOneBased }?.text.orEmpty()
        return if (pageText.isNotBlank() && pageText.length >= 18) {
            aiEngine.extractQuestionsFromPdfText(pageText, pageOneBased)
        } else {
            val rendered = renderPdfPageForOcr(inspection.cacheFilePath, pageOneBased)
                .getOrElse { err ->
                    return StudyAIEngine.OcrResult.Failed(err.message ?: "Failed to render PDF page $pageOneBased.")
                }
            try {
                when (val res = aiEngine.extractQuestionsFromImage(rendered.jpegBytes, rendered.mimeType, "PDF Page $pageOneBased")) {
                    is StudyAIEngine.OcrResult.Success -> StudyAIEngine.OcrResult.Success(
                        res.candidates.map { it.copy(pageNumber = pageOneBased) }
                    )
                    is StudyAIEngine.OcrResult.Failed -> res
                }
            } finally {
                if (!rendered.previewBitmap.isRecycled) rendered.previewBitmap.recycle()
            }
        }
    }

    fun searchPdfContextForQuestion(inspection: PdfInspection, question: String): String {
        val clean = question.trim()
        if (clean.isBlank()) return inspection.fullContextSummary.take(10_000)
        val keywords = clean.lowercase().split(Regex("\\W+")).filter { it.length >= 3 }.toSet()
        if (keywords.isEmpty()) return inspection.fullContextSummary.take(8_000)
        val scored = inspection.pages
            .filter { it.text.isNotBlank() }
            .map { page ->
                val lower = page.text.lowercase()
                val hits = keywords.count { lower.contains(it) }
                hits to page
            }
            .sortedByDescending { it.first }
            .take(4)
            .joinToString("\n\n") { (_, page) -> "Page ${page.pageNumber}:\n${page.text.take(2500)}" }
        return scored.ifBlank { inspection.fullContextSummary.take(8_000) }
    }

    fun release() {
        stopVoiceInput()
        runCatching {
            File(appContext.cacheDir, PDF_CACHE_DIR).listFiles()?.forEach { it.delete() }
        }
    }

    private fun countPdfPages(file: File): Int = runCatching {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer -> renderer.pageCount }
        }
    }.getOrDefault(0)

    private fun parseDocumentPages(doc: ExtractedDocument, fallbackPageCount: Int): List<PdfPageSummary> {
        if (doc.sections.isEmpty()) {
            return (1..fallbackPageCount.coerceAtMost(200).coerceAtLeast(1)).map {
                PdfPageSummary(it, "")
            }
        }
        val list = ArrayList<PdfPageSummary>(doc.sections.size)
        doc.sections.forEachIndexed { idx, sec ->
            val pageNum = Regex("(\\d+)").find(sec.label)?.groupValues?.get(1)?.toIntOrNull() ?: (idx + 1)
            list += PdfPageSummary(pageNum, sec.text.trim())
        }
        val maxKnown = list.maxOfOrNull { it.pageNumber } ?: 1
        val total = max(fallbackPageCount, maxKnown).coerceAtMost(200)
        val byPage = list.associateBy { it.pageNumber }
        return (1..total).map { p -> byPage[p] ?: PdfPageSummary(p, "") }
    }

    private fun readCappedBytes(file: File, maxBytes: Int): ByteArray {
        val len = file.length().coerceAtMost(maxBytes.toLong()).toInt().coerceAtLeast(0)
        val out = ByteArray(len)
        file.inputStream().use { input ->
            var read = 0
            while (read < len) {
                val n = input.read(out, read, len - read)
                if (n <= 0) break
                read += n
            }
        }
        return out
    }

    private fun cleanupOldPdfCache(dir: File) {
        val files = dir.listFiles() ?: return
        if (files.size > 3) {
            files.sortedBy { it.lastModified() }.dropLast(2).forEach { it.delete() }
        }
    }

    private fun readExifRotation(uri: Uri): Int = runCatching {
        appContext.contentResolver.openInputStream(uri)?.use { stream ->
            val exif = ExifInterface(stream)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } ?: 0
    }.getOrDefault(0)

    private fun decodeBoundedBitmap(uri: Uri, maxDimension: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        appContext.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
        val w = opts.outWidth
        val h = opts.outHeight
        if (w <= 0 || h <= 0) return null
        var sample = 1
        while ((w / sample) > maxDimension * 2 || (h / sample) > maxDimension * 2) {
            sample *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = appContext.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOpts)
        } ?: return null

        val maxSide = max(raw.width, raw.height)
        if (maxSide <= maxDimension) return raw
        val scale = maxDimension.toFloat() / maxSide
        val scaled = Bitmap.createScaledBitmap(
            raw,
            (raw.width * scale).toInt().coerceAtLeast(1),
            (raw.height * scale).toInt().coerceAtLeast(1),
            true
        )
        if (scaled !== raw) raw.recycle()
        return scaled
    }

    private fun rotateBitmapIfNeeded(bmp: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bmp
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    private fun createPreviewBitmap(bmp: Bitmap, maxDimension: Int): Bitmap {
        val maxSide = max(bmp.width, bmp.height)
        if (maxSide <= maxDimension) return bmp.copy(Bitmap.Config.ARGB_8888, false)
        val scale = maxDimension.toFloat() / maxSide
        return Bitmap.createScaledBitmap(
            bmp,
            (bmp.width * scale).toInt().coerceAtLeast(1),
            (bmp.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun compressJpegBounded(bmp: Bitmap, maxBytes: Int): ByteArray {
        val stream = ByteArrayOutputStream()
        var quality = 86
        while (quality >= 50) {
            stream.reset()
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            if (stream.size() <= maxBytes) break
            quality -= 12
        }
        return stream.toByteArray()
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else null
        }
    }.getOrNull()

    private fun describeSpeechError(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Microphone audio error. Check if another app is using the mic."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Speech recognition network error. Check internet or try typing."
        SpeechRecognizer.ERROR_NO_MATCH -> "Couldn't catch a clear question. Please speak again or edit manually."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected. Tap the mic and ask your question."
        else -> "Voice input interrupted ($error). Tap mic to retry."
    }

    companion object {
        private const val PDF_CACHE_DIR = "study_pdf_cache"
        private const val MAX_PDF_FILE_BYTES = 48 * 1024 * 1024
        private const val MAX_TEXT_EXTRACT_BYTES = 14 * 1024 * 1024
    }
}
