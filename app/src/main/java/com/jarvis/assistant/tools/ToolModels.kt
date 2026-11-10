package com.jarvis.assistant.tools

import com.google.gson.JsonObject

/**
 * How much damage a tool call could do. The permission manager decides from this, never from which AI provider
 * asked: switching provider can not loosen a rule.
 */
enum class Risk(val label: String) {
    READ("Reads data"),
    NETWORK("Uses the internet"),
    WRITE("Creates or changes files"),
    EXECUTE("Runs programs"),
    /** Deleting, overwriting important data, pushing to a remote, installing untrusted software. Always asks. */
    DESTRUCTIVE("Irreversible or destructive")
}

data class ToolParam(
    val name: String,
    val type: String = "string",          // string | integer | boolean | array | object
    val description: String = "",
    val required: Boolean = false,
    val enumValues: List<String> = emptyList()
)

data class ToolSpec(
    val name: String,
    val description: String,
    val params: List<ToolParam> = emptyList(),
    val risk: Risk,
    /** Groups tools in the UI and in permission rules (files, github, terminal, web, server, documents, task). */
    val group: String
)

/** Who asked. Recorded for audit only; every source follows the same rules. */
data class ToolCall(val id: String, val name: String, val args: JsonObject, val source: String)

data class ToolArtifact(val path: String, val name: String, val mime: String)

sealed class ToolResult {
    data class Ok(val output: String, val artifacts: List<ToolArtifact> = emptyList()) : ToolResult()
    data class Error(val message: String) : ToolResult()
    data class Denied(val reason: String) : ToolResult()

    val isOk: Boolean get() = this is Ok
    val text: String get() = when (this) {
        is Ok -> output
        is Error -> "Error: $message"
        is Denied -> "Not allowed: $reason"
    }
}

/** What the tool needs while it runs. Kept small so tools are easy to test. */
interface ToolContext {
    val workspace: Workspace
    fun progress(message: String) {}
    /** True once the user pressed Stop. Long tools check it between steps. */
    val cancelled: Boolean get() = false
}

interface Tool {
    val spec: ToolSpec

    /** A short, plain description of exactly what this call would do (shown in the confirmation prompt). */
    fun describe(args: JsonObject): String = spec.description

    /** True when this particular call is irreversible even though the tool is usually safe (for example overwriting a file). */
    fun isDestructive(args: JsonObject, ctx: ToolContext): Boolean = spec.risk == Risk.DESTRUCTIVE

    suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult
}

class ToolRegistry {
    private val tools = LinkedHashMap<String, Tool>()

    @Synchronized fun register(tool: Tool) {
        require(tool.spec.name.matches(Regex("[a-z][a-z0-9_]{1,63}"))) { "Bad tool name: ${tool.spec.name}" }
        tools[tool.spec.name] = tool
    }

    @Synchronized fun get(name: String): Tool? = tools[name]
    @Synchronized fun all(): List<Tool> = tools.values.toList()
    @Synchronized fun specs(): List<ToolSpec> = tools.values.map { it.spec }
}
