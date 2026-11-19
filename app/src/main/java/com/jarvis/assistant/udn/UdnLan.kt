package com.jarvis.assistant.udn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * LAN fast path. When both devices are on the same network, a parent sends the target a tiny "check now" nudge
 * straight over the LAN, so the command is picked up immediately instead of waiting for Realtime. The nudge carries
 * no command, no data and no authority: the target still fetches and verifies the command from the server over the
 * authenticated API. Control over the LAN without internet access is NOT implemented.
 */
object UdnLan {
    fun localIpv4(context: Context): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val net = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(net) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return null
        return cm.getLinkProperties(net)?.linkAddresses?.map { it.address }
            ?.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }

    private fun sameSubnet24(a: String, b: String) = a.substringBeforeLast('.') == b.substringBeforeLast('.')

    /** Fire-and-forget; silently does nothing when the target isn't on this subnet or doesn't answer. */
    suspend fun nudge(context: Context, host: String, port: Int) = withContext(Dispatchers.IO) {
        val mine = localIpv4(context) ?: return@withContext
        if (!sameSubnet24(mine, host) || port !in 1024..65535) return@withContext
        runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), 400)
                s.soTimeout = 400
                s.getOutputStream().apply { write("GET /udn/nudge HTTP/1.1\r\nHost: jarvis\r\nConnection: close\r\n\r\n".toByteArray()); flush() }
                s.getInputStream().read()
            }
        }
    }
}

class UdnLanListener(private val onNudge: () -> Unit) {
    private var server: ServerSocket? = null
    private var job: Job? = null
    @Volatile private var lastNudgeMs = 0L

    val port: Int? get() = server?.localPort

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        val ss = runCatching { ServerSocket(0) }.getOrNull() ?: return
        server = ss
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val client = try { ss.accept() } catch (e: Exception) { break }
                launch {
                    client.use { c ->
                        runCatching {
                            c.soTimeout = 1500
                            val line = c.getInputStream().bufferedReader().readLine().orEmpty()
                            val ok = line.startsWith("GET /udn/nudge")
                            val now = System.currentTimeMillis()
                            if (ok && now - lastNudgeMs >= 1_000) { lastNudgeMs = now; onNudge() }
                            c.getOutputStream().write((if (ok) "HTTP/1.1 204 No Content" else "HTTP/1.1 404 Not Found").plus("\r\nConnection: close\r\n\r\n").toByteArray())
                        }
                    }
                }
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        runCatching { server?.close() }; server = null
    }
}
