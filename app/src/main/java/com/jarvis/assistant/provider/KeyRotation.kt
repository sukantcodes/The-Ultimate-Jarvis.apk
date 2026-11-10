package com.jarvis.assistant.provider

/** One key handed out for one request. The caller wipes [secret] when the request is over. */
class KeyLease(
    val keyId: String,
    val label: String,
    /** Model assigned to this key, or blank to use the provider's ChatBox model. */
    val modelId: String,
    val secret: ApiKeySecret
) {
    override fun toString(): String = "KeyLease($label)"
}

/** What the chat engine needs from the key pool. Small on purpose so the engine can be tested with a fake. */
interface KeyRotationSource {
    /** True when the user has saved at least one key for this provider in the key manager. */
    fun hasKeys(profileId: String): Boolean

    /** The next usable key not in [exclude], or null when none is. */
    fun lease(profileId: String, exclude: Set<String>): KeyLease?

    fun reportSuccess(keyId: String)

    /** Records why a request with this key failed and tells the caller what kind of failure it was. */
    fun reportFailure(keyId: String, error: ProviderError): KeyFailure

    /** Plain-language reason nothing is usable (all resting, rejected or switched off), with the earliest return time. */
    fun unavailableSummary(profileId: String): String
}

class PoolKeyRotation(
    private val pool: ApiKeyPool,
    private val vault: KeyVault,
    private val clock: () -> Long = System::currentTimeMillis
) : KeyRotationSource {

    override fun hasKeys(profileId: String): Boolean = pool.hasKeys(profileId)

    override fun lease(profileId: String, exclude: Set<String>): KeyLease? {
        val skipped = HashSet(exclude)
        while (true) {
            val rec = pool.acquire(profileId, skipped) ?: return null
            val secret = vault.load(rec.id)?.takeIf { !it.isBlank }
            if (secret != null) return KeyLease(rec.id, rec.label, rec.modelId, secret)
            // The key text is gone (secure storage was cleared). Treat like a rejected key rather than looping on it.
            pool.reportFailure(rec.id, KeyFailure.AUTH, null, "The key text is missing from secure storage. Re-enter this key.")
            skipped += rec.id
        }
    }

    override fun reportSuccess(keyId: String) = pool.reportSuccess(keyId)

    override fun reportFailure(keyId: String, error: ProviderError): KeyFailure {
        val failure = KeyFailureClassifier.classify(error)
        pool.reportFailure(keyId, failure, error.retryAfterMs, error.message)
        return failure
    }

    override fun unavailableSummary(profileId: String): String {
        val now = clock()
        val keys = pool.listFor(profileId)
        if (keys.isEmpty()) return "No API keys are saved for this provider."
        val enabled = keys.filter { it.enabled }
        if (enabled.isEmpty()) return "All ${keys.size} saved keys are switched off."
        val resting = enabled.filter { it.healthAt(now).let { h -> h == KeyHealth.RATE_LIMITED || h == KeyHealth.QUOTA_EXHAUSTED } }
        val rejected = enabled.count { it.healthAt(now) == KeyHealth.INVALID }
        val billing = enabled.count { it.healthAt(now) == KeyHealth.BILLING }
        val parts = ArrayList<String>()
        if (resting.isNotEmpty()) {
            val minutes = ((resting.minOf { it.unavailableUntil } - now).coerceAtLeast(0L) + 59_999L) / 60_000L
            parts += "${resting.size} used up or rate limited (the first returns in about ${if (minutes <= 1) "a minute" else "$minutes minutes"})"
        }
        if (rejected > 0) parts += "$rejected rejected by the provider"
        if (billing > 0) parts += "$billing with a billing problem"
        return "Every key for this provider is unavailable: " + parts.joinToString(", ") + ". Open the key manager to add a key or reset a cooldown."
    }
}
