package com.jarvis.assistant.ui.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Supabase email/password auth using the app's public publishable key. Never put a service-role key here. */
internal object SupabaseEmailAuth {
    data class Result(val success: Boolean, val message: String, val needsEmailConfirmation: Boolean = false)

    // Set these to the project's Supabase URL and publishable (client) key before enabling this entry point.
    private const val SUPABASE_URL = "https://yeggjbjcoaaoqntqyjdv.supabase.co"
    private const val SUPABASE_PUBLISHABLE_KEY = "REPLACE_WITH_SUPABASE_PUBLISHABLE_KEY"
    private val client = OkHttpClient()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun authenticate(email: String, password: String, register: Boolean): Result = withContext(Dispatchers.IO) {
        if (SUPABASE_PUBLISHABLE_KEY.startsWith("REPLACE_")) {
            return@withContext Result(false, "Supabase publishable key is not configured.")
        }
        val endpoint = if (register) "/auth/v1/signup" else "/auth/v1/token?grant_type=password"
        val payload = JSONObject().put("email", email).put("password", password).toString()
        val request = Request.Builder()
            .url(SUPABASE_URL + endpoint)
            .header("apikey", SUPABASE_PUBLISHABLE_KEY)
            .header("Authorization", "Bearer $SUPABASE_PUBLISHABLE_KEY")
            .post(payload.toRequestBody(jsonType))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val data = runCatching { JSONObject(body) }.getOrNull()
                if (!response.isSuccessful) {
                    val error = data?.optString("msg").takeUnless { it.isNullOrBlank() }
                        ?: data?.optString("message").takeUnless { it.isNullOrBlank() }
                        ?: "Authentication failed (HTTP ${response.code})."
                    Result(false, error)
                } else {
                    val confirmation = register && data?.optJSONObject("user")?.optString("id").isNullOrBlank()
                    Result(true, if (confirmation) "Check your email to confirm your account." else "Authenticated.", confirmation)
                }
            }
        } catch (_: Exception) {
            Result(false, "Could not connect. Check your internet and try again.")
        }
    }
}
