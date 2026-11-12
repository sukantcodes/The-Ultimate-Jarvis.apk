package com.jarvis.assistant.tts.engine

import android.content.Context
import android.util.Log
import com.jarvis.assistant.tts.model.DownloadState
import com.jarvis.assistant.tts.model.VoiceEngineType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Resilient, lifecycle-safe model downloader and unpacker.
 * Handles streaming OkHttp downloads, safe Tar.bz2 and Zip extractions,
 * and strict path traversal protection against Zip/Tar Slip vulnerabilities.
 */
class TtsModelDownloader(private val context: Context) {

    companion object {
        private const val TAG = "TtsModelDownloader"
        private const val BUFFER_SIZE = 8192 // 8 KB buffer

        fun getModelsBaseDir(context: Context): File {
            val dir = File(context.filesDir, "tts_models")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

        fun getEngineDir(context: Context, engineType: VoiceEngineType): File {
            val base = getModelsBaseDir(context)
            return File(base, engineType.archiveName)
        }

        /**
         * Resolves the actual directory containing the engine's model weights.
         * Gracefully handles both direct root unpacks and nested archive subdirectories.
         */
        fun resolveModelDir(context: Context, engineType: VoiceEngineType): File? {
            if (!engineType.isLocal) return null
            val baseDir = getEngineDir(context, engineType)
            if (!baseDir.exists() || !baseDir.isDirectory) return null

            val targetModel = engineType.defaultModelFileName
            if (File(baseDir, targetModel).exists()) {
                return baseDir
            }

            val nestedArchive = File(baseDir, engineType.archiveName)
            if (File(nestedArchive, targetModel).exists()) {
                return nestedArchive
            }

            val matchingChild = baseDir.listFiles()?.firstOrNull { child ->
                child.isDirectory && File(child, targetModel).exists()
            }
            if (matchingChild != null) {
                return matchingChild
            }

            return null
        }

        fun isEngineInstalled(context: Context, engineType: VoiceEngineType): Boolean {
            if (!engineType.isLocal) return true
            val dir = resolveModelDir(context, engineType) ?: return false

            return when (engineType) {
                VoiceEngineType.LOCAL_KOKORO -> {
                    // Check required artifacts: model.int8.onnx, voices.bin, tokens.txt, espeak-ng-data
                    File(dir, "model.int8.onnx").exists() &&
                            File(dir, "voices.bin").exists() &&
                            File(dir, "tokens.txt").exists() &&
                            File(dir, "espeak-ng-data").exists()
                }
                VoiceEngineType.LOCAL_PIPER -> {
                    // Check required artifacts: en_GB-alan-medium.onnx, tokens.txt, espeak-ng-data
                    File(dir, "en_GB-alan-medium.onnx").exists() &&
                            File(dir, "tokens.txt").exists() &&
                            File(dir, "espeak-ng-data").exists()
                }
                else -> true
            }
        }

        /**
         * Sanitizes entry relative paths by stripping root archive folder prefixes and normalising path separators.
         */
        fun sanitizeRelativeEntryPath(entryName: String, archiveName: String): String {
            var name = entryName.replace('\\', '/')
            while (name.startsWith("/")) name = name.substring(1)
            while (name.startsWith("./")) name = name.substring(2)
            if (archiveName.isNotBlank()) {
                if (name == archiveName || name == "$archiveName/") {
                    return ""
                }
                if (name.startsWith("$archiveName/")) {
                    name = name.removePrefix("$archiveName/")
                }
            }
            return name
        }
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    suspend fun downloadAndInstall(
        engineType: VoiceEngineType,
        onSuccess: (suspend () -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (!engineType.isLocal) {
            _downloadState.value = DownloadState.Ready
            onSuccess?.invoke()
            return@withContext true
        }

        val url = engineType.downloadUrl
        if (url.isBlank()) {
            _downloadState.value = DownloadState.Error("No download URL provided for engine ${engineType.displayName}")
            return@withContext false
        }

        val cacheDir = context.cacheDir
        val tempArchiveFile = File(cacheDir, "${engineType.archiveName}_${System.currentTimeMillis()}.download.tmp")
        val targetExtractDir = getEngineDir(context, engineType)

        try {
            _downloadState.value = DownloadState.Downloading(0, 0L, 0L)
            Log.i(TAG, "Starting download of ${engineType.displayName} from $url")

            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorMsg = "HTTP download failed with code ${response.code}: ${response.message}"
                Log.e(TAG, errorMsg)
                _downloadState.value = DownloadState.Error(errorMsg)
                return@withContext false
            }

            val body = response.body ?: run {
                _downloadState.value = DownloadState.Error("Response body was empty")
                return@withContext false
            }

            val totalBytes = body.contentLength()
            var downloadedBytes = 0L

            body.byteStream().use { input ->
                FileOutputStream(tempArchiveFile).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var read: Int
                    var lastReportedProgress = -1

                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloadedBytes += read

                        val progress = if (totalBytes > 0) {
                            ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                        } else {
                            -1
                        }

                        if (progress != lastReportedProgress) {
                            lastReportedProgress = progress
                            _downloadState.value = DownloadState.Downloading(progress, downloadedBytes, totalBytes)
                        }
                    }
                    output.flush()
                }
            }

            Log.i(TAG, "Download finished (${downloadedBytes} bytes). Beginning archive extraction...")
            _downloadState.value = DownloadState.Extracting("Preparing target workspace...")

            if (targetExtractDir.exists()) {
                targetExtractDir.deleteRecursively()
            }
            targetExtractDir.mkdirs()

            if (url.endsWith(".tar.bz2") || url.endsWith(".tbz2") || url.endsWith(".bz2")) {
                extractTarBz2(tempArchiveFile, targetExtractDir, engineType.archiveName)
            } else if (url.endsWith(".zip")) {
                extractZip(tempArchiveFile, targetExtractDir, engineType.archiveName)
            } else {
                throw IOException("Unsupported archive format for $url")
            }

            // Normalise directory structure if the archive unpacked everything into a sub-folder
            flattenNestedSingleFolder(targetExtractDir)

            Log.i(TAG, "Extraction completed successfully into ${targetExtractDir.absolutePath}")
            _downloadState.value = DownloadState.Ready

            onSuccess?.invoke()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Download or extraction failed for ${engineType.displayName}", e)
            _downloadState.value = DownloadState.Error("Installation failed: ${e.message}", e)
            if (targetExtractDir.exists()) {
                targetExtractDir.deleteRecursively()
            }
            false
        } finally {
            if (tempArchiveFile.exists()) {
                tempArchiveFile.delete()
            }
        }
    }

