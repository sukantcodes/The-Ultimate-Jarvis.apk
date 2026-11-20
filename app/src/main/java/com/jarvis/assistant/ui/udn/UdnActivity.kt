package com.jarvis.assistant.ui.udn

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jarvis.assistant.auth.AuthManager
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.background.WakeKeepAlive
import com.jarvis.assistant.udn.*
import com.jarvis.assistant.util.PermissionHelper
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Device Network dashboard. Everything shown comes from the live registry or from this device's real state;
 * nothing here is sample data. Parent-only controls are hidden for children, and the server enforces the same rules.
 */
class UdnActivity : AppCompatActivity() {

    private lateinit var mgr: UdnManager
    private lateinit var settings: UdnSettings
    private lateinit var content: LinearLayout
    private lateinit var dynamic: LinearLayout
    private lateinit var commandOutput: TextView
    private var renderJob: Job? = null
    private var messageCache: List<UdnMessage>? = null
    private var lastSignature = 0

    private var accent = 0
    private var textPrimary = Color.WHITE
    private var textSecondary = Color.LTGRAY
    private var cardColor = 0
    private var surface = 0

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        mgr = UdnManager.get(applicationContext)
        settings = UdnSettings.get(applicationContext)
        resolveColors()
        buildShell()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { mgr.state.collectLatest { render() } }
                launch {
                    // Keeps the dashboard honest: the registry refreshes while it's visible, and "last seen" ages in place.
                    while (true) {
                        if (mgr.state.value.enrolled && mgr.state.value.authorized) {
                            mgr.refreshRegistry()
                            (mgr.messages() as? UdnResult.Ok)?.let { if (it.value != messageCache) { messageCache = it.value; render() } }
                        }
                        delay(15_000)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            mgr.ensureJoined()
            if (mgr.state.value.enrolled) { mgr.refreshSelf(); if (mgr.state.value.authorized) mgr.refreshRegistry() }
            render()
        }
    }

    // ------------------------------------------------------------------ theme + building blocks

    /**
     * A fixed look, independent of the app theme and with no animation: a deep static backdrop with clear, frosted
     * glass cards. Nothing here redraws on its own, so the glass can't flicker in and out.
     */
    private fun resolveColors() {
        accent = try { ThemeManager.getThemeAccentColor() } catch (_: Throwable) { Color.parseColor("#6EA8FF") }
        // keep the accent readable on dark glass
        val hsl = FloatArray(3); androidx.core.graphics.ColorUtils.colorToHSL(accent, hsl)
        hsl[2] = hsl[2].coerceIn(0.58f, 0.8f); hsl[1] = hsl[1].coerceAtLeast(0.55f)
        accent = androidx.core.graphics.ColorUtils.HSLToColor(hsl)
        surface = Color.parseColor("#080C18")
        textPrimary = Color.parseColor("#F5F8FF")
        textSecondary = Color.parseColor("#A9B4CC")
        cardColor = 0
    }

