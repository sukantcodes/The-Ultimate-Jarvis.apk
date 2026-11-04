package com.jarvis.assistant.automation.rapido

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.automation.agent.AgentTaskEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/** Turns the live accessibility tree into plain [RapidoNode] values. */
object RapidoNodeReader {
    private const val MAX_NODES = 1_500
    private const val MAX_DEPTH = 45

    fun read(root: AccessibilityNodeInfo?): List<RapidoNode> {
        if (root == null) return emptyList()
        val out = ArrayList<RapidoNode>()
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
                out += RapidoNode(
                    text = text, description = description, viewId = node.viewIdResourceName.orEmpty(), className = className,
                    clickable = clickable, editable = editable, selected = node.isSelected, checked = node.isChecked,
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
 * Reads what location access the phone offers. Coordinates are used only to judge accuracy and to look up a coarse
 * area name on the device itself; they are never returned, logged or sent anywhere.
 */
object RapidoLocationReader {
    private const val TAG = "RapidoLocation"

    @SuppressLint("MissingPermission")
    suspend fun read(context: Context): LocationState {
        val app = context.applicationContext
        val fine = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val manager = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val enabled = manager != null && LocationManagerCompat.isLocationEnabled(manager)
        if ((!fine && !coarse) || !enabled || manager == null) return LocationClassifier.classify(fine, coarse, enabled, null)

        val fix = freshOrRecent(manager, fine)
        val area = fix?.let { areaName(app, it) }
        val age = fix?.let { System.currentTimeMillis() - it.time }
        return LocationClassifier.classify(fine, coarse, true, fix?.takeIf { it.hasAccuracy() }?.accuracy, age, area)
    }

    @SuppressLint("MissingPermission")
    private suspend fun freshOrRecent(manager: LocationManager, fine: Boolean): Location? {
        val providers = buildList {
            if (fine && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
            if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
        }
        for (provider in providers) {
            val fresh = withTimeoutOrNull(4_000L) {
                suspendCancellableCoroutine<Location?> { cont ->
                    val signal = CancellationSignal()
                    cont.invokeOnCancellation { signal.cancel() }
                    try {
                        LocationManagerCompat.getCurrentLocation(manager, provider, signal, { r -> r.run() }) { loc -> if (cont.isActive) cont.resume(loc) }
                    } catch (e: Exception) {
                        Log.w(TAG, "current location failed: ${e.javaClass.simpleName}")
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
            if (fresh != null) return fresh
        }
        // No new fix in time: fall back to the newest cached one; the classifier discards it when it is too old.
        return try {
            providers.mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.time }
        } catch (e: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun areaName(context: Context, loc: Location): String? = try {
        val a = Geocoder(context, Locale.getDefault()).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()
        a?.subLocality ?: a?.locality ?: a?.subAdminArea
    } catch (e: Exception) {
        null
    }
}

/**
 * The real phone behind [RapidoAgent]. Every tap, typed character, scroll and launch goes through
 * AgentTaskEngine.executeUiMutation, so it is serialized with all other automation and stops at once when the user
 * cancels. Nothing here decides what to do; it only does one thing and reports honestly.
 */
class AndroidRapidoDriver(
    private val context: Context,
    private val onProgress: (String) -> Unit = {}
) : RapidoDriver {

    private companion object {
        const val TAG = "RapidoDriver"
    }

    private val service: JarvisAccessibilityService? get() = JarvisAccessibilityService.instance

    private fun installedPackage(): String? = RapidoApp.packages.firstOrNull { pkg ->
        try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun isRapidoInstalled(): Boolean = installedPackage() != null

    override fun foregroundPackage(): String? = service?.getActiveRootNode()?.packageName?.toString()

    override suspend fun snapshot(): List<RapidoNode>? {
        val svc = service ?: return null
        // The tree can be briefly empty while a page swaps; one short retry avoids a false "unreadable".
        for (attempt in 0..1) {
            val root = svc.getActiveRootNode()
            if (root != null) return RapidoNodeReader.read(root)
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

    override suspend fun launchApp(): Boolean {
        val pkg = installedPackage() ?: return false
        val started = AgentTaskEngine.executeUiMutation("rapido-open") {
            val home = context.packageManager.getLaunchIntentForPackage(pkg)
            home != null && start(home)
        }
        if (!started) return false
        // Success means Rapido really came to the front, not that startActivity returned.
        for (i in 0 until 16) {
            if (AgentTaskEngine.isCancelled()) return false
            if (foregroundPackage() == pkg) return true
            delay(500)
        }
        return false
    }

    override suspend fun click(node: RapidoNode): Boolean {
        val svc = service ?: return false
        if (node.right <= node.left || node.bottom <= node.top) return false
        val rect = Rect(node.left, node.top, node.right, node.bottom)
        return AgentTaskEngine.executeUiMutation("rapido-click") { svc.performClickAtRect(rect) }
    }

    override suspend fun typeInto(node: RapidoNode, text: String): Boolean {
        val svc = service ?: return false
        // Never type on sign-in, OTP, CAPTCHA, PIN or card-security screens, whatever the caller asked.
        val screen = RapidoScreenReader.classify(snapshot().orEmpty())
        if (screen == RapidoScreen.CHALLENGE || screen == RapidoScreen.SIGN_IN) return false
        if (node.right <= node.left || node.bottom <= node.top) return false
        val rect = Rect(node.left, node.top, node.right, node.bottom)
        // smartTypeText answers true only when the text was read back from the field.
        return AgentTaskEngine.executeUiMutation("rapido-type") { svc.smartTypeText(text, rect, false) }
    }

    override suspend fun scroll(down: Boolean): Boolean {
        val svc = service ?: return false
        return AgentTaskEngine.executeUiMutation("rapido-scroll") {
            svc.performSmartScroll(if (down) "down" else "up", "medium")
        }
    }

    override suspend fun back(): Boolean {
        val svc = service ?: return false
        return AgentTaskEngine.executeUiMutation("rapido-back") { svc.performSystemGesture("back") }
    }

    override suspend fun pause(ms: Long) = delay(ms)

    override fun now(): Long = System.currentTimeMillis()

    override fun cancelled(): Boolean = AgentTaskEngine.isCancelled()

    override fun progress(message: String) {
        AgentTaskEngine.updateActivity(message, AgentTaskEngine.State.OBSERVING)
        onProgress(message)
    }

    override suspend fun locationState(): LocationState = RapidoLocationReader.read(context)
}
