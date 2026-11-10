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
import com.jarvis.assistant.provider.KeyManager
import com.jarvis.assistant.provider.KeyRecord
import com.jarvis.assistant.provider.ProviderProfile
import com.jarvis.assistant.provider.ProviderRuntime
import com.jarvis.assistant.provider.ResetSchedule
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * API Key Manager. Lists the saved keys of one custom provider with a masked hint, status and usage, and lets the
 * user add, rename, edit, switch off, test, reset the cooldown of, and delete each key. Full key text is never shown.
 */
class KeyManagerActivity : AppCompatActivity() {

    private val providers by lazy { ProviderRuntime.manager(applicationContext) }
    private val keys by lazy { ProviderRuntime.keyManager(applicationContext) }
    private val pool by lazy { ProviderRuntime.keyPool(applicationContext) }

    private lateinit var list: LinearLayout
    private lateinit var summary: android.widget.TextView
    private var profiles: List<ProviderProfile> = emptyList()
    private var selected: ProviderProfile? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ThemeManager.applyDynamicBackground(root)

        val bar = UiKit.row(this).apply { setPadding(UiKit.dp(this@KeyManagerActivity, 8), UiKit.dp(this@KeyManagerActivity, 12), UiKit.dp(this@KeyManagerActivity, 16), UiKit.dp(this@KeyManagerActivity, 8)) }
        bar.addView(UiKit.button(this, "‹ Back") { finish() })
        bar.addView(UiKit.text(this, "  API Key Manager", 20f, bold = true))
        root.addView(bar)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = UiKit.dp(this@KeyManagerActivity, 16); setPadding(p, 0, p, p)
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(body) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        profiles = providers.profiles()
        if (profiles.isEmpty()) {
            body.addView(UiKit.text(this, "Add a custom provider first (Settings → API Key & Model Settings → Add provider). Keys belong to a provider.", 14f, muted = true))
            return
        }

