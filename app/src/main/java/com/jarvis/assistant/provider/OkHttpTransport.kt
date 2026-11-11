package com.jarvis.assistant.provider

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume

/**
 * The only class that touches the network for custom providers. Redirects are never followed (so a credential is
 * never replayed to another host), and exception messages never contain the URL, headers, or body of a request.
 */
class OkHttpTransport(base: OkHttpClient = OkHttpClient()) : HttpTransport {

    private val client: OkHttpClient = base.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(true)
        .build()

    private fun build(spec: HttpRequestSpec): Request {
        val builder = try {
            Request.Builder().url(spec.url)
        } catch (e: IllegalArgumentException) {
            throw TransportException(TransportFailure.INVALID_URL, "The address is not a valid URL.")
        }
        spec.headers.forEach { (k, v) ->
            try { builder.header(k, v) } catch (e: IllegalArgumentException) {
                throw TransportException(TransportFailure.INVALID_URL, "A header name or value is not valid.")
            }
        }
        val body: RequestBody? = when (val b = spec.body) {
            null -> if (spec.method.equals("POST", true) || spec.method.equals("PUT", true)) ByteArray(0).toRequestBody(null) else null
            is HttpBody.Text -> b.content.toRequestBody(b.contentType.toMediaTypeOrNull())
            is HttpBody.Multipart -> {
                val mb = MultipartBody.Builder().setType(MultipartBody.FORM)
                for (p in b.parts) {
                    if (p.bytes != null) {
                        mb.addFormDataPart(p.name, p.filename ?: "file", p.bytes.toRequestBody(p.contentType?.toMediaTypeOrNull()))
                    } else {
                        mb.addFormDataPart(p.name, p.value.orEmpty())
                    }
                }
                mb.build()
            }
        }
        return builder.method(spec.method.uppercase(), body).build()
    }

    private fun clientFor(spec: HttpRequestSpec): OkHttpClient = client.newBuilder()
        .callTimeout(maxOf(spec.timeoutMs, 120_000L), TimeUnit.MILLISECONDS)
        .connectTimeout(30_000L, TimeUnit.MILLISECONDS)
        .readTimeout(maxOf(spec.timeoutMs, 120_000L), TimeUnit.MILLISECONDS)
        .build()

    private fun map(e: IOException): TransportException = when (e) {
        is TransportException -> e
        is SocketTimeoutException -> TransportException(TransportFailure.TIMEOUT, "The provider did not answer in time.")
        is SSLException -> TransportException(TransportFailure.TLS, "A secure connection could not be made.")
        is UnknownHostException -> TransportException(TransportFailure.UNREACHABLE, "The provider's address could not be found.")
        else -> if (e.message?.contains("timeout", true) == true)
            TransportException(TransportFailure.TIMEOUT, "The provider did not answer in time.")
        else TransportException(TransportFailure.UNREACHABLE, "The provider could not be reached.")
    }

    override suspend fun execute(request: HttpRequestSpec): HttpResponse {
        var lastError: Throwable? = null
        for (attempt in 1..3) {
            try {
                return executeOnce(request)
            } catch (e: TransportException) {
                lastError = e
                if (e.kind in setOf(TransportFailure.TIMEOUT, TransportFailure.UNREACHABLE) && attempt < 3) {
                    kotlinx.coroutines.delay(1000L * attempt)
                    continue
                }
                throw e
            }
        }
        throw lastError as? TransportException ?: TransportException(TransportFailure.UNREACHABLE, "Connection failed after 3 retries.")
    }

    private suspend fun executeOnce(request: HttpRequestSpec): HttpResponse {
        val call = clientFor(request).newCall(build(request))
        return suspendCancellableCoroutine { cont: CancellableContinuation<HttpResponse> ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWith(Result.failure(map(e)))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use {
                            val text = it.body?.string().orEmpty().take(MAX_BODY)
                            val headers = HashMap<String, String>()
                            for (name in it.headers.names()) headers[name.lowercase()] = it.header(name).orEmpty()
                            Result.success(HttpResponse(it.code, text, headers))
                        }
                    } catch (e: IOException) {
                        Result.failure(map(e))
                    }
                    if (cont.isActive) cont.resumeWith(result)
                }
            })
        }
    }

    override fun streamLines(request: HttpRequestSpec): Flow<String> = flow {
        val call = clientFor(request).newCall(build(request))
        try {
            val response = try { call.execute() } catch (e: IOException) { throw map(e) }
            response.use { r ->
                if (!r.isSuccessful) {
                    val text = try { r.body?.string().orEmpty().take(MAX_BODY) } catch (_: IOException) { "" }
                    throw HttpStatusException(r.code, text, r.header("Retry-After"))
                }
                val source = r.body?.source() ?: return@use
                try {
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        emit(line)
                    }
                } catch (e: IOException) {
                    throw map(e)
                }
            }
        } finally {
            call.cancel()
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun probeWebSocket(request: HttpRequestSpec, firstMessage: String, timeoutMs: Long): WsProbe {
        val req = build(request.copy(method = "GET", body = null))
        val ws = client.newBuilder().readTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
        return suspendCancellableCoroutine { cont ->
            var socket: WebSocket? = null
            val finished = java.util.concurrent.atomic.AtomicBoolean(false)
            fun done(r: WsProbe) {
                if (finished.compareAndSet(false, true)) {
                    try { socket?.close(1000, null) } catch (_: Exception) {}
                    if (cont.isActive) cont.resume(r)
                }
            }
            val timer = java.util.Timer(true)
            timer.schedule(object : java.util.TimerTask() {
                override fun run() { done(WsProbe(false, "No answer from the provider before the timeout.")) }
            }, timeoutMs)
            socket = ws.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(firstMessage) }
                override fun onMessage(webSocket: WebSocket, text: String) { handle(text) }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) { handle(bytes.utf8()) }
                private fun handle(text: String) {
                    if (text.contains("setupComplete") || text.contains("setup_complete")) done(WsProbe(true, "setupComplete"))
                    else done(WsProbe(false, text.take(300)))
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    done(WsProbe(false, "The connection was refused or dropped.", response?.code))
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    done(WsProbe(false, "Closed by the provider (code $code): ${reason.take(200)}"))
                }
            })
            cont.invokeOnCancellation { timer.cancel(); socket?.cancel() }
        }
    }

    companion object {
        private const val MAX_BODY = 8 * 1024 * 1024
    }
}
