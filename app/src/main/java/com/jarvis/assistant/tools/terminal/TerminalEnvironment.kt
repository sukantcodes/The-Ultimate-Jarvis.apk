package com.jarvis.assistant.tools.terminal

data class ToolFound(val name: String, val path: String, val version: String)

data class EnvReport(
    val backend: String,
    val shell: String,
    val cwd: String,
    val system: String,
    val tools: Map<String, ToolFound>,
    val missing: List<String>
) {
    fun has(tool: String) = tools.containsKey(tool)

    /** Honest summary a model or user can act on. */
    fun format(): String = buildString {
        appendLine("Backend: $backend (shell: $shell)")
        appendLine("Working folder: $cwd")
        if (system.isNotBlank()) appendLine("System: $system")
        if (tools.isNotEmpty()) {
            appendLine("Available programs:")
            tools.values.forEach { appendLine("  ${it.name}${if (it.version.isNotBlank()) " - ${it.version}" else ""}") }
        }
        if (missing.isNotEmpty()) appendLine("Not installed here: ${missing.joinToString()}")
        if (!has("node") || !has("npm")) appendLine("Note: Node.js/npm are not available, so React/Vite projects can not be built on this device. Plain HTML/CSS/JS sites work without them.")
        if (!has("git")) appendLine("Note: git is not available here; repository work goes through the GitHub tools instead.")
        if (!has("python3") && !has("python")) appendLine("Note: Python is not available here.")
    }.trimEnd()
}

/** Finds out, by asking the shell, which programs a terminal command can really use. Nothing is assumed. */
object TerminalEnvironment {
    private val VERSIONED = listOf("git", "node", "npm", "python3", "python", "pip", "pip3")
    private val PRESENCE_ONLY = listOf("npx", "java", "gradle", "curl", "wget", "unzip", "zip", "tar", "make", "gcc", "sh")

    suspend fun detect(manager: TerminalManager): EnvReport {
        val session = manager.getOrCreate("env-probe")
        val script = buildString {
            append("uname -srm 2>/dev/null | sed 's/^/SYS|/'\n")
            for (t in VERSIONED) append("p=\$(command -v $t 2>/dev/null) && echo \"TOOL|$t|\$p|\$($t --version 2>&1 | head -n 1)\"\n")
            for (t in PRESENCE_ONLY) append("p=\$(command -v $t 2>/dev/null) && echo \"TOOL|$t|\$p|\"\n")
            append("true\n")
        }
        val r = session.run(script, 20_000)
        val tools = LinkedHashMap<String, ToolFound>()
        var system = ""
        for (line in r.output.lines()) {
            val parts = line.split('|')
            when {
                parts.size >= 2 && parts[0] == "SYS" -> system = parts.drop(1).joinToString("|").trim()
                parts.size >= 3 && parts[0] == "TOOL" -> tools[parts[1]] = ToolFound(parts[1], parts[2], parts.drop(3).joinToString("|").trim().take(80))
            }
        }
        manager.close("env-probe")
        val all = VERSIONED + PRESENCE_ONLY
        return EnvReport(manager.backend.name, manager.backend.shell, "workspace", system, tools, all.filter { it !in tools })
    }
}
