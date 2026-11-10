package com.jarvis.assistant.provider

import kotlinx.coroutines.flow.Flow

/**
 * The only seam between provider logic and the network. Discovery, probing, testing and chat all go through
 * this interface, so they can be exercised with a fake transport and so exactly one class ([OkHttpTransport])
 * ever touches sockets.
 *
 * Implementations must never follow redirects (a redirect could carry an API key to another host) and must
 * never put a URL, header or key into an exception message.
 */
interface HttpTransport {
    /** Performs a request and returns any HTTP answer, including 4xx/5xx. Throws [TransportException] on network failure. */
    suspend fun execute(request: HttpRequestSpec): HttpResponse

    /**
     * Streams the response body line by line (for server-sent events). The flow fails with [HttpStatusException]
     * for a non-2xx answer and with [TransportException] for network failures. Cancelling the collector closes
     * the connection.
     */
    fun streamLines(request: HttpRequestSpec): Flow<String>

    /** Opens a WebSocket, sends [firstMessage], and reports whether the server answered with a setup acknowledgement. */
    suspend fun probeWebSocket(request: HttpRequestSpec, firstMessage: String, timeoutMs: Long): WsProbe
}

class MultipartPart(
    val name: String,
    val value: String? = null,
    val filename: String? = null,
    val contentType: String? = null,
    val bytes: ByteArray? = null
)

sealed class HttpBody {
    class Text(val contentType: String, val content: String) : HttpBody()
    class Multipart(val parts: List<MultipartPart>) : HttpBody()
}

class HttpRequestSpec(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: HttpBody? = null,
    val timeoutMs: Long = 20_000L
) {
    fun copy(
        method: String = this.method,
        url: String = this.url,
        headers: Map<String, String> = this.headers,
        body: HttpBody? = this.body,
        timeoutMs: Long = this.timeoutMs
    ) = HttpRequestSpec(method, url, headers, body, timeoutMs)

    /** Never prints header values or query values, so a request can be logged without leaking a key. */
    override fun toString(): String = "HttpRequestSpec($method ${ProviderUrl.redactQuery(url)})"
}

class HttpResponse(val code: Int, val body: String, val headers: Map<String, String> = emptyMap()) {
    val isSuccessful: Boolean get() = code in 200..299
    override fun toString(): String = "HttpResponse($code)"
}

enum class TransportFailure { TIMEOUT, UNREACHABLE, TLS, INVALID_URL }

class TransportException(val kind: TransportFailure, message: String) : java.io.IOException(message)

class HttpStatusException(val code: Int, val body: String, val retryAfter: String? = null) :
    java.io.IOException("HTTP $code")

data class WsProbe(val ok: Boolean, val detail: String, val httpCode: Int? = null)
