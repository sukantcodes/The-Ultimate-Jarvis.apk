package com.jarvis.assistant.tools.appcontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ChangeJournalTest {
    private var now = 1_000L
    private fun journal(file: File? = null, max: Int = 1000) = ChangeJournal(file, max) { now }

    @Test fun recordsWhatChangedAndWho() {
        val j = journal()
        j.record("jarvis_prefs", "key_app_theme", "light", "dark", "jarvis")
        val c = j.recent().single()
        assertEquals("light", c.before)
        assertEquals("dark", c.after)
        assertEquals("jarvis", c.actor)
        assertTrue(j.describe(c).contains("jarvis_prefs/key_app_theme"))
        assertTrue(j.describe(c).contains("\"light\" -> \"dark\""))
    }

    @Test fun secretValuesAreNeverKept() {
        val j = journal()
        j.record("api", "Groq.api_key", "sk-old-secret", "sk-new-secret", "jarvis")
        j.record("jarvis_prefs", "key_owner_voice_profile", "0.1,0.2", "0.3,0.4", "user or app")
        val all = j.recent()
        assertTrue(all.all { it.valueHidden && it.before == null && it.after == null })
        assertFalse(all.joinToString { j.describe(it) }.contains("secret"))
        assertTrue(j.describe(all.first()).contains("value hidden"))
    }

    @Test fun sensitiveAndNoiseKeysAreRecognised() {
        assertTrue(ChangeJournal.isSensitive("key_api_key"))
        assertTrue(ChangeJournal.isSensitive("github_access_token"))
        assertTrue(ChangeJournal.isSensitive("lock_pin"))
        assertTrue(ChangeJournal.isSensitive("key_chat_history"))
        assertFalse(ChangeJournal.isSensitive("key_app_theme"))
        assertFalse(ChangeJournal.isSensitive("key_typing_speed"))
        assertTrue(ChangeJournal.isNoise("key_task_overlay_x"))
        assertFalse(ChangeJournal.isNoise("key_orb_glow"))
    }

    @Test fun filtersBySourceTextAndTime() {
        val j = journal()
        now = 1_000; j.record("p", "a", "1", "2", "jarvis")
        now = 5_000; j.record("p", "b", "x", "y", "user or app")
        now = 9_000; j.record("p", "orb_glow", "1", "2", "jarvis")
        assertEquals(2, j.recent(actor = "jarvis").size)
        assertEquals(1, j.recent(actor = "user").size)
        assertEquals(listOf("orb_glow"), j.recent(search = "ORB").map { it.key })
        assertEquals(listOf("b", "orb_glow"), j.recent(sinceMs = 5_000).map { it.key })
        assertEquals(1, j.recent(limit = 1).size)
    }

    @Test fun keepsOnlyTheNewestWhenFull() {
        val j = journal(max = 3)
        (1..5).forEach { j.record("p", "k$it", null, "v", "jarvis") }
        assertEquals(listOf("k3", "k4", "k5"), j.recent().map { it.key })
        assertEquals(3, j.count())
    }

    @Test fun survivesARestartThroughItsFile() {
        val f = File(Files.createTempDirectory("journal").toFile(), "app_changes.jsonl")
        val first = journal(f)
        first.record("jarvis_prefs", "key_voice", "Puck", "Kore", "jarvis")
        first.record("api", "Groq.api_key", "a", "b", "jarvis")
        val second = journal(f)
        val kept = second.recent()
        assertEquals(2, kept.size)
        assertEquals("Kore", kept[0].after)
        assertTrue(kept[1].valueHidden)
        assertNull(kept[1].after)
        assertFalse(f.readText().contains("\"b\""))
    }

    @Test fun aDamagedFileStartsAgainEmptyInsteadOfFailing() {
        val f = File(Files.createTempDirectory("journal").toFile(), "app_changes.jsonl")
        f.writeText("not json at all\n{broken\n")
        assertEquals(0, journal(f).count())
    }

    @Test fun longValuesAreShortened() {
        val j = journal()
        j.record("p", "key_note", "", "x".repeat(1000), "user or app")
        assertEquals(ChangeJournal.MAX_VALUE, j.recent().single().after!!.length)
    }
}
