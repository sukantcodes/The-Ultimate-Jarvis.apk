package com.jarvis.assistant.tools.server

import com.jarvis.assistant.tools.terminal.RunningCommand
import com.jarvis.assistant.tools.terminal.TerminalBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID

sealed class StartResult {
    data class Started(val tunnel: Tunnel) : StartResult()
    /** A link for this port is already open (or opening); nothing new was started. */
    data class AlreadyActive(val tunnel: Tunnel) : StartResult()
    data class Rejected(val message: String) : StartResult()
}

/** A local server that could be shared. [managed] servers are JARVIS previews; the rest were found by looking at common ports. */
data class ForwardCandidate(val port: Int, val label: String, val managed: Boolean, val shared: Boolean)

/**
 * Owns every public link. One tunnel program runs per forwarded port (at most [Config.maxTunnels]), started through the
 * terminal's backend (Termux on a phone), so the existing process plumbing is reused instead of a second one.
 *
 * A link is ended, and its process stopped, when: the person stops it; its time limit passes; the preview it belongs to
 * is stopped (listener on [ServerManager]); the local port stops answering (a poll that exists only while a link is
 * open); the connection to Cloudflare stays down for [Config.reconnectLimitMs]; or the tunnel program ends by itself.
 * The runner script has its own time limit and port watchdog, so a link also ends if JARVIS is killed.
 *
 * Nothing here blocks the caller for long: [start] returns as soon as the program is launched and progress is
 * published on [tunnels]. [stop] returns at once and the process is stopped in the background.
 */
