package com.jarvis.assistant.provider

/**
 * Data model for the multi-key pool. Nothing here touches Android, the network or secrets: the key text itself
 * lives only in the encrypted [KeyVault], addressed by [KeyRecord.id].
 */

/** Why a key is or is not usable right now. */
enum class KeyHealth(val label: String) {
    AVAILABLE("Ready"),
    RATE_LIMITED("Rate limited (short wait)"),
    QUOTA_EXHAUSTED("Quota used up"),
    /** The provider rejected the key (401/403). Never retried automatically; needs a new key or a manual reset. */
    INVALID("Rejected by provider"),
    /** The provider reports a billing / balance problem. Never retried automatically. */
    BILLING("Billing problem")
}

/**
 * What kind of failure a request had, as far as the key is concerned. Only some of them say something about
 * the key; a provider outage or a dropped connection must never take a good key out of rotation.
 */
enum class KeyFailure(val rotates: Boolean) {
    QUOTA(true),
    RATE_LIMIT(true),
    AUTH(true),
    BILLING(true),
    SERVER(false),
    NETWORK(false),
    OTHER(false)
}

/**
 * When a key's quota is known to refill, for providers that do not say so in their answers.
 * [DAILY] resets at a wall-clock time in a fixed UTC offset; [INTERVAL] resets every [intervalMinutes] counted
 * from [anchorEpochMs].
 */
data class ResetSchedule(
    val type: Type = Type.NONE,
    val hour: Int = 0,
    val minute: Int = 0,
    val utcOffsetMinutes: Int = 0,
    val intervalMinutes: Int = 0,
    val anchorEpochMs: Long = 0L
) {
    enum class Type { NONE, DAILY, INTERVAL }

    /** The next reset strictly after [nowMs], or null when no schedule is set. */
    fun nextResetAfter(nowMs: Long): Long? = when (type) {
        Type.NONE -> null
        Type.DAILY -> {
            val offsetMs = utcOffsetMinutes * 60_000L
            val local = nowMs + offsetMs
            val dayStart = Math.floorDiv(local, DAY_MS) * DAY_MS
            var candidate = dayStart + hour.coerceIn(0, 23) * HOUR_MS + minute.coerceIn(0, 59) * 60_000L
            if (candidate <= local) candidate += DAY_MS
            candidate - offsetMs
        }
        Type.INTERVAL -> {
            if (intervalMinutes <= 0) null
            else {
                val step = intervalMinutes * 60_000L
                val n = Math.floorDiv(nowMs - anchorEpochMs, step) + 1
                anchorEpochMs + n * step
            }
        }
    }

    companion object {
        private const val HOUR_MS = 3_600_000L
        private const val DAY_MS = 86_400_000L
        val NONE = ResetSchedule()
        fun daily(hour: Int, minute: Int, utcOffsetMinutes: Int = 0) =
            ResetSchedule(Type.DAILY, hour = hour, minute = minute, utcOffsetMinutes = utcOffsetMinutes)
        fun every(minutes: Int, anchorEpochMs: Long = 0L) =
            ResetSchedule(Type.INTERVAL, intervalMinutes = minutes, anchorEpochMs = anchorEpochMs)
    }
}

/** Per-key counters. Request counts are made by JARVIS itself; token usage is not read from providers yet. */
data class KeyUsage(
    val requests: Long = 0L,
    val successes: Long = 0L,
    val quotaHits: Long = 0L,
    val rateLimitHits: Long = 0L,
    val authFailures: Long = 0L,
    val lastUsedAt: Long = 0L,
    val lastSuccessAt: Long = 0L
)

data class KeyRecord(
    val id: String,
    /** The [ProviderProfile.id] this key belongs to. */
    val providerId: String,
    val label: String,
    /** Lower number = tried first. Keys with equal priority take turns (least recently used first). */
    val priority: Int = 100,
    val enabled: Boolean = true,
    /** Optional model for this key. Blank means the provider's ChatBox model. */
    val modelId: String = "",
    val resetSchedule: ResetSchedule = ResetSchedule.NONE,
    /** How long to rest this key after a quota hit when neither the provider nor [resetSchedule] says. */
    val cooldownMs: Long = DEFAULT_QUOTA_COOLDOWN_MS,
    val health: KeyHealth = KeyHealth.AVAILABLE,
    /** Epoch millis when a cooling-down key becomes available again. 0 when not cooling down. */
    val unavailableUntil: Long = 0L,
    val lastFailureNote: String = "",
    val usage: KeyUsage = KeyUsage(),
    val createdAt: Long = 0L,
    /** Flagged true when quota (429/402/403/RESOURCE_EXHAUSTED) is hit on this key. */
    val isRateLimited: Boolean = false,
    /** Timestamp when the rate limit was detected. */
    val limitedAtTimestamp: Long = 0L
) {
    /** Health as of [nowMs]: a finished cooldown counts as available again, even right after an app restart. */
    fun isRateLimitedAt(nowMs: Long): Boolean {
        if (!isRateLimited) return false
        if (unavailableUntil > 0L && nowMs >= unavailableUntil) return false
        return true
    }

    /** Health as of [nowMs]: a finished cooldown counts as available again, even right after an app restart. */
    fun healthAt(nowMs: Long): KeyHealth = when {
        health == KeyHealth.RATE_LIMITED || health == KeyHealth.QUOTA_EXHAUSTED || isRateLimited -> {
            if (unavailableUntil > 0L && nowMs >= unavailableUntil) KeyHealth.AVAILABLE else health
        }
        else -> health
    }

    /** Returns true if key is enabled, not rate-limited, and its health is AVAILABLE as of [nowMs]. */
    fun isUsableAt(nowMs: Long): Boolean {
        if (!enabled) return false
        if (isRateLimitedAt(nowMs)) return false
        return healthAt(nowMs) == KeyHealth.AVAILABLE
    }

    companion object {
        const val DEFAULT_QUOTA_COOLDOWN_MS = 60L * 60_000L
        const val DEFAULT_RATE_LIMIT_COOLDOWN_MS = 30_000L
        const val MIN_COOLDOWN_MS = 1_000L
        const val MAX_COOLDOWN_MS = 7L * 24 * 3_600_000L
    }
}
