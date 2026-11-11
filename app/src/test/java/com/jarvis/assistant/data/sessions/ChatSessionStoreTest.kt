package com.jarvis.assistant.data.sessions

import com.jarvis.assistant.data.model.ChatTurn
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ChatSessionStoreTest {
    private val dir = Files.createTempDirectory("sess").toFile()
    private var now = 1000L
    private val store = ChatSessionStore(dir) { now++ }
    private fun turn(u: String, a: String, att: String? = null) = ChatTurn(userTranscript = u, jarvisResponse = a, attachmentPath = att)

    @Test fun createSaveLoadAndAutoTitle() {
        val s = store.create(); store.save(s.id, listOf(turn("How do I bake bread?\nsecond line", "Mix flour.")))
        assertEquals("How do I bake bread?", store.meta(s.id)!!.title); assertEquals(1, store.meta(s.id)!!.turnCount)
        assertEquals("Mix flour.", ChatSessionStore(dir).load(s.id).single().jarvisResponse)   // survives restart
    }

    @Test fun renamedTitleIsKeptAndOrderingPinsFirstThenNewest() {
        val a = store.create(); val b = store.create(); val c = store.create()
        store.save(a.id, listOf(turn("alpha", "x"))); store.rename(a.id, "My title"); store.save(a.id, listOf(turn("changed", "x")))
        assertEquals("My title", store.meta(a.id)!!.title)
        store.save(b.id, listOf(turn("beta", "x"))); store.save(c.id, listOf(turn("gamma", "x"))); store.pin(a.id, true)
        assertEquals(listOf(a.id, c.id, b.id), store.sessions().map { it.id })
    }

    @Test fun archiveHidesAndDeleteRemovesAndReturnsAttachments() {
        val s = store.create(); store.save(s.id, listOf(turn("q", "a", "/x/file.png")))
        store.archive(s.id, true); assertTrue(store.sessions().isEmpty()); assertEquals(1, store.sessions(includeArchived = true).size)
        assertEquals(listOf("/x/file.png"), store.delete(s.id)); assertNull(store.meta(s.id)); assertFalse(File(dir, "${s.id}.json").exists())
    }

    @Test fun searchFindsTitlesAndMessagesWithSnippet() {
        val a = store.create(); store.save(a.id, listOf(turn("tell me about kubernetes", "It orchestrates containers across machines.")))
        val b = store.create("Recipes"); store.save(b.id, listOf(turn("pasta", "boil water")))
        assertEquals(listOf(a.id), store.search("containers").map { it.first.id }); assertTrue(store.search("containers").first().second.contains("containers"))
        assertEquals(listOf(b.id), store.search("recipes").map { it.first.id }); assertTrue(store.search("  ").isEmpty()); assertTrue(store.search("zzz").isEmpty())
    }

    @Test fun damagedFileLosesOnlyThatChat() {
        val a = store.create(); val b = store.create(); store.save(a.id, listOf(turn("one", "1"))); store.save(b.id, listOf(turn("two", "2")))
        File(dir, "${a.id}.json").writeText("{broken"); assertTrue(store.load(a.id).isEmpty()); assertEquals("2", store.load(b.id).single().jarvisResponse)
        File(dir, "index.json").writeText("garbage"); assertTrue(store.sessions().isEmpty())
    }

    @Test fun exportMarkdown() {
        val s = store.create("T"); store.save(s.id, listOf(turn("hi", "hello"))); val md = store.exportMarkdown(s.id)
        assertTrue(md.startsWith("# T")); assertTrue(md.contains("**You:** hi")); assertTrue(md.contains("**Jarvis:** hello"))
    }
}
