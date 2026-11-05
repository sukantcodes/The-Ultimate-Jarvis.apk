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
    val badge: String = "",
    /** ARGB colour for the badge and the row's accent; 0 keeps the theme accent. */
    val tone: Int = 0,
    /** Full values (complete IDs, timestamps) shown when the row is tapped. */
    val detail: String = ""
)

/** Palette shared by the dashboard so every card, badge and chart series has a stable colour. */
object OwnerPalette {
    val CYAN = 0xFF22D3EE.toInt()
    val VIOLET = 0xFFA78BFA.toInt()
    val GREEN = 0xFF34D399.toInt()
    val AMBER = 0xFFFBBF24.toInt()
    val PINK = 0xFFF472B6.toInt()
    val BLUE = 0xFF60A5FA.toInt()
    val ORANGE = 0xFFFB923C.toInt()
    val RED = 0xFFF87171.toInt()
    val GREY = 0xFF94A3B8.toInt()
    val CYCLE = intArrayOf(CYAN, VIOLET, GREEN, AMBER, PINK, BLUE, ORANGE, RED)
}

data class DayPoint(val day: String, val signIns: Int, val appOpens: Int, val newDevices: Int)

data class LabelCount(val label: String, val count: Int)

data class TrendsData(
    val days: Int,
    val daily: List<DayPoint>,
    val models: List<LabelCount>,
    val androidVersions: List<LabelCount>,
    val appVersions: List<LabelCount>,
    val providers: List<LabelCount>,
    val lockedNow: Long,
    val failedAttempts24h: Long,
    val activeDashboardSessions: Long
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