    /**
     * Extracts .tar.bz2 archives safely using Apache Commons Compress with Zip/Tar Slip protection
     * and automatic root directory flattening.
     */
    private fun extractTarBz2(archive: File, destDir: File, archiveName: String) {
        val canonicalDest = destDir.canonicalFile
        FileInputStream(archive).use { fis ->
            BufferedInputStream(fis).use { bis ->
                BZip2CompressorInputStream(bis).use { bzIn ->
                    TarArchiveInputStream(bzIn).use { tarIn ->
                        var entry = tarIn.nextTarEntry
                        while (entry != null) {
                            val relPath = sanitizeRelativeEntryPath(entry.name, archiveName)
                            if (relPath.isNotBlank()) {
                                _downloadState.value = DownloadState.Extracting(relPath)
                                val targetFile = File(destDir, relPath).canonicalFile

                                // Strict path traversal verification
                                if (!targetFile.toPath().startsWith(canonicalDest.toPath())) {
                                    throw SecurityException("Path traversal attempt in tar archive: ${entry.name}")
                                }

                                if (entry.isDirectory) {
                                    if (!targetFile.exists()) targetFile.mkdirs()
                                } else {
                                    val parent = targetFile.parentFile
                                    if (parent != null && !parent.exists()) parent.mkdirs()

                                    BufferedOutputStream(FileOutputStream(targetFile)).use { out ->
                                        val buffer = ByteArray(BUFFER_SIZE)
                                        var count: Int
                                        while (tarIn.read(buffer).also { count = it } != -1) {
                                            out.write(buffer, 0, count)
                                        }
                                        out.flush()
                                    }
                                }
                            }
                            entry = tarIn.nextTarEntry
                        }
                    }
                }
            }
        }
    }

    /**
     * Extracts .zip archives safely with Zip Slip protection and automatic root directory flattening.
     */
    private fun extractZip(archive: File, destDir: File, archiveName: String) {
        val canonicalDest = destDir.canonicalFile
        FileInputStream(archive).use { fis ->
            BufferedInputStream(fis).use { bis ->
                ZipInputStream(bis).use { zipIn ->
                    var entry = zipIn.nextEntry
                    while (entry != null) {
                        val relPath = sanitizeRelativeEntryPath(entry.name, archiveName)
                        if (relPath.isNotBlank()) {
                            _downloadState.value = DownloadState.Extracting(relPath)
                            val targetFile = File(destDir, relPath).canonicalFile

                            // Strict path traversal verification
                            if (!targetFile.toPath().startsWith(canonicalDest.toPath())) {
                                throw SecurityException("Path traversal attempt in zip archive: ${entry.name}")
                            }

                            if (entry.isDirectory) {
                                if (!targetFile.exists()) targetFile.mkdirs()
                            } else {
                                val parent = targetFile.parentFile
                                if (parent != null && !parent.exists()) parent.mkdirs()

                                BufferedOutputStream(FileOutputStream(targetFile)).use { out ->
                                    val buffer = ByteArray(BUFFER_SIZE)
                                    var count: Int
                                    while (zipIn.read(buffer).also { count = it } != -1) {
                                        out.write(buffer, 0, count)
                                    }
                                    out.flush()
                                }
                            }
                        }
                        zipIn.closeEntry()
                        entry = zipIn.nextEntry
                    }
                }
            }
        }
    }

    /**
     * If the archive contained a single root directory (e.g. kokoro-int8-multi-lang-v1_0/...),
     * move all its contents up to [destDir] so files reside directly where expected.
     */
    private fun flattenNestedSingleFolder(destDir: File) {
        val children = destDir.listFiles() ?: return
        if (children.size == 1 && children[0].isDirectory) {
            val nestedDir = children[0]
            val nestedChildren = nestedDir.listFiles() ?: return
            for (file in nestedChildren) {
                val target = File(destDir, file.name)
                file.renameTo(target)
            }
            nestedDir.delete()
        }
    }

    fun deleteInstalledModel(engineType: VoiceEngineType): Boolean {
        if (!engineType.isLocal) return true
        val dir = getEngineDir(context, engineType)
        return if (dir.exists()) {
            dir.deleteRecursively()
        } else {
            true
        }
    }
}
