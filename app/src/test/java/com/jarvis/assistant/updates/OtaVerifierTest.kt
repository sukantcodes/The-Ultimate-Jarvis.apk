package com.jarvis.assistant.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OtaVerifierTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val permanent = "76" + "3F7FBC95F8EAC7001EDC4159CA9354C591666AB5FE0FD7B450156AF58E09D4"
    private val other = "AA".repeat(32)

    private fun apk(bytes: ByteArray): Pair<File, String> {
        val file = temp.newFile()
        file.writeBytes(bytes)
        return file to OtaVerifier.sha256Hex(file)
    }

    @Test
    fun acceptsTheExactPublishedFile() {
        val (file, sha) = apk(ByteArray(2048) { it.toByte() })
        assertNull(OtaVerifier.verifyFile(file, testRelease(size = file.length(), sha = sha)))
    }

    @Test
    fun rejectsAMissingFile() {
        assertNotNull(OtaVerifier.verifyFile(File(temp.root, "nope.apk"), testRelease()))
    }

    @Test
    fun rejectsAnIncompleteDownloadBySize() {
        val (file, sha) = apk(ByteArray(1000))
        val problem = OtaVerifier.verifyFile(file, testRelease(size = 2000, sha = sha))
        assertTrue(problem!!.contains("incomplete"))
    }

    @Test
    fun rejectsAFileWithTheRightSizeButWrongChecksum() {
        val (file, _) = apk(ByteArray(1000) { 7 })
        val problem = OtaVerifier.verifyFile(file, testRelease(size = 1000, sha = other))
        assertTrue(problem!!.contains("checksum"))
    }

    @Test
    fun checksumComparisonIsAgainstUpperCaseHex() {
        val (file, sha) = apk("jarvis".toByteArray())
        assertEquals(sha, sha.uppercase())
        assertEquals(64, sha.length)
    }

    @Test
    fun acceptsApkSignedByThePublishedAndInstalledCertificate() {
        assertNull(OtaVerifier.checkSigners(setOf(permanent), setOf(permanent), permanent))
    }

    @Test
    fun rejectsAnUnsignedApk() {
        assertEquals("The APK is not signed.", OtaVerifier.checkSigners(emptySet(), setOf(permanent), permanent))
    }

    @Test
    fun rejectsAnApkSignedWithAnotherCertificateThanPublished() {
        val problem = OtaVerifier.checkSigners(setOf(other), setOf(permanent), permanent)
        assertTrue(problem!!.contains("published for this release"))
    }

    @Test
    fun rejectsAnApkWithAnExtraSigner() {
        assertNotNull(OtaVerifier.checkSigners(setOf(permanent, other), setOf(permanent), permanent))
    }

    @Test
    fun rejectsAnUpdateSignedWithADifferentKeyThanTheInstalledApp() {
        val problem = OtaVerifier.checkSigners(setOf(permanent), setOf(other), permanent)
        assertTrue(problem!!.contains("different key"))
        assertTrue(problem.contains("Your data is untouched"))
    }

    @Test
    fun publishedCertificateIsComparedCaseInsensitively() {
        assertNull(OtaVerifier.checkSigners(setOf(permanent), setOf(permanent), permanent.lowercase()))
    }
}
