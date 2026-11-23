package com.jarvis.assistant.updates

/** Pure decisions about whether and how to update. No Android types, so every rule is unit tested. */
object OtaPolicy {
    enum class Availability { AVAILABLE, UP_TO_DATE, NEEDS_NEWER_ANDROID, NO_RELEASE }

    /** A release is offered only when it is strictly newer than the installed build, so a downgrade is never possible. */
    fun availability(release: OtaRelease?, installedVersionCode: Long, sdkInt: Int): Availability {
        if (release == null) return Availability.NO_RELEASE
        if (release.versionCode <= installedVersionCode) return Availability.UP_TO_DATE
        if (sdkInt < release.minSdk) return Availability.NEEDS_NEWER_ANDROID
        return Availability.AVAILABLE
    }

    /**
     * The branch to actually use. A selected branch that the server no longer lists falls back to the default branch,
     * and the app never moves a person off the default branch on its own.
     */
    fun effectiveBranch(selected: String, listed: List<OtaBranch>, defaultBranch: String): String =
        if (listed.isEmpty() || listed.any { it.name == selected }) selected else defaultBranch

    fun shouldAutoDownload(
        autoDownloadEnabled: Boolean,
        availability: Availability,
        alreadyReadyOrActive: Boolean,
        failedAttempts: Int
    ): Boolean = autoDownloadEnabled &&
        availability == Availability.AVAILABLE &&
        !alreadyReadyOrActive &&
        failedAttempts < OtaConfig.AUTO_DOWNLOAD_MAX_ATTEMPTS

    /** True when a stored download id and the newly announced release describe the same build, so nothing is re-downloaded. */
    fun isSameBuild(a: OtaRelease?, b: OtaRelease?): Boolean =
        a != null && b != null && a.branch == b.branch && a.versionCode == b.versionCode && a.sha256 == b.sha256

    fun notifyAllowed(lastNotifiedCode: Long, release: OtaRelease): Boolean = release.versionCode > lastNotifiedCode

    fun describeBranch(name: String, defaultBranch: String): String =
        if (name == defaultBranch) "$name · default" else "$name · experimental"
}
