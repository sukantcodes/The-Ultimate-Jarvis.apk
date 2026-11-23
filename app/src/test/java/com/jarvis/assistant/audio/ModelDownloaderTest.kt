package com.jarvis.assistant.audio

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class ModelDownloaderTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private val payload = ByteArray(600_000) { (it * 31 % 251).toByte() }

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() { server.shutdown() }

    private fun sha(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    private fun spec(data: ByteArray = payload, sha: String = sha(data)) = ModelDownloader.Spec(server.url("/m.zip").toString(), sha, data.size.toLong())
    private fun body(bytes: ByteArray) = Buffer().write(bytes)
    private fun partFile() = File(tmp.root, "m.zip.part")

    @Test fun downloads_and_verifies_a_whole_file() {
        server.enqueue(MockResponse().setBody(body(payload)))
        val progress = mutableListOf<Long>()
        ModelDownloader().download(spec(), partFile()) { done, _ -> progress += done }
        assertArrayEquals(payload, partFile().readBytes())
        assertNull(server.takeRequest().getHeader("Range"))
        assertEquals(payload.size.toLong(), progress.last())
        assertTrue("progress should move forward", progress == progress.sorted())
    }

    @Test fun resumes_a_partial_download_from_where_it_stopped() {
        partFile().writeBytes(payload.copyOfRange(0, 200_000))
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 200000-599999/600000")
                .setBody(body(payload.copyOfRange(200_000, payload.size)))
        )
        ModelDownloader().download(spec(), partFile())
        assertEquals("bytes=200000-", server.takeRequest().getHeader("Range"))
        assertArrayEquals(payload, partFile().readBytes())
    }

    @Test fun restarts_cleanly_when_the_server_ignores_the_resume_request() {
        partFile().writeBytes(payload.copyOfRange(0, 200_000))
        server.enqueue(MockResponse().setBody(body(payload))) // plain 200 with the whole file
        ModelDownloader().download(spec(), partFile())
        assertArrayEquals(payload, partFile().readBytes())
    }

    @Test fun retries_from_scratch_once_when_the_server_says_the_range_is_invalid() {
        partFile().writeBytes(payload.copyOfRange(0, 200_000))
        server.enqueue(MockResponse().setResponseCode(416))
        server.enqueue(MockResponse().setBody(body(payload)))
        ModelDownloader().download(spec(), partFile())
        assertEquals("bytes=200000-", server.takeRequest().getHeader("Range"))
        assertNull(server.takeRequest().getHeader("Range"))
        assertArrayEquals(payload, partFile().readBytes())
    }

    @Test fun a_resume_that_starts_at_the_wrong_position_is_rejected() {
        partFile().writeBytes(payload.copyOfRange(0, 200_000))
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-599999/600000").setBody(body(payload)))
        try { ModelDownloader().download(spec(), partFile()); fail("expected failure") } catch (e: IOException) { /* expected */ }
    }

    @Test fun a_file_with_the_wrong_hash_is_deleted_and_reported() {
        server.enqueue(MockResponse().setBody(body(payload)))
        try {
            ModelDownloader().download(spec(sha = "0".repeat(64)), partFile())
            fail("expected a verification failure")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("damaged"))
        }
        assertFalse("a bad file must not be kept for the next try", partFile().exists())
    }

    @Test fun an_http_error_is_reported() {
        server.enqueue(MockResponse().setResponseCode(404))
        try { ModelDownloader().download(spec(), partFile()); fail("expected failure") } catch (e: IOException) {
            assertTrue(e.message!!.contains("404"))
        }
    }

    @Test fun an_already_complete_verified_file_is_not_downloaded_again() {
        partFile().writeBytes(payload)
        ModelDownloader().download(spec(), partFile())
        assertEquals(0, server.requestCount)
    }
}
