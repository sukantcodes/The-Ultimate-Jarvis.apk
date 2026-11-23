package com.jarvis.assistant.audio

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Downloads one model archive, resuming a partly downloaded file, and only returns once the whole file matches the
 * expected size and SHA-256. A file that fails the check is deleted so the next try starts clean.
 */
class ModelDownloader(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
) {
    class Spec(val url: String, val sha256: String, val bytes: Long)

    /** Leaves a complete, verified file at [part]. [onProgress] gets (bytes so far, total bytes). */
    fun download(spec: Spec, part: File, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        part.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IOException("Could not create ${it.absolutePath}") }
        if (part.length() > spec.bytes) part.delete()
        if (part.length() != spec.bytes) fetch(spec, part, onProgress, mayRetryFromScratch = true)
        if (part.length() != spec.bytes || !ModelArchive.sha256(part).equals(spec.sha256, ignoreCase = true)) {
            part.delete()
            throw IOException("The downloaded voice model was incomplete or damaged. It will be downloaded again next time.")
        }
        onProgress(spec.bytes, spec.bytes)
    }

    private fun fetch(spec: Spec, part: File, onProgress: (Long, Long) -> Unit, mayRetryFromScratch: Boolean) {
        val have = part.length()
        val request = Request.Builder().url(spec.url).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
        client.newCall(request).execute().use { response ->
            val append: Boolean
            when (response.code) {
                206 -> {
                    // Only continue if the server really starts where we asked it to.
                    val start = response.header("Content-Range")?.substringAfter("bytes ", "")?.substringBefore('-')?.trim()?.toLongOrNull()
                    if (start != null && start != have) throw IOException("Server resumed at the wrong position.")
                    append = true
                }
                200 -> append = false // full file (first try, or the server ignored the resume request)
                416 -> {
                    part.delete()
                    if (!mayRetryFromScratch) throw IOException("Model download could not be resumed (HTTP 416).")
                    fetch(spec, part, onProgress, mayRetryFromScratch = false)
                    return
                }
                else -> throw IOException("Model download failed (HTTP ${response.code}).")
            }
            val body = response.body ?: throw IOException("Model download returned no data.")
            var done = if (append) have else 0L
            var lastReport = done
            FileOutputStream(part, append).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (done - lastReport >= 256 * 1024) { lastReport = done; onProgress(done, spec.bytes) }
                    }
                }
            }
        }
    }
}
