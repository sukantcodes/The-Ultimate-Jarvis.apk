package com.jarvis.assistant.auth

import android.net.Uri
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Thin REST client for Supabase Auth (GoTrue). Sends only the publishable key; bodies, tokens and
 * passwords are never logged.
 */
internal class SupabaseAuthClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    private val json = "application/json; charset=utf-8".toMediaType()
    private val profileHttp = http.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    /** Fetches the authoritative profile for the current authenticated user. */
    suspend fun fetchCurrentUserProfile(accessToken: String): AuthResult<AuthProfile> {
        val result: AuthResult<AuthProfile?> = call(
            path = "/auth/v1/user",
            query = emptyMap(),
            body = null,
            method = "GET",
            bearer = accessToken,
            requestClient = profileHttp
        ) { SupabaseResponseParser.parseProfile(it) }
        return when (result) {
            is AuthResult.Success -> result.value?.let { AuthResult.Success(it) }
                ?: AuthResult.Failure(AuthError.UNKNOWN, "The account profile response was incomplete.")
            is AuthResult.Failure -> result
        }
    }

    suspend fun signUp(email: String, password: String, challenge: String): AuthResult<SignUpOutcome> =
        call(
            path = "/auth/v1/signup",
            query = mapOf("redirect_to" to SupabaseConfig.redirectUri(AuthFlow.SIGNUP)),
            body = JsonObject().apply {
                addProperty("email", email); addProperty("password", password)
                addProperty("code_challenge", challenge); addProperty("code_challenge_method", "s256")
            }
        ) { SupabaseResponseParser.parseSignUp(it, now()) }

    suspend fun signInWithPassword(email: String, password: String): AuthResult<AuthSession> =
        session(
            path = "/auth/v1/token", query = mapOf("grant_type" to "password"),
            body = JsonObject().apply { addProperty("email", email); addProperty("password", password) }
        )

    suspend fun refresh(refreshToken: String): AuthResult<AuthSession> =
        session(
            path = "/auth/v1/token", query = mapOf("grant_type" to "refresh_token"),
            body = JsonObject().apply { addProperty("refresh_token", refreshToken) }
        )

    suspend fun exchangeCode(code: String, verifier: String): AuthResult<AuthSession> =
        session(
            path = "/auth/v1/token", query = mapOf("grant_type" to "pkce"),
            body = JsonObject().apply { addProperty("auth_code", code); addProperty("code_verifier", verifier) }
        )

    suspend fun sendPasswordReset(email: String, challenge: String): AuthResult<Unit> =
        call(
            path = "/auth/v1/recover",
            query = mapOf("redirect_to" to SupabaseConfig.redirectUri(AuthFlow.RECOVERY)),
            body = JsonObject().apply {
                addProperty("email", email)
                addProperty("code_challenge", challenge); addProperty("code_challenge_method", "s256")
            }
        ) { }

    suspend fun updatePassword(accessToken: String, newPassword: String): AuthResult<Unit> =
        call(
            path = "/auth/v1/user", query = emptyMap(), method = "PUT", bearer = accessToken,
            body = JsonObject().apply { addProperty("password", newPassword) }
        ) { }

    /** Revokes the session server-side. Callers clear local state regardless of this result. */
    suspend fun signOut(accessToken: String): AuthResult<Unit> =
        call(
            path = "/auth/v1/logout", query = mapOf("scope" to "local"), bearer = accessToken,
            body = JsonObject()
        ) { }

    /** Browser URL that starts Supabase's Google OAuth (PKCE). The Google client secret stays in Supabase. */
    fun googleAuthorizeUrl(challenge: String): String =
        Uri.parse(SupabaseConfig.url + "/auth/v1/authorize").buildUpon()
            .appendQueryParameter("provider", "google")
            .appendQueryParameter("redirect_to", SupabaseConfig.redirectUri(AuthFlow.GOOGLE))
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "s256")
            .build().toString()

    private suspend fun session(path: String, query: Map<String, String>, body: JsonObject): AuthResult<AuthSession> =
        call(path, query, body) { text ->
            SupabaseResponseParser.parseSession(text, now())
        }.let { result ->
            when (result) {
                is AuthResult.Success ->
                    result.value?.let { AuthResult.Success(it) }
                        ?: AuthResult.Failure(AuthError.UNKNOWN, "The server returned an unexpected response.")
                is AuthResult.Failure -> result
            }
        }

    private suspend fun <T> call(
        path: String,
        query: Map<String, String>,
        body: JsonObject?,
        method: String = "POST",
        bearer: String? = null,
        requestClient: OkHttpClient = http,
        parse: (String) -> T
    ): AuthResult<T> {
        if (!SupabaseConfig.isConfigured) {
            return AuthResult.Failure(
                AuthError.NOT_CONFIGURED,
                SupabaseResponseParser.messageFor(AuthError.NOT_CONFIGURED, "")
            )
        }
        return withContext(Dispatchers.IO) {
            try {
                val url = (SupabaseConfig.url + path).toHttpUrl().newBuilder()
                    .apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }
                    .build()
                val request = Request.Builder()
                    .url(url)
                    .header("apikey", SupabaseConfig.publishableKey)
                    .header("Authorization", "Bearer ${bearer ?: SupabaseConfig.publishableKey}")
                    .method(
                        method,
                        if (method.equals("GET", ignoreCase = true) || method.equals("HEAD", ignoreCase = true)) null
                        else body?.toString()?.toRequestBody(json)
                    )
                    .build()
                requestClient.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (response.isSuccessful) AuthResult.Success(parse(text))
                    else SupabaseResponseParser.parseError(response.code, text)
                }
            } catch (_: IOException) {
                AuthResult.Failure(AuthError.NETWORK, SupabaseResponseParser.messageFor(AuthError.NETWORK, ""))
            } catch (_: Exception) {
                AuthResult.Failure(AuthError.UNKNOWN, SupabaseResponseParser.messageFor(AuthError.UNKNOWN, ""))
            }
        }
    }

    private fun now() = System.currentTimeMillis() / 1000L
}
