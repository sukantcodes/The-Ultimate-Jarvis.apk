package com.jarvis.assistant.background

import java.io.File
import java.util.Locale

/** Reads code and pulls out the names that say what a file is for. Works on any language by file type. */
internal object CodeInsights {
    private val jsFn = Regex("""(?:function\s+([A-Za-z_$][\w$]*)|(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=\s*(?:async\s*)?(?:\([^)]*\)|[A-Za-z_$][\w$]*)\s*=>|class\s+([A-Za-z_$][\w$]*))""")
    private val pyFn = Regex("""(?m)^\s*(?:def|class)\s+([A-Za-z_]\w*)""")
    private val kotlinFn = Regex("""(?m)\b(?:fun|class|object)\s+([A-Za-z_]\w*)""")
    private val cssSel = Regex("""(?m)^\s*([.#]?[A-Za-z][\w\-.#:\s>,+~\[\]="]*?)\s*\{""")
    private val htmlId = Regex("""\bid\s*=\s*["']([^"']+)["']""")
    private val htmlTag = Regex("""<(nav|header|footer|section|form|canvas|table|main|aside|video|audio)\b""", RegexOption.IGNORE_CASE)

    fun symbols(fileName: String, text: String, limit: Int = 8): List<String> {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val raw: Sequence<String> = when (ext) {
            "js", "mjs", "ts", "jsx", "tsx" -> jsFn.findAll(text).map { m -> m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }.orEmpty() }
            "py" -> pyFn.findAll(text).map { it.groupValues[1] }
            "kt", "java", "go", "rs", "swift" -> kotlinFn.findAll(text).map { it.groupValues[1] }
            "css", "scss" -> cssSel.findAll(text).map { it.groupValues[1].trim().take(28) }
            "html", "htm" -> htmlTag.findAll(text).map { it.groupValues[1].lowercase(Locale.ROOT) } + htmlId.findAll(text).map { it.groupValues[1] }
            else -> emptySequence()
        }
        return raw.filter { it.isNotBlank() }.distinct().take(limit).toList()
    }

    /** A plain-words role for a file type, used only when no AI model is available to describe the code. */
    fun role(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "html", "htm" -> "page structure"
        "css", "scss" -> "styling"
        "js", "mjs", "ts", "jsx", "tsx" -> "logic"
        "json" -> "data"
        "md" -> "documentation"
        "py", "kt", "java", "go", "rs" -> "program code"
        else -> "file"
    }
}

/**
 * Decides what Jarvis says while a build runs, and says each thing once.
 *
 * Every tick it looks at what really changed on disk (new files, how much a file grew, which declarations
 * appeared). If something meaningful is new, it asks an AI model to say, in the owner's language, which PART of the
 * project the new code is for and what it does, given the real code and everything already said. The model may answer
 * SKIP when there is nothing new. Progress numbers are only added when nothing else is new for a while. Near-repeats
 * of earlier lines are dropped. With no model available, a short factual line in plain words is used instead.
 */
