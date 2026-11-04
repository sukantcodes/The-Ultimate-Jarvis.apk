package com.jarvis.assistant.automation.blinkit

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.automation.agent.AgentTaskEngine
import com.jarvis.assistant.automation.amazon.AccessibilityNodeReader
import com.jarvis.assistant.automation.amazon.AmazonNode
import com.jarvis.assistant.automation.rapido.LocationState
import com.jarvis.assistant.automation.rapido.RapidoLocationReader
import kotlinx.coroutines.delay

/**
 * The real phone behind [BlinkitAgent]. Every tap, typed character, scroll and launch goes through
 * AgentTaskEngine.executeUiMutation, so it is serialized with all other automation and stops at once when the user
 * cancels. Nothing here decides what to do; it only does one thing and reports honestly. The accessibility tree is read
 * with the same reader the Amazon module uses.
 */
class AndroidBlinkitDriver(
    private val context: Context,
    private val onProgress: (String) -> Unit = {}
) : BlinkitDriver {

    private companion object {
        const val TAG = "BlinkitDriver"
    }

    private val service: JarvisAccessibilityService? get() = JarvisAccessibilityService.instance

    private fun installedPackage(): String? = BlinkitLinks.packages.firstOrNull { pkg ->
        try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun isInstalled(): Boolean = installedPackage() != null

    override fun foregroundPackage(): String? = service?.getActiveRootNode()?.packageName?.toString()

    override suspend fun snapshot(): List<AmazonNode>? {
        val svc = service ?: return null
        // The tree can be briefly empty while a page swaps; one short retry avoids a false "unreadable".
        for (attempt in 0..1) {
            val root = svc.getActiveRootNode()
            if (root != null) return AccessibilityNodeReader.read(root)
            delay(250)
        }
        return null
    }

    private fun start(intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (first: Exception) {
            Log.w(TAG, "startActivity from the app failed: ${first.message}")
            try {
                val svc = service ?: return false
                svc.startActivity(intent)
                true
            } catch (second: Exception) {
                Log.w(TAG, "startActivity from the accessibility service failed: ${second.message}")
                false
            }
        }
    }

    override suspend fun launch(url: String?): Boolean {
        val pkg = installedPackage()
        // Without the app there is nothing to open for "home"; a link may still open in the browser.
        if (pkg == null && url == null) return false
        val started = AgentTaskEngine.executeUiMutation("blinkit-open") {
            if (url != null) {
                val view = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                if (pkg != null) {
                    view.setPackage(pkg)
                    if (start(view)) true
                    else {
                        // The app did not take the link: open the app itself; the agent then types the search.
                        val home = context.packageManager.getLaunchIntentForPackage(pkg)
                        home != null && start(home)
                    }
                } else start(view)
            } else {
                val home = context.packageManager.getLaunchIntentForPackage(pkg!!)
                home != null && start(home)
            }
        }
        if (!started) return false
        // Success means the target app really came to the front, not that startActivity returned.
        for (i in 0 until 16) {
            if (AgentTaskEngine.isCancelled()) return false
            val front = foregroundPackage()
            if (pkg != null && front == pkg) return true
            if (pkg == null && front != null && front != context.packageName) return true
            delay(500)
        }
        return false
    }

    override suspend fun click(node: AmazonNode): Boolean {
        val svc = service ?: return false
        if (node.right <= node.left || node.bottom <= node.top) return false
        val rect = Rect(node.left, node.top, node.right, node.bottom)
        return AgentTaskEngine.executeUiMutation("blinkit-click") { svc.performClickAtRect(rect) }
    }

    override suspend fun typeInto(node: AmazonNode, text: String): Boolean {
        val svc = service ?: return false
        // Never type on login, OTP, CAPTCHA, PIN or card-security screens, whatever the caller asked.
        val screen = BlinkitScreenReader.classify(snapshot().orEmpty())
        if (screen == BlinkitScreen.CHALLENGE || screen == BlinkitScreen.SIGN_IN) return false
        if (node.right <= node.left || node.bottom <= node.top) return false
        val rect = Rect(node.left, node.top, node.right, node.bottom)
        // smartTypeText answers true only when the text was read back from the field.
        return AgentTaskEngine.executeUiMutation("blinkit-type") { svc.smartTypeText(text, rect, false) }
    }

    override suspend fun submitSearch(): Boolean {
        val svc = service ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return AgentTaskEngine.executeUiMutation("blinkit-submit") {
            val focused = svc.getActiveRootNode()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            focused?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) == true
        }
    }

    override suspend fun scroll(down: Boolean): Boolean {
        val svc = service ?: return false
        return AgentTaskEngine.executeUiMutation("blinkit-scroll") {
            svc.performSmartScroll(if (down) "down" else "up", "medium")
        }
    }

    override suspend fun back(): Boolean {
        val svc = service ?: return false
        return AgentTaskEngine.executeUiMutation("blinkit-back") { svc.performSystemGesture("back") }
    }

    override suspend fun pause(ms: Long) = delay(ms)

    override fun now(): Long = System.currentTimeMillis()

    override fun cancelled(): Boolean = AgentTaskEngine.isCancelled()

    override fun progress(message: String) {
        AgentTaskEngine.updateActivity(message, AgentTaskEngine.State.OBSERVING)
        onProgress(message)
    }

    /** Coordinates are only used on the device to judge accuracy; they are never returned, logged or sent. */
    override suspend fun locationState(): LocationState = RapidoLocationReader.read(context)
}
