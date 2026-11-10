package com.jarvis.assistant.ui.chat

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.R
import com.jarvis.assistant.tools.Workspace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/** Workspace-only command console. It never starts a system shell or arbitrary executable. */
object ChatTerminalDialog {
    private const val MAX_READ_BYTES = 512 * 1024L
    private const val MAX_OUTPUT_CHARS = 24000
    private const val MAX_WORKSPACE_BYTES = 50L * 1024L * 1024L
    private const val MAX_NETWORK_BYTES = 5L * 1024L * 1024L
    private val networkClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    fun show(context: Context) {
        val workspace = Workspace(File(context.applicationContext.filesDir, "chatbox-workspace"))
        var cwd = workspace.root
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        root.addView(TextView(context).apply {
            text = "JARVIS TERMINAL"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(context).apply {
            text = "Workspace sandbox + HTTPS internet tools. No root, system shell, or unrestricted phone-file access."
            textSize = 12f
            setPadding(0, dp(4), 0, dp(8))
        })
        val path = TextView(context).apply { textSize = 11f; typeface = Typeface.MONOSPACE }
        root.addView(path)
        val output = TextView(context).apply {
            text = "Workspace Terminal\nType help for supported commands.\n"
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setTextColor(0xFFE6F2FF.toInt())
            setBackgroundColor(0xFF101820.toInt())
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val scroll = ScrollView(context).apply { addView(output) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(240), 1f))
        val input = EditText(context).apply {
            hint = "Enter command…"
            singleLine = true
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_GO
        }
        root.addView(input)
        val buttons = LinearLayout(context).apply { gravity = Gravity.END }
        val clear = Button(context).apply { text = "Clear" }
        val run = Button(context).apply { text = "Run" }
        buttons.addView(clear)
        buttons.addView(run)
        root.addView(buttons)

        val dialog = MaterialAlertDialogBuilder(context, R.style.ThemeOverlay_Jarvis_MaterialDialog)
            .setView(root)
            .setNegativeButton("Close", null)
            .create()

        fun append(value: String) {
            output.append(value.take(MAX_OUTPUT_CHARS))
            if (output.text.length > MAX_OUTPUT_CHARS) output.text = output.text.takeLast(MAX_OUTPUT_CHARS)
            scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
        }
        fun refreshPath() { path.text = "workspace: /" + workspace.relative(cwd) }
        fun resolve(value: String): File {
            if (value.startsWith("/")) throw Workspace.PathRejected("Absolute paths are disabled.")
            val relative = if (cwd == workspace.root) value else workspace.relative(cwd) + "/" + value
            return workspace.resolve(relative)
        }
        suspend fun execute(raw: String): String = withContext(Dispatchers.IO) {
            val a = tokenize(raw)
            if (a.isEmpty()) return@withContext ""
            val cmd = a[0].lowercase()
            fun need(min: Int, max: Int = min) {
                if (a.size < min + 1 || a.size > max + 1) throw IllegalArgumentException("Invalid arguments. Type help.")
            }
            when (cmd) {
                "help" -> "Commands: pwd, ls [path], cd [folder], cat <file>, head <file> [lines], tail <file> [lines], wc <file>, grep <text> <file>, tree [path], mkdir <folder>, touch <file>, write <file> <text>, rm <file>, curl <https-url>, wget <https-url> [filename], netcheck, clear, exit.\nHTTPS internet access is available through curl/wget. This is a safe workspace command console, not a full Termux/Linux shell: arbitrary binaries, package installation, scripts, pipes, root, and phone-system commands are not enabled."
                "curl", "wget" -> {
                    need(1, if (cmd == "wget") 2 else 1)
                    val uri = validatePublicHttpsUrl(a[1])
                    val request = Request.Builder().url(uri.toASCIIString()).header("User-Agent", "JARVIS-ChatBox-Terminal/1.0").get().build()
                    networkClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code} ${response.message}. Redirects are not followed; use the final HTTPS URL.")
                        val body = response.body ?: throw IllegalStateException("The server returned an empty response.")
                        if (body.contentLength() > MAX_NETWORK_BYTES) throw IllegalStateException("Response exceeds the 5 MB terminal transfer limit.")
                        val bytes = body.byteStream().use { readLimited(it, (MAX_NETWORK_BYTES + 1).toInt()) }
                        if (bytes.size > MAX_NETWORK_BYTES) throw IllegalStateException("Response exceeds the 5 MB terminal transfer limit.")
                        if (cmd == "curl") {
                            val type = body.contentType()?.toString().orEmpty()
                            if (type.startsWith("text/") || type.contains("json") || type.contains("xml") || type.contains("javascript")) {
                                String(bytes, Charsets.UTF_8).take(16000) + if (bytes.size > 16000) "\n… output truncated …" else ""
                            } else {
                                "Downloaded ${bytes.size} bytes (content-type: ${type.ifBlank { "unknown" }}). Use wget to save binary files."
                            }
                        } else {
                            val name = a.getOrNull(2) ?: uri.path.substringAfterLast('/').takeIf { it.isNotBlank() } ?: "download.bin"
                            if (name in listOf(".", "..") || name.contains('/') || name.contains('\\') || name.any { it.isISOControl() }) {
                                throw IllegalArgumentException("Use a simple filename without folder paths.")
                            }
                            val target = resolve(name)
                            val old = if (target.isFile) target.length() else 0L
                            if (workspace.sizeOf() - old + bytes.size > MAX_WORKSPACE_BYTES) throw IllegalStateException("Workspace quota (50 MB) exceeded.")
                            target.parentFile?.mkdirs()
                            target.writeBytes(bytes)
                            "Saved ${bytes.size} bytes to /" + workspace.relative(target)
                        }
                    }
                }
                "netcheck" -> {
                    need(0)
                    val request = Request.Builder().url("https://example.com/").head().build()
                    networkClient.newCall(request).execute().use { response ->
                        "Internet reachable: HTTP ${response.code} from example.com"
                    }
                }
                "pwd" -> "/" + workspace.relative(cwd)
                "ls" -> {
                    need(0, 1)
                    val dir = if (a.size == 2) resolve(a[1]) else cwd
                    if (!dir.isDirectory) throw IllegalArgumentException("Not a directory.")
                    dir.listFiles()?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
                        ?.joinToString("\n") { f -> (if (f.isDirectory) "DIR  " else "FILE ") + f.name + (if (f.isFile) " (" + f.length() + " bytes)" else "") }
                        .orEmpty().ifBlank { "(empty)" }
                }
                "cd" -> {
                    need(0, 1)
                    val next = when {
                        a.size == 1 || a[1] == "/" -> workspace.root
                        a[1] == ".." -> if (cwd == workspace.root) workspace.root else cwd.parentFile ?: workspace.root
                        else -> resolve(a[1])
                    }
                    if (!next.isDirectory || (next != workspace.root && !next.canonicalPath.startsWith(workspace.root.canonicalPath + File.separator))) {
                        throw IllegalArgumentException("Folder not found or outside workspace.")
                    }
                    cwd = next.canonicalFile
                    "/" + workspace.relative(cwd)
                }
                "cat", "head", "tail", "wc" -> {
                    need(1, if (cmd == "head" || cmd == "tail") 2 else 1)
                    val file = resolve(a[1])
                    if (!file.isFile || file.length() > MAX_READ_BYTES) throw IllegalArgumentException("File missing or larger than 512 KB.")
                    val body = file.readText(Charsets.UTF_8)
                    when (cmd) {
                        "cat" -> body
                        "head" -> body.lineSequence().take(a.getOrNull(2)?.toIntOrNull()?.coerceIn(1, 200) ?: 10).joinToString("\n")
                        "tail" -> body.lineSequence().toList().takeLast(a.getOrNull(2)?.toIntOrNull()?.coerceIn(1, 200) ?: 10).joinToString("\n")
                        else -> body.lineSequence().count().toString() + " lines, " + body.split(Regex("\\s+")).count { it.isNotBlank() } + " words, " + file.length() + " bytes"
                    }
                }
                "grep" -> {
                    need(2)
                    val file = resolve(a[2])
                    if (!file.isFile || file.length() > MAX_READ_BYTES) throw IllegalArgumentException("File missing or larger than 512 KB.")
                    file.readLines(Charsets.UTF_8).mapIndexedNotNull { i, line -> if (line.contains(a[1], true)) (i + 1).toString() + ": " + line else null }
                        .take(200).joinToString("\n").ifBlank { "No matches." }
                }
                "tree" -> {
                    need(0, 1)
                    val start = if (a.size == 2) resolve(a[1]) else cwd
                    if (!start.isDirectory) throw IllegalArgumentException("Not a directory.")
                    buildString {
                        fun visit(dir: File, prefix: String, depth: Int) {
                            if (depth > 3 || length > 10000) return
                            dir.listFiles()?.sortedBy { it.name.lowercase() }?.forEach { f ->
                                append(prefix).append(if (f.isDirectory) "DIR  " else "FILE ").append(f.name).append('\n')
                                if (f.isDirectory) visit(f, prefix + "  ", depth + 1)
                            }
                        }
                        append(start.name.ifBlank { "workspace" }).append("/\n")
                        visit(start, "  ", 0)
                    }
                }
                "mkdir" -> {
                    need(1)
                    val dir = resolve(a[1])
                    if (dir.exists() || !dir.mkdirs()) throw IllegalStateException("Could not create folder.")
                    "Created " + workspace.relative(dir)
                }
                "touch" -> {
                    need(1)
                    val file = resolve(a[1])
                    if (file.exists()) "Already exists: " + workspace.relative(file)
                    else {
                        if (workspace.sizeOf() >= MAX_WORKSPACE_BYTES) throw IllegalStateException("Workspace quota reached.")
                        file.parentFile?.mkdirs()
                        if (!file.createNewFile()) throw IllegalStateException("Could not create file.")
                        "Created " + workspace.relative(file)
                    }
                }
                "write" -> {
                    need(2)
                    val file = resolve(a[1])
                    val bytes = a[2].toByteArray(Charsets.UTF_8)
                    val old = if (file.isFile) file.length() else 0L
                    if (workspace.sizeOf() - old + bytes.size > MAX_WORKSPACE_BYTES) throw IllegalStateException("Workspace quota (50 MB) exceeded.")
                    file.parentFile?.mkdirs()
                    file.writeBytes(bytes)
                    "Wrote " + bytes.size + " bytes to " + workspace.relative(file)
                }
                "rm" -> {
                    need(1)
                    val file = resolve(a[1])
                    if (!file.isFile) throw IllegalArgumentException("Only individual files can be removed.")
                    "DELETE_CONFIRM:" + workspace.relative(file)
                }
                "clear" -> "CLEAR_TERMINAL"
                "exit" -> "EXIT_TERMINAL"
                else -> throw IllegalArgumentException("Unknown command: " + cmd + ". Type help.")
            }
        }

