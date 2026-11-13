package com.jarvis.assistant.provider

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.util.UUID

/**
 * The saved set of API keys and what each one is currently good for. Holds metadata only (label, priority,
 * cooldown, counters); the key text is in the [KeyVault]. State is written to [store] after every change, so a
 * key that hit its limit stays resting across app restarts, and comes back by itself once its time has passed.
 *
 * All methods are safe to call from several threads. Selection and bookkeeping happen under one lock, so two
 * requests running at once cannot both be handed the same "least recently used" key.
 */
class ApiKeyPool(
    private val store: KeyValueStore,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val lock = Any()

    fun newId(): String = "pk_" + UUID.randomUUID().toString().replace("-", "").take(16)

    fun list(): List<KeyRecord> = synchronized(lock) { readAll() }

    fun listFor(providerId: String): List<KeyRecord> = list().filter { it.providerId == providerId }

    fun get(id: String): KeyRecord? = list().firstOrNull { it.id == id }

    fun hasKeys(providerId: String): Boolean = listFor(providerId).isNotEmpty()

    fun upsert(record: KeyRecord) = synchronized(lock) {
        val all = readAll().toMutableList()
        val index = all.indexOfFirst { it.id == record.id }
        val stored = if (record.createdAt == 0L) record.copy(createdAt = clock()) else record
        if (index >= 0) all[index] = stored else all.add(stored)
        writeAll(all)
    }

    fun delete(id: String): Boolean = synchronized(lock) {
        val all = readAll()
        val kept = all.filter { it.id != id }
        if (kept.size == all.size) return false
        writeAll(kept)
        true
    }

    fun rename(id: String, label: String): Boolean = update(id) { it.copy(label = label.trim().ifEmpty { it.label }) }

    fun setEnabled(id: String, enabled: Boolean): Boolean = update(id) { it.copy(enabled = enabled) }

    fun setPriority(id: String, priority: Int): Boolean = update(id) { it.copy(priority = priority) }

    /** The user pressed "reset cooldown" (or replaced the key): the key is eligible again right now. */
    fun resetCooldown(id: String): Boolean = update(id) {
        it.copy(
            health = KeyHealth.AVAILABLE,
            unavailableUntil = 0L,
            lastFailureNote = "",
            isRateLimited = false,
            limitedAtTimestamp = 0L
        )
    }

    /**
     * Resets rate-limit and quota exhausted flags for all keys under [providerId] (or globally when null).
     * Keys immediately become available for reuse on demand.
     */
    fun resetLimitFlags(providerId: String? = null): Int = synchronized(lock) {
        val all = readAll().toMutableList()
        var count = 0
        all.indices.forEach { i ->
            val rec = all[i]
            if (providerId == null || rec.providerId == providerId) {
                if (rec.isRateLimited || rec.health == KeyHealth.RATE_LIMITED || rec.health == KeyHealth.QUOTA_EXHAUSTED) {
                    all[i] = rec.copy(
                        health = KeyHealth.AVAILABLE,
                        unavailableUntil = 0L,
                        lastFailureNote = "",
                        isRateLimited = false,
                        limitedAtTimestamp = 0L
                    )
                    count++
                }
            }
        }
        if (count > 0) writeAll(all)
        count
    }

    /**
     * Chooses the next key for [providerId] and records the use. Skips disabled keys, keys that are resting or
     * rejected, and anything in [exclude] (keys already tried for this request, which prevents switching loops).
     * Returns null when nothing is usable.
     */
    fun acquire(providerId: String, exclude: Set<String> = emptySet()): KeyRecord? = synchronized(lock) {
        val now = clock()
        val all = readAll().toMutableList()
        val pick = all.filter { it.providerId == providerId && it.id !in exclude && it.isUsableAt(now) }
            .sortedWith(compareBy<KeyRecord> { it.priority }.thenBy { it.usage.lastUsedAt }.thenBy { it.createdAt }.thenBy { it.id })
            .firstOrNull() ?: return null
        val index = all.indexOfFirst { it.id == pick.id }
        val used = pick.copy(
            health = KeyHealth.AVAILABLE,
            unavailableUntil = 0L,
            isRateLimited = false,
            limitedAtTimestamp = 0L,
            usage = pick.usage.copy(requests = pick.usage.requests + 1, lastUsedAt = now)
        )
        all[index] = used
        writeAll(all)
        used
    }

    fun reportSuccess(id: String) {
        update(id) {
            val now = clock()
            it.copy(
                health = KeyHealth.AVAILABLE, unavailableUntil = 0L, lastFailureNote = "",
                isRateLimited = false, limitedAtTimestamp = 0L,
                usage = it.usage.copy(successes = it.usage.successes + 1, lastSuccessAt = now)
            )
        }
    }

    /**
     * Records a failure for the key. Quota and rate-limit failures rest the key until the provider's `Retry-After`,
     * the key's reset schedule or its configured cooldown (in that order of preference). Authentication and
     * billing failures take the key out until the user fixes it. Server and network failures leave the key alone.
     */
    fun reportFailure(id: String, failure: KeyFailure, retryAfterMs: Long?, note: String = ""): KeyRecord? = synchronized(lock) {
        val all = readAll().toMutableList()
        val index = all.indexOfFirst { it.id == id }
        if (index < 0) return null
        val now = clock()
        val rec = all[index]
        val shortNote = note.replace(Regex("\\s+"), " ").trim().take(160)
        val updated = when (failure) {
            KeyFailure.QUOTA -> rec.copy(
                health = KeyHealth.QUOTA_EXHAUSTED,
                isRateLimited = true,
                limitedAtTimestamp = now,
                unavailableUntil = quotaUntil(rec, retryAfterMs, now),
                lastFailureNote = shortNote,
                usage = rec.usage.copy(quotaHits = rec.usage.quotaHits + 1)
            )
            KeyFailure.RATE_LIMIT -> rec.copy(
                health = KeyHealth.RATE_LIMITED,
                isRateLimited = true,
                limitedAtTimestamp = now,
                unavailableUntil = now + (retryAfterMs ?: KeyRecord.DEFAULT_RATE_LIMIT_COOLDOWN_MS)
                    .coerceIn(KeyRecord.MIN_COOLDOWN_MS, KeyRecord.MAX_COOLDOWN_MS),
                lastFailureNote = shortNote,
                usage = rec.usage.copy(rateLimitHits = rec.usage.rateLimitHits + 1)
            )
            KeyFailure.AUTH -> rec.copy(
                health = KeyHealth.INVALID, unavailableUntil = 0L, lastFailureNote = shortNote,
                usage = rec.usage.copy(authFailures = rec.usage.authFailures + 1)
            )
            KeyFailure.BILLING -> rec.copy(health = KeyHealth.BILLING, unavailableUntil = 0L, lastFailureNote = shortNote)
            KeyFailure.SERVER, KeyFailure.NETWORK, KeyFailure.OTHER -> rec
        }
        all[index] = updated
        writeAll(all)
        updated
    }

    /** Earliest moment any resting key for [providerId] becomes usable again, or null if none will on its own. */
    fun earliestReturn(providerId: String): Long? {
        val now = clock()
        return listFor(providerId)
            .filter { it.enabled && it.healthAt(now).let { h -> h == KeyHealth.RATE_LIMITED || h == KeyHealth.QUOTA_EXHAUSTED } }
            .minOfOrNull { it.unavailableUntil }
    }

    private fun quotaUntil(rec: KeyRecord, retryAfterMs: Long?, now: Long): Long {
        val max = KeyRecord.MAX_COOLDOWN_MS
        if (retryAfterMs != null && retryAfterMs > 0) return now + retryAfterMs.coerceAtMost(max)
        rec.resetSchedule.nextResetAfter(now)?.let { return it.coerceAtMost(now + max) }
        return now + rec.cooldownMs.coerceIn(KeyRecord.MIN_COOLDOWN_MS, max)
    }

    private fun update(id: String, change: (KeyRecord) -> KeyRecord): Boolean = synchronized(lock) {
        val all = readAll().toMutableList()
        val index = all.indexOfFirst { it.id == id }
        if (index < 0) return false
        all[index] = change(all[index])
        writeAll(all)
        true
    }

    private fun readAll(): List<KeyRecord> {
        val now = clock()
        return KeyPoolCodec.decode(store.getString(STORE_KEY))
            // A clock that moved backwards must not leave a key stuck for longer than the maximum cooldown.
            .map { if (it.unavailableUntil > now + KeyRecord.MAX_COOLDOWN_MS) it.copy(unavailableUntil = now + KeyRecord.MAX_COOLDOWN_MS) else it }
    }

    private fun writeAll(records: List<KeyRecord>) {
        store.putString(STORE_KEY, KeyPoolCodec.encode(records))
    }

    companion object {
        const val STORE_KEY = "key_pool_json"
    }
}

