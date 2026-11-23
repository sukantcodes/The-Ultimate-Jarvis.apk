package com.jarvis.assistant.updates

/** Builds a valid [OtaRelease] for tests; override only what a test cares about. */
internal fun testRelease(
    code: Long = 11752,
    minSdk: Int = 26,
    size: Long = 150_000_000,
    sha: String = "AB".repeat(32),
    cert: String = "CD".repeat(32),
    notes: String = ""
) = OtaRelease(
    branch = "Antigravity", versionCode = code, versionName = "1.8.8.$code", tag = "ota-antigravity-$code",
    assetName = "jarvis-antigravity-$code.apk",
    apkUrl = OtaConfig.DOWNLOAD_PREFIX + "ota-antigravity-$code/jarvis-antigravity-$code.apk",
    sizeBytes = size, sha256 = sha, certSha256 = cert, minSdk = minSdk, minSupportedVersionCode = 1,
    releaseNotes = notes, commitSha = "e".repeat(40), publishedAt = "2026-11-23T10:00:00+00:00"
)
