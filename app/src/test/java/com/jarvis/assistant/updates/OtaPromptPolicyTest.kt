package com.jarvis.assistant.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OtaPromptPolicyTest {
    private val installed = 144L

    @Test
    fun newerUnpostponedBuildIsOffered() {
        assertTrue(OtaPromptPolicy.shouldPrompt(testRelease(11752), installed, 34, 0L, emptySet()))
    }

    @Test
    fun noPopupWhenNotNewerThanInstalled() {
        assertFalse(OtaPromptPolicy.shouldPrompt(testRelease(144), installed, 34, 0L, emptySet()))
        assertFalse(OtaPromptPolicy.shouldPrompt(testRelease(100), installed, 34, 0L, emptySet()))
    }

    @Test
    fun laterSilencesThatVersionOnEveryLaunch() {
        val release = testRelease(11752)
        val postponed = OtaPromptPolicy.postponedAfterLater(0L, release)
        assertEquals(11752L, postponed)
        assertFalse(OtaPromptPolicy.shouldPrompt(release, installed, 34, postponed, emptySet()))
    }

    @Test
    fun aNewerBuildIsOfferedAgainAfterLater() {
        val postponed = OtaPromptPolicy.postponedAfterLater(0L, testRelease(11752))
        assertTrue(OtaPromptPolicy.shouldPrompt(testRelease(11753), installed, 34, postponed, emptySet()))
    }

    @Test
    fun laterNeverLowersAnEarlierPostponement() {
        assertEquals(11760L, OtaPromptPolicy.postponedAfterLater(11760L, testRelease(11752)))
    }

    @Test
    fun aBuildIsOfferedOnlyOncePerRun() {
        assertFalse(OtaPromptPolicy.shouldPrompt(testRelease(11752), installed, 34, 0L, setOf(11752L)))
    }

    @Test
    fun noPopupWhenAndroidIsTooOld() {
        assertFalse(OtaPromptPolicy.shouldPrompt(testRelease(11752, minSdk = 33), installed, 30, 0L, emptySet()))
    }

    @Test
    fun conciseNotesKeepOnlyChangeLinesAndDropHashAndHeader() {
        val notes = "JARVIS 1.8.8.11752 (build 11752) from branch Antigravity\n\nChanges:\n- Faster chat\n- Fix voice typing\n" +
            "- A\n- B\n- C\n\nSHA-256: " + "AB".repeat(32)
        assertEquals(listOf("Faster chat", "Fix voice typing", "A", "B"), OtaPromptPolicy.conciseNotes(notes))
    }

    @Test
    fun conciseNotesTrimLongLines() {
        val line = OtaPromptPolicy.conciseNotes("- " + "x".repeat(300)).single()
        assertEquals(90, line.length)
        assertTrue(line.endsWith("…"))
    }

    @Test
    fun conciseNotesFallBackToFirstPlainLine() {
        assertEquals(listOf("Plain note"), OtaPromptPolicy.conciseNotes("Plain note\nSecond line"))
        assertTrue(OtaPromptPolicy.conciseNotes("").isEmpty())
    }
}
