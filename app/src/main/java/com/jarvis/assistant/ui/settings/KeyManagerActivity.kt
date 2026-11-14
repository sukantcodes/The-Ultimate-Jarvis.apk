package com.jarvis.assistant.ui.settings

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.R
import com.jarvis.assistant.provider.AuthMethod
import com.jarvis.assistant.provider.KeyManager
import com.jarvis.assistant.provider.KeyRecord
import com.jarvis.assistant.provider.ProviderProfile
import com.jarvis.assistant.provider.ProviderProtocol
import com.jarvis.assistant.provider.ProviderRuntime
import com.jarvis.assistant.provider.ResetSchedule
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * API Key Manager.
 * Enables multiple API keys per provider (Gemini, Custom AI Providers, ElevenLabs) with automatic
 * rate-limit/quota failover, limit flag reset, priority reordering, and resilient Keystore access.
 */
class KeyManagerActivity : AppCompatActivity() {

    private val providers by lazy { ProviderRuntime.manager(applicationContext) }
    private val keys by lazy { ProviderRuntime.keyManager(applicationContext) }
    private val pool by lazy { ProviderRuntime.keyPool(applicationContext) }

    private lateinit var list: LinearLayout
    private lateinit var summary: android.widget.TextView
    private lateinit var spinner: Spinner
    private var allProviders: List<ProviderProfile> = emptyList()
    private var selected: ProviderProfile? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ThemeManager.applyDynamicBackground(root)

        val bar = UiKit.row(this).apply {
            setPadding(
                UiKit.dp(this@KeyManagerActivity, 8),
                UiKit.dp(this@KeyManagerActivity, 12),
                UiKit.dp(this@KeyManagerActivity, 16),
                UiKit.dp(this@KeyManagerActivity, 8)
            )
        }
        bar.addView(UiKit.button(this, "‹ Back") { finish() })
        bar.addView(UiKit.text(this, "  API Key Manager", 20f, bold = true))
        root.addView(bar)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = UiKit.dp(this@KeyManagerActivity, 16)
            setPadding(p, 0, p, p)
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(body) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        body.addView(UiKit.text(this, "Provider", 12f, muted = true))
        spinner = Spinner(this).apply {
            minimumHeight = UiKit.dp(this@KeyManagerActivity, 48)
        }
        body.addView(spinner)

        refreshProviders()

        summary = UiKit.text(this, "", 12f, muted = true).apply {
            setPadding(0, UiKit.dp(this@KeyManagerActivity, 8), 0, UiKit.dp(this@KeyManagerActivity, 8))
        }
        body.addView(summary)

        // Action Buttons Row: Add Key, Reset Provider Limits, Reset All Limits
        val actionRow = UiKit.row(this).apply {
            gravity = Gravity.START
            setPadding(0, 0, 0, UiKit.dp(this@KeyManagerActivity, 8))
        }
        actionRow.addView(UiKit.button(this, "+ Add API key", outlined = false) { showEditor(null) })
        actionRow.addView(UiKit.gap(this, 8))
        actionRow.addView(UiKit.button(this, "Reset Limit Flags", outlined = true) {
            val prof = selected ?: return@button
            val cleared = keys.resetLimitFlags(prof.id)
            render()
            toast("Cleared limit flags for $cleared key(s) under ${prof.displayName}.")
        })
        actionRow.addView(UiKit.gap(this, 8))
        actionRow.addView(UiKit.button(this, "Reset All Limits", outlined = true) {
            val cleared = keys.resetLimitFlags(null)
            render()
            toast("Cleared limit flags across all providers ($cleared keys restored).")
        })

