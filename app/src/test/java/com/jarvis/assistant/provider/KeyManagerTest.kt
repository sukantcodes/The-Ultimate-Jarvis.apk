package com.jarvis.assistant.provider

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyManagerTest {
    private var now = 5_000_000L
    private val store = MemoryStore()
    private val vault = MemoryVault()
    private val pool = ApiKeyPool(store) { now }
    private val transport = FakeTransport()
    private val providers = ProviderConfigManager(ProviderRepository(store, vault), transport, clock = { now })
    private val keys = KeyManager(pool, vault, providers)

    @Test fun keys_can_be_added_renamed_edited_disabled_and_deleted() {
        assertNull(keys.add("p1", "Main", "sk-main-0000000000", priority = 1))
        assertNull(keys.add("p1", "", "sk-second-0000000000"))
        val list = keys.keys("p1")
        assertEquals(2, list.size)
        assertEquals("Key 2", list[1].label)
        pool.rename(list[1].id, "Backup"); pool.setPriority(list[1].id, 5); pool.setEnabled(list[1].id, false)
        val b = pool.get(list[1].id)!!
        assertEquals("Backup", b.label); assertEquals(5, b.priority); assertFalse(b.enabled)
        assertTrue(keys.remove(list[0].id))
        assertNull(vault.keys[list[0].id])
        assertEquals(1, keys.keys("p1").size)
    }

    @Test fun duplicates_and_bad_text_are_refused_with_a_reason() {
        assertNull(keys.add("p1", "A", "sk-same-0000000000"))
        assertEquals("This key is already saved for this provider.", keys.add("p1", "B", "  sk-same-0000000000 "))
        assertNull(keys.add("p2", "C", "sk-same-0000000000")) // same text under another provider is a different key
        assertNotNull(keys.add("p1", "D", "bad\nkey"))
        assertNotNull(keys.add("p1", "E", ""))
        assertEquals(1, keys.keys("p1").size)
    }

    @Test fun invisible_paste_characters_are_stripped_but_inner_breaks_are_refused() {
        assertNull(keys.add("p1", "Pasted", "\u200Bsk-pasted-0000000000\uFEFF\u00A0"))
        assertEquals("sk-pasted-0000000000", vault.keys[keys.keys("p1")[0].id])
        assertNotNull(keys.add("p1", "Broken", "sk-bro ken-0000000000"))
    }

    @Test fun nothing_is_saved_without_secure_storage() {
        val locked = KeyManager(pool, MemoryVault(available = false), providers)
        assertTrue(locked.add("p1", "A", "sk-aaaa-0000000000")!!.contains("never stored as plain text"))
        assertTrue(pool.list().isEmpty())
    }

    @Test fun only_a_masked_hint_is_ever_shown() {
        keys.add("p1", "A", "sk-SECRETKEY-abcd1234")
        val id = keys.keys("p1")[0].id
        assertEquals("••••••••1234", keys.mask(id))
        assertFalse(keys.statusLine(pool.get(id)!!, now).contains("SECRETKEY"))
        assertTrue(store.map.values.none { it.contains("SECRETKEY") })
    }

    @Test fun replacing_a_rejected_key_makes_it_usable_again() {
        keys.add("p1", "A", "sk-old-00000000000")
        val id = keys.keys("p1")[0].id
        pool.reportFailure(id, KeyFailure.AUTH, null)
        assertNull(pool.acquire("p1"))
        assertNull(keys.replaceKey(id, "sk-new-00000000000"))
        assertEquals("sk-new-00000000000", vault.keys[id])
        assertEquals(id, pool.acquire("p1")!!.id)
    }

    @Test fun a_cooldown_can_be_reset_by_hand() {
        keys.add("p1", "A", "sk-aaaa-0000000000")
        val id = keys.keys("p1")[0].id
        pool.reportFailure(id, KeyFailure.QUOTA, 3_600_000L)
        assertNull(pool.acquire("p1"))
        pool.resetCooldown(id)
        assertEquals(id, pool.acquire("p1")!!.id)
    }

    @Test fun the_status_line_shows_time_left_and_limit_hits() {
        keys.add("p1", "A", "sk-aaaa-0000000000")
        val id = keys.keys("p1")[0].id
        pool.reportFailure(id, KeyFailure.QUOTA, 90 * 60_000L)
        val line = keys.statusLine(pool.get(id)!!, now)
        assertTrue(line, line.contains("Quota used up") && line.contains("90 min") && line.contains("1 limit hit"))
        pool.setEnabled(id, false)
        assertTrue(keys.statusLine(pool.get(id)!!, now).startsWith("Switched off"))
    }

    @Test fun testing_a_key_uses_that_key_against_its_own_provider_and_a_pass_clears_a_stale_rejection() {
        val p = Fixtures.profile(id = "p1", models = listOf(ModelInfo("m1")))
        assertNull(providers.save(p, "provider-level-key-000"))
        keys.add("p1", "A", "sk-testing-0000000000")
        val id = keys.keys("p1")[0].id
        pool.reportFailure(id, KeyFailure.AUTH, null)
        transport.onUrl("/models", 200, Fixtures.openAiModels("m1"))
        transport.onUrl("/chat/completions", 200, Fixtures.completion("ok"))
        val report = runBlocking { keys.test(id) }!!
        assertTrue(report.summary, report.ok)
        assertTrue(transport.requests.all { r -> r.headers.values.any { it.contains("sk-testing-0000000000") } })
        assertEquals(KeyHealth.AVAILABLE, pool.get(id)!!.health)
    }

    @Test fun deleting_a_provider_s_keys_removes_their_secrets() {
        keys.add("p1", "A", "sk-aaaa-0000000000"); keys.add("p1", "B", "sk-bbbb-0000000000"); keys.add("p2", "C", "sk-cccc-0000000000")
        keys.removeAllFor("p1")
        assertTrue(keys.keys("p1").isEmpty())
        assertEquals(1, vault.keys.size)
    }

    @Test fun reset_limit_flags_clears_rate_limited_status() {
        keys.add("p1", "Key1", "sk-p1-1111111111")
        keys.add("p1", "Key2", "sk-p1-2222222222")
        keys.add("p2", "Key3", "sk-p2-3333333333")
        val k1 = keys.keys("p1")[0].id
        val k2 = keys.keys("p1")[1].id
        val k3 = keys.keys("p2")[0].id

        pool.reportFailure(k1, KeyFailure.RATE_LIMIT, 60_000L)
        pool.reportFailure(k2, KeyFailure.QUOTA, 60_000L)
        pool.reportFailure(k3, KeyFailure.RATE_LIMIT, 60_000L)

        assertTrue(pool.get(k1)!!.isRateLimited)
        assertTrue(pool.get(k2)!!.isRateLimited)
        assertTrue(pool.get(k3)!!.isRateLimited)

        // Reset only p1
        val resetP1Count = keys.resetLimitFlags("p1")
        assertEquals(2, resetP1Count)
        assertFalse(pool.get(k1)!!.isRateLimited)
        assertFalse(pool.get(k2)!!.isRateLimited)
        assertTrue(pool.get(k3)!!.isRateLimited)

        // Reset all
        val resetAllCount = keys.resetLimitFlags(null)
        assertEquals(1, resetAllCount)
        assertFalse(pool.get(k3)!!.isRateLimited)
    }

    @Test fun automatic_cooldown_reset_after_expiration() {
        keys.add("p1", "Key1", "sk-p1-1111111111")
        val k1 = keys.keys("p1")[0].id
        pool.reportFailure(k1, KeyFailure.RATE_LIMIT, 5_000L)
        val rec = pool.get(k1)!!
        assertTrue(rec.isRateLimited)
        assertTrue(rec.isRateLimitedAt(now + 1_000L))
        assertFalse(rec.isUsableAt(now + 1_000L))

        // After cooldown expires
        assertFalse(rec.isRateLimitedAt(now + 6_000L))
        assertTrue(rec.isUsableAt(now + 6_000L))
        assertEquals(KeyHealth.AVAILABLE, rec.healthAt(now + 6_000L))

        // Acquire after expiration automatically clears rate limit flag on leased and stored record
        now += 6_000L
        val leased = pool.acquire("p1")
        assertNotNull(leased)
        assertFalse(leased!!.isRateLimited)
        assertFalse(pool.get(k1)!!.isRateLimited)
    }

    @Test fun multi_provider_support_and_sync() {
        assertNull(keys.add("gemini", "Gemini Key 1", "AIzaSyGeminiKey12345"))
        assertNull(keys.add("gemini", "Gemini Key 2", "AIzaSyGeminiKey67890", priority = 50))
        assertNull(keys.add("groq", "Groq Key 1", "gsk_groqKey1111111111"))
        assertNull(keys.add("elevenlabs", "ElevenLabs Key 1", "eleven_key_1111111111"))
        assertNull(keys.add("custom_provider_1", "Custom Key 1", "sk-custom-1111111111"))

        assertEquals(2, keys.keys("gemini").size)
        assertEquals(1, keys.keys("groq").size)
        assertEquals(1, keys.keys("elevenlabs").size)
        assertEquals(1, keys.keys("custom_provider_1").size)

        // Lower priority is acquired first
        val gLease = pool.acquire("gemini")
        assertNotNull(gLease)
        assertEquals("Gemini Key 2", gLease!!.label)
    }
}
