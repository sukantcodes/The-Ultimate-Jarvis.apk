package com.jarvis.assistant.tools

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import com.jarvis.assistant.provider.AndroidKeyVault
import com.jarvis.assistant.provider.ProviderRuntime
import com.jarvis.assistant.tools.builtin.FileTools
import com.jarvis.assistant.tools.files.DocumentTools
import com.jarvis.assistant.tools.github.GitHubClient
import com.jarvis.assistant.tools.github.GitHubTokenSource
import com.jarvis.assistant.tools.github.GitHubTools
import com.jarvis.assistant.tools.github.InstalledSkillRegistry
import com.jarvis.assistant.tools.github.JavaNetDownloader
import com.jarvis.assistant.tools.server.ServerTools
import com.jarvis.assistant.tools.tasks.runTools
import com.jarvis.assistant.tools.terminal.ProcessBackend
import com.jarvis.assistant.tools.terminal.TerminalManager
import com.jarvis.assistant.tools.terminal.TerminalTools
import com.jarvis.assistant.tools.web.DuckDuckGoHtmlSearch
import com.jarvis.assistant.tools.web.WebClient
import com.jarvis.assistant.tools.web.WebTools
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.lang.ref.WeakReference

/**
 * The one tool layer for the whole app. ChatBox with the Custom API and ChatBox with Gemini (and later voice) get the
 * same [ToolBackend], so permissions, the workspace sandbox, redaction and the audit log are identical whichever
 * provider is answering.
 */
object ToolRuntime {
    private const val GITHUB_TOKEN_ID = "github_access_token"
    @Volatile private var built: Built? = null

    class Built(
        val workspace: Workspace, val registry: ToolRegistry, val executor: ToolExecutor, val backend: ToolBackend,
        val audit: AuditLog, val policy: PermissionPolicy, val redactor: SecretRedactor,
        val terminal: TerminalManager?, val servers: ServerTools, val web: WebTools, val githubToken: GitHubTokenStore,
        val memory: com.jarvis.assistant.tools.memory.MemoryStore, val tasks: com.jarvis.assistant.tools.tasks.TaskStore
    )

    /** Token storage in the same Keystore-backed encrypted vault as API keys. Never logged, never shown in full. */
    class GitHubTokenStore(private val vault: AndroidKeyVault, private val redactor: SecretRedactor) : GitHubTokenSource {
        override fun get(): String? = vault.load(GITHUB_TOKEN_ID)?.reveal()?.takeIf { it.isNotBlank() }
        val isSaved: Boolean get() = get() != null
        fun save(token: String): Boolean { val ok = vault.store(GITHUB_TOKEN_ID, token.trim()); if (ok) redactor.register(token); return ok }
        fun remove() { get()?.let { redactor.forget(it) }; vault.delete(GITHUB_TOKEN_ID) }
        fun masked(): String = get()?.let { if (it.length <= 8) "••••" else it.take(4) + "••••••••" + it.takeLast(4) } ?: "Not connected"
    }

    fun get(context: Context): Built = built ?: synchronized(this) { built ?: build(context.applicationContext).also { built = it } }

    /** What ChatBox gives to the provider engine. Null never happens on a normal device; kept nullable for safety. */
    fun backend(context: Context): ToolBackend? = try { get(context).backend } catch (e: Exception) { null }

