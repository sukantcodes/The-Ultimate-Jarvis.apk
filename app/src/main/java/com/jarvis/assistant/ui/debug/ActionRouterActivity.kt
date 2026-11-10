package com.jarvis.assistant.ui.debug

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.jarvis.assistant.automation.action.ActionLatencyStore
import com.jarvis.assistant.R
import com.jarvis.assistant.util.ThemeManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Settings for native-first routing plus its local execution-latency dashboard. */
class ActionRouterActivity : AppCompatActivity() {
    private lateinit var list: LinearLayout
    private val prefs by lazy { getSharedPreferences("jarvis_prefs", MODE_PRIVATE) }
    private val store by lazy { ActionLatencyStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        ThemeManager.applyDynamicBackground(root)
        root.addView(TextView(this).apply {
            text = "Native-first Action Router"; textSize = 23f
            setTextColor(color(R.attr.themeTextPrimary))
        })
        root.addView(TextView(this).apply {
            text = "Try native Android APIs, explicit intents and deep links before accessibility, screen vision and existing automation."
            textSize = 13f; setPadding(0, dp(8), 0, dp(14))
            setTextColor(color(R.attr.themeTextSecondary))
        })
        val toggle = MaterialSwitch(this).apply {
            text = "Enable native-first routing"
            isChecked = prefs.getBoolean(KEY_ENABLED, false)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(KEY_ENABLED, checked).apply() }
        }
        root.addView(toggle)
        root.addView(TextView(this).apply {
            text = "When disabled, the original command execution path is used unchanged. Routing history is stored locally (maximum 200 records)."
            textSize = 12f; setPadding(0, dp(4), 0, dp(12))
            setTextColor(color(R.attr.themeTextSecondary))
        })
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(MaterialButton(this).apply { text = "Refresh"; setOnClickListener { render() } })
        buttons.addView(MaterialButton(this).apply {
            text = "Clear history"
            setOnClickListener {
                MaterialAlertDialogBuilder(this@ActionRouterActivity)
                    .setTitle("Clear latency history?")
                    .setMessage("Only local diagnostic records will be removed.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Clear") { _, _ -> store.clear(); render() }.show()
            }
        })
        root.addView(buttons)
        val scroll = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        render()
    }

    private fun render() {
        list.removeAllViews()
        val rows = store.recent()
        if (rows.isEmpty()) {
            list.addView(TextView(this).apply { text = "No routed actions recorded yet."; setPadding(0, dp(18), 0, dp(18)) })
            return
        }
        val date = SimpleDateFormat("dd MMM, HH:mm:ss", Locale.getDefault())
        rows.forEach { row ->
            list.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setBackgroundColor(color(R.attr.themeCardBg))
                addView(TextView(this@ActionRouterActivity).apply {
                    text = row.taskName; textSize = 15f
                    setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
                })
                addView(TextView(this@ActionRouterActivity).apply {
                    text = "${row.path} · ${row.latencyMs} ms · ${if (row.success) "SUCCESS" else "FAILED"}\n${date.format(Date(row.timestampMs))}\nTried: ${row.attemptedPaths.joinToString(" → ")}"
                    textSize = 12f; setPadding(0, dp(5), 0, 0)
                    setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
                })
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    private fun color(attr: Int): Int {
        val value = android.util.TypedValue()
        theme.resolveAttribute(attr, value, true)
        return if (value.resourceId != 0) getColor(value.resourceId) else value.data
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object { private const val KEY_ENABLED = "key_native_action_router_enabled" }
}
