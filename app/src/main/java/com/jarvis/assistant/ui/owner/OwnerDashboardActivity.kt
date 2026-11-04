package com.jarvis.assistant.ui.owner

import android.content.Intent
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
import com.google.android.material.tabs.TabLayout
import com.jarvis.assistant.R
import com.jarvis.assistant.databinding.ActivityOwnerDashboardBinding
import com.jarvis.assistant.owner.OverviewData
import com.jarvis.assistant.owner.OwnerDashboardClient
import com.jarvis.assistant.owner.OwnerPage
import com.jarvis.assistant.owner.OwnerResult
import com.jarvis.assistant.owner.OwnerRow
import com.jarvis.assistant.owner.OwnerSession
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

    private enum class Tab { OVERVIEW, DEVICES, IPS, ACTIVITY }

    private lateinit var binding: ActivityOwnerDashboardBinding
    private val adapter = OwnerRowAdapter()
    private var tab = Tab.OVERVIEW
    private var page = 0
    private var search = ""
    private var deviceWindowDays = 0
    private var eventType = ""
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
        Tab.DEVICES -> R.string.owner_tab_devices
        Tab.IPS -> R.string.owner_tab_ips
        Tab.ACTIVITY -> R.string.owner_tab_activity
    })

    private fun selectTab(t: Tab) {
        tab = t; page = 0; search = ""; deviceWindowDays = 0; eventType = ""
        binding.etSearch.setText("")
        binding.etSearch.hint = getString(if (t == Tab.IPS) R.string.owner_search_ips else R.string.owner_search_devices)
        binding.etSearch.visibility = if (t == Tab.DEVICES || t == Tab.IPS) View.VISIBLE else View.GONE
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
                Tab.DEVICES -> handle(OwnerDashboardClient.devices(page, search, deviceWindowDays)) { showPage(it) }
                Tab.IPS -> handle(OwnerDashboardClient.ips(page, search)) { showPage(it) }
                Tab.ACTIVITY -> handle(OwnerDashboardClient.events(page, eventType)) { showPage(it) }
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

    private fun showLoading() {
        binding.progress.visibility = View.VISIBLE
        binding.stateBox.visibility = View.GONE
        binding.list.visibility = View.GONE
        binding.overviewScroll.visibility = View.GONE
        binding.pager.visibility = View.GONE
    }

    private fun showState(message: String, retry: Boolean) {
        binding.progress.visibility = View.GONE
        binding.list.visibility = View.GONE
        binding.overviewScroll.visibility = View.GONE
        binding.pager.visibility = View.GONE
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
            R.string.owner_stat_accounts to nf.format(o.totalAccounts),
            R.string.owner_stat_mau to nf.format(o.accountsActive30d),
            R.string.owner_stat_devices to nf.format(o.totalDevices),
            R.string.owner_stat_recent_devices to nf.format(o.devicesActive24h),
            R.string.owner_stat_free to nf.format(o.freeUsers),
            R.string.owner_stat_pro to (o.proUsers?.let { nf.format(it) } ?: notTracked),
            R.string.owner_stat_signins to nf.format(o.signins24h),
            R.string.owner_stat_ips to nf.format(o.distinctIps30d)
        ).forEach { (label, value) ->
            val card = LayoutInflater.from(this).inflate(R.layout.item_owner_stat, grid, false)
            card.findViewById<TextView>(R.id.tvStatValue).text = value
            card.findViewById<TextView>(R.id.tvStatLabel).text = getString(label)
            grid.addView(card)
        }
        binding.tvDefinitions.text = getString(R.string.owner_overview_definitions)
        binding.tvProNote.text = o.proNote.ifBlank { o.presenceNote }
        binding.tvProNote.visibility = if (binding.tvProNote.text.isNullOrBlank()) View.GONE else View.VISIBLE
        fillRows(binding.recentSignins, o.recentSignins)
        fillRows(binding.recentActivity, o.recentActivity)
        binding.tvRetention.text = getString(R.string.owner_retention_fmt, o.ipRetentionDays)
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
