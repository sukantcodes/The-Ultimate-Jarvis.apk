package com.jarvis.assistant.auth

/**
 * A Supabase Auth session.
 *
 * Signing in only proves identity. It never implies a Pro subscription or any other entitlement;
 * those must be checked separately.
 */
data class AuthSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSec: Long,
    val userId: String,
    val email: String,
    val displayName: String = "",
    val avatarUrl: String = ""
) {
    /** True when the access token is expired or will expire within [skewSec] seconds. */
    fun isExpired(nowEpochSec: Long, skewSec: Long = 60L): Boolean =
        nowEpochSec + skewSec >= expiresAtEpochSec
}

enum class AuthError {
    INVALID_CREDENTIALS,
    EMAIL_NOT_CONFIRMED,
    ALREADY_REGISTERED,
    WEAK_PASSWORD,
    SIGNUP_DISABLED,
    RATE_LIMITED,
    SESSION_INVALID,
    NETWORK,
    NOT_CONFIGURED,
    CANCELLED,
    UNKNOWN
}

sealed class AuthResult<out T> {
    data class Success<out T>(val value: T) : AuthResult<T>()
    data class Failure(
        val error: AuthError,
        val message: String,
        val httpCode: Int = 0
    ) : AuthResult<Nothing>()
}

/** What Supabase did with a sign-up request, which depends on the project's email-confirmation setting. */
sealed class SignUpOutcome {
    /** Email confirmation is off: the account is active and a session was issued. */
    data class SignedIn(val session: AuthSession) : SignUpOutcome()

    /** Email confirmation is on: the user must open the link sent to their inbox. */
    object ConfirmationRequired : SignUpOutcome()

    /** The email is already registered (Supabase returns a user with no identities). */
    object AlreadyRegistered : SignUpOutcome()
}

/** The browser/email-link flows that come back to the app through the auth-callback deep link. */
enum class AuthFlow(val key: String) {
    GOOGLE("google"),
    SIGNUP("signup"),
    RECOVERY("recovery");

    companion object {
        fun fromKey(key: String?): AuthFlow? = values().firstOrNull { it.key == key }
    }
}

/** Result of handling an auth-callback deep link. */
sealed class CallbackOutcome {
    data class Completed(val flow: AuthFlow) : CallbackOutcome()
    data class Info(val message: String) : CallbackOutcome()
    data class Failed(val error: AuthError, val message: String) : CallbackOutcome()
}
