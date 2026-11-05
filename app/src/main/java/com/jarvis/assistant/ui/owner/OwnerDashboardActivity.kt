package com.jarvis.assistant.ui.owner

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.jarvis.assistant.R
import com.jarvis.assistant.databinding.ActivityOwnerDashboardBinding
import com.jarvis.assistant.owner.OverviewData
import com.jarvis.assistant.owner.OwnerDashboardClient
import com.jarvis.assistant.owner.OwnerPage
import com.jarvis.assistant.owner.OwnerResult
import com.jarvis.assistant.owner.OwnerPalette
import com.jarvis.assistant.owner.OwnerRow
import com.jarvis.assistant.owner.OwnerSession
import com.jarvis.assistant.owner.TrendsData
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.NumberFormat

/**
 * Owner analytics. Only reachable after the backend accepted the owner password. Every list is fetched one
 * page at a time from the server; nothing here decides authorization. Leaving the screen revokes the
 * dashboard session.
 */
class OwnerDashboardActivity : AppCompatActivity() {

    private enum class Tab { OVERVIEW, INSIGHTS, DEVICES, IPS, ACTIVITY, USER_CONTROL, USERS }

    private lateinit var binding: ActivityOwnerDashboardBinding
    private val adapter = OwnerRowAdapter { showDetail(it) }
    private var tab = Tab.OVERVIEW
    private var page = 0
    private var search = ""
    private var deviceWindowDays = 0
    private var eventType = ""
    private var trendDays = 14
    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var expiryJob: Job? = null
    private var totalPages = 1
    private var leftForPassword = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        // No valid dashboard session (e.g. process was recreated): never show data, ask for the password.
        if (!OwnerSession.hasSession) { returnToPassword(); return }

        binding = ActivityOwnerDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ThemeManager.applyDynamicBackground(binding.root)

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.btnBack.setOnClickListener { finish() }
        binding.btnAddBlock.setOnClickListener { showAddBlockDialog() }
        binding.btnRefresh.setOnClickListener { load() }
        binding.btnRetry.setOnClickListener { load() }
        binding.btnPrev.setOnClickListener { if (page > 0) { page--; load() } }
        binding.btnNext.setOnClickListener { if (page + 1 < totalPages) { page++; load() } }

