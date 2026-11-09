package com.jarvis.assistant.auth

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Result of checking the stored session against the server. */
enum class SessionCheck { SIGNED_IN, SIGNED_OUT, EXPIRED, OFFLINE_KEPT }

/**
 * Single entry point for account state. Guest mode is simply "no session + guest chosen": no Supabase
 * account is created for guests, and nothing a guest has stored is touched by signing in or out.
 * Being signed in grants identity only, never a Pro entitlement.
 */
object AuthManager {
    private lateinit var store: SessionStore
    private val client = SupabaseAuthClient()
    private val refreshLock = Mutex()
    private val profileRefreshLock = Mutex()
    private val profileScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _session = MutableStateFlow<AuthSession?>(null)

    /** Current session, or null for guests / signed-out users. */
    val session: StateFlow<AuthSession?> = _session.asStateFlow()

    @Volatile private var initialised = false

    fun init(context: Context) {
        if (initialised) return
        synchronized(this) {
            if (initialised) return
            store = SessionStore(context)
            com.jarvis.assistant.owner.DeviceTelemetry.init(context)
            _session.value = store.loadSession()
            syncAccountProfile(_session.value)
            initialised = true
            _session.value?.let { saved ->
                // Refresh persisted claims in the background; Settings observes session changes and repaints the card.
                profileScope.launch { refreshAccountProfile(saved.userId) }
            }
        }
    }

    val isSignedIn: Boolean get() = _session.value != null
    val email: String? get() = _session.value?.email?.takeIf { it.isNotBlank() }

    /** True on cold start when the user has neither a session nor chosen to continue as a guest. */
    fun needsAuthScreen(): Boolean = _session.value == null && !store.guestChosen

    fun continueAsGuest() { store.guestChosen = true }

    /** Message queued for the auth screen (read once). */
    fun consumeNotice(): String? = store.pendingNotice.also { if (it != null) store.pendingNotice = null }

    // ── Email / password ────────────────────────────────────────────────────────────────────────

    suspend fun signIn(email: String, password: String): AuthResult<AuthSession> {
        val result = client.signInWithPassword(email.trim(), password)
        if (result is AuthResult.Success) adoptNewSignIn(result.value)
        return result
    }

    suspend fun signUp(email: String, password: String): AuthResult<SignUpOutcome> {
        val verifier = Pkce.newVerifier()
        store.savePendingVerifier(AuthFlow.SIGNUP, verifier)
        val result = client.signUp(email.trim(), password, Pkce.challengeFor(verifier))
        if (result is AuthResult.Success) {
            val outcome = result.value
            if (outcome is SignUpOutcome.SignedIn) adoptNewSignIn(outcome.session)
        }
        return result
    }

    suspend fun sendPasswordReset(email: String): AuthResult<Unit> {
        val verifier = Pkce.newVerifier()
        store.savePendingVerifier(AuthFlow.RECOVERY, verifier)
        return client.sendPasswordReset(email.trim(), Pkce.challengeFor(verifier))
    }

    /** Sets a new password for the signed-in user (used after opening a reset link). */
    suspend fun updatePassword(newPassword: String): AuthResult<Unit> {
        val token = validAccessToken()
        if (token is AuthResult.Failure) return token
        return client.updatePassword((token as AuthResult.Success).value, newPassword)
    }

    // ── Google (Supabase OAuth, PKCE, system browser) ───────────────────────────────────────────

    /** Returns the URL to open in the browser. The callback is delivered to [handleCallback]. */
    fun beginGoogleSignIn(): AuthResult<String> {
        if (!SupabaseConfig.isConfigured) {
            return AuthResult.Failure(AuthError.NOT_CONFIGURED, SupabaseResponseParser.messageFor(AuthError.NOT_CONFIGURED, ""))
        }
        val verifier = Pkce.newVerifier()
        store.savePendingVerifier(AuthFlow.GOOGLE, verifier)
        return AuthResult.Success(client.googleAuthorizeUrl(Pkce.challengeFor(verifier)))
    }

