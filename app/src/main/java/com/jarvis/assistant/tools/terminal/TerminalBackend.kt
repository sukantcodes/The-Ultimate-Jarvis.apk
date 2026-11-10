package com.jarvis.assistant.tools.terminal

import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/** One command that has been started. */
interface RunningCommand {
    val pid: Long?
    val isAlive: Boolean
    /** Null while running. */
    val exitCode: Int?
    /** Waits up to [timeoutMs]; true when the command has ended (and all of its output has been delivered). */
    fun waitFor(timeoutMs: Long): Boolean
    /** Stops the command and everything it started: polite first, forceful after [graceMs]. */
    fun kill(graceMs: Long = 1500)
}

/**
 * Where commands actually run. [ProcessBackend] runs them in a shell owned by this app. A Termux backend (see
 * [TermuxBridgeSpec]) or any other execution environment can implement the same interface; the session, guard and
 * tools above it do not change.
 */
interface TerminalBackend {
    val name: String
    /** Path of the shell used, for display. */
    val shell: String
    /** The environment a command starts with. Built from an allow-list, never copied from the app's own environment. */
    fun environment(workspaceRoot: File): Map<String, String>
    /** Starts [script] (a shell script text) in [cwd]. Output (stdout and stderr merged) is delivered to [onOutput]. */
    fun launch(script: String, cwd: File, env: Map<String, String>, onOutput: (String) -> Unit): RunningCommand
}

/**
 * Runs commands with the device's own shell (/system/bin/sh on Android, /bin/sh elsewhere) as a child process of
 * this app. It sees only what the app itself can see: no root, and no other app's files. Programs such as git,
 * node or python exist only if they are installed where the PATH points; the app does not bundle them.
 */
class ProcessBackend(
    private val shellCandidates: List<String> = listOf("/system/bin/sh", "/bin/sh"),
    /** The process environment to take PATH-like, non-secret values from. Injected so tests can prove nothing else leaks. */
    private val inherited: () -> Map<String, String> = { System.getenv() },
    private val extraEnv: Map<String, String> = mapOf("GIT_TERMINAL_PROMPT" to "0", "NO_COLOR" to "1", "PAGER" to "cat", "GIT_PAGER" to "cat", "CI" to "true")
) : TerminalBackend {

    override val name: String = "App shell"
    override val shell: String = shellCandidates.firstOrNull { File(it).canExecute() }
        ?: throw IllegalStateException("No shell found (looked for ${shellCandidates.joinToString()}).")

    override fun environment(workspaceRoot: File): Map<String, String> {
        val env = LinkedHashMap<String, String>()
        env["PATH"] = buildPath(inherited()["PATH"])
        env["HOME"] = workspaceRoot.path
        val tmp = File(workspaceRoot, ".tmp").also { it.mkdirs() }
        env["TMPDIR"] = tmp.path
        env["LANG"] = "C.UTF-8"
        env["TERM"] = "dumb"
        env["USER"] = "jarvis"
        env["SHELL"] = shell
        env["npm_config_cache"] = File(workspaceRoot, ".cache/npm").path
        env["XDG_CACHE_HOME"] = File(workspaceRoot, ".cache").path
        // Needed by some Android tools, not secret.
        for (k in listOf("ANDROID_ROOT", "ANDROID_DATA")) inherited()[k]?.let { env[k] = it }
        env.putAll(extraEnv)
        return env
    }

    private fun buildPath(inheritedPath: String?): String {
        val parts = LinkedHashSet<String>()
        inheritedPath?.split(':')?.filter { it.isNotBlank() }?.forEach { parts += it }
        for (p in listOf("/system/bin", "/system/xbin", "/usr/local/bin", "/usr/bin", "/bin", "/data/data/com.termux/files/usr/bin")) if (File(p).isDirectory) parts += p
        if (parts.isEmpty()) parts += "/bin"
        return parts.joinToString(":")
    }

    override fun launch(script: String, cwd: File, env: Map<String, String>, onOutput: (String) -> Unit): RunningCommand {
        val pb = ProcessBuilder(shell, "-c", script).directory(cwd).redirectErrorStream(true)
        pb.environment().clear()
        pb.environment().putAll(env)
        val process = pb.start()
        // Nothing can type into a command: a program that waits for input fails at once instead of hanging.
        try { process.outputStream.close() } catch (_: Exception) {}
        val reader = Thread({
            try {
                InputStreamReader(process.inputStream, Charsets.UTF_8).use { r ->
                    val buf = CharArray(4096)
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        if (n > 0) onOutput(String(buf, 0, n))
                    }
                }
            } catch (_: Exception) { /* the process was killed while reading */ }
        }, "terminal-reader").apply { isDaemon = true; start() }
        return ProcessCommand(process, reader)
    }

    private class ProcessCommand(private val process: Process, private val reader: Thread) : RunningCommand {
        override val pid: Long? = ProcessTree.pidOf(process)
        override val isAlive: Boolean get() = process.isAlive
        override val exitCode: Int? get() = if (process.isAlive) null else process.exitValue()

        override fun waitFor(timeoutMs: Long): Boolean {
            if (!process.waitFor(timeoutMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)) return false
            // The shell is gone; give the reader a moment to deliver the last output.
            reader.join(2000)
            return true
        }

        override fun kill(graceMs: Long) {
            ProcessTree.kill(process, graceMs)
            reader.join(1000)
        }
    }
}

