package com.jarvis.assistant.automation.typing

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scripted device. Every behaviour a real app can show (late focus, stale node, rejected write...) is a switch. */
class FakeTypingHost(initialText: String? = "") : TypingHost {
    @Volatile var foreground: String? = "com.example.app"
    @Volatile var fieldText: String? = initialText
    @Volatile var focused = true
    @Volatile var editable = true
    @Volatile var hasField = true
    @Volatile var focusWorks = true
    @Volatile var protectedField: String? = null
    @Volatile var readyAfterPolls = 0
    @Volatile var staleOnce = false
    @Volatile var failTypesRemaining = 0
    @Volatile var landButReportFailure = 0
    @Volatile var corruptWrite = false
    @Volatile var unreadable = false
    @Volatile var lastPreferAddressBar = false
    private var handle = 1L
    private var readyPolls = 0
    val log: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf<String>())

    fun count(event: String) = synchronized(log) { log.count { it == event } }

    override fun foregroundPackage(): String? = foreground

    override suspend fun awaitUiChange(timeoutMs: Long): Boolean {
        delay(minOf(timeoutMs, 5L).coerceAtLeast(1L))
        return false
    }

    override fun probeField(preferAddressBar: Boolean): FieldProbe {
        lastPreferAddressBar = preferAddressBar
        log += "probe"
        protectedField?.let { return FieldProbe.Protected(it) }
        if (!hasField) return FieldProbe.NotFound
        return FieldProbe.Found(FieldInfo(handle = handle, focused = focused, editable = editable))
    }

    override fun requestFocus(handle: Long): Boolean {
        log += "focus"
        if (focusWorks) { focused = true; editable = true }
        return focusWorks
    }

    override fun readiness(handle: Long): Readiness {
        if (staleOnce) { staleOnce = false; this.handle++; log += "stale"; return Readiness.STALE }
        if (readyPolls++ < readyAfterPolls) return Readiness.NOT_READY
        return Readiness.READY
    }

    override fun readField(handle: Long): String? = if (unreadable) null else fieldText

    override suspend fun typeInto(handle: Long, text: String, allowAddressBar: Boolean): TypeAttempt {
        log += "type"
        if (failTypesRemaining > 0) { failTypesRemaining--; return TypeAttempt(false, "rejected") }
        fieldText = if (corruptWrite) text.take(2) else text
        if (landButReportFailure > 0) { landButReportFailure--; return TypeAttempt(false, "no readback") }
        return TypeAttempt(true, "SET_TEXT")
    }
}

class TypingSessionTest {

    private val fast = TypingConfig(
        navigationTimeoutMs = 600, fieldTimeoutMs = 300, focusTimeoutMs = 300, readyTimeoutMs = 300,
        typeTimeoutMs = 1_000, maxAttempts = 3, overallDeadlineMs = 8_000, eventWaitMs = 10
    )

    private fun session(
        host: TypingHost, text: String, mode: TypeMode = TypeMode.APPEND, pkg: String? = null,
        addressBar: Boolean = false, config: TypingConfig = fast, states: MutableList<TypingState>? = null,
        cancelled: () -> Boolean = { false }
    ) = TypingSession(host, text, mode, pkg, addressBar, config, isCancelled = cancelled, onState = { states?.add(it) })

    // ---- the happy path -------------------------------------------------------------------------------------

    @Test fun typesExactTextThroughEveryState() = runBlocking {
        val host = FakeTypingHost("")
        val states = mutableListOf<TypingState>()
        val r = session(host, "Hello, how are you?", states = states).run()
        assertTrue(r.message, r.success)
        assertEquals(TypingState.COMPLETED, r.state)
        assertEquals("Hello, how are you?", host.fieldText)
        assertEquals(1, host.count("type"))
        assertEquals(
            listOf(TypingState.LOCATING_FIELD, TypingState.WAITING_FOR_READY_FIELD, TypingState.TYPING,
                TypingState.VERIFYING, TypingState.COMPLETED), states
        )
    }

    @Test fun unicodeMultilineAndLongTextAreEnteredExactly() = runBlocking {
        val text = "नमस्ते दुनिया 😊\nLine two — “quotes” & symbols!\n" + "x".repeat(5_000)
        val host = FakeTypingHost("")
        val r = session(host, text).run()
        assertTrue(r.message, r.success)
        assertEquals(text, host.fieldText)
    }

    // ---- navigation then typing, as one continuous operation ------------------------------------------------