internal class BuildNarrator(
    private val request: () -> String,
    private val language: () -> String,
    /** (system prompt, user prompt) -> the model's reply, or null when no model could answer. */
    private val ask: suspend (String, String) -> String?,
    /** Localised plain-words fallback: (file, role, symbols, isNewFile) -> a sentence. */
    private val fallback: (String, String, List<String>, Boolean) -> String,
    private val smartPurpose: ((file: String, content: String, isNew: Boolean) -> String)? = null
) {
    private class Known(var length: Long)

    private val known = HashMap<String, Known>()
    private val narratedLines = HashMap<String, Int>()
    private val spoken = ArrayDeque<String>()
    private var lastSpokeAt = 0L
    private var lastDoneSpoken = false

    /** Returns the sentence to speak now, or null to stay quiet. */
    suspend fun tick(dir: File, files: List<File>, percent: Int, etaSec: Int, workerDone: Boolean, minGapMs: Long, nudgeAfterMs: Long): String? {
        val now = System.currentTimeMillis()
        val rel = files.associateBy { it.relativeTo(dir).path }
        val newFiles = rel.keys.filter { it !in known }
        val changed = rel.filter { (path, f) -> known[path]?.length != f.length() }.keys
        rel.forEach { (path, f) -> known.getOrPut(path) { Known(0) }.length = f.length() }

        val focusPath = changed.maxByOrNull { rel.getValue(it).lastModified() }
        val focusFile = focusPath?.let { rel.getValue(it) }
        val text = focusFile?.let { runCatching { it.readText() }.getOrNull() }.orEmpty()
        val lines = if (text.isEmpty()) 0 else text.lines().size
        val before = focusPath?.let { narratedLines[it] } ?: 0
        val grew = lines - before
        val isNew = focusPath != null && focusPath in newFiles
        val doneNow = workerDone && !lastDoneSpoken
        val significant = focusPath != null && (isNew || grew >= 5) || doneNow

        if (now - lastSpokeAt < minGapMs) return null
        if (!significant && now - lastSpokeAt < nudgeAfterMs) return null

        val symbols = if (focusFile != null) CodeInsights.symbols(focusFile.name, text) else emptyList()
        val added = when {
            focusFile == null -> ""
            before > 0 && grew > 0 -> text.lines().drop(before).joinToString("\n").takeLast(1_400)
            text.length > 1_400 -> text.take(1_000) + "\n…\n" + text.takeLast(400)
            else -> text
        }
        val fileList = rel.entries.joinToString(", ") { (p, f) -> "$p (${runCatching { f.readLines().size }.getOrDefault(0)} lines)" }.take(500)
        val includeProgress = !significant || doneNow

        val system = "You are JARVIS, speaking aloud to the owner while their project is being built. " +
            "Speak ONLY in ${language()} (if that is Hinglish, write Hindi in Roman letters). Use one or two short, natural spoken sentences: plain text, no markdown, no emojis, " +
            "and never read file extensions aloud as 'dot js'. " +
            "State which file is being created or updated AND explain the exact purpose of that file and the features, layout, or animations being added. " +
            "Analyze the real code and use concrete names you can see (for example the 60fps game loop, collision checks, responsive navigation bar, Web Audio sound effects, or colour theme). " +
            "If you notice an obvious bug in the code shown, mention it briefly. " +
            "Never repeat or reword anything listed under ALREADY SAID. " +
            "If there is nothing meaningful and new to say, answer exactly: SKIP"
        val prompt = buildString {
            append("Owner's request: \"").append(request().take(300)).append("\"\n")
            append("Files so far: ").append(fileList.ifBlank { "none yet" }).append('\n')
            if (focusPath != null && significant) {
                append("Newest work: ").append(focusPath).append(" (").append(lines).append(" lines, ")
                append(if (isNew) "just created" else "+$grew new lines").append(")\n")
                if (symbols.isNotEmpty()) append("Declarations in it: ").append(symbols.joinToString(", ")).append('\n')
                append("New code:\n<<<\n").append(added).append("\n>>>\n")
            } else {
                append("Nothing new has been written for a while; the model is still working.\n")
            }
            if (doneNow) append("All files are now written; the project is about to be checked and opened.\n")
            append("ALREADY SAID:\n")
            if (spoken.isEmpty()) append("- (nothing yet)\n") else spoken.forEach { append("- ").append(it).append('\n') }
            if (includeProgress) append("Progress: ").append(percent).append("% done, about ").append(etaSec).append(" seconds left. You may mention it briefly.\n")
            else append("Do not mention percentages or time.\n")
        }

        val reply = ask(system, prompt)
        val line: String? = when {
            reply == null -> if (significant && focusPath != null) {
                smartPurpose?.invoke(focusFile!!.name, text, isNew) ?: fallback(focusFile!!.name, CodeInsights.role(focusFile.name), symbols, isNew)
            } else null
            reply.trim().trim('.', '!', '"').equals("SKIP", ignoreCase = true) -> null
            else -> clean(reply)
        }
        // The same moment is never narrated twice, whether it produced a line or the model chose to stay quiet.
        if (focusPath != null) narratedLines[focusPath] = lines
        if (doneNow) lastDoneSpoken = true
        if (line.isNullOrBlank() || isRepeat(line)) return null
        spoken.addLast(line)
        while (spoken.size > 8) spoken.removeFirst()
        lastSpokeAt = now
        return line
    }

    private fun clean(s: String): String =
        s.replace(Regex("[*_`#>]+"), "").replace(Regex("\\s+"), " ").trim().trim('"').take(280)

    private fun words(s: String): Set<String> =
        s.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{M}\\p{N}]+")).filter { it.length > 2 }.toSet()

    /** True when [line] says almost the same thing as something already spoken. */
    private fun isRepeat(line: String): Boolean {
        val w = words(line)
        if (w.isEmpty()) return true
        return spoken.any { old ->
            val o = words(old)
            val inter = w.intersect(o).size.toDouble()
            val union = w.union(o).size.toDouble()
            union > 0 && inter / union >= 0.6
        }
    }
}