    /** Handles com.jarvis.assistant://auth-callback/<flow> for Google, email confirmation and password reset. */
    suspend fun handleCallback(uri: Uri): CallbackOutcome? {
        if (uri.scheme != SupabaseConfig.REDIRECT_SCHEME || uri.host != SupabaseConfig.REDIRECT_HOST) return null
        val flow = AuthFlow.fromKey(uri.pathSegments.firstOrNull()) ?: return null
        val params = CallbackParams.parse(uri.encodedQuery, uri.encodedFragment)

        return when (val kind = CallbackParams.classify(params)) {
            is CallbackParams.Kind.Error -> {
                store.takePendingVerifier(flow)
                if (kind.cancelled && flow == AuthFlow.GOOGLE) {
                    CallbackOutcome.Failed(AuthError.CANCELLED, SupabaseResponseParser.messageFor(AuthError.CANCELLED, ""))
                } else {
                    CallbackOutcome.Failed(
                        AuthError.UNKNOWN,
                        kind.description.ifBlank { "Sign-in failed. Please try again." }
                    )
                }
            }
            is CallbackParams.Kind.Code -> {
                val verifier = store.takePendingVerifier(flow)
                    ?: return CallbackOutcome.Failed(
                        AuthError.SESSION_INVALID,
                        "This link must be opened on the device where you started. Please start again."
                    )
                when (val result = client.exchangeCode(kind.code, verifier)) {
                    is AuthResult.Success -> { adoptNewSignIn(result.value); CallbackOutcome.Completed(flow) }
                    is AuthResult.Failure -> CallbackOutcome.Failed(result.error, result.message)
                }
            }
            CallbackParams.Kind.ImplicitTokens -> {
                store.takePendingVerifier(flow)
                // Never build a session from tokens in a link; for sign-up links the email is confirmed, so ask for login.
                if (flow == AuthFlow.SIGNUP) CallbackOutcome.Info("Email confirmed. You can log in now.")
                else CallbackOutcome.Failed(AuthError.UNKNOWN, "That sign-in link isn't supported. Please try again from the app.")
            }
            CallbackParams.Kind.Empty -> null
        }
    }

    // ── Session lifecycle ───────────────────────────────────────────────────────────────────────

    internal fun freshAccessToken(session: AuthSession?, nowEpochSec: Long): String? =
        session?.takeIf { !it.isExpired(nowEpochSec) }?.accessToken

    /** A non-expired access token, refreshing first if needed. Network errors keep the session. */
    suspend fun validAccessToken(): AuthResult<String> {
        val current = _session.value
            ?: return AuthResult.Failure(AuthError.SESSION_INVALID, SupabaseResponseParser.messageFor(AuthError.SESSION_INVALID, ""))
        freshAccessToken(current, nowSec())?.let { return AuthResult.Success(it) }
        val invalid = AuthResult.Failure(AuthError.SESSION_INVALID, SupabaseResponseParser.messageFor(AuthError.SESSION_INVALID, ""))
        return when (refreshLocked()) {
            SessionCheck.SIGNED_IN -> {
                freshAccessToken(_session.value, nowSec())?.let { AuthResult.Success(it) } ?: invalid
            }
            // Keeping an expired session locally is useful for offline account display,
            // but an expired bearer token must never be returned as valid credentials.
            SessionCheck.OFFLINE_KEPT -> invalid
            else -> invalid
        }
    }

    /** Called when the app returns to the foreground. */
    suspend fun checkSession(): SessionCheck {
        val current = _session.value ?: return SessionCheck.SIGNED_OUT
        if (!current.isExpired(nowSec())) return SessionCheck.SIGNED_IN
        return refreshLocked()
    }