    @Test fun waitsForTheAppThenTypesWithoutBeingToldAgain() = runBlocking {
        val host = FakeTypingHost("")
        host.foreground = "com.jarvis.assistant"
        val states = mutableListOf<TypingState>()
        launch { delay(120); host.foreground = "com.android.chrome" }
        val r = session(host, "Android development", pkg = "com.android.chrome", states = states).run()
        assertTrue(r.message, r.success)
        assertEquals(TypingState.NAVIGATING, states.first())
        assertEquals(1, states.count { it == TypingState.NAVIGATING })
        assertEquals("Android development", host.fieldText)
    }

    @Test fun appThatNeverOpensFailsInBoundedTimeAndTypesNothing() = runBlocking {
        val host = FakeTypingHost("")
        host.foreground = "com.jarvis.assistant"
        val t0 = System.currentTimeMillis()
        val r = session(host, "abc", pkg = "com.android.chrome").run()
        assertFalse(r.success)
        assertEquals(TypingFailure.APP_NOT_FOREGROUND, r.failure)
        assertEquals(0, host.count("type"))
        assertTrue(System.currentTimeMillis() - t0 < 3_000)
    }

    // ---- focus and readiness ------------------------------------------------------------------------------------

    @Test fun focusIsRequestedWhenTheFieldDoesNotOwnIt() = runBlocking {
        val host = FakeTypingHost("")
        host.focused = false; host.editable = false      // e.g. Chrome's new-tab search box
        val states = mutableListOf<TypingState>()
        val r = session(host, "Android development", addressBar = true, states = states).run()
        assertTrue(r.message, r.success)
        assertTrue(states.contains(TypingState.REQUESTING_FOCUS))
        assertEquals(1, host.count("focus"))
        assertTrue("address bar must be allowed when asked", host.lastPreferAddressBar)
    }

    @Test fun focusNeverAcquiredFailsHonestlyWithoutTyping() = runBlocking {
        val host = FakeTypingHost("")
        host.focused = false; host.focusWorks = false
        val r = session(host, "abc").run()
        assertFalse(r.success)
        assertEquals(TypingFailure.FOCUS_NOT_ACQUIRED, r.failure)
        assertEquals(3, r.attempts)
        assertEquals(0, host.count("type"))
    }

    @Test fun keyboardVisibleButFieldNotReadyYetWaitsThenTypes() = runBlocking {
        val host = FakeTypingHost("")
        host.readyAfterPolls = 3
        val states = mutableListOf<TypingState>()
        val r = session(host, "abc", states = states).run()
        assertTrue(r.message, r.success)
        assertTrue(states.contains(TypingState.WAITING_FOR_READY_FIELD))
        assertEquals(1, host.count("type"))
    }

    @Test fun fieldThatNeverBecomesReadyIsNeverTypedInto() = runBlocking {
        val host = FakeTypingHost("")
        host.readyAfterPolls = Int.MAX_VALUE
        val r = session(host, "abc").run()
        assertFalse(r.success)
        assertEquals(TypingFailure.FIELD_NOT_READY, r.failure)
        assertEquals(0, host.count("type"))
    }

    // ---- recovery -----------------------------------------------------------------------------------------------

    @Test fun staleNodeIsRelocatedInRecoveryAndTyped() = runBlocking {
        val host = FakeTypingHost("")
        host.staleOnce = true
        val states = mutableListOf<TypingState>()
        val r = session(host, "abc", states = states).run()
        assertTrue(r.message, r.success)
        assertEquals(2, r.attempts)
        assertTrue(states.contains(TypingState.RECOVERY))
        assertEquals(1, host.count("type"))
    }

    @Test fun rejectedWriteIsRetriedWithoutReNavigatingAndKeepsTheText() = runBlocking {
        val host = FakeTypingHost("")
        host.foreground = "com.whatsapp"
        host.failTypesRemaining = 1
        val states = mutableListOf<TypingState>()
        val r = session(host, "Hello, how are you?", pkg = "com.whatsapp", states = states).run()
        assertTrue(r.message, r.success)
        assertEquals(2, r.attempts)
        assertEquals(2, host.count("type"))
        assertEquals("only one navigation phase", 1, states.count { it == TypingState.NAVIGATING })
        assertEquals("Hello, how are you?", host.fieldText)
    }

    @Test fun appendRetryNeverDuplicatesExistingOrTypedText() = runBlocking {
        val host = FakeTypingHost("Hello ")
        host.failTypesRemaining = 1
        val r = session(host, "world", mode = TypeMode.APPEND).run()
        assertTrue(r.message, r.success)
        assertEquals("Hello world", host.fieldText)
    }

