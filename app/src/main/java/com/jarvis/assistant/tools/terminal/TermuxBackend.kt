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
 * setup in [TermuxBridgeSpec.userSetupSteps]. Limits, stated plainly: output arrives when the command ends; Termux
 * works in its own folder (~/jarvis-workspace), which this app can not read, so files made by the app's file tools
 * are not there; a running command can not be force-stopped from here.
 */
class TermuxBackend(private val context: Context, private val spec: TermuxBridgeSpec = TermuxBridgeSpec()) : TerminalBackend {
    override val name = "Termux"
    override val shell = spec.shellPath
    override fun environment(workspaceRoot: File): Map<String, String> = emptyMap()

    private class Pending(val onOutput: (String) -> Unit) { val latch = CountDownLatch(1); @Volatile var exit: Int? = null }

    override fun launch(script: String, cwd: File, env: Map<String, String>, onOutput: (String) -> Unit): RunningCommand {
        if (!isInstalled(context)) throw IllegalStateException("Termux is not installed.")
        val id = counter.incrementAndGet(); val p = Pending(onOutput); pending[id] = p
        val result = Intent(context, TermuxResultReceiver::class.java).setAction("com.jarvis.assistant.TERMUX_RESULT").putExtra("id", id)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val wrapped = "mkdir -p \"\$HOME/jarvis-workspace\" && cd \"\$HOME/jarvis-workspace\" && { $script\n}"
        val i = Intent(spec.action).setClassName(spec.packageName, spec.service)
            .putExtra(TermuxBridgeSpec.EXTRA_COMMAND_PATH, spec.shellPath)
            .putExtra(TermuxBridgeSpec.EXTRA_ARGUMENTS, arrayOf("-c", wrapped))
            .putExtra(TermuxBridgeSpec.EXTRA_WORKDIR, "/data/data/com.termux/files/home")
            .putExtra(TermuxBridgeSpec.EXTRA_BACKGROUND, true)
            .putExtra(TermuxBridgeSpec.EXTRA_PENDING_INTENT, PendingIntent.getBroadcast(context, id, result, flags))
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        } catch (e: SecurityException) {
            pending.remove(id)
            throw IllegalStateException("Termux refused the command. Grant JARVIS the \"Run commands in Termux environment\" permission and set allow-external-apps=true in ~/.termux/termux.properties.")
        }
        return object : RunningCommand {
            override val pid: Long? = null
            override val isAlive get() = p.latch.count > 0
            override val exitCode get() = p.exit
            override fun waitFor(timeoutMs: Long) = p.latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            override fun kill(graceMs: Long) { pending.remove(id); p.exit = -1; p.onOutput("\n[Stopped waiting. A command already started in Termux can not be cancelled from here.]\n"); p.latch.countDown() }
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
