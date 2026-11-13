package com.jarvis.assistant.tools

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.jarvis.assistant.tools.builtin.FileTools
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MapSettings : PermissionPolicy.SettingsStore {
    val m = HashMap<String, String>()
    override fun get(key: String) = m[key]
    override fun put(key: String, value: String) { m[key] = value }
}

class ScriptedConfirmer(var answer: Decision) : Confirmer {
    val asked = ArrayList<ConfirmRequest>()
    override suspend fun confirm(request: ConfirmRequest): Decision { asked += request; return answer }
}

fun args(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

class ToolLayerTest {
    private val dir: File = Files.createTempDirectory("jarvis-ws").toFile()
    private val ws = Workspace(dir)
    private val redactor = SecretRedactor()
    private val audit = AuditLog(File(dir, ".jarvis/audit.jsonl"), redactor)
    private val settings = MapSettings()
    private val policy = PermissionPolicy(settings)
    private val confirmer = ScriptedConfirmer(Decision.ALLOW_ONCE)
    private val registry = ToolRegistry().also { r -> FileTools.all().forEach(r::register) }
    private val exec = ToolExecutor(registry, policy, audit, { confirmer }, redactor, ws)

    @After fun cleanup() { dir.deleteRecursively() }

    private fun call(name: String, json: String, id: String = "c${System.nanoTime()}", source: String = "custom:test") =
        runBlocking { exec.execute(ToolCall(id, name, args(json), source)) }

    // ---- sandbox -------------------------------------------------------------------------------------

    @Test fun paths_that_escape_the_workspace_are_rejected() {
        for (bad in listOf("../x", "a/../../x", "/etc/passwd", "C:/x", "a\\..\\..\\x", "a/\u0000b")) {
            try { ws.resolve(bad); fail("accepted $bad") } catch (e: Workspace.PathRejected) { }
        }
        assertEquals(ws.root, ws.resolve("."))
        assertTrue(ws.resolve("a/b/c.txt").path.startsWith(ws.root.path))
    }

    @Test fun internal_storage_and_device_paths_are_resolved() {
        val sdcardFile = ws.resolve("/sdcard/documents/notes.txt")
        assertEquals(File("/sdcard/documents/notes.txt").canonicalPath, sdcardFile.path)

        val sdcardAlias = ws.resolve("sdcard/download/sample.txt")
        assertEquals(File("/sdcard/download/sample.txt").canonicalPath, sdcardAlias.path)

        val storageFile = ws.resolve("/storage/emulated/0/Music/song.mp3")
        assertEquals(File("/storage/emulated/0/Music/song.mp3").canonicalPath, storageFile.path)

        // Relative path computation handles files outside workspace root gracefully
        val rel = ws.relative(sdcardFile)
        assertTrue(rel.contains("notes.txt"))
    }

    @Test fun a_symbolic_link_cannot_be_used_to_leave_the_workspace() {
        val outside = Files.createTempDirectory("outside").toFile()
        try {
            File(outside, "secret.txt").writeText("TOP SECRET")
            Files.createSymbolicLink(File(dir, "link").toPath(), outside.toPath())
            val r = call("read_file", """{"path":"link/secret.txt"}""")
            assertTrue(r.text, r is ToolResult.Error)
            assertFalse(r.text.contains("TOP SECRET"))
        } finally { outside.deleteRecursively() }
    }

    @Test fun project_names_are_sanitised() {
        val p = ws.project("My Cool Site!!")
        assertTrue(p.path.endsWith("projects/my-cool-site"))
        try { ws.project("///"); fail() } catch (e: Workspace.PathRejected) { }
    }

    // ---- permissions ---------------------------------------------------------------------------------

    @Test fun reading_is_allowed_without_asking_and_writing_asks_first() {
        File(dir, "a.txt").writeText("hello")
        assertEquals("hello", (call("read_file", """{"path":"a.txt"}""") as ToolResult.Ok).output)
        assertTrue(confirmer.asked.isEmpty())
        assertTrue(call("write_file", """{"path":"b.txt","content":"x"}""").isOk)
        assertEquals(1, confirmer.asked.size)
        assertEquals("x", File(dir, "b.txt").readText())
    }

    @Test fun declining_blocks_the_action_and_nothing_is_written() {
        confirmer.answer = Decision.DENY
        val r = call("write_file", """{"path":"b.txt","content":"x"}""")
        assertTrue(r is ToolResult.Denied)
        assertFalse(File(dir, "b.txt").exists())
    }

    @Test fun overwriting_and_deleting_are_destructive_and_always_ask_even_after_a_session_grant() {
        confirmer.answer = Decision.ALLOW_SESSION
        assertTrue(call("write_file", """{"path":"a.txt","content":"1"}""").isOk)      // new file: session grant given
        val before = confirmer.asked.size
        assertTrue(call("write_file", """{"path":"c.txt","content":"2"}""").isOk)      // covered by the grant
        assertEquals(before, confirmer.asked.size)
        assertTrue(call("write_file", """{"path":"a.txt","content":"3","overwrite":true}""").isOk)
        assertTrue(confirmer.asked.last().destructive)                                 // overwrite asked again
        assertTrue(call("delete_path", """{"path":"c.txt"}""").isOk)
        assertTrue(confirmer.asked.last().destructive && confirmer.asked.last().risk == Risk.DESTRUCTIVE)
        assertFalse(policy.hasSessionGrant("delete_path"))
    }

    @Test fun destructive_actions_can_never_be_set_to_always_allow() {
        policy.setMode(Risk.DESTRUCTIVE, Mode.ALLOW)
        assertEquals(Mode.ASK, policy.modeFor(Risk.DESTRUCTIVE, "files"))
        File(dir, "z.txt").writeText("z")
        call("delete_path", """{"path":"z.txt"}""")
        assertEquals(1, confirmer.asked.size)
    }

    @Test fun auto_approve_all_bypasses_all_prompts_including_destructive_actions() {
        policy.setAutoApproveAll(true)
        assertTrue(policy.isAutoApproveAll())
        File(dir, "del.txt").writeText("delete me")
        val r = call("delete_path", """{"path":"del.txt"}""")
        assertTrue(r.isOk)
        assertFalse(File(dir, "del.txt").exists())
        assertEquals(0, confirmer.asked.size)

        val r2 = call("write_file", """{"path":"auto.txt","content":"approved"}""")
        assertTrue(r2.isOk)
        assertEquals("approved", File(dir, "auto.txt").readText())
        assertEquals(0, confirmer.asked.size)

        // Turn it off and verify confirmation is asked again
        policy.setAutoApproveAll(false)
        assertFalse(policy.isAutoApproveAll())
        val r3 = call("write_file", """{"path":"manual.txt","content":"prompt"}""")
        assertTrue(r3.isOk)
        assertEquals(1, confirmer.asked.size)
    }

    @Test fun allowing_a_task_covers_subsequent_non_destructive_actions_for_the_same_task() {
        confirmer.answer = Decision.ALLOW_TASK
        val taskId = "task-git-clone-42"
        assertTrue(call("write_file", """{"path":"f1.txt","content":"1"}""", source = taskId).isOk)
        assertEquals(1, confirmer.asked.size)
        assertTrue(call("write_file", """{"path":"f2.txt","content":"2"}""", source = taskId).isOk)
        assertEquals(1, confirmer.asked.size)
        assertTrue(call("write_file", """{"path":"f3.txt","content":"3"}""", source = "other-task").isOk)
        assertEquals(2, confirmer.asked.size)
    }

    @Test fun a_switched_off_category_is_denied_without_asking() {
        policy.setMode(Risk.WRITE, Mode.DENY, group = "files")
        val r = call("write_file", """{"path":"q.txt","content":"x"}""")
        assertTrue(r is ToolResult.Denied)
        assertTrue(confirmer.asked.isEmpty())
    }

    @Test fun the_same_rules_apply_whichever_provider_asks() {
        confirmer.answer = Decision.DENY
        assertTrue(call("write_file", """{"path":"1.txt","content":"x"}""", source = "custom:deepseek") is ToolResult.Denied)
        assertTrue(call("write_file", """{"path":"2.txt","content":"x"}""", source = "gemini-voice") is ToolResult.Denied)
        assertEquals(listOf("custom:deepseek", "gemini-voice"), confirmer.asked.map { it.source })
    }

    @Test fun with_no_screen_to_ask_on_consequential_actions_are_denied() {
        val headless = ToolExecutor(registry, policy, audit, { DenyingConfirmer }, redactor, ws)
        val r = runBlocking { headless.execute(ToolCall("h1", "delete_path", args("""{"path":"x"}"""), "bg")) }
        assertTrue(r is ToolResult.Denied)
    }

    // ---- executor behaviour --------------------------------------------------------------------------

    @Test fun unknown_tools_and_missing_arguments_are_reported() {
        assertTrue(call("rm_rf", "{}").text.contains("no tool named"))
        assertTrue(call("read_file", "{}").text.contains("Missing required"))
    }

    @Test fun a_repeated_call_id_does_not_run_twice() {
        assertTrue(call("append_file", """{"path":"log.txt","content":"x"}""", id = "dup").isOk)
        assertTrue(call("append_file", """{"path":"log.txt","content":"x"}""", id = "dup").isOk)
        assertEquals("x", File(dir, "log.txt").readText())
    }

    @Test fun a_denied_call_can_be_asked_again_and_approved() {
        confirmer.answer = Decision.DENY
        assertTrue(call("write_file", """{"path":"r.txt","content":"x"}""", id = "again") is ToolResult.Denied)
        confirmer.answer = Decision.ALLOW_ONCE
        assertTrue(call("write_file", """{"path":"r.txt","content":"x"}""", id = "again").isOk)
    }

    @Test fun existing_files_are_not_replaced_unless_overwrite_is_set() {
        File(dir, "keep.txt").writeText("original")
        assertTrue(call("write_file", """{"path":"keep.txt","content":"new"}""") is ToolResult.Error)
        assertEquals("original", File(dir, "keep.txt").readText())
    }

    @Test fun listing_searching_moving_and_reading_work_together() {
        call("write_file", """{"path":"src/Main.kt","content":"fun main() {\n  println(\"Hello\")\n}"}""")
        assertTrue(call("list_files", """{"recursive":true}""").text.contains("src/Main.kt"))
        assertTrue(call("search_files", """{"query":"hello"}""").text.contains("src/Main.kt:2"))
        assertTrue(call("move_path", """{"from":"src/Main.kt","to":"app/Main.kt"}""").isOk)
        assertTrue(call("read_file", """{"path":"app/Main.kt","max_chars":10}""").text.contains("more characters"))
        assertTrue(call("move_path", """{"from":"nothing","to":"x"}""") is ToolResult.Error)
    }

    @Test fun binary_files_are_not_dumped_as_text() {
        File(dir, "img.bin").writeBytes(byteArrayOf(1, 2, 0, 3, 4))
        assertTrue(call("read_file", """{"path":"img.bin"}""").text.contains("binary"))
    }

    @Test fun the_workspace_root_cannot_be_deleted() {
        assertTrue(call("delete_path", """{"path":"."}""").text.contains("root"))
        assertTrue(dir.exists())
    }

    @Test fun tools_that_run_too_long_are_stopped() {
        val slow = object : Tool {
            override val spec = ToolSpec("slow_tool", "x", emptyList(), Risk.READ, "files")
            override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult { kotlinx.coroutines.delay(5_000); return ToolResult.Ok("late") }
        }
        registry.register(slow)
        val r = runBlocking { exec.execute(ToolCall("s", "slow_tool", JsonObject(), "t"), timeoutMs = 100) }
        assertTrue(r.text, r is ToolResult.Error && r.message.contains("stopped"))
    }

    // ---- secrets -------------------------------------------------------------------------------------

    @Test fun secrets_are_removed_from_tool_output_and_the_audit_log() {
        redactor.register("my-private-token-9999")
        File(dir, "env.txt").writeText("TOKEN=my-private-token-9999\nkey=sk-abcdefghijklmnop1234\nghp_abcdefghijklmnopqrstuv")
        val out = call("read_file", """{"path":"env.txt"}""").text
        assertFalse(out, out.contains("my-private-token-9999") || out.contains("sk-abcdefghijklmnop1234") || out.contains("ghp_abcdefghijklmnopqrstuv"))
        call("write_file", """{"path":"leak.txt","content":"my-private-token-9999"}""")
        val log = File(dir, ".jarvis/audit.jsonl").readText()
        assertFalse(log.contains("my-private-token-9999"))
        assertTrue(audit.recent().isNotEmpty())
    }

    @Test fun the_redactor_catches_common_key_formats_without_registration() {
        val r = SecretRedactor()
        val s = r.redact("Authorization: Bearer abcdefghijklmnop and AIzaSyA1234567890123456789012345678901 and password=hunter2hunter2 and AKIAABCDEFGHIJKLMNOP")
        assertFalse(s.contains("abcdefghijklmnop") || s.contains("AIzaSy") || s.contains("hunter2hunter2") || s.contains("AKIAABCDEF"))
        assertEquals("plain text stays", r.redact("plain text stays"))
    }

    // ---- provider schemas ----------------------------------------------------------------------------

    @Test fun the_same_tools_are_described_to_openai_compatible_and_gemini_providers() {
        val specs = registry.specs()
        val openAi = ToolSchemas.toOpenAi(specs)
        val gem = ToolSchemas.toGemini(specs)
        assertEquals(specs.size, openAi.size()); assertEquals(specs.size, gem.size())
        val write = openAi.map { it.asJsonObject.getAsJsonObject("function") }.first { it.get("name").asString == "write_file" }
        assertEquals("object", write.getAsJsonObject("parameters").get("type").asString)
        assertTrue(write.getAsJsonObject("parameters").getAsJsonArray("required").map { it.asString }.containsAll(listOf("path", "content")))
        val gWrite = gem.map { it.asJsonObject }.first { it.get("name").asString == "write_file" }
        assertEquals("OBJECT", gWrite.getAsJsonObject("parameters").get("type").asString)
    }

    @Test fun tool_requests_from_both_providers_parse_into_the_same_call_type() {
        val msg = JsonParser.parseString("""{"tool_calls":[{"id":"c1","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"a.txt\"}"}},{"function":{"name":"x","arguments":"not json"}}]}""").asJsonObject
        val calls = ToolSchemas.parseOpenAiCalls(msg, "custom:x")
        assertEquals("read_file", calls[0].name); assertEquals("a.txt", calls[0].args.get("path").asString)
        assertEquals(0, calls[1].args.size())
        val g = ToolSchemas.parseGeminiCalls(JsonParser.parseString("""[{"id":"g1","name":"read_file","args":{"path":"b.txt"}}]""").asJsonArray, "gemini-voice")
        assertEquals("b.txt", g[0].args.get("path").asString); assertEquals("gemini-voice", g[0].source)
    }
}
