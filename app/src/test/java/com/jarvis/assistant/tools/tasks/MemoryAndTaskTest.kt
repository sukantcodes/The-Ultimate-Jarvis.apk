package com.jarvis.assistant.tools.tasks

import com.jarvis.assistant.provider.ProviderError
import com.jarvis.assistant.provider.ProviderErrorKind
import com.jarvis.assistant.provider.ProviderException
import com.jarvis.assistant.tools.*
import com.jarvis.assistant.tools.memory.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class MemoryTest {
    private val dir = Files.createTempDirectory("mem").toFile()
    private val store = MemoryStore(java.io.File(dir, "m.json"))

    @Test fun savesSearchesEditsDeletesExportsAndSurvivesRestart() {
        val a = (store.save("My favourite language is Kotlin", "preference") as MemoryStore.SaveResult.Saved).entry
        store.save("The user's dog is called Biscuit")
        assertEquals(a.id, (store.save("my favourite language is kotlin") as MemoryStore.SaveResult.Saved).entry.id)   // no duplicate
        assertEquals(2, MemoryStore(java.io.File(dir, "m.json")).list().size)
        assertTrue(store.relevant("what is my dog called").first().text.contains("Biscuit"))
        assertTrue(store.contextFor("dog").contains("Biscuit")); assertTrue(store.exportJson().contains("Kotlin"))
        assertTrue(store.update(a.id, "Favourite language is Rust")); assertTrue(store.list().any { it.text.contains("Rust") })
        assertTrue(store.delete(a.id)); assertFalse(store.delete(a.id)); store.clear(); assertTrue(store.list().isEmpty())
    }

    @Test fun secretsAreNeverStored() {
        for (s in listOf("my key is sk-abcdefghijklmnopqrstuvwx", "token: ghp_abcdefghijklmnopqrstuvwxyz0123", "password = hunter2hunter", "AIzaSyA1234567890123456789012345678901", "-----BEGIN RSA PRIVATE KEY-----", "Bearer abcdefghijklmnopqrstuvwxyz123456"))
            assertTrue(s, store.save(s) is MemoryStore.SaveResult.Refused)
        assertTrue(store.list().isEmpty())
        assertTrue(store.save("I like tea") is MemoryStore.SaveResult.Saved)
    }

    @Test fun switchingMemoryOffStopsSavingAndRecall() {
        store.save("I like tea"); store.enabled = false
        assertTrue(store.save("more") is MemoryStore.SaveResult.Refused); assertTrue(store.relevant("tea").isEmpty()); assertEquals("", store.contextFor("tea"))
    }

    @Test fun toolsWork() = runBlocking {
        val ctx = object : ToolContext { override val workspace = Workspace(dir) }
        val tools = MemoryTools.all(store).associateBy { it.spec.name }
        assertTrue(tools["memory_save"]!!.run(args("""{"text":"likes chess"}"""), ctx).isOk)
        assertFalse(tools["memory_save"]!!.run(args("""{"text":"api key is sk-abcdefghijklmnopqrstuvwx"}"""), ctx).isOk)
        assertTrue(tools["memory_list"]!!.run(args("{}"), ctx).text.contains("chess"))
        assertEquals(Risk.DESTRUCTIVE, tools["memory_delete"]!!.spec.risk)
    }
}

class TaskEngineTest {
    private val dir = Files.createTempDirectory("tasks").toFile()
    private val store = TaskStore(dir)
    private val pauses = ArrayList<Long>()
    private fun engine(r: SegmentRunner, limits: RunLimits = RunLimits()) = TaskEngine(store, r, limits, pause = { pauses += it })
    private fun markDone(id: String, vararg n: Int) { store.update(id) { t -> t.copy(steps = t.steps.map { if (it.index in n) it.copy(status = StepStatus.DONE) else it }) } }

    @Test fun runsStretchesUntilAllStepsAreDoneAndSavesCheckpoints() = runBlocking {
        val t = store.create("build site", listOf("a", "b", "c"))
        var calls = 0
        val done = engine({ prompt, _ -> calls++; assertTrue(prompt.contains("Current plan")); markDone(t.id, calls); "did $calls" }).run(t.id)
        assertEquals(TaskState.DONE, done.state); assertEquals(3, done.segments); assertEquals(3, store.load(t.id)!!.steps.count { it.status == StepStatus.DONE })
    }

    @Test fun transientFailuresRetryWithBackoffThenSucceed() = runBlocking {
        val t = store.create("g", listOf("a")); var n = 0
        val r = engine({ _, _ -> if (n++ < 2) throw ProviderException(ProviderError(ProviderErrorKind.RATE_LIMITED, "slow down")); markDone(t.id, 1); "ok" }).run(t.id)
        assertEquals(TaskState.DONE, r.state); assertEquals(listOf(2000L, 4000L), pauses)
    }

    @Test fun permanentFailureStopsAndExhaustedRetriesPause() = runBlocking {
        val a = store.create("g", listOf("a"))
        assertEquals(TaskState.FAILED, engine({ _, _ -> throw ProviderException(ProviderError(ProviderErrorKind.KEY_REJECTED, "bad key")) }).run(a.id).state)
        val b = store.create("g2", listOf("a"))
        val r = engine({ _, _ -> throw ProviderException(ProviderError(ProviderErrorKind.TIMEOUT, "t")) }, RunLimits(maxRetriesPerSegment = 2)).run(b.id)
        assertEquals(TaskState.PAUSED, r.state)
    }

    @Test fun noProgressTwiceInARowPausesInsteadOfSpinning() = runBlocking {
        val t = store.create("g", listOf("a", "b")); var calls = 0
        val r = engine({ _, _ -> calls++; "thinking" }).run(t.id)
        assertEquals(TaskState.PAUSED, r.state); assertEquals(2, calls); assertTrue(r.log.last().contains("no progress"))
    }

    @Test fun segmentBudgetPausesAndResumeContinuesFromTheSavedPlan() = runBlocking {
        val t = store.create("g", listOf("a", "b", "c", "d")); var n = 0
        val first = engine({ _, _ -> n++; markDone(t.id, n); "x" }, RunLimits(maxSegments = 2)).run(t.id)
        assertEquals(TaskState.PAUSED, first.state); assertEquals(2, first.steps.count { it.status == StepStatus.DONE })
        val second = engine({ _, _ -> n++; markDone(t.id, n); "x" }).run(t.id)   // a new engine, as after an app restart
        assertEquals(TaskState.DONE, second.state); assertEquals(4, n)
    }

    @Test fun cancellationIsRecorded() = runBlocking {
        val t = store.create("g", listOf("a")); val gate = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) { try { engine({ _, _ -> gate.await(); "x" }).run(t.id) } catch (e: CancellationException) { } }
        delay(100); job.cancelAndJoin()
        assertEquals(TaskState.CANCELLED, store.load(t.id)!!.state)
    }

    @Test fun planAndUpdateTools() = runBlocking {
        val ctx = object : ToolContext { override val workspace = Workspace(dir) }
        val tools = TaskTools(store); val id = Regex("t\\d+").find(tools.Plan().run(args("""{"goal":"x","steps":["one","two"]}"""), ctx).text)!!.value
        assertTrue(tools.Update().run(args("""{"task_id":"$id","step":1,"status":"done","note":"ok"}"""), ctx).text.contains("[done] one"))
        assertFalse(tools.Update().run(args("""{"task_id":"$id","step":9,"status":"done"}"""), ctx).isOk)
        assertTrue(tools.Status().run(args("{}"), ctx).text.contains("two"))
    }
}
