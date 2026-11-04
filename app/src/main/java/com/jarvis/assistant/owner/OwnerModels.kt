package com.jarvis.assistant.owner

/** Result of a dashboard backend call. Messages are safe to show; they never contain secrets. */
sealed class OwnerResult<out T> {
    data class Ok<out T>(val value: T) : OwnerResult<T>()
    /** Wrong password, or not permitted. Intentionally generic. */
    object Denied : OwnerResult<Nothing>()
    data class Locked(val retryAfterSec: Int) : OwnerResult<Nothing>()
    /** The dashboard session is missing, expired or revoked; the password must be entered again. */
    object SessionExpired : OwnerResult<Nothing>()
    object NotSignedIn : OwnerResult<Nothing>()
    object Unavailable : OwnerResult<Nothing>()
    object Network : OwnerResult<Nothing>()
    object Error : OwnerResult<Nothing>()
}

data class OverviewData(
    val totalAccounts: Long,
    val accountsActive30d: Long,
    val totalDevices: Long,
    val devicesActive24h: Long,
    val devicesActive7d: Long,
    val signins24h: Long,
    val signins7d: Long,
    val appOpens24h: Long,
    val distinctIps30d: Long,
    val freeUsers: Long,
    val proUsers: Long?,
    val proNote: String,
    val presenceNote: String,
    val ipRetentionDays: Int,
    val recentSignins: List<OwnerRow>,
    val recentActivity: List<OwnerRow>
)

/** One generic list row, so devices / IPs / events share a single adapter. */
data class OwnerRow(
    val title: String,
    val line2: String,
    val line3: String,
    val badge: String = ""
)

data class OwnerPage(
    val rows: List<OwnerRow>,
    val total: Int,
    val page: Int,
    val pageSize: Int,
    val note: String = ""
) {
    val pageCount: Int get() = if (total == 0) 1 else (total + pageSize - 1) / pageSize
}

data class VerifiedSession(val token: String, val expiresAtMs: Long)