        suspend fun runCommand(raw: String) {
            if (raw.isBlank()) return
            append("\n$ " + raw + "\n")
            try {
                val result = execute(raw)
                when {
                    result == "CLEAR_TERMINAL" -> output.text = ""
                    result == "EXIT_TERMINAL" -> dialog.dismiss()
                    result.startsWith("DELETE_CONFIRM:") -> {
                        val relative = result.substringAfter(':')
                        withContext(Dispatchers.Main) {
                            MaterialAlertDialogBuilder(context, R.style.ThemeOverlay_Jarvis_MaterialDialog)
                                .setTitle("Delete workspace file?")
                                .setMessage("Permanently delete " + relative + "?")
                                .setNegativeButton("Cancel", null)
                                .setPositiveButton("Delete") { _, _ ->
                                    scope.launch {
                                        val deleted = withContext(Dispatchers.IO) {
                                            runCatching { workspace.resolve(relative).takeIf { it.isFile }?.delete() == true }.getOrDefault(false)
                                        }
                                        append(if (deleted) "Deleted " + relative + "\n" else "Could not delete file.\n")
                                    }
                                }.show()
                        }
                    }
                    else -> append(result + "\n")
                }
                refreshPath()
            } catch (e: Exception) {
                append("Error: " + (e.message ?: "command failed") + "\n")
            }
        }

