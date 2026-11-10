package com.jarvis.assistant.tools

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WorkspaceWebToolsTest {
    private val root = Files.createTempDirectory("jarvis-agent-workspace").toFile()
    private val workspace = Workspace(root)
    private val settings = object : PermissionPolicy.SettingsStore {
        private val values = HashMap<String, String>()
        override fun get(key: String): String? = values[key]
        override fun put(key: String, value: String) { values[key] = value }
    }
    private val confirmer = object : Confirmer {
        override suspend fun confirm(request: ConfirmRequest) = Decision.ALLOW_ONCE
    }
    private val registry = ToolRegistry().also { r ->
        WorkspaceTools.all().forEach(r::register)
        WebTools.all().forEach(r::register)
    }
    private val executor = ToolExecutor(
        registry, PermissionPolicy(settings), AuditLog(null, SecretRedactor()),
        { confirmer }, SecretRedactor(), workspace
    )

    @After fun cleanup() { root.deleteRecursively() }

    private fun call(name: String, json: String) = runBlocking {
        executor.execute(ToolCall("test_${System.nanoTime()}", name, JsonParser.parseString(json).asJsonObject, "chatbox:test"))
    }

    @Test fun project_templates_create_expected_files_inside_workspace() {
        val result = call("create_project", """{"name":"Demo Site","template":"web"}""")
        assertTrue(result.text, result.isOk)
        val project = File(root, "projects/demo-site")
        assertTrue(File(project, "index.html").isFile)
        assertTrue(File(project, "styles.css").isFile)
        assertTrue(File(project, "script.js").isFile)
        assertTrue(result.text.contains("projects/demo-site"))
    }

    @Test fun exact_patch_requires_a_unique_match_and_keeps_a_backup() {
        val source = File(root, "src/Main.kt")
        source.parentFile?.mkdirs()
        source.writeText("fun main() { println(\"old\") }\n")
        val patched = call("apply_patch", """{"path":"src/Main.kt","find":"println(\"old\")","replace":"println(\"new\")"}""")
        assertTrue(patched.text, patched.isOk)
        assertTrue(source.readText().contains("println(\"new\")"))
        val backups = File(root, ".jarvis/backups")
        assertEquals(1, backups.listFiles()?.size)
        assertTrue(backups.listFiles()!!.single().readText().contains("println(\"old\")"))
    }

    @Test fun ambiguous_patch_is_rejected_without_modifying_the_file() {
        val source = File(root, "repeat.txt")
        source.writeText("same same")
        val result = call("apply_patch", """{"path":"repeat.txt","find":"same","replace":"changed"}""")
        assertTrue(result.text, result is ToolResult.Error)
        assertEquals("same same", source.readText())
    }

    @Test fun web_fetch_rejects_non_https_and_local_destinations() {
        val http = call("fetch_url", """{"url":"http://example.com"}""")
        assertTrue(http is ToolResult.Error)
        val local = call("fetch_url", """{"url":"https://localhost/"}""")
        assertTrue(local is ToolResult.Error)
    }
}
