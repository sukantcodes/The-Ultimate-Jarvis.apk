package com.jarvis.assistant.provider

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagerEngineTest {

    private val store = MemoryStore()
    private val vault = MemoryVault()
    private val transport = FakeTransport()
    private val repo = ProviderRepository(store, vault)
    private val manager = ProviderConfigManager(repo, transport, clock = { 1000L })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun tearDown() { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }

    private val textYes = mapOf(Capability.TEXT to CapabilityResult(Support.YES, Evidence.PROBE))

    private fun saved(
        id: String = "p1",
        models: List<ModelInfo> = listOf(ModelInfo("m1", capabilities = textYes), ModelInfo("m2", capabilities = textYes)),
        model: String = "m1",
        protocol: ProviderProtocol = ProviderProtocol.OPENAI_COMPATIBLE,
        base: String = "https://api.example.com/v1"
    ): ProviderProfile {
        val p = Fixtures.profile(id = id, base = base, protocol = protocol, models = models, model = model)
        assertNull(manager.save(p, Fixtures.KEY))
        return p
    }

    // ---- storage & security ----------------------------------------------------------------------------

    @Test fun the_key_is_stored_only_in_the_vault_never_in_profile_storage() {
        saved()
        assertEquals(Fixtures.KEY, vault.keys["p1"])
        assertTrue(store.map.values.none { it.contains(Fixtures.KEY) })
        assertTrue(store.map.values.none { it.contains("SECRET") })
    }

    @Test fun nothing_is_saved_when_secure_storage_is_unavailable() {
        val m = ProviderConfigManager(ProviderRepository(MemoryStore(), MemoryVault(available = false)), transport)
        val problem = m.save(Fixtures.profile(), Fixtures.KEY)
        assertNotNull(problem)
        assertTrue(problem!!.contains("never stored as plain text"))
        assertTrue(m.profiles().isEmpty())
    }

    @Test fun reopening_shows_only_a_masked_hint_and_editing_without_a_new_key_keeps_the_old_one() {
        saved()
        assertEquals("••••••••7890", manager.keyHint("p1"))
        val edited = manager.profile("p1")!!.copy(name = "Renamed")
        assertNull(manager.save(edited, null))
        assertEquals(Fixtures.KEY, vault.keys["p1"])
        assertEquals("Renamed", manager.profile("p1")!!.name)
        assertNull(manager.save(edited, "   "))
        assertEquals(Fixtures.KEY, vault.keys["p1"])
    }

    @Test fun profiles_round_trip_with_models_capabilities_selection_and_settings() {
        val caps = mapOf(
            Capability.TEXT to CapabilityResult(Support.YES, Evidence.METADATA, "n"),
            Capability.REALTIME_VOICE to CapabilityResult(Support.NO, Evidence.PROBE, "refused")
        )
        val p = Fixtures.profile(models = listOf(ModelInfo("m1", "Model One", "acme", 8192, listOf("generateContent"), caps)))
            .copy(chatModelId = "m1", sttModelId = "w", sttSupport = Support.YES, lastRefreshAt = 5, lastRefreshStatus = "1 models",
                settings = mapOf("temperature" to "0.2"), discoveryPath = "/x", realtimeUrl = "wss://a.example.com/s", authName = "X-T", authMethod = AuthMethod.CUSTOM_HEADER)
        assertNull(manager.save(p, Fixtures.KEY))
        val back = ProviderConfigManager(ProviderRepository(store, vault), transport).profile("p1")!!
        assertEquals(p, back)
    }

    @Test fun a_corrupt_profile_store_does_not_crash() {
        store.putString("profiles_json", "{not json")
        assertTrue(manager.profiles().isEmpty())
        store.putString("profiles_json", """{"profiles":[{"id":"ok","baseUrl":"https://a.example.com"},{"nope":1},5]}""")
        assertEquals(listOf("ok"), manager.profiles().map { it.id })
    }

    @Test fun invalid_profiles_are_rejected_with_a_reason() {
        assertTrue(manager.save(Fixtures.profile(base = "http://x.example.com"), Fixtures.KEY)!!.contains("https"))
        assertNotNull(manager.save(Fixtures.profile(auth = AuthMethod.CUSTOM_HEADER), Fixtures.KEY))
        assertNotNull(manager.save(Fixtures.profile().copy(settings = mapOf("temperature" to "9")), Fixtures.KEY))
        assertNotNull(manager.save(Fixtures.profile().copy(realtimeUrl = "ws://x"), Fixtures.KEY))
        assertNotNull(manager.save(Fixtures.profile(), "bad\nkey"))
        assertTrue(manager.profiles().isEmpty())
    }

    @Test fun deleting_removes_the_profile_and_its_key_and_deactivates_it() {
        saved()
        assertTrue(runBlocking { transport.onUrl("/models", 200, Fixtures.openAiModels("m1", "m2")); manager.activate("p1") } is ActivationResult.Activated)
        assertTrue(manager.delete("p1"))
        assertNull(vault.keys["p1"])
        assertNull(manager.activeProfile())
        assertEquals(ChatResolution.NotActive, manager.resolveChat())
    }

    @Test fun multiple_profiles_coexist_and_keys_are_kept_separate() {
        saved("a"); manager.save(Fixtures.profile(id = "b", base = "https://other.example.com/v1"), "key-for-b-0000000000")
        assertEquals(setOf("a", "b"), manager.profiles().map { it.id }.toSet())
        assertEquals(Fixtures.KEY, vault.keys["a"])
        assertEquals("key-for-b-0000000000", vault.keys["b"])
    }

    // ---- activation & single source of truth -----------------------------------------------------------

    @Test fun nothing_is_active_by_default_so_existing_engines_are_untouched() {
        assertEquals(ChatResolution.NotActive, manager.resolveChat())
        assertEquals(VoiceResolution.NotActive, manager.resolveVoice())
    }

    @Test fun activating_confirms_the_model_and_publishes_one_configuration_to_chat_and_voice() {
        saved()
        transport.onUrl("/models", 200, Fixtures.openAiModels("m1", "m2"))
        val before = manager.state.value.revision
        val result = runBlocking { manager.activate("p1") } as ActivationResult.Activated
        assertNull(result.warning)
        assertTrue(manager.state.value.revision > before)
        assertEquals("p1", manager.state.value.activeProfileId)
        val chat = manager.resolveChat() as ChatResolution.Ready
        assertEquals("m1", chat.provider.modelId)
        assertEquals("https://api.example.com/v1", chat.provider.baseUrl)
        assertEquals(Fixtures.KEY, chat.provider.auth.secret!!.reveal())
        // Voice on a plain OpenAI-compatible model without a speech-to-text model is blocked with a reason, not faked.
        val voice = manager.resolveVoice() as VoiceResolution.Blocked
        assertTrue(voice.reason.contains("speech-to-text"))
    }

    @Test fun activation_is_refused_when_the_chosen_model_is_no_longer_offered_and_the_old_provider_stays() {
        saved("a"); saved("b", model = "gone")
        transport.onUrl("/models", 200, Fixtures.openAiModels("m1"))
        assertTrue(runBlocking { manager.activate("a") } is ActivationResult.Activated)
        val rejected = runBlocking { manager.activate("b") } as ActivationResult.Rejected
        assertTrue(rejected.reason.contains("no longer offered"))
        assertEquals("a", manager.state.value.activeProfileId)
        // The refreshed list was still saved so the user can pick again.
        assertEquals(listOf("m1"), manager.profile("b")!!.models.map { it.id })
    }

    @Test fun activation_is_refused_for_rejected_keys_and_missing_keys_and_blank_models() {
        saved()
        transport.onUrl("/models", 401, "{}")
        assertTrue((runBlocking { manager.activate("p1") } as ActivationResult.Rejected).reason.contains("Authentication failed"))
        vault.keys.clear()
        assertTrue((runBlocking { manager.activate("p1") } as ActivationResult.Rejected).reason.contains("No API key"))
        manager.save(Fixtures.profile(id = "nm", model = ""), Fixtures.KEY)
        assertTrue((runBlocking { manager.activate("nm") } as ActivationResult.Rejected).reason.contains("Choose a model"))
        assertNull(manager.activeProfile())
    }

    @Test fun activation_while_offline_proceeds_with_a_warning_instead_of_blocking() {
        saved()
        transport.onThrow({ true }, TransportException(TransportFailure.UNREACHABLE, "offline"))
        val r = runBlocking { manager.activate("p1") } as ActivationResult.Activated
        assertNotNull(r.warning)
    }

    @Test fun activation_when_discovery_is_unsupported_proceeds_with_a_warning() {
        saved()
        transport.onUrl("/models", 404, "{}")
        val r = runBlocking { manager.activate("p1") } as ActivationResult.Activated
        assertTrue(r.warning!!.contains("doesn't list"))
    }

    @Test fun a_text_incapable_model_cannot_be_activated_or_used() {
        saved(models = listOf(ModelInfo("m1", capabilities = mapOf(Capability.TEXT to CapabilityResult(Support.NO, Evidence.METADATA, "embedding model")))))
        transport.onUrl("/models", 404, "{}")
        val r = runBlocking { manager.activate("p1") } as ActivationResult.Rejected
        assertTrue(r.reason.contains("can't generate text"))
    }

    @Test fun changing_the_active_providers_model_changes_what_the_runtime_resolves() {
        saved()
        transport.onUrl("/models", 200, Fixtures.openAiModels("m1", "m2"))
        runBlocking { manager.activate("p1") }
        assertEquals("m1", (manager.resolveChat() as ChatResolution.Ready).provider.modelId)
        val rev = manager.state.value.revision
        assertNull(manager.save(manager.profile("p1")!!.copy(selectedModelId = "m2"), null))
        assertTrue("editing the active provider must be announced", manager.state.value.revision > rev)
        assertEquals("m2", (manager.resolveChat() as ChatResolution.Ready).provider.modelId)
    }

    @Test fun chatbox_can_use_its_own_model_while_voice_keeps_the_main_one() {
        saved()
        transport.onUrl("/models", 200, Fixtures.openAiModels("m1", "m2"))
        manager.save(manager.profile("p1")!!.copy(chatModelId = "m2"), null)
        runBlocking { manager.activate("p1") }
        assertEquals("m2", (manager.resolveChat() as ChatResolution.Ready).provider.modelId)
        assertEquals("m1", manager.profile("p1")!!.selectedModelId)
    }

    @Test fun deactivating_returns_to_the_built_in_engines_and_never_picks_another_custom_one() {
        saved("a"); saved("b")
        transport.onUrl("/models", 200, Fixtures.openAiModels("m1", "m2"))
        runBlocking { manager.activate("a") }
        manager.deactivate()
        assertEquals(ChatResolution.NotActive, manager.resolveChat())
        assertNull(manager.state.value.activeProfileId)
    }

    @Test fun a_broken_active_profile_blocks_with_a_reason_it_does_not_resolve_to_nothing() {
        saved()
        transport.onUrl("/models", 200, Fixtures.openAiModels("m1", "m2"))
        runBlocking { manager.activate("p1") }
        vault.keys.clear()
        val blocked = manager.resolveChat() as ChatResolution.Blocked
        assertTrue(blocked.reason.contains("No API key"))
        assertTrue(manager.resolveVoice() is VoiceResolution.Blocked)
    }

    @Test fun a_gemini_live_model_resolves_for_realtime_voice_with_the_derived_socket_address() {
        val live = ModelInfo("live-1", capabilities = CapabilityDeriver.fromGemini(listOf("generateContent", "bidiGenerateContent")))
        saved(protocol = ProviderProtocol.GEMINI, base = "https://gw.example.com/v1beta", models = listOf(live), model = "live-1")
        manager.save(manager.profile("p1")!!.copy(authMethod = AuthMethod.X_GOOG_API_KEY), null)
        transport.onUrl("/models", 200, """{"models":[{"name":"models/live-1","supportedGenerationMethods":["generateContent","bidiGenerateContent"]}]}""")
        runBlocking { manager.activate("p1") }
        val v = manager.resolveVoice() as VoiceResolution.Ready
        assertEquals(VoiceMode.REALTIME_GEMINI_LIVE, v.mode)
        assertEquals("live-1", v.provider.modelId)
        assertEquals(ProviderProtocol.GEMINI, v.provider.protocol)
    }

    // ---- test connection ---------------------------------------------------------------------------------

    @Test fun test_connection_contacts_only_the_custom_endpoint_with_its_own_key() {
        val p = saved()
        transport.onUrl("/models", 200, Fixtures.openAiModels("m1", "m2"))
        transport.onUrl("/chat/completions", 200, Fixtures.completion("OK"))
        val report = runBlocking { manager.testConnection(p, null, "m1") }
        assertTrue(report.summary, report.ok)
        assertTrue(report.summary.contains("2 models"))
        assertTrue(transport.requests.isNotEmpty())
        assertTrue(transport.requests.all { it.url.startsWith("https://api.example.com/") })
        assertTrue(transport.requests.none { it.url.contains("googleapis") || it.url.contains("groq") })
        assertTrue(transport.requests.all { it.headers["Authorization"] == "Bearer ${Fixtures.KEY}" })
    }

    @Test fun test_connection_uses_the_key_typed_in_the_form_before_it_is_saved() {
        transport.onUrl("/models", 200, Fixtures.openAiModels("m1"))
        transport.onUrl("/chat/completions", 200, Fixtures.completion("OK"))
        runBlocking { manager.testConnection(Fixtures.profile(id = "draft"), "typed-key-12345678", "m1") }
        assertTrue(transport.requests.all { it.headers["Authorization"] == "Bearer typed-key-12345678" })
        assertTrue(vault.keys.isEmpty())
    }

    @Test fun test_connection_reports_the_exact_failure() {
        val p = saved()
        transport.onUrl("/models", 403, """{"error":{"message":"key disabled"}}""")
        val r = runBlocking { manager.testConnection(p, null, "m1") }
        assertFalse(r.ok)
        assertTrue(r.summary.contains("rejected the API key"))
        assertTrue(r.summary.contains("key disabled"))
    }

    @Test fun test_connection_flags_a_model_the_provider_does_not_list_and_a_model_that_does_not_answer() {
        val p = saved()
        transport.onUrl("/models", 200, Fixtures.openAiModels("other"))
        assertTrue(runBlocking { manager.testConnection(p, null, "m1") }.summary.contains("isn't in the provider's model list"))
        val t2 = FakeTransport()
        val m2 = ProviderConfigManager(repo, t2)
        t2.onUrl("/models", 200, Fixtures.openAiModels("m1"))
        t2.onUrl("/chat/completions", 500, "{}")
        val r = runBlocking { m2.testConnection(p, null, "m1") }
        assertFalse(r.ok)
        assertTrue(r.summary.contains("unavailable"))
    }

    @Test fun test_connection_with_manual_model_when_discovery_is_unsupported_still_checks_the_model() {
        val p = saved()
        transport.onUrl("/models", 404, "{}")
        transport.onUrl("/chat/completions", 200, Fixtures.completion("OK"))
        val r = runBlocking { manager.testConnection(p, null, "typed-by-hand") }
        assertTrue(r.ok)
        assertTrue(r.discoveryUnsupported)
        assertTrue(r.steps.any { it.title == "Selected model" && it.status == StepStatus.WARN })
    }

    // ---- ChatBox engine ------------------------------------------------------------------------------------

    private class FakeChatStore : ChatTurnStore {
        class Row(val id: String, var user: String, var assistant: String, var state: String = "pending")
        val rows = ArrayList<Row>()
        var seq = 0
        @Synchronized override fun completedTurns(excludingId: String?) =
            rows.filter { it.state == "done" && it.id != excludingId }.map { StoredTurn(it.id, it.user, it.assistant) }
        @Synchronized override fun begin(displayText: String, attachment: AttachmentRef?): String {
            val r = Row("t${++seq}", displayText, ""); rows += r; return r.id
        }
        @Synchronized override fun updatePartial(id: String, text: String) { rows.first { it.id == id }.assistant = text }
        @Synchronized override fun finish(id: String, text: String) { rows.first { it.id == id }.apply { assistant = text; state = "done" } }
        @Synchronized override fun fail(id: String, partial: String, errorText: String) {
            rows.first { it.id == id }.apply { assistant = if (partial.isBlank()) errorText else "$partial\n\n$errorText"; state = "failed" }
        }
        @Synchronized override fun resetForRetry(id: String): Boolean { rows.firstOrNull { it.id == id }?.apply { assistant = ""; state = "pending" } ?: return false; return true }
        @Synchronized override fun userText(id: String) = rows.firstOrNull { it.id == id }?.user
    }

    private fun engine(chatStore: FakeChatStore) = ProviderChatEngine(manager, transport, chatStore, scope, { "SYS" }, uiThrottleMs = 0)

    private fun activate(models: String = Fixtures.openAiModels("m1", "m2")) {
        saved()
        transport.onUrl("/models", 200, models)
        runBlocking { manager.activate("p1") }
    }

    private fun awaitIdle(e: ProviderChatEngine) = runBlocking {
        withTimeout(5000) { e.state.first { it is EngineState.Idle || it is EngineState.Failed } }
    }

    @Test fun chatbox_does_nothing_when_no_custom_provider_is_active_so_the_old_path_runs() {
        val e = engine(FakeChatStore())
        assertEquals(SendOutcome.NotActive, e.send("hi"))
    }

    @Test fun chatbox_sends_to_the_selected_model_and_streams_the_answer_into_the_chat() {
        activate()
        transport.stream(FakeTransport.StreamRule({ true }, Fixtures.sse("Hello", " there")))
        val s = FakeChatStore(); val e = engine(s)
        val started = e.send("first question") as SendOutcome.Started
        assertTrue(started.providerLabel.contains("m1"))
        awaitIdle(e)
        assertEquals("Hello there", s.rows[0].assistant)
        assertEquals("done", s.rows[0].state)
        val body = com.google.gson.JsonParser.parseString(transport.bodyOf(transport.requests.last { it.url.endsWith("/chat/completions") })).asJsonObject
        assertEquals("m1", body["model"].asString)
        assertEquals(listOf("system", "user"), body["messages"].asJsonArray.map { it.asJsonObject["role"].asString })
    }

    @Test fun follow_up_questions_include_earlier_turns_but_never_failed_ones() {
        activate()
        val s = FakeChatStore()
        s.rows += FakeChatStore.Row("old1", "q1", "a1", "done")
        s.rows += FakeChatStore.Row("old2", "q-failed", ProviderChatEngine.ERROR_PREFIX + "boom", "failed")
        transport.stream(FakeTransport.StreamRule({ true }, Fixtures.sse("ok")))
        val e = engine(s)
        e.send("q2"); awaitIdle(e)
        val body = com.google.gson.JsonParser.parseString(transport.bodyOf(transport.requests.last { it.url.endsWith("/chat/completions") })).asJsonObject
        val contents = body["messages"].asJsonArray.map { it.asJsonObject["content"].asString }
        assertEquals(listOf("SYS", "q1", "a1", "q2"), contents)
    }

    @Test fun failure_is_shown_and_keeps_the_users_message() {
        activate()
        transport.stream(FakeTransport.StreamRule({ true }, emptyList(), failWith = HttpStatusException(503, "{}")))
        val s = FakeChatStore(); val e = engine(s)
        e.send("please answer")
        val failed = awaitIdle(e) as EngineState.Failed
        assertEquals(ProviderErrorKind.UNAVAILABLE, failed.error.kind)
        assertEquals(1, s.rows.size)
        assertEquals("please answer", s.rows[0].user)
        assertTrue(s.rows[0].assistant.startsWith(ProviderChatEngine.ERROR_PREFIX))
        assertEquals("failed", s.rows[0].state)
    }

    @Test fun retry_reuses_the_same_turn_in_place_and_never_duplicates_the_message() {
        activate()
        transport.stream(FakeTransport.StreamRule({ true }, emptyList(), failWith = TransportException(TransportFailure.TIMEOUT, "t")))
        val s = FakeChatStore(); val e = engine(s)
        val id = (e.send("question") as SendOutcome.Started).turnId
        assertTrue(awaitIdle(e) is EngineState.Failed)

        transport.clearStreams()
        transport.stream(FakeTransport.StreamRule({ true }, Fixtures.sse("Recovered")))
        assertTrue(e.retry(id) is SendOutcome.Started)
        assertTrue(awaitIdle(e) is EngineState.Idle)

        assertEquals("a retry must not add a second user message", 1, s.rows.size)
        assertEquals("Recovered", s.rows[0].assistant)
        assertEquals("done", s.rows[0].state)
        assertEquals(2, transport.requests.count { it.url.endsWith("/chat/completions") })
        // A turn that succeeded can't be "retried" again.
        assertTrue(e.retry(id) is SendOutcome.Blocked)
    }

    @Test fun retry_after_switching_provider_uses_the_provider_active_now() {
        activate()
        saved("p2", base = "https://second.example.com/v1")
        transport.onUrl("second.example.com/v1/models", 200, Fixtures.openAiModels("m1", "m2"))
        transport.stream(FakeTransport.StreamRule({ true }, emptyList(), failWith = HttpStatusException(503, "{}")))
        val s = FakeChatStore(); val e = engine(s)
        val id = (e.send("question") as SendOutcome.Started).turnId
        awaitIdle(e)
        runBlocking { manager.activate("p2") }
        transport.clearStreams()
        transport.stream(FakeTransport.StreamRule({ true }, Fixtures.sse("from-second")))
        e.retry(id); awaitIdle(e)
        assertEquals("from-second", s.rows[0].assistant)
        assertEquals("second.example.com", ProviderUrl.hostOf(transport.requests.last { it.url.endsWith("/chat/completions") }.url))
    }

    @Test fun a_second_question_while_one_is_streaming_is_refused_not_duplicated() {
        activate()
        transport.stream(FakeTransport.StreamRule({ true }, Fixtures.sse("a", "b", "c"), delayEachMs = 150))
        val s = FakeChatStore(); val e = engine(s)
        assertTrue(e.send("one") is SendOutcome.Started)
        assertEquals(SendOutcome.Busy, e.send("two"))
        awaitIdle(e)
        assertEquals(1, s.rows.size)
    }

    @Test fun switching_provider_mid_answer_does_not_change_the_response_in_flight_but_applies_to_the_next_question() {
        activate()
        saved("p2", base = "https://second.example.com/v1")
        transport.onUrl("second.example.com/v1/models", 200, Fixtures.openAiModels("m1", "m2"))
        transport.stream(FakeTransport.StreamRule({ it.url.startsWith("https://api.example.com") }, Fixtures.sse("from-first", "!"), delayEachMs = 150))
        transport.stream(FakeTransport.StreamRule({ it.url.startsWith("https://second.example.com") }, Fixtures.sse("from-second")))
        val s = FakeChatStore(); val e = engine(s)
        e.send("q1")
        runBlocking { manager.activate("p2") } // switch while the first answer is still arriving
        awaitIdle(e)
        assertEquals("from-first!", s.rows[0].assistant)
        e.send("q2"); awaitIdle(e)
        assertEquals("from-second", s.rows[1].assistant)
        val hosts = transport.requests.filter { it.url.endsWith("/chat/completions") }.map { ProviderUrl.hostOf(it.url) }
        assertEquals(listOf("api.example.com", "second.example.com"), hosts)
    }

    @Test fun a_failing_custom_provider_never_falls_back_to_another_provider() {
        activate()
        transport.stream(FakeTransport.StreamRule({ true }, emptyList(), failWith = HttpStatusException(401, "{}")))
        val s = FakeChatStore(); val e = engine(s)
        e.send("hello")
        val st = awaitIdle(e) as EngineState.Failed
        assertEquals(ProviderErrorKind.AUTH_FAILED, st.error.kind)
        assertTrue(transport.requests.all { it.url.startsWith("https://api.example.com/") })
        assertEquals(1, transport.requests.count { it.url.endsWith("/chat/completions") })
    }

    @Test fun a_blocked_provider_reports_why_and_adds_no_chat_row() {
        activate()
        vault.keys.clear()
        val s = FakeChatStore(); val e = engine(s)
        val out = e.send("hi") as SendOutcome.Blocked
        assertTrue(out.reason.contains("No API key"))
        assertTrue(s.rows.isEmpty())
    }

    @Test fun images_and_pdfs_are_refused_unless_the_model_supports_them_and_nothing_is_fabricated() {
        activate()
        val s = FakeChatStore(); val e = engine(s)
        val blocked = e.send("what is this", attachments = listOf(ChatAttachment.Image("image/png", "AAAA"))) as SendOutcome.Blocked
        assertTrue(blocked.reason.contains("hasn't been verified"))
        assertTrue(s.rows.isEmpty())
        assertTrue(transport.requests.none { it.url.endsWith("/chat/completions") })
    }

    @Test fun images_are_sent_when_the_model_is_known_to_accept_them() {
        val vision = ModelInfo("m1", capabilities = textYes + (Capability.IMAGE_INPUT to CapabilityResult(Support.YES, Evidence.PROBE)))
        saved(models = listOf(vision))
        transport.onUrl("/models", 200, Fixtures.openAiModels("m1"))
        runBlocking { manager.activate("p1") }
        transport.stream(FakeTransport.StreamRule({ true }, Fixtures.sse("a cat")))
        val s = FakeChatStore(); val e = engine(s)
        assertTrue(e.send("what is this", attachments = listOf(ChatAttachment.Image("image/png", "AAAA"))) is SendOutcome.Started)
        awaitIdle(e)
        assertTrue(transport.bodyOf(transport.requests.last { it.url.endsWith("/chat/completions") }).contains("data:image/png;base64,AAAA"))
        assertEquals("a cat", s.rows[0].assistant)
    }

    @Test fun document_text_travels_to_the_model_but_the_chat_shows_only_the_short_message_and_follow_ups_keep_it() {
        activate()
        transport.stream(FakeTransport.StreamRule({ true }, Fixtures.sse("summary")))
        val s = FakeChatStore(); val e = engine(s)
        e.send("📎 notes.txt\nsummarise", sendText = "summarise\n\n[FILE notes.txt]\nSECRET-DOC-CONTENT")
        awaitIdle(e)
        assertEquals("📎 notes.txt\nsummarise", s.rows[0].user)
        e.send("and the second point?"); awaitIdle(e)
        val last = transport.bodyOf(transport.requests.last { it.url.endsWith("/chat/completions") })
        assertTrue("follow-up must still carry the document", last.contains("SECRET-DOC-CONTENT"))
    }

    @Test fun cancelling_keeps_what_arrived_and_a_cancel_with_nothing_arrived_can_be_retried() {
        activate()
        transport.stream(FakeTransport.StreamRule({ true }, Fixtures.sse("one", "two", "three", "four"), delayEachMs = 300))
        val s = FakeChatStore(); val e = engine(s)
        e.send("slow")
        Thread.sleep(450)
        e.cancel()
        runBlocking { withTimeout(3000) { e.state.first { it is EngineState.Idle } } }
        assertTrue(s.rows[0].assistant.contains("(stopped)"))
        assertEquals("done", s.rows[0].state)
    }

    @Test fun context_budget_drops_the_oldest_turns_first_and_shortens_an_oversized_one() {
        val turns = (1..6).map { StoredTurn("t$it", "q$it", "a$it".padEnd(100, 'x')) }
        val msgs = ChatContext.build(turns, "new", budgetChars = 330)
        assertEquals("new", msgs.last().text)
        assertTrue(msgs.size < 13)
        assertEquals("q6", msgs[msgs.size - 3].text)
        val huge = ChatContext.build(listOf(StoredTurn("x", "u".repeat(500), "a".repeat(500))), "n", budgetChars = 200)
        assertTrue(huge.sumOf { it.text.length } <= 201)
    }

    @Test fun a_partial_answer_followed_by_an_error_notice_is_not_sent_back_as_history() {
        val history = listOf(
            StoredTurn("1", "hi", "Hello there"),
            StoredTurn("2", "question", "partial text\n\n" + ProviderChatEngine.ERROR_PREFIX + "The connection dropped.")
        )
        val messages = ChatContext.build(history, "next")
        assertEquals(listOf("hi", "Hello there", "next"), messages.map { it.text })
    }
}