    private suspend fun refreshLocked(): SessionCheck {
        refreshLock.withLock {
            val current = _session.value ?: return SessionCheck.SIGNED_OUT
            // Another caller may have refreshed while we waited for the lock.
            if (!current.isExpired(nowSec())) return SessionCheck.SIGNED_IN
            return when (val result = client.refresh(current.refreshToken)) {
                is AuthResult.Success -> { adopt(result.value); SessionCheck.SIGNED_IN }
                is AuthResult.Failure -> when (result.error) {
                    // The server rejected the refresh token: the session is really over.
                    AuthError.SESSION_INVALID, AuthError.INVALID_CREDENTIALS -> expire()
                    // Offline, rate-limited or a server hiccup: keep the session rather than logging out.
                    else -> SessionCheck.OFFLINE_KEPT
                }
            }
        }
    }

    private fun expire(): SessionCheck {
        com.jarvis.assistant.owner.OwnerSession.clear()
        store.clearSession()
        _session.value = null
        clearAccountProfile()
        store.pendingNotice = "Your session expired. Please sign in again, or continue as a guest."
        return SessionCheck.EXPIRED
    }

    /** Logs out: revokes the session (best effort) and clears local credentials. Guest data is untouched. */
    suspend fun signOut() {
        // Revoke any owner-dashboard access first (needs the still-valid account session), then drop it locally.
        com.jarvis.assistant.owner.OwnerDashboardClient.endSession()
        val current = _session.value
        store.clearSession()
        _session.value = null
        clearAccountProfile()
        store.guestChosen = false
        if (current != null) client.signOut(current.accessToken)
    }

    private fun adopt(session: AuthSession) {
        val previous = _session.value
        val merged = session.copy(
            displayName = session.displayName.ifBlank {
                previous?.takeIf { it.userId == session.userId }?.displayName.orEmpty()
            },
            avatarUrl = session.avatarUrl.ifBlank {
                previous?.takeIf { it.userId == session.userId }?.avatarUrl.orEmpty()
            }
        )
        store.saveSession(merged)
        _session.value = merged
        syncAccountProfile(merged)
    }

    private fun syncAccountProfile(session: AuthSession?) {
        if (session == null || session.userId.isBlank()) return
        runCatching {
            com.jarvis.assistant.JarvisApp.instance.preferences.syncAccountProfile(
                session.userId, session.displayName, session.avatarUrl
            )
        }
    }

    private fun clearAccountProfile() {
        runCatching { com.jarvis.assistant.JarvisApp.instance.preferences.clearAccountProfile() }
    }

    /** A fresh interactive sign-in (not a token refresh): adopt the session and record the sign-in. */
    private suspend fun adoptNewSignIn(session: AuthSession) {
        adopt(session)
        com.jarvis.assistant.owner.DeviceTelemetry.onSignedIn()
        // Token responses can omit provider identity claims. Resolve the authenticated user record
        // before the auth screen hands control back to the app (bounded to a short network timeout).
        refreshAccountProfile(session.userId)
    }

    private suspend fun refreshAccountProfile(expectedUserId: String) {
        if (expectedUserId.isBlank()) return
        profileRefreshLock.withLock {
            val before = _session.value?.takeIf { it.userId == expectedUserId } ?: return
            val token = when (val access = validAccessToken()) {
                is AuthResult.Success -> access.value
                is AuthResult.Failure -> return
            }
            val result = client.fetchCurrentUserProfile(token)
            if (result !is AuthResult.Success || result.value.userId != expectedUserId) return

            // Never let a slow response from account A overwrite a later sign-in to account B.
            val current = _session.value?.takeIf { it.userId == expectedUserId } ?: return
            val profile = result.value
            val updated = current.copy(
                displayName = profile.displayName.ifBlank { current.displayName },
                avatarUrl = profile.avatarUrl.ifBlank { current.avatarUrl }
            )
            if (updated != before) {
                store.saveSession(updated)
                _session.value = updated
                syncAccountProfile(updated)
            }
        }
    }

    private fun nowSec() = System.currentTimeMillis() / 1000L
}
