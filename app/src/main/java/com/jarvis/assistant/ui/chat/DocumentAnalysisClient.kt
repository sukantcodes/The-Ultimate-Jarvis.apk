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
import java.io.File
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
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".xml", ignoreCase = true) &&
                    (entry.name.contains("document") || entry.name.contains("sharedStrings") ||
                     entry.name.contains("sheet") || entry.name.contains("slides/slide") ||
                     entry.name.contains("content.xml") || entry.name.contains("word/"))) {
                    val xml = zip.readBytes().toString(Charsets.UTF_8)
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
}
