package com.jarvis.assistant.updates

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Fixed facts about where JARVIS updates come from. Nothing here is user-editable. */
object OtaConfig {
    const val REPOSITORY = "sukantcodes/The-Ultimate-Jarvis.apk"
    const val DEFAULT_BRANCH = "Antigravity"
    const val PACKAGE_NAME = "com.jarvis.assistant"
    const val MAX_APK_BYTES = 400_000_000L
    const val DOWNLOAD_PREFIX = "https://github.com/" + REPOSITORY + "/releases/download/"
    const val AUTO_DOWNLOAD_MAX_ATTEMPTS = 3
}

/** One published build. Every field has been validated by [OtaFeedParser]; the APK itself lives in GitHub Releases. */
data class OtaRelease(
    val branch: String,
    val versionCode: Long,
    val versionName: String,
    val tag: String,
    val assetName: String,
    val apkUrl: String,
    val sizeBytes: Long,
    val sha256: String,
    val certSha256: String,
    val minSdk: Int,
    val minSupportedVersionCode: Long,
    val releaseNotes: String,
    val commitSha: String,
    val publishedAt: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("branch", branch)
        .put("version_code", versionCode)
        .put("version_name", versionName)
        .put("tag", tag)
        .put("asset_name", assetName)
        .put("apk_url", apkUrl)
        .put("size_bytes", sizeBytes)
        .put("sha256", sha256)
        .put("cert_sha256", certSha256)
        .put("min_sdk", minSdk)
        .put("min_supported_version_code", minSupportedVersionCode)
        .put("release_notes", releaseNotes)
        .put("commit_sha", commitSha)
        .put("published_at", publishedAt)
}

data class OtaBranch(val name: String, val isDefault: Boolean, val description: String)

data class OtaFeed(
    val branch: String,
    val defaultBranch: String,
    val branches: List<OtaBranch>,
    val release: OtaRelease?,
    val history: List<OtaRelease>
)

class OtaFormatException(message: String) : Exception(message)

/**
 * Strict parser for the `ota-updates` feed. A release is accepted only when its download address is exactly the
 * GitHub Releases URL of this repository for its own tag and asset name, and its digests are well formed. Anything
 * else is rejected so a tampered or malformed feed can never point the app at an untrusted file.
 */
object OtaFeedParser {
    private val HEX64 = Regex("^[A-F0-9]{64}$")
    private val COMMIT = Regex("^[0-9a-f]{40}$")
    private val BRANCH = Regex("^[A-Za-z0-9][A-Za-z0-9._/-]{0,98}$")
    private val TAG = Regex("^[A-Za-z0-9._-]{1,120}$")
    private val ASSET = Regex("^[A-Za-z0-9._-]{1,120}\\.apk$")
    private val VERSION_NAME = Regex("^[A-Za-z0-9._+-]{1,40}$")

    fun parse(body: String): OtaFeed {
        val root = try {
            JSONObject(body)
        } catch (_: JSONException) {
            throw OtaFormatException("The update feed is not valid JSON.")
        }
        val branch = root.optString("branch", "")
        if (!BRANCH.matches(branch)) throw OtaFormatException("The update feed names an invalid branch.")
        val defaultBranch = root.optString("default_branch", OtaConfig.DEFAULT_BRANCH)
            .takeIf { BRANCH.matches(it) } ?: OtaConfig.DEFAULT_BRANCH

        val branches = ArrayList<OtaBranch>()
        val branchArray = root.optJSONArray("branches") ?: JSONArray()
        for (i in 0 until branchArray.length()) {
            val item = branchArray.optJSONObject(i) ?: continue
            val name = item.optString("name", item.optString("branch", ""))
            if (!BRANCH.matches(name)) continue
            branches.add(OtaBranch(name, item.optBoolean("is_default", false), item.optString("description", "").take(200)))
        }

        val release = root.optJSONObject("release")?.let { parseRelease(it, branch) }
        val history = ArrayList<OtaRelease>()
        val historyArray = root.optJSONArray("history") ?: JSONArray()
        for (i in 0 until historyArray.length()) {
            val item = historyArray.optJSONObject(i) ?: continue
            try {
                history.add(parseRelease(item, branch))
            } catch (_: OtaFormatException) {
                // A bad historical row is skipped; it must never hide a valid newest release.
            }
        }
        return OtaFeed(branch, defaultBranch, branches, release, history)
    }

    fun parseRelease(obj: JSONObject, expectedBranch: String): OtaRelease {
        fun bad(what: String): Nothing = throw OtaFormatException("Release validation failed: $what.")
        val branch = obj.optString("branch", "")
        if (branch != expectedBranch) bad("branch mismatch")
        val code = obj.optLong("version_code", -1L)
        if (code <= 0L) bad("version code")
        val name = obj.optString("version_name", "")
        if (!VERSION_NAME.matches(name)) bad("version name")
        val tag = obj.optString("tag", "")
        if (!TAG.matches(tag)) bad("tag")
        val asset = obj.optString("asset_name", "")
        if (!ASSET.matches(asset)) bad("asset name")
        val url = obj.optString("apk_url", "")
        val expectedSuffix = "$tag/$asset"
        if (!url.startsWith(OtaConfig.DOWNLOAD_PREFIX, ignoreCase = true) ||
            url.substring(OtaConfig.DOWNLOAD_PREFIX.length) != expectedSuffix
        ) {
            bad("untrusted download address")
        }
        val size = obj.optLong("size_bytes", -1L)
        if (size < 1L || size > OtaConfig.MAX_APK_BYTES) bad("size")
        val sha = obj.optString("sha256", "").uppercase()
        if (!HEX64.matches(sha)) bad("checksum")
        val cert = obj.optString("cert_sha256", "").uppercase()
        if (!HEX64.matches(cert)) bad("certificate digest")
        val minSdk = obj.optInt("min_sdk", 26)
        if (minSdk < 1 || minSdk > 99) bad("minimum SDK")
        val minSupported = obj.optLong("min_supported_version_code", 1L)
        if (minSupported < 1L || minSupported > code) bad("minimum supported version")
        val commit = obj.optString("commit_sha", "")
        if (!COMMIT.matches(commit)) bad("commit")
        return OtaRelease(
            branch = branch,
            versionCode = code,
            versionName = name,
            tag = tag,
            assetName = asset,
            apkUrl = url,
            sizeBytes = size,
            sha256 = sha,
            certSha256 = cert,
            minSdk = minSdk,
            minSupportedVersionCode = minSupported,
            releaseNotes = obj.optString("release_notes", "").take(8000),
            commitSha = commit,
            publishedAt = obj.optString("published_at", "")
        )
    }
}
