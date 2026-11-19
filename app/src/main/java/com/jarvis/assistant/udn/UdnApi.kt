package com.jarvis.assistant.udn

import android.content.Context
import android.util.Log
import com.jarvis.assistant.auth.AuthManager
import com.jarvis.assistant.auth.AuthResult
import com.jarvis.assistant.auth.SupabaseConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Client for the udn-api Edge Function. Every call carries the signed-in user's access token (account identity)
 * and an ECDSA signature made with this device's Keystore key over the exact request body (device identity),
 * a timestamp and a single-use nonce (replay protection).
 */
class UdnApi(context: Context) {
    private val settings = UdnSettings.get(context)
    @Volatile private var clockOffsetSec = 0L

    suspend fun call(action: String, args: JSONObject = JSONObject(), asDevice: String? = settings.deviceId): UdnResult<JSONObject> =
        withContext(Dispatchers.IO) { send(action, args, asDevice, allowClockRetry = true) }

    private suspend fun send(action: String, args: JSONObject, asDevice: String?, allowClockRetry: Boolean): UdnResult<JSONObject> {
        if (!SupabaseConfig.isConfigured) return UdnResult.Err("not_configured", UdnErrors.describe("not_configured"))
        val token = when (val t = AuthManager.validAccessToken()) {
            is AuthResult.Success -> t.value
            is AuthResult.Failure -> return UdnResult.Err(
                if (t.error == com.jarvis.assistant.auth.AuthError.NETWORK) "network" else "not_signed_in",
                UdnErrors.describe(if (t.error == com.jarvis.assistant.auth.AuthError.NETWORK) "network" else "not_signed_in")
            )
        }
        val body = JSONObject(args.toString()).put("action", action)
        val raw = body.toString()
        val bytes = raw.toByteArray(Charsets.UTF_8)
        val ts = (System.currentTimeMillis() / 1000 + clockOffsetSec).toString()
        val nonce = UdnSigning.newNonce()
        val deviceHeader = asDevice ?: "register"
        val signature = try {
            UdnIdentity.sign(UdnSigning.canonical(action, deviceHeader, ts, nonce, UdnSigning.sha256Hex(bytes)))
        } catch (e: Exception) {
            Log.e(TAG, "Signing failed: ${e.javaClass.simpleName}")
            return UdnResult.Err("signing_failed", "This device's security key is unavailable.")
        }
        val request = Request.Builder()
            .url(SupabaseConfig.url + "/functions/v1/udn-api")
            .header("apikey", SupabaseConfig.publishableKey)
            .header("Authorization", "Bearer $token")
            .header("X-UDN-Ts", ts)
            .header("X-UDN-Nonce", nonce)
            .header("X-UDN-Sig", signature)
            .apply { if (asDevice != null) header("X-UDN-Device", asDevice) }
            .post(bytes.toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val json = runCatching { JSONObject(text) }.getOrNull()
                if (json != null && json.optBoolean("ok", false)) {
                    UdnResult.Ok(json)
                } else {
                    val code = json?.optString("error").orEmpty().ifEmpty { if (resp.code == 401) "unauthorized" else "http_${resp.code}" }
                    if (code == "stale_request" && allowClockRetry) {
                        val serverMs = resp.headers.getDate("Date")?.time
                        if (serverMs != null) {
                            clockOffsetSec = (serverMs - System.currentTimeMillis()) / 1000
                            return@use send(action, args, asDevice, allowClockRetry = false)
                        }
                    }
                    UdnResult.Err(code, UdnErrors.describe(code, json?.optString("reason")), json, resp.code)
                }
            }
        } catch (e: IOException) {
            UdnResult.Err("network", UdnErrors.describe("network"))
        } catch (e: Exception) {
            Log.w(TAG, "UDN call failed: ${e.javaClass.simpleName}")
            UdnResult.Err("error", e.message ?: "Unexpected error")
        }
    }

    companion object {
        private const val TAG = "UdnApi"
        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false) // retries are explicit and idempotent (nonces/command nonces), never silent
                .build()
        }
    }
}
