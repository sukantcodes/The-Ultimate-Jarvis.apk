package com.jarvis.assistant.tools

import java.io.File
import java.io.IOException

/**
 * The workspace and device file system controller for JARVIS tools.
 * Resolves workspace-relative paths inside [root] and allows full access to the device's internal storage
 * (e.g. /sdcard, /storage/emulated/0) while preventing traversal into restricted Linux system directories.
 */
class Workspace(rootDir: File) {
    val root: File = rootDir.canonicalFile.also { it.mkdirs() }

    class PathRejected(message: String) : IOException(message)

    companion object {
        private val RESTRICTED_SYSTEM_PREFIXES = listOf(
            "/etc", "/proc", "/sys", "/dev", "/root", "/system", "/sbin", "/vendor", "/odm", "/product"
        )
    }

    /**
     * Resolves [path] inside the workspace or across accessible device internal storage (/sdcard, /storage).
     * Throws [PathRejected] if path format is invalid, attempts to access restricted system paths, or leaves the workspace.
     */
    fun resolve(path: String): File {
        val raw = path.trim().replace('\\', '/')
        if (raw.contains('\u0000')) throw PathRejected("The path contains an invalid character.")
        if (Regex("^[A-Za-z]:").containsMatchIn(raw)) throw PathRejected("Windows drive letter paths are not supported on Android: $raw")

        // Normalize internal storage aliases (sdcard/..., storage/...) to absolute paths
        val cleaned = when {
            raw == "sdcard" || raw.startsWith("sdcard/") -> "/$raw"
            raw == "storage" || raw.startsWith("storage/") -> "/$raw"
            else -> raw
        }

        // If path is an absolute path (internal storage or device path)
        if (cleaned.startsWith("/")) {
            val isRestricted = RESTRICTED_SYSTEM_PREFIXES.any { prefix ->
                cleaned == prefix || cleaned.startsWith("$prefix/")
            }
            if (isRestricted) {
                throw PathRejected("Access to system path is restricted: $cleaned")
            }
            // Disallow relative traversal tricks within absolute paths that escape to root
            val canonical = File(cleaned).canonicalFile
            val canonicalPath = canonical.path.replace(File.separatorChar, '/')
            if (RESTRICTED_SYSTEM_PREFIXES.any { prefix -> canonicalPath == prefix || canonicalPath.startsWith("$prefix/") }) {
                throw PathRejected("Access to system path is restricted: $cleaned")
            }
            return canonical
        }

        // Relative path inside workspace root
        if (cleaned.isEmpty() || cleaned == ".") return root
        if (cleaned.split('/').any { it == ".." }) throw PathRejected("The path leaves the workspace (\"..\" is not allowed).")

        var current = root
        for (part in cleaned.split('/').filter { it.isNotEmpty() && it != "." }) {
            current = File(current, part)
            // A link anywhere on the way could point out of the workspace; refuse to pass through one.
            if (current.exists() && java.nio.file.Files.isSymbolicLink(current.toPath())) {
                throw PathRejected("Links are not followed inside the workspace.")
            }
        }
        val canonical = current.canonicalFile
        if (canonical != root && !canonical.path.startsWith(root.path + File.separator)) {
            throw PathRejected("The path leaves the workspace.")
        }
        return canonical
    }

    fun relative(file: File): String {
        val canonical = file.canonicalFile
        return if (canonical.path.startsWith(root.path)) {
            val rel = canonical.relativeTo(root).path.replace(File.separatorChar, '/')
            if (rel.isEmpty()) "." else rel
        } else {
            canonical.path.replace(File.separatorChar, '/')
        }
    }

    /** A project folder (one website, one repository clone) directly under the workspace. */
    fun project(name: String): File {
        val safe = name.trim().lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-', '.').take(60)
        if (safe.isEmpty()) throw PathRejected("Give the project a name using letters or numbers.")
        return resolve("projects/$safe").also { it.mkdirs() }
    }

    /** Total size of everything under [dir]; used to enforce a workspace quota. */
    fun sizeOf(dir: File = root): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
