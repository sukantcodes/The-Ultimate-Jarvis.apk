package com.jarvis.assistant.updates

/** Pure rules for the "Update Available" popup shown when JARVIS opens. No Android types, so every rule is unit tested. */
object OtaPromptPolicy {
    /**
     * The popup appears only for a build that is newer than the installed one, runs on this Android version, was not
     * postponed with "Later", and was not already offered during this run of the app. A newer build than the postponed one
     * is offered again.
     */
    fun shouldPrompt(
        release: OtaRelease,
        installedCode: Long,
        sdkInt: Int,
        postponedCode: Long,
        promptedThisRun: Set<Long>
    ): Boolean =
        release.versionCode > installedCode &&
            sdkInt >= release.minSdk &&
            release.versionCode > postponedCode &&
            release.versionCode !in promptedThisRun

    /** What "Later" remembers: never lowers an earlier postponement. */
    fun postponedAfterLater(previous: Long, release: OtaRelease): Long = maxOf(previous, release.versionCode)

    /**
     * A few short lines for the popup. The published notes are a header, a "Changes:" list of "- " lines and a trailing
     * "SHA-256:" line; only the change lines are shown. Notes without any list fall back to their first plain line.
     */
    fun conciseNotes(notes: String, maxLines: Int = 4, maxLineChars: Int = 90): List<String> {
        val lines = notes.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("SHA-256", ignoreCase = true) }
        val bullets = lines.filter { it.startsWith("- ") }.map { it.removePrefix("- ").trim() }.filter { it.isNotEmpty() }
        val chosen = if (bullets.isNotEmpty()) bullets else lines.filterNot { it.endsWith(":") }.take(1)
        return chosen.take(maxLines).map { if (it.length > maxLineChars) it.take(maxLineChars - 1).trimEnd() + "…" else it }
    }
}
