package com.jarvis.assistant.owner

/**
 * The dashboard access token lives in memory only. It is never written to preferences, files or logs,
 * so killing the app or logging out discards it. The server enforces the real expiry; [expiresAtMs]
 * only lets the UI close early.
 */
object OwnerSession {
    @Volatile private var token: String? = null
    @Volatile private var expiresAtMs: Long = 0L

    fun start(newToken: String, expiresAtEpochMs: Long) {
        token = newToken
        expiresAtMs = expiresAtEpochMs
    }

    /** The token if one is held and not past its expiry, else null. */
    fun token(nowMs: Long = System.currentTimeMillis()): String? =
        token?.takeIf { nowMs < expiresAtMs }

    val hasSession: Boolean get() = token() != null

    fun expiresAtMs(): Long = expiresAtMs

    /** Local invalidation only. Use [OwnerDashboardClient.endSession] to also revoke it server-side. */
    fun clear() {
        token = null
        expiresAtMs = 0L
    }
}
