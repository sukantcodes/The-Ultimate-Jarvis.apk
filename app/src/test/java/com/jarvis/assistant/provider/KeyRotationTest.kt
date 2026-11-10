package com.jarvis.assistant.provider

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyRotationTest {

    private var now = 1_000_000L
    private val store = MemoryStore()
    private val pool = ApiKeyPool(store) { now }
    private val vault = MemoryVault()
    private val rotation = PoolKeyRotation(pool, vault) { now }

    private fun addKey(id: String, secret: String, priority: Int = 100, provider: String = "p1", cooldownMs: Long = 3_600_000L) {
        pool.upsert(KeyRecord(id = id, providerId = provider, label = "Key $id", priority = priority, cooldownMs = cooldownMs))
        vault.store(id, secret)
    }

    private fun err(code: Int, body: String = "", kind: ProviderErrorKind, retryAfterMs: Long? = null) =
        ProviderError(kind, "x" + if (body.isEmpty()) "" else " Provider said: $body", code, retryAfterMs)

    // ---- classification --------------------------------------------------------------------------------

    @Test fun quota_rate_limit_auth_billing_and_server_errors_are_told_apart() {
        assertEquals(KeyFailure.QUOTA, KeyFailureClassifier.classify(err(429, "You exceeded your current quota", ProviderErrorKind.RATE_LIMITED)))
        assertEquals(KeyFailure.QUOTA, KeyFailureClassifier.classify(err(429, "RESOURCE_EXHAUSTED", ProviderErrorKind.RATE_LIMITED)))
        assertEquals(KeyFailure.RATE_LIMIT, KeyFailureClassifier.classify(err(429, "Too many requests, slow down", ProviderErrorKind.RATE_LIMITED)))
        assertEquals(KeyFailure.RATE_LIMIT, KeyFailureClassifier.classify(err(429, "", ProviderErrorKind.RATE_LIMITED)))
        assertEquals(KeyFailure.AUTH, KeyFailureClassifier.classify(err(401, "bad", ProviderErrorKind.AUTH_FAILED)))
        assertEquals(KeyFailure.AUTH, KeyFailureClassifier.classify(err(403, "forbidden", ProviderErrorKind.KEY_REJECTED)))
        assertEquals(KeyFailure.BILLING, KeyFailureClassifier.classify(err(402, "pay up", ProviderErrorKind.BAD_REQUEST)))
        assertEquals(KeyFailure.BILLING, KeyFailureClassifier.classify(err(403, "Check your plan and billing details", ProviderErrorKind.KEY_REJECTED)))
        assertEquals(KeyFailure.SERVER, KeyFailureClassifier.classify(err(503, "", ProviderErrorKind.UNAVAILABLE)))
        assertEquals(KeyFailure.NETWORK, KeyFailureClassifier.classify(ProviderError(ProviderErrorKind.TIMEOUT, "t")))
        assertEquals(KeyFailure.NETWORK, KeyFailureClassifier.classify(ProviderError(ProviderErrorKind.STREAM_INTERRUPTED, "t")))
        assertEquals(KeyFailure.OTHER, KeyFailureClassifier.classify(err(400, "bad json", ProviderErrorKind.BAD_REQUEST)))
    }

    @Test fun server_and_network_failures_never_rotate_but_key_problems_do() {
        assertFalse(KeyFailure.SERVER.rotates)
        assertFalse(KeyFailure.NETWORK.rotates)
        assertFalse(KeyFailure.OTHER.rotates)
        assertTrue(KeyFailure.QUOTA.rotates && KeyFailure.RATE_LIMIT.rotates && KeyFailure.AUTH.rotates && KeyFailure.BILLING.rotates)
    }

    @Test fun retry_after_accepts_seconds_and_http_dates_and_ignores_junk() {
        assertEquals(30_000L, RetryAfter.parse("30", 0))
        assertEquals(1_500L, RetryAfter.parse("1.5", 0))
        assertNull(RetryAfter.parse("-5", 0))
        assertNull(RetryAfter.parse("soon", 0))
        assertNull(RetryAfter.parse(null, 0))
        assertEquals(60_000L, RetryAfter.parse("Wed, 21 Oct 2015 07:29:00 GMT", java.time.Instant.parse("2015-10-21T07:28:00Z").toEpochMilli()))
    }

    // ---- reset schedules -------------------------------------------------------------------------------

    @Test fun daily_schedule_finds_the_next_reset_in_the_configured_offset() {
        // 2024-01-01T10:00:00Z; reset daily at 05:30 in UTC+05:30 == 00:00Z.
        val t = java.time.Instant.parse("2024-01-01T10:00:00Z").toEpochMilli()
        val next = ResetSchedule.daily(5, 30, 330).nextResetAfter(t)!!
        assertEquals(java.time.Instant.parse("2024-01-02T00:00:00Z").toEpochMilli(), next)
        // Exactly at the reset moment the next one is tomorrow, never "now".
        assertEquals(next + 86_400_000L, ResetSchedule.daily(5, 30, 330).nextResetAfter(next))
        assertNull(ResetSchedule.NONE.nextResetAfter(t))
    }

    @Test fun interval_schedule_counts_from_its_anchor() {
        val s = ResetSchedule.every(60, anchorEpochMs = 0)
        assertEquals(3_600_000L, s.nextResetAfter(1))
        assertEquals(7_200_000L, s.nextResetAfter(3_600_000L))
        assertNull(ResetSchedule.every(0).nextResetAfter(5))
    }

    // ---- pool selection --------------------------------------------------------------------------------

    @Test fun keys_are_used_in_priority_order_and_equal_priority_takes_turns() {
        addKey("a", "key-a-0000000000", priority = 10)
        addKey("b", "key-b-0000000000", priority = 5)
        assertEquals("b", pool.acquire("p1")!!.id)
        assertEquals("b", pool.acquire("p1")!!.id)
        addKey("c", "key-c-0000000000", priority = 5)
        now += 10
        assertEquals("c", pool.acquire("p1")!!.id) // b was used more recently than c
        now += 10
        assertEquals("b", pool.acquire("p1")!!.id)
    }

    @Test fun disabled_keys_and_other_providers_keys_are_skipped() {
        addKey("a", "key-a-0000000000")
        addKey("x", "key-x-0000000000", provider = "other")
        pool.setEnabled("a", false)
        assertNull(pool.acquire("p1"))
        assertEquals("x", pool.acquire("other")!!.id)
        pool.setEnabled("a", true)
        assertEquals("a", pool.acquire("p1")!!.id)
    }

    @Test fun excluded_keys_are_not_offered_again_which_prevents_switching_loops() {
        addKey("a", "key-a-0000000000", priority = 1)
        addKey("b", "key-b-0000000000", priority = 2)
        assertEquals("b", pool.acquire("p1", exclude = setOf("a"))!!.id)
        assertNull(pool.acquire("p1", exclude = setOf("a", "b")))
    }

    // ---- limits, cooldowns and recovery ----------------------------------------------------------------

    @Test fun a_quota_hit_rests_the_key_until_the_provider_says_and_then_it_returns_by_itself() {
        addKey("a", "key-a-0000000000", priority = 1)
        addKey("b", "key-b-0000000000", priority = 2)
        pool.reportFailure("a", KeyFailure.QUOTA, retryAfterMs = 120_000L)
        assertEquals("b", pool.acquire("p1")!!.id)
        now += 119_999
        assertEquals("b", pool.acquire("p1")!!.id)
        now += 2
        assertEquals("a", pool.acquire("p1")!!.id) // limit reset: key a is back, no user action needed
    }

    @Test fun without_retry_after_the_reset_schedule_decides_then_the_cooldown() {
        addKey("a", "key-a-0000000000", cooldownMs = 600_000L)
        pool.reportFailure("a", KeyFailure.QUOTA, null)
        assertEquals(now + 600_000L, pool.get("a")!!.unavailableUntil)
        pool.resetCooldown("a")
        pool.upsert(pool.get("a")!!.copy(resetSchedule = ResetSchedule.every(30, 0)))
        pool.reportFailure("a", KeyFailure.QUOTA, null)
        assertEquals(ResetSchedule.every(30, 0).nextResetAfter(now), pool.get("a")!!.unavailableUntil)
    }

    @Test fun a_short_rate_limit_uses_a_short_default_wait_and_is_counted_separately_from_quota() {
        addKey("a", "key-a-0000000000")
        pool.reportFailure("a", KeyFailure.RATE_LIMIT, null)
        val r = pool.get("a")!!
        assertEquals(KeyHealth.RATE_LIMITED, r.health)
        assertEquals(now + KeyRecord.DEFAULT_RATE_LIMIT_COOLDOWN_MS, r.unavailableUntil)
        assertEquals(1, r.usage.rateLimitHits); assertEquals(0, r.usage.quotaHits)
    }

    @Test fun rejected_and_billing_keys_are_never_retried_automatically_until_the_user_resets_them() {
        addKey("a", "key-a-0000000000")
        pool.reportFailure("a", KeyFailure.AUTH, null)
        now += 365L * 86_400_000L
        assertNull(pool.acquire("p1"))
        assertEquals(KeyHealth.INVALID, pool.get("a")!!.healthAt(now))
        pool.resetCooldown("a")
        assertEquals("a", pool.acquire("p1")!!.id)
        pool.reportFailure("a", KeyFailure.BILLING, null)
        now += 365L * 86_400_000L
        assertNull(pool.acquire("p1"))
    }

    @Test fun server_errors_leave_a_good_key_in_rotation() {
        addKey("a", "key-a-0000000000")
        pool.reportFailure("a", KeyFailure.SERVER, null)
        pool.reportFailure("a", KeyFailure.NETWORK, null)
        assertEquals(KeyHealth.AVAILABLE, pool.get("a")!!.health)
        assertNotNull(pool.acquire("p1"))
    }

    @Test fun cooldown_state_and_usage_survive_an_app_restart() {
        addKey("a", "key-a-0000000000")
        pool.acquire("p1")
        pool.reportFailure("a", KeyFailure.QUOTA, retryAfterMs = 600_000L)
        val reopened = ApiKeyPool(store) { now + 1000 }
        val r = reopened.get("a")!!
        assertEquals(KeyHealth.QUOTA_EXHAUSTED, r.health)
        assertEquals(now + 600_000L, r.unavailableUntil)
        assertEquals(1, r.usage.requests); assertEquals(1, r.usage.quotaHits)
        assertNull(reopened.acquire("p1"))
        val later = ApiKeyPool(store) { now + 600_001L }
        assertEquals("a", later.acquire("p1")!!.id)
    }

    @Test fun a_clock_set_far_back_cannot_strand_a_key_for_longer_than_the_maximum_cooldown() {
        addKey("a", "key-a-0000000000")
        pool.reportFailure("a", KeyFailure.QUOTA, retryAfterMs = KeyRecord.MAX_COOLDOWN_MS)
        val rewound = ApiKeyPool(store) { now - 10L * 86_400_000L }
        assertTrue(rewound.get("a")!!.unavailableUntil <= now - 10L * 86_400_000L + KeyRecord.MAX_COOLDOWN_MS)
    }

    @Test fun a_corrupt_pool_store_does_not_crash() {
        store.putString(ApiKeyPool.STORE_KEY, "{broken")
        assertTrue(pool.list().isEmpty())
        store.putString(ApiKeyPool.STORE_KEY, """{"keys":[{"id":"ok","providerId":"p1"},{"nope":1},7]}""")
        assertEquals(listOf("ok"), pool.list().map { it.id })
    }

    @Test fun the_stored_pool_contains_no_key_text() {
        addKey("a", "sk-SUPER-SECRET-VALUE-123")
        pool.acquire("p1")
        assertTrue(store.map.values.none { it.contains("SUPER-SECRET") })
    }

    @Test fun simultaneous_requests_are_never_handed_the_same_key_when_others_are_free() {
        for (i in 1..4) addKey("k$i", "key-$i-0000000000")
        val got = java.util.Collections.synchronizedList(ArrayList<String>())
        val threads = (1..4).map { Thread { got += pool.acquire("p1", exclude = emptySet())!!.id } }
        // Started together; selection and the usage write happen under one lock, so each thread sees the others' leases.
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(4, got.toSet().size)
    }

    // ---- lease + summaries -----------------------------------------------------------------------------

    @Test fun a_key_whose_text_vanished_from_secure_storage_is_skipped_not_looped_on() {
        addKey("a", "key-a-0000000000", priority = 1)
        addKey("b", "key-b-0000000000", priority = 2)
        vault.delete("a")
        assertEquals("b", rotation.lease("p1", emptySet())!!.keyId)
        assertEquals(KeyHealth.INVALID, pool.get("a")!!.health)
    }

    @Test fun the_summary_explains_why_nothing_is_usable() {
        assertEquals("No API keys are saved for this provider.", rotation.unavailableSummary("p1"))
        addKey("a", "key-a-0000000000"); addKey("b", "key-b-0000000000")
        pool.reportFailure("a", KeyFailure.QUOTA, 300_000L)
        pool.reportFailure("b", KeyFailure.AUTH, null)
        val s = rotation.unavailableSummary("p1")
        assertTrue(s, s.contains("1 used up") && s.contains("5 minutes") && s.contains("1 rejected"))
        assertFalse(s.contains("key-a"))
    }

    // ---- the chat engine rotating in the middle of a conversation --------------------------------------

    private class Store : ChatTurnStore {
        class Row(val id: String, val user: String, var assistant: String, var state: String = "pending")
        val rows = ArrayList<Row>()
        private var seq = 0
        @Synchronized override fun completedTurns(excludingId: String?) =
            rows.filter { it.id != excludingId && it.state == "done" }.map { StoredTurn(it.id, it.user, it.assistant) }
        @Synchronized override fun begin(displayText: String, attachment: AttachmentRef?): String { val r = Row("t${++seq}", displayText, ""); rows += r; return r.id }
        @Synchronized override fun updatePartial(id: String, text: String) { rows.first { it.id == id }.assistant = text }
        @Synchronized override fun finish(id: String, text: String) { rows.first { it.id == id }.apply { assistant = text; state = "done" } }
        @Synchronized override fun fail(id: String, partial: String, errorText: String) { rows.first { it.id == id }.apply { assistant = if (partial.isBlank()) errorText else "$partial\n\n$errorText"; state = "failed" } }
        @Synchronized override fun resetForRetry(id: String): Boolean { rows.firstOrNull { it.id == id }?.apply { assistant = ""; state = "pending" } ?: return false; return true }
        @Synchronized override fun userText(id: String) = rows.firstOrNull { it.id == id }?.user
    }

    private val transport = FakeTransport()
    private val repo = ProviderRepository(store, vault)
    private val manager = ProviderConfigManager(repo, transport, clock = { now }, keyRotation = rotation)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun tearDown() { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }

    private fun engineWithProvider(): Pair<ProviderChatEngine, Store> {
        val profile = Fixtures.profile(models = listOf(ModelInfo("m1", capabilities = mapOf(Capability.TEXT to CapabilityResult(Support.YES, Evidence.PROBE)))))
        // The profile has no single key of its own: only the key manager's pool.
        repo.upsert(profile)
        repo.setActiveId("p1")
        val s = Store()
        return ProviderChatEngine(manager, transport, s, scope, { "SYS" }, uiThrottleMs = 0, keyRotation = rotation) to s
    }

    private fun usesKey(r: HttpRequestSpec, key: String) = r.headers.values.any { it.contains(key) }

    private fun awaitDone(e: ProviderChatEngine) = runBlocking { withTimeout(5000) { e.state.first { it is EngineState.Idle || it is EngineState.Failed } } }

    @Test fun when_the_first_key_hits_its_quota_the_same_message_continues_on_the_next_key() {
        addKey("a", "key-AAAA-0000000000", priority = 1)
        addKey("b", "key-BBBB-0000000000", priority = 2)
        transport.stream(FakeTransport.StreamRule({ usesKey(it, "key-AAAA") }, emptyList(),
            failWith = HttpStatusException(429, """{"error":{"message":"You exceeded your current quota"}}""", "3600")))
        transport.stream(FakeTransport.StreamRule({ usesKey(it, "key-BBBB") }, Fixtures.sse("Hello", " again")))
        val (e, s) = engineWithProvider()
        assertTrue(e.send("question") is SendOutcome.Started)
        awaitDone(e)
        assertEquals("Hello again", s.rows[0].assistant)
        assertEquals("done", s.rows[0].state)
        assertEquals(KeyHealth.QUOTA_EXHAUSTED, pool.get("a")!!.health)
        assertEquals(now + 3_600_000L, pool.get("a")!!.unavailableUntil)
        assertEquals(1, pool.get("b")!!.usage.successes)
    }

    @Test fun the_user_is_told_which_key_was_switched() {
        addKey("a", "key-AAAA-0000000000", priority = 1)
        addKey("b", "key-BBBB-0000000000", priority = 2)
        transport.stream(FakeTransport.StreamRule({ usesKey(it, "key-AAAA") }, emptyList(), failWith = HttpStatusException(429, """{"error":{"message":"quota"}}""")))
        transport.stream(FakeTransport.StreamRule({ usesKey(it, "key-BBBB") }, Fixtures.sse("ok")))
        val (e, _) = engineWithProvider()
        val notice = kotlinx.coroutines.CompletableDeferred<String>()
        // UNDISPATCHED: the collector is subscribed before send() runs, so the notice cannot be missed.
        val collecting = scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { e.keyNotices.collect { notice.complete(it) } }
        e.send("question"); awaitDone(e)
        val text = runBlocking { withTimeout(5000) { notice.await() } }
        collecting.cancel()
        assertTrue(text, text.contains("Key b") && text.contains("Key a"))
        assertFalse(text.contains("AAAA") || text.contains("BBBB"))
    }

    @Test fun every_key_exhausted_gives_one_clear_error_and_each_key_is_tried_only_once() {
        addKey("a", "key-AAAA-0000000000", priority = 1)
        addKey("b", "key-BBBB-0000000000", priority = 2)
        transport.stream(FakeTransport.StreamRule({ true }, emptyList(), failWith = HttpStatusException(429, """{"error":{"message":"quota exceeded"}}""")))
        val (e, s) = engineWithProvider()
        e.send("question"); awaitDone(e)
        assertEquals("failed", s.rows[0].state)
        assertEquals(2, transport.requests.count { it.url.contains("chat/completions") })
        assertTrue(s.rows[0].assistant, s.rows[0].assistant.contains("unavailable"))
    }

    @Test fun a_rejected_key_is_marked_invalid_and_not_retried_while_the_next_key_answers() {
        addKey("a", "key-AAAA-0000000000", priority = 1)
        addKey("b", "key-BBBB-0000000000", priority = 2)
        transport.stream(FakeTransport.StreamRule({ usesKey(it, "key-AAAA") }, emptyList(), failWith = HttpStatusException(401, """{"error":{"message":"bad key"}}""")))
        transport.stream(FakeTransport.StreamRule({ usesKey(it, "key-BBBB") }, Fixtures.sse("fine")))
        val (e, s) = engineWithProvider()
        e.send("one"); awaitDone(e)
        e.send("two"); awaitDone(e)
        assertEquals("fine", s.rows[1].assistant)
        assertEquals(KeyHealth.INVALID, pool.get("a")!!.health)
        assertEquals(1, transport.requests.count { usesKey(it, "key-AAAA") }) // never touched again
    }

    @Test fun a_provider_outage_does_not_burn_through_the_keys() {
        addKey("a", "key-AAAA-0000000000", priority = 1)
        addKey("b", "key-BBBB-0000000000", priority = 2)
        transport.stream(FakeTransport.StreamRule({ true }, emptyList(), failWith = HttpStatusException(503, "{}")))
        val (e, s) = engineWithProvider()
        e.send("q"); awaitDone(e)
        assertEquals("failed", s.rows[0].state)
        assertEquals(1, transport.requests.count { it.url.contains("chat/completions") })
        assertEquals(KeyHealth.AVAILABLE, pool.get("a")!!.health)
    }

    @Test fun a_limit_hit_after_text_already_streamed_is_not_blindly_replayed() {
        addKey("a", "key-AAAA-0000000000", priority = 1)
        addKey("b", "key-BBBB-0000000000", priority = 2)
        transport.stream(FakeTransport.StreamRule({ usesKey(it, "key-AAAA") }, Fixtures.sse("partial"),
            failAfter = TransportException(TransportFailure.UNREACHABLE, "drop")))
        val (e, s) = engineWithProvider()
        e.send("q"); awaitDone(e)
        assertEquals(1, transport.requests.count { it.url.contains("chat/completions") })
        assertTrue(s.rows[0].assistant.startsWith("partial"))
    }

    @Test fun the_complete_key_never_appears_in_pool_summaries_or_lease_text() {
        addKey("a", "sk-VERYSECRET-9999999")
        val lease = rotation.lease("p1", emptySet())!!
        assertFalse(lease.toString().contains("VERYSECRET"))
        assertFalse(lease.secret.toString().contains("VERYSECRET"))
    }
}
