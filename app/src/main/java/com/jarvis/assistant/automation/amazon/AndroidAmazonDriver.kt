package com.jarvis.assistant.automation.amazon

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.automation.agent.AgentTaskEngine
import kotlinx.coroutines.delay

/** Turns the live accessibility tree into plain [AmazonNode] values. */
object AccessibilityNodeReader {
    private const val MAX_NODES = 1_500
    private const val MAX_DEPTH = 45

    fun read(root: AccessibilityNodeInfo?): List<AmazonNode> {
        if (root == null) return emptyList()
        val out = ArrayList<AmazonNode>()
        val bounds = Rect()
        fun walk(node: AccessibilityNodeInfo, depth: Int, parentClickable: Boolean) {
            if (out.size >= MAX_NODES || depth > MAX_DEPTH) return
            if (!node.isVisibleToUser) return
            node.getBoundsInScreen(bounds)
            val text = node.text?.toString().orEmpty()
            val description = node.contentDescription?.toString().orEmpty()
            val className = node.className?.toString().orEmpty()
            val editable = node.isEditable || className.contains("EditText", ignoreCase = true)
            val clickable = node.isClickable || parentClickable
            if (text.isNotBlank() || description.isNotBlank() || editable) {
                out += AmazonNode(
                    text = text,
                    description = description,
                    viewId = node.viewIdResourceName.orEmpty(),
                    className = className,
                    clickable = clickable,
                    editable = editable,
                    selected = node.isSelected,
                    checked = node.isChecked,
                    left = bounds.left, top = bounds.top, right = bounds.right, bottom = bounds.bottom
                )
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child, depth + 1, clickable)
            }
        }
        walk(root, 0, false)
        return out
    }
}

/**
 * The real phone behind [AmazonAgent]. Every tap, typed character, scroll and launch goes through
 * AgentTaskEngine.executeUiMutation, so it is serialized with all other automation and stops at once
 * when the user cancels. Nothing here decides what to do; it only does one thing and reports honestly.
 */
class AndroidAmazonDriver(
    private val context: Context,
    private val onProgress: (String) -> Unit = {}
) : AmazonDriver {

    private companion object {
        const val TAG = "AmazonDriver"
    }

    private val service: JarvisAccessibilityService? get() = JarvisAccessibilityService.instance

    private fun installedAmazonPackage(): String? = AmazonLinks.allPackages.firstOrNull { pkg ->
        try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun isAmazonInstalled(): Boolean = installedAmazonPackage() != null

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

    override suspend fun launch(url: String): Boolean {
        val pkg = installedAmazonPackage()
        val started = AgentTaskEngine.executeUiMutation("amazon-open") {
            val view = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            if (pkg != null) {
                view.setPackage(pkg)
                if (start(view)) true
                else {
                    // The app did not take the link: open the app itself; the agent then types the search.
                    val home = context.packageManager.getLaunchIntentForPackage(pkg)
                    home != null && start(home)
                }
            } else {
                start(view)
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
        return AgentTaskEngine.executeUiMutation("amazon-click") { svc.performClickAtRect(rect) }
    }

    override suspend fun typeInto(node: AmazonNode, text: String): Boolean {
        val svc = service ?: return false
        // Never type on sign-in, OTP, CAPTCHA, PIN or card-security screens, whatever the caller asked.
        val screen = AmazonScreenReader.classify(snapshot().orEmpty())
        if (screen == AmazonScreen.CHALLENGE || screen == AmazonScreen.SIGN_IN) return false
        if (node.right <= node.left || node.bottom <= node.top) return false
        val rect = Rect(node.left, node.top, node.right, node.bottom)
        // smartTypeText answers true only when the text was read back from the field.
        return AgentTaskEngine.executeUiMutation("amazon-type") { svc.smartTypeText(text, rect, false) }
    }

    override suspend fun submitSearch(): Boolean {
        val svc = service ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return AgentTaskEngine.executeUiMutation("amazon-submit") {
            val focused = svc.getActiveRootNode()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            focused?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) == true
        }
    }

    override suspend fun scroll(down: Boolean): Boolean {
        val svc = service ?: return false
        return AgentTaskEngine.executeUiMutation("amazon-scroll") {
            svc.performSmartScroll(if (down) "down" else "up", "medium")
        }
    }

    override suspend fun back(): Boolean {
        val svc = service ?: return false
        return AgentTaskEngine.executeUiMutation("amazon-back") { svc.performSystemGesture("back") }
    }

    override suspend fun pause(ms: Long) = delay(ms)

    override fun now(): Long = System.currentTimeMillis()

    override fun cancelled(): Boolean = AgentTaskEngine.isCancelled()

    override fun progress(message: String) {
        AgentTaskEngine.updateActivity(message, AgentTaskEngine.State.OBSERVING)
        onProgress(message)
    }
}