/**
 * Stops a process together with everything it started. Uses ProcessHandle where the platform has it (reflection, so
 * the same code also loads on Android versions without it) and falls back to `pkill -P` on the shell's pid.
 */
object ProcessTree {
    fun pidOf(p: Process): Long? = try {
        (Process::class.java.getMethod("pid").invoke(p) as Long)
    } catch (_: Throwable) {
        try { p.javaClass.getDeclaredField("pid").also { it.isAccessible = true }.getInt(p).toLong() } catch (_: Throwable) { null }
    }

    fun kill(p: Process, graceMs: Long) {
        val handles = descendantHandles(p)
        p.destroy()
        invokeAll(handles, "destroy")
        if (!p.waitFor(graceMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)) p.destroyForcibly()
        if (handles.isNotEmpty()) {
            val deadline = System.currentTimeMillis() + graceMs
            while (System.currentTimeMillis() < deadline && handles.any { isAlive(it) }) Thread.sleep(25)
            invokeAll(handles.filter { isAlive(it) }, "destroyForcibly")
        } else {
            pkillChildren(pidOf(p))
        }
        p.waitFor(1, TimeUnit.SECONDS)
    }

    private fun descendantHandles(p: Process): List<Any> = try {
        val phClass = Class.forName("java.lang.ProcessHandle")
        val handle = Process::class.java.getMethod("toHandle").invoke(p)
        val stream = phClass.getMethod("descendants").invoke(handle)
        @Suppress("UNCHECKED_CAST")
        (Class.forName("java.util.stream.Stream").getMethod("toArray").invoke(stream) as Array<Any?>).filterNotNull()
    } catch (_: Throwable) { emptyList() }

    private fun isAlive(h: Any): Boolean = try { Class.forName("java.lang.ProcessHandle").getMethod("isAlive").invoke(h) as Boolean } catch (_: Throwable) { false }

    private fun invokeAll(handles: List<Any>, method: String) {
        val phClass = try { Class.forName("java.lang.ProcessHandle") } catch (_: Throwable) { return }
        for (h in handles) try { phClass.getMethod(method).invoke(h) } catch (_: Throwable) {}
    }

    private fun pkillChildren(pid: Long?) {
        if (pid == null) return
        try { Runtime.getRuntime().exec(arrayOf("pkill", "-KILL", "-P", pid.toString())).waitFor(1, TimeUnit.SECONDS) } catch (_: Throwable) {}
    }
}
