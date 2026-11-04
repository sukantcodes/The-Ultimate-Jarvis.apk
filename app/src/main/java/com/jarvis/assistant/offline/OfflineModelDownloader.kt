package com.jarvis.assistant.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Download manager for the supported Qwen3 0.6B Q4_K_M GGUF.
 * The source publishes the file at approximately 484 MB. The response's Content-Length
 * is used as the authoritative byte count when available; validation also checks GGUF magic
 * and a conservative size range so metadata rounding cannot break model activation.
 */
object OfflineModelDownloader {
    private const val MIN_MODEL_BYTES = 400L * 1024L * 1024L
    private const val MAX_MODEL_BYTES = 550L * 1024L * 1024L
    const val MODEL_BYTES = 507_000_000L // UI/progress fallback only; HTTP length is authoritative.
    const val MODEL_DISPLAY_SIZE = "about 484 MB"
    private const val FILE_NAME = "Qwen3-0.6B-Q4_K_M.gguf"
    private const val URL_MODEL =
        "https://huggingface.co/lmstudio-community/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf?download=true"

    fun modelFile(context: Context) =
        File(File(context.applicationContext.filesDir, "offline-models"), FILE_NAME)

    private fun partFile(context: Context) =
        File(modelFile(context).parentFile, "$FILE_NAME.part")

    private fun hasGgufMagic(file: File): Boolean = try {
        FileInputStream(file).use { input ->
            val magic = ByteArray(4)
            input.read(magic) == 4 &&
                magic[0] == 'G'.code.toByte() &&
                magic[1] == 'G'.code.toByte() &&
                magic[2] == 'U'.code.toByte() &&
                magic[3] == 'F'.code.toByte()
        }
    } catch (_: Exception) {
        false
    }

    fun isModelValid(file: File): Boolean =
        file.isFile && file.length() in MIN_MODEL_BYTES..MAX_MODEL_BYTES && hasGgufMagic(file)

    fun isInternetAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    suspend fun download(context: Context, onProgress: (Long, Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dest = modelFile(context)
            if (isModelValid(dest)) {
                onProgress(dest.length(), dest.length())
                return@withContext dest
            }

            dest.parentFile?.let {
                if (!it.exists() && !it.mkdirs()) error("Cannot create private model storage.")
            }

            val part = partFile(context)
            if (part.length() > MAX_MODEL_BYTES) part.delete()
            var offset = part.length()

            fun open(start: Long): HttpURLConnection {
                val connection = (URL(URL_MODEL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "JARVIS-Android")
                    if (start > 0) setRequestProperty("Range", "bytes=$start-")
                }
                connection.connect()
                if (connection.responseCode !in 200..299) {
                    val code = connection.responseCode
                    connection.disconnect()
                    error("Model server returned HTTP $code.")
                }
                return connection
            }

            var connection = open(offset)
            if (offset > 0 && connection.responseCode != HttpURLConnection.HTTP_PARTIAL) {
                connection.disconnect()
                part.delete()
                offset = 0
                connection = open(0)
            }

            val contentLength = connection.contentLengthLong
            val total = if (contentLength > 0) offset + contentLength else MODEL_BYTES
            try {
                connection.inputStream.use { input ->
                    FileOutputStream(part, offset > 0).use { output ->
                        val buffer = ByteArray(256 * 1024)
                        var count = offset
                        var lastReport = count
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            count += n
                            if (count - lastReport >= 1_048_576L) {
                                onProgress(count, total.coerceAtMost(MAX_MODEL_BYTES))
                                lastReport = count
                            }
                        }
                        output.fd.sync()
                        onProgress(count, total.coerceAtMost(MAX_MODEL_BYTES))
                    }
                }
            } finally {
                connection.disconnect()
            }

            if (contentLength > 0 && part.length() != total) {
                error("Download incomplete (${part.length()} of $total bytes). Tap Download to resume.")
            }
            if (!isModelValid(part)) {
                part.delete()
                error("Downloaded file failed GGUF/size validation. Download the model again.")
            }

            if (dest.exists() && !dest.delete()) error("Cannot replace the old model file.")
            if (!part.renameTo(dest)) error("Cannot finalize the model file.")

            // Remove the previous large model only after the new file has passed validation.
            val legacyModel = File(dest.parentFile, "Qwen3-1.7B-Q4_K_M.gguf")
            if (legacyModel.exists()) legacyModel.delete()
            dest
        }
}
