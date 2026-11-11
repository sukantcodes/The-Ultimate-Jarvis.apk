package com.jarvis.assistant.tools.terminal

import com.jarvis.assistant.tools.SecretRedactor
import com.jarvis.assistant.tools.Workspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.io.File

/** Keeps the most recent output, with absolute positions so a reader can ask for "everything since last time". */
class OutputBuffer(private val capChars: Int = 200_000) {
    private val sb = StringBuilder()
    private var startOffset = 0L

    val droppedChars: Long @Synchronized get() = startOffset
    val endOffset: Long @Synchronized get() = startOffset + sb.length

    @Synchronized fun append(text: String) {
        sb.append(text)
        if (sb.length > capChars) {
            val drop = sb.length - capChars
            sb.delete(0, drop); startOffset += drop
        }
    }

    /** Text from [offset] on (or from the oldest kept text if [offset] was already dropped), and the new offset. */
    @Synchronized fun readFrom(offset: Long): Pair<String, Long> {
        val from = (offset - startOffset).coerceIn(0, sb.length.toLong()).toInt()
        return sb.substring(from) to (startOffset + sb.length)
    }

    @Synchronized fun tail(maxChars: Int): String = if (sb.length <= maxChars) sb.toString() else sb.substring(sb.length - maxChars)

    @Synchronized fun clear() { startOffset += sb.length; sb.setLength(0) }
}

data class CommandResult(
    val exitCode: Int?,
    val output: String,
    val timedOut: Boolean = false,
    val cancelled: Boolean = false,
    val elapsedMs: Long = 0,
    val cwd: String = "",
    /** Set when the command was not started at all, with the reason. */
    val rejected: String? = null,
    val outputTruncated: Boolean = false
) {
    val ok: Boolean get() = rejected == null && !timedOut && !cancelled && exitCode == 0
}

data class HistoryEntry(val command: String, val startedAt: Long, var exitCode: Int?)

/**
 * One shell "tab". Each command runs in its own shell process starting in the folder the previous command left
 * off in, so `cd` and relative paths persist like in a real terminal (shell variables do not). Only one command
 * runs at a time per session; use several sessions for parallel work.
 */
