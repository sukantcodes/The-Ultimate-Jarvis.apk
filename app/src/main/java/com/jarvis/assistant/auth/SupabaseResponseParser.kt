package com.jarvis.assistant.auth

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Turns Supabase Auth (GoTrue) responses into typed results. Uses Gson's tree API (no reflection, so it
 * needs no ProGuard rules) and has no Android dependencies, so it is unit tested on the JVM.
 */
object SupabaseResponseParser {

    private val SESSION_ERROR_CODES = setOf(
        "refresh_token_not_found",
        "refresh_token_already_used",
        "session_not_found",
        "session_expired",
        "bad_jwt",
        "no_authorization"
    )

    /** Parses a token response; returns null if it does not contain a usable session. */
    fun parseSession(body: String, nowEpochSec: Long): AuthSession? {
        val obj = parseObject(body) ?: return null
        val access = obj.str("access_token") ?: return null
        val refresh = obj.str("refresh_token") ?: return null
        val expiresAt = obj.long("expires_at") ?: (nowEpochSec + (obj.long("expires_in") ?: 3600L))
        val user = obj.obj("user")
        return AuthSession(
            accessToken = access,
            refreshToken = refresh,
            expiresAtEpochSec = expiresAt,
            userId = user?.str("id").orEmpty(),
            email = user?.str("email").orEmpty()
        )
    }

    /**
     * With email confirmation OFF the response is a full session. With it ON the response is the new user
     * (no tokens); an already-registered address comes back as a user whose `identities` array is empty.
     */
    fun parseSignUp(body: String, nowEpochSec: Long): SignUpOutcome {
        parseSession(body, nowEpochSec)?.let { return SignUpOutcome.SignedIn(it) }
        val identities = parseObject(body)?.get("identities")
        val alreadyRegistered =
            identities != null && identities.isJsonArray && identities.asJsonArray.size() == 0
        return if (alreadyRegistered) SignUpOutcome.AlreadyRegistered else SignUpOutcome.ConfirmationRequired
    }

    fun parseError(httpCode: Int, body: String): AuthResult.Failure {
        val obj = parseObject(body)
        val code = (obj?.str("error_code") ?: obj?.str("error"))?.lowercase().orEmpty()
        val raw = obj?.str("msg") ?: obj?.str("message") ?: obj?.str("error_description") ?: ""
        val lower = raw.lowercase()

        val error = when {
            code == "invalid_credentials" || lower.contains("invalid login credentials") ->
                AuthError.INVALID_CREDENTIALS
            code == "email_not_confirmed" || lower.contains("email not confirmed") ->
                AuthError.EMAIL_NOT_CONFIRMED
            code == "user_already_exists" || code == "email_exists" || lower.contains("already registered") ->
                AuthError.ALREADY_REGISTERED
            code == "weak_password" || code == "same_password" -> AuthError.WEAK_PASSWORD
            code == "signup_disabled" || code == "email_provider_disabled" -> AuthError.SIGNUP_DISABLED
            httpCode == 429 || (code.startsWith("over_") && code.endsWith("rate_limit")) -> AuthError.RATE_LIMITED
            code in SESSION_ERROR_CODES || lower.contains("refresh token") || lower.contains("jwt") ->
                AuthError.SESSION_INVALID
            else -> AuthError.UNKNOWN
        }
        return AuthResult.Failure(error, messageFor(error, raw), httpCode)
    }

    /** User-facing text. [raw] is the server's own message and is only used where it is genuinely helpful. */
    fun messageFor(error: AuthError, raw: String): String = when (error) {
        AuthError.INVALID_CREDENTIALS -> "Incorrect email or password."
        AuthError.EMAIL_NOT_CONFIRMED ->
            "Please confirm your email first. Check your inbox for the confirmation link."
        AuthError.ALREADY_REGISTERED ->
            "An account with this email already exists. Try logging in or resetting your password."
        AuthError.WEAK_PASSWORD ->
            raw.ifBlank { "That password is too weak. Use a longer, less common password." }
        AuthError.SIGNUP_DISABLED -> "New sign-ups are currently disabled."
        AuthError.RATE_LIMITED -> "Too many attempts. Please wait a minute and try again."
        AuthError.SESSION_INVALID -> "Your session has expired. Please sign in again."
        AuthError.NETWORK -> "Couldn't reach the server. Check your internet connection and try again."
        AuthError.NOT_CONFIGURED ->
            "Account sign-in isn't configured in this build. You can continue as a guest."
        AuthError.CANCELLED -> "Sign-in was cancelled."
        AuthError.UNKNOWN -> raw.ifBlank { "Something went wrong. Please try again." }
    }

    private fun parseObject(body: String): JsonObject? =
        try {
            val element = JsonParser.parseString(body)
            if (element.isJsonObject) element.asJsonObject else null
        } catch (_: Exception) {
            null
        }

    private fun JsonObject.str(name: String): String? {
        val element = get(name) ?: return null
        if (!element.isJsonPrimitive) return null
        return element.asString.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.long(name: String): Long? {
        val element = get(name) ?: return null
        if (!element.isJsonPrimitive) return null
        return try {
            element.asLong
        } catch (_: Exception) {
            null
        }
    }

    private fun JsonObject.obj(name: String): JsonObject? {
        val element = get(name) ?: return null
        return if (element.isJsonObject) element.asJsonObject else null
    }
}
