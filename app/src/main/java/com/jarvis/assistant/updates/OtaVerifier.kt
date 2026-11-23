package com.jarvis.assistant.updates

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.jarvis.assistant.BuildConfig
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Everything that must be true of a downloaded APK before Android is asked to install it. Each check returns a
 * plain-language reason when it fails and null when it passes, so the updater can show exactly what went wrong.
 */
object OtaVerifier {
    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02X".format(it) }
    }

    /** Whole-file check: exact size and SHA-256 as published by the verified release metadata. */
    fun verifyFile(file: File, release: OtaRelease): String? {
        if (!file.isFile) return "The downloaded file is missing."
        if (file.length() != release.sizeBytes) {
            return "The download is incomplete or the wrong size (${file.length()} of ${release.sizeBytes} bytes)."
        }
        if (sha256Hex(file) != release.sha256) return "The download's checksum does not match the published release."
        return null
    }

    /** Package name, version, signing certificate and compatibility, read from the APK itself. */
    fun verifyArchive(context: Context, file: File, release: OtaRelease): String? {
        val pm = context.packageManager
        val archive = archiveInfo(pm, file) ?: return "Android could not read the downloaded APK."
        if (archive.packageName != OtaConfig.PACKAGE_NAME || archive.packageName != context.packageName) {
            return "This APK is not a JARVIS update."
        }
        val archiveVersion = versionCodeOf(archive)
        if (archiveVersion != release.versionCode) return "The APK's version does not match the published release."
        if (archiveVersion <= BuildConfig.VERSION_CODE.toLong()) {
            return "This build is not newer than the installed JARVIS, so Android would treat it as a downgrade."
        }
        if (Build.VERSION.SDK_INT < release.minSdk) return "This update needs a newer version of Android."

        val archiveSigners = certificateDigests(archive)
        if (archiveSigners.isEmpty()) return "The APK is not signed."
        if (archiveSigners != setOf(release.certSha256)) {
            return "The APK is not signed with the certificate published for this release."
        }
        val installedSigners = certificateDigests(installedInfo(pm, context.packageName))
        if (installedSigners.isEmpty() || installedSigners != archiveSigners) {
            return "This update is signed with a different key than the JARVIS installed on this phone, so Android " +
                "would refuse to update it in place. Your data is untouched. A one-time reinstall of a release-signed " +
                "build is needed (back up first); updates after that apply in place."
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, file: File): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        return pm.getPackageArchiveInfo(file.absolutePath, flags)
    }

    @Suppress("DEPRECATION")
    private fun installedInfo(pm: PackageManager, packageName: String): PackageInfo {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        return pm.getPackageInfo(packageName, flags)
    }

    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun certificateDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02X".format(it) }
        }.toSet()
    }
}
