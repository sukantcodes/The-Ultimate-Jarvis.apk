package com.jarvis.assistant.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * GitHub OAuth App Device Flow.
 * The OAuth client ID is public. Device Flow does not use or embed a client secret.
 */
object GitHubDeviceFlow {
    // Public OAuth App client ID supplied by the app owner.
    private const val CLIENT_ID = "Ov23liapG5Xi11CaclCv"
    private const val DEVICE_CODE_URL = "https://github.com/login/device/code"
    private const val ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token"
    private const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
    // Least-privilege scope for public repository access plus the signed-in account identity.
    // Private repository access is intentionally not requested by this OAuth flow.
    private const val SCOPES = "read:user public_repo"

    private val client by lazy {
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    data class DeviceAuthorization(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val expiresInSeconds: Long,
        val intervalSeconds: Long
    )

    suspend fun requestAuthorization(): DeviceAuthorization = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("scope", SCOPES)
            .build()
        val json = post(DEVICE_CODE_URL, body)
        val deviceCode = json.optString("device_code")
        val userCode = json.optString("user_code")
        val verificationUri = json.optString("verification_uri")
        if (deviceCode.isBlank() || userCode.isBlank() ||
            verificationUri != "https://github.com/login/device") {
            throw IOException("GitHub returned an invalid device authorization response.")
        }
        DeviceAuthorization(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUri = verificationUri,
            expiresInSeconds = json.optLong("expires_in", 900L).coerceIn(60L, 900L),
            intervalSeconds = json.optLong("interval", 5L).coerceIn(5L, 60L)
        )
    }

    /**
     * Polls only at GitHub's supplied interval. Returns the access token or throws a
     * safe, user-facing error. The caller must persist the token using encrypted storage.
     */
    suspend fun awaitAccessToken(
        authorization: DeviceAuthorization,
        onWaiting: (Long) -> Unit = {}
    ): String {
        val deadline = System.currentTimeMillis() + authorization.expiresInSeconds * 1000L
        var interval = authorization.intervalSeconds.coerceAtLeast(5L)
        while (System.currentTimeMillis() < deadline) {
            delay(interval * 1000L)
            if (System.currentTimeMillis() >= deadline) break
            val result = withContext(Dispatchers.IO) {
                val body = FormBody.Builder()
                    .add("client_id", CLIENT_ID)
                    .add("device_code", authorization.deviceCode)
                    .add("grant_type", DEVICE_GRANT)
                    .build()
                post(ACCESS_TOKEN_URL, body)
            }
            val token = result.optString("access_token")
            if (token.isNotBlank()) return token
            when (result.optString("error")) {
                "authorization_pending" -> onWaiting(interval)
                "slow_down" -> {
                    interval = (result.optLong("interval", interval + 5L)).coerceAtLeast(interval + 5L)
                    onWaiting(interval)
                }
                "access_denied" -> throw IOException("GitHub authorization was denied.")
                "expired_token", "token_expired" -> throw IOException("The GitHub sign-in code expired. Start sign-in again.")
                "device_flow_disabled" -> throw IOException("Device Flow is not enabled for this GitHub OAuth App.")
                "incorrect_client_credentials" -> throw IOException("GitHub rejected the OAuth Client ID. Check the app configuration.")
                "incorrect_device_code" -> throw IOException("GitHub rejected the device code. Start sign-in again.")
                else -> throw IOException("GitHub sign-in failed: " +
                    result.optString("error_description").ifBlank { result.optString("error").ifBlank { "Unexpected response." } })
            }
        }
        throw IOException("The GitHub sign-in code expired. Start sign-in again.")
    }

    private fun post(url: String, body: FormBody): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "JARVIS-ChatBox")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (payload.length > 64_000) throw IOException("GitHub returned an unexpectedly large authentication response.")
            val json = runCatching { JSONObject(payload) }
                .getOrElse { throw IOException("GitHub returned an unreadable authentication response.") }
            if (!response.isSuccessful && json.optString("error").isBlank()) {
                throw IOException("GitHub authentication endpoint returned HTTP ${response.code}.")
            }
            return json
        }
    }
}
