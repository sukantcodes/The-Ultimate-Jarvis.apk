package com.jarvis.assistant.tools.server

import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/*
 * Temporary public links for servers that run on this phone ("forward a port").
 *
 * Provider choice: Cloudflare Quick Tunnels (`cloudflared tunnel --url ...`). It needs no account or token, gives an
 * HTTPS address on trycloudflare.com, runs as one outbound connection (so nothing on the phone listens to the
 * internet), and cloudflared is an official Termux package, so `pkg install cloudflared` is a signature-checked install
 * that stays cached on the phone and is never downloaded again. Android 10+ refuses to run binaries from an app's own
 * folder, so a copy downloaded into this app could not run anyway; Termux is where it can. Alternatives considered:
 * ngrok (needs an account and token), SSH-based relays such as localhost.run or serveo (a third-party service that
 * stops answering without warning, no stable client on Termux by default) and bore (plain TCP, no HTTPS).
 *
 * Cloudflare gives no uptime guarantee for quick tunnels; the address is random and changes every time.
 */

/** Why a link ended. */
enum class StopReason(val text: String) {
    USER("You stopped it."),
    SERVER_STOPPED("The local server stopped."),
    EXPIRED("The link reached its time limit."),
    NETWORK_LOST("The connection to Cloudflare could not be restored."),
    APP_CLOSED("JARVIS closed.")
}

sealed class TunnelState {
    /** Starting the tunnel program (or installing it the first time). */
    data class Preparing(val detail: String) : TunnelState()
    /** The tunnel program is up; the link is not confirmed yet. [url] is known once Cloudflare hands it out. */
    data class Connecting(val url: String?) : TunnelState()
    /** Cloudflare accepted the tunnel. [verified] is true when this phone also fetched the link from the internet. */
    data class Live(val url: String, val verified: Boolean) : TunnelState()
    /** The connection to Cloudflare dropped; the tunnel program is retrying and the same link comes back when it succeeds. */
    data class Reconnecting(val url: String) : TunnelState()
    data class Stopped(val reason: StopReason, val url: String?) : TunnelState()
    data class Failed(val message: String) : TunnelState()
}

data class Tunnel(
    val id: String,
    val port: Int,
    val label: String,
    val startedAt: Long,
    val expiresAt: Long,
    val state: TunnelState,
    /** A problem worth showing next to a running link, for example "the local server is not answering". */
    val note: String? = null
) {
    val isActive: Boolean get() = state !is TunnelState.Stopped && state !is TunnelState.Failed
    /** The address that can be handed out: only a link Cloudflare has accepted (live, or briefly reconnecting). */
    val publicUrl: String? get() = when (val s = state) {
        is TunnelState.Live -> s.url
        is TunnelState.Reconnecting -> s.url
        else -> null
    }
}

/** Which ports may be put on the internet. A port is never exposed by guessing: it must be named or detected, and confirmed. */
object PortPolicy {
    private val BLOCKED: Map<Int, String> = buildMap {
        put(1433, "SQL Server"); put(1521, "Oracle Database"); put(2375, "Docker"); put(2376, "Docker")
        put(3306, "MySQL / MariaDB"); put(3389, "Remote Desktop"); put(5037, "Android Debug Bridge"); put(5555, "Android Debug Bridge")
        put(5432, "PostgreSQL"); put(5900, "VNC"); put(5901, "VNC"); put(5984, "CouchDB"); put(6379, "Redis")
        put(8022, "the Termux SSH server"); put(9200, "Elasticsearch"); put(9300, "Elasticsearch"); put(11211, "Memcached"); put(27017, "MongoDB")
        for (p in 20241..20245) put(p, "the tunnel program's own status page")
    }

    /** Null when [port] may be forwarded, otherwise the reason it may not. */
    fun check(port: Int): String? = when {
        port !in 1..65535 -> "$port is not a port number. Ports go from 1 to 65535."
        port < 1024 -> "Port $port is a reserved system port. Servers started by JARVIS or Termux use ports from 1024 up."
        BLOCKED.containsKey(port) -> "Port $port is used by ${BLOCKED[port]}. JARVIS will not put that on the internet."
        else -> null
    }

    /** Ports that development servers and static servers commonly use. Detection only ever looks at these, plus JARVIS previews. */
    val COMMON: List<Int> = listOf(
        1313, 1234, 2368, 3000, 3001, 3002, 3003, 3030, 3333, 4000, 4040, 4173, 4200, 4321, 5000, 5001, 5173, 5174, 5500,
        6006, 7000, 7860, 8000, 8001, 8008, 8080, 8081, 8082, 8088, 8100, 8787, 8888, 9000, 9090
    )
}

