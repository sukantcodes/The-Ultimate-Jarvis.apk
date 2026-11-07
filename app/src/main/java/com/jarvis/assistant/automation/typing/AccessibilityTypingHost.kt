package com.jarvis.assistant.automation.typing

import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [TypingHost] backed by [JarvisAccessibilityService]. Works on any app that exposes an accessible text
 * field; it contains no per-app package lists.
 */
class AccessibilityTypingHost : TypingHost {

    private val service: JarvisAccessibilityService? get() = JarvisAccessibilityService.instance

    override fun foregroundPackage(): String? = service?.foregroundPackageName()

    /** Reacts to real accessibility events (window change, content change, focus) instead of sleeping. */
    override suspend fun awaitUiChange(timeoutMs: Long): Boolean {
        if (timeoutMs <= 0) return false
        val start = JarvisAccessibilityService.uiChanges.value.revision
        return withTimeoutOrNull(timeoutMs) {
            JarvisAccessibilityService.uiChanges.first { it.revision > start }
        } != null
    }

    override fun probeField(preferAddressBar: Boolean): FieldProbe =
        service?.probeTypingField(preferAddressBar) ?: FieldProbe.NotFound

    override fun requestFocus(handle: Long): Boolean = service?.requestTypingFocus(handle) ?: false

    override fun readiness(handle: Long): Readiness = service?.typingFieldReadiness(handle) ?: Readiness.STALE

    override fun readField(handle: Long): String? = service?.readTypingField(handle)

    /** The strategy chain blocks briefly (it polls the live field); run it interruptibly off the caller's thread. */
    override suspend fun typeInto(handle: Long, text: String, allowAddressBar: Boolean): TypeAttempt =
        runInterruptible(Dispatchers.IO) {
            service?.typeIntoTypingField(handle, text) ?: TypeAttempt(false, "accessibility service is not running")
        }
}
