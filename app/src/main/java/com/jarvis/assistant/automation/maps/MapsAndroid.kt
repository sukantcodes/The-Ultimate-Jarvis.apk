package com.jarvis.assistant.automation.maps

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import com.jarvis.assistant.R
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.automation.agent.AgentTaskEngine
import com.jarvis.assistant.util.ActivityLauncherHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/** Hands links to Google Maps, falling back to any map app that understands them. */
object MapsLauncher {
    fun open(context: Context, uri: String): Boolean {
        val app = context.applicationContext
        fun intent(withPackage: Boolean) = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            if (withPackage) setPackage(MapsLinks.PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return ActivityLauncherHelper.startActivitySafely(app, intent(true)) || ActivityLauncherHelper.startActivitySafely(app, intent(false))
    }

    /** Brings Google Maps to the front without changing what it shows. */
    fun bringToFront(context: Context): Boolean {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(MapsLinks.PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return ActivityLauncherHelper.startActivitySafely(context.applicationContext, i)
    }
}

/** The phone's position, read only when a search or a saved place needs it. Coordinates stay in memory. */
object MapsLocation {
    sealed class Result {
        data class Fix(val lat: Double, val lon: Double, val accuracyM: Int) : Result()
        data class Unavailable(val reason: String) : Result()
    }

    private fun granted(c: Context, p: String) = ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    suspend fun read(context: Context): Result {
        val app = context.applicationContext
        val fine = granted(app, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = granted(app, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!fine && !coarse) return Result.Unavailable("Jarvis does not have location permission. Allow location for Jarvis in Android's app settings.")
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return Result.Unavailable("This phone has no location service.")
        if (!LocationManagerCompat.isLocationEnabled(lm)) return Result.Unavailable("The phone's location (GPS) is switched off. Turn it on in quick settings; Jarvis does not switch it on by itself.")
        val providers = buildList {
            if (fine && lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
        }
        var best: Location? = null
        for (p in providers) {
            val l = withTimeoutOrNull(7_000L) { fresh(lm, p) } ?: continue
            if (best == null || (l.hasAccuracy() && l.accuracy < (best.accuracy))) best = l
        }
        if (best == null) {
            best = providers.mapNotNull { lm.getLastKnownLocation(it) }
                .filter { System.currentTimeMillis() - it.time <= 30 * 60_000L }.maxByOrNull { it.time }
        }
        val fix = best ?: return Result.Unavailable("The phone has no usable location fix right now. Move to an open area and try again, or tell me the area to search near.")
        return Result.Fix(fix.latitude, fix.longitude, if (fix.hasAccuracy()) fix.accuracy.toInt() else 0)
    }

    @SuppressLint("MissingPermission")
    private suspend fun fresh(lm: LocationManager, provider: String): Location? = suspendCancellableCoroutine { cont ->
        val signal = CancellationSignal()
        cont.invokeOnCancellation { signal.cancel() }
        LocationManagerCompat.getCurrentLocation(lm, provider, signal, { r -> r.run() }) { loc -> if (cont.isActive) cont.resume(loc) }
    }
}

/** Reads and taps the Google Maps screen through the Accessibility service. Everything returns "can not" when it is off. */
class AndroidMapsDriver(private val context: Context) : MapsDriver {
    private val service: JarvisAccessibilityService? get() = JarvisAccessibilityService.instance

    fun accessibilityOn(): Boolean = service != null

    override suspend fun open(uri: String): Boolean = MapsLauncher.open(context, uri)

    override suspend fun texts(): List<String>? = withContext(Dispatchers.Default) {
        val root = service?.getActiveRootNode() ?: return@withContext null
        if (root.packageName?.toString() != MapsLinks.PACKAGE) return@withContext null
        val out = ArrayList<String>()
        var budget = 700
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || budget <= 0 || depth > 45) return
            budget--
            n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
            n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        out.distinct()
    }

    override suspend fun click(label: String): Boolean = withContext(Dispatchers.Default) { service?.clickNodeWithText(label) ?: false }

    override suspend fun scrollDown(): Boolean = withContext(Dispatchers.Default) { service?.performSmartScroll("down", "medium") ?: false }

    override suspend fun pause(ms: Long) = delay(ms)

    override fun cancelled(): Boolean = AgentTaskEngine.isCancelled()
}

/** Launcher shortcuts: long-press the Jarvis icon, or pin a place on the home screen, to start navigation in one tap. */
object MapsShortcuts {
    const val ACTION = "com.jarvis.assistant.action.MAPS_NAVIGATE"
    const val EXTRA_DEST = "dest"
    const val EXTRA_LABEL = "label"

    private fun build(ctx: Context, p: SavedPlace): ShortcutInfoCompat {
        val id = "maps_" + p.name.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }.ifEmpty { "place" }
        val intent = Intent(ctx, MapsShortcutActivity::class.java).setAction(ACTION)
            .putExtra(EXTRA_DEST, p.destination()).putExtra(EXTRA_LABEL, p.name)
        return ShortcutInfoCompat.Builder(ctx, id)
            .setShortLabel("Go: " + p.name.take(18))
            .setLongLabel("Navigate to " + p.name.take(40))
            .setIcon(IconCompat.createWithResource(ctx, R.mipmap.ic_launcher))
            .setIntent(intent)
            .build()
    }

    /** Shows the first few saved places when the Jarvis icon is long-pressed. Never throws. */
    fun publish(context: Context, places: List<SavedPlace>) {
        try {
            val app = context.applicationContext
            val max = minOf(4, ShortcutManagerCompat.getMaxShortcutCountPerActivity(app))
            ShortcutManagerCompat.setDynamicShortcuts(app, places.take(max).map { build(app, it) })
        } catch (_: Exception) { /* shortcuts are a convenience; saving a place must not fail because of them */ }
    }

    /** Asks Android to add a home-screen icon. Android shows its own confirmation. */
    fun pin(context: Context, place: SavedPlace): Boolean = try {
        val app = context.applicationContext
        ShortcutManagerCompat.isRequestPinShortcutSupported(app) && ShortcutManagerCompat.requestPinShortcut(app, build(app, place), null)
    } catch (_: Exception) { false }
}

/** Invisible screen behind the shortcuts: starts navigation and closes at once. */
class MapsShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dest = intent?.getStringExtra(MapsShortcuts.EXTRA_DEST)
        val uri = dest?.takeIf { it.isNotBlank() }?.let { MapsLinks.navigationUri(it, MapsLinks.Mode.DRIVING, emptySet()) }
        if (uri != null) MapsLauncher.open(this, uri)
        finish()
    }
}
