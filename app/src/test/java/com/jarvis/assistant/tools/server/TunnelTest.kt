package com.jarvis.assistant.tools.server

import com.google.gson.JsonParser
import com.jarvis.assistant.tools.*
import com.jarvis.assistant.tools.terminal.RunningCommand
import com.jarvis.assistant.tools.terminal.TerminalBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

class FakeCommand(private val emit: (String) -> Unit) : RunningCommand {
    @Volatile var alive = true
    val killed = AtomicBoolean(false)
    override val pid: Long? = null
    override val isAlive get() = alive
    override val exitCode: Int? get() = if (alive) null else if (killed.get()) -1 else 1
    override fun waitFor(timeoutMs: Long) = !alive
    override fun kill(graceMs: Long) { killed.set(true); alive = false }
    fun say(text: String) = emit(text)
    fun exit() { alive = false }
}

class FakeBackend(override val isRemote: Boolean = true, private val onLaunch: (FakeCommand) -> Unit = {}) : TerminalBackend {
    override val name = "Fake"
    override val shell = "/bin/sh"
    val commands = ArrayList<FakeCommand>()
    val scripts = ArrayList<String>()
    override fun environment(workspaceRoot: File) = emptyMap<String, String>()
    override fun launch(script: String, cwd: File, env: Map<String, String>, onOutput: (String) -> Unit): RunningCommand {
        scripts += script
        return FakeCommand(onOutput).also { commands += it; onLaunch(it) }
    }
}

class FakeProbe(vararg ports: Int) : PortProbe {
    val open = CopyOnWriteArraySet<Int>().apply { ports.forEach { add(it) } }
    override fun listeningHost(port: Int): String? = if (port in open) "127.0.0.1" else null
}

private const val LINK = "https://calm-river-test-demo.trycloudflare.com"
private const val UP = "[jarvis] STARTING\nINF Requesting new quick Tunnel on trycloudflare.com...\nINF |  $LINK  |\nINF Registered tunnel connection connIndex=0 location=bom01\n"

class TunnelTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val servers = ServerManager()
    private val dir = Files.createTempDirectory("jv-tunnel").toFile()
    private val probe = FakeProbe(3000)
    private var verifies = true
    @Volatile private var sameLocal: Boolean? = true
    private fun linkCheck() = if (verifies) LinkCheck(true, sameLocal) else LinkCheck(false)
    @Volatile private var now = 1_000_000L
    private val fast = TunnelManager.Config(monitorIntervalMs = 30, verifyFirstDelayMs = 5, verifyIntervalMs = 5, verifyAttempts = 3, lingerMs = 600_000, startupLimitMs = 600_000, retryDelayMs = 20, healthIntervalMs = 600_000)

    @Before fun noop() {}
    @After fun tearDown() { servers.stopAll(); scope.cancel() }

    private fun manager(backend: TerminalBackend, cfg: TunnelManager.Config = fast, available: () -> Boolean = { true }) =
        TunnelManager(backend, dir, servers, scope, probe, UrlVerifier { _, _ -> linkCheck() }, { now }, available, cfg)

    private fun await(what: String, ms: Long = 5000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond() && System.currentTimeMillis() < end) Thread.sleep(10)
        assertTrue("timed out waiting for: $what", cond())
    }
    private fun TunnelManager.first(): Tunnel = tunnels.value.first()

    // ---- parsing and policy ----

    @Test fun the_log_parser_finds_the_link_but_never_the_api_host() {
        assertEquals(CloudflaredLog.Event.Url(LINK), CloudflaredLog.parse("2026-11-22T06:28:52Z INF |  $LINK  |"))
        assertNull(CloudflaredLog.parse("failed to request quick Tunnel: Post \"https://api.trycloudflare.com/tunnel\": x".replace("failed to request", "note")))
        assertTrue(CloudflaredLog.parse("failed to request quick Tunnel: Post \"https://api.trycloudflare.com/tunnel\": dial tcp: lookup api.trycloudflare.com: no such host") is CloudflaredLog.Event.StartFailed)
        assertEquals(CloudflaredLog.Event.Registered, CloudflaredLog.parse("INF Registered tunnel connection connIndex=1 protocol=quic"))
        assertEquals(CloudflaredLog.Event.Retrying, CloudflaredLog.parse("ERR Retrying connection in up to 2s"))
        assertEquals(CloudflaredLog.Event.ServerGone, CloudflaredLog.parse("[jarvis] LOCAL_SERVER_STOPPED"))
        assertEquals(CloudflaredLog.Event.Url(LINK), CloudflaredLog.parse("\u001B[32m$LINK\u001B[0m"))
    }

    @Test fun rate_limits_and_network_failures_get_plain_messages() {
        val rate = (CloudflaredLog.parse("failed to request quick Tunnel: 429 Too Many Requests") as CloudflaredLog.Event.StartFailed).message
        assertTrue(rate, rate.contains("limiting"))
        val net = (CloudflaredLog.parse("failed to request quick Tunnel: dial tcp: i/o timeout") as CloudflaredLog.Event.StartFailed).message
        assertTrue(net, net.contains("internet"))
    }

    @Test fun sensitive_and_invalid_ports_are_refused() {
        for (p in listOf(0, -1, 70000, 22, 80, 1023, 5555, 6379, 5432, 8022, 20241)) assertNotNull("$p", PortPolicy.check(p))
        for (p in listOf(1024, 3000, 5173, 8080, 65535)) assertNull("$p", PortPolicy.check(p))
    }

    @Test fun the_script_is_built_only_from_safe_values() {
        assertThrows(IllegalArgumentException::class.java) { TunnelScript.build(3000, "evil; rm -rf /", 3600, true) }
        assertThrows(IllegalArgumentException::class.java) { TunnelScript.build(0, "127.0.0.1", 3600, true) }
        assertThrows(IllegalArgumentException::class.java) { TunnelScript.build(3000, "127.0.0.1", 1, true) }
        val s = TunnelScript.build(3000, "127.0.0.1", 3600, true)
        assertTrue(s.contains("--http-host-header 127.0.0.1")); assertTrue(s.contains("http://127.0.0.1:\$PORT")); assertTrue(s.contains("pkg install"))
        assertFalse(s.contains("@")); assertFalse(s.contains("§"))
        assertTrue(TunnelScript.build(3000, "::1", 3600, false).contains("http://[::1]:\$PORT"))
        assertFalse(TunnelScript.build(3000, "127.0.0.1", 3600, false).contains("pkg install"))
    }

    @Test fun the_link_does_not_depend_on_the_pipe_back_to_the_app() {
        // cloudflared writes to a log file and SIGPIPE is ignored, so closing the app can not end the link.
        val s = TunnelScript.build(3000, "127.0.0.1", 1800, true)
        assertTrue(s.contains("trap '' PIPE"))
        assertTrue(s.contains(">\"\$LOG\" 2>&1"))
        assertEquals(30, TunnelManager.Config().defaultMinutes)
    }

    // ---- manager ----

    @Test fun a_link_is_reported_only_after_cloudflare_accepts_it_and_it_answers() {
        val b = FakeBackend(); val m = manager(b)
        val r = m.start(3000, "my-site", 30) as StartResult.Started
        assertNull(r.tunnel.publicUrl)
        b.commands[0].say(UP)
        await("live") { m.first().state is TunnelState.Live }
        val live = m.first().state as TunnelState.Live
        assertEquals(LINK, live.url); assertTrue(live.verified)
        assertEquals(LINK, m.first().publicUrl)
        assertEquals("my-site", m.first().label)
        assertEquals(30 * 60_000L, m.first().expiresAt - m.first().startedAt)
        m.close()
    }

    @Test fun a_link_that_cannot_be_confirmed_is_live_but_says_so() {
        verifies = false
        val b = FakeBackend(); val m = manager(b)
        m.start(3000); b.commands[0].say(UP)
        await("live") { m.first().state is TunnelState.Live }
        assertFalse((m.first().state as TunnelState.Live).verified)
        m.close()
    }

    @Test fun asking_twice_gives_the_same_link_and_starts_one_process() {
        val b = FakeBackend(); val m = manager(b)
        assertTrue(m.start(3000) is StartResult.Started)
        assertTrue(m.start(3000) is StartResult.AlreadyActive)
        assertEquals(1, b.commands.size); assertEquals(1, m.active().size)
        m.close()
    }

    @Test fun bad_requests_start_nothing() {
        val b = FakeBackend(); val m = manager(b)
        assertTrue(m.start(22) is StartResult.Rejected)
        assertTrue((m.start(4000) as StartResult.Rejected).message.contains("Nothing is listening"))
        val local = manager(FakeBackend(isRemote = false), available = { false })
        assertEquals(TunnelScript.NOT_AVAILABLE, (local.start(3000) as StartResult.Rejected).message)
        assertTrue(b.commands.isEmpty()); assertTrue(m.tunnels.value.isEmpty())
    }

    @Test fun the_open_link_limit_is_enforced() {
        // The product default is higher (Config.maxTunnels); the rule under test is "never more than the limit", so set a small one.
        val b = FakeBackend(); val m = manager(b, fast.copy(maxTunnels = 3))
        for (p in listOf(3000, 3001, 3002)) { probe.open += p; assertTrue(m.start(p) is StartResult.Started) }
        probe.open += 3003
        val over = m.start(3003)
        assertTrue(over is StartResult.Rejected)
        assertTrue((over as StartResult.Rejected).message.contains("3 public links"))
        m.close()
    }

    @Test fun stopping_ends_the_process_and_says_who_did_it() {
        val b = FakeBackend(); val m = manager(b)
        m.start(3000); b.commands[0].say(UP); await("live") { m.first().state is TunnelState.Live }
        assertTrue(m.stop(3000))
        assertEquals(StopReason.USER, (m.first().state as TunnelState.Stopped).reason)
        await("process killed") { b.commands[0].killed.get() }
        assertFalse(m.stop(3000))
        assertNull(m.forPort(3000))
        assertTrue("a new link can be made afterwards", m.start(3000) is StartResult.Started)
        m.close()
    }

    @Test fun stopping_a_preview_closes_its_link() {
        File(dir, "site").mkdirs(); File(dir, "site/index.html").writeText("<h1>hi</h1>")
        val s = servers.start("site", File(dir, "site"))
        val b = FakeBackend(); val realProbe = LoopbackProbe()
        val m = TunnelManager(b, dir, servers, scope, realProbe, UrlVerifier { _, _ -> LinkCheck(true, true) }, { now }, { true }, fast)
        assertTrue(m.start(s.boundPort, "site") is StartResult.Started)
        servers.stop("site")
        await("closed with the server") { (m.first().state as? TunnelState.Stopped)?.reason == StopReason.SERVER_STOPPED }
        await("process killed") { b.commands[0].killed.get() }
        m.close()
    }

    @Test fun a_server_that_stops_answering_closes_its_link() {
        val b = FakeBackend(); val m = manager(b)
        m.start(3000); b.commands[0].say(UP); await("live") { m.first().state is TunnelState.Live }
        probe.open.remove(3000)
        await("closed") { (m.first().state as? TunnelState.Stopped)?.reason == StopReason.SERVER_STOPPED }
        assertTrue(b.commands[0].killed.get())
        m.close()
    }

    @Test fun a_quick_server_restart_does_not_close_the_link() {
        val b = FakeBackend(); val m = manager(b, fast.copy(missesBeforeStop = 20))
        m.start(3000); b.commands[0].say(UP); await("live") { m.first().state is TunnelState.Live }
        probe.open.remove(3000); Thread.sleep(120); probe.open.add(3000); Thread.sleep(200)
        assertTrue(m.first().state is TunnelState.Live)
        m.close()
    }

    @Test fun a_link_expires_at_its_time_limit() {
        val b = FakeBackend(); val m = manager(b)
        m.start(3000, minutes = 5); b.commands[0].say(UP); await("live") { m.first().state is TunnelState.Live }
        now += 6 * 60_000L
        await("expired") { (m.first().state as? TunnelState.Stopped)?.reason == StopReason.EXPIRED }
        assertTrue(b.commands[0].killed.get())
        m.close()
    }

    @Test fun the_same_link_returns_after_a_network_drop() {
        val b = FakeBackend(); val m = manager(b)
        m.start(3000); b.commands[0].say(UP); await("live") { m.first().state is TunnelState.Live }
        b.commands[0].say("ERR Retrying connection in up to 4s connIndex=0\n")
        await("reconnecting") { m.first().state is TunnelState.Reconnecting }
        assertEquals("the link stays known while reconnecting", LINK, m.first().publicUrl)
        b.commands[0].say("INF Registered tunnel connection connIndex=0\n")
        await("live again") { (m.first().state as? TunnelState.Live)?.url == LINK }
        m.close()
    }

    @Test fun a_connection_that_never_comes_back_closes_the_link() {
        val b = FakeBackend(); val m = manager(b, fast.copy(reconnectLimitMs = 100))
        m.start(3000); b.commands[0].say(UP); await("live") { m.first().state is TunnelState.Live }
        b.commands[0].say("ERR Retrying connection in up to 4s\n")
        now += 1_000
        await("gave up") { (m.first().state as? TunnelState.Stopped)?.reason == StopReason.NETWORK_LOST }
        m.close()
    }

    @Test fun failures_are_reported_in_plain_words_and_leave_nothing_running() {
        val b = FakeBackend(); val m = manager(b, fast.copy(maxAttempts = 1))
        m.start(3000)
        b.commands[0].say("[jarvis] STARTING\nfailed to request quick Tunnel: dial tcp: no such host\n")
        await("failed") { m.first().state is TunnelState.Failed }
        assertTrue((m.first().state as TunnelState.Failed).message.contains("internet"))
        await("killed") { b.commands[0].killed.get() }
        assertTrue(m.active().isEmpty())
        m.close()
    }

    @Test fun a_program_that_dies_by_itself_is_explained() {
        val b = FakeBackend(); val m = manager(b, fast.copy(maxAttempts = 1))
        m.start(3000); b.commands[0].say("[jarvis] STARTING\nsomething odd happened\n"); b.commands[0].exit()
        await("failed") { m.first().state is TunnelState.Failed }
        assertTrue((m.first().state as TunnelState.Failed).message.contains("something odd happened"))
        m.close()
    }

    @Test fun a_missing_install_is_explained() {
        val b = FakeBackend(); val m = manager(b)
        m.start(3000); b.commands[0].say("[jarvis] INSTALLING\n")
        await("installing") { (m.first().state as? TunnelState.Preparing)?.detail?.contains("Installing") == true }
        b.commands[0].say("[jarvis] ERR_INSTALL\n")
        await("failed") { (m.first().state as? TunnelState.Failed)?.message?.contains("pkg install cloudflared") == true }
        m.close()
    }

    @Test fun a_launch_that_throws_becomes_a_failure() {
        val m = manager(object : TerminalBackend {
            override val name = "x"; override val shell = "/bin/sh"; override val isRemote = true
            override fun environment(workspaceRoot: File) = emptyMap<String, String>()
            override fun launch(script: String, cwd: File, env: Map<String, String>, onOutput: (String) -> Unit): RunningCommand = throw IllegalStateException("Termux is not installed.")
        })
        m.start(3000)
        assertEquals("Termux is not installed.", (m.first().state as TunnelState.Failed).message)
        assertNull(m.forPort(3000))
    }

    @Test fun detection_lists_previews_and_common_ports_and_marks_shared_ones() {
        probe.open += 5173
        val b = FakeBackend(); val m = manager(b)
        assertEquals(listOf(3000, 5173), m.detect().map { it.port }.sorted())
        m.start(3000)
        assertTrue(m.detect().first { it.port == 3000 }.shared)
        m.close()
    }

    // ---- reliability ----

    private val LINK2 = "https://other-river-test-demo.trycloudflare.com"
    private fun up(link: String) = "[jarvis] STARTING\nINF |  $link  |\nINF Registered tunnel connection connIndex=0\n"

    @Test fun a_failed_start_is_retried_over_http2_and_then_works() {
        val b = FakeBackend(); val m = manager(b)
        m.start(3000)
        b.commands[0].say("[jarvis] STARTING\nfailed to request quick Tunnel: dial tcp: i/o timeout\n")
        await("second attempt") { b.commands.size == 2 }
        assertFalse(b.scripts[0].contains("--protocol http2")); assertTrue(b.scripts[1].contains("--protocol http2"))
        assertTrue(b.commands[0].killed.get())
        assertTrue(m.first().state is TunnelState.Preparing)
        b.commands[1].say(up(LINK))
        await("live") { m.first().state is TunnelState.Live }
        assertEquals(LINK, m.first().publicUrl)
        m.close()
    }

    @Test fun when_every_attempt_fails_the_link_fails_with_the_reason() {
        val b = FakeBackend(); val m = manager(b, fast.copy(maxAttempts = 2))
        m.start(3000); b.commands[0].say("failed to request quick Tunnel: 429 Too Many Requests\n")
        await("retry") { b.commands.size == 2 }
        b.commands[1].say("failed to request quick Tunnel: 429 Too Many Requests\n")
        await("failed") { m.first().state is TunnelState.Failed }
        assertTrue((m.first().state as TunnelState.Failed).message.contains("limiting"))
        assertEquals(2, b.commands.size)
        m.close()
    }

    @Test fun output_from_a_killed_attempt_cannot_change_the_new_one() {
        val b = FakeBackend(); val m = manager(b)
        m.start(3000); b.commands[0].say("failed to request quick Tunnel: dial tcp: i/o timeout\n")
        await("second attempt") { b.commands.size == 2 }
        b.commands[0].say(up(LINK2))
        Thread.sleep(150)
        assertTrue(m.first().state is TunnelState.Preparing)
        m.close()
    }

    @Test fun a_live_link_that_stops_answering_is_rebuilt_with_a_new_address() {
        val b = FakeBackend(); val m = manager(b, fast.copy(healthIntervalMs = 30, healthFailsBeforeHeal = 2))
        m.start(3000); b.commands[0].say(up(LINK)); await("live") { (m.first().state as? TunnelState.Live)?.verified == true }
        verifies = false
        await("rebuilding") { b.commands.size == 2 }
        assertTrue(b.commands[0].killed.get())
        assertTrue(m.first().note!!.contains("new one"))
        verifies = true
        b.commands[1].say(up(LINK2))
        await("live again") { (m.first().state as? TunnelState.Live)?.url == LINK2 }
        m.close()
    }

    @Test fun a_link_that_was_never_seen_working_from_this_phone_is_not_rebuilt() {
        verifies = false
        val b = FakeBackend(); val m = manager(b, fast.copy(healthIntervalMs = 20, healthFailsBeforeHeal = 1))
        m.start(3000); b.commands[0].say(up(LINK)); await("live") { m.first().state is TunnelState.Live }
        Thread.sleep(300)
        assertEquals(1, b.commands.size); assertTrue(m.first().state is TunnelState.Live)
        m.close()
    }

    @Test fun healing_is_limited_and_then_reports_failure() {
        val b = FakeBackend(); val m = manager(b, fast.copy(healthIntervalMs = 20, healthFailsBeforeHeal = 1, maxHeals = 1))
        m.start(3000); b.commands[0].say(up(LINK)); await("live") { (m.first().state as? TunnelState.Live)?.verified == true }
        verifies = false
        await("rebuild") { b.commands.size == 2 }
        verifies = true; b.commands[1].say(up(LINK2)); await("live again") { (m.first().state as? TunnelState.Live)?.verified == true }
        verifies = false
        await("gave up") { m.first().state is TunnelState.Failed }
        assertEquals(2, b.commands.size)
        m.close()
    }

    @Test fun the_link_says_whether_viewers_get_the_same_page() {
        val b = FakeBackend(onLaunch = { c -> c.say(UP) }); val m = manager(b)
        val same = runBlocking { tools(m)["port_forward_start"]!!.run(args("""{"port":3000}"""), ctx) }
        assertTrue(same.text, same.text.contains("exactly the same page"))
        assertEquals(true, (m.first().state as TunnelState.Live).matchesLocal)
        m.close()
        sameLocal = false
        val m2 = manager(FakeBackend(onLaunch = { c -> c.say(UP) }))
        val diff = runBlocking { tools(m2)["port_forward_start"]!!.run(args("""{"port":3000}"""), ctx) }
        assertTrue(diff.text, diff.text.contains("differs"))
        m2.close()
    }

    // ---- tools ----

    private val ctx = object : ToolContext { override val workspace = Workspace(dir) }
    private fun args(json: String) = JsonParser.parseString(json).asJsonObject
    private fun tools(m: TunnelManager) = TunnelTools(m, servers).all().associateBy { it.spec.name }

    @Test fun starting_a_link_always_asks_and_says_exactly_what_will_be_public() {
        val m = manager(FakeBackend()); val t = tools(m)["port_forward_start"]!!
        assertTrue(t.isDestructive(args("{}"), ctx)); assertEquals(Risk.NETWORK, t.spec.risk)
        val text = t.describe(args("""{"port":3000,"minutes":30}"""))
        assertTrue(text, text.contains("PUBLIC INTERNET ACCESS") && text.contains("port 3000") && text.contains("30 min"))
        assertTrue(t.describe(args("""{"port":22}""")).contains("Nothing will be shared"))
        assertTrue(tools(m).filterKeys { it != "port_forward_start" }.values.none { it.isDestructive(args("{}"), ctx) })
        assertTrue(m.tunnels.value.isEmpty())
    }

    @Test fun the_start_tool_returns_the_link_when_it_is_ready() {
        val b = FakeBackend(onLaunch = { c -> c.say(UP) }); val m = manager(b)
        val r = runBlocking { tools(m)["port_forward_start"]!!.run(args("""{"port":3000}"""), ctx) }
        assertTrue(r.text, r is ToolResult.Ok && r.text.contains(LINK) && r.text.contains("Checked from outside"))
        val again = runBlocking { tools(m)["port_forward_start"]!!.run(args("""{"port":3000}"""), ctx) }
        assertTrue(again.text, again.text.contains("already has a public link"))
        assertEquals(1, b.commands.size)
        assertTrue(runBlocking { tools(m)["port_forward_status"]!!.run(args("{}"), ctx) }.text.contains(LINK))
        assertTrue(runBlocking { tools(m)["port_forward_stop"]!!.run(args("""{"all":true}"""), ctx) }.text.contains("Closed 1"))
        m.close()
    }

    @Test fun without_a_port_one_detected_server_is_used_and_several_are_never_guessed() {
        val b = FakeBackend(onLaunch = { c -> c.say(UP) }); val m = manager(b)
        assertTrue(runBlocking { tools(m)["port_forward_start"]!!.run(args("{}"), ctx) }.isOk)
        m.close()
        probe.open += 5173
        val m2 = manager(FakeBackend())
        val r = runBlocking { tools(m2)["port_forward_start"]!!.run(args("{}"), ctx) }
        assertTrue(r.text, r is ToolResult.Error && r.text.contains("Several"))
        assertTrue(m2.tunnels.value.isEmpty())
        probe.open.clear()
        assertTrue(runBlocking { tools(m2)["port_forward_start"]!!.run(args("{}"), ctx) }.text.contains("No local server"))
    }

    @Test fun a_cancelled_start_leaves_no_link_open() {
        val b = FakeBackend(); val m = manager(b)
        val cancelled = object : ToolContext { override val workspace = Workspace(dir); override val cancelled = true }
        val r = runBlocking { tools(m)["port_forward_start"]!!.run(args("""{"port":3000}"""), cancelled) }
        assertTrue(r.text, r.text.contains("Cancelled"))
        assertTrue(m.active().isEmpty()); await("killed") { b.commands[0].killed.get() }
    }
}
