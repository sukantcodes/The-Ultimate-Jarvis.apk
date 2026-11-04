package com.jarvis.assistant.automation.context

import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService

/**
 * Short-lived, in-memory view of the foreground app.
 *
 * Deliberately stores no screen text, notification content, contact data or credentials.
 * The value is derived from the active accessibility root, with the latest UI event as fallback.
 */
data class ForegroundAppSnapshot(
    val packageName: String,
    val uiRevision: Long,
    val observedAtMs: Long,
    val isFresh: Boolean
)

object AutomationContextSnapshot {
    fun current(maxAgeMs: Long = DEFAULT_MAX_AGE_MS): ForegroundAppSnapshot? {
        val service = JarvisAccessibilityService.instance ?: return null
        val event = JarvisAccessibilityService.uiChanges.value
        val now = System.currentTimeMillis()
        val activePackage = runCatching {
            service.getActiveRootNode()?.packageName?.toString().orEmpty()
        }.getOrDefault("")
        val packageName = activePackage.ifBlank { event.packageName }
        if (packageName.isBlank()) return null

        val observedAt = if (activePackage.isNotBlank()) now else event.timestamp
        val age = (now - observedAt).coerceAtLeast(0L)
        return ForegroundAppSnapshot(
            packageName = packageName,
            uiRevision = event.revision,
            observedAtMs = observedAt,
            isFresh = age <= maxAgeMs
        )
    }

    private const val DEFAULT_MAX_AGE_MS = 5_000L
}