        Tab.values().forEach { t ->
            binding.tabs.addTab(binding.tabs.newTab().setText(tabTitle(t)).setTag(t))
        }
        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(t: TabLayout.Tab) { selectTab(t.tag as Tab) }
            override fun onTabUnselected(t: TabLayout.Tab) {}
            override fun onTabReselected(t: TabLayout.Tab) {}
        })

        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { applySearch(); true } else false
        }
        binding.etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                searchJob?.cancel()
                searchJob = lifecycleScope.launch { delay(450); applySearch() }
            }
        })

        scheduleExpiry()
        selectTab(Tab.OVERVIEW)
    }

    // ── Tabs / filters ──────────────────────────────────────────────────────────────────────────

    private fun tabTitle(t: Tab) = getString(when (t) {
        Tab.OVERVIEW -> R.string.owner_tab_overview
        Tab.INSIGHTS -> R.string.owner_tab_insights
        Tab.DEVICES -> R.string.owner_tab_devices
        Tab.IPS -> R.string.owner_tab_ips
        Tab.ACTIVITY -> R.string.owner_tab_activity
        Tab.USER_CONTROL -> R.string.owner_tab_user_control
        Tab.USERS -> R.string.owner_tab_users
    })

    private fun selectTab(t: Tab) {
        tab = t; page = 0; search = ""; deviceWindowDays = 0; eventType = ""; trendDays = 14
        binding.etSearch.setText("")
        binding.etSearch.hint = getString(if (t == Tab.IPS) R.string.owner_search_ips else R.string.owner_search_devices)
        binding.etSearch.visibility = if (t == Tab.DEVICES || t == Tab.IPS || t == Tab.USERS) View.VISIBLE else View.GONE
        binding.btnAddBlock.visibility = if (t == Tab.USER_CONTROL) View.VISIBLE else View.GONE
        buildFilters()
        binding.filterBar.visibility = if (t == Tab.OVERVIEW) View.GONE else View.VISIBLE
        load()
    }

    private fun buildFilters() {
        binding.chipFilters.removeAllViews()
        val options: List<Pair<Int, () -> Unit>> = when (tab) {
            Tab.DEVICES -> listOf(
                R.string.owner_filter_any to { deviceWindowDays = 0 },
                R.string.owner_filter_24h to { deviceWindowDays = 1 },
                R.string.owner_filter_7d to { deviceWindowDays = 7 },
                R.string.owner_filter_30d to { deviceWindowDays = 30 })
            Tab.INSIGHTS -> listOf(
                R.string.owner_range_14 to { trendDays = 14 },
                R.string.owner_range_7 to { trendDays = 7 },
                R.string.owner_range_30 to { trendDays = 30 })
            Tab.ACTIVITY -> listOf(
                R.string.owner_filter_all to { eventType = "" },
                R.string.owner_filter_signins to { eventType = "sign_in" },
                R.string.owner_filter_opens to { eventType = "app_open" })
            else -> emptyList()
        }
        options.forEachIndexed { i, (label, onPick) ->
            val chip = Chip(this).apply {
                id = View.generateViewId()
                text = getString(label)
                isCheckable = true
                isChecked = i == 0
                setOnClickListener { onPick(); page = 0; load() }
            }
            binding.chipFilters.addView(chip)
        }
        binding.chipFilters.parent.let { (it as View).visibility = if (options.isEmpty()) View.GONE else View.VISIBLE }
    }

    private fun applySearch() {
        val q = binding.etSearch.text?.toString()?.trim().orEmpty()
        if (q == search) return
        search = q; page = 0; load()
    }

    // ── Loading ─────────────────────────────────────────────────────────────────────────────────

    private fun load() {
        loadJob?.cancel()
        showLoading()
        val requested = tab
        loadJob = lifecycleScope.launch {
            when (requested) {
                Tab.OVERVIEW -> handle(OwnerDashboardClient.overview()) { showOverview(it) }
                Tab.INSIGHTS -> handle(OwnerDashboardClient.trends(trendDays)) { showInsights(it) }
                Tab.DEVICES -> handle(OwnerDashboardClient.devices(page, search, deviceWindowDays)) { showPage(it) }
                Tab.IPS -> handle(OwnerDashboardClient.ips(page, search)) { showPage(it) }
                Tab.ACTIVITY -> handle(OwnerDashboardClient.events(page, eventType)) { showPage(it) }
                Tab.USER_CONTROL -> handle(OwnerDashboardClient.blocks()) { showPage(it) }
                Tab.USERS -> handle(OwnerDashboardClient.users(page, search)) { showPage(it) }
            }
        }
    }

    private fun <T> handle(result: OwnerResult<T>, onOk: (T) -> Unit) {
        when (result) {
            is OwnerResult.Ok -> onOk(result.value)
            OwnerResult.SessionExpired, OwnerResult.NotSignedIn -> {
                Toast.makeText(this, R.string.owner_session_expired, Toast.LENGTH_LONG).show()
                returnToPassword()
            }
            OwnerResult.Network -> showState(getString(R.string.owner_pw_network), retry = true)
            else -> showState(getString(R.string.owner_error), retry = true)
        }
    }

    private fun hideContent() {
        binding.list.visibility = View.GONE
        binding.overviewScroll.visibility = View.GONE
        binding.insightsScroll.visibility = View.GONE
        binding.pager.visibility = View.GONE
    }

    private fun showLoading() {
        hideContent()
        binding.progress.visibility = View.VISIBLE
        binding.stateBox.visibility = View.GONE
    }

    private fun stampUpdated() {
        val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())
        binding.tvDashSubtitle.text = getString(R.string.owner_updated_fmt, time)
    }

    private fun showState(message: String, retry: Boolean) {
        hideContent()
        binding.progress.visibility = View.GONE
        binding.stateBox.visibility = View.VISIBLE
        binding.tvState.text = message
        binding.btnRetry.visibility = if (retry) View.VISIBLE else View.GONE
    }

    private fun showPage(p: OwnerPage) {
        binding.progress.visibility = View.GONE
        if (p.rows.isEmpty()) {
            showState(getString(R.string.owner_empty), retry = false)
            return
        }
        binding.stateBox.visibility = View.GONE
        stampUpdated()
        binding.list.visibility = View.VISIBLE
        adapter.submit(p.rows)
        binding.list.scrollToPosition(0)
        totalPages = p.pageCount
        binding.pager.visibility = View.VISIBLE
        binding.tvPage.text = getString(R.string.owner_page_fmt, p.page + 1, totalPages, p.total)
        binding.btnPrev.alpha = if (page > 0) 1f else 0.4f
        binding.btnNext.alpha = if (page + 1 < totalPages) 1f else 0.4f
    }

    private fun showOverview(o: OverviewData) {
        binding.progress.visibility = View.GONE
        binding.stateBox.visibility = View.GONE
        binding.overviewScroll.visibility = View.VISIBLE

        val nf = NumberFormat.getIntegerInstance()
        val notTracked = getString(R.string.owner_not_tracked)
        val grid = binding.statGrid
        grid.removeAllViews()
        grid.columnCount = resources.getInteger(R.integer.owner_stat_columns)
        listOf(
            Triple(R.string.owner_stat_accounts, nf.format(o.totalAccounts), OwnerPalette.CYAN),
            Triple(R.string.owner_stat_mau, nf.format(o.accountsActive30d), OwnerPalette.VIOLET),
            Triple(R.string.owner_stat_devices, nf.format(o.totalDevices), OwnerPalette.BLUE),
            Triple(R.string.owner_stat_recent_devices, nf.format(o.devicesActive24h), OwnerPalette.GREEN),
            Triple(R.string.owner_stat_free, nf.format(o.freeUsers), OwnerPalette.AMBER),
            Triple(R.string.owner_stat_pro, o.proUsers?.let { nf.format(it) } ?: notTracked, OwnerPalette.PINK),
            Triple(R.string.owner_stat_signins, nf.format(o.signins24h), OwnerPalette.ORANGE),
            Triple(R.string.owner_stat_ips, nf.format(o.distinctIps30d), OwnerPalette.RED)
        ).forEach { (label, value, color) ->
            val card = LayoutInflater.from(this).inflate(R.layout.item_owner_stat, grid, false)
            card.findViewById<TextView>(R.id.tvStatValue).apply {
                text = value; setTextColor(color); typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            }
            card.findViewById<TextView>(R.id.tvStatLabel).apply { text = getString(label); typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
            card.background = tinted(color)
            grid.addView(card)
        }
        binding.tvDefinitions.text = getString(R.string.owner_overview_definitions)
        binding.tvProNote.text = o.proNote.ifBlank { o.presenceNote }
        binding.tvProNote.visibility = if (binding.tvProNote.text.isNullOrBlank()) View.GONE else View.VISIBLE
        fillRows(binding.recentSignins, o.recentSignins)
        fillRows(binding.recentActivity, o.recentActivity)
        binding.tvRetention.text = getString(R.string.owner_retention_fmt, o.ipRetentionDays)
        stampUpdated()
    }

    // ── Insights ────────────────────────────────────────────────────────────────────────────────

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Soft tinted card: translucent fill and outline in one accent colour. */
    private fun tinted(color: Int) = GradientDrawable().apply {
        cornerRadius = dp(16).toFloat()
        setColor(Color.argb(34, Color.red(color), Color.green(color), Color.blue(color)))
        setStroke(dp(1), Color.argb(110, Color.red(color), Color.green(color), Color.blue(color)))
    }

    private fun insightCard(title: String, color: Int, content: View): View {
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(14))
            background = tinted(color)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(5), dp(5), dp(5), dp(5)) }
        }
        box.addView(TextView(this).apply {
            text = title
            setTextColor(color)
            textSize = 13f
            letterSpacing = 0.08f
            isAllCaps = true
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        })
        box.addView(content)
        return box
    }

    private fun legend(items: List<Pair<String, Int>>): View {
        val row = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
        items.forEach { (name, color) ->
            row.addView(TextView(this).apply {
                text = "● $name"
                setTextColor(color)
                textSize = 12f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(0, dp(8), dp(14), 0)
            })
        }
        return row
    }

    private fun showInsights(t: TrendsData) {
        binding.progress.visibility = View.GONE
        binding.stateBox.visibility = View.GONE
        binding.insightsScroll.visibility = View.VISIBLE
        val box = binding.insightsContainer
        box.removeAllViews()

        // Daily activity chart
        val labels = t.daily.map { it.day.takeLast(5).replace('-', '/') }
        val chart = TrendChartView(this).apply {
            setData(labels, listOf(
                TrendChartView.Series(getString(R.string.owner_legend_signins), OwnerPalette.VIOLET, t.daily.map { it.signIns }),
                TrendChartView.Series(getString(R.string.owner_legend_opens), OwnerPalette.CYAN, t.daily.map { it.appOpens }),
                TrendChartView.Series(getString(R.string.owner_legend_devices), OwnerPalette.GREEN, t.daily.map { it.newDevices })
            ))
        }
        val chartBox = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        chartBox.addView(chart)
        chartBox.addView(legend(listOf(
            getString(R.string.owner_legend_signins) to OwnerPalette.VIOLET,
            getString(R.string.owner_legend_opens) to OwnerPalette.CYAN,
            getString(R.string.owner_legend_devices) to OwnerPalette.GREEN
        )))
        box.addView(insightCard(getString(R.string.owner_ins_daily) + " · ${t.days}d", OwnerPalette.VIOLET, chartBox))

        fun distribution(title: Int, color: Int, items: List<com.jarvis.assistant.owner.LabelCount>, palette: IntArray) {
            val content: View = if (items.isEmpty()) {
                TextView(this).apply {
                    text = getString(R.string.owner_no_data_yet); textSize = 13f
                    setTextColor(Color.GRAY)
                }
            } else DistributionView(this).apply { setData(items.map { it.label to it.count }, palette) }
            box.addView(insightCard(getString(title), color, content))
        }
        distribution(R.string.owner_ins_models, OwnerPalette.BLUE, t.models, intArrayOf(OwnerPalette.BLUE, OwnerPalette.CYAN, OwnerPalette.VIOLET, OwnerPalette.GREEN))
        distribution(R.string.owner_ins_android, OwnerPalette.GREEN, t.androidVersions, intArrayOf(OwnerPalette.GREEN, OwnerPalette.AMBER, OwnerPalette.ORANGE, OwnerPalette.PINK))
        distribution(R.string.owner_ins_app, OwnerPalette.AMBER, t.appVersions, intArrayOf(OwnerPalette.AMBER, OwnerPalette.ORANGE, OwnerPalette.RED, OwnerPalette.PINK))
        distribution(R.string.owner_ins_providers, OwnerPalette.PINK, t.providers, intArrayOf(OwnerPalette.PINK, OwnerPalette.VIOLET, OwnerPalette.CYAN))

        // Security: lockouts and open sessions, straight from the backend.
        val nf = NumberFormat.getIntegerInstance()
        val sec = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
        listOf(
            Triple(R.string.owner_sec_locked, t.lockedNow, if (t.lockedNow > 0) OwnerPalette.RED else OwnerPalette.GREEN),
            Triple(R.string.owner_sec_failed, t.failedAttempts24h, if (t.failedAttempts24h > 0) OwnerPalette.AMBER else OwnerPalette.GREEN),
            Triple(R.string.owner_sec_sessions, t.activeDashboardSessions, OwnerPalette.CYAN)
        ).forEach { (label, value, color) ->
            sec.addView(android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(context).apply {
                    text = nf.format(value); textSize = 24f; setTextColor(color)
                    typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
                })
                addView(TextView(context).apply {
                    text = getString(label); textSize = 11f; setTextColor(Color.GRAY)
                    typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                })
            })
        }
        box.addView(insightCard(getString(R.string.owner_ins_security), OwnerPalette.RED, sec))
        stampUpdated()
    }

    // ── Row details ─────────────────────────────────────────────────────────────────────────────

    private fun showDetail(row: OwnerRow) {
        if (tab == Tab.USER_CONTROL && row.detail.startsWith("BLOCK|")) {
            val parts = row.detail.split("|", limit = 3)
            if (parts.size == 3) MaterialAlertDialogBuilder(this)
                .setTitle("Unblock access?")
                .setMessage("Allow this " + parts[1] + " to use JARVIS again?\\n\\n" + parts[2])
                .setNegativeButton("Cancel", null)
                .setPositiveButton("UNBLOCK") { _, _ ->
                    lifecycleScope.launch {
                        when (OwnerDashboardClient.setBlock(parts[1], parts[2], blocked = false)) {
                            is OwnerResult.Ok -> { Toast.makeText(this@OwnerDashboardActivity, "Access unblocked", Toast.LENGTH_SHORT).show(); load() }
                            else -> Toast.makeText(this@OwnerDashboardActivity, R.string.owner_error, Toast.LENGTH_SHORT).show()
                        }
                    }
                }.show()
            return
        }
        if (row.detail.isBlank()) return
        val body = TextView(this).apply {
            text = row.detail
            typeface = Typeface.MONOSPACE
            textSize = 12.5f
            setTextIsSelectable(true)
            setPadding(dp(22), dp(8), dp(22), 0)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(row.title)
            .setView(android.widget.ScrollView(this).apply { addView(body) })
            .setNeutralButton(R.string.owner_copy) { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("JARVIS", row.detail))
                Toast.makeText(this, R.string.owner_copied, Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton(R.string.owner_close, null)
            .show()
    }

    private fun fillRows(container: android.widget.LinearLayout, rows: List<OwnerRow>) {
        container.removeAllViews()
        if (rows.isEmpty()) {
            container.addView(TextView(this).apply {
                text = getString(R.string.owner_empty)
                setTextColor(resources.getColor(android.R.color.darker_gray, theme))
                setPadding(8, 8, 8, 8)
            })
            return
        }
        rows.forEach { row ->
            val v = LayoutInflater.from(this).inflate(R.layout.item_owner_row, container, false)
            OwnerRowAdapter.bindRow(v, row)
            container.addView(v)
        }
    }

    private fun showAddBlockDialog() {
        val types = arrayOf("IP address", "Device install ID", "User account ID")
        val keys = arrayOf("ip", "device", "user")
        var selected = 0
        val input = android.widget.EditText(this).apply { hint = "Exact IP / device ID / user ID"; maxLines = 1 }
        val reason = android.widget.EditText(this).apply { hint = "Reason (optional)"; maxLines = 2 }
        val content = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(android.widget.Spinner(this@OwnerDashboardActivity).apply {
                adapter = android.widget.ArrayAdapter(this@OwnerDashboardActivity, android.R.layout.simple_spinner_dropdown_item, types)
                onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) { selected = position }
                }
            })
            addView(input); addView(reason)
        }
        MaterialAlertDialogBuilder(this).setTitle("Block JARVIS access")
            .setMessage("The server will reject matching access checks.")
            .setView(content).setNegativeButton("Cancel", null)
            .setPositiveButton("BLOCK") { _, _ ->
                val target = input.text?.toString()?.trim().orEmpty()
                if (target.isNotBlank()) lifecycleScope.launch {
                    when (OwnerDashboardClient.setBlock(keys[selected], target, reason.text?.toString().orEmpty(), true)) {
                        is OwnerResult.Ok -> { Toast.makeText(this@OwnerDashboardActivity, "Access block saved", Toast.LENGTH_SHORT).show(); load() }
                        else -> Toast.makeText(this@OwnerDashboardActivity, "Could not save block. Check the identifier.", Toast.LENGTH_SHORT).show()
                    }
                } else Toast.makeText(this, "Enter an identifier", Toast.LENGTH_SHORT).show()
            }.show()
    }
    // ── Session lifetime ────────────────────────────────────────────────────────────────────────

    /** Closes the dashboard when the session's time is up; the server rejects it at the same moment. */
    private fun scheduleExpiry() {
        expiryJob?.cancel()
        expiryJob = lifecycleScope.launch {
            val wait = OwnerSession.expiresAtMs() - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            Toast.makeText(this@OwnerDashboardActivity, R.string.owner_session_expired, Toast.LENGTH_LONG).show()
            returnToPassword()
        }
    }

    private fun returnToPassword() {
        if (leftForPassword) return
        leftForPassword = true
        OwnerSession.clear()
        startActivity(Intent(this, OwnerPasswordActivity::class.java))
        finish()
    }

    /** Leaving the app or the screen ends dashboard access instead of leaving data open in the background. */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !isFinishing) finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isChangingConfigurations) {
            // Revoke server-side even though this activity's scope is ending.
            CoroutineScope(Dispatchers.IO).launch { OwnerDashboardClient.endSession() }
        }
    }
}