class TerminalSession(
    val id: String,
    val title: String,
    private val backend: TerminalBackend,
    private val workspace: Workspace,
    private val redactor: SecretRedactor,
    private val clock: () -> Long = System::currentTimeMillis,
    bufferChars: Int = 200_000,
    private val maxBackgroundMs: Long = 60 * 60_000L
) {
    val createdAt: Long = clock()
    val buffer = OutputBuffer(bufferChars)
    private val _output = MutableSharedFlow<String>(extraBufferCapacity = 256)
    /** Live output as it arrives (already redacted). */
    val output: SharedFlow<String> = _output.asSharedFlow()

    @Volatile var cwd: File = workspace.root
        private set
    @Volatile private var current: RunningCommand? = null
    @Volatile private var currentCommand: String? = null
    @Volatile private var currentStarted: Long = 0
    @Volatile private var lastExit: Int? = null
    private var readCursor = 0L
    private val historyList = ArrayList<HistoryEntry>()
    private val lock = Any()

    val history: List<HistoryEntry> get() = synchronized(lock) { historyList.toList() }
    val isRunning: Boolean get() = current?.isAlive == true
    val runningCommand: String? get() = if (isRunning) currentCommand else null
    val runningPid: Long? get() = if (isRunning) current?.pid else null
    val runningSince: Long get() = currentStarted
    val lastExitCode: Int? get() = lastExit

    fun relativeCwd(): String = workspace.relative(cwd).ifEmpty { "." }

    /** Moves the session to [relative] inside the workspace. Throws if the folder does not exist or is outside. */
    fun changeDirectory(relative: String) {
        val d = workspace.resolve(relative)
        require(d.isDirectory) { "Not a folder: $relative" }
        cwd = d
    }

    /** What would stop a command from starting: leaving the workspace with `cd`, or another command already running. */
    fun preflight(command: String): String? {
        if (isRunning) return "A command is still running in session \"$id\". Wait for it, read it, or stop it first."
        if (command.isBlank()) return "The command is empty."
        if (command.length > 20_000) return "The command is too long."
        confinementProblem(command)?.let { return it }
        return null
    }

    private fun confinementProblem(command: String): String? {
        var dir = cwd
        for (seg in ShellWords.segments(command)) {
            val w = ShellWords.words(seg)
            if (w.isEmpty() || w[0] != "cd") continue
            val target = w.drop(1).firstOrNull { !it.startsWith("-") } ?: workspace.root.path
            if (target.contains('$') || target.contains('`')) return "cd with a variable can not be checked; use a plain folder name."
            val resolved = when {
                target == "~" || target.startsWith("~/") -> File(workspace.root, target.removePrefix("~").trimStart('/'))
                target.startsWith("/") -> File(target)
                else -> File(dir, target)
            }
            val canonical = try { resolved.canonicalFile } catch (e: Exception) { return "That folder can not be resolved." }
            if (canonical != workspace.root && !canonical.path.startsWith(workspace.root.path + File.separator))
                return "Refused: \"cd $target\" would leave the workspace. Terminal commands stay inside the workspace folder."
            dir = canonical
        }
        return null
    }

    /**
     * Runs [command] to completion (or until [timeoutMs]). Cancelling the calling coroutine stops the command and
     * everything it started. A command that exceeds the timeout is killed and reported as timed out.
     */
    suspend fun run(command: String, timeoutMs: Long, onChunk: ((String) -> Unit)? = null): CommandResult {
        preflight(command)?.let { return CommandResult(null, "", rejected = it, cwd = relativeCwd()) }
        val started = clock()
        val run = start(command, onChunk)
        var finished = false
        try {
            withContext(Dispatchers.IO) {
                val deadline = clock() + timeoutMs
                while (true) {
                    if (run.cmd.waitFor(40)) { finished = true; break }
                    if (clock() >= deadline) break
                    kotlinx.coroutines.yield()
                }
            }
        } finally {
            if (!finished && run.cmd.isAlive) withContext(kotlinx.coroutines.NonCancellable) { run.cmd.kill() }
        }
        val timedOut = !finished
        return finish(run, command, started, timedOut = timedOut, cancelled = false)
    }

    /** Starts [command] and returns at once; follow it with [readNew] and stop it with [cancel]. */
    fun startBackground(command: String): String? {
        preflight(command)?.let { return it }
        val run = start(command, null)
        Thread({
            if (!run.cmd.waitFor(maxBackgroundMs)) { run.cmd.kill() }
            run.lines.flush(); applyMarker(run); lastExit = run.cmd.exitCode; run.history.exitCode = lastExit
        }, "terminal-watchdog-$id").apply { isDaemon = true; start() }
        return null
    }

    /** Output written since the last call to [readNew]. */
    fun readNew(maxChars: Int = 20_000): String {
        val (text, end) = synchronized(lock) { buffer.readFrom(readCursor) }
        val shown = if (text.length > maxChars) text.takeLast(maxChars) else text
        synchronized(lock) { readCursor = end }
        return if (text.length > maxChars) "… (${text.length - maxChars} earlier characters skipped)\n$shown" else shown
    }

    /** Stops the running command, if any. Returns true when something was stopped. */
    fun cancel(): Boolean {
        val c = current ?: return false
        if (!c.isAlive) return false
        wasKilled = true
        c.kill()
        return true
    }

    fun clear() { buffer.clear(); synchronized(lock) { readCursor = buffer.endOffset } }

    fun close() { cancel() }

    // ---- internals ----------------------------------------------------------------------------------

    private inner class Run(val cmd: RunningCommand, val marker: String, val lines: LineSink, val history: HistoryEntry)

    private fun start(command: String, onChunk: ((String) -> Unit)?): Run {
        val marker = "__JV_CWD_" + java.lang.Long.toHexString(System.nanoTime()) + "_" + (0..9999).random() + "__"
        val sink = LineSink(marker, onChunk)
        val script = buildString {
            append("cd ").append(ShellQuote.quote(cwd.path)).append(" || exit 97\n")
            append(command).append('\n')
            append("__jv_rc=\$?\n")
            append("printf '\\n%s%s\\n' ").append(marker).append(" \"\$(pwd)\"\n")
            append("exit \$__jv_rc\n")
        }
        val env = backend.environment(workspace.root)
        currentCommand = command
        currentStarted = clock()
        lastExit = null
        wasKilled = false
        val entry = HistoryEntry(redactor.redact(command).take(2000), currentStarted, null)
        synchronized(lock) {
            historyList += entry
            while (historyList.size > 200) historyList.removeAt(0)
        }
        val cmd = backend.launch(script, cwd, env) { chunk -> sink.feed(chunk) }
        current = cmd
        return Run(cmd, marker, sink, entry)
    }

    private fun finish(run: Run, command: String, started: Long, timedOut: Boolean, cancelled: Boolean): CommandResult {
        val exit = if (timedOut) null else run.cmd.exitCode
        run.lines.flush()
        applyMarker(run)
        lastExit = exit
        run.history.exitCode = exit
        val wasCancelled = cancelled || (!timedOut && wasKilled)
        val text = run.lines.captured()
        val cap = 60_000
        return CommandResult(
            exitCode = exit, output = if (text.length > cap) text.takeLast(cap) else text, timedOut = timedOut, cancelled = wasCancelled,
            elapsedMs = clock() - started, cwd = relativeCwd(), outputTruncated = text.length > cap
        )
    }

    @Volatile private var wasKilled = false

    private fun applyMarker(run: Run) {
        val reported = run.lines.reportedCwd ?: return
        val f = File(reported)
        val ok = try { f.canonicalFile.let { it == workspace.root || it.path.startsWith(workspace.root.path + File.separator) } } catch (e: Exception) { false }
        if (ok && f.isDirectory) cwd = f.canonicalFile
        else {
            cwd = workspace.root
            val note = "\n[Moved back to the workspace folder: a command ended outside it.]\n"
            buffer.append(note); _output.tryEmit(note)
        }
    }

    /** Turns raw chunks into redacted whole lines, hides the cwd marker line, and feeds buffer, flow and callback. */
    private inner class LineSink(private val marker: String, private val onChunk: ((String) -> Unit)?) {
        private val partial = StringBuilder()
        private val all = StringBuilder()
        var reportedCwd: String? = null
            private set

        @Synchronized fun feed(chunk: String) {
            partial.append(chunk)
            while (true) {
                val nl = partial.indexOf("\n")
                if (nl < 0) break
                emitLine(partial.substring(0, nl + 1))
                partial.delete(0, nl + 1)
            }
            // A very long line without a break is emitted in pieces so memory stays bounded.
            if (partial.length > 8192) { emitLine(partial.toString()); partial.setLength(0) }
        }

        @Synchronized fun flush() { if (partial.isNotEmpty()) { emitLine(partial.toString()); partial.setLength(0) } }

        @Synchronized fun captured(): String = all.toString().trimEnd('\n', '\r').let { it }

        private var heldBlank = false

        private fun emitLine(raw: String) {
            val line = raw.replace(ANSI, "")
            val m = line.indexOf(marker)
            if (m >= 0) { heldBlank = false; reportedCwd = line.substring(m + marker.length).trim(); return }
            // The wrapper prints one line break before the marker; a bare line break is held back and dropped if the marker follows.
            if (line == "\n") {
                if (heldBlank) deliver("\n")
                heldBlank = true
                return
            }
            if (heldBlank) { deliver("\n"); heldBlank = false }
            deliver(line)
        }

        private fun deliver(line: String) {
            val safe = redactor.redact(line)
            all.append(safe)
            if (all.length > 400_000) all.delete(0, all.length - 300_000)
            buffer.append(safe)
            _output.tryEmit(safe)
            onChunk?.invoke(safe)
        }
    }

    companion object {
        private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u0007]*\u0007")
    }
}
