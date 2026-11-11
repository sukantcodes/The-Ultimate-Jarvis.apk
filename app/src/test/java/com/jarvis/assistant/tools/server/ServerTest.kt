package com.jarvis.assistant.tools.server

import com.jarvis.assistant.tools.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.nio.file.Files

class ServerTest {
    private val dir = Files.createTempDirectory("jarvis-srv").toFile()
    private val ws = Workspace(dir)
    private val ctx = object : ToolContext { override val workspace = ws }
    private val tools = ServerTools()
    private val servers = tools.servers
    @After fun tearDown() = servers.stopAll()

    private fun run(t: Tool, json: String) = runBlocking { t.run(args(json), ctx) }
    private fun tool(name: String) = tools.all().first { it.spec.name == name }
    private fun get(url: String): Pair<Int, String> { val c = URL(url).openConnection() as HttpURLConnection; c.connectTimeout = 3000; c.readTimeout = 3000
        return try { c.responseCode to (if (c.responseCode < 400) c.inputStream.readBytes().toString(Charsets.UTF_8) else c.errorStream?.readBytes()?.toString(Charsets.UTF_8).orEmpty()) } finally { c.disconnect() } }
    private fun raw(port: Int, request: String): String = Socket("127.0.0.1", port).use { s -> s.soTimeout = 3000; s.getOutputStream().write(request.toByteArray()); s.getOutputStream().flush(); s.getInputStream().readBytes().toString(Charsets.ISO_8859_1) }

    @Test fun createCheckPreviewAndServe() {
        assertTrue(run(tool("project_create"), """{"name":"My Site","template":"landing","title":"Acme"}""").isOk)
        val chk = run(tool("project_check"), """{"name":"my-site"}""").text; assertTrue(chk, chk.startsWith("Project check passed"))
        val r = run(tool("preview_start"), """{"name":"my-site"}"""); assertTrue(r.text, r.isOk)
        val s = servers.running().values.single()
        assertEquals("127.0.0.1", s.url.substringAfter("//").substringBefore(':'))
        val (code, body) = get(s.url); assertEquals(200, code); assertTrue(body.contains("Acme"))
        assertEquals(200, get(s.url + "style.css").first); assertEquals(404, get(s.url + "missing.html").first)
        assertTrue(run(tool("preview_status"), "{}").text.contains("my-site"))
        assertTrue(run(tool("preview_stop"), """{"name":"my-site"}""").text.contains("stopped"))
        assertTrue(servers.running().isEmpty())
    }

    @Test fun gameTemplateIsCompleteAndServes() {
        assertTrue(run(tool("project_create"), """{"name":"snake","template":"game","title":"Snake"}""").isOk)
        assertTrue(run(tool("project_check"), """{"name":"snake"}""").text.startsWith("Project check passed"))
        assertTrue(File(ws.project("snake"), "script.js").readText().contains("touchstart"))
        assertTrue(run(tool("preview_start"), """{"name":"snake"}""").isOk)
        assertTrue(get(servers.running().values.single().url + "script.js").second.contains("function step"))
    }

    @Test fun blocksTraversalDotfilesLinksAndOtherMethods() {
        run(tool("project_create"), """{"name":"s"}"""); val proj = ws.project("s")
        File(proj, ".env").writeText("SECRET=1"); File(dir, "outside.txt").writeText("outside")
        Files.createSymbolicLink(File(proj, "link.txt").toPath(), File(dir, "outside.txt").toPath())
        run(tool("preview_start"), """{"name":"s"}"""); val s = servers.running().values.single()
        for (p in listOf("/../outside.txt", "/%2e%2e/outside.txt", "/.env", "/link.txt", "/..%2foutside.txt")) assertNotEquals(p, 200, raw(s.boundPort, "GET $p HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n").substring(9, 12).toInt())
        assertTrue(raw(s.boundPort, "POST / HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n").startsWith("HTTP/1.1 405"))
        assertTrue(raw(s.boundPort, "GET / HTTP/1.1\r\nHost: evil.example\r\n\r\n").startsWith("HTTP/1.1 403"))
        assertTrue(raw(s.boundPort, "GET / HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n").startsWith("HTTP/1.1 200"))
    }

    @Test fun checkFindsBrokenLinksAndBadHtml() {
        run(tool("project_create"), """{"name":"b"}"""); val p = ws.project("b")
        File(p, "index.html").writeText("<!doctype html><html><body><img src=\"nope.png\"><script src=\"gone.js\"></script><div><p>x</div></body></html>")
        val t = run(tool("project_check"), """{"name":"b"}""").text
        assertTrue(t, t.contains("nope.png")); assertTrue(t.contains("gone.js")); assertTrue(t.contains("not well-formed"))
        File(p, "index.html").delete(); assertTrue(run(tool("project_check"), """{"name":"b"}""").text.contains("no index.html"))
    }

    @Test fun frameworkProjectIsHonestAboutBuild() {
        val p = ws.project("react"); File(p, "package.json").writeText("""{"scripts":{"build":"vite build"}}"""); File(p, "index.html").writeText("<!doctype html><html><body></body></html>")
        assertTrue(ProjectBuilder.check(p, nodeAvailable = false).notes.single().contains("Node.js is not available"))
        assertTrue(ProjectBuilder.check(p, nodeAvailable = true).notes.single().contains("npm run build"))
    }

    @Test fun snapshotRestoreAndExport() {
        run(tool("project_create"), """{"name":"r","template":"app"}"""); val p = ws.project("r")
        assertTrue(run(tool("project_snapshot"), """{"name":"r","label":"v1"}""").isOk)
        File(p, "index.html").writeText("changed"); File(p, "extra.txt").writeText("x")
        assertTrue(run(tool("project_restore"), """{"name":"r","label":"v1"}""").text, run(tool("project_restore"), """{"name":"r","label":"v1"}""").isOk)
        assertTrue(File(p, "index.html").readText().contains("<!doctype html>")); assertFalse(File(p, "extra.txt").exists())
        assertFalse(run(tool("project_restore"), """{"name":"r","label":"nope"}""").isOk)
        val e = run(tool("project_export"), """{"name":"r"}"""); assertTrue(e.isOk); assertTrue(File(dir, "exports/r.zip").length() > 0)
        assertEquals(Risk.DESTRUCTIVE, tool("project_restore").spec.risk)
    }

    @Test fun limitsAndErrors() {
        for (n in 1..3) { run(tool("project_create"), """{"name":"p$n"}"""); assertTrue(run(tool("preview_start"), """{"name":"p$n"}""").isOk) }
        run(tool("project_create"), """{"name":"p4"}"""); assertFalse(run(tool("preview_start"), """{"name":"p4"}""").isOk)
        assertFalse(run(tool("project_create"), """{"name":"p1"}""").isOk)
        assertFalse(run(tool("project_create"), """{"name":"x","template":"weird"}""").isOk)
        assertFalse(run(tool("preview_start"), """{"name":"empty-one"}""").isOk)
    }
}
