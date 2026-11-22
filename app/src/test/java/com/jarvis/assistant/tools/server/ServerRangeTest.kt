package com.jarvis.assistant.tools.server

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.Socket
import java.nio.file.Files

class ServerRangeTest {
    private val dir = Files.createTempDirectory("jv-range").toFile()
    private val servers = ServerManager()
    @After fun tearDown() = servers.stopAll()

    @Test fun range_headers_are_understood() {
        assertEquals(0L..99L, LocalServer.parseRange("bytes=0-99", 1000))
        assertEquals(900L..999L, LocalServer.parseRange("bytes=900-", 1000))
        assertEquals(900L..999L, LocalServer.parseRange("bytes=-100", 1000))
        assertEquals(0L..999L, LocalServer.parseRange("bytes=0-5000", 1000))
        assertTrue(LocalServer.parseRange("bytes=2000-", 1000)!!.isEmpty())
        assertTrue(LocalServer.parseRange("bytes=-0", 1000)!!.isEmpty())
        assertNull(LocalServer.parseRange(null, 1000))
        assertNull(LocalServer.parseRange("items=0-5", 1000))
        assertNull(LocalServer.parseRange("bytes=0-1,5-6", 1000))
        assertNull(LocalServer.parseRange("bytes=5-2", 1000))
        assertNull(LocalServer.parseRange("bytes=abc-", 1000))
    }

    private fun raw(port: Int, request: String): ByteArray =
        Socket("127.0.0.1", port).use { s -> s.soTimeout = 3000; s.getOutputStream().write(request.toByteArray()); s.getOutputStream().flush(); s.getInputStream().readBytes() }

    @Test fun a_preview_serves_partial_content_for_video_style_requests() {
        val data = ByteArray(1000) { (it % 251).toByte() }
        File(dir, "index.html").writeText("<h1>x</h1>"); File(dir, "clip.mp4").writeBytes(data)
        val port = servers.start("p", dir).boundPort
        fun get(range: String?): Pair<String, ByteArray> {
            val out = raw(port, "GET /clip.mp4 HTTP/1.1\r\nHost: 127.0.0.1\r\n" + (range?.let { "Range: $it\r\n" } ?: "") + "\r\n")
            val i = String(out, Charsets.ISO_8859_1).indexOf("\r\n\r\n")
            return String(out, 0, i, Charsets.ISO_8859_1) to out.copyOfRange(i + 4, out.size)
        }
        val (h1, b1) = get("bytes=10-19")
        assertTrue(h1, h1.startsWith("HTTP/1.1 206") && h1.contains("Content-Range: bytes 10-19/1000") && h1.contains("Content-Length: 10"))
        assertArrayEquals(data.copyOfRange(10, 20), b1)
        val (h2, b2) = get("bytes=-5")
        assertTrue(h2.contains("Content-Range: bytes 995-999/1000")); assertArrayEquals(data.copyOfRange(995, 1000), b2)
        val (h3, _) = get("bytes=5000-")
        assertTrue(h3, h3.startsWith("HTTP/1.1 416") && h3.contains("Content-Range: bytes */1000"))
        val (h4, b4) = get(null)
        assertTrue(h4.startsWith("HTTP/1.1 200") && h4.contains("Accept-Ranges: bytes")); assertArrayEquals(data, b4)
    }
}