    @Test fun writeThatLandedButReportedFailureIsStillVerifiedFromTheField() = runBlocking {
        val host = FakeTypingHost("Hello ")
        host.landButReportFailure = 1
        val r = session(host, "world").run()
        assertTrue(r.message, r.success)
        assertEquals(1, r.attempts)
        assertEquals(1, host.count("type"))
        assertEquals("Hello world", host.fieldText)
    }

    // ---- modes -----------------------------------------------------------------------------------------------------

    @Test fun replaceModeReplacesExistingText() = runBlocking {
        val host = FakeTypingHost("old text")
        val r = session(host, "new text", mode = TypeMode.REPLACE).run()
        assertTrue(r.message, r.success)
        assertEquals("new text", host.fieldText)
    }

    @Test fun clearModeEmptiesTheField() = runBlocking {
        val host = FakeTypingHost("something")
        val r = session(host, "", mode = TypeMode.CLEAR).run()
        assertTrue(r.message, r.success)
        assertEquals("", host.fieldText)
    }

    @Test fun loosePreExistingMatchDoesNotSkipTyping() = runBlocking {
        val host = FakeTypingHost("say hello")
        val r = session(host, "hello", mode = TypeMode.REPLACE).run()
        assertTrue(r.message, r.success)
        assertEquals("typing must actually happen", 1, host.count("type"))
        assertEquals("hello", host.fieldText)
    }

    // ---- never fake success ------------------------------------------------------------------------------------------

    @Test fun mismatchingReadbackIsFailureNotSuccess() = runBlocking {
        val host = FakeTypingHost("")
        host.corruptWrite = true
        val r = session(host, "complete sentence here").run()
        assertFalse(r.success)
        assertEquals(TypingFailure.VERIFY_MISMATCH, r.failure)
        assertEquals(3, r.attempts)
    }

    @Test fun unreadableFieldCannotBeReportedAsSuccess() = runBlocking {
        val host = FakeTypingHost("")
        host.unreadable = true
        val r = session(host, "abc").run()
        assertFalse(r.success)
    }

    @Test fun protectedFieldIsBlockedAndNeverRetriedOrTyped() = runBlocking {
        val host = FakeTypingHost("")
        host.protectedField = "it is a password or secure input"
        val r = session(host, "hunter2").run()
        assertFalse(r.success)
        assertEquals(TypingState.BLOCKED, r.state)
        assertEquals(TypingFailure.PROTECTED_FIELD, r.failure)
        assertEquals(1, r.attempts)
        assertEquals(0, host.count("type"))
        assertTrue(r.message.contains("protected"))
    }

    @Test fun noEditableFieldReportsTheRealReason() = runBlocking {
        val host = FakeTypingHost("")
        host.hasField = false
        val r = session(host, "abc").run()
        assertFalse(r.success)
        assertEquals(TypingFailure.NO_EDITABLE_FIELD, r.failure)
        assertEquals(0, host.count("type"))
    }

    @Test fun blankTextIsRefused() = runBlocking {
        val r = session(FakeTypingHost(""), "   ").run()
        assertFalse(r.success)
        assertEquals(TypingFailure.BLANK_TEXT, r.failure)
        assertEquals(0, r.attempts)
    }

    // ---- bounded: never stuck in thinking/typing/waiting ----------------------------------------------------------------

    @Test fun overallDeadlineStopsAnEndlessSearch() = runBlocking {
        val host = FakeTypingHost("")
        host.hasField = false
        val cfg = fast.copy(fieldTimeoutMs = 60_000, overallDeadlineMs = 200)
        val t0 = System.currentTimeMillis()
        val r = session(host, "abc", config = cfg).run()
        assertFalse(r.success)
        assertTrue("took " + (System.currentTimeMillis() - t0), System.currentTimeMillis() - t0 < 3_000)
    }

    @Test fun cancellationFlagStopsImmediately() = runBlocking {
        val host = FakeTypingHost("")
        val r = session(host, "abc", cancelled = { true }).run()
        assertFalse(r.success)
        assertEquals(TypingState.CANCELLED, r.state)
        assertEquals(0, host.count("type"))
    }

    @Test fun coroutineCancellationInterruptsAWaitingSession() = runBlocking {
        val host = FakeTypingHost("")
        host.hasField = false
        val s = session(host, "abc", config = fast.copy(fieldTimeoutMs = 60_000, overallDeadlineMs = 120_000))
        val job = async { s.run() }
        delay(80)
        job.cancel()
        try { job.await() } catch (_: CancellationException) { }
        assertTrue(job.isCancelled)
        assertEquals(TypingState.CANCELLED, s.state)
        assertNotNull(job)
    }
}
