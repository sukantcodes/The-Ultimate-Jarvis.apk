package com.jarvis.assistant.provider

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Decides what a failed request means for the API key that made it. Quota exhaustion, short rate limits,
 * authentication problems, billing problems and server trouble are kept apart because each is handled
 * differently: only the first four move on to another key, and only the first two come back by themselves.
 */
object KeyFailureClassifier {

    private val QUOTA_WORDS = listOf(
        "quota", "insufficient_quota", "exceeded your current", "resource_exhausted", "resource exhausted",
        "daily limit", "monthly limit", "limit reached", "usage limit", "out of credits"
    )
    private val BILLING_WORDS = listOf(
        "billing", "payment", "insufficient balance", "insufficient funds", "balance is too low", "plan and billing"
    )
    private val BAD_KEY_WORDS = listOf(
        "api key not valid", "invalid api key", "incorrect api key", "api_key_invalid", "invalid_api_key",
        "api key expired", "key has been disabled", "unauthorized"
    )

    fun classify(error: ProviderError): KeyFailure {
        val said = providerSaid(error.message).lowercase()
        val code = error.httpCode
        return when {
            error.kind == ProviderErrorKind.CANCELLED -> KeyFailure.OTHER
            code == 402 -> KeyFailure.BILLING
            code == 401 || error.kind == ProviderErrorKind.AUTH_FAILED -> KeyFailure.AUTH
            code == 429 || error.kind == ProviderErrorKind.RATE_LIMITED -> when {
                QUOTA_WORDS.any { said.contains(it) } -> KeyFailure.QUOTA
                else -> KeyFailure.RATE_LIMIT
            }
            code == 403 || error.kind == ProviderErrorKind.KEY_REJECTED -> when {
                QUOTA_WORDS.any { said.contains(it) } -> KeyFailure.QUOTA
                BILLING_WORDS.any { said.contains(it) } -> KeyFailure.BILLING
                else -> KeyFailure.AUTH
            }
            error.kind == ProviderErrorKind.BAD_REQUEST && BAD_KEY_WORDS.any { said.contains(it) } -> KeyFailure.AUTH
            error.kind == ProviderErrorKind.BAD_REQUEST && BILLING_WORDS.any { said.contains(it) } &&
                QUOTA_WORDS.none { said.contains(it) } -> KeyFailure.BILLING
            error.kind == ProviderErrorKind.TIMEOUT || error.kind == ProviderErrorKind.TLS ||
                error.kind == ProviderErrorKind.STREAM_INTERRUPTED -> KeyFailure.NETWORK
            error.kind == ProviderErrorKind.UNAVAILABLE -> if (code == null) KeyFailure.NETWORK else KeyFailure.SERVER
            else -> KeyFailure.OTHER
        }
    }

    /** Only the provider's own explanation. The generic text JARVIS adds ("rate limit was reached") says nothing about the cause. */
    private fun providerSaid(message: String): String {
        val marker = "Provider said:"
        val i = message.indexOf(marker)
        return if (i < 0) "" else message.substring(i + marker.length)
    }
}

/** Reads the `Retry-After` header: either a number of seconds or an HTTP date. */
object RetryAfter {
    fun parse(value: String?, nowMs: Long): Long? {
        val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        v.toLongOrNull()?.let { seconds -> return if (seconds < 0) null else seconds * 1000L }
        v.toDoubleOrNull()?.let { seconds -> return if (seconds < 0) null else (seconds * 1000).toLong() }
        return try {
            val at = ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            (at - nowMs).coerceAtLeast(0L)
        } catch (e: Exception) {
            null
        }
    }
}