    private fun build(app: Context): Built {
        val workspace = Workspace(File(app.filesDir, "workspace"))
        val redactor = SecretRedactor()
        val audit = AuditLog(File(workspace.root, ".jarvis/audit.jsonl"), redactor)
        val prefs = app.getSharedPreferences("tool_permissions", Context.MODE_PRIVATE)
        val policy = PermissionPolicy(PrefsSettings(prefs))
        val transport = ProviderRuntime.transport()
        val registry = ToolRegistry()
        FileTools.all().forEach(registry::register)
        DocumentTools.all().forEach(registry::register)

        val web = WebTools(WebClient(transport), DuckDuckGoHtmlSearch(transport))
        web.all().forEach(registry::register)

        val servers = ServerTools(nodeAvailable = { onPath("node") })
        servers.all().forEach(registry::register)
        registry.register(OpenUrlTool(app, servers.servers))
        AndroidConfirmer.init(app)

        val tokens = GitHubTokenStore(AndroidKeyVault(app), redactor)
        tokens.get()?.let { redactor.register(it) }
        val gh = GitHubClient(transport, tokens, redactor, JavaNetDownloader())
        GitHubTools.all(gh, InstalledSkillRegistry(File(workspace.root, ".jarvis/skills.json")), tokens).forEach(registry::register)

        val terminal = try { TerminalManager(workspace, ProcessBackend(), redactor) } catch (e: Exception) { null }
        terminal?.let { TerminalTools.all(it).forEach(registry::register) }

        val memory = com.jarvis.assistant.tools.memory.MemoryStore(File(app.filesDir, "memory/memories.json"))
        memory.enabled = app.getSharedPreferences("tool_permissions", Context.MODE_PRIVATE).getBoolean("memory_enabled", true)
        com.jarvis.assistant.tools.memory.MemoryTools.all(memory).forEach(registry::register)
        val tasks = com.jarvis.assistant.tools.tasks.TaskStore(File(workspace.root, ".jarvis/tasks"))
        com.jarvis.assistant.tools.tasks.TaskTools(tasks).all().forEach(registry::register)

        val executor = ToolExecutor(registry, policy, audit, { AndroidConfirmer }, redactor, workspace)
        val backend = ExecutorBackend(registry, executor)
        val taskRunner = com.jarvis.assistant.tools.tasks.TaskRunner(tasks, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default), {
            com.jarvis.assistant.provider.TaskSegmentRunner(ProviderRuntime.manager(app), transport, backend, ProviderRuntime.keyRotation(app),
                { com.jarvis.assistant.provider.ChatContext.systemPrompt((app as? android.app.Application)?.let { com.jarvis.assistant.JarvisApp.instance.preferences.userName } ?: "") })
        })
        com.jarvis.assistant.tools.tasks.TaskTools(tasks).runTools(taskRunner, tasks).forEach(registry::register)
        return Built(workspace, registry, executor, backend, audit, policy, redactor, terminal, servers, web, tokens, memory, tasks)
    }

    private fun onPath(program: String): Boolean =
        (System.getenv("PATH") ?: "").split(':').any { it.isNotEmpty() && File(it, program).canExecute() }

    private class PrefsSettings(private val p: SharedPreferences) : PermissionPolicy.SettingsStore {
        override fun get(key: String): String? = p.getString(key, null)
        override fun put(key: String, value: String) { p.edit().putString(key, value).apply() }
    }
}

/**
 * Shows the permission prompt on the screen the user is looking at. If no screen is in front (the app is in the
 * background) or the user does not answer, the answer is DENY: nothing risky ever runs unattended.
 */
object AndroidConfirmer : Confirmer {
    @Volatile private var host: WeakReference<Activity>? = null
    @Volatile private var appContext: Context? = null
    fun init(context: Context) { appContext = context.applicationContext }
    fun attach(activity: Activity) { host = WeakReference(activity); appContext = activity.applicationContext }
    fun detach(activity: Activity) { if (host?.get() === activity) host = null }

    override suspend fun confirm(request: ConfirmRequest): Decision {
        val activity = host?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
            ?: return appContext?.let { ApprovalNotifier.ask(it, request, 120_000L) } ?: Decision.DENY   // not on screen: ask through a notification
        val answer = CompletableDeferred<Decision>()
        withContext(Dispatchers.Main) {
            val title = if (request.destructive) "Confirm: ${request.risk.label}" else "Allow ${request.tool}?"
            val message = buildString {
                append(request.summary).append("\n\n").append(request.risk.label).append(" · ").append(request.group)
                append("\nAsked by: ").append(request.source)
                if (request.destructive) append("\n\nThis can not be undone.")
            }
            val b = AlertDialog.Builder(activity).setTitle(title).setMessage(message).setCancelable(false)
                .setNegativeButton("Deny") { _, _ -> answer.complete(Decision.DENY) }
                .setPositiveButton("Allow once") { _, _ -> answer.complete(Decision.ALLOW_ONCE) }
            if (!request.destructive) b.setNeutralButton("Allow this session") { _, _ -> answer.complete(Decision.ALLOW_SESSION) }
            try { b.show() } catch (e: Exception) { answer.complete(Decision.DENY) }
        }
        return withTimeoutOrNull(120_000L) { answer.await() } ?: Decision.DENY
    }
}
