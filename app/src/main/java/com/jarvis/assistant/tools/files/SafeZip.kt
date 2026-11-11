package com.jarvis.assistant.tools.files

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

class ZipLimitException(message: String) : IOException(message)

data class ZipEntryInfo(
    val name: String, val compressedSize: Long, val size: Long, val method: Int,
    val isDirectory: Boolean, val problem: String?
)

data class ZipIndex(val entries: List<ZipEntryInfo>, val warnings: List<String>, val refusal: String?, val truncated: Boolean)

/** Reads ZIP-based files (archives, DOCX, XLSX, PPTX) with hard limits against zip bombs and path tricks. */
object SafeZip {

    fun looksLikeZip(b: ByteArray): Boolean =
        b.size >= 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() &&
            ((b[2] == 3.toByte() && b[3] == 4.toByte()) || (b[2] == 5.toByte() && b[3] == 6.toByte()))

    /** Why an entry name must never be extracted to disk, or null when it is a plain relative path. */
    fun nameProblem(name: String): String? {
        if (name.isEmpty()) return "empty name"
        if (name.length > 1024) return "name too long"
        if (name.indexOf('\u0000') >= 0) return "contains a null character"
        if (name.startsWith("/") || name.startsWith("\\")) return "absolute path"
        if (Regex("^[A-Za-z]:").containsMatchIn(name)) return "drive path"
        if (name.split('/', '\\').any { it == ".." }) return "path leaves the folder (..)"
        return null
    }

    fun index(bytes: ByteArray, limits: ExtractionLimits): ZipIndex {
        val warnings = ArrayList<String>()
        val entries = ArrayList<ZipEntryInfo>()
        var truncated = false
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var eocd = -1
        var p = bytes.size - 22
        val stop = maxOf(0, bytes.size - 22 - 65535)
        while (p >= stop) {
            if (bytes[p] == 0x50.toByte() && bytes[p + 1] == 0x4B.toByte() && bytes[p + 2] == 5.toByte() && bytes[p + 3] == 6.toByte()) { eocd = p; break }
            p--
        }
        var usedCentral = false
        if (eocd >= 0) {
            val total = bb.getShort(eocd + 10).toInt() and 0xFFFF
            val cdOffset = bb.getInt(eocd + 16).toLong() and 0xFFFFFFFFL
            if (total != 0xFFFF && cdOffset != 0xFFFFFFFFL && cdOffset < bytes.size) {
                usedCentral = true
                var pos = cdOffset.toInt()
                var count = 0
                while (count < total) {
                    if (pos + 46 > bytes.size || bb.getInt(pos) != 0x02014b50) { warnings += "The archive's file list is damaged after $count entries."; break }
                    if (entries.size >= limits.maxZipEntries) { truncated = true; break }
                    val method = bb.getShort(pos + 10).toInt() and 0xFFFF
                    val comp = bb.getInt(pos + 20).toLong() and 0xFFFFFFFFL
                    val size = bb.getInt(pos + 24).toLong() and 0xFFFFFFFFL
                    val nameLen = bb.getShort(pos + 28).toInt() and 0xFFFF
                    val extraLen = bb.getShort(pos + 30).toInt() and 0xFFFF
                    val commentLen = bb.getShort(pos + 32).toInt() and 0xFFFF
                    if (pos + 46 + nameLen > bytes.size) break
                    val name = String(bytes, pos + 46, nameLen, Charsets.UTF_8)
                    entries += ZipEntryInfo(name, comp, size, method, name.endsWith("/"), nameProblem(name))
                    pos += 46 + nameLen + extraLen + commentLen
                    count++
                }
            } else warnings += "ZIP64 archive: file sizes are measured by reading the contents."
        }
        if (!usedCentral) {
            // No usable central directory: list by reading through, within the same limits.
            try {
                ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
                    var total = 0L
                    while (true) {
                        val e = zin.nextEntry ?: break
                        if (entries.size >= limits.maxZipEntries) { truncated = true; break }
                        var size = 0L
                        val buf = ByteArray(16384)
                        while (true) {
                            val r = zin.read(buf); if (r < 0) break
                            size += r; total += r
                            if (size > limits.maxZipEntryBytes || total > limits.maxZipTotalBytes) return ZipIndex(entries, warnings, "The archive expands beyond the safety limit and was not opened.", truncated)
                        }
                        entries += ZipEntryInfo(e.name, e.compressedSize, size, e.method, e.isDirectory, nameProblem(e.name))
                    }
                }
            } catch (e: IOException) { warnings += "The archive could not be read completely: ${e.message}" }
        }
        val declared = entries.sumOf { it.size }
        val refusal = when {
            entries.any { it.size > limits.maxZipEntryBytes } -> "A file inside the archive is larger than ${limits.maxZipEntryBytes / (1024 * 1024)} MB when unpacked. Refused as a possible zip bomb."
            declared > limits.maxZipTotalBytes -> "The archive unpacks to ${declared / (1024 * 1024)} MB (limit ${limits.maxZipTotalBytes / (1024 * 1024)} MB). Refused as a possible zip bomb."
            entries.any { it.compressedSize > 0 && it.size > 50L * 1024 * 1024 && it.size / it.compressedSize > 200 } -> "A file inside the archive has an extreme compression ratio. Refused as a possible zip bomb."
            else -> null
        }
        return ZipIndex(entries, warnings, refusal, truncated)
    }

    /** Reads the entries accepted by [wanted]; every byte read is counted, whatever the file claims about its own size. */
    fun read(bytes: ByteArray, limits: ExtractionLimits, wanted: (String) -> Boolean): Map<String, ByteArray> {
        index(bytes, limits).refusal?.let { throw ZipLimitException(it) }
        val out = LinkedHashMap<String, ByteArray>()
        var total = 0L
        var count = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                if (++count > limits.maxZipEntries) throw ZipLimitException("The archive has more than ${limits.maxZipEntries} entries.")
                val keep = !e.isDirectory && nameProblem(e.name) == null && wanted(e.name)
                val sink = if (keep) ByteArrayOutputStream() else null
                var entryBytes = 0L
                val buf = ByteArray(16384)
                while (true) {
                    val r = zin.read(buf); if (r < 0) break
                    entryBytes += r; total += r
                    if (entryBytes > limits.maxZipEntryBytes) throw ZipLimitException("A file inside the archive is too large when unpacked.")
                    if (total > limits.maxZipTotalBytes) throw ZipLimitException("The archive unpacks beyond the safety limit.")
                    if (sink != null) { if (entryBytes > limits.maxXmlPartBytes) throw ZipLimitException("A document part is too large."); sink.write(buf, 0, r) }
                }
                if (sink != null) out[e.name] = sink.toByteArray()
            }
        }
        return out
    }
}