/** Finds out whether something is listening on this phone's loopback address, and on which one. */
interface PortProbe {
    /** "127.0.0.1" or "::1" when something accepts connections on [port], otherwise null. */
    fun listeningHost(port: Int): String?
    fun isListening(port: Int): Boolean = listeningHost(port) != null
}

class LoopbackProbe(private val timeoutMs: Int = 250) : PortProbe {
    override fun listeningHost(port: Int): String? {
        for (host in listOf("127.0.0.1", "::1")) {
            val ok = try {
                Socket().use { it.connect(InetSocketAddress(InetAddress.getByName(host), port), timeoutMs); true }
            } catch (_: Exception) { false }
            if (ok) return host
        }
        return null
    }
}

/** Fetches a public link the way anyone on the internet would, to find out whether it really answers. */
fun interface UrlVerifier {
    fun isReachable(url: String): Boolean
}

class HttpUrlVerifier : UrlVerifier {
    /** True when something answered with a real page or an application error (below 500). Cloudflare's own "not ready" pages are 5xx. */
    override fun isReachable(url: String): Boolean {
        val c = try { URL(url).openConnection() as HttpURLConnection } catch (_: IOException) { return false }
        return try {
            c.connectTimeout = 4_000; c.readTimeout = 6_000; c.instanceFollowRedirects = false; c.requestMethod = "GET"
            c.setRequestProperty("User-Agent", "JARVIS-link-check")
            c.responseCode in 100..499
        } catch (_: IOException) { false } finally { c.disconnect() }
    }
}

/** What cloudflared (and our own runner script) say, reduced to the events the manager acts on. */
object CloudflaredLog {
    sealed class Event {
        data class Url(val url: String) : Event()
        data object Registered : Event()
        data object Retrying : Event()
        data object OriginDown : Event()
        data class StartFailed(val message: String) : Event()
        data object Installing : Event()
        data object Starting : Event()
        data object InstallFailed : Event()
        data object NotInstalled : Event()
        data object ServerGone : Event()
        data object Expired : Event()
        data object Closed : Event()
    }

    private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")
    // A quick-tunnel address is two or more words joined by hyphens, so the API host (api.trycloudflare.com) never matches.
    private val QUICK_URL = Regex("https://([a-z0-9]+(?:-[a-z0-9]+)+)\\.trycloudflare\\.com")

    fun clean(line: String): String = line.replace(ANSI, "").trim()

    fun parse(rawLine: String): Event? {
        val line = clean(rawLine)
        if (line.isEmpty()) return null
        if (line.startsWith("[jarvis]")) return when (line.removePrefix("[jarvis]").trim()) {
            "INSTALLING" -> Event.Installing
            "STARTING" -> Event.Starting
            "ERR_INSTALL" -> Event.InstallFailed
            "ERR_MISSING" -> Event.NotInstalled
            "LOCAL_SERVER_STOPPED" -> Event.ServerGone
            "EXPIRED" -> Event.Expired
            "TUNNEL_CLOSED" -> Event.Closed
            else -> null
        }
        if (line.contains("failed to request quick Tunnel", ignoreCase = true)) return Event.StartFailed(friendlyStartFailure(line))
        QUICK_URL.find(line)?.let { return Event.Url(it.value) }
        if (line.contains("Registered tunnel connection")) return Event.Registered
        if (line.contains("Unable to reach the origin service")) return Event.OriginDown
        if (RETRY_MARKERS.any { line.contains(it, ignoreCase = true) }) return Event.Retrying
        return null
    }

    private val RETRY_MARKERS = listOf(
        "Retrying connection in up to", "Unable to establish connection", "failed to dial to edge", "Connection terminated", "Lost connection with the edge"
    )

    internal fun friendlyStartFailure(line: String): String = when {
        line.contains("429") || line.contains("Too Many Requests", true) || line.contains("1015") ->
            "Cloudflare is limiting how many quick links can be made right now. Wait a minute and try again."
        line.contains("no such host", true) || line.contains("dial tcp", true) || line.contains("timeout", true) || line.contains("network is unreachable", true) ->
            "Could not reach Cloudflare. Check that the phone has internet access, then try again."
        else -> "Cloudflare could not create the link: " + line.substringAfter("failed to request quick Tunnel", line).trim(' ', ':').take(160)
    }
}

