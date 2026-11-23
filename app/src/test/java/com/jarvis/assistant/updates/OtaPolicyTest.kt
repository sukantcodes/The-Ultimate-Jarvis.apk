package com.jarvis.assistant.updates

import com.jarvis.assistant.updates.OtaPolicy.Availability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OtaPolicyTest {
    private fun release(code: Long, minSdk: Int = 26, sha: String = "AB".repeat(32), branch: String = "Antigravity") = OtaRelease(
        branch = branch, versionCode = code, versionName = "1.8.7-ota.$code", tag = "ota-x-$code", assetName = "x-$code.apk",
        apkUrl = OtaConfig.DOWNLOAD_PREFIX + "ota-x-$code/x-$code.apk", sizeBytes = 150_000_000, sha256 = sha,
        certSha256 = "CD".repeat(32), minSdk = minSdk, minSupportedVersionCode = 1, releaseNotes = "", commitSha = "e".repeat(40),
        publishedAt = ""
    )

    @Test
    fun newerReleaseIsAvailable() {
        assertEquals(Availability.AVAILABLE, OtaPolicy.availability(release(1800), 1700, 34))
    }

    @Test
    fun sameOrOlderReleaseIsNeverOffered() {
        assertEquals(Availability.UP_TO_DATE, OtaPolicy.availability(release(1700), 1700, 34))
        assertEquals(Availability.UP_TO_DATE, OtaPolicy.availability(release(1600), 1700, 34))
    }

    @Test
    fun noReleaseMeansNothingPublished() {
        assertEquals(Availability.NO_RELEASE, OtaPolicy.availability(null, 143, 34))
    }

    @Test
    fun incompatibleAndroidIsReported() {
        assertEquals(Availability.NEEDS_NEWER_ANDROID, OtaPolicy.availability(release(1800, minSdk = 30), 1700, 28))
        assertEquals(Availability.AVAILABLE, OtaPolicy.availability(release(1800, minSdk = 30), 1700, 30))
    }

    @Test
    fun missingBranchFallsBackToDefaultButListedBranchIsKept() {
        val listed = listOf(OtaBranch("Antigravity", true, ""), OtaBranch("Antigravity-exp", false, ""))
        assertEquals("Antigravity-exp", OtaPolicy.effectiveBranch("Antigravity-exp", listed, "Antigravity"))
        assertEquals("Antigravity", OtaPolicy.effectiveBranch("Gone", listed, "Antigravity"))
        assertEquals("Antigravity", OtaPolicy.effectiveBranch("Antigravity", listed, "Antigravity"))
        // With no listing available the choice is left alone.
        assertEquals("Antigravity-exp", OtaPolicy.effectiveBranch("Antigravity-exp", emptyList(), "Antigravity"))
    }

    @Test
    fun autoDownloadRules() {
        assertTrue(OtaPolicy.shouldAutoDownload(true, Availability.AVAILABLE, false, 0))
        assertFalse(OtaPolicy.shouldAutoDownload(false, Availability.AVAILABLE, false, 0))
        assertFalse(OtaPolicy.shouldAutoDownload(true, Availability.UP_TO_DATE, false, 0))
        assertFalse(OtaPolicy.shouldAutoDownload(true, Availability.AVAILABLE, true, 0))
        assertTrue(OtaPolicy.shouldAutoDownload(true, Availability.AVAILABLE, false, OtaConfig.AUTO_DOWNLOAD_MAX_ATTEMPTS - 1))
        assertFalse(OtaPolicy.shouldAutoDownload(true, Availability.AVAILABLE, false, OtaConfig.AUTO_DOWNLOAD_MAX_ATTEMPTS))
    }

    @Test
    fun sameBuildDetectionPreventsRedownload() {
        assertTrue(OtaPolicy.isSameBuild(release(1800), release(1800)))
        assertFalse(OtaPolicy.isSameBuild(release(1800), release(1801)))
        assertFalse(OtaPolicy.isSameBuild(release(1800), release(1800, sha = "00".repeat(32))))
        assertFalse(OtaPolicy.isSameBuild(null, release(1800)))
    }

    @Test
    fun notifiesOncePerVersion() {
        assertTrue(OtaPolicy.notifyAllowed(1700, release(1800)))
        assertFalse(OtaPolicy.notifyAllowed(1800, release(1800)))
        assertFalse(OtaPolicy.notifyAllowed(1900, release(1800)))
    }

    @Test
    fun branchLabels() {
        assertEquals("Antigravity · default", OtaPolicy.describeBranch("Antigravity", "Antigravity"))
        assertEquals("exp · experimental", OtaPolicy.describeBranch("exp", "Antigravity"))
    }
}
