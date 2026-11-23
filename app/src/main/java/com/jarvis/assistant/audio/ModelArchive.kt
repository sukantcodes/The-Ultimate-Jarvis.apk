package com.jarvis.assistant.audio

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** File helpers for downloaded model archives. Plain JVM, no Android types, so they are unit-tested directly. */
object ModelArchive {

    /** Upper bound on what one archive may unpack to, so a corrupt or hostile file cannot fill the phone. */
    private const val MAX_UNPACKED_BYTES = 400L * 1024 * 1024

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Unpacks [zip] into [destDir]. Any entry that would land outside [destDir] fails the whole unpack. */
    fun extract(zip: File, destDir: File) {
        if (!destDir.exists() && !destDir.mkdirs()) throw IOException("Could not create ${destDir.absolutePath}")
        val root = destDir.canonicalFile
        var total = 0L
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                val target = File(root, entry.name).canonicalFile
                if (target.path != root.path && !target.path.startsWith(root.path + File.separator)) {
                    throw IOException("Unsafe path in model archive: ${entry.name}")
                }
                if (entry.isDirectory) {
                    if (!target.exists() && !target.mkdirs()) throw IOException("Could not create ${target.absolutePath}")
                    continue
                }
                target.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IOException("Could not create ${it.absolutePath}") }
                target.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = zin.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_UNPACKED_BYTES) throw IOException("Model archive unpacks to more than expected.")
                        out.write(buffer, 0, n)
                    }
                }
            }
        }
    }
}