    private fun glass(radiusDp: Int = 22): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#2EFFFFFF"), Color.parseColor("#0FFFFFFF"))
    ).apply {
        cornerRadius = dp(radiusDp).toFloat()
        setStroke(dp(1), Color.parseColor("#4DFFFFFF"))
    }

    private fun backdrop(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#0B1226"), Color.parseColor("#070A14"), Color.parseColor("#0C1530"))
    )

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(s: CharSequence, sizeSp: Float = 14f, color: Int = textPrimary, bold: Boolean = false) = TextView(this).apply {
        text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp); setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun card(vararg children: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = glass()
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
        children.forEach { addView(it) }
    }

    private fun withAlpha(color: Int, a: Int) = Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))

    private fun button(label: String, filled: Boolean = true, onClick: () -> Unit) = TextView(this).apply {
        // glass pill: tinted clear glass for the primary action, plain clear glass for the rest
        text = label; gravity = Gravity.CENTER; isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (filled) (if (ThemeManager.isLight(accent)) Color.BLACK else Color.WHITE) else textPrimary)
        setPadding(dp(14), dp(9), dp(14), dp(9))
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            if (filled) { setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 0xCC)); setStroke(dp(1), Color.parseColor("#66FFFFFF")) }
            else { setColor(Color.parseColor("#1AFFFFFF")); setStroke(dp(1), Color.parseColor("#55FFFFFF")) }
        }
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8); marginEnd = dp(8) }
    }

    private fun row(vararg children: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        children.forEach { addView(it) }
    }

    private fun flow(vararg children: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        children.forEach { addView(it) }
    }

    private fun section(title: String) = text(title.uppercase(), 11f, accent, true).apply {
        letterSpacing = 0.12f; setPadding(dp(4), dp(10), 0, dp(6))
    }

    private fun toggle(label: String, sub: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit): View {
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(label, 14f, bold = true))
            if (sub != null) addView(text(sub, 12f, textSecondary))
        }
        val sw = SwitchCompat(this).apply { isChecked = checked; isEnabled = enabled; setOnCheckedChangeListener { _, v -> onChange(v) } }
        return row(labels.apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }, sw)
            .apply { setPadding(0, dp(6), 0, dp(6)) }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun fmtTime(ms: Long?): String = if (ms == null) "never" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))

    private fun <T> run(op: suspend () -> UdnResult<T>, ok: (T) -> Unit = {}) {
        lifecycleScope.launch {
            when (val r = op()) {
                is UdnResult.Ok -> { ok(r.value); render() }
                is UdnResult.Err -> toast(r.message)
            }
        }
    }

    // ------------------------------------------------------------------ shell (built once)

    private fun buildShell() {
        val scroll = ScrollView(this).apply { background = backdrop(); isFillViewport = true }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(20), dp(16), dp(40)) }
        scroll.addView(content)
        setContentView(scroll)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }

        content.addView(row(
            text("‹", 26f, accent, true).apply { setPadding(0, 0, dp(14), 0); setOnClickListener { finish() } },
            text("Device Network", 22f, bold = true)
        ))
        content.addView(text("Your JARVIS devices, one secure network.", 13f, textSecondary).apply { setPadding(0, dp(2), 0, dp(14)) })

        dynamic = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(dynamic)

        commandOutput = text("", 13f, textSecondary)
    }

    // ------------------------------------------------------------------ rendering

    private fun renderSignature(s: UdnState): Int = listOf(
        s.configured, AuthManager.isSignedIn, s.enrolled, s.status, s.isParent, s.slot, s.linkRunning, s.realtimeConnected, s.lastError,
        s.devices.map { listOf(it.id, it.name, it.status, it.online, it.batteryPct, it.charging, it.connection, it.parentSlot, (it.lastSeenMs ?: 0L) / 60_000, it.capabilities.map { c -> "${c.capability}${c.available}${c.permissionGranted}${c.policy}" }) },
        s.groups, messageCache, settings.remoteControlEnabled, settings.locationSharingEnabled, settings.linkEnabled,
        PermissionHelper.hasRecordAudioPermission(this), WakeKeepAlive.isEnabled(this),
        JarvisConversationService.isSessionOn.value || JarvisConversationService.isStandby.value,
        PermissionHelper.hasBatteryOptimizationPermission(this)
    ).hashCode()

    private fun render() {
        if (!::dynamic.isInitialized || isFinishing || isDestroyed) return
        val s = mgr.state.value
        val sig = renderSignature(s)
        if (sig == lastSignature && dynamic.childCount > 0) return // nothing changed: leave the screen exactly as it is
        lastSignature = sig
        dynamic.removeAllViews()

        if (!s.configured) {
            dynamic.addView(card(text("Cloud not configured", 16f, bold = true), text("This build has no Supabase configuration, so the Device Network is unavailable.", 13f, textSecondary)))
            return
        }
        if (!AuthManager.isSignedIn) {
            dynamic.addView(card(text("Sign in required", 16f, bold = true),
                text("The Device Network is tied to your JARVIS account. Sign in from the menu (Account), then come back here.", 13f, textSecondary)))
            return
        }
        if (!s.enrolled) { renderEnroll(); renderLocalSettings(); return }

        renderOverview(s)
        if (!s.authorized) { renderWaiting(s); renderLocalSettings(); return }

        if (s.isParent) renderPending(s)
        renderDevices(s)
        if (s.isParent) { renderCommand(s); renderMessages(s); renderGroups(s) } else renderMessages(s)
        renderLocalSettings()
        renderBackgroundVoice()
        if (s.isParent) renderAudit()
    }

    private fun renderEnroll() {
        dynamic.addView(card(
            text("Join your JARVIS Device Network?", 17f, bold = true),
            text("This registers this phone with your account so your other JARVIS devices can see it. Nothing is controllable until a parent device approves it, and you can leave the network at any time.", 13f, textSecondary).apply { setPadding(0, dp(6), 0, dp(4)) },
            flow(button("Join network") {
                settings.enrollmentPrompted = true
                run({ mgr.enroll() })
            })
        ))
    }

    private fun renderOverview(s: UdnState) {
        val devices = s.devices.filter { it.status != "revoked" }
        val online = devices.count { it.online && it.isAuthorized }
        val role = when {
            s.isParent -> "Parent ${s.slot ?: ""}".trim()
            s.authorized -> "Child"
            s.status == "rejected" -> "Rejected"
            else -> "Waiting for approval"
        }
        dynamic.addView(card(
            row(text("●", 14f, if (s.authorized && s.linkRunning) Color.parseColor("#00E676") else if (s.authorized) Color.parseColor("#FFD700") else textSecondary),
                text("  This device: $role", 16f, bold = true)),
            text(
                when {
                    !s.authorized -> "Not part of the controllable network yet."
                    s.linkRunning && s.realtimeConnected -> "Linked · live updates connected"
                    s.linkRunning -> "Linked · using polling (live updates reconnecting)"
                    settings.linkEnabled -> "Link starting…"
                    else -> "Network link is turned off on this device"
                }, 12f, textSecondary
            ),
            if (s.authorized) text("$online of ${devices.count { it.isAuthorized }} authorized devices online", 12f, textSecondary) else text("", 1f),
            s.lastError?.let { text(it, 12f, Color.parseColor("#FF5252")).apply { setPadding(0, dp(4), 0, 0) } } ?: text("", 1f)
        ))
    }

    private fun renderWaiting(s: UdnState) {
        dynamic.addView(card(
            text(if (s.status == "rejected" || s.status == "revoked") "This device was removed from the network." else "Joining the network…", 15f, bold = true),
            text("Devices join automatically once you're signed in. If this doesn't change in a moment, check your connection and tap Retry.", 12f, textSecondary).apply { setPadding(0, dp(4), 0, 0) },
            flow(button("Retry") { lifecycleScope.launch { mgr.ensureJoined(); mgr.refreshSelf(); render() } })
        ))
    }

    private fun renderPending(s: UdnState) {
        val pending = s.devices.filter { it.status == "pending" }
        if (pending.isEmpty()) return
        dynamic.addView(section("Waiting for your approval"))
        pending.forEach { d ->
            dynamic.addView(card(
                text(d.name, 15f, bold = true),
                text(listOfNotNull(d.manufacturer, d.model, d.osVersion, d.platform).joinToString(" · "), 12f, textSecondary),
                text("Key fingerprint: ${d.fingerprint?.take(16) ?: "unknown"}…", 11f, textSecondary),
                flow(
                    button("Approve") { run({ mgr.decideDevice(d.id, true) }) },
                    button("Reject", false) { run({ mgr.decideDevice(d.id, false) }) }
                )
            ))
        }
    }

    private fun renderDevices(s: UdnState) {
        val devices = s.devices.filter { it.status == "authorized" }.sortedWith(compareByDescending<UdnDevice> { it.isParent }.thenBy { it.name.lowercase() })
        dynamic.addView(section("Devices"))
        if (devices.isEmpty()) { dynamic.addView(card(text("No authorized devices yet.", 13f, textSecondary))); return }
        devices.forEach { d ->
            val isSelf = d.id == s.deviceId
            val dot = if (d.online) Color.parseColor("#00E676") else textSecondary
            val role = if (d.isParent) "Parent ${d.parentSlot}" else "Child"
            val bat = d.batteryPct?.let { "$it%${if (d.charging == true) " ⚡" else ""}" } ?: "battery unknown"
            val c = card(
                row(text("●  ", 14f, dot), text(d.name + if (isSelf) "  (this device)" else "", 15f, bold = true)),
                text("$role · ${d.platform} · ${if (d.online) "online" else "offline"}", 12f, textSecondary),
                text("$bat · ${connLabel(d.connection)} · last seen ${fmtTime(d.lastSeenMs)}", 12f, textSecondary)
            )
            c.setOnClickListener { showDevice(d, isSelf) }
            dynamic.addView(c)
        }
    }

    private fun connLabel(c: String?) = when (c) { "wifi" -> "Wi-Fi"; "cellular" -> "mobile data"; "ethernet" -> "Ethernet"; null -> "network unknown"; else -> c }

    private fun renderCommand(s: UdnState) {
        val input = EditText(this).apply {
            hint = "e.g. “open Chatbox on my tablet” or “battery of my laptop”"
            setTextColor(textPrimary); setHintTextColor(textSecondary); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            textSize = 14f
        }
        commandOutput.text = ""
        val send = button("Send") {
            val t = input.text.toString().trim()
            if (t.isEmpty()) return@button
            commandOutput.text = "Working…"
            lifecycleScope.launch {
                val reply = try { UdnVoiceRouter.handle(applicationContext, t) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { "I couldn't complete that device command." }
                commandOutput.text = reply
                input.text?.clear()
            }
        }
        dynamic.addView(section("Command"))
        dynamic.addView(card(
            input, flow(send), commandOutput.apply { (parent as? ViewGroup)?.removeView(this) },
            text("Works in English, Hindi and Hinglish. You can also say this to JARVIS by voice. Results come from the target device, not from this app.", 11f, textSecondary).apply { setPadding(0, dp(6), 0, 0) }
        ))
    }

    private fun renderGroups(s: UdnState) {
        dynamic.addView(section("Groups"))
        val views = mutableListOf<View>()
        if (s.groups.isEmpty()) views += text("No groups yet.", 13f, textSecondary)
        s.groups.forEach { g ->
            val names = g.deviceIds.mapNotNull { id -> s.devices.firstOrNull { it.id == id }?.name }
            views += text(g.name, 14f, bold = true)
            views += text(if (names.isEmpty()) "No devices" else names.joinToString(", "), 12f, textSecondary)
            views += flow(
                button("Edit members", false) { editGroup(g, s) },
                button("Delete", false) { run({ mgr.deleteGroup(g.id) }) }
            )
        }
        views += flow(button("New group", false) {
            val et = EditText(this).apply { hint = "Group name"; setSingleLine() }
            AlertDialog.Builder(this).setTitle("New group").setView(et)
                .setPositiveButton("Create") { _, _ -> et.text.toString().trim().takeIf { it.isNotEmpty() }?.let { n -> run({ mgr.createGroup(n) }) } }
                .setNegativeButton("Cancel", null).show()
        })
        dynamic.addView(card(*views.toTypedArray()))
    }

    private fun editGroup(g: UdnGroup, s: UdnState) {
        val devs = s.devices.filter { it.isAuthorized }
        val checked = BooleanArray(devs.size) { devs[it].id in g.deviceIds }
        AlertDialog.Builder(this).setTitle(g.name)
            .setMultiChoiceItems(devs.map { it.name }.toTypedArray(), checked) { _, i, v -> checked[i] = v }
            .setPositiveButton("Save") { _, _ ->
                lifecycleScope.launch {
                    devs.forEachIndexed { i, d ->
                        val was = d.id in g.deviceIds
                        if (checked[i] != was) mgr.setGroupMember(g.id, d.id, remove = !checked[i])
                    }
                    mgr.refreshRegistry(); render()
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun renderMessages(s: UdnState) {
        dynamic.addView(section(if (s.isParent) "Messages" else "Messages for this device"))
        val msgs = messageCache
        val byId = s.devices.associateBy { it.id }
        val views = mutableListOf<View>()
        if (s.isParent) {
            views += text("Send a message to any device in the network. It appears as a notification there and is saved here.", 12f, textSecondary)
            views += flow(button("New message") { composeMessage(s) })
        }
        if (msgs == null) views += text("Loading…", 12f, textSecondary)
        else if (msgs.isEmpty()) views += text("No messages yet.", 12f, textSecondary).apply { setPadding(0, dp(6), 0, 0) }
        else msgs.take(25).forEach { m ->
            val from = byId[m.fromDeviceId]?.name ?: "A parent device"
            val to = byId[m.toDeviceId]?.name ?: "device"
            val status = when (m.delivery) {
                "succeeded" -> "delivered"; "failed", "rejected", "cancelled" -> "not shown"; "expired", "timed_out" -> "not delivered"
                null -> "saved"; else -> "waiting for device"
            }
            views += text(if (s.isParent) "$from → $to" else "From $from", 12f, accent, true).apply { setPadding(0, dp(10), 0, 0) }
            views += text(m.body, 14f)
            views += text("${fmtTime(m.createdMs)} · $status", 11f, textSecondary)
        }
        dynamic.addView(card(*views.toTypedArray()))
    }

    private fun composeMessage(s: UdnState) {
        val targets = s.devices.filter { it.isAuthorized && it.id != s.deviceId }
        if (targets.isEmpty()) { toast("No other devices have joined yet."); return }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val input = EditText(this).apply { hint = "Message"; minLines = 2; maxLines = 5; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES }
        box.addView(input)
        val labels = (listOf("All devices") + targets.map { it.name + if (!it.online) " (offline)" else "" }).toTypedArray()
        val checked = BooleanArray(labels.size) { it == 0 }
        val dlg = AlertDialog.Builder(this).setTitle("New message").setView(box)
            .setMultiChoiceItems(labels, checked) { d, i, v ->
                checked[i] = v
                val lv = (d as AlertDialog).listView
                if (i == 0) for (k in 1 until labels.size) { checked[k] = v; lv.setItemChecked(k, v) }
                else if (!v) { checked[0] = false; lv.setItemChecked(0, false) }
            }
            .setPositiveButton("Send", null).setNegativeButton("Cancel", null).create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = input.text.toString().trim()
                val ids = targets.filterIndexed { i, _ -> checked[i + 1] }.map { it.id }
                if (text.isEmpty()) { input.error = "Write a message"; return@setOnClickListener }
                if (ids.isEmpty()) { toast("Choose at least one device."); return@setOnClickListener }
                dlg.dismiss()
                lifecycleScope.launch {
                    when (val r = mgr.sendMessage(ids, text)) {
                        is UdnResult.Ok -> { toast("Message saved and sent."); (mgr.messages() as? UdnResult.Ok)?.let { messageCache = it.value }; render() }
                        is UdnResult.Err -> toast(r.message)
                    }
                }
            }
        }
        dlg.show()
    }

    private fun renderLocalSettings() {
        val s = mgr.state.value
        dynamic.addView(section("This device"))
        val views = mutableListOf<View>()
        if (s.enrolled && s.authorized) {
            views += toggle("Allow remote control", "When off, this device refuses every command from other devices.", settings.remoteControlEnabled) {
                settings.remoteControlEnabled = it; lifecycleScope.launch { mgr.pushCapabilities(force = true) }
            }
            views += toggle("Allow location sharing", "Off by default. Even when on, a parent's request still needs your Allow tap here.", settings.locationSharingEnabled) {
                settings.locationSharingEnabled = it; lifecycleScope.launch { mgr.pushCapabilities(force = true) }
            }
            views += toggle("Keep network link running", "Foreground service so this device can receive commands while the app is closed.", settings.linkEnabled) {
                settings.linkEnabled = it
                if (it) UdnLinkService.start(applicationContext) else UdnLinkService.stop(applicationContext)
                render()
            }
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                views += text("Notifications are off: consent prompts for sensitive commands can't be shown.", 12f, Color.parseColor("#FFD700"))
                views += flow(button("Allow notifications", false) { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) })
            }
        }
        if (s.enrolled) {
            views += flow(button("Leave network", false) {
                confirm("Leave the network?", "This device will be removed and its key deleted. It can rejoin later with approval.") {
                    val id = s.deviceId ?: return@confirm
                    run({ mgr.revokeDevice(id) })
                }
            })
        }
        if (views.isEmpty()) return
        dynamic.addView(card(*views.toTypedArray()))
    }

    private fun renderBackgroundVoice() {
        dynamic.addView(section("Voice"))
        val micOk = PermissionHelper.hasRecordAudioPermission(this)
        val wakeOn = WakeKeepAlive.isEnabled(this)
        val listening = JarvisConversationService.isSessionOn.value || JarvisConversationService.isStandby.value
        val ignoringOpt = PermissionHelper.hasBatteryOptimizationPermission(this)
        val views = mutableListOf<View>()
        views += text(
            when {
                !micOk -> "Microphone permission is off, so voice control is unavailable."
                listening -> "“Hey JARVIS” standby is active. Network commands work by voice, with the screen on or in the background."
                wakeOn -> "Standby is enabled but not running right now."
                else -> "Standby is off. Voice network commands work while a JARVIS voice session is active; typed commands always work."
            }, 13f)
        views += text("Android shows a persistent notification while the microphone is in use in the background, and some manufacturers stop background apps unless battery optimization is disabled. JARVIS cannot listen while Android blocks microphone access (for example during a phone call).", 11f, textSecondary)
        views += text("Battery optimization: " + if (ignoringOpt) "exempt (recommended)" else "not exempt — background link may be stopped by the system", 12f, if (ignoringOpt) textSecondary else Color.parseColor("#FFD700"))
        val actions = mutableListOf<View>()
        if (!ignoringOpt) actions += button("Allow background", false) {
            runCatching { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
                .onFailure { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
        actions += button(if (wakeOn) "Turn off standby" else "Turn on standby", false) {
            WakeKeepAlive.setEnabled(this, !wakeOn); render()
        }
        views += flow(*actions.toTypedArray())
        dynamic.addView(card(*views.toTypedArray()))
    }

    private fun renderAudit() {
        dynamic.addView(section("Security events"))
        val holder = card(text("Loading…", 12f, textSecondary))
        dynamic.addView(holder)
        lifecycleScope.launch {
            when (val r = mgr.auditEvents()) {
                is UdnResult.Ok -> {
                    holder.removeAllViews()
                    if (r.value.isEmpty()) holder.addView(text("No events yet.", 12f, textSecondary))
                    r.value.take(15).forEach { e ->
                        holder.addView(text(e.action.replace('_', ' '), 13f, bold = true))
                        holder.addView(text(fmtTime(e.createdMs), 11f, textSecondary).apply { setPadding(0, 0, 0, dp(6)) })
                    }
                }
                is UdnResult.Err -> { holder.removeAllViews(); holder.addView(text(r.message, 12f, textSecondary)) }
            }
        }
    }

    // ------------------------------------------------------------------ device details

    private fun showDevice(d: UdnDevice, isSelf: Boolean) {
        val s = mgr.state.value
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        body.addView(text(listOfNotNull(d.manufacturer, d.model, d.osVersion).joinToString(" · ").ifBlank { d.platform }, 12f, textSecondary))
        body.addView(text("App ${d.appVersion ?: "?"} · fingerprint ${d.fingerprint?.take(16) ?: "?"}…", 11f, textSecondary))
        body.addView(text("Capabilities", 13f, accent, true).apply { setPadding(0, dp(10), 0, dp(2)) })
        if (d.capabilities.isEmpty()) body.addView(text("Not reported yet.", 12f, textSecondary))
        d.capabilities.forEach { c ->
            val state = when {
                !c.available -> "unavailable — ${c.reason ?: "not supported"}"
                c.permissionGranted == false -> "needs permission${c.osPermission?.let { " ($it)" } ?: ""}"
                c.policy == "deny" -> "blocked by policy"
                c.policy == "confirm" -> "available, asks first"
                else -> "available"
            }
            body.addView(text("${UdnCapabilities.label(c.capability)}: $state", 12f, if (c.available && c.permissionGranted != false && c.policy != "deny") textPrimary else textSecondary))
        }
        val history = text("Loading history…", 12f, textSecondary)
        body.addView(text("Recent commands", 13f, accent, true).apply { setPadding(0, dp(10), 0, dp(2)) })
        body.addView(history)

        val builder = AlertDialog.Builder(this).setTitle(d.name).setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("Close", null)
        if (s.isParent) {
            builder.setNeutralButton("Rename") { _, _ -> rename(d) }
            if (!d.isParent || isSelf) builder.setNegativeButton(if (isSelf) "Leave network" else "Remove") { _, _ ->
                confirm(if (isSelf) "Leave the network?" else "Remove ${d.name}?", "It will lose access immediately and must re-enroll to return.") { run({ mgr.revokeDevice(d.id) }) }
            }
        }
        builder.show()
        if (s.isParent) lifecycleScope.launch {
            history.text = when (val r = mgr.commandHistory(d.id)) {
                is UdnResult.Ok -> if (r.value.isEmpty()) "No commands yet." else r.value.take(8).joinToString("\n") { "${UdnCapabilities.label(it.capability)} — ${it.status} (${fmtTime(it.createdMs)})" }
                is UdnResult.Err -> r.message
            }
        } else history.text = "Only parent devices can see command history."
    }

    private fun rename(d: UdnDevice) {
        val et = EditText(this).apply { setText(d.name); setSingleLine() }
        AlertDialog.Builder(this).setTitle("Rename device").setView(et)
            .setPositiveButton("Save") { _, _ -> et.text.toString().trim().takeIf { it.isNotEmpty() }?.let { n -> run({ mgr.renameDevice(d.id, n) }) } }
            .setNegativeButton("Cancel", null).show()
    }

    private fun confirm(title: String, message: String, onYes: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setPositiveButton("Confirm") { _, _ -> onYes() }.setNegativeButton("Cancel", null).show()
    }
}
