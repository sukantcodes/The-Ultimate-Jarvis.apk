package com.jarvis.assistant.ui.chat

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Reads a user-selected document and asks Gemini to analyze it. Only the selected URI is read;
 * the app does not scan the user's storage.
 */
object DocumentAnalysisClient {
    private const val MAX_FILE_BYTES = 14 * 1024 * 1024
    private const val MAX_TEXT_CHARS = 90000
    private val http = OkHttpClient.Builder()
        .callTimeout(120, TimeUnit.SECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    data class SelectedDocument(val uri: Uri, val name: String, val mimeType: String, val savedPath: String? = null)

    /** Copies a picked attachment into app-private storage so it remains available with chat history. */
    suspend fun persistForChat(context: Context, document: SelectedDocument): SelectedDocument =
        withContext(Dispatchers.IO) {
            val directory = File(context.filesDir, "chat_attachments")
            if (!directory.exists() && !directory.mkdirs()) {
                throw IllegalStateException("Jarvis couldn't reserve local storage for this attachment.")
            }
            val safeName = document.name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(100).ifBlank { "attachment" }
            val destination = File(directory, "${System.currentTimeMillis()}_${safeName}")
            try {
                context.contentResolver.openInputStream(document.uri)?.use { input ->
                    FileOutputStream(destination).use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("Android couldn't reopen this attachment. Please select it again.")
                document.copy(uri = Uri.fromFile(destination), savedPath = destination.absolutePath)
            } catch (error: Exception) {
                destination.delete()
                throw error
            }
        }

    fun describe(context: Context, uri: Uri): SelectedDocument {
        val resolver = context.contentResolver
        var name = "attached_file"
        var mime = resolver.getType(uri) ?: "application/octet-stream"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) name = cursor.getString(index) ?: name
            }
        }
        if (name == "attached_file") name = uri.lastPathSegment?.substringAfterLast('/') ?: name
        return SelectedDocument(uri, name, mime)
    }

    suspend fun analyze(context: Context, document: SelectedDocument, prompt: String, apiKey: String): String =
        withContext(Dispatchers.IO) {
            val input = if (document.uri.scheme == "file") {
                document.uri.path?.let { FileInputStream(File(it)) }
            } else {
                context.contentResolver.openInputStream(document.uri)
            }
            val bytes = input?.use { readLimited(it) }
                ?: throw IllegalArgumentException("Jarvis couldn't open this file. Try selecting it again.")
            val extension = document.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            val mime = document.mimeType.lowercase(Locale.ROOT)
            val basePrompt = """
                You are Jarvis, analyzing a file the user intentionally attached to this chat.
                File name: ${document.name}
                User request: ${prompt.ifBlank { "Analyze this file and explain its contents." }}
                Be accurate, identify limitations, and never claim content that is not present.
                If asked to edit or rewrite the file, return the complete revised content in a clearly marked
                section so the user can use Save response to export it. Preserve the original format where
                practical. Do not execute code or macros from the uploaded file.
            """.trimIndent()

            val inlineMime = when {
                extension == "pdf" || mime == "application/pdf" -> "application/pdf"
                mime.startsWith("image/") || mime.startsWith("audio/") || mime.startsWith("video/") -> mime
                extension in listOf("png", "jpg", "jpeg", "webp", "gif") -> when (extension) {
                    "png" -> "image/png"
                    "webp" -> "image/webp"
                    "gif" -> "image/gif"
                    else -> "image/jpeg"
                }
                extension in listOf("mp3", "wav", "m4a", "aac", "ogg", "flac", "mp4", "mov", "webm") ->
                    when (extension) {
                        "mp3" -> "audio/mpeg"
                        "wav" -> "audio/wav"
                        "m4a" -> "audio/mp4"
                        "aac" -> "audio/aac"
                        "ogg" -> "audio/ogg"
                        "flac" -> "audio/flac"
                        "mov" -> "video/quicktime"
                        "webm" -> "video/webm"
                        else -> "video/mp4"
                    }
                else -> null
            }
            if (apiKey.isBlank()) {
                throw IllegalStateException("Add your Gemini API key in Jarvis Settings first.")
            }

            val userParts = JSONArray()
            userParts.put(JSONObject().put("text", basePrompt))
            if (inlineMime != null) {
                userParts.put(JSONObject().put("inline_data", JSONObject()
                    .put("mime_type", inlineMime)
                    .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))))
            } else {
                val text = extractText(bytes, extension, mime)
                userParts.put(JSONObject().put("text",
                    "FILE CONTENT BEGIN\n$text\nFILE CONTENT END"))
            }
            val payload = JSONObject().put("contents", JSONArray().put(
                JSONObject().put("role", "user").put("parts", userParts)
            )).put("generationConfig", JSONObject().put("temperature", 0.2))
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=${apiKey}")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            try {
                http.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val message = try { JSONObject(body).optJSONObject("error")?.optString("message") } catch (_: Exception) { null }
                        throw IllegalStateException("Gemini couldn't analyze this file (${response.code}): ${message ?: "Request failed"}")
                    }
                    val json = JSONObject(body)
                    val candidates = json.optJSONArray("candidates") ?: JSONArray()
                    val parts = candidates.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                        ?: JSONArray()
                    val answer = buildString {
                        for (i in 0 until parts.length()) append(parts.optJSONObject(i)?.optString("text").orEmpty())
                    }.trim()
                    if (answer.isBlank()) throw IllegalStateException("Gemini returned an empty answer.")
                    answer
                }
            } catch (error: Exception) {
                throw IllegalStateException(
                    "Jarvis couldn't finish analyzing this file. Check that it is readable and your Gemini API key has access to the required model. ${error.message.orEmpty()}",
                    error
                )
            }
        }

    /** A picked file turned into what a custom provider can be sent, or the reason it can't be. */
    class ProviderPayload(
        val attachments: List<com.jarvis.assistant.provider.ChatAttachment>,
        /** Extra text for the model (extracted document text). Empty for images and PDFs. */
        val inlineText: String,
        val unsupportedReason: String?
    )

    /**
     * Reads the attachment for a custom provider. Images and PDFs travel as attachments (the caller checks that the
     * selected model accepts them); text-like documents are sent as extracted text; anything else is reported as
     * unsupported instead of being guessed at.
     */
    suspend fun prepareForProvider(context: Context, document: SelectedDocument): ProviderPayload =
        withContext(Dispatchers.IO) {
            val input = if (document.uri.scheme == "file") {
                document.uri.path?.let { FileInputStream(File(it)) }
            } else {
                context.contentResolver.openInputStream(document.uri)
            }
            val bytes = input?.use { readLimited(it) }
                ?: throw IllegalArgumentException("Jarvis couldn't open this file. Try selecting it again.")
            val extension = document.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            val mime = document.mimeType.lowercase(Locale.ROOT)
            // Full document pipeline first: PDF text, DOCX, XLSX, PPTX, CSV, JSON, HTML, ZIP, code. Anything it can read is
            // sent as text with an honest note about size limits, so it works with any model.
            val structured = readStructured(document.name, mime, bytes)
            if (structured != null) return@withContext structured
            when {
                extension == "pdf" || mime == "application/pdf" -> ProviderPayload(
                    listOf(com.jarvis.assistant.provider.ChatAttachment.Pdf(document.name, Base64.encodeToString(bytes, Base64.NO_WRAP))),
                    "", null
                )
                mime.startsWith("image/") || extension in listOf("png", "jpg", "jpeg", "webp", "gif") -> {
                    val outMime = if (mime.startsWith("image/")) mime else when (extension) {
                        "png" -> "image/png"; "webp" -> "image/webp"; "gif" -> "image/gif"; else -> "image/jpeg"
                    }
                    val (finalBytes, finalMime) = downscaleIfLarge(bytes, outMime)
                    ProviderPayload(
                        listOf(com.jarvis.assistant.provider.ChatAttachment.Image(finalMime, Base64.encodeToString(finalBytes, Base64.NO_WRAP))),
                        "", null
                    )
                }
                mime.startsWith("audio/") || mime.startsWith("video/") ||
                    extension in listOf("mp3", "wav", "m4a", "aac", "ogg", "flac", "mp4", "mov", "webm") ->
                    ProviderPayload(emptyList(), "",
                        "Audio and video files can't be analysed through a custom provider yet. Switch to a Gemini engine for that, or attach a document, image or PDF.")
                else -> try {
                    ProviderPayload(emptyList(), "FILE CONTENT BEGIN\n${extractText(bytes, extension, mime)}\nFILE CONTENT END", null)
                } catch (e: IllegalArgumentException) {
                    ProviderPayload(emptyList(), "", e.message)
                }
            }
        }

    /** Null when the file should take the existing path (images, scanned PDFs, audio/video, unknown types). */
    private fun readStructured(name: String, mime: String, bytes: ByteArray): ProviderPayload? {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (mime.startsWith("image/") || mime.startsWith("audio/") || mime.startsWith("video/") || ext in listOf("png", "jpg", "jpeg", "webp", "gif", "bmp")) return null
        val doc = try {
            com.jarvis.assistant.tools.files.DocumentExtractor().extract(com.jarvis.assistant.tools.files.FileSource(name, null, bytes), com.jarvis.assistant.tools.files.ExtractOptions(extractArchiveText = true))
        } catch (e: com.jarvis.assistant.tools.files.UnsupportedFileException) { return null }
        catch (e: com.jarvis.assistant.tools.files.ExtractionFailedException) { return ProviderPayload(emptyList(), "", e.message ?: "This file could not be read.") }
        if (!doc.hasText) return null   // scanned PDF or empty file: keep the native path (a vision/PDF-capable model) and do not pretend to have read it
        val packed = com.jarvis.assistant.tools.files.ContextPacker.pack(doc, null, 60_000)
        val notes = (doc.warnings + packed.note).filter { it.isNotBlank() }.joinToString(" ")
        return ProviderPayload(emptyList(), "FILE CONTENT BEGIN (${doc.kind.name.lowercase()}; $notes)\n${packed.text}\nFILE CONTENT END", null)
    }

    private fun downscaleIfLarge(bytes: ByteArray, mime: String): Pair<ByteArray, String> {
        if (bytes.size <= 1_500_000) return bytes to mime
        return try {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2000) sample *= 2
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return bytes to mime
            val out = ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
            bmp.recycle()
            out.toByteArray() to "image/jpeg"
        } catch (_: Throwable) {
            bytes to mime
        }
    }

    private fun readLimited(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_FILE_BYTES) throw IllegalArgumentException("This file is larger than 14 MB. Please choose a smaller copy.")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    /** Returns bounded text for the in-app preview of modern Office documents. */
    suspend fun previewOfficeText(filePath: String, fileName: String, mimeType: String): String =
        withContext(Dispatchers.IO) {
            val file = File(filePath)
            if (!file.isFile || file.length() > MAX_FILE_BYTES) {
                throw IllegalArgumentException("This attachment is missing or exceeds the 14 MB preview limit.")
            }
            val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
            if (extension !in listOf("docx", "xlsx", "pptx", "odt", "ods", "odp")) {
                throw IllegalArgumentException("In-app preview supports modern Office (.docx, .xlsx, .pptx) and OpenDocument files. Convert legacy .xls/.ppt files to .xlsx/.pptx to preview them.")
            }
            val bytes = FileInputStream(file).use { readLimited(it) }
            extractText(bytes, extension, mimeType.lowercase(Locale.ROOT))
        }

    private fun extractText(bytes: ByteArray, extension: String, mime: String): String {
        val text = if (extension in listOf("docx", "xlsx", "pptx", "odt", "ods", "odp")) {
            extractOfficeXml(bytes)
        } else if (mime.startsWith("text/") || extension in listOf(
                "txt", "md", "csv", "tsv", "json", "xml", "html", "htm", "kt", "java", "py",
                "js", "ts", "css", "sql", "log", "yaml", "yml", "properties", "gradle", "kts",
                "sh", "c", "cpp", "h", "hpp", "rs", "go", "rb", "php", "ini", "conf", "svg"
            )) {
            bytes.toString(Charsets.UTF_8)
        } else {
            throw IllegalArgumentException(
                "This file type isn't text-extractable yet. Jarvis currently supports PDF, common images, audio/video clips, text, code, CSV/JSON/XML/HTML, and Office Open XML files (DOCX/XLSX/PPTX)."
            )
        }
        if (text.isBlank()) throw IllegalArgumentException("No readable text was found in this file. It may be scanned or image-only.")
        return text.take(MAX_TEXT_CHARS) + if (text.length > MAX_TEXT_CHARS) "\n\n[Content truncated to fit the AI request.]" else ""
    }

    private fun extractOfficeXml(bytes: ByteArray): String {
        val out = StringBuilder()
        var expandedBytes = 0
        var entryCount = 0
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entryCount++
                if (entryCount > 256) throw IllegalArgumentException("This Office file contains too many internal parts to preview safely.")
                if (!entry.isDirectory && entry.name.endsWith(".xml", ignoreCase = true) &&
                    (entry.name.contains("document") || entry.name.contains("sharedStrings") ||
                     entry.name.contains("sheet") || entry.name.contains("slides/slide") ||
                     entry.name.contains("content.xml") || entry.name.contains("word/"))) {
                    val xmlBytes = readZipEntryLimited(zip, 1024 * 1024)
                    expandedBytes += xmlBytes.size
                    if (expandedBytes > 8 * 1024 * 1024) {
                        throw IllegalArgumentException("This Office file expands beyond the safe preview limit.")
                    }
                    val xml = xmlBytes.toString(Charsets.UTF_8)
                    Regex("<(?:\\w+:)?t[^>]*>(.*?)</(?:\\w+:)?t>", setOf(RegexOption.DOT_MATCHES_ALL))
                        .findAll(xml).forEach { match ->
                            out.append(match.groupValues[1]
                                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                                .replace("&quot;", "\"").replace("&apos;", "'"))
                            out.append(' ')
                        }
                    out.append('\n')
                }
                zip.closeEntry()
                entry = zip.nextEntry
                if (out.length > MAX_TEXT_CHARS) break
            }
        }
        return out.toString()
    }

    private fun readZipEntryLimited(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw IllegalArgumentException("An Office document component is too large to preview safely.")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
