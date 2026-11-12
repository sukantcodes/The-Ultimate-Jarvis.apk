package com.jarvis.assistant.ui.diagnostics

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.jarvis.assistant.R
import com.jarvis.assistant.util.ThemeManager
import java.util.Locale

/**
 * Phone Diagnostics home: live health of storage, memory and battery, one-tap Full Phone Boost,
 * and the individual cleaning tools. All numbers are read on the device and never uploaded.
 */
class DeviceDiagnosticsActivity : AppCompatActivity() {

    private lateinit var ui: Ui
    private lateinit var ring: HealthRingView
    private lateinit var legendStorage: TextView
    private lateinit var legendMemory: TextView
    private lateinit var legendBattery: TextView
    private lateinit var lastRunText: TextView
    private lateinit var updated: TextView
    private lateinit var statusHost: LinearLayout
    private val tileSubtitles = HashMap<Int, TextView>()
    private var entered = false

    private class Tile(val feature: Int, val title: String, val icon: Int, val color: Int)

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        val p = ui.p

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(40))
        }
        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(scroll)
        ThemeManager.applyDynamicBackground(scroll)

        // ---- Header ------------------------------------------------------------------------------
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = ui.lp().apply { bottomMargin = ui.dp(16) }
        }
        header.addView(roundButton(R.drawable.ic_diag_back, "Back") { finish() })
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = ui.dp(14)
            }
        }
        titles.addView(ui.label("Phone Diagnostics", 24f, bold = true))
        updated = ui.label("", 12f, p.muted)
        titles.addView(updated)
        header.addView(titles)
        header.addView(roundButton(R.drawable.ic_settings_chip, "Live Telemetry HUD") {
            com.jarvis.assistant.ui.telemetry.HardwareTelemetryBottomSheet.show(supportFragmentManager)
        }.apply { layoutParams = LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)).apply { marginEnd = ui.dp(8) } })
        header.addView(roundButton(R.drawable.ic_diag_refresh, "Refresh") { refresh(true) })
        root.addView(header)

        // ---- Hero: health ring + Full Boost ----------------------------------------------------
        val hero = ui.card(20).apply { gravity = Gravity.CENTER_HORIZONTAL }
        ring = HealthRingView(this).apply {
            colorStorage = p.accent
            colorMemory = p.accent2
            colorBattery = p.success
            textPrimary = p.text
            textMuted = p.muted
            layoutParams = LinearLayout.LayoutParams(ui.dp(236), ui.dp(236)).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }
        hero.addView(ring)

        val legend = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ui.lp().apply { topMargin = ui.dp(6); bottomMargin = ui.dp(12) }
        }
        legendStorage = TextView(this)
        legendMemory = TextView(this)
        legendBattery = TextView(this)
        legend.addView(legendItem(p.accent, "Storage", legendStorage))
        legend.addView(legendItem(p.accent2, "Memory", legendMemory))
        legend.addView(legendItem(p.success, "Battery", legendBattery))
        hero.addView(legend)

        lastRunText = ui.label("", 12f, p.muted).apply { gravity = Gravity.CENTER }
        hero.addView(lastRunText, ui.lp().apply { bottomMargin = ui.dp(14) })

        val fullBoost = ui.primaryButton("Full Phone Boost").apply {
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_diag_boost, 0, 0, 0)
            compoundDrawablePadding = ui.dp(10)
            compoundDrawableTintList = ColorStateList.valueOf(Color.WHITE)
            contentDescription = "Start Full Phone Boost"
            setOnClickListener { open(CleanerActions.F_FULL) }
        }
        hero.addView(fullBoost, ui.lp())
        hero.addView(
            ui.label("Cleans junk, stops background apps and frees memory. You approve before anything is deleted.", 11f, p.muted).apply {
                gravity = Gravity.CENTER
                setPadding(ui.dp(8), ui.dp(10), ui.dp(8), 0)
            }
        )
        root.addView(hero)

        // ---- Tools grid -------------------------------------------------------------------------
        root.addView(sectionTitle("TOOLS"))
        val tiles = listOf(
            Tile(CleanerActions.F_JUNK, "Junk Clean", R.drawable.ic_diag_junk, p.accent),
            Tile(CleanerActions.F_BOOST, "RAM Boost", R.drawable.ic_diag_memory, p.accent2),
            Tile(CleanerActions.F_BATTERY, "Battery", R.drawable.ic_diag_battery, p.success),
            Tile(CleanerActions.F_LARGE, "Large Files", R.drawable.ic_diag_folder, p.warning),
            Tile(CleanerActions.F_WHATSAPP, "WhatsApp", R.drawable.ic_diag_chat, Color.rgb(37, 211, 102)),
            Tile(CleanerActions.F_PHOTOS, "Photos", R.drawable.ic_diag_photo, Color.rgb(244, 114, 182)),
            Tile(CleanerActions.F_APPS, "Apps", R.drawable.ic_diag_apps, Color.rgb(251, 146, 60)),
            Tile(CleanerActions.F_PROTECTED, "Protected", R.drawable.ic_diag_shield, Color.rgb(96, 165, 250))
        )
        for (rowStart in tiles.indices step 2) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = ui.lp().apply { bottomMargin = ui.dp(12) }
            }
            for (i in rowStart until minOf(rowStart + 2, tiles.size)) {
                row.addView(tileView(tiles[i], first = i == rowStart))
            }
            root.addView(row)
        }

        // ---- Live status -------------------------------------------------------------------------
        root.addView(sectionTitle("LIVE STATUS"))
        statusHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(statusHost, ui.lp())

        if (savedInstanceState == null) playEntrance(root)
    }

    override fun onResume() {
        super.onResume()
        refresh(animate = true)
    }

    // ---- Building blocks --------------------------------------------------------------------------

    private fun open(feature: Int) {
        if (feature == CleanerActions.F_PROTECTED) {
            ui.showProtectedApps(this) { refresh(false) }
            return
        }
        startActivity(Intent(this, CleanupFeatureActivity::class.java).putExtra(CleanupFeatureActivity.EXTRA_FEATURE, feature))
    }

    private fun roundButton(icon: Int, description: String, onClick: () -> Unit): ImageView = ImageView(this).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(ui.p.text)
        contentDescription = description
        isClickable = true
        isFocusable = true
        val pad = ui.dp(11)
        setPadding(pad, pad, pad, pad)
        val shape = GradientDrawable().apply {
            this.shape = GradientDrawable.OVAL
            setColor(ui.p.card)
            setStroke(ui.dp(1), ui.p.border)
        }
        background = ui.ripple(shape, 24)
        layoutParams = LinearLayout.LayoutParams(ui.dp(46), ui.dp(46))
        setOnClickListener { onClick() }
    }

    private fun sectionTitle(value: String): TextView = ui.label(value, 11f, ui.p.muted, bold = true).apply {
        letterSpacing = 0.16f
        setPadding(ui.dp(4), ui.dp(10), 0, ui.dp(10))
    }

    private fun legendItem(color: Int, name: String, valueView: TextView): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
            layoutParams = LinearLayout.LayoutParams(ui.dp(8), ui.dp(8)).apply { marginEnd = ui.dp(6) }
        })
        top.addView(ui.label(name, 11f, ui.p.muted))
        box.addView(top)
        valueView.apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(ui.p.text)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            text = "--"
        }
        box.addView(valueView)
        return box
    }

    private fun tileView(tile: Tile, first: Boolean): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(16), ui.dp(16), ui.dp(14), ui.dp(16))
            background = ui.ripple(ui.glass(22), 22, tile.color.withAlphaF(0.22f))
            isClickable = true
            isFocusable = true
            contentDescription = tile.title
            minimumHeight = ui.dp(124)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (first) marginEnd = ui.dp(6) else marginStart = ui.dp(6)
            }
            setOnClickListener { open(tile.feature) }
        }
        box.addView(ui.badge(tile.icon, tile.color, 44))
        box.addView(ui.label(tile.title, 15f, bold = true).apply { setPadding(0, ui.dp(12), 0, 0) })
        val sub = ui.label("", 11f, ui.p.muted).apply { setPadding(0, ui.dp(2), 0, 0); maxLines = 2 }
        tileSubtitles[tile.feature] = sub
        box.addView(sub)
        return box
    }

    private fun playEntrance(root: LinearLayout) {
        if (entered) return
        entered = true
        val rise = ui.dp(28).toFloat()
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            child.alpha = 0f
            child.translationY = rise
            child.animate().alpha(1f).translationY(0f)
                .setStartDelay(60L + i * 55L).setDuration(480L)
                .setInterpolator(DecelerateInterpolator(1.8f)).start()
        }
    }

    // ---- Live data --------------------------------------------------------------------------------

    private fun refresh(animate: Boolean) {
        val disk = CleanerEngine.disk()
        val mem = CleanerEngine.memory(this)
        val battery = CleanerEngine.battery(this)
        val batteryFraction = if (battery != null && battery.percent in 0..100) battery.percent / 100f else -1f

        ring.setValues(disk.usedFraction, mem.usedFraction, batteryFraction, animate)
        ring.centerValue = ui.formatBytes(disk.free)
        ring.centerLabel = "FREE STORAGE"
        ring.contentDescription = "Storage ${(disk.usedFraction * 100).toInt()} percent used, memory ${(mem.usedFraction * 100).toInt()} percent used"
        legendStorage.text = "${(disk.usedFraction * 100).toInt()}%"
        legendMemory.text = "${(mem.usedFraction * 100).toInt()}%"
        legendBattery.text = if (batteryFraction >= 0f) "${battery!!.percent}%" else "--"

        val last = CleanerEngine.lastRun(this)
        lastRunText.text = if (last == null) {
            "No boost yet. Run Full Phone Boost to get started."
        } else {
            val ago = DateUtils.getRelativeTimeSpanString(last.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            "Last boost $ago  •  ${ui.formatBytes(last.storageFreed)} storage  •  ${ui.formatBytes(last.ramFreed)} memory"
        }

        tileSubtitles[CleanerActions.F_JUNK]?.text = "Cache, temp and empty folders"
        tileSubtitles[CleanerActions.F_BOOST]?.text = "${ui.formatBytes(mem.available)} free of ${ui.formatBytes(mem.total)}"
        tileSubtitles[CleanerActions.F_BATTERY]?.text = if (battery != null && battery.percent >= 0) {
            "${battery.percent}%  •  ${if (battery.charging) "charging" else "on battery"}"
        } else {
            "Saver and usage"
        }
        tileSubtitles[CleanerActions.F_LARGE]?.text = "Find the biggest files"
        tileSubtitles[CleanerActions.F_WHATSAPP]?.text = "Media only. Chats stay safe"
        tileSubtitles[CleanerActions.F_PHOTOS]?.text = "Duplicates and screenshots"
        tileSubtitles[CleanerActions.F_APPS]?.text = "Uninstall what you don't use"
        val protectedCount = CleanerEngine.protectedApps(this).size
        tileSubtitles[CleanerActions.F_PROTECTED]?.text =
            if (protectedCount == 0) "Apps Boost never stops" else "$protectedCount app${if (protectedCount == 1) "" else "s"} protected"

        statusHost.removeAllViews()
        statusHost.addView(statusCard("BATTERY & CHARGING", batteryLines(), if (batteryFraction >= 0f) batteryFraction else null, ui.p.success))
        statusHost.addView(statusCard("INTERNAL STORAGE", storageLines(disk), disk.usedFraction, ui.p.accent))
        statusHost.addView(statusCard("MEMORY (RAM)", memoryLines(mem), mem.usedFraction, ui.p.accent2))
        statusHost.addView(statusCard("NETWORK", networkLines(), null, ui.p.warning))
        updated.text = "Updated " + java.text.SimpleDateFormat("h:mm:ss a", Locale.getDefault()).format(java.util.Date())
    }

    private fun statusCard(title: String, rows: List<Pair<String, String>>, meter: Float?, color: Int): View {
        val card = ui.card(18)
        card.addView(ui.label(title, 11f, color, bold = true).apply {
            letterSpacing = 0.12f
            setPadding(0, 0, 0, ui.dp(10))
        })
        if (meter != null) {
            val fill = meter.coerceIn(0f, 1f)
            val track = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = GradientDrawable().apply { cornerRadius = ui.dp(4).toFloat(); setColor(color.withAlphaF(0.16f)) }
                layoutParams = ui.lp(h = ui.dp(8)).apply { bottomMargin = ui.dp(8) }
            }
            track.addView(View(this).apply {
                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(color, color.withAlphaF(0.7f))).apply {
                    cornerRadius = ui.dp(4).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, fill)
            })
            track.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f - fill)
            })
            card.addView(track)
        }
        rows.forEachIndexed { index, (label, value) ->
            if (index > 0) card.addView(ui.divider())
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, ui.dp(9), 0, ui.dp(9))
            }
            row.addView(ui.label(label, 13f, ui.p.muted), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(ui.label(value, 13f, bold = true).apply {
                gravity = Gravity.END
                maxWidth = ui.dp(200)
            })
            card.addView(row)
        }
        return card
    }

    private fun batteryLines(): List<Pair<String, String>> {
        val intent = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return listOf("Status" to "Battery information is unavailable")
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
        val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
        val technology = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY).orEmpty()
        val level = CleanerEngine.battery(this)?.percent ?: -1
        return buildList {
            add("Charge" to if (level in 0..100) "$level%" else "Unavailable")
            add("State" to when (status) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
                BatteryManager.BATTERY_STATUS_FULL -> "Full"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Connected, not charging"
                else -> "Unknown"
            })
            add("Health" to when (health) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheating"
                BatteryManager.BATTERY_HEALTH_DEAD -> "Fault reported"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over-voltage"
                BatteryManager.BATTERY_HEALTH_COLD -> "Too cold"
                BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Issue reported"
                else -> "Not reported"
            })
            add("Power source" to when (plugged) {
                BatteryManager.BATTERY_PLUGGED_AC -> "AC charger"
                BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
                0 -> "Not plugged in"
                else -> "Connected"
            })
            add("Temperature" to if (temperature >= 0) String.format(Locale.getDefault(), "%.1f °C", temperature / 10f) else "Unavailable")
            add("Voltage" to if (voltage > 0) "$voltage mV" else "Unavailable")
            if (technology.isNotBlank()) add("Battery type" to technology)
        }
    }

    private fun storageLines(disk: DiskStats): List<Pair<String, String>> =
        if (disk.total <= 0L) {
            listOf("Status" to "Storage information is unavailable")
        } else {
            listOf(
                "Used" to ui.formatBytes(disk.used),
                "Available" to ui.formatBytes(disk.free),
                "Total" to ui.formatBytes(disk.total),
                "Usage" to "${(disk.usedFraction * 100).toInt()}%"
            )
        }

    private fun memoryLines(mem: MemStats): List<Pair<String, String>> = listOf(
        "In use" to ui.formatBytes(mem.used),
        "Available" to ui.formatBytes(mem.available),
        "Total" to ui.formatBytes(mem.total),
        "Memory pressure" to if (mem.low) "Low. Try RAM Boost" else "Normal"
    )

    private fun networkLines(): List<Pair<String, String>> {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = manager.activeNetwork
            ?: return listOf("Connection" to "No active network", "Internet" to "Not connected")
        val capabilities = manager.getNetworkCapabilities(active)
            ?: return listOf("Connection" to "Network details unavailable")
        val transports = buildList {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("Wi-Fi")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("Mobile data")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("Ethernet")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("Bluetooth")
        }
        return listOf(
            "Connection" to (transports.takeIf { it.isNotEmpty() }?.joinToString(" + ") ?: "Other network"),
            "Internet" to if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) "Working" else "Not validated, sign-in may be needed",
            "Metered" to if (manager.isActiveNetworkMetered) "Yes" else "No"
        )
    }
}

/** Feature ids shared by the dashboard and the cleaning flow. */
object CleanerActions {
    const val F_JUNK = 0
    const val F_BOOST = 1
    const val F_BATTERY = 2
    const val F_LARGE = 3
    const val F_WHATSAPP = 4
    const val F_PHOTOS = 5
    const val F_APPS = 6
    const val F_FULL = 7
    const val F_PROTECTED = 100
}
