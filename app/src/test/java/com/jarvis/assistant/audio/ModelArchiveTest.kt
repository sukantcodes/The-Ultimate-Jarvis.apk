package com.jarvis.assistant.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ModelArchiveTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun zipOf(vararg entries: Pair<String, String>): File {
        val f = tmp.newFile()
        ZipOutputStream(f.outputStream()).use { z ->
            for ((name, text) in entries) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
        }
        return f
    }

    @Test fun sha256_matches_the_known_value() {
        val f = tmp.newFile().apply { writeText("abc") }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ModelArchive.sha256(f))
    }

    @Test fun extracts_files_and_nested_folders() {
        val dest = File(tmp.root, "out")
        ModelArchive.extract(zipOf("conf/model.conf" to "a", "am/final.mdl" to "bb", "top.txt" to "c"), dest)
        assertEquals("a", File(dest, "conf/model.conf").readText())
        assertEquals("bb", File(dest, "am/final.mdl").readText())
        assertEquals("c", File(dest, "top.txt").readText())
    }

    @Test fun an_entry_that_escapes_the_target_folder_fails_the_whole_unpack() {
        val dest = File(tmp.root, "out")
        try {
            ModelArchive.extract(zipOf("ok.txt" to "fine", "../evil.txt" to "bad"), dest)
            fail("expected the unsafe path to be rejected")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("Unsafe path"))
        }
        assertFalse("nothing may be written outside the target", File(tmp.root, "evil.txt").exists())
    }
}
