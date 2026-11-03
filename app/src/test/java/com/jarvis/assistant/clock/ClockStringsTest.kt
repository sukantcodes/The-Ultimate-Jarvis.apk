package com.jarvis.assistant.clock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** The Clock strings: every translation has only keys the English file has, with the same placeholders, and the three fully translated languages are complete. */
class ClockStringsTest {

    private fun resDir(): File = listOf("src/main/res", "app/src/main/res").map { File(it) }.first { it.exists() }

    private fun load(file: File): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate { nodes.item(it).attributes.getNamedItem("name").nodeValue to nodes.item(it).textContent }
    }

    private fun placeholders(s: String) = Regex("%\\d\\$[ds]").findAll(s).map { it.value }.sorted().toList()

    private val english by lazy { load(File(resDir(), "values/strings_clock.xml")) }
    private val languages = listOf("hi", "bn", "kn", "ta", "te", "ml", "mr", "gu", "pa", "ur", "or", "as")

    @Test fun everyLanguageFileExistsAndOnlyUsesKnownKeys() {
        for (lang in languages) {
            val f = File(resDir(), "values-$lang/strings_clock.xml")
            assertTrue("missing $f", f.exists())
            for ((k, v) in load(f)) {
                assertTrue("$lang has unknown key $k", english.containsKey(k))
                assertEquals("$lang/$k placeholders", placeholders(english.getValue(k)), placeholders(v))
                assertTrue("$lang/$k is blank", v.isNotBlank())
            }
        }
    }

    @Test fun hindiBengaliAndKannadaAreComplete() {
        for (lang in listOf("hi", "bn", "kn")) {
            val m = load(File(resDir(), "values-$lang/strings_clock.xml"))
            assertEquals("$lang is missing: ${english.keys - m.keys}", english.keys, m.keys)
        }
    }

    @Test fun everyClockStringUsedInCodeExists() {
        val src = listOf("src/main/java", "app/src/main/java").map { File(it) }.first { it.exists() }
        val used = HashSet<String>()
        src.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            Regex("R\\.string\\.(clock_[a-z0-9_]+)").findAll(f.readText()).forEach { used += it.groupValues[1] }
        }
        assertTrue(used.isNotEmpty())
        assertEquals("used but undefined: ${used - english.keys}", emptySet<String>(), used - english.keys)
    }
}
