package com.jarvis.assistant.automation.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvis.assistant.security.UnlockCredentialStore
import com.jarvis.assistant.security.UnlockCredentialStore.LockType
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Wakes the screen, brings up the lock-screen credential UI and enters the saved PIN, password or
 * pattern through the accessibility service.
 *
 * Safety rules that are enforced in code:
 *  - Only windows owned by a trusted system UI / keyguard package are ever touched, so the credential
 *    can never be typed into a normal app.
 *  - The credential is entered at most once per call. Two failed automatic entries pause automation
 *    for a while so the phone's own lock-out is not triggered.
 *  - The credential is never logged and never included in a returned message.
 */
class ScreenUnlocker(
    private val service: JarvisAccessibilityService,
    private val store: UnlockCredentialStore
) {

    data class Result(val success: Boolean, val message: String)

    private companion object {
        const val TAG = "ScreenUnlocker"
        val unlockMutex = Mutex()

        val KNOWN_KEYGUARD_PACKAGES = setOf(
            "com.android.systemui",
            "com.android.keyguard",
            "com.miui.keyguard"
        )

        val DIGIT_ID_PATTERNS = listOf(
            Regex("^key_?(\\d)$"),
            Regex("^digit_?(\\d)$"),
            Regex("^num(?:ber)?_?(?:pad_?)?(?:key_?)?(\\d)$"),
            Regex("^button_?(\\d)$")
        )

        val ENTER_LABELS = setOf("enter", "ok", "done", "submit", "unlock", "confirm")
    }

    // ------------------------------------------------------------------------------------------
    // Entry point
    // ------------------------------------------------------------------------------------------

    suspend fun unlock(): Result {
        if (!unlockMutex.tryLock()) return Result(false, "An unlock is already in progress.")
        try {
            return runUnlock()
        } finally {
            unlockMutex.unlock()
        }
    }

    private suspend fun runUnlock(): Result {
        val km = service.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val pm = service.getSystemService(Context.POWER_SERVICE) as PowerManager

        if (pm.isInteractive && !km.isKeyguardLocked) {
            return Result(true, "The phone is already unlocked.")
        }

        val secure = km.isDeviceSecure
        val type = store.type()
        val secret = store.secret().orEmpty()
        if (secure) {
            if (type == null || secret.isEmpty()) {
                return Result(
                    false,
                    "No screen-lock PIN, password or pattern is saved yet. Open Jarvis Settings, then Screen Unlock, and save it first."
                )
            }
            val blocked = store.blockedForMillis()
            if (blocked > 0L) {
                val minutes = (blocked + 59_999L) / 60_000L
                return Result(
                    false,
                    "Automatic unlock is paused for about $minutes more minutes after failed attempts, to protect the phone from lock-out. Unlock it by hand once and check the saved credential in Screen Unlock settings."
                )
            }
        }

        val info = service.serviceInfo
        val originalFlags = info?.flags
        var wakeLock: PowerManager.WakeLock? = null
        try {
            // Lock-screen keys are sometimes marked "not important for accessibility". Include them
            // for the duration of the unlock only, so normal screen reading elsewhere is unchanged.
            if (info != null) {
                info.flags = info.flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                service.serviceInfo = info
            }

            wakeLock = wakeScreen(pm)
            if (!waitUntil(3_000L) { pm.isInteractive }) {
                return Result(false, "I could not turn the screen on. Check that battery optimization is not blocking Jarvis.")
            }
            delay(400L)

            if (!km.isKeyguardLocked) return Result(true, "Phone unlocked.")

            if (!secure) return unlockSwipeOnly(km)

            val lockType = type ?: return Result(false, "No lock type saved.")
            return unlockSecure(km, lockType, secret)
        } finally {
            try {
                wakeLock?.let { if (it.isHeld) it.release() }
            } catch (_: Exception) {
            }
            try {
                if (info != null && originalFlags != null) {
                    info.flags = originalFlags
                    service.serviceInfo = info
                }
            } catch (_: Exception) {
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Lock types
    // ------------------------------------------------------------------------------------------

    private suspend fun unlockSwipeOnly(km: KeyguardManager): Result {
        repeat(4) {
            if (km.isKeyguardLocked) {
                swipeUp()
                delay(700L)
            }
        }
        return if (!km.isKeyguardLocked) {
            Result(true, "Phone unlocked.")
        } else {
            Result(false, "I swiped up but the lock screen is still showing.")
        }
    }

    private suspend fun unlockSecure(km: KeyguardManager, lockType: LockType, secret: String): Result {
        val label = when (lockType) {
            LockType.PIN -> "PIN pad"
            LockType.PASSWORD -> "password field"
            LockType.PATTERN -> "pattern grid"
        }

        // 1) Reveal the credential screen (swipe up as needed) and confirm it is the expected kind.
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        var swipes = 0
        var ready = false
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!km.isKeyguardLocked) return Result(true, "Phone unlocked.")
            val roots = keyguardRoots()
            if (isSurfaceVisible(lockType, roots)) {
                ready = true
                break
            }
            val mismatch = detectDifferentLockType(lockType, roots)
            if (mismatch != null) return Result(false, mismatch)
            if (swipes < 5) {
                swipeUp()
                swipes++
            }
            delay(600L)
        }
        if (!ready) {
            return Result(
                false,
                "I could not find the $label on the lock screen. Make sure the saved lock type matches your phone's lock, and that the Jarvis accessibility service is on."
            )
        }

        // 2) Enter the credential exactly once.
        val entryError = when (lockType) {
            LockType.PIN -> enterPin(km, secret)
            LockType.PASSWORD -> enterPassword(secret)
            LockType.PATTERN -> enterPattern(secret)
        }
        if (entryError != null) return Result(false, entryError)

        // 3) Verify.
        val unlocked = waitUntil(4_500L, 150L) { !km.isKeyguardLocked }
        return if (unlocked) {
            store.recordSuccess()
            Result(true, "Phone unlocked.")
        } else {
            store.recordFailure()
            Result(
                false,
                "I entered the saved credential but the phone is still locked. Please re-check the lock type and value in Screen Unlock settings."
            )
        }
    }

    private suspend fun enterPin(km: KeyguardManager, pin: String): String? {
        for ((index, digit) in pin.withIndex()) {
            if (!km.isKeyguardLocked) return null
            var key: AccessibilityNodeInfo? = null
            repeat(3) {
                if (key == null) {
                    key = collectDigitKeys(keyguardRoots())[digit]
                    if (key == null) delay(150L)
                }
            }
            val node = key ?: return "The PIN pad disappeared while I was entering digit ${index + 1}."
            if (!service.clickNodeAndTap(node)) return "I could not tap a key on the PIN pad."
            delay(130L)
        }
        // Many phones accept a 6-digit PIN automatically. If the pad is still there, press Enter.
        delay(450L)
        if (km.isKeyguardLocked) {
            findEnterButton(keyguardRoots())?.let { service.clickNodeAndTap(it) }
        }
        return null
    }

    private suspend fun enterPassword(password: String): String? {
        var field: AccessibilityNodeInfo? = null
        repeat(3) {
            if (field == null) {
                field = findPasswordField(keyguardRoots())
                if (field == null) delay(200L)
            }
        }
        val node = field ?: return "I could not find the password field on the lock screen."
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        delay(200L)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, password)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            return "This phone's lock screen would not accept automatic typing. A PIN or pattern works more reliably."
        }
        delay(250L)
        var submitted = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            submitted = node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        if (!submitted) {
            findEnterButton(keyguardRoots())?.let { submitted = service.clickNodeAndTap(it) }
        }
        return if (submitted) null else "I typed the password but could not find the Enter key."
    }

    private suspend fun enterPattern(sequence: String): String? {
        val node = findPatternNode(keyguardRoots()) ?: return "I could not find the pattern grid."
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() < 90 || bounds.height() < 90) {
            return "The pattern grid looks too small to draw on safely."
        }
        val cellW = bounds.width() / 3f
        val cellH = bounds.height() / 3f
        val points = sequence.map { ch ->
            val index = ch - '1'
            val row = index / 3
            val col = index % 3
            PointF(bounds.left + (col + 0.5f) * cellW, bounds.top + (row + 0.5f) * cellH)
        }
        if (points.size < 2) return "The saved pattern is too short."
        val path = Path().apply {
            moveTo(points[0].x, points[0].y)
            for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
        }
        val duration = (points.size * 180L).coerceIn(700L, 3_000L)
        return if (dispatchPath(path, duration)) null else "The phone cancelled the pattern gesture."
    }

    // ------------------------------------------------------------------------------------------
    // Detection
    // ------------------------------------------------------------------------------------------

    private fun isSurfaceVisible(type: LockType, roots: List<AccessibilityNodeInfo>): Boolean = when (type) {
        LockType.PIN -> collectDigitKeys(roots).size == 10
        LockType.PASSWORD -> findPasswordField(roots) != null
        LockType.PATTERN -> findPatternNode(roots) != null
    }

    /** Returns a message when the phone clearly shows a different kind of lock than the saved one. */
    private fun detectDifferentLockType(expected: LockType, roots: List<AccessibilityNodeInfo>): String? {
        val hasPad = expected != LockType.PIN && collectDigitKeys(roots).size == 10
        val hasPattern = expected != LockType.PATTERN && findPatternNode(roots) != null
        return when {
            hasPattern -> "Your phone is showing a pattern lock, but the saved credential is a ${expected.name.lowercase(Locale.ROOT)}. Update it in Screen Unlock settings."
            hasPad -> "Your phone is showing a PIN pad, but the saved credential is a ${expected.name.lowercase(Locale.ROOT)}. Update it in Screen Unlock settings."
            else -> null
        }
    }

    private fun keyguardRoots(): List<AccessibilityNodeInfo> {
        val roots = ArrayList<AccessibilityNodeInfo>()
        try {
            for (window in service.windows) {
                window.root?.let { roots.add(it) }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Could not enumerate windows: ${e.message}")
        }
        try {
            service.rootInActiveWindow?.let { roots.add(it) }
        } catch (_: Exception) {
        }
        return roots.filter { isTrustedKeyguardPackage(it.packageName?.toString()) }
    }

    private fun isTrustedKeyguardPackage(pkg: String?): Boolean {
        if (pkg.isNullOrEmpty()) return false
        if (pkg in KNOWN_KEYGUARD_PACKAGES) return true
        if (!pkg.contains("systemui") && !pkg.contains("keyguard")) return false
        return try {
            val appInfo = service.packageManager.getApplicationInfo(pkg, 0)
            (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        } catch (_: Exception) {
            false
        }
    }

    private fun collectDigitKeys(roots: List<AccessibilityNodeInfo>): Map<Char, AccessibilityNodeInfo> {
        for (requireVisible in listOf(true, false)) {
            val best = HashMap<Char, Pair<Int, AccessibilityNodeInfo>>()
            for (root in roots) {
                walk(root, 0) { node ->
                    if (requireVisible && !node.isVisibleToUser) return@walk
                    val found = digitOf(node) ?: return@walk
                    val (digit, rank) = found
                    val current = best[digit]
                    if (current == null || rank < current.first) best[digit] = rank to node
                }
            }
            if (best.size == 10) return best.mapValues { it.value.second }
            if (!requireVisible) return best.mapValues { it.value.second }
        }
        return emptyMap()
    }

    /** Returns the digit a node represents and how trustworthy the match is (0 = best). */
    private fun digitOf(node: AccessibilityNodeInfo): Pair<Char, Int>? {
        val id = node.viewIdResourceName?.substringAfterLast('/')?.lowercase(Locale.ROOT)
        if (id != null) {
            for (pattern in DIGIT_ID_PATTERNS) {
                val match = pattern.find(id)
                if (match != null) return match.groupValues[1][0] to 0
            }
        }
        val text = node.text?.toString()?.trim()
        if (text != null && text.length == 1 && text[0] in '0'..'9') return text[0] to 1
        val desc = node.contentDescription?.toString()?.trim()
        if (desc != null && desc.length == 1 && desc[0] in '0'..'9') return desc[0] to 2
        return null
    }

    private fun findPasswordField(roots: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
        var result: AccessibilityNodeInfo? = null
        for (root in roots) {
            walk(root, 0) { node ->
                if (result == null && node.isEditable && node.isVisibleToUser) result = node
            }
            if (result != null) break
        }
        return result
    }

    private fun findPatternNode(roots: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
        var result: AccessibilityNodeInfo? = null
        for (root in roots) {
            walk(root, 0) { node ->
                if (result == null) {
                    val cls = node.className?.toString()?.lowercase(Locale.ROOT).orEmpty()
                    val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
                    if (cls.contains("lockpatternview") || id.endsWith("lockpatternview") || id.endsWith("lock_pattern_view")) {
                        result = node
                    }
                }
            }
            if (result != null) break
        }
        return result
    }

    private fun findEnterButton(roots: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
        var result: AccessibilityNodeInfo? = null
        for (root in roots) {
            walk(root, 0) { node ->
                if (result == null && node.isVisibleToUser) {
                    val id = node.viewIdResourceName?.substringAfterLast('/')?.lowercase(Locale.ROOT).orEmpty()
                    val desc = node.contentDescription?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
                    val text = node.text?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
                    if (id == "key_enter" || id == "enter" || id == "button_enter" ||
                        desc in ENTER_LABELS || text in ENTER_LABELS
                    ) {
                        result = node
                    }
                }
            }
            if (result != null) break
        }
        return result
    }

    private fun walk(node: AccessibilityNodeInfo?, depth: Int, visit: (AccessibilityNodeInfo) -> Unit) {
        if (node == null || depth > 40) return
        visit(node)
        for (i in 0 until node.childCount) {
            walk(node.getChild(i), depth + 1, visit)
        }
    }

    // ------------------------------------------------------------------------------------------
    // Low-level helpers
    // ------------------------------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun wakeScreen(pm: PowerManager): PowerManager.WakeLock? = try {
        pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            "jarvis:screen-unlock"
        ).apply { acquire(30_000L) }
    } catch (e: Exception) {
        Log.w(TAG, "Screen wake lock failed: ${e.message}")
        null
    }

    private suspend fun swipeUp(): Boolean {
        val dm = service.resources.displayMetrics
        val x = dm.widthPixels / 2f
        val path = Path().apply {
            moveTo(x, dm.heightPixels * 0.85f)
            lineTo(x, dm.heightPixels * 0.25f)
        }
        return dispatchPath(path, 350L)
    }

    private suspend fun dispatchPath(path: Path, durationMs: Long): Boolean {
        val completed = withTimeoutOrNull(durationMs + 3_000L) {
            suspendCancellableCoroutine<Boolean> { cont ->
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
                    .build()
                val accepted = service.dispatchGesture(
                    gesture,
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            if (cont.isActive) cont.resume(false)
                        }
                    },
                    null
                )
                if (!accepted && cont.isActive) cont.resume(false)
            }
        }
        return completed == true
    }

    private suspend fun waitUntil(timeoutMs: Long, stepMs: Long = 100L, condition: () -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            if (condition()) return true
            delay(stepMs)
        }
        return condition()
    }
}
