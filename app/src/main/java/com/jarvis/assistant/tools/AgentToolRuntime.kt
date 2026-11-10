package com.jarvis.assistant.tools

import android.content.Context
import com.jarvis.assistant.tools.builtin.FileTools
import com.jarvis.assistant.skills.SkillStore
import java.io.File

/** Process-wide ChatBox tool environment; consequential actions require the active approval host. */
object AgentToolApproval {
    @Volatile var confirmer: Confirmer? = null
}

object AgentToolRuntime {
    @Volatile private var executorInstance: ToolExecutor? = null
    @Volatile private var registryInstance: ToolRegistry? = null

    private fun registry(context: Context): ToolRegistry = registryInstance ?: synchronized(this) {
        registryInstance ?: ToolRegistry().also { registry ->
            FileTools.all().forEach(registry::register)
            SkillTools.all(SkillStore.get(context.applicationContext)).forEach(registry::register)
            WorkspaceTools.all().forEach(registry::register)
            WebTools.all().forEach(registry::register)
            GitHubTools.all(context.applicationContext).forEach(registry::register)
            GitHubWriteTools.all(context.applicationContext).forEach(registry::register)
            ScheduledTaskTools.all(context.applicationContext).forEach(registry::register)
            registryInstance = registry
        }
    }

    fun specs(context: Context): List<ToolSpec> {
        val app = context.applicationContext
        return registry(app).also { it.register(ConnectorTools(app)) }.specs()
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
                registry = registry(app),
                policy = PermissionPolicy(settings),
                audit = audit,
                confirmer = { AgentToolApproval.confirmer ?: DenyingConfirmer },
                redactor = redactor,
                workspace = workspace
            ).also { executorInstance = it }
        }
    }
}
