package com.jarvis.assistant.provider

import com.google.gson.JsonParser
import com.jarvis.assistant.tools.*
import com.jarvis.assistant.tools.builtin.FileTools
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ToolChatLoopTest {
    private val dir: File = Files.createTempDirectory("jarvis-loop").toFile()
    private val ws = Workspace(dir)
    private val redactor = SecretRedactor()
    private val confirmer = ScriptedConfirmer(Decision.ALLOW_ONCE)
    private val registry = ToolRegistry().also { r -> FileTools.all().forEach(r::register) }
    private val exec = ToolExecutor(registry, PermissionPolicy(MapSettings()), AuditLog(File(dir, ".a/audit.jsonl"), redactor), { confirmer }, redactor, ws)
    private val backend = ExecutorBackend(registry, exec)
    private val tr = FakeTransport()
    private val req get() = ChatRequest(Fixtures.resolved(), "sys", listOf(ChatMessage(ChatRole.USER, "make a file")))
    @After fun cleanup() { dir.deleteRecursively() }

    private fun toolCallBody(id: String, name: String, argsJson: String) =
        """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"$id","type":"function","function":{"name":"$name","arguments":${com.google.gson.JsonPrimitive(argsJson)}}}]}}]}"""

    /** Plays the given response bodies in order, one per request. */
    private fun script(vararg bodies: String) { var i = 0; tr.addRule({ it.url.endsWith("chat/completions") }) { HttpResponse(200, bodies[minOf(i++, bodies.size - 1)]) } }

    private fun sentBodies() = tr.requests.map { JsonParser.parseString((it.body as HttpBody.Text).content).asJsonObject }

    @Test fun modelAsksForToolResultComesBackAndFinalAnswerIsReturned() {
        script(toolCallBody("c1", "write_file", """{"path":"notes.txt","content":"hello"}"""), Fixtures.completion("Done, I wrote notes.txt."))
        val seen = ArrayList<String>()
        val r = runBlocking { OpenAiToolLoop(tr, backend).run(req, "t") { seen += it } }
        assertEquals("Done, I wrote notes.txt.", r.text); assertEquals(1, r.toolCalls); assertNull(r.stoppedEarly)
        assertEquals("hello", File(dir, "notes.txt").readText())
        val second = sentBodies()[1].getAsJsonArray("messages")
        assertEquals("tool", second.last().asJsonObject.get("role").asString)
        assertEquals("c1", second.last().asJsonObject.get("tool_call_id").asString)
        assertTrue(second[second.size() - 2].asJsonObject.has("tool_calls"))
        assertTrue(sentBodies()[0].has("tools")); assertTrue(seen.any { it.contains("write_file") })
    }

    @Test fun everyRoundReportsTheProvidersTokenCount() {
        val first = toolCallBody("c1", "list_files", """{"path":"."}""").removeSuffix("}") + ""","usage":{"prompt_tokens":100,"completion_tokens":10,"total_tokens":110}}"""
        val second = Fixtures.completion("done").removeSuffix("}") + ""","usage":{"prompt_tokens":150,"completion_tokens":5,"total_tokens":155}}"""
        script(first, second)
        val used = ArrayList<TokenUsage>()
        val loop = OpenAiToolLoop(tr, backend)
        loop.onUsage = { used += it }
        val r = runBlocking { loop.run(req, "t") }
        assertEquals("done", r.text)
        assertEquals(listOf(110L, 155L), used.map { it.total })
    }

    @Test fun deniedToolIsReportedToTheModelAndNothingHappens() {
        confirmer.answer = Decision.DENY
        script(toolCallBody("c1", "delete_path", """{"path":"x.txt"}"""), Fixtures.completion("I could not delete it."))
        File(dir, "x.txt").writeText("keep")
        val r = runBlocking { OpenAiToolLoop(tr, backend).run(req, "t") }
        assertTrue(File(dir, "x.txt").exists()); assertEquals("I could not delete it.", r.text)
        assertTrue(sentBodies()[1].getAsJsonArray("messages").last().asJsonObject.get("content").asString.startsWith("Not allowed"))
    }

    @Test fun unknownToolAndBadArgsGoBackAsErrorsNotCrashes() {
        script(toolCallBody("c1", "no_such_tool", "{}"), Fixtures.completion("ok"))
        val r = runBlocking { OpenAiToolLoop(tr, backend).run(req, "t") }
        assertEquals("ok", r.text)
        assertTrue(sentBodies()[1].getAsJsonArray("messages").last().asJsonObject.get("content").asString.startsWith("Error"))
    }

    @Test fun identicalRepeatedCallsAreStoppedAndRoundLimitIsHonest() {
        script(toolCallBody("a", "list_files", """{"path":"."}"""))   // the model never stops asking
        val r = runBlocking { OpenAiToolLoop(tr, backend, maxRounds = 6).run(req, "t") }
        assertNotNull(r.stoppedEarly); assertEquals(6, r.rounds)
        val third = sentBodies()[3].getAsJsonArray("messages").last().asJsonObject.get("content").asString
        assertTrue(third, third.contains("already made"))
    }

    @Test fun huge_tool_output_is_clipped_with_a_note() {
        File(dir, "big.txt").writeText("x".repeat(50_000))
        script(toolCallBody("c1", "read_file", """{"path":"big.txt","max_chars":50000}"""), Fixtures.completion("read"))
        runBlocking { OpenAiToolLoop(tr, backend, maxResultChars = 1000).run(req, "t") }
        val content = sentBodies()[1].getAsJsonArray("messages").last().asJsonObject.get("content").asString
        assertTrue(content.length < 1200); assertTrue(content.contains("not sent to the model"))
    }

    @Test fun providerErrorsSurfaceAsProviderException() {
        tr.addRule({ true }) { HttpResponse(429, """{"error":{"message":"rate"}}""") }
        try { runBlocking { OpenAiToolLoop(tr, backend).run(req, "t") }; fail() } catch (e: ProviderException) { assertEquals(ProviderErrorKind.RATE_LIMITED, e.error.kind) }
    }

    @Test fun geminiFunctionCallingUsesTheSameBackend() {
        val call = """{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"write_file","args":{"path":"g.txt","content":"from gemini"}}}]}}]}"""
        val done = """{"candidates":[{"content":{"role":"model","parts":[{"text":"Saved g.txt"}]}}]}"""
        var i = 0; tr.addRule({ it.url.contains(":generateContent") }) { HttpResponse(200, if (i++ == 0) call else done) }
        val r = runBlocking { GeminiToolLoop(tr, backend).run(ChatRequest(Fixtures.resolved(ProviderProtocol.GEMINI), "sys", listOf(ChatMessage(ChatRole.USER, "go"))), "g") }
        assertEquals("Saved g.txt", r.text); assertEquals("from gemini", File(dir, "g.txt").readText())
        val second = sentBodies()[1]
        assertTrue(second.getAsJsonArray("contents").last().asJsonObject.getAsJsonArray("parts")[0].asJsonObject.has("functionResponse"))
        assertTrue(second.getAsJsonArray("tools")[0].asJsonObject.has("functionDeclarations"))
    }

    @Test fun workspaceEscapeAttemptsFailThroughTheLoop() {
        script(toolCallBody("c1", "read_file", """{"path":"../../etc/passwd"}"""), Fixtures.completion("no"))
        runBlocking { OpenAiToolLoop(tr, backend).run(req, "t") }
        assertTrue(sentBodies()[1].getAsJsonArray("messages").last().asJsonObject.get("content").asString.startsWith("Error"))
    }
}
