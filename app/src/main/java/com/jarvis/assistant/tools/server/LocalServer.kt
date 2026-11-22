package com.jarvis.assistant.tools.server

import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A small static-file web server for previewing a website the assistant built. It listens on 127.0.0.1 only, so it is
 * reachable from this phone and nowhere else. It serves GET/HEAD, never lists folders, never leaves [root], and never
 * follows links. Nothing here runs project code.
 */
class LocalServer(val root: File, val port: Int = 0, private val spaFallback: Boolean = false) {
    private val rootCanon = root.canonicalFile
    private var socket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val pool = Executors.newFixedThreadPool(16) { r -> Thread(r, "jarvis-preview").also { it.isDaemon = true } }
    val requestCount = AtomicInteger(0)

    val boundPort: Int get() = socket?.localPort ?: -1
    val isRunning: Boolean get() = running.get()
    val url: String get() = "http://127.0.0.1:$boundPort/"

    @Synchronized fun start() {
        if (running.get()) return
        if (!rootCanon.isDirectory) throw IOException("The project folder does not exist.")
        val s = ServerSocket(port, 20, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        socket = s; running.set(true)
        Thread({ acceptLoop(s) }, "jarvis-preview-accept").also { it.isDaemon = true }.start()
    }

    @Synchronized fun stop() {
        running.set(false)
        try { socket?.close() } catch (e: IOException) {}
        socket = null; pool.shutdownNow()
    }

    private fun acceptLoop(s: ServerSocket) {
        while (running.get()) {
            val c = try { s.accept() } catch (e: IOException) { break }
            try { pool.execute { handle(c) } } catch (e: Exception) { try { c.close() } catch (x: IOException) {} }
        }
    }

    private fun handle(c: Socket) {
        c.use {
            try {
                c.soTimeout = 5_000
                val inp = BufferedInputStream(c.getInputStream())
                val head = readHead(inp) ?: return
                requestCount.incrementAndGet()
                val lines = head.split("\r\n")
                val parts = lines[0].split(' ')
                if (parts.size < 3 || !parts[2].startsWith("HTTP/")) return respond(c, 400, "Bad Request", "text/plain", "Bad request".toByteArray(), false)
                val method = parts[0]
                if (method != "GET" && method != "HEAD") return respond(c, 405, "Method Not Allowed", "text/plain", "Only GET and HEAD are supported.".toByteArray(), false, mapOf("Allow" to "GET, HEAD"))
                val host = lines.firstOrNull { it.startsWith("Host:", true) }?.substringAfter(':')?.trim()?.substringBeforeLast(':').orEmpty()
                // Refuse DNS-rebinding style requests: only the loopback names are served.
                if (host.isNotEmpty() && host != "127.0.0.1" && host != "localhost") return respond(c, 403, "Forbidden", "text/plain", "Forbidden".toByteArray(), false)
                val file = resolve(parts[1]) ?: return respond(c, 404, "Not Found", "text/html; charset=utf-8", notFound(), method == "HEAD")
                if (file == FORBIDDEN) return respond(c, 403, "Forbidden", "text/plain", "Forbidden".toByteArray(), method == "HEAD")
                val rangeHeader = lines.firstOrNull { it.startsWith("Range:", true) }?.substringAfter(':')?.trim()
                serveFile(c, file, rangeHeader, method == "HEAD")
            } catch (e: SocketTimeoutException) {
            } catch (e: IOException) {
            }
        }
    }

    /**
     * Sends [file], honouring one `Range: bytes=` request. Browsers on iPhones will not play video or audio from a server
     * that ignores Range, so previews shared through a public link need it. A slice is capped at 8 MB per answer (the
     * browser simply asks for the next part); a range past the end is a 416.
     */
    private fun serveFile(c: Socket, file: File, rangeHeader: String?, headOnly: Boolean) {
        val len = file.length()
        val type = mimeFor(file.name)
        val range = parseRange(rangeHeader, len)
        if (range == null) return respond(c, 200, "OK", type, file.readBytes(), headOnly, mapOf("Accept-Ranges" to "bytes"))
        if (range.isEmpty()) return respond(c, 416, "Range Not Satisfiable", "text/plain", "Range not satisfiable".toByteArray(), headOnly, mapOf("Content-Range" to "bytes */$len"))
        val bytes = java.io.RandomAccessFile(file, "r").use { f ->
            f.seek(range.first); ByteArray((range.last - range.first + 1).toInt()).also { f.readFully(it) }
        }
        respond(c, 206, "Partial Content", type, bytes, headOnly, mapOf("Accept-Ranges" to "bytes", "Content-Range" to "bytes ${range.first}-${range.last}/$len"))
    }

    private fun readHead(inp: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 16_384) {
            val b = inp.read(); if (b < 0) return null
            sb.append(b.toChar())
            if (sb.endsWith("\r\n\r\n")) return sb.toString()
        }
        return null
    }