        run.setOnClickListener {
            val command = input.text.toString()
            input.text?.clear()
            scope.launch { runCommand(command) }
        }
        clear.setOnClickListener { output.text = "" }
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_GO || action == EditorInfo.IME_ACTION_DONE) {
                run.performClick()
                true
            } else false
        }
        dialog.setOnDismissListener { scope.cancel() }
        refreshPath()
        dialog.show()
        dialog.window?.setLayout((context.resources.displayMetrics.widthPixels * 0.94f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun readLimited(input: java.io.InputStream, limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() < limit) {
            val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun validatePublicHttpsUrl(raw: String): URI {
        val uri = runCatching { URI(raw) }.getOrElse { throw IllegalArgumentException("Invalid URL.") }
        if (!uri.scheme.equals("https", true) || uri.host.isNullOrBlank() || uri.userInfo != null) {
            throw IllegalArgumentException("Only public HTTPS URLs are supported.")
        }
        val host = uri.host.lowercase()
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) {
            throw IllegalArgumentException("Local and private network addresses are blocked.")
        }
        val addresses = runCatching { InetAddress.getAllByName(host) }
            .getOrElse { throw IllegalArgumentException("Could not resolve the internet host.") }
        if (addresses.isEmpty() || addresses.any {
                it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress ||
                    it.isSiteLocalAddress || it.hostAddress.startsWith("fc", true) ||
                    it.hostAddress.startsWith("fd", true)
            }) {
            throw IllegalArgumentException("Local and private network addresses are blocked.")
        }
        return uri
    }

    private fun tokenize(raw: String): List<String> {
        val result = ArrayList<String>()
        val token = StringBuilder()
        var quote: Char? = null
        var escaped = false
        for (c in raw) {
            if (escaped) { token.append(c); escaped = false; continue }
            if (c == '\\' && quote != '\'') { escaped = true; continue }
            if (quote != null) {
                if (c == quote) quote = null else token.append(c)
            } else when (c) {
                '\'', '"' -> quote = c
                ' ', '\t' -> if (token.isNotEmpty()) { result.add(token.toString()); token.clear() }
                else -> token.append(c)
            }
        }
        if (escaped || quote != null) throw IllegalArgumentException("Unclosed quote or escape.")
        if (token.isNotEmpty()) result.add(token.toString())
        return result
    }
}
