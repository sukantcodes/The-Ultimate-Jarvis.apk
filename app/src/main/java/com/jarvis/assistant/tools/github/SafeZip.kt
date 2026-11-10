package com.jarvis.assistant.tools.github

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipFile

/**
 * Extracts a downloaded repository archive into a folder without trusting it. Rejects "zip slip" paths (.., absolute
 * paths, drive letters, NUL), refuses to write through a symbolic link, never creates links itself (a link entry is
 * written as an ordinary small file), skips any `.git` folder (a planted `.git/config` could run code later when git
 * is used there), and enforces limits on entries, per-file size and total unpacked size while streaming, so a
 * decompression bomb is stopped by what is actually produced, not by what the archive claims.
 */
object SafeZip {
    data class Limits(
        val maxEntries: Int = 20_000,
        val maxFileBytes: Long = 25L * 1024 * 1024,
        val maxTotalBytes: Long = 150L * 1024 * 1024
    )

    class Result(val files: Int, val bytes: Long, val skippedGit: Int, val strippedFolder: String?)

    fun extract(zipBytes: ByteArray, dest: File, limits: Limits = Limits(), stripTopFolder: Boolean = true): Result {
        dest.mkdirs()
        val tmp = File.createTempFile("jarvis-archive", ".zip", dest.parentFile ?: dest)
        try {
            tmp.writeBytes(zipBytes)
            return extract(tmp, dest, limits, stripTopFolder)
        } finally {
            tmp.delete()
        }
    }

    fun extract(zip: File, dest: File, limits: Limits = Limits(), stripTopFolder: Boolean = true): Result {
        val destCanon = dest.canonicalFile
        destCanon.mkdirs()
        val zf = try { ZipFile(zip) } catch (e: IOException) { throw GitHubException(GhErrorKind.UNSAFE, "The downloaded file is not a valid archive.") }
        zf.use { z ->
            val entries = z.entries().toList()
            if (entries.size > limits.maxEntries) throw GitHubException(GhErrorKind.TOO_LARGE, "The archive has ${entries.size} entries, more than the ${limits.maxEntries} allowed.")
            // Validate every name before writing anything.
            val names = entries.map { normalise(it.name) }
            val strip = if (stripTopFolder) commonTopFolder(names) else null
            var declared = 0L
            for (e in entries) if (!e.isDirectory && e.size > 0) declared += e.size
            if (declared > limits.maxTotalBytes) throw GitHubException(GhErrorKind.TOO_LARGE, "The archive would unpack to ${declared / 1_048_576} MB, more than the ${limits.maxTotalBytes / 1_048_576} MB allowed.")

            var files = 0; var total = 0L; var skipped = 0
            for ((idx, e) in entries.withIndex()) {
                var rel = names[idx]
                if (strip != null) rel = rel.removePrefix("$strip/")
                if (rel.isEmpty() || rel == strip) continue
                if (rel.split('/').any { it.equals(".git", ignoreCase = true) }) { skipped++; continue }
                val target = File(destCanon, rel)
                checkInside(destCanon, target)
                if (e.isDirectory) { target.mkdirs(); continue }
                target.parentFile?.let { parent ->
                    checkInside(destCanon, parent)
                    if (!parent.isDirectory && !parent.mkdirs()) throw GitHubException(GhErrorKind.UNSAFE, "The archive contains conflicting file and folder names.")
                }
                if (target.exists()) throw GitHubException(GhErrorKind.UNSAFE, "The archive contains the same path twice.")
                var written = 0L
                z.getInputStream(e).use { input ->
                    target.outputStream().use { out ->
                        val buf = ByteArray(32 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            written += n; total += n
                            if (written > limits.maxFileBytes) throw GitHubException(GhErrorKind.TOO_LARGE, "A file in the archive is larger than ${limits.maxFileBytes / 1_048_576} MB.")
                            if (total > limits.maxTotalBytes) throw GitHubException(GhErrorKind.TOO_LARGE, "The archive unpacks to more than ${limits.maxTotalBytes / 1_048_576} MB.")
                            out.write(buf, 0, n)
                        }
                    }
                }
                target.setExecutable(false)
                files++
            }
            return Result(files, total, skipped, strip)
        }
    }

    /** Turns an entry name into a clean relative path or rejects the whole archive. */
    fun normalise(raw: String): String {
        val n = raw.replace('\\', '/')
        if (n.contains('\u0000')) throw unsafe()
        if (n.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(n)) throw unsafe()
        val segs = n.split('/').filter { it.isNotEmpty() && it != "." }
        if (segs.any { it == ".." }) throw unsafe()
        return segs.joinToString("/")
    }

    private fun commonTopFolder(names: List<String>): String? {
        val nonEmpty = names.filter { it.isNotEmpty() }
        if (nonEmpty.isEmpty()) return null
        val first = nonEmpty.first().substringBefore('/')
        if (nonEmpty.any { it.substringBefore('/') != first }) return null
        // A single file at the top level is not a folder to strip.
        if (nonEmpty.all { !it.contains('/') }) return null
        return first
    }

    private fun checkInside(root: File, f: File) {
        // Walk up from f to root: every existing component must be a real directory/file, never a link, and inside root.
        var cur: File? = f
        while (cur != null && cur != root) {
            if (cur.exists() && Files.isSymbolicLink(cur.toPath())) throw GitHubException(GhErrorKind.UNSAFE, "The archive tries to write through a symbolic link.")
            cur = cur.parentFile
        }
        val canon = f.canonicalFile
        if (canon != root && !canon.path.startsWith(root.path + File.separator)) throw unsafe()
    }

    private fun unsafe() = GitHubException(GhErrorKind.UNSAFE, "The archive contains an unsafe path and was not unpacked.")
}
