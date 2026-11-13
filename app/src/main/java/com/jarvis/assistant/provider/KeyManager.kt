package com.jarvis.assistant.provider

/**
 * What the Key Manager screen does with keys. Adds, edits, renames and deletes keys; keeps the key text only in
 * the [KeyVault]; shows only a masked hint; and tests a key against its own provider.
 */
class KeyManager(
    private val pool: ApiKeyPool,
    private val vault: KeyVault,
    private val providers: ProviderConfigManager?
) {
    val canStoreKeys: Boolean get() = vault.available

    fun keys(providerId: String): List<KeyRecord> = pool.listFor(providerId)

    /** Returns a problem description, or null when the key was saved. */
    fun add(
        providerId: String, label: String, keyText: String, priority: Int = 100, modelId: String = "",
        cooldownMs: Long = KeyRecord.DEFAULT_QUOTA_COOLDOWN_MS, resetSchedule: ResetSchedule = ResetSchedule.NONE
    ): String? {
        if (!vault.available) return unavailableMessage()
        val clean = cleanKeyText(keyText)
        validateClean(clean)?.let { return it }
        if (isDuplicate(providerId, clean, exceptId = null)) return "This key is already saved for this provider."
        val id = pool.newId()
        if (!vault.store(id, clean)) return "The key could not be saved to secure storage." + reason()
        pool.upsert(
            KeyRecord(
                id = id, providerId = providerId,
                label = label.trim().ifEmpty { "Key ${pool.listFor(providerId).size + 1}" },
                priority = priority, modelId = modelId.trim(),
                cooldownMs = cooldownMs.coerceIn(KeyRecord.MIN_COOLDOWN_MS, KeyRecord.MAX_COOLDOWN_MS), resetSchedule = resetSchedule
            )
        )
        syncKeyIfApplicable(providerId)
        return null
    }

    /** Swaps in new key text (for example after the provider revoked the old one) and makes the key eligible again. */
    fun replaceKey(id: String, keyText: String): String? {
        val rec = pool.get(id) ?: return "That key no longer exists."
        if (!vault.available) return unavailableMessage()
        val clean = cleanKeyText(keyText)
        validateClean(clean)?.let { return it }
        if (isDuplicate(rec.providerId, clean, exceptId = id)) return "This key is already saved for this provider."
        if (!vault.store(id, clean)) return "The key could not be saved to secure storage." + reason()
        pool.resetCooldown(id)
        syncKeyIfApplicable(rec.providerId)
        return null
    }

    /** Pasted keys often carry invisible characters or edge spaces. A space or line break inside a key is still refused. */
    private fun cleanKeyText(raw: String): String =
        raw.filterNot { it in '\u200B'..'\u200D' || it == '\uFEFF' || it == '\u00A0' }.trim()

    /** Shared checks for a cleaned key: the provider's character rules, plus no space or line break inside the key. */
    private fun validateClean(clean: String): String? =
        ProviderAuth.validateKeyText(clean)
            ?: if (clean.any { it.isWhitespace() }) "The key contains a space or line break. Re-copy it without extra spaces or line breaks." else null

    private fun reason(): String = vault.lastError?.let { " ($it)" }.orEmpty()

    private fun unavailableMessage(): String =
        "Secure storage could not start on this device, so the key was not saved. Keys are never stored as plain text." + reason() +
            " Try again in a few seconds."

    fun remove(id: String): Boolean {
        val rec = pool.get(id)
        vault.delete(id)
        val res = pool.delete(id)
        rec?.let { syncKeyIfApplicable(it.providerId) }
        return res
    }

    fun resetLimitFlags(providerId: String? = null): Int {
        val count = pool.resetLimitFlags(providerId)
        providerId?.let { syncKeyIfApplicable(it) }
        return count
    }

    /** Deletes every key of a provider (used when the provider itself is deleted). */
    fun removeAllFor(providerId: String) = pool.listFor(providerId).forEach { remove(it.id) }

    /** "••••••••a1b2" or "" when there is no key text. Never the whole key. */
    fun mask(id: String): String {
        val secret = vault.load(id) ?: return ""
        val hint = ProviderAuth.hint(secret)
        secret.wipe()
        return hint
    }

    /** Sends a real request with this one key to its own provider. A pass also clears a stale "rejected" mark. */
    suspend fun test(id: String): TestReport? {
        val rec = pool.get(id) ?: return null
        if (rec.providerId == "gemini") {
            val secret = vault.load(id)?.takeIf { !it.isBlank } ?: return null
            val text = secret.reveal()
            secret.wipe()
            val check = try {
                com.jarvis.assistant.network.ApiKeyValidator.validate(text)
            } catch (e: Exception) {
                com.jarvis.assistant.network.ApiKeyCheck.Unreachable(false)
            }
            return if (check is com.jarvis.assistant.network.ApiKeyCheck.Valid) {
                pool.resetCooldown(id)
                val step = TestStep("API Key Verification", StepStatus.PASS, "Google AI Studio verified key")
                TestReport(listOf(step), null, check.latencyMs, discoveryUnsupported = true)
            } else {
                val step = TestStep("API Key Verification", StepStatus.FAIL, "Google AI Studio rejected key: $check")
                TestReport(listOf(step), null, null, discoveryUnsupported = true)
            }
        }
        val manager = providers ?: return null
        val profile = manager.profile(rec.providerId) ?: return null
        val secret = vault.load(id)?.takeIf { !it.isBlank } ?: return null
        val text = secret.reveal()
        secret.wipe()
        val model = rec.modelId.ifBlank { profile.chatModel?.id.orEmpty() }
        val report = manager.testConnection(profile, text, model)
        if (report.ok && (rec.health == KeyHealth.INVALID || rec.health == KeyHealth.BILLING)) pool.resetCooldown(id)
        return report
    }

    fun syncKeyIfApplicable(providerId: String) {
        try {
            val app = com.jarvis.assistant.JarvisApp.instance
            val activeKeyRec = pool.listFor(providerId)
                .filter { it.enabled && it.isUsableAt(System.currentTimeMillis()) }
                .minByOrNull { it.priority }
            val key = if (activeKeyRec != null) {
                val secret = vault.load(activeKeyRec.id)
                if (secret != null && !secret.isBlank) {
                    val k = secret.reveal()
                    secret.wipe()
                    k
                } else null
            } else null

            when (providerId) {
                "gemini" -> if (key != null) app.preferences.apiKey = key
                "groq" -> if (key != null) com.jarvis.assistant.security.AiEngineStore(app).setGroqKey(key)
                "elevenlabs" -> if (key != null) app.preferences.elevenLabsApiKey = key
                else -> if (key != null) {
                    providers?.saveKey(providerId, key) ?: vault.store(providerId, key)
                }
            }
        } catch (_: Exception) {}
    }

    private fun isDuplicate(providerId: String, clean: String, exceptId: String?): Boolean =
        pool.listFor(providerId).any { r ->
            r.id != exceptId && vault.load(r.id)?.let { s -> val same = s.reveal() == clean; s.wipe(); same } == true
        }

    /** A one-line status for the list: health, resting time, and counters. Contains no key text. */
    fun statusLine(rec: KeyRecord, nowMs: Long): String {
        val health = rec.healthAt(nowMs)
        val head = when {
            !rec.enabled -> "Switched off"
            health == KeyHealth.RATE_LIMITED || health == KeyHealth.QUOTA_EXHAUSTED -> {
                val mins = ((rec.unavailableUntil - nowMs).coerceAtLeast(0L) + 59_999L) / 60_000L
                "${health.label} · back in ${if (mins >= 120) "${mins / 60} h" else "$mins min"}"
            }
            else -> health.label
        }
        val u = rec.usage
        return "$head · ${u.requests} request${if (u.requests == 1L) "" else "s"}" +
            (if (u.quotaHits + u.rateLimitHits > 0) " · ${u.quotaHits + u.rateLimitHits} limit hit${if (u.quotaHits + u.rateLimitHits == 1L) "" else "s"}" else "")
    }
}
