package com.jarvis.assistant.udn

import android.content.Context
import android.util.Log
import com.jarvis.assistant.auth.AuthManager
import com.jarvis.assistant.auth.AuthResult
import com.jarvis.assistant.auth.SupabaseConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import kotlin.math.min
import kotlin.random.Random

/**
 * Supabase Realtime subscription to udn_wakeups. A wake-up row carries only ids (never command contents); on
 * receiving one the manager polls the authenticated API. If the socket is down, polling continues on a timer, so
 * Realtime is a latency optimisation and not a single point of failure. Reconnects with exponential backoff and
 * proactively every 45 minutes so the access token stays fresh.
 */
class UdnRealtime(
    private val context: Context,
    private val onWake: (kind: String, targetDeviceId: String?) -> Unit,
    private val onConnectionChanged: (Boolean) -> Unit
) {
    private var job: Job? = null
    @Volatile private var socket: WebSocket? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch { loop() }
    }

    fun stop() {
        job?.cancel(); job = null
        socket?.close(1000, null); socket = null
        onConnectionChanged(false)
    }

    /** Called when the network changes; dropping the socket makes the loop reconnect immediately. */
    fun reconnectNow() { socket?.cancel() }

    private suspend fun loop() {
        var failures = 0
        while (currentCoroutineContext().isActive) {
            val token = (AuthManager.validAccessToken() as? AuthResult.Success)?.value
            if (token == null || !SupabaseConfig.isConfigured) { delay(15_000); continue }
            val closed = CompletableDeferred<Unit>()
            var joined = false
            val url = SupabaseConfig.url.replaceFirst("https://", "wss://") + "/realtime/v1/websocket?apikey=" + SupabaseConfig.publishableKey + "&vsn=1.0.0"
            val ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val join = JSONObject().put("topic", TOPIC).put("event", "phx_join").put("ref", "1").put("payload", JSONObject()
                        .put("access_token", token)
                        .put("config", JSONObject()
                            .put("broadcast", JSONObject().put("ack", false).put("self", false))
                            .put("presence", JSONObject().put("key", ""))
                            .put("postgres_changes", org.json.JSONArray().put(JSONObject().put("event", "INSERT").put("schema", "public").put("table", "udn_wakeups")))))
                    webSocket.send(join.toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
                    when (msg.optString("event")) {
                        "phx_reply" -> if (msg.optString("ref") == "1") {
                            val ok = msg.optJSONObject("payload")?.optString("status") == "ok"
                            if (ok) { joined = true; onConnectionChanged(true) } else webSocket.cancel()
                        }
                        "postgres_changes" -> {
                            val rec = msg.optJSONObject("payload")?.optJSONObject("data")?.optJSONObject("record") ?: return
                            onWake(rec.optString("kind"), rec.optStringOrNull("target_device_id"))
                        }
                        "phx_error", "phx_close" -> webSocket.cancel()
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.complete(Unit) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "Realtime socket dropped: ${t.javaClass.simpleName}")
                    closed.complete(Unit)
                }
            })
            socket = ws
            kotlinx.coroutines.coroutineScope {
                val hb = launch {
                    var n = 2
                    while (true) { delay(25_000); ws.send(JSONObject().put("topic", "phoenix").put("event", "heartbeat").put("payload", JSONObject()).put("ref", (n++).toString()).toString()) }
                }
                withTimeoutOrNull(45 * 60_000L) { closed.await() }
                hb.cancel()
            }
            ws.cancel(); socket = null
            onConnectionChanged(false)
            failures = if (joined) 0 else failures + 1
            val backoff = min(60_000L, 2_000L * (1L shl min(failures, 5))) + Random.nextLong(0, 1_500)
            delay(if (joined) 1_000L else backoff)
        }
    }

    companion object {
        private const val TAG = "UdnRealtime"
        private const val TOPIC = "realtime:udn-wakeups"
        private val client: OkHttpClient by lazy { OkHttpClient.Builder().readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS).build() }
    }
}
