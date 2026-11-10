package com.jarvis.assistant.tools.terminal

import com.jarvis.assistant.tools.SecretRedactor
import com.jarvis.assistant.tools.Workspace
import java.util.concurrent.ConcurrentHashMap

data class ProcessInfo(val sessionId: String, val pid: Long?, val command: String, val startedAt: Long)

/**
 * All terminal sessions. Keeps track of every process it started so the user (or a model) can list and stop
 * them, and [shutdown] leaves nothing running behind when the app closes a workspace.
 */
class TerminalManager(
    val workspace: Workspace,
    val backend: TerminalBackend,
    val redactor: SecretRedactor,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxSessions: Int = 8
) {
    private val sessions = ConcurrentHashMap<String, TerminalSession>()
    private var counter = 0
    val guard = CommandGuard(workspace.root.path)

    @Synchronized fun create(title: String? = null, id: String? = null): TerminalSession {
        if (sessions.size >= maxSessions) throw IllegalStateException("Too many terminal sessions ($maxSessions). Close one first.")
        val sid = id?.takeIf { it.matches(ID_RE) && !sessions.containsKey(it) } ?: run {
            var n: String
            do { n = "t${++counter}" } while (sessions.containsKey(n))
            n
        }
        return TerminalSession(sid, title?.take(40)?.ifBlank { null } ?: "Terminal $sid", backend, workspace, redactor, clock).also { sessions[sid] = it }
    }

    /** The session with this id, created on first use. [id] must be short letters, digits, dash or underscore. */
    @Synchronized fun getOrCreate(id: String = DEFAULT_ID): TerminalSession {
        require(id.matches(ID_RE)) { "A session name is 1-24 letters, digits, - or _." }
        return sessions[id] ?: create(id = id)
    }

    fun get(id: String): TerminalSession? = sessions[id]

    fun list(): List<TerminalSession> = sessions.values.sortedBy { it.createdAt }

    fun close(id: String): Boolean {
        val s = sessions.remove(id) ?: return false
        s.close()
        return true
    }

    fun clear(id: String): Boolean = sessions[id]?.also { it.clear() } != null

    /** Commands this manager started that are still running. */
    fun processes(): List<ProcessInfo> = list().filter { it.isRunning }.map { ProcessInfo(it.id, it.runningPid, it.runningCommand.orEmpty(), it.runningSince) }

    /** Stops the running command of the session with this id, or the one with this pid. Only own processes can be stopped. */
    fun kill(target: String): String {
        val bySession = sessions[target]
        if (bySession != null) return if (bySession.cancel()) "Stopped the command in session \"$target\"." else "Nothing is running in session \"$target\"."
        val pid = target.toLongOrNull() ?: return "No session or process \"$target\"."
        val owner = list().firstOrNull { it.isRunning && it.runningPid == pid } ?: return "Process $pid was not started by this terminal, so it can not be stopped here."
        owner.cancel()
        return "Stopped process $pid (session \"${owner.id}\")."
    }

    fun shutdown() { sessions.values.forEach { it.close() }; sessions.clear() }

    companion object {
        const val DEFAULT_ID = "main"
        private val ID_RE = Regex("^[A-Za-z0-9_-]{1,24}$")
    }
}