/** JSON for the key pool. Tree API only (no reflection), so release builds with R8 need no keep rules. No key text is ever written. */
object KeyPoolCodec {
    private const val VERSION = 1

    fun encode(records: List<KeyRecord>): String {
        val root = JsonObject()
        root.addProperty("version", VERSION)
        root.add("keys", JsonArray().apply { records.forEach { add(encodeRecord(it)) } })
        return root.toString()
    }

    fun decode(json: String?): List<KeyRecord> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val root = JsonParser.parseString(json)
            if (!root.isJsonObject) return emptyList()
            val arr = root.asJsonObject.get("keys")
            if (arr == null || !arr.isJsonArray) return emptyList()
            arr.asJsonArray.mapNotNull { runCatching { decodeRecord(it) }.getOrNull() }
        } catch (e: JsonParseException) {
            emptyList()
        }
    }

    private fun encodeRecord(r: KeyRecord) = JsonObject().apply {
        addProperty("id", r.id)
        addProperty("providerId", r.providerId)
        addProperty("label", r.label)
        addProperty("priority", r.priority)
        addProperty("enabled", r.enabled)
        addProperty("modelId", r.modelId)
        addProperty("cooldownMs", r.cooldownMs)
        addProperty("health", r.health.name)
        addProperty("unavailableUntil", r.unavailableUntil)
        addProperty("lastFailureNote", r.lastFailureNote)
        addProperty("createdAt", r.createdAt)
        addProperty("isRateLimited", r.isRateLimited)
        addProperty("limitedAtTimestamp", r.limitedAtTimestamp)
        add("schedule", JsonObject().apply {
            addProperty("type", r.resetSchedule.type.name)
            addProperty("hour", r.resetSchedule.hour)
            addProperty("minute", r.resetSchedule.minute)
            addProperty("offset", r.resetSchedule.utcOffsetMinutes)
            addProperty("interval", r.resetSchedule.intervalMinutes)
            addProperty("anchor", r.resetSchedule.anchorEpochMs)
        })
        add("usage", JsonObject().apply {
            addProperty("requests", r.usage.requests)
            addProperty("successes", r.usage.successes)
            addProperty("quotaHits", r.usage.quotaHits)
            addProperty("rateLimitHits", r.usage.rateLimitHits)
            addProperty("authFailures", r.usage.authFailures)
            addProperty("lastUsedAt", r.usage.lastUsedAt)
            addProperty("lastSuccessAt", r.usage.lastSuccessAt)
        })
    }

    private fun decodeRecord(e: JsonElement): KeyRecord {
        val o = e.asJsonObject
        val schedule = o.obj("schedule")
        val usage = o.obj("usage")
        return KeyRecord(
            id = o.str("id").ifBlank { throw IllegalArgumentException("no id") },
            providerId = o.str("providerId"),
            label = o.str("label"),
            priority = o.long("priority", 100).toInt(),
            enabled = o.bool("enabled", true),
            modelId = o.str("modelId"),
            resetSchedule = ResetSchedule(
                type = enumOr(schedule?.str("type"), ResetSchedule.Type.NONE),
                hour = schedule?.long("hour", 0)?.toInt() ?: 0,
                minute = schedule?.long("minute", 0)?.toInt() ?: 0,
                utcOffsetMinutes = schedule?.long("offset", 0)?.toInt() ?: 0,
                intervalMinutes = schedule?.long("interval", 0)?.toInt() ?: 0,
                anchorEpochMs = schedule?.long("anchor", 0) ?: 0L
            ),
            cooldownMs = o.long("cooldownMs", KeyRecord.DEFAULT_QUOTA_COOLDOWN_MS),
            health = enumOr(o.str("health"), KeyHealth.AVAILABLE),
            unavailableUntil = o.long("unavailableUntil", 0),
            lastFailureNote = o.str("lastFailureNote"),
            usage = KeyUsage(
                requests = usage?.long("requests", 0) ?: 0L,
                successes = usage?.long("successes", 0) ?: 0L,
                quotaHits = usage?.long("quotaHits", 0) ?: 0L,
                rateLimitHits = usage?.long("rateLimitHits", 0) ?: 0L,
                authFailures = usage?.long("authFailures", 0) ?: 0L,
                lastUsedAt = usage?.long("lastUsedAt", 0) ?: 0L,
                lastSuccessAt = usage?.long("lastSuccessAt", 0) ?: 0L
            ),
            createdAt = o.long("createdAt", 0),
            isRateLimited = o.bool("isRateLimited", false),
            limitedAtTimestamp = o.long("limitedAtTimestamp", 0)
        )
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: fallback

    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    private fun JsonObject.long(key: String, default: Long): Long =
        get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asLong }.getOrNull() } ?: default

    private fun JsonObject.bool(key: String, default: Boolean): Boolean =
        get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asBoolean }.getOrNull() } ?: default

    private fun JsonObject.obj(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject
}