/** The program that runs inside Termux (or the app shell) for one tunnel. Only integers and fixed text go into it. */
object TunnelScript {
    const val NOT_AVAILABLE = "Public links need Termux (a Linux shell with cloudflared). Open the Terminal tab and type: jpkg setup"

    /**
     * Runs cloudflared against http://[host]:[port] and watches over it, so the link can not outlive its reason to exist
     * even if JARVIS itself is killed: it ends when the time is up, or when the local port stops answering for three
     * checks in a row (about nine seconds, long enough for a server to restart). cloudflared rewrites the Host header to
     * 127.0.0.1 so JARVIS previews (which refuse any other name) and dev servers with host checks keep working.
     * [install] is true inside Termux, where a missing cloudflared is installed once with the package manager.
     */
    fun build(port: Int, host: String, ttlSeconds: Int, install: Boolean): String {
        require(port in 1..65535) { "Bad port." }
        require(host == "127.0.0.1" || host == "::1") { "Bad host." }
        require(ttlSeconds in 30..86_400) { "Bad time limit." }
        val origin = if (host == "::1") "[::1]" else host
        val missing = if (install) INSTALL else "  echo '[jarvis] ERR_MISSING'\n  exit 72"
        return TEMPLATE.replace("§", "$").replace("@PORT@", port.toString()).replace("@HOST@", host)
            .replace("@ORIGIN@", origin).replace("@TTL@", ttlSeconds.toString()).replace("@MISSING@", missing)
    }

    private val INSTALL = """
        |  echo '[jarvis] INSTALLING'
        |  pkg install -y -o Dpkg::Options::=--force-confnew cloudflared >/dev/null 2>&1 \
        |    || { pkg update -y -o Dpkg::Options::=--force-confnew >/dev/null 2>&1; pkg install -y -o Dpkg::Options::=--force-confnew cloudflared >/dev/null 2>&1; }
        |  command -v cloudflared >/dev/null 2>&1 || { echo '[jarvis] ERR_INSTALL'; exit 71; }
    """.trimMargin()

    private val TEMPLATE = """
        export DEBIAN_FRONTEND=noninteractive
        PORT=@PORT@
        TTL=@TTL@
        RUN="§HOME/.jarvis-run"
        PIDF="§RUN/tunnel-§PORT.pid"
        mkdir -p "§RUN" 2>/dev/null
        if [ -f "§PIDF" ]; then
          old="§(cat "§PIDF" 2>/dev/null)"
          case "§old" in
            ''|*[!0-9]*) ;;
            *) if tr '\0' ' ' < "/proc/§old/cmdline" 2>/dev/null | grep -q cloudflared; then kill "§old" 2>/dev/null; sleep 1; kill -9 "§old" 2>/dev/null; fi ;;
          esac
          rm -f "§PIDF"
        fi
        if ! command -v cloudflared >/dev/null 2>&1; then
        @MISSING@
        fi
        echo '[jarvis] STARTING'
        cloudflared tunnel --no-autoupdate --http-host-header 127.0.0.1 --url "http://@ORIGIN@:§PORT" 2>&1 &
        CF=§!
        echo "§CF" > "§PIDF"
        END=§(( §(date +%s) + TTL ))
        MISS=0
        REASON=""
        while kill -0 "§CF" 2>/dev/null; do
          sleep 3
          if [ "§(date +%s)" -ge "§END" ]; then REASON=EXPIRED; break; fi
          if [ -n "§BASH_VERSION" ]; then
            if (exec 3<>/dev/tcp/@HOST@/§PORT) 2>/dev/null; then MISS=0; else MISS=§((MISS+1)); fi
            if [ "§MISS" -ge 3 ]; then REASON=LOCAL_SERVER_STOPPED; break; fi
          fi
        done
        if kill -0 "§CF" 2>/dev/null; then
          kill "§CF" 2>/dev/null; sleep 1; kill -9 "§CF" 2>/dev/null
          wait "§CF" 2>/dev/null
          RC=0
        else
          wait "§CF" 2>/dev/null; RC=§?
        fi
        rm -f "§PIDF"
        [ -n "§REASON" ] && echo "[jarvis] §REASON"
        echo '[jarvis] TUNNEL_CLOSED'
        exit §RC
    """.trimIndent() + "\n"
}