    internal fun resolve(rawTarget: String): File? {
        var path = rawTarget.substringBefore('?').substringBefore('#')
        path = try { URLDecoder.decode(path.replace("+", "%2B"), "UTF-8") } catch (e: Exception) { return FORBIDDEN }
        if (path.contains('\u0000') || path.contains('\\')) return FORBIDDEN
        if (path.split('/').any { it == ".." }) return FORBIDDEN
        if (path.split('/').any { it.startsWith(".") && it.isNotEmpty() }) return FORBIDDEN   // .git, .env, dotfiles
        var f = File(rootCanon, path.trimStart('/'))
        var cur: File? = f
        while (cur != null && cur != rootCanon) { if (cur.exists() && Files.isSymbolicLink(cur.toPath())) return FORBIDDEN; cur = cur.parentFile }
        if (f.isDirectory) f = File(f, "index.html")
        val canon = try { f.canonicalFile } catch (e: IOException) { return FORBIDDEN }
        if (!canon.path.startsWith(rootCanon.path + File.separator)) return FORBIDDEN
        if (canon.isFile) return canon
        if (spaFallback && !File(path).name.contains('.')) File(rootCanon, "index.html").takeIf { it.isFile }?.let { return it }
        return null
    }

    private fun notFound() = "<!doctype html><meta charset=utf-8><title>Not found</title><h1>404</h1><p>That file does not exist in this project.</p>".toByteArray()

    private fun respond(c: Socket, code: Int, reason: String, type: String, body: ByteArray, headOnly: Boolean, extra: Map<String, String> = emptyMap()) {
        val out = c.getOutputStream()
        val h = StringBuilder("HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n")
        extra.forEach { (k, v) -> h.append(k).append(": ").append(v).append("\r\n") }
        h.append("\r\n"); out.write(h.toString().toByteArray(Charsets.ISO_8859_1))
        if (!headOnly) out.write(body); out.flush()
    }

    companion object {
        private val UNSATISFIABLE = -1L..-2L
        private const val MAX_SLICE = 8L * 1024 * 1024

        /** The byte range to send, null for "the whole file" (no header, or one this server does not handle), or [UNSATISFIABLE]. */
        internal fun parseRange(header: String?, length: Long): LongRange? {
            if (header == null || !header.startsWith("bytes=") || header.contains(',')) return null
            val spec = header.removePrefix("bytes=").trim()
            val dash = spec.indexOf('-'); if (dash < 0) return null
            val a = spec.substring(0, dash).trim(); val b = spec.substring(dash + 1).trim()
            val start: Long; var end: Long
            if (a.isEmpty()) {
                val suffix = b.toLongOrNull() ?: return null
                if (suffix <= 0 || length == 0L) return UNSATISFIABLE
                start = (length - suffix).coerceAtLeast(0); end = length - 1
            } else {
                start = a.toLongOrNull() ?: return null
                end = if (b.isEmpty()) length - 1 else (b.toLongOrNull() ?: return null)
                if (end < start) return null
            }
            if (start >= length) return UNSATISFIABLE
            end = minOf(end, length - 1, start + MAX_SLICE - 1)
            return start..end
        }

        private val FORBIDDEN = File("/\u0000forbidden")
        fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> "text/html; charset=utf-8"; "css" -> "text/css; charset=utf-8"; "js", "mjs" -> "text/javascript; charset=utf-8"
            "json", "map" -> "application/json; charset=utf-8"; "svg" -> "image/svg+xml"; "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"; "webp" -> "image/webp"; "ico" -> "image/x-icon"; "txt", "md" -> "text/plain; charset=utf-8"; "xml" -> "application/xml"
            "woff" -> "font/woff"; "woff2" -> "font/woff2"; "ttf" -> "font/ttf"; "mp3" -> "audio/mpeg"; "mp4" -> "video/mp4"; "pdf" -> "application/pdf"; "wasm" -> "application/wasm"
            else -> "application/octet-stream"
        }
    }
}

/**
 * Keeps track of running previews (at most [maxServers]) so they can be listed and stopped. Listeners registered with
 * [addStopListener] hear about every preview that is stopped here, with the port it was using, so something built on
 * top of a preview (a public tunnel, for example) can end together with it. Listeners run after the manager's lock is
 * released, so they may call back into the manager.
 */
class ServerManager(private val maxServers: Int = 3) {
    private val servers = LinkedHashMap<String, LocalServer>()
    private val stopListeners = java.util.concurrent.CopyOnWriteArrayList<(name: String, port: Int) -> Unit>()

    /** Calls [listener] with the project name and port whenever a preview is stopped. Close the result to unregister. */
    fun addStopListener(listener: (name: String, port: Int) -> Unit): AutoCloseable {
        stopListeners.add(listener)
        return AutoCloseable { stopListeners.remove(listener) }
    }

    @Synchronized fun start(name: String, dir: File, spa: Boolean = false): LocalServer {
        servers[name]?.let { if (it.isRunning) return it }
        servers.values.removeAll { !it.isRunning }
        if (servers.size >= maxServers) throw IOException("$maxServers previews are already running. Stop one first.")
        return LocalServer(dir, 0, spa).also { it.start(); servers[name] = it }
    }

    fun stop(name: String): Boolean {
        val s = synchronized(this) { servers.remove(name) } ?: return false
        val port = s.boundPort
        s.stop()
        notifyStopped(name, port)
        return true
    }

    fun stopAll() {
        val all = synchronized(this) { servers.entries.map { it.key to it.value }.also { servers.clear() } }
        all.forEach { (name, s) -> val port = s.boundPort; s.stop(); notifyStopped(name, port) }
    }

    @Synchronized fun running(): Map<String, LocalServer> = servers.filterValues { it.isRunning }

    private fun notifyStopped(name: String, port: Int) {
        if (port <= 0) return
        stopListeners.forEach { l -> try { l(name, port) } catch (_: Exception) { } }
    }
}
