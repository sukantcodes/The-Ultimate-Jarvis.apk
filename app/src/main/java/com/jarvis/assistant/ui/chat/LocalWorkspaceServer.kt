package com.jarvis.assistant.ui.chat

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Static preview server for ChatBox workspace projects. Loopback only; never executes uploaded code. */
object LocalWorkspaceServer {
    private const val MAX_REQUEST_LINE = 4096
    private const val MAX_HEADER_BYTES = 8192
    private const val MAX_FILE_BYTES = 20L * 1024L * 1024L
    private val running = AtomicBoolean(false)
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var activePort = -1

    fun isRunning(): Boolean = running.get()
    fun port(): Int = activePort
    fun url(): String = if (isRunning()) "http://127.0.0.1:$activePort/" else ""

    @Synchronized
    fun start(root: File, port: Int = 8765): String {
        require(port in 1024..65535) { "Choose a port between 1024 and 65535." }
        if (running.get()) return url()
        val canonicalRoot = root.canonicalFile
        require(canonicalRoot.isDirectory) { "ChatBox workspace folder is missing." }
        val socket = ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        activePort = socket.localPort
        running.set(true)
        Thread({
            while (running.get()) {
                try {
                    val client = socket.accept()
                    Thread({ handleClient(client, canonicalRoot) }, "JarvisLocalHttpClient").apply {
                        isDaemon = true
                        start()
                    }
                } catch (_: Exception) {
                    if (running.get()) stop()
                }
            }
        }, "JarvisLocalHttpServer").apply { isDaemon = true; start() }
        return url()
    }

    @Synchronized
    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        activePort = -1
    }

    private fun handleClient(socket: java.net.Socket, root: File) {
        socket.use { client ->
            client.soTimeout = 5000
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())
            try {
                val requestLine = readAsciiLine(input, MAX_REQUEST_LINE) ?: return
                val parts = requestLine.split(' ')
                if (parts.size != 3) { respond(output, 400, "Bad Request", "Malformed HTTP request."); return }
                val method = parts[0].uppercase(Locale.ROOT)
                if (method != "GET" && method != "HEAD") {
                    respond(output, 405, "Method Not Allowed", "Only GET and HEAD are supported.", "Allow: GET, HEAD\r\n")
                    return
                }
                var headerBytes = 0
                while (true) {
                    val line = readAsciiLine(input, MAX_HEADER_BYTES) ?: break
                    headerBytes += line.length + 2
                    if (headerBytes > MAX_HEADER_BYTES) { respond(output, 431, "Request Header Fields Too Large", "Headers are too large."); return }
                    if (line.isEmpty()) break
                }
                val rawPath = runCatching { java.net.URI(parts[1]).rawPath ?: "/" }.getOrElse {
                    respond(output, 400, "Bad Request", "Invalid request path."); return
                }
                val decoded = runCatching { URLDecoder.decode(rawPath.replace("+", "%2B"), "UTF-8") }.getOrElse {
                    respond(output, 400, "Bad Request", "Invalid URL encoding."); return
                }
                if (decoded.contains('\\') || decoded.split('/').any { it == ".." || it == "." }) {
                    respond(output, 403, "Forbidden", "Path traversal is not allowed."); return
                }
                val relative = decoded.trimStart('/').ifBlank { "index.html" }
                val target = File(root, relative).canonicalFile
                if (target != root && !target.path.startsWith(root.path + File.separator)) {
                    respond(output, 403, "Forbidden", "Path is outside the workspace."); return
                }
                if (!target.isFile) { respond(output, 404, "Not Found", "File not found in ChatBox workspace."); return }
                if (target.length() > MAX_FILE_BYTES) { respond(output, 413, "Payload Too Large", "File exceeds the 20 MB preview limit."); return }
                val headers = "HTTP/1.1 200 OK\r\nContent-Type: ${mimeType(target.extension)}\r\nContent-Length: ${target.length()}\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nContent-Security-Policy: default-src 'self' 'unsafe-inline' data:; object-src 'none'; frame-ancestors 'none'\r\nConnection: close\r\n\r\n"
                output.write(headers.toByteArray(StandardCharsets.US_ASCII))
                if (method == "GET") target.inputStream().use { it.copyTo(output) }
                output.flush()
            } catch (_: Exception) {
                runCatching { respond(output, 500, "Internal Server Error", "Unable to serve this workspace file.") }
            }
        }
    }

    private fun readAsciiLine(input: BufferedInputStream, limit: Int): String? {
        val bytes = java.io.ByteArrayOutputStream()
        while (bytes.size() <= limit) {
            val value = input.read()
            if (value == -1) return if (bytes.size() == 0) null else bytes.toString("US-ASCII")
            if (value == 10) break
            if (value != 13) bytes.write(value)
        }
        if (bytes.size() > limit) throw IllegalArgumentException("HTTP line too long.")
        return bytes.toString("US-ASCII")
    }

    private fun respond(output: BufferedOutputStream, code: Int, reason: String, message: String, extra: String = "") {
        val body = message.toByteArray(StandardCharsets.UTF_8)
        val headers = "HTTP/1.1 $code $reason\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${body.size}\r\n${extra}Connection: close\r\n\r\n"
        output.write(headers.toByteArray(StandardCharsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    private fun mimeType(extension: String): String = when (extension.lowercase(Locale.ROOT)) {
        "html", "htm" -> "text/html; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "js", "mjs" -> "text/javascript; charset=utf-8"
        "json", "map" -> "application/json; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "ico" -> "image/x-icon"
        "txt", "md", "csv", "xml", "yml", "yaml" -> "text/plain; charset=utf-8"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        else -> "application/octet-stream"
    }
}
