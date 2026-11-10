package com.jarvis.assistant.tools

import java.io.File
import java.io.IOException

/**
 * The isolated folder JARVIS tools work in. Every path a tool receives is relative to this root and is checked
 * before use, so a model (or a web page or repository talking through it) can not read or write outside it, reach
 * app-private data, or follow a link out of the folder.
 */
class Workspace(rootDir: File) {
    val root: File = rootDir.canonicalFile.also { it.mkdirs() }

    class PathRejected(message: String) : IOException(message)

    /** Resolves [relative] inside the workspace or throws [PathRejected]. The target need not exist yet. */
    fun resolve(relative: String): File {
        val cleaned = relative.trim().replace('\\', '/')
        if (cleaned.isEmpty() || cleaned == ".") return root
        if (cleaned.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(cleaned)) throw PathRejected("Use a path inside the workspace, not an absolute path.")
        if (cleaned.contains('\u0000')) throw PathRejected("The path contains an invalid character.")
        if (cleaned.split('/').any { it == ".." }) throw PathRejected("The path leaves the workspace (\"..\" is not allowed).")
        var current = root
        for (part in cleaned.split('/').filter { it.isNotEmpty() && it != "." }) {
            current = File(current, part)
            // A link anywhere on the way could point out of the workspace; refuse to pass through one.
            if (current.exists() && java.nio.file.Files.isSymbolicLink(current.toPath())) throw PathRejected("Links are not followed inside the workspace.")
        }
        val canonical = current.canonicalFile
        if (canonical != root && !canonical.path.startsWith(root.path + File.separator)) throw PathRejected("The path leaves the workspace.")
        return canonical
    }

    fun relative(file: File): String = file.canonicalFile.relativeTo(root).path.replace(File.separatorChar, '/')

    /** A project folder (one website, one repository clone) directly under the workspace. */
    fun project(name: String): File {
        val safe = name.trim().lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-', '.').take(60)
        if (safe.isEmpty()) throw PathRejected("Give the project a name using letters or numbers.")
        return resolve("projects/$safe").also { it.mkdirs() }
    }

    /** Total size of everything under [dir]; used to enforce a workspace quota. */
    fun sizeOf(dir: File = root): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
