package com.jarvis.assistant.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.flow.first
import org.junit.Test

class WorkRoutingTest {
    private val store = MemoryStore()
    private val vault = MemoryVault()
    private val repo = ProviderRepository(store, vault)
    private val manager = ProviderConfigManager(repo, FakeTransport())
    private val routes = RoutingStore(store)
    private val text = mapOf(Capability.TEXT to CapabilityResult(Support.YES, Evidence.PROBE))

    private fun provider(id: String, vararg models: String) {
        val p = Fixtures.profile(id = id, base = "https://$id.example.com/v1", models = models.map { ModelInfo(it, capabilities = text) }, model = models.first())
        assertNull(manager.save(p, "key-$id-0000000000"))
    }

    @Test fun every_requested_category_exists() {
        assertEquals(10, WorkCategory.values().size)
        assertEquals(WorkCategory.CODING, WorkCategory.fromId("coding"))
        assertNull(WorkCategory.fromId("nope"))
    }

    @Test fun routes_round_trip_and_survive_reopening() {
        routes.set(WorkCategory.CODING, Route("a", "big"))
        routes.set(WorkCategory.VOICE, Route(Route.GEMINI))
        val back = RoutingStore(store)
        assertEquals(Route("a", "big"), back.get(WorkCategory.CODING))
        assertTrue(back.get(WorkCategory.VOICE)!!.isGemini)
        back.clear(WorkCategory.CODING)
        assertNull(RoutingStore(store).get(WorkCategory.CODING))
    }

    @Test fun categories_can_use_different_providers_and_models() {
        provider("a", "m1", "m2"); provider("b", "x1")
        repo.setActiveId("a")
        routes.set(WorkCategory.CODING, Route("b", "x1"))
        routes.set(WorkCategory.FILE_ANALYSIS, Route("", "m2"))
        val coding = manager.resolveForCategory(WorkCategory.CODING, routes) as ChatResolution.Ready
        assertEquals("b", coding.provider.profileId); assertEquals("x1", coding.provider.modelId)
        val files = manager.resolveForCategory(WorkCategory.FILE_ANALYSIS, routes) as ChatResolution.Ready
        assertEquals("a", files.provider.profileId); assertEquals("m2", files.provider.modelId)
        val chat = manager.resolveForCategory(WorkCategory.CHAT, routes) as ChatResolution.Ready
        assertEquals("a", chat.provider.profileId); assertEquals("m1", chat.provider.modelId)
    }

    @Test fun a_gemini_route_hands_over_to_the_builtin_path_and_nothing_is_forced_through_it_otherwise() {
        provider("a", "m1"); repo.setActiveId("a")
        routes.set(WorkCategory.VOICE, Route(Route.GEMINI))
        assertEquals(ChatResolution.NotActive, manager.resolveForCategory(WorkCategory.VOICE, routes))
        assertTrue(manager.resolveForCategory(WorkCategory.CHAT, routes) is ChatResolution.Ready)
    }

    @Test fun a_deleted_provider_is_reported_not_silently_replaced() {
        provider("a", "m1"); provider("b", "x1"); repo.setActiveId("a")
        routes.set(WorkCategory.CODING, Route("b", ""))
        manager.delete("b")
        val r = manager.resolveForCategory(WorkCategory.CODING, routes)
        assertTrue(r is ChatResolution.Blocked && r.reason.contains("deleted"))
        routes.forgetProvider("b")
        assertTrue(manager.resolveForCategory(WorkCategory.CODING, routes) is ChatResolution.Ready)
    }

    @Test fun corrupt_route_data_is_ignored() {
        store.putString(RoutingStore.KEY, "{bad")
        assertTrue(routes.all().isEmpty())
        store.putString(RoutingStore.KEY, """{"chat":{"p":"a"},"unknown":{"p":"z"},"coding":5}""")
        assertEquals(setOf(WorkCategory.CHAT), routes.all().keys)
    }

    @Test fun offline_tts_route_is_recognized_and_preserved() {
        routes.set(WorkCategory.VOICE, Route(Route.OFFLINE_TTS, "local_kokoro"))
        val saved = routes.get(WorkCategory.VOICE)
        assertNotNull(saved)
        assertTrue(saved!!.isOfflineTts)
        assertEquals("local_kokoro", saved.modelId)
    }

    @Test fun task_type_from_id_resolves_all_categories() {
        assertEquals(TaskType.CHAT_ANALYSIS, TaskType.fromId("chat_analysis"))
        assertEquals(TaskType.IMAGE_UNDERSTANDING, TaskType.fromId("image_understanding"))
        assertEquals(TaskType.CODING_DEBUGGING, TaskType.fromId("coding_debugging"))
        assertEquals(TaskType.VOICE_CONVERSATION, TaskType.fromId("voice_conversation"))
        assertEquals(TaskType.CHAT_ANALYSIS, TaskType.fromId("unknown"))
    }
}

