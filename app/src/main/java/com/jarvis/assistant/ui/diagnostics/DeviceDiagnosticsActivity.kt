package com.jarvis.assistant.ui.diagnostics

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * User-triggered, read-only diagnostics. All values are queried locally and are not uploaded.
 * No runtime permissions are required for these summaries.
 */
class DeviceDiagnosticsActivity : AppCompatActivity() {

    private lateinit var cards: LinearLayout
    private lateinit var lastUpdated: TextView
    private var surfaceColor: Int = Color.WHITE
    private var primaryTextColor: Int = Color.BLACK
    private var secondaryTextColor: Int = Color.DKGRAY
    private var accentColor: Int = Color.BLUE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        surfaceColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, Color.WHITE)
        primaryTextColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
        secondaryTextColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.DKGRAY)
        accentColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, Color.BLUE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(MaterialColors.getColor(this@DeviceDiagnosticsActivity, android.R.attr.colorBackground, Color.WHITE))
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
        val back = TextView(this).apply {
            text = "‹"
            textSize = 34f
            setTextColor(primaryTextColor)
            gravity = Gravity.CENTER
            contentDescription = "Back"
            setOnClickListener { finish() }
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(48))
        }
        val heading = TextView(this).apply {
            text = "Device Diagnostics"
            textSize = 21f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(primaryTextColor)
            setPadding(dp(8), 0, 0, 0)
        }
        toolbar.addView(back)
        toolbar.addView(heading, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(toolbar)

        lastUpdated = TextView(this).apply {
            textSize = 12f
            setTextColor(secondaryTextColor)
            setPadding(dp(20), 0, dp(20), dp(10))
        }
        root.addView(lastUpdated)

        val refresh = MaterialButton(this).apply {
            text = "Refresh diagnostics"
            isAllCaps = false
            setOnClickListener { renderDiagnostics() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(16), 0, dp(16), dp(12))
            }
        }
        root.addView(refresh)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(dp(16), dp(4), dp(16), dp(24))
        }
        cards = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(cards)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)
        renderDiagnostics()
    }

    private fun renderDiagnostics() {
        cards.removeAllViews()
        addCleanupTools()
        addCard("BATTERY & CHARGING", batteryLines())
        addCard("INTERNAL STORAGE", storageLines())
        addCard("NETWORK CONNECTIVITY", networkLines())
        lastUpdated.text = "Updated " + SimpleDateFormat("h:mm:ss a", Locale.getDefault()).format(Date())
    }

    private fun addCleanupTools() {
        val entries = listOf(
            "Junk Clean" to "Scan phone-wide shared storage for junk",
            "Boost" to "Check phone memory and open app controls",
            "Battery Saver" to "Open Android battery-saver controls",
            "Large Files Cleanup" to "Find large files across shared phone storage",
            "WhatsApp Clean" to "Find WhatsApp media across shared storage",
            "Photos Clean" to "Find photos across shared phone storage",
            "App Uninstaller" to "Select an app and confirm uninstall"
        )
        val card = MaterialCardView(this).apply {
            radius = dp(20).toFloat()
            cardElevation = dp(3).toFloat()
            setCardBackgroundColor(surfaceColor)
            useCompatPadding = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(12))
        }
        column.addView(TextView(this).apply {
            text = "DEVICE CLEANUP"
            textSize = 12f
            letterSpacing = 0.09f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(accentColor)
            setPadding(0, 0, 0, dp(10))
        })
        entries.forEachIndexed { index, entry ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(9), 0, dp(9))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    startActivity(Intent(this@DeviceDiagnosticsActivity, CleanupFeatureActivity::class.java)
                        .putExtra(CleanupFeatureActivity.EXTRA_FEATURE, index))
                }
            }
            val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            labels.addView(TextView(this).apply {
                text = entry.first
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(primaryTextColor)
            })
            labels.addView(TextView(this).apply {
                text = entry.second
                textSize = 12f
                setTextColor(secondaryTextColor)
            })
            row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = "›"
                textSize = 28f
                setTextColor(accentColor)
            })
            column.addView(row)
            if (index != entries.lastIndex) column.addView(View(this).apply {
                setBackgroundColor(secondaryTextColor.withAlpha(35))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            })
        }
        card.addView(column)
        cards.addView(card)
    }

    private fun batteryLines(): List<Pair<String, String>> {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return listOf("Status" to "Battery information is unavailable")
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val percent = if (level >= 0 && scale > 0) (level * 100f / scale).toInt() else -1
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
        val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
        val technology = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY).orEmpty()

        val statusText = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
            BatteryManager.BATTERY_STATUS_FULL -> "Full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Connected, not charging"
            else -> "Unknown"
        }
        val healthText = when (health) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Android reports good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Android reports overheating"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Android reports a fault"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Android reports over-voltage"
            BatteryManager.BATTERY_HEALTH_COLD -> "Android reports too cold"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Android reports an unspecified issue"
            else -> "Not reported by device"
        }
        val powerSource = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC charger"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            0 -> "Not plugged in"
            else -> "Connected"
        }
        return buildList {
            add("Charge" to if (percent in 0..100) "$percent%" else "Unavailable")
            add("State" to statusText)
            add("Health" to healthText)
            add("Power source" to powerSource)
            add("Temperature" to if (temperature >= 0) String.format(Locale.getDefault(), "%.1f °C", temperature / 10f) else "Unavailable")
            add("Voltage" to if (voltage > 0) "$voltage mV" else "Unavailable")
            if (technology.isNotBlank()) add("Battery type" to technology)
        }
    }

    private fun storageLines(): List<Pair<String, String>> = try {
        val stats = StatFs(Environment.getDataDirectory().absolutePath)
        val total = stats.totalBytes.coerceAtLeast(0L)
        val free = stats.availableBytes.coerceIn(0L, total)
        val used = (total - free).coerceAtLeast(0L)
        listOf(
            "Used" to formatBytes(used),
            "Available" to formatBytes(free),
            "Total" to formatBytes(total),
            "Usage" to if (total > 0L) (used * 100L / total).coerceIn(0L, 100L).toString() + "%" else "Unavailable"
        )
    } catch (_: Exception) {
        listOf("Status" to "Storage information is unavailable")
    }

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
            "Internet access" to if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) "Network advertises internet" else "No internet capability",
            "Validated internet" to if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) "Android validated internet access" else "Not validated / sign-in may be required",
            "Metered" to if (manager.isActiveNetworkMetered) "Yes" else "No"
        )
    }

    private fun addCard(title: String, rows: List<Pair<String, String>>) {
        val card = MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            cardElevation = dp(2).toFloat()
            setCardBackgroundColor(surfaceColor)
            useCompatPadding = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        column.addView(TextView(this).apply {
            text = title
            textSize = 12f
            letterSpacing = 0.08f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(accentColor)
            setPadding(0, 0, 0, dp(8))
        })
        rows.forEach { (label, value) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(7), 0, dp(7))
            }
            row.addView(TextView(this).apply {
                text = label
                textSize = 14f
                setTextColor(secondaryTextColor)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = value
                textSize = 14f
                setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
                setTextColor(primaryTextColor)
                gravity = Gravity.END
                maxWidth = dp(190)
            })
            column.addView(row)
            val divider = View(this).apply {
                setBackgroundColor(secondaryTextColor.withAlpha(45))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            }
            column.addView(divider)
        }
        card.addView(column)
        cards.addView(card)
    }

    private fun formatBytes(bytes: Long): String =
        android.text.format.Formatter.formatShortFileSize(this, bytes)

    private fun Int.withAlpha(alpha: Int): Int = (this and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
