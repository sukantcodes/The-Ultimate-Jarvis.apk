package com.jarvis.assistant.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/** Outcome of asking Google whether an API key is accepted. Never carries the key itself. */
sealed class ApiKeyCheck {
    data class Valid(val latencyMs: Long) : ApiKeyCheck()
    data class Rejected(val httpCode: Int) : ApiKeyCheck()
    data class Unreachable(val timedOut: Boolean) : ApiKeyCheck()
    object Empty : ApiKeyCheck()
}

/**
 * The single place where a Gemini API key is tested. Settings and the first-launch tutorial both call it.
 * The key travels in a request header, never in the URL, so it cannot end up in URL logs or exception text.
 */
object ApiKeyValidator {
    private const val MODELS_URL = "https://generativelanguage.googleapis.com/v1beta/models"
    private const val KEY_HEADER = "x-goog-api-key"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    suspend fun validate(apiKey: String): ApiKeyCheck = withContext(Dispatchers.IO) {
        val key = apiKey.trim()
        if (key.isEmpty()) return@withContext ApiKeyCheck.Empty
        val started = System.currentTimeMillis()
        try {
            val request = Request.Builder().url(MODELS_URL).header(KEY_HEADER, key).get().build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) ApiKeyCheck.Valid(System.currentTimeMillis() - started)
                else ApiKeyCheck.Rejected(response.code)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            ApiKeyCheck.Unreachable(timedOut = true)
        } catch (e: IOException) {
            ApiKeyCheck.Unreachable(timedOut = false)
        } catch (e: Exception) {
            // Includes a key with characters HTTP headers cannot carry. Nothing about the key is reported.
            ApiKeyCheck.Unreachable(timedOut = false)
        }
    }

    /** Plain-language explanation for the person using the app. */
    fun describe(check: ApiKeyCheck): String = when (check) {
        is ApiKeyCheck.Valid -> "Google accepted this key (${check.latencyMs} ms)."
        is ApiKeyCheck.Rejected -> when (check.httpCode) {
            400, 401, 403 -> "Google rejected this key (HTTP ${check.httpCode}). Check that you copied all of it."
            429 -> "Google is rate-limiting this key right now (HTTP 429). Wait a minute and test again."
            in 500..599 -> "Google's service had a problem (HTTP ${check.httpCode}). Try again shortly."
            else -> "Unexpected answer from Google (HTTP ${check.httpCode})."
        }
        is ApiKeyCheck.Unreachable ->
            if (check.timedOut) "The connection timed out. Check your internet and try again."
            else "Couldn't reach Google. Check your internet connection."
        ApiKeyCheck.Empty -> "Paste an API key first."
    }
}
