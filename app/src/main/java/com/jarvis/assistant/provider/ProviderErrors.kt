package com.jarvis.assistant.provider

import com.google.gson.JsonObject
import com.google.gson.JsonParser

enum class ProviderErrorKind {
    INVALID_ENDPOINT,
    INVALID_CONFIG,
    AUTH_FAILED,
    KEY_REJECTED,
    DISCOVERY_UNSUPPORTED,
    NO_MODELS,
    TIMEOUT,
    UNAVAILABLE,
    INVALID_RESPONSE,
    RATE_LIMITED,
    REDIRECT_BLOCKED,
    TLS,
    MODEL_NOT_FOUND,
    UNSUPPORTED_FEATURE,
    STREAM_INTERRUPTED,
    BAD_REQUEST,
    CANCELLED
}

/** A failure explained in plain language. [message] never contains the API key. */
data class ProviderError(
    val kind: ProviderErrorKind,
    val message: String,
    val httpCode: Int? = null
) {
    /** Failures that say nothing about whether a model supports a feature (the provider was just unreachable or busy). */
    val isTransient: Boolean
        get() = kind == ProviderErrorKind.TIMEOUT || kind == ProviderErrorKind.UNAVAILABLE ||
            kind == ProviderErrorKind.RATE_LIMITED || kind == ProviderErrorKind.TLS

    val isAuth: Boolean get() = kind == ProviderErrorKind.AUTH_FAILED || kind == ProviderErrorKind.KEY_REJECTED
}

class ProviderException(val error: ProviderError) : Exception(error.message)

enum class ErrorContext { DISCOVERY, CHAT }

object ProviderErrors {

    private const val MAX_DETAIL = 180

    /** Extracts the provider's own explanation from a JSON error body, redacts the key, and shortens it. */
    fun providerMessage(body: String, secret: String?): String {
        val raw = extractMessage(body)
        return ProviderAuth.redact(raw, secret).replace(Regex("\\s+"), " ").trim().take(MAX_DETAIL)
    }

    private fun extractMessage(body: String): String {
        val text = body.trim()
        if (text.isEmpty()) return ""
        if (text.startsWith("<")) return "" // an HTML error page tells the user nothing
        return try {
            val root = JsonParser.parseString(text)
            if (!root.isJsonObject) return text.take(MAX_DETAIL)
            val obj = root.asJsonObject
            val error = obj.get("error")
            when {
                error != null && error.isJsonObject -> stringOf(error.asJsonObject, "message")
                    ?: stringOf(error.asJsonObject, "status") ?: ""
                error != null && error.isJsonPrimitive -> error.asString
                else -> stringOf(obj, "message") ?: stringOf(obj, "detail") ?: stringOf(obj, "error_description") ?: ""
            }
        } catch (e: Exception) {
            text.take(MAX_DETAIL)
        }
    }

    private fun stringOf(obj: JsonObject, key: String): String? {
        val v = obj.get(key) ?: return null
        return if (v.isJsonPrimitive) v.asString else null
    }

    fun fromHttp(code: Int, body: String, secret: String?, context: ErrorContext): ProviderError {
        val detail = providerMessage(body, secret)
        fun withDetail(base: String) = if (detail.isBlank()) base else "$base Provider said: $detail"
        return when {
            code in 300..399 -> ProviderError(
                ProviderErrorKind.REDIRECT_BLOCKED,
                "The endpoint redirected (HTTP $code). JARVIS does not follow redirects, so your key is never sent to another address. Use the final URL instead.",
                code
            )
            code == 401 -> ProviderError(
                ProviderErrorKind.AUTH_FAILED,
                withDetail("Authentication failed (HTTP 401). Check the API key and the authentication method."),
                code
            )
            code == 403 -> ProviderError(
                ProviderErrorKind.KEY_REJECTED,
                withDetail("The provider rejected the API key or it isn't allowed to do this (HTTP 403)."),
                code
            )
            code == 404 || code == 405 || code == 501 ->
                if (context == ErrorContext.DISCOVERY) ProviderError(
                    ProviderErrorKind.DISCOVERY_UNSUPPORTED,
                    "This endpoint doesn't offer a model list (HTTP $code). Enter the model ID manually, or set a different model-discovery path.",
                    code
                ) else ProviderError(
                    ProviderErrorKind.MODEL_NOT_FOUND,
                    withDetail("The provider could not find this model or route (HTTP $code)."),
                    code
                )
            code == 408 || code == 504 -> ProviderError(
                ProviderErrorKind.TIMEOUT,
                "The provider took too long to answer (HTTP $code). Try again.",
                code
            )
            code == 429 -> ProviderError(
                ProviderErrorKind.RATE_LIMITED,
                withDetail("The provider's rate limit was reached (HTTP 429). Wait a moment and try again."),
                code
            )
            code in 500..599 -> ProviderError(
                ProviderErrorKind.UNAVAILABLE,
                withDetail("The provider is unavailable right now (HTTP $code). Try again shortly."),
                code
            )
            code == 400 || code == 413 || code == 415 || code == 422 -> ProviderError(
                if (looksLikeModelMissing(detail)) ProviderErrorKind.MODEL_NOT_FOUND else ProviderErrorKind.BAD_REQUEST,
                withDetail("The provider rejected the request (HTTP $code)."),
                code
            )
            else -> ProviderError(
                ProviderErrorKind.UNAVAILABLE,
                withDetail("The provider answered with an unexpected status (HTTP $code)."),
                code
            )
        }
    }

    private fun looksLikeModelMissing(detail: String): Boolean {
        val d = detail.lowercase()
        return d.contains("model") && (d.contains("not found") || d.contains("does not exist") ||
            d.contains("not available") || d.contains("unknown model") || d.contains("invalid model"))
    }

    fun fromTransport(e: TransportException): ProviderError = when (e.kind) {
        TransportFailure.TIMEOUT -> ProviderError(
            ProviderErrorKind.TIMEOUT,
            "The connection timed out. Check your internet connection and the endpoint address."
        )
        TransportFailure.TLS -> ProviderError(
            ProviderErrorKind.TLS,
            "A secure (HTTPS) connection could not be established. The provider's certificate may be invalid."
        )
        TransportFailure.INVALID_URL -> ProviderError(
            ProviderErrorKind.INVALID_ENDPOINT,
            "The endpoint address could not be used."
        )
        TransportFailure.UNREACHABLE -> ProviderError(
            ProviderErrorKind.UNAVAILABLE,
            "Could not reach the provider. Check your internet connection and the endpoint address."
        )
    }

    fun invalidResponse(hint: String): ProviderError =
        ProviderError(ProviderErrorKind.INVALID_RESPONSE, "The provider's answer wasn't in the expected format. $hint".trim())
}
