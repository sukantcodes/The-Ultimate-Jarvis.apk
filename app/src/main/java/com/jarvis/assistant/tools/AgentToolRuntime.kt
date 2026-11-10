package com.jarvis.assistant.tools

import android.content.Context
import com.jarvis.assistant.tools.builtin.FileTools
import java.io.File

/**
 * Process-wide ChatBox tool environment. The workspace is private to the app; tools never receive arbitrary
 * device paths. Consequential operations are routed through the active ChatBox approval host, or denied headlessly.
 */
object AgentToolApproval {
    @Volatile var confirmer: Confirmer? = null
}

object AgentToolRuntime {
    @Volatile private var executorInstance: ToolExecutor? = null
    @Volatile private var registryInstance: ToolRegistry? = null

    private fun registry(): ToolRegistry = registryInstance ?: synchronized(this) {
        registryInstance ?: ToolRegistry().also { registry ->
            FileTools.all().forEach(registry::register)
            registryInstance = registry
        }
    }

    fun specs(context: Context): List<ToolSpec> {
        context.applicationContext // ensure callers pass a valid Android context
        return registry().specs()
    }

    fun executor(context: Context): ToolExecutor = executorInstance ?: synchronized(this) {
        executorInstance ?: run {
            val app = context.applicationContext
            val prefs = app.getSharedPreferences("jarvis_agent_permissions", Context.MODE_PRIVATE)
            val settings = object : PermissionPolicy.SettingsStore {
                override fun get(key: String): String? = prefs.getString(key, null)
                override fun put(key: String, value: String) { prefs.edit().putString(key, value).apply() }
            }
            val redactor = SecretRedactor()
            val audit = AuditLog(File(app.filesDir, "agent-tool-audit.jsonl"), redactor)
            val workspace = Workspace(File(app.filesDir, "chatbox-workspace"))
            ToolExecutor(
                registry = registry(),
                policy = PermissionPolicy(settings),
                audit = audit,
                confirmer = { AgentToolApproval.confirmer ?: DenyingConfirmer },
                redactor = redactor,
                workspace = workspace
            ).also { executorInstance = it }
        }
    }
}