class TunnelManager(
    private val backend: TerminalBackend,
    private val workspaceRoot: File,
    private val servers: ServerManager,
    private val scope: CoroutineScope,
    private val probe: PortProbe = LoopbackProbe(),
    private val verifier: UrlVerifier = HttpUrlVerifier(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val cloudflaredAvailable: () -> Boolean = { isCloudflaredOnPath() },
    private val config: Config = Config()
) : AutoCloseable {

    data class Config(
        val maxTunnels: Int = 10,
        val defaultMinutes: Int = 30,
        val maxMinutes: Int = 1440,
        val monitorIntervalMs: Long = 2_000,
        val missesBeforeStop: Int = 4,
        val startupLimitMs: Long = 60_000,
        /** No address from Cloudflare this long after the program started: it is stuck, so start a fresh one instead of waiting out [startupLimitMs]. */
        val urlLimitMs: Long = 15_000,
        /** Address received but the tunnel is still not accepted this long after the program started: start a fresh one. */
        val registerLimitMs: Long = 25_000,
        val installLimitMs: Long = 300_000,
        val reconnectLimitMs: Long = 180_000,
        val verifyFirstDelayMs: Long = 0,
        val verifyIntervalMs: Long = 400,
        val verifyAttempts: Int = 30,
        /** The link is handed out this long after Cloudflare accepts it even if this phone could not fetch it yet; checking carries on in the background. */
        val fastLiveMs: Long = 1_500,
        /** How long a stopped or failed link stays in the list so the person can read why. */
        val lingerMs: Long = 15_000,
        /** Tries to get a link going before giving up (the second and third use HTTP/2, which networks that block QUIC allow). */
        val maxAttempts: Int = 3,
        val retryDelayMs: Long = 500,
        /** While a link is live and has been seen working from this phone, it is fetched this often; a dead link is rebuilt. */
        val healthIntervalMs: Long = 30_000,
        val healthFailsBeforeHeal: Int = 3,
        val maxHeals: Int = 2
    )

    private class Entry(val id: String, val port: Int, val host: String, val label: String, val startedAt: Long, val expiresAt: Long) {
        @Volatile var state: TunnelState = TunnelState.Preparing("Starting the tunnel")
        @Volatile var command: RunningCommand? = null
        @Volatile var url: String? = null
        @Volatile var registered = false
        @Volatile var verified = false
        @Volatile var verifyStarted = false
        @Volatile var installing = false
        @Volatile var sawOutput = false
        @Volatile var stopReason: StopReason? = null
        @Volatile var failMessage: String? = null
        @Volatile var note: String? = null
        @Volatile var reconnectSince = 0L
        @Volatile var monitor: Job? = null
        @Volatile var verify: Job? = null
        // HTTP/2 (TCP) connects first time on every network; QUIC (UDP) is blocked on many mobile and Wi-Fi networks and cloudflared then wastes a long time falling back.
        @Volatile var protocol = "http2"
        @Volatile var announced = false
        @Volatile var attempt = 1
        @Volatile var heals = 0
        @Volatile var generation = 0
        @Volatile var launchedAt = 0L
        @Volatile var healthFails = 0
        @Volatile var lastHealthAt = 0L
        @Volatile var everLive = false
        @Volatile var matchesLocal: Boolean? = null
        val partial = StringBuilder()
        val tail = ArrayDeque<String>()
        val terminal: Boolean get() = state is TunnelState.Stopped || state is TunnelState.Failed
        fun snapshot() = Tunnel(id, port, label, startedAt, expiresAt, state, note)
    }

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()
    private val _tunnels = MutableStateFlow<List<Tunnel>>(emptyList())

    /** Every link that is open, opening, or recently ended, oldest first. */
    val tunnels: StateFlow<List<Tunnel>> = _tunnels.asStateFlow()

    private val stopListener: AutoCloseable = servers.addStopListener { _, port -> stop(port, StopReason.SERVER_STOPPED) }

    fun get(id: String): Tunnel? = synchronized(lock) { entries[id]?.snapshot() }
    fun active(): List<Tunnel> = synchronized(lock) { entries.values.filter { !it.terminal }.map { it.snapshot() } }
    fun forPort(port: Int): Tunnel? = synchronized(lock) { entries.values.firstOrNull { it.port == port && !it.terminal }?.snapshot() }

    // ---- finding something to share ---------------------------------------------------------------------------

    /** JARVIS previews that are running, plus anything answering on the usual development ports. Blocks briefly; call it off the main thread. */
    fun detect(): List<ForwardCandidate> {
        val managed = servers.running().map { (name, s) -> name to s.boundPort }.filter { it.second > 0 }
        val managedPorts = managed.map { it.second }.toSet()
        val shared = synchronized(lock) { entries.values.filter { !it.terminal }.map { it.port }.toSet() }
        val scan = PortPolicy.COMMON.filter { it !in managedPorts }
        val open = runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(3_000) { scan.map { p -> async { if (probe.isListening(p)) p else null } }.awaitAll().filterNotNull() } ?: emptyList()
        }
        return managed.map { ForwardCandidate(it.second, it.first, true, it.second in shared) } +
            open.map { ForwardCandidate(it, "Local server on port $it", false, it in shared) }
    }

    // ---- starting and stopping --------------------------------------------------------------------------------

    /**
     * Opens a link to [port]. The port must be allowed, must really be answering, and may not already have a link
     * (asking twice returns the existing one). Confirmation from the person is the caller's job and happens first.
     */
    fun start(port: Int, label: String? = null, minutes: Int? = null): StartResult {
        PortPolicy.check(port)?.let { return StartResult.Rejected(it) }
        if (!backend.isRemote && !cloudflaredAvailable()) return StartResult.Rejected(TunnelScript.NOT_AVAILABLE)
        val host = probe.listeningHost(port)
            ?: return StartResult.Rejected("Nothing is listening on port $port, so there is nothing to share. Start the server first, then try again.")
        val mins = (minutes ?: config.defaultMinutes).coerceIn(1, config.maxMinutes)
        val now = clock()
        val entry = synchronized(lock) {
            entries.values.firstOrNull { it.port == port && !it.terminal }?.let { return StartResult.AlreadyActive(it.snapshot()) }
            if (entries.values.count { !it.terminal } >= config.maxTunnels) {
                return StartResult.Rejected("${config.maxTunnels} public links are already open. Stop one first.")
            }
            val name = label?.takeIf { it.isNotBlank() } ?: "Port $port"
            Entry(UUID.randomUUID().toString().take(8), port, host, name.take(60), now, now + mins * 60_000L)
                .also { entries[it.id] = it; publishLocked() }
        }
        launchProcess(entry, mins * 60)
        return StartResult.Started(entry.snapshot())
    }

    /** Closes the link to [port], if there is one. Returns at once; the process is stopped in the background. */
    fun stop(port: Int, reason: StopReason = StopReason.USER): Boolean {
        val e = synchronized(lock) { entries.values.firstOrNull { it.port == port && !it.terminal } } ?: return false
        stopEntry(e, reason, sync = false)
        return true
    }

    fun stopById(id: String, reason: StopReason = StopReason.USER): Boolean {
        val e = synchronized(lock) { entries[id]?.takeIf { !it.terminal } } ?: return false
        stopEntry(e, reason, sync = false)
        return true
    }

    fun stopAll(reason: StopReason = StopReason.USER): Int {
        val open = synchronized(lock) { entries.values.filter { !it.terminal } }
        open.forEach { stopEntry(it, reason, sync = false) }
        return open.size
    }

    /** Ends every link and stops listening to previews. Processes are stopped before this returns. */
    override fun close() {
        runCatching { stopListener.close() }
        val open = synchronized(lock) { entries.values.filter { !it.terminal } }
        open.forEach { stopEntry(it, StopReason.APP_CLOSED, sync = true) }
    }

    // ---- internals --------------------------------------------------------------------------------------------

    private fun launchProcess(e: Entry, ttlSeconds: Int) {
        val ttl = if (ttlSeconds > 0) ttlSeconds else ((e.expiresAt - clock()) / 1000).toInt().coerceAtLeast(30)
        val script = TunnelScript.build(e.port, e.host, ttl, install = backend.isRemote, protocol = e.protocol)
        val gen = synchronized(lock) { e.launchedAt = clock(); ++e.generation }
        val cmd = try {
            // Output from an earlier process of this link (killed during a retry) must not change the new one.
            backend.launch(script, workspaceRoot, backend.environment(workspaceRoot)) { chunk -> if (e.generation == gen) onOutput(e, chunk) }
        } catch (ex: Exception) {
            fail(e, ex.message ?: "The tunnel program could not be started.")
            return
        }
        // A stop that arrived while the program was starting could not kill it yet: do it now.
        val stoppedMeanwhile = synchronized(lock) { e.command = cmd; e.terminal }
        if (stoppedMeanwhile) { kill(cmd, sync = false); return }
        e.monitor = scope.launch(Dispatchers.IO) { monitor(e) }
    }

    private fun onOutput(e: Entry, chunk: String) {
        val events = ArrayList<CloudflaredLog.Event>()
        synchronized(e.partial) {
            e.partial.append(chunk)
            while (true) {
                val nl = e.partial.indexOf("\n")
                if (nl < 0) break
                val line = e.partial.substring(0, nl)
                e.partial.delete(0, nl + 1)
                val clean = CloudflaredLog.clean(line)
                if (clean.isNotEmpty()) { e.tail.addLast(clean.take(200)); while (e.tail.size > 6) e.tail.removeFirst() }
                CloudflaredLog.parse(line)?.let { events += it }
            }
            if (e.partial.length > 8_192) e.partial.setLength(0)
        }
        events.forEach { apply(e, it) }
    }

    private fun apply(e: Entry, ev: CloudflaredLog.Event) {
        var failure: String? = null
        var retryable = false
        var verifyUrl: String? = null
        synchronized(lock) {
            if (e.terminal) return
            e.sawOutput = true
            when (ev) {
                is CloudflaredLog.Event.Installing -> {
                    e.installing = true
                    if (e.state is TunnelState.Preparing) setLocked(e, TunnelState.Preparing("Installing cloudflared (first time only)"))
                }
                is CloudflaredLog.Event.Starting -> if (e.state is TunnelState.Preparing) setLocked(e, TunnelState.Preparing("Starting the tunnel"))
                is CloudflaredLog.Event.Url -> {
                    e.url = ev.url
                    if (e.state is TunnelState.Preparing || e.state is TunnelState.Connecting) setLocked(e, TunnelState.Connecting(ev.url))
                    verifyUrl = verifyTargetLocked(e)
                }
                is CloudflaredLog.Event.Registered -> {
                    e.registered = true
                    val u = e.url
                    if (e.state is TunnelState.Reconnecting && u != null) {
                        e.reconnectSince = 0; e.note = null
                        setLocked(e, TunnelState.Live(u, e.verified, e.matchesLocal))
                    } else verifyUrl = verifyTargetLocked(e)
                }
                is CloudflaredLog.Event.Retrying -> {
                    val u = e.url
                    if (e.state is TunnelState.Live && u != null) { e.reconnectSince = clock(); setLocked(e, TunnelState.Reconnecting(u)) }
                }
                is CloudflaredLog.Event.OriginDown -> { e.note = "The local server is not answering. Check that it is still running."; publishLocked() }
                is CloudflaredLog.Event.StartFailed -> { failure = ev.message; retryable = true }
                is CloudflaredLog.Event.InstallFailed -> failure = "cloudflared could not be installed in Termux. Open Termux and run: pkg install cloudflared"
                is CloudflaredLog.Event.NotInstalled -> failure = TunnelScript.NOT_AVAILABLE
                is CloudflaredLog.Event.ServerGone -> if (e.stopReason == null) e.stopReason = StopReason.SERVER_STOPPED
                is CloudflaredLog.Event.Expired -> if (e.stopReason == null) e.stopReason = StopReason.EXPIRED
                is CloudflaredLog.Event.Closed -> Unit
            }
            Unit
        }
        failure?.let { if (!(retryable && retry(e, heal = false))) fail(e, it) }
        verifyUrl?.let { startVerify(e, it) }
    }

    /** The address to check, once, when Cloudflare has both handed it out and accepted the tunnel. Call with [lock] held. */
    private fun verifyTargetLocked(e: Entry): String? {
        val u = e.url
        if (u == null || !e.registered || e.verifyStarted) return null
        e.verifyStarted = true
        return u
    }

    private fun localUrl(e: Entry) = "http://${if (e.host == "::1") "[::1]" else e.host}:${e.port}/"

    /** Fetches the public address from this phone (out to the internet and back in) until it answers, and compares it with the local page. */
    private fun startVerify(e: Entry, url: String) {
        // The link is handed out after fastLiveMs no matter what: a check that hangs (a brand new address can stall a connection
        // for several seconds) must never hold the person back, so this timer is separate from the checks themselves.
        scope.launch(Dispatchers.IO) {
            delay(config.fastLiveMs)
            synchronized(lock) {
                if (!e.terminal && e.url == url && (e.state is TunnelState.Connecting || e.state is TunnelState.Preparing)) {
                    e.announced = true
                    setLocked(e, TunnelState.Live(url, false, null))
                }
            }
        }
        e.verify = scope.launch(Dispatchers.IO) {
            delay(config.verifyFirstDelayMs)
            var result = LinkCheck(false)
            var attempt = 0
            while (!result.reachable && attempt < config.verifyAttempts && !e.terminal) {
                result = try { verifier.check(url, localUrl(e)) } catch (_: Exception) { LinkCheck(false) }
                if (!result.reachable) delay(config.verifyIntervalMs)
                attempt++
            }
            synchronized(lock) {
                if (!e.terminal && (e.state is TunnelState.Connecting || e.state is TunnelState.Preparing || (e.announced && e.state is TunnelState.Live))) {
                    e.verified = result.reachable; e.matchesLocal = result.sameAsLocal; e.everLive = true; e.lastHealthAt = System.currentTimeMillis()
                    setLocked(e, TunnelState.Live(url, result.reachable, result.sameAsLocal))
                }
            }
        }
    }

    /**
     * Starts over with a fresh process: after a start that failed (up to [Config.maxAttempts] tries, HTTP/2 from the second),
     * or when a link that worked stopped answering ([heal], up to [Config.maxHeals] times; the address then changes).
     * Returns false when the tries are used up, and the caller reports the failure.
     */
    private fun retry(e: Entry, heal: Boolean): Boolean {
        val old: RunningCommand?
        synchronized(lock) {
            if (e.terminal) return true
            if (heal) { if (e.heals >= config.maxHeals) return false; e.heals++ }
            else { if (e.attempt >= config.maxAttempts) return false; e.attempt++ }
            e.protocol = "http2"
            old = e.command; e.command = null
            e.url = null; e.registered = false; e.verified = false; e.verifyStarted = false; e.matchesLocal = null; e.announced = false
            e.healthFails = 0; e.reconnectSince = 0; e.installing = false; e.failMessage = null
            e.note = if (heal) "The link stopped answering, so a new one was made. The old address no longer works." else null
            setLocked(e, TunnelState.Preparing(
                if (heal) "The link stopped answering. Reconnecting with a new link…" else "Trying again (attempt ${e.attempt} of ${config.maxAttempts})…"))
        }
        e.verify?.cancel(); e.monitor?.cancel()
        old?.let { kill(it, sync = false) }
        scope.launch(Dispatchers.IO) {
            delay(config.retryDelayMs)
            if (!e.terminal) launchProcess(e, 0)
        }
        return true
    }

    private suspend fun monitor(e: Entry) {
        var misses = 0
        while (true) {
            delay(config.monitorIntervalMs)
            if (e.terminal) return
            val cmd = e.command ?: return
            if (!cmd.isAlive) { endedByItself(e); return }
            val now = clock()
            if (now >= e.expiresAt) { stopEntry(e, StopReason.EXPIRED, sync = false); return }
            if (probe.isListening(e.port)) misses = 0 else misses++
            if (misses >= config.missesBeforeStop) { stopEntry(e, StopReason.SERVER_STOPPED, sync = false); return }
            when (e.state) {
                is TunnelState.Preparing, is TunnelState.Connecting -> {
                    // A start that is stuck is restarted early instead of waiting out the full minute.
                    val limit = when {
                        e.installing -> config.installLimitMs
                        e.url == null -> minOf(config.urlLimitMs, config.startupLimitMs)
                        else -> minOf(config.registerLimitMs, config.startupLimitMs)
                    }
                    if (now - e.launchedAt > limit) { if (!retry(e, heal = false)) fail(e, startupTimeoutMessage(e)); return }
                }
                is TunnelState.Live -> if (e.verified && System.currentTimeMillis() - e.lastHealthAt >= config.healthIntervalMs) {
                    e.lastHealthAt = System.currentTimeMillis()
                    val url = (e.state as? TunnelState.Live)?.url
                    val ok = url != null && (try { verifier.check(url, localUrl(e)).reachable } catch (_: Exception) { false })
                    if (ok) e.healthFails = 0
                    else if (++e.healthFails >= config.healthFailsBeforeHeal && !retry(e, heal = true)) {
                        fail(e, "The link stopped answering and could not be restored. Ask for a new one."); return
                    } else if (e.healthFails >= config.healthFailsBeforeHeal) return
                }
                is TunnelState.Reconnecting -> if (now - e.reconnectSince > config.reconnectLimitMs) { stopEntry(e, StopReason.NETWORK_LOST, sync = false); return }
                else -> Unit
            }
        }
    }

    private fun startupTimeoutMessage(e: Entry): String = when {
        !e.sawOutput -> "Termux did not report back. Open Termux once, make sure external apps are allowed (type: jpkg setup), then try again."
        e.installing -> "Installing cloudflared took too long. Check the phone's internet connection and try again."
        else -> "The link did not finish connecting. Check the phone's internet connection and try again."
    }

    /** The program ended without being asked to: say why from what it printed. */
    private fun endedByItself(e: Entry) {
        val unexpected = synchronized(lock) { !e.terminal && e.stopReason == null && e.failMessage == null }
        if (unexpected && !(e.state is TunnelState.Preparing && e.installing) && retry(e, heal = e.everLive)) return
        val cmd: RunningCommand?
        synchronized(lock) {
            if (e.terminal) return
            val reason = e.stopReason
            val failure = e.failMessage
            val state = when {
                reason != null -> TunnelState.Stopped(reason, e.url)
                failure != null -> TunnelState.Failed(failure)
                else -> {
                    val last = synchronized(e.partial) { e.tail.lastOrNull() }
                    TunnelState.Failed("The tunnel program ended unexpectedly (exit code ${e.command?.exitCode ?: "unknown"})." + if (last != null) " Last message: $last" else "")
                }
            }
            setLocked(e, state); lingerLocked(e)
            cmd = e.command
        }
        e.verify?.cancel()
        cmd?.let { kill(it, sync = false) }
    }

    private fun stopEntry(e: Entry, reason: StopReason, sync: Boolean) {
        val cmd: RunningCommand?
        synchronized(lock) {
            if (e.terminal) return
            if (e.stopReason == null) e.stopReason = reason
            setLocked(e, TunnelState.Stopped(e.stopReason ?: reason, e.url))
            lingerLocked(e)
            cmd = e.command
        }
        e.verify?.cancel()
        e.monitor?.cancel()
        if (cmd != null) kill(cmd, sync)
    }

    private fun fail(e: Entry, message: String) {
        val cmd: RunningCommand?
        synchronized(lock) {
            if (e.terminal) return
            e.failMessage = message
            setLocked(e, TunnelState.Failed(message)); lingerLocked(e)
            cmd = e.command
        }
        e.verify?.cancel()
        e.monitor?.cancel()
        if (cmd != null) kill(cmd, sync = false)
    }

    private fun kill(cmd: RunningCommand, sync: Boolean) {
        if (sync) { try { cmd.kill() } catch (_: Exception) { }; return }
        scope.launch(Dispatchers.IO) { try { cmd.kill() } catch (_: Exception) { } }
    }

    /** Takes a finished link out of the list after [Config.lingerMs]. */
    private fun lingerLocked(e: Entry) {
        scope.launch {
            delay(config.lingerMs)
            synchronized(lock) { if (entries[e.id]?.terminal == true) { entries.remove(e.id); publishLocked() } }
        }
    }

    private fun setLocked(e: Entry, s: TunnelState) { e.state = s; publishLocked() }

    private fun publishLocked() { _tunnels.value = entries.values.map { it.snapshot() } }

    companion object {
        fun isCloudflaredOnPath(): Boolean =
            (System.getenv("PATH") ?: "").split(':').any { it.isNotEmpty() && File(it, "cloudflared").canExecute() }
    }
}