        body.addView(UiKit.text(this, "Provider", 12f, muted = true))
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, profiles.map { it.displayName })
        val activeId = providers.activeProfile()?.id
        spinner.setSelection(profiles.indexOfFirst { it.id == activeId }.coerceAtLeast(0))
        body.addView(spinner)
        selected = profiles[spinner.selectedItemPosition]

        summary = UiKit.text(this, "", 12f, muted = true).apply { setPadding(0, UiKit.dp(this@KeyManagerActivity, 8), 0, UiKit.dp(this@KeyManagerActivity, 8)) }
        body.addView(summary)
        body.addView(UiKit.button(this, "Add API key", outlined = false) { showEditor(null) })
        body.addView(UiKit.gap(this, 12))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(list)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) { selected = profiles[pos]; render() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        if (selected == null) return
        // Cooldown countdowns and "key is back" changes show up without leaving the screen.
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.RESUMED) { while (true) { render(); delay(15_000) } } }
    }

    private fun render() {
        val profile = selected ?: return
        val records = keys.keys(profile.id)
        val now = System.currentTimeMillis()
        val usable = records.count { it.isUsableAt(now) }
        summary.text = if (records.isEmpty()) {
            "No keys yet. Add several and JARVIS will switch to the next one automatically when a key reaches its limit."
        } else {
            "${records.size} key${if (records.size == 1) "" else "s"} · $usable ready now. Lower priority number is used first."
        }
        list.removeAllViews()
        records.sortedWith(compareBy({ it.priority }, { it.createdAt })).forEach { list.addView(keyCard(it, now)) }
    }

    private fun keyCard(rec: KeyRecord, now: Long): LinearLayout {
        val card = UiKit.card(this)
        val head = UiKit.row(this)
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(UiKit.text(this, rec.label, 16f, bold = true))
        titles.addView(UiKit.text(this, keys.mask(rec.id).ifEmpty { "No key text" } + "  ·  priority ${rec.priority}" +
            (if (rec.modelId.isNotBlank()) "  ·  ${rec.modelId}" else ""), 12f, muted = true))
        head.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val toggle = Switch(this).apply {
            isChecked = rec.enabled
            contentDescription = "Use ${rec.label}"
            setOnCheckedChangeListener { _, on -> pool.setEnabled(rec.id, on); render() }
        }
        head.addView(toggle)
        card.addView(head)
        card.addView(UiKit.text(this, keys.statusLine(rec, now), 12f).apply { setPadding(0, UiKit.dp(this@KeyManagerActivity, 6), 0, 0) })
        if (rec.lastFailureNote.isNotBlank()) card.addView(UiKit.text(this, rec.lastFailureNote, 11f, muted = true))
        val actions = UiKit.row(this).apply { gravity = Gravity.START }
        actions.addView(UiKit.button(this, "Test") { testKey(rec) })
        actions.addView(UiKit.button(this, "Edit") { showEditor(rec) })
        actions.addView(UiKit.button(this, "Reset") { pool.resetCooldown(rec.id); render(); toast("Cooldown cleared for ${rec.label}.") })
        actions.addView(UiKit.button(this, "Delete") { confirmDelete(rec) })
        val hs = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(actions) }
        card.addView(hs)
        return card
    }

    private fun testKey(rec: KeyRecord) {
        toast("Testing ${rec.label}…")
        lifecycleScope.launch {
            val report = try { keys.test(rec.id) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
            render()
            MaterialAlertDialogBuilder(this@KeyManagerActivity, R.style.ThemeOverlay_Jarvis_MaterialDialog)
                .setTitle(if (report?.ok == true) "Key works" else "Key test failed")
                .setMessage(report?.summary ?: "This key could not be tested. Check that its provider still exists and the key text is saved.")
                .setPositiveButton("OK", null).show()
        }
    }

    private fun confirmDelete(rec: KeyRecord) {
        MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_Jarvis_MaterialDialog)
            .setTitle("Delete ${rec.label}?")
            .setMessage("The key is removed from this phone. This can't be undone, but you can add it again.")
            .setPositiveButton("Delete") { _, _ -> keys.remove(rec.id); render() }
            .setNegativeButton("Cancel", null).show()
    }

    /** Add (rec == null) or edit. Leaving the key box empty when editing keeps the saved key. */
    private fun showEditor(rec: KeyRecord?) {
        val profile = selected ?: return
        val label = UiKit.input(this, "Name (for example Personal)", rec?.label.orEmpty())
        val key = UiKit.input(this, if (rec == null) "API key" else "New API key (leave empty to keep)", secret = true)
        val priority = UiKit.input(this, "Priority (1 = first)", (rec?.priority ?: 100).toString(), number = true)
        val model = UiKit.input(this, "Model for this key (optional)", rec?.modelId.orEmpty())
        val cooldown = UiKit.input(this, "Rest after a limit, minutes", ((rec?.cooldownMs ?: KeyRecord.DEFAULT_QUOTA_COOLDOWN_MS) / 60_000L).toString(), number = true)
        val schedTypes = arrayOf("No reset schedule", "Resets daily at a time", "Resets every N minutes")
        val sched = Spinner(this).apply {
            adapter = ArrayAdapter(this@KeyManagerActivity, android.R.layout.simple_spinner_dropdown_item, schedTypes)
            setSelection(when (rec?.resetSchedule?.type) { ResetSchedule.Type.DAILY -> 1; ResetSchedule.Type.INTERVAL -> 2; else -> 0 })
        }
        val s = rec?.resetSchedule ?: ResetSchedule.NONE
        val at = UiKit.input(this, "Daily time HH:MM (24 h)", if (s.type == ResetSchedule.Type.DAILY) "%02d:%02d".format(s.hour, s.minute) else "")
        val offset = UiKit.input(this, "Time zone offset, minutes from UTC (India = 330)", (if (s.type == ResetSchedule.Type.DAILY) s.utcOffsetMinutes else java.util.TimeZone.getDefault().rawOffset / 60_000).toString())
        val every = UiKit.input(this, "Reset interval, minutes", if (s.type == ResetSchedule.Type.INTERVAL) s.intervalMinutes.toString() else "", number = true)
        val form = UiKit.form(this, label, key, priority, model, cooldown, UiKit.text(this, "Quota reset (when the provider doesn't say)", 12f, muted = true), sched, at, offset, every)
        MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_Jarvis_MaterialDialog)
            .setTitle(if (rec == null) "Add API key" else "Edit ${rec.label}")
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create().also { dlg ->
                dlg.setOnShowListener {
                    dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val schedule = when (sched.selectedItemPosition) {
                            1 -> {
                                val m = Regex("^(\\d{1,2}):(\\d{2})$").matchEntire(at.text.toString().trim())
                                val h = m?.groupValues?.get(1)?.toInt(); val mi = m?.groupValues?.get(2)?.toInt()
                                if (h == null || mi == null || h > 23 || mi > 59) { toast("Enter the daily reset time as HH:MM."); return@setOnClickListener }
                                ResetSchedule.daily(h, mi, offset.text.toString().trim().toIntOrNull() ?: 0)
                            }
                            2 -> {
                                val n = every.text.toString().trim().toIntOrNull()
                                if (n == null || n <= 0) { toast("Enter the reset interval in minutes."); return@setOnClickListener }
                                ResetSchedule.every(n, System.currentTimeMillis())
                            }
                            else -> ResetSchedule.NONE
                        }
                        val prio = priority.text.toString().trim().toIntOrNull() ?: 100
                        val rest = (cooldown.text.toString().trim().toLongOrNull() ?: 60L).coerceIn(1L, 7L * 24 * 60) * 60_000L
                        val typed = key.text.toString()
                        val problem = if (rec == null) {
                            keys.add(profile.id, label.text.toString(), typed, prio, model.text.toString(), rest, schedule)
                        } else {
                            if (typed.isNotBlank()) keys.replaceKey(rec.id, typed) else null
                        }
                        if (problem != null) { toast(problem); return@setOnClickListener }
                        if (rec != null) {
                            pool.get(rec.id)?.let {
                                pool.upsert(it.copy(label = label.text.toString().trim().ifEmpty { it.label }, priority = prio,
                                    modelId = model.text.toString().trim(), cooldownMs = rest, resetSchedule = schedule))
                            }
                        }
                        dlg.dismiss(); render()
                    }
                }
            }.show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