class WorkClassifierAndEngineRoutingTest {
    private val store = MemoryStore()
    private val vault = MemoryVault()
    private val repo = ProviderRepository(store, vault)
    private val routes = RoutingStore(store)
    private val transport = FakeTransport()
    private val manager = ProviderConfigManager(repo, transport, routes = routes)
    private val text = mapOf(Capability.TEXT to CapabilityResult(Support.YES, Evidence.PROBE))
    private val vision = mapOf(Capability.TEXT to CapabilityResult(Support.YES, Evidence.PROBE), Capability.IMAGE_INPUT to CapabilityResult(Support.YES, Evidence.PROBE))

    @Test fun messages_are_classified_into_the_right_kind_of_work() {
        assertEquals(WorkCategory.CHAT, WorkClassifier.classify("what's the weather like", emptyList(), false))
        assertEquals(WorkCategory.CODING, WorkClassifier.classify("fix this bug in my Kotlin code", emptyList(), false))
        assertEquals(WorkCategory.CODING, WorkClassifier.classify("```x```", emptyList(), false))
        assertEquals(WorkCategory.FILE_ANALYSIS, WorkClassifier.classify("summarise", emptyList(), true))
        assertEquals(WorkCategory.IMAGE, WorkClassifier.classify("what is this", listOf(ChatAttachment.Image("image/png", "x")), true))
    }

    @Test fun the_engine_sends_each_kind_of_work_to_its_own_provider_and_model() {
        val a = Fixtures.profile(id = "a", base = "https://a.example.com/v1", models = listOf(ModelInfo("chat-model", capabilities = text)), model = "chat-model")
        val b = Fixtures.profile(id = "b", base = "https://b.example.com/v1", models = listOf(ModelInfo("coder", capabilities = text)), model = "coder")
        assertNull(manager.save(a, "key-a-0000000000")); assertNull(manager.save(b, "key-b-0000000000"))
        repo.setActiveId("a")
        routes.set(WorkCategory.CODING, Route("b", "coder"))
        transport.stream(FakeTransport.StreamRule({ true }, Fixtures.sse("ok")))
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val engine = ProviderChatEngine(manager, transport, MiniStore(), scope, { "SYS" }, uiThrottleMs = 0)
        try {
            assertTrue(engine.send("hello there") is SendOutcome.Started); await(engine)
            assertTrue(engine.send("please debug this kotlin function") is SendOutcome.Started); await(engine)
            val urls = transport.requests.filter { it.url.contains("chat/completions") }.map { it.url }
            assertTrue(urls[0], urls[0].startsWith("https://a.example.com"))
            assertTrue(urls[1], urls[1].startsWith("https://b.example.com"))
            assertTrue(transport.bodyOf(transport.requests.last { it.url.contains("chat/completions") }).contains("\"coder\""))
        } finally { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    }

    @Test fun an_image_is_refused_with_an_explanation_when_the_routed_model_cannot_see_images() {
        val a = Fixtures.profile(id = "a", models = listOf(ModelInfo("m", capabilities = text)), model = "m")
        assertNull(manager.save(a, "key-a-0000000000")); repo.setActiveId("a")
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val engine = ProviderChatEngine(manager, transport, MiniStore(), scope, { "SYS" }, uiThrottleMs = 0)
        try {
            val r = engine.send("what is this", attachments = listOf(ChatAttachment.Image("image/png", "AAAA")))
            assertTrue(r is SendOutcome.Blocked)
            assertTrue((r as SendOutcome.Blocked).reason.isNotBlank())
            assertEquals(0, transport.requests.size) // nothing was sent anywhere
        } finally { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    }

    private fun await(e: ProviderChatEngine) = kotlinx.coroutines.runBlocking {
        kotlinx.coroutines.withTimeout(5000) { e.state.first { it is EngineState.Idle || it is EngineState.Failed } }
        kotlinx.coroutines.withTimeout(5000) { while (e.isBusy) kotlinx.coroutines.delay(2) }
    }

    private class MiniStore : ChatTurnStore {
        private val rows = ArrayList<Triple<String, String, String>>()
        override fun completedTurns(excludingId: String?) = rows.filter { it.first != excludingId && it.third.isNotBlank() }.map { StoredTurn(it.first, it.second, it.third) }
        override fun begin(displayText: String, attachment: AttachmentRef?): String { val id = "t${rows.size + 1}"; rows += Triple(id, displayText, ""); return id }
        override fun updatePartial(id: String, text: String) {}
        override fun finish(id: String, text: String) { val i = rows.indexOfFirst { it.first == id }; rows[i] = rows[i].copy(third = text) }
        override fun fail(id: String, partial: String, errorText: String) {}
        override fun resetForRetry(id: String) = true
        override fun userText(id: String) = rows.firstOrNull { it.first == id }?.second
    }
}