        val hScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(actionRow)
        }
        body.addView(hScroll)
        body.addView(UiKit.gap(this, 8))

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(list)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                selected = allProviders.getOrNull(pos) ?: allProviders.firstOrNull()
                render()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        render()
    }

    private fun refreshProviders() {
        val geminiBuiltIn = ProviderProfile(
            id = "gemini",
            name = "Google Gemini (Built-in)",
            baseUrl = "https://generativelanguage.googleapis.com",
            protocol = ProviderProtocol.GEMINI,
            authMethod = AuthMethod.BEARER
        )
        val groqBuiltIn = ProviderProfile(
            id = "groq",
            name = "Groq (Built-in)",
            baseUrl = "https://api.groq.com/openai",
            protocol = ProviderProtocol.OPENAI_COMPATIBLE,
            authMethod = AuthMethod.BEARER
        )
        val elevenLabsBuiltIn = ProviderProfile(
            id = "elevenlabs",
            name = "ElevenLabs Turbo",
            baseUrl = "https://api.elevenlabs.io",
            protocol = ProviderProtocol.OPENAI_COMPATIBLE,
            authMethod = AuthMethod.CUSTOM_HEADER,
            authName = "xi-api-key"
        )
        val customProfiles = providers.profiles()
        allProviders = listOf(geminiBuiltIn, groqBuiltIn, elevenLabsBuiltIn) + customProfiles

        val currentId = selected?.id ?: providers.activeProfile()?.id ?: "gemini"
        val titles = allProviders.map { it.displayName }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, titles).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinner.adapter = adapter
        val idx = allProviders.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        spinner.setSelection(idx)
        selected = allProviders.getOrNull(idx) ?: allProviders.firstOrNull()
    }

    override fun onResume() {
        super.onResume()
        refreshProviders()
        render()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    render()
                    delay(15_000)
                }
            }
        }
    }

    private fun render() {
        val profile = selected ?: return
        val records = keys.keys(profile.id)
        val now = System.currentTimeMillis()
        val usable = records.count { it.isUsableAt(now) }
        val rateLimitedCount = records.count { it.isRateLimitedAt(now) }

        val rateLimitNote = if (rateLimitedCount > 0) " · $rateLimitedCount currently rate-limited" else ""
        summary.text = if (records.isEmpty()) {
            "No keys stored for ${profile.displayName}. Add multiple keys and JARVIS will automatically fail over to the next key upon rate-limit (429)."
        } else {
            "${records.size} key${if (records.size == 1) "" else "s"} · $usable ready$rateLimitNote. Lower priority is leased first."
        }
        list.removeAllViews()
        records.sortedWith(compareBy({ it.priority }, { it.createdAt })).forEach { list.addView(keyCard(it, now)) }
    }

    private fun keyCard(rec: KeyRecord, now: Long): LinearLayout {
        val card = UiKit.card(this)
        val head = UiKit.row(this)
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val labelRow = UiKit.row(this)
        labelRow.addView(UiKit.text(this, rec.label, 16f, bold = true))
        if (rec.isRateLimitedAt(now)) {
            val badge = UiKit.text(this, " ⚡ RATE LIMITED ", 10f, bold = true).apply {
                setTextColor(ThemeManager.getThemeWarningColor())
                setPadding(UiKit.dp(this@KeyManagerActivity, 6), 0, 0, 0)
            }
            labelRow.addView(badge)
        } else if (rec.isUsableAt(now)) {
            val badge = UiKit.text(this, " ✓ READY ", 10f, bold = true).apply {
                setTextColor(ThemeManager.getThemeSuccessColor())
                setPadding(UiKit.dp(this@KeyManagerActivity, 6), 0, 0, 0)
            }
            labelRow.addView(badge)
        }
        titles.addView(labelRow)

        titles.addView(
            UiKit.text(
                this,
                keys.mask(rec.id).ifEmpty { "Saved Secret" } + " · priority ${rec.priority}" +
                        (if (rec.modelId.isNotBlank()) " · ${rec.modelId}" else ""),
                12f,
                muted = true
            )
        )
        head.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val toggle = Switch(this).apply {
            isChecked = rec.enabled
            contentDescription = "Use ${rec.label}"
            setOnCheckedChangeListener { _, on ->
                pool.setEnabled(rec.id, on)
                render()
            }
        }
        head.addView(toggle)
        card.addView(head)

        card.addView(UiKit.text(this, keys.statusLine(rec, now), 12f).apply {
            setPadding(0, UiKit.dp(this@KeyManagerActivity, 6), 0, 0)
        })
        if (rec.lastFailureNote.isNotBlank()) {
            card.addView(UiKit.text(this, rec.lastFailureNote, 11f, muted = true))
        }

        val actions = UiKit.row(this).apply { gravity = Gravity.START }
        actions.addView(UiKit.button(this, "Test") { testKey(rec) })
        actions.addView(UiKit.gap(this, 6))
        actions.addView(UiKit.button(this, "Edit") { showEditor(rec) })
        actions.addView(UiKit.gap(this, 6))
        actions.addView(UiKit.button(this, "Reset Limit") {
            pool.resetCooldown(rec.id)
            render()
            toast("Rate limit flag & cooldown cleared for ${rec.label}.")
        })
        actions.addView(UiKit.gap(this, 6))
        actions.addView(UiKit.button(this, "Delete") { confirmDelete(rec) })

        val hs = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(actions)
        }
        card.addView(hs)
        return card
    }

    private fun testKey(rec: KeyRecord) {
        toast("Testing ${rec.label}…")
        lifecycleScope.launch {
            val report = try {
                keys.test(rec.id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            render()
            MaterialAlertDialogBuilder(this@KeyManagerActivity)
                .setTitle(if (report?.ok == true) "Key Operational" else "Key Test Failed")
                .setMessage(report?.summary ?: "This key could not be tested. Check network connectivity and credentials.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun confirmDelete(rec: KeyRecord) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete ${rec.label}?")
            .setMessage("The key will be safely removed from secure storage.")
            .setPositiveButton("Delete") { _, _ ->
                keys.remove(rec.id)
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Add (rec == null) or edit existing API key. Safe from null pointer exceptions. */
    private fun showEditor(rec: KeyRecord?) {
        try {
            val profile = selected ?: allProviders.firstOrNull() ?: run {
                toast("Please select a provider first.")
                return
            }
            val label = UiKit.input(this, "Key Label (e.g. Primary Key, Backup)", rec?.label.orEmpty())
            val key = UiKit.input(this, if (rec == null) "API Key" else "New API Key (leave empty to keep)", secret = true)
            val priority = UiKit.input(this, "Priority (1 = highest priority)", (rec?.priority ?: 100).toString(), number = true)
            val model = UiKit.input(this, "Model for this key (optional)", rec?.modelId.orEmpty())
            val cooldown = UiKit.input(this, "Cooldown after rate-limit, minutes", ((rec?.cooldownMs ?: KeyRecord.DEFAULT_QUOTA_COOLDOWN_MS) / 60_000L).toString(), number = true)

            val schedTypes = arrayOf("No reset schedule", "Resets daily at a time", "Resets every N minutes")
            val sched = Spinner(this).apply {
                minimumHeight = UiKit.dp(this@KeyManagerActivity, 48)
                val schedAdapter = ArrayAdapter(this@KeyManagerActivity, android.R.layout.simple_spinner_item, schedTypes).apply {
                    setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
                adapter = schedAdapter
                setSelection(when (rec?.resetSchedule?.type) {
                    ResetSchedule.Type.DAILY -> 1
                    ResetSchedule.Type.INTERVAL -> 2
                    else -> 0
                })
            }
            val s = rec?.resetSchedule ?: ResetSchedule.NONE
            val at = UiKit.input(this, "Daily time HH:MM (24 h)", if (s.type == ResetSchedule.Type.DAILY) "%02d:%02d".format(s.hour, s.minute) else "")
            val offset = UiKit.input(this, "Time zone offset, minutes from UTC", (if (s.type == ResetSchedule.Type.DAILY) s.utcOffsetMinutes else java.util.TimeZone.getDefault().rawOffset / 60_000).toString())
            val every = UiKit.input(this, "Reset interval, minutes", if (s.type == ResetSchedule.Type.INTERVAL) s.intervalMinutes.toString() else "", number = true)

            val form = UiKit.form(
                this,
                label, key, priority, model, cooldown,
                UiKit.text(this, "Quota Reset Schedule (Optional)", 12f, muted = true),
                sched, at, offset, every
            )

            fun processSave(): Boolean {
                val schedule = when (sched.selectedItemPosition) {
                    1 -> {
                        val m = Regex("^(\\d{1,2}):(\\d{2})$").matchEntire(at.text.toString().trim())
                        val h = m?.groupValues?.get(1)?.toInt()
                        val mi = m?.groupValues?.get(2)?.toInt()
                        if (h == null || mi == null || h > 23 || mi > 59) {
                            toast("Enter the daily reset time as HH:MM.")
                            return false
                        }
                        ResetSchedule.daily(h, mi, offset.text.toString().trim().toIntOrNull() ?: 0)
                    }
                    2 -> {
                        val n = every.text.toString().trim().toIntOrNull()
                        if (n == null || n <= 0) {
                            toast("Enter the reset interval in minutes.")
                            return false
                        }
                        ResetSchedule.every(n, System.currentTimeMillis())
                    }
                    else -> ResetSchedule.NONE
                }
                val prio = priority.text.toString().trim().toIntOrNull() ?: 100
                val rest = (cooldown.text.toString().trim().toLongOrNull() ?: 60L).coerceIn(1L, 7L * 24 * 60) * 60_000L
                val typed = key.text.toString().trim()

                val problem = if (rec == null) {
                    if (typed.isBlank()) {
                        toast("Please enter an API key.")
                        return false
                    }
                    keys.add(
                        providerId = profile.id,
                        label = label.text.toString().trim(),
                        keyText = typed,
                        priority = prio,
                        modelId = model.text.toString().trim(),
                        cooldownMs = rest,
                        resetSchedule = schedule
                    )
                } else {
                    if (typed.isNotBlank()) keys.replaceKey(rec.id, typed) else null
                }

                if (problem != null) {
                    toast(problem)
                    return false
                }

                if (rec != null) {
                    pool.get(rec.id)?.let {
                        pool.upsert(
                            it.copy(
                                label = label.text.toString().trim().ifEmpty { it.label },
                                priority = prio,
                                modelId = model.text.toString().trim(),
                                cooldownMs = rest,
                                resetSchedule = schedule
                            )
                        )
                    }
                }
                render()
                if (rec == null) toast("API key saved for ${profile.displayName}.")
                return true
            }

            val dlg = MaterialAlertDialogBuilder(this)
                .setTitle(if (rec == null) "Add API Key (${profile.displayName})" else "Edit ${rec.label}")
                .setView(ScrollView(this).apply { addView(form) })
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .create()

            dlg.setOnShowListener {
                val accent = ThemeManager.getThemeAccentColor()
                dlg.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.setTextColor(accent)
                dlg.getButton(android.content.DialogInterface.BUTTON_NEGATIVE)?.setTextColor(ThemeManager.getThemeTextSecondaryColor())
                val positiveBtn = dlg.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
                positiveBtn?.setOnClickListener {
                    try {
                        if (processSave()) {
                            dlg.dismiss()
                        }
                    } catch (e: Exception) {
                        toast("Couldn't save the key: ${e.message ?: e.javaClass.simpleName}")
                    }
                }
            }
            dlg.show()
        } catch (t: Throwable) {
            android.util.Log.e("KeyManagerActivity", "Failed to open key editor dialog", t)
            toast("Could not open key editor: ${t.localizedMessage ?: t.javaClass.simpleName}")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
