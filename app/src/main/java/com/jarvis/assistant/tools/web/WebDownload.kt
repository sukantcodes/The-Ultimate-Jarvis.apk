package com.jarvis.assistant.tools.web

import com.google.gson.JsonObject
import com.jarvis.assistant.tools.*
import com.jarvis.assistant.tools.builtin.int
import com.jarvis.assistant.tools.builtin.str
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class RawResponse(val code: Int, val headers: Map<String, String>, val body: InputStream, val close: () -> Unit)

/** Downloads a file into the workspace. Every hop (including redirects) is checked by [UrlGuard]; size is enforced while streaming. */
class WebDownloader(
    private val guard: UrlGuard = UrlGuard(),
    private val maxBytes: Long = 50L * 1024 * 1024,
    private val maxRedirects: Int = 5,
    private val connect: (String) -> RawResponse = { url ->
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false; c.connectTimeout = 15_000; c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) JarvisAssistant/1.0")
        val code = c.responseCode
        RawResponse(code, c.headerFields.filterKeys { it != null }.mapValues { it.value.lastOrNull().orEmpty() }, if (code in 200..299) c.inputStream else (c.errorStream ?: java.io.ByteArrayInputStream(ByteArray(0))), { c.disconnect() })
    }
) {
    class Result(val file: File, val bytes: Long, val finalUrl: String, val contentType: String)

    fun download(url: String, target: File): Result {
        var current = guard.check(url); var hops = 0
        while (true) {
            val r = try { connect(current.toString()) } catch (e: java.io.IOException) { throw WebException(WebException.Kind.NETWORK, "The site could not be reached.") }
            try {
                if (r.code in 300..399) {
                    val loc = r.headers.entries.firstOrNull { it.key.equals("location", true) }?.value ?: throw WebException(WebException.Kind.HTTP, "Redirect without a location.")
                    if (++hops > maxRedirects) throw WebException(WebException.Kind.HTTP, "Too many redirects.")
                    current = guard.check(current.resolve(loc).toString()); continue
                }
                if (r.code !in 200..299) throw WebException(WebException.Kind.HTTP, "The site answered with HTTP ${r.code}.")
                val declared = r.headers.entries.firstOrNull { it.key.equals("content-length", true) }?.value?.toLongOrNull()
                if (declared != null && declared > maxBytes) throw WebException(WebException.Kind.TOO_LARGE, "The file is ${declared / 1_048_576} MB, more than the ${maxBytes / 1_048_576} MB limit.")
                target.parentFile?.mkdirs()
                val tmp = File(target.path + ".part")
                var total = 0L
                try {
                    tmp.outputStream().use { out -> val buf = ByteArray(32 * 1024); while (true) { val n = r.body.read(buf); if (n < 0) break; total += n
                        if (total > maxBytes) throw WebException(WebException.Kind.TOO_LARGE, "The download is larger than ${maxBytes / 1_048_576} MB and was stopped."); out.write(buf, 0, n) } }
                    if (!tmp.renameTo(target)) { target.writeBytes(tmp.readBytes()); tmp.delete() }
                } catch (e: Exception) { tmp.delete(); throw if (e is WebException) e else WebException(WebException.Kind.NETWORK, "The download was interrupted.") }
                return Result(target, total, current.toString(), r.headers.entries.firstOrNull { it.key.equals("content-type", true) }?.value.orEmpty())
            } finally { r.close() }
        }
    }
}

class WebDownloadTool(private val downloader: WebDownloader = WebDownloader()) : Tool {
    override val spec = ToolSpec("web_download", "Download a file from the internet into the workspace (default folder downloads/). Downloaded files are never run.",
        listOf(ToolParam("url", required = true), ToolParam("path", description = "Where to save it, e.g. downloads/data.zip.")), Risk.WRITE, "web")
    override fun describe(args: JsonObject) = "Download ${args.str("url")?.substringBefore('?')}"
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult { return try {
        val url = args.str("url") ?: return ToolResult.Error("url is required")
        val name = args.str("path") ?: ("downloads/" + (java.net.URI(url).path.substringAfterLast('/').ifBlank { "download" }.replace(Regex("[^A-Za-z0-9._-]"), "_")))
        val target = ctx.workspace.resolve(name)
        if (target.exists()) return ToolResult.Error("$name already exists.")
        val r = downloader.download(url, target)
        ToolResult.Ok("Saved $name (${r.bytes} bytes, ${r.contentType.substringBefore(';').ifBlank { "unknown type" }}). It was not opened or run.", listOf(ToolArtifact(target.path, target.name, r.contentType.substringBefore(';'))))
    } catch (e: WebException) { ToolResult.Error(e.message ?: "Download failed") } }
}
