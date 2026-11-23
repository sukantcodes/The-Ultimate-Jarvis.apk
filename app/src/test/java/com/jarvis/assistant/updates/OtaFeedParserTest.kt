package com.jarvis.assistant.updates

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OtaFeedParserTest {
    private val sha = "AB".repeat(32)
    private val cert = "CD".repeat(32)
    private val commit = "e".repeat(40)

    private fun releaseJson(
        branch: String = "Antigravity",
        code: Long = 1800,
        tag: String = "ota-antigravity-1800",
        asset: String = "jarvis-antigravity-1800.apk",
        url: String? = null,
        size: Long = 150_000_000,
        shaValue: String = sha
    ): JSONObject = JSONObject()
        .put("branch", branch).put("version_code", code).put("version_name", "1.8.7-ota.$code")
        .put("tag", tag).put("asset_name", asset)
        .put("apk_url", url ?: (OtaConfig.DOWNLOAD_PREFIX + tag + "/" + asset))
        .put("size_bytes", size).put("sha256", shaValue).put("cert_sha256", cert)
        .put("min_sdk", 26).put("min_supported_version_code", 1)
        .put("release_notes", "- Fix orb glow").put("commit_sha", commit)
        .put("published_at", "2026-11-22T12:00:00.123+00:00")

    private fun feed(release: JSONObject?, history: List<JSONObject> = emptyList(), branch: String = "Antigravity"): String {
        val root = JSONObject().put("branch", branch).put("default_branch", "Antigravity")
        val branches = org.json.JSONArray()
        branches.put(JSONObject().put("branch", "Antigravity").put("is_default", true).put("description", "Primary"))
        branches.put(JSONObject().put("branch", "Antigravity-exp").put("is_default", false).put("description", "Experiment"))
        root.put("branches", branches)
        if (release != null) root.put("release", release)
        val h = org.json.JSONArray()
        history.forEach { h.put(it) }
        root.put("history", h)
        return root.toString()
    }

    private fun assertRejected(json: String) {
        try {
            OtaFeedParser.parse(json)
            fail("expected the feed to be rejected")
        } catch (_: OtaFormatException) {
            // expected
        }
    }

    @Test
    fun parsesValidFeed() {
        val parsed = OtaFeedParser.parse(feed(releaseJson(), listOf(releaseJson(code = 1799, tag = "ota-antigravity-1799", asset = "jarvis-antigravity-1799.apk"))))
        assertEquals("Antigravity", parsed.branch)
        assertEquals("Antigravity", parsed.defaultBranch)
        assertEquals(2, parsed.branches.size)
        assertTrue(parsed.branches.first { it.name == "Antigravity" }.isDefault)
        val release = parsed.release
        assertNotNull(release)
        assertEquals(1800L, release!!.versionCode)
        assertEquals(sha, release.sha256)
        assertEquals(150_000_000L, release.sizeBytes)
        assertEquals(2, parsed.history.size + 1)
    }

    @Test
    fun feedWithoutReleaseIsValid() {
        val parsed = OtaFeedParser.parse(feed(null))
        assertNull(parsed.release)
        assertTrue(parsed.history.isEmpty())
    }

    @Test
    fun uppercasesDigests() {
        val parsed = OtaFeedParser.parse(feed(releaseJson(shaValue = sha.lowercase())))
        assertEquals(sha, parsed.release!!.sha256)
    }

    @Test
    fun rejectsDownloadFromAnyOtherHost() {
        assertRejected(feed(releaseJson(url = "https://evil.example/jarvis.apk")))
        assertRejected(feed(releaseJson(url = "https://yeggjbjcoaaoqntqyjdv.supabase.co/storage/v1/object/public/jarvis-releases/a.apk")))
        assertRejected(feed(releaseJson(url = "http://github.com/${OtaConfig.REPOSITORY}/releases/download/ota-antigravity-1800/jarvis-antigravity-1800.apk")))
    }

    @Test
    fun rejectsOtherRepositoryAndMismatchedAsset() {
        assertRejected(feed(releaseJson(url = "https://github.com/attacker/fork/releases/download/ota-antigravity-1800/jarvis-antigravity-1800.apk")))
        assertRejected(feed(releaseJson(url = OtaConfig.DOWNLOAD_PREFIX + "ota-antigravity-1800/other.apk")))
        assertRejected(feed(releaseJson(url = OtaConfig.DOWNLOAD_PREFIX + "ota-antigravity-1800/jarvis-antigravity-1800.apk?x=1")))
        assertRejected(feed(releaseJson(url = OtaConfig.DOWNLOAD_PREFIX + "ota-antigravity-1800/../x/jarvis-antigravity-1800.apk")))
    }

    @Test
    fun rejectsMalformedFields() {
        assertRejected(feed(releaseJson(shaValue = "xyz")))
        assertRejected(feed(releaseJson(size = 0)))
        assertRejected(feed(releaseJson(size = OtaConfig.MAX_APK_BYTES + 1)))
        assertRejected(feed(releaseJson(code = 0)))
        assertRejected(feed(releaseJson(asset = "evil.exe")))
        assertRejected(feed(releaseJson(tag = "bad tag")))
    }

    @Test
    fun rejectsReleaseFromAnotherBranch() {
        assertRejected(feed(releaseJson(branch = "Antigravity-exp")))
    }

    @Test
    fun rejectsGarbage() {
        assertRejected("not json")
        assertRejected("{}")
        assertRejected("{\"branch\":\"bad branch!\"}")
    }

    @Test
    fun badHistoryRowIsSkippedAndDoesNotHideTheNewestRelease() {
        val bad = releaseJson(code = 1700, tag = "ota-antigravity-1700", asset = "jarvis-antigravity-1700.apk", shaValue = "nope")
        val parsed = OtaFeedParser.parse(feed(releaseJson(), listOf(bad)))
        assertEquals(1800L, parsed.release!!.versionCode)
        assertTrue(parsed.history.isEmpty())
    }

    @Test
    fun releaseRoundTripsThroughJson() {
        val original = OtaFeedParser.parse(feed(releaseJson())).release!!
        val again = OtaFeedParser.parseRelease(original.toJson(), "Antigravity")
        assertEquals(original, again)
    }
}
