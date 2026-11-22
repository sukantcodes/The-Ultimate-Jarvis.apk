package com.jarvis.assistant.tools.terminal

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs commands inside Termux (real git, node, npm, python) through its RUN_COMMAND service. It needs the one-time
 * setup in [TermuxBridgeSpec.userSetupSteps]. Output streams live over a loopback socket (like the Termux app); if that socket cannot open it arrives when the command ends. Limits, stated plainly: Termux
 * works in its own folder (~/jarvis-workspace), which this app can not read, so files made by the app's file tools
 * are not there. Every command records its shell's pid inside Termux ([TermuxProcessControl]), so [RunningCommand.kill]
 * really stops the command and everything it started, by sending Termux a small kill script.
 */
class TermuxBackend(private val context: Context, private val spec: TermuxBridgeSpec = TermuxBridgeSpec()) : TerminalBackend {
    override val name = "Termux"
    override val shell = spec.shellPath
    override val isRemote = true
    override fun environment(workspaceRoot: File): Map<String, String> = emptyMap()

    private class Pending(val onOutput: (String) -> Unit) { val latch = CountDownLatch(1); @Volatile var exit: Int? = null }

    override fun launch(script: String, cwd: File, env: Map<String, String>, onOutput: (String) -> Unit): RunningCommand {
        if (!isInstalled(context)) throw IllegalStateException("Termux is not installed.")
        val id = counter.incrementAndGet(); val p = Pending(onOutput); pending[id] = p
        // Live output: Termux sends everything the command prints to a loopback socket this app listens on, so the whole
        // install scrolls past as it happens (like Termux itself) instead of appearing at the end.
        val live = LiveStream.open(onOutput)
        val result = Intent(context, TermuxResultReceiver::class.java).setAction("com.jarvis.assistant.TERMUX_RESULT").putExtra("id", id)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val token = TermuxProcessControl.newToken()
        val tracked = TermuxProcessControl.track(script, token)
        val wrapped = if (live != null) LiveStream.wrap(tracked, live.port) else tracked
        val i = commandIntent(wrapped).putExtra(TermuxBridgeSpec.EXTRA_PENDING_INTENT, PendingIntent.getBroadcast(context, id, result, flags))
        try {
            startTermux(i)
        } catch (e: SecurityException) {
            pending.remove(id); live?.close()
            throw IllegalStateException("Termux refused the command. Grant JARVIS the \"Run commands in Termux environment\" permission and set allow-external-apps=true in ~/.termux/termux.properties (type: jpkg setup).")
        } catch (e: Exception) {
            pending.remove(id); live?.close()
            throw IllegalStateException("Could not reach Termux (${e.javaClass.simpleName}). Open Termux once, then try again. See: jpkg setup")
        }
        return object : RunningCommand {
            override val pid: Long? = null
            override val isAlive get() = p.latch.count > 0
            override val exitCode get() = p.exit
            override fun waitFor(timeoutMs: Long) = p.latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            override fun kill(graceMs: Long) {
                val wasRunning = p.latch.count > 0
                if (wasRunning) remoteKill(token)
                live?.close(); pending.remove(id); p.exit = -1
                if (wasRunning) p.onOutput("\n[Stopped. Termux was told to end the command and everything it started.]\n")
                p.latch.countDown()
            }
        }
    }

    private fun commandIntent(script: String): Intent = Intent(spec.action).setClassName(spec.packageName, spec.service)
        .putExtra(TermuxBridgeSpec.EXTRA_COMMAND_PATH, spec.shellPath)
        .putExtra(TermuxBridgeSpec.EXTRA_ARGUMENTS, arrayOf("-c", script))
        .putExtra(TermuxBridgeSpec.EXTRA_WORKDIR, "/data/data/com.termux/files/home")
        .putExtra(TermuxBridgeSpec.EXTRA_BACKGROUND, true)

    private fun startTermux(i: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        } catch (e: IllegalStateException) {
            // Android may refuse a foreground-service start; a plain start still works while this app is on screen.
            context.startService(i)
        }
    }

    /** Sends Termux the kill script for a command started by [launch]. Best effort: Termux not being reachable leaves nothing to stop. */
    private fun remoteKill(token: String) {
        try { startTermux(commandIntent(TermuxProcessControl.killScript(token))) } catch (_: Exception) { }
    }

    /** A one-connection loopback server that relays what the Termux command prints. */
    private class LiveStream private constructor(private val server: java.net.ServerSocket, private val onOutput: (String) -> Unit) {
        val port: Int get() = server.localPort
        fun close() { try { server.close() } catch (_: Exception) {} }
        private fun start() {
            Thread {
                try {
                    server.soTimeout = 15 * 60_000
                    server.accept().use { c ->
                        val r = c.getInputStream().reader(Charsets.UTF_8)
                        val buf = CharArray(2048)
                        while (true) { val n = r.read(buf); if (n < 0) break; onOutput(String(buf, 0, n)) }
                    }
                } catch (_: Exception) { } finally { close() }
            }.apply { isDaemon = true; name = "jarvis-termux-live"; start() }
        }

        companion object {
            fun open(onOutput: (String) -> Unit): LiveStream? = try {
                LiveStream(java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")), onOutput).also { it.start() }
            } catch (_: Exception) { null }

            /** Runs [script] and copies everything it prints to the socket; if the socket cannot be opened it just runs normally. */
            fun wrap(script: String, port: Int): String = buildString {
                append("__jp_run() {\n").append(script).append("\n}\n")
                append("if exec 9<>/dev/tcp/127.0.0.1/").append(port).append(" 2>/dev/null; then\n")
                append("  command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock >/dev/null 2>&1\n")
                append("  set -o pipefail\n  ( printf '\u25B6 Termux got the command and is running it now.\\n'; __jp_run ) 2>&1 | cat >&9\n  __jp_rc=\${PIPESTATUS[0]}\n")
                append("  command -v termux-wake-unlock >/dev/null 2>&1 && termux-wake-unlock >/dev/null 2>&1\n  exec 9>&-\n  exit \$__jp_rc\n")
                append("else\n  ( __jp_run )\n  exit \$?\nfi\n")
            }
        }
    }

    companion object {
        private val counter = AtomicInteger(0)
        private val pending = ConcurrentHashMap<Int, Pending>()

        fun isInstalled(context: Context): Boolean = try { context.packageManager.getPackageInfo("com.termux", 0); true } catch (e: Exception) { false }

        internal fun deliver(id: Int, stdout: String, stderr: String, exit: Int?, err: Int?, errmsg: String?) {
            val p = pending.remove(id) ?: return
            if (stdout.isNotEmpty()) p.onOutput(stdout)
            if (stderr.isNotEmpty()) p.onOutput(if (stdout.endsWith("\n") || stdout.isEmpty()) stderr else "\n$stderr")
            if (err != null && err != -1 && !errmsg.isNullOrBlank()) p.onOutput("\n[Termux error: $errmsg]\n")
            p.exit = exit ?: if (err != null && err != -1) 1 else 0
            p.latch.countDown()
        }
    }
}

class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("id", -1)
        val b = intent.getBundleExtra("result")
        TermuxBackend.deliver(id, b?.getString("stdout").orEmpty(), b?.getString("stderr").orEmpty(),
            if (b?.containsKey("exitCode") == true) b.getInt("exitCode") else null, if (b?.containsKey("err") == true) b.getInt("err") else null, b?.getString("errmsg"))
    }
}
