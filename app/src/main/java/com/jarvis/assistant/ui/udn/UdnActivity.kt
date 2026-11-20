package com.jarvis.assistant.ui.udn

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.jarvis.assistant.R
import com.jarvis.assistant.auth.AuthManager
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.background.WakeKeepAlive
import com.jarvis.assistant.udn.*
import com.jarvis.assistant.util.PermissionHelper
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/**
 * Device Network dashboard. Everything on screen comes from the live registry or from this device's real state, and the
 * look follows the active app theme exactly (see [UdnTheme]). The screen only rebuilds when the content truly changes;
 * live status (link state, connection) updates in place, so nothing flashes.
 */
class UdnActivity : AppCompatActivity() {

    private lateinit var mgr: UdnManager
    private lateinit var settings: UdnSettings
    private lateinit var th: UdnTheme

    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private lateinit var bottomStack: LinearLayout
    private lateinit var resultCard: LinearLayout
    private lateinit var resultText: TextView
    private lateinit var composer: LinearLayout
    private lateinit var subtitle: TextView

    private var messageCache: List<UdnMessage>? = null
    private var auditCache: List<UdnAuditEvent>? = null
    private var lastSignature = 0
    private var liveHolder: FrameLayout? = null
    private var barsBottom = 0
    private var imeBottom = 0

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { forceRender() }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        mgr = UdnManager.get(applicationContext)
        settings = UdnSettings.get(applicationContext)
        th = UdnTheme(this)
        // One static, themed canvas for this screen (the shared window canvas can be animated in some themes).
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        if (Build.VERSION.SDK_INT >= 31) try { window.setBackgroundBlurRadius(0) } catch (_: Throwable) {}
        window.setBackgroundDrawable(StaticBackdrop.forCurrentTheme(th))
        buildShell()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { mgr.state.collectLatest { updateLive(); render() } }
                launch {
                    var tick = 0
                    while (true) {
                        val s = mgr.state.value
                        if (s.enrolled && s.authorized) {
                            mgr.refreshRegistry()
                            (mgr.messages() as? UdnResult.Ok)?.let { if (it.value != messageCache) { messageCache = it.value; render() } }
                            if (s.isParent && tick % 4 == 0) (mgr.auditEvents() as? UdnResult.Ok)?.let { if (it.value != auditCache) { auditCache = it.value; render() } }
                        }
                        tick++
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

    // ------------------------------------------------------------------ shell (built once)

    private fun buildShell() {
        val root = FrameLayout(this)
        scroll = ScrollView(this).apply { isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER; isVerticalScrollBarEnabled = false }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(th.dp(18), th.dp(10), th.dp(18), th.dp(40)) }
        scroll.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // header: back + title + live subtitle (persistent, updated in place)
        val back = FrameLayout(this).apply {
            background = th.glass(20); isClickable = true; isFocusable = true
            setOnClickListener { finish() }
            addView(th.icon(R.drawable.udn_ic_back, 20, th.text1), FrameLayout.LayoutParams(th.dp(20), th.dp(20), Gravity.CENTER))
        }
        subtitle = th.text("", 12f, th.text2)
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(th.text("Device Network", 26f, th.text1, UdnTheme.Weight.Bold))
            addView(subtitle, th.lp(top = 4))
        }
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(back, LinearLayout.LayoutParams(th.dp(40), th.dp(40)).apply { marginEnd = th.dp(14) })
            addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }, th.lp(bottom = 18))

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; id = View.generateViewId() }
        bodyHolder = body
        content.addView(body, th.lp())

        // bottom stack: result card + command composer (parents only; shown from render())
        resultText = th.text("", 13f, th.text1).apply { setLineSpacing(0f, 1.15f) }
        resultCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = th.glass(20, th.accent); setPadding(th.dp(16), th.dp(12), th.dp(16), th.dp(12))
            visibility = View.GONE; isClickable = true
            setOnClickListener { visibility = View.GONE }
            addView(resultText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val input = EditText(this).apply {
            hint = "Ask JARVIS to control a device…"
            setHintTextColor(th.muted); setTextColor(th.text1); textSize = 14f; background = null
            setSingleLine(); imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(th.dp(4), th.dp(10), th.dp(4), th.dp(10))
        }
        val send = FrameLayout(this).apply {
            background = th.pill(th.accent); isClickable = true; isFocusable = true
            addView(th.icon(R.drawable.udn_ic_send, 20, th.onAccent), FrameLayout.LayoutParams(th.dp(20), th.dp(20), Gravity.CENTER))
        }
        val submit = {
            val t = input.text.toString().trim()
            if (t.isNotEmpty()) {
                input.text?.clear()
                showResult("Working…")
                lifecycleScope.launch {
                    val reply = try { UdnVoiceRouter.handle(applicationContext, t) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { "I couldn't complete that device command." }
                    showResult(reply)
                }
            }
        }
        send.setOnClickListener { submit() }
        input.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { submit(); true } else false }
        composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = th.glass(30).also { }; setPadding(th.dp(18), th.dp(6), th.dp(8), th.dp(6)); visibility = View.GONE
            addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(send, LinearLayout.LayoutParams(th.dp(42), th.dp(42)).apply { marginStart = th.dp(8) })
        }
        // solid-ish underlay so list content never shows through the composer text
        bottomStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(th.dp(14), 0, th.dp(14), 0)
            addView(resultCard, th.lp(bottom = 8)); addView(composer, th.lp())
        }
        root.addView(bottomStack, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            barsBottom = bars.bottom; imeBottom = ime.bottom
            scroll.setPadding(bars.left, bars.top, bars.right, 0)
            bottomStack.setPadding(th.dp(14) + bars.left, 0, th.dp(14) + bars.right, maxOf(bars.bottom, ime.bottom) + th.dp(10))
            applyContentBottomPadding()
            insets
        }
    }

    private lateinit var bodyHolder: LinearLayout

    private fun applyContentBottomPadding() {
        val extra = if (composer.visibility == View.VISIBLE) th.dp(96) else th.dp(28)
        content.setPadding(th.dp(18), th.dp(10), th.dp(18), extra + barsBottom)
    }

    private fun showResult(s: String) {
        resultText.text = s
        resultCard.visibility = View.VISIBLE
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ------------------------------------------------------------------ in-place live status

    private fun updateLive() {
        val s = mgr.state.value
        subtitle.text = when {
            !s.configured -> "Cloud sync isn't configured in this build"
            !AuthManager.isSignedIn -> "Sign in to use your device network"
            !s.enrolled -> "This device hasn't joined yet"
            !s.authorized -> "Joining the network…"
            else -> {
                val devs = s.devices.filter { it.isAuthorized }
                if (s.isParent) "${devs.count { it.online }} of ${devs.size} devices online" else "Connected to your JARVIS network"
            }
        }
        liveHolder?.let { h ->
            h.removeAllViews()
            val (label, color) = when {
                !s.authorized -> "Not linked" to th.muted
                s.linkRunning && s.realtimeConnected -> "Live" to th.success
                s.linkRunning -> "Linked" to th.success
                settings.linkEnabled -> "Connecting" to th.warning
                else -> "Link off" to th.muted
            }
            h.addView(th.chip(label, color, dot = true))
        }
    }

    // ------------------------------------------------------------------ rendering

    private fun bucket(ms: Long?): String {
        if (ms == null) return "never"
        val m = (System.currentTimeMillis() - ms) / 60_000
        return when { m < 2 -> "now"; m < 60 -> "m${m / 5}"; m < 1440 -> "h${m / 60}"; else -> "d${m / 1440}" }
    }

    private fun signature(s: UdnState): Int = listOf(
        s.configured, AuthManager.isSignedIn, s.enrolled, s.status, s.isParent, s.slot,
        s.devices.map { listOf(it.id, it.name, it.status, it.online, it.batteryPct, it.charging, it.connection, it.parentSlot, bucket(it.lastSeenMs), it.capabilities.map { c -> "${c.capability}${c.available}${c.permissionGranted}${c.policy}" }) },
        s.groups, messageCache, auditCache?.map { it.id },
        settings.remoteControlEnabled, settings.locationSharingEnabled, settings.linkEnabled,
        PermissionHelper.hasRecordAudioPermission(this), WakeKeepAlive.isEnabled(this),
        JarvisConversationService.isSessionOn.value || JarvisConversationService.isStandby.value,
        PermissionHelper.hasBatteryOptimizationPermission(this)
    ).hashCode()

    private fun forceRender() { lastSignature = 0; render() }

    private fun render() {
        if (!::bodyHolder.isInitialized || isFinishing || isDestroyed) return
        val s = mgr.state.value
        val sig = signature(s)
        if (sig == lastSignature && bodyHolder.childCount > 0) return
        lastSignature = sig
        val y = scroll.scrollY
        bodyHolder.removeAllViews()
        liveHolder = null

        composer.visibility = if (s.enrolled && s.authorized && s.isParent) View.VISIBLE else View.GONE
        if (composer.visibility != View.VISIBLE) resultCard.visibility = View.GONE
        applyContentBottomPadding()

        when {
            !s.configured -> bodyHolder.addView(message("Cloud sync isn't configured", "This build has no Supabase configuration, so the Device Network is unavailable."))
            !AuthManager.isSignedIn -> bodyHolder.addView(message("Sign in required", "The Device Network is tied to your JARVIS account. Sign in from the menu, then come back here."))
            !s.enrolled -> { renderJoin(); renderLocalSettings(s) }
            !s.authorized -> { renderHero(s); renderWaiting(s); renderLocalSettings(s) }
            else -> {
                renderHero(s)
                if (s.isParent) renderPending(s)
                renderDevices(s)
                renderMessages(s)
                if (s.isParent) renderGroups(s)
                renderLocalSettings(s)
                renderVoice()
                if (s.isParent) renderAudit()
            }
        }
        updateLive()
        scroll.post { scroll.scrollTo(0, y) }
    }

    private fun sectionTitle(title: String, trailing: View? = null): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(th.text(title.uppercase(), 11f, th.text2, UdnTheme.Weight.Bold).apply { letterSpacing = 0.14f }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (trailing != null) addView(trailing)
        layoutParams = th.lp(top = 24, bottom = 10).apply { marginStart = th.dp(4); marginEnd = th.dp(4) }
    }

    private fun card(tint: Int? = null, pad: Int = 16, vararg children: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = th.glass(22, tint)
        setPadding(th.dp(pad), th.dp(pad), th.dp(pad), th.dp(pad))
        layoutParams = th.lp(bottom = 12)
        children.forEach { addView(it) }
    }

    private fun message(title: String, body: String, action: View? = null): View = card(th.accent, 20,
        th.text(title, 17f, th.text1, UdnTheme.Weight.Bold),
        th.text(body, 13f, th.text2).apply { setLineSpacing(0f, 1.2f) }.also { it.layoutParams = th.lp(top = 8) }
    ).also { c -> if (action != null) c.addView(action, th.lp(w = ViewGroup.LayoutParams.WRAP_CONTENT, top = 14)) }

    private fun button(label: String, primary: Boolean = true, iconRes: Int? = null, onClick: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        setPadding(th.dp(16), th.dp(10), th.dp(18), th.dp(10))
        background = if (primary) th.pill(th.accent) else th.pill(th.alpha(th.text1, 0x14), th.border)
        isClickable = true; isFocusable = true
        if (iconRes != null) addView(th.icon(iconRes, 16, if (primary) th.onAccent else th.text1).also { it.layoutParams = LinearLayout.LayoutParams(th.dp(16), th.dp(16)).apply { marginEnd = th.dp(8) } })
        addView(th.text(label, 13f, if (primary) th.onAccent else th.text1, UdnTheme.Weight.Medium))
        setOnClickListener { onClick() }
    }

    private fun row(vararg children: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        children.forEach { addView(it) }
    }

    private fun weighted(v: View, marginEnd: Int = 0): View = v.also { it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { this.marginEnd = th.dp(marginEnd) } }

    // ---- join

    private fun renderJoin() {
        bodyHolder.addView(message(
            "Join your JARVIS Device Network",
            "Register this device with your account so your other JARVIS devices can see it. A parent device can then control it, and you can leave at any time.",
            button("Join network", true, R.drawable.udn_ic_group) {
                settings.enrollmentPrompted = true
                lifecycleScope.launch { mgr.enroll(); forceRender() }
            }
        ))
    }

    private fun renderWaiting(s: UdnState) {
        val removed = s.status == "rejected" || s.status == "revoked"
        bodyHolder.addView(message(
            if (removed) "This device was removed" else "Joining the network…",
            if (removed) "A parent removed this device. You can rejoin at any time." else "Devices join automatically once you're signed in. If this doesn't change in a moment, check your connection.",
            button(if (removed) "Rejoin" else "Retry", true, R.drawable.udn_ic_refresh) { lifecycleScope.launch { mgr.ensureJoined(); mgr.refreshSelf(); forceRender() } }
        ))
    }

    // ---- hero

    private fun roleLabel(s: UdnState) = if (s.isParent) "Parent ${s.slot ?: ""}".trim() else "Child device"

    private fun renderHero(s: UdnState) {
        val self = s.devices.firstOrNull { it.id == s.deviceId }
        val name = self?.name ?: "${Build.MANUFACTURER} ${Build.MODEL}"
        val holder = FrameLayout(this).also { liveHolder = it }
        val head = row(
            th.iconTile(R.drawable.udn_ic_shield, th.accent, 52, 26),
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(th.text("THIS DEVICE", 10f, th.text2, UdnTheme.Weight.Bold).apply { letterSpacing = 0.14f })
                addView(th.text(name, 19f, th.text1, UdnTheme.Weight.Bold), th.lp(top = 4))
            }.also { it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = th.dp(14) } },
            holder
        )
        val roles = row(th.chip(roleLabel(s), th.accent, filled = true)).apply { layoutParams = th.lp(top = 14) }
        val authorized = s.devices.filter { it.isAuthorized }
        val tiles = row(
            weighted(statTile(authorized.count { it.online }.toString(), "Online"), 8),
            weighted(statTile(authorized.size.toString(), "Devices"), 8),
            weighted(statTile(authorized.count { it.isParent }.toString(), "Parents"))
        ).apply { layoutParams = th.lp(top = 16) }
        val c = card(th.accent, 18, head, roles)
        if (s.isParent) c.addView(tiles)
        bodyHolder.addView(c)
    }

    private fun statTile(value: String, label: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        background = GradientDrawable().apply { cornerRadius = th.dpf(16f); setColor(th.alpha(th.text1, 0x10)); setStroke(th.dp(1), th.alpha(th.text1, 0x18)) }
        setPadding(th.dp(8), th.dp(12), th.dp(8), th.dp(12))
        addView(th.text(value, 22f, th.text1, UdnTheme.Weight.Bold))
        addView(th.text(label, 11f, th.text2), th.lp(top = 4))
    }

    // ---- pending

    private fun renderPending(s: UdnState) {
        val pending = s.devices.filter { it.status == "pending" }
        if (pending.isEmpty()) return
        bodyHolder.addView(sectionTitle("Waiting for approval"))
        pending.forEach { d ->
            bodyHolder.addView(card(th.warning, 16,
                row(th.iconTile(platformIcon(d), th.warning), th.text(d.name, 15f, th.text1, UdnTheme.Weight.Bold).also { it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = th.dp(12) } }),
                row(button("Approve") { run({ mgr.decideDevice(d.id, true) }) }, button("Reject", false) { run({ mgr.decideDevice(d.id, false) }) }.also { it.layoutParams = th.lp(w = ViewGroup.LayoutParams.WRAP_CONTENT, start = 10) }).apply { layoutParams = th.lp(top = 12) }
            ))
        }
    }

    // ---- devices

    private fun platformIcon(d: UdnDevice): Int {
        val n = "${d.name} ${d.model ?: ""}".lowercase()
        return when {
            d.platform == "windows" -> R.drawable.udn_ic_laptop
            Regex("\\b(pad|tab|tablet)\\b").containsMatchIn(n) -> R.drawable.udn_ic_tablet
            else -> R.drawable.udn_ic_phone
        }
    }

    private fun ago(ms: Long?): String {
        if (ms == null) return "never"
        val m = (System.currentTimeMillis() - ms) / 60_000
        return when {
            m < 1 -> "just now"; m < 60 -> "$m min ago"; m < 1440 -> "${m / 60} h ago"
            else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))
        }
    }

    private fun connLabel(c: String?) = when (c) { "wifi" -> "Wi-Fi"; "cellular" -> "Mobile data"; "ethernet" -> "Ethernet"; null -> "Network unknown"; else -> c }

    private fun batteryBar(pct: Int, charging: Boolean?): View {
        val color = when { charging == true -> th.success; pct <= 15 -> th.error; pct <= 30 -> th.warning; else -> th.accent }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(th.icon(if (charging == true) R.drawable.udn_ic_bolt else R.drawable.udn_ic_battery, 14, color))
            addView(th.text("$pct%", 12f, th.text1, UdnTheme.Weight.Medium), th.lp(w = ViewGroup.LayoutParams.WRAP_CONTENT, start = 6))
            addView(FrameLayout(this@UdnActivity).apply {
                background = th.pill(th.alpha(th.text1, 0x1A), null, 4)
                addView(View(this@UdnActivity).apply { background = th.pill(color, null, 4) }, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT).also { lp -> post { lp.width = (width * pct.coerceIn(2, 100) / 100f).toInt(); requestLayout() } })
            }, th.lp(w = th.dp(54), h = th.dp(4), start = 8))
        }
    }

    private fun renderDevices(s: UdnState) {
        val devices = s.devices.filter { it.status == "authorized" }
            .sortedWith(compareByDescending<UdnDevice> { it.id == s.deviceId }.thenByDescending { it.isParent }.thenBy { it.name.lowercase() })
        bodyHolder.addView(sectionTitle("Devices"))
        if (!s.isParent) {
            bodyHolder.addView(card(null, 16, th.text("Only parent devices can see and control the network. This device can still receive commands and messages from them.", 13f, th.text2).apply { setLineSpacing(0f, 1.2f) }))
            return
        }
        if (devices.isEmpty()) { bodyHolder.addView(card(null, 16, th.text("No devices yet.", 13f, th.text2))); return }
        devices.forEach { d ->
            val isSelf = d.id == s.deviceId
            val top = row(
                th.iconTile(platformIcon(d), if (d.online) th.accent else th.muted),
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(th.text(d.name + if (isSelf) "  ·  this device" else "", 16f, th.text1, UdnTheme.Weight.Bold))
                    addView(th.text(listOfNotNull(d.manufacturer?.replaceFirstChar { it.uppercase() }, d.osVersion?.let { "Android $it".takeIf { _ -> d.platform == "android" } ?: it }).joinToString(" · ").ifBlank { d.platform.replaceFirstChar { it.uppercase() } }, 12f, th.text2), th.lp(top = 4))
                }.also { it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = th.dp(12); marginEnd = th.dp(8) } },
                th.chip(if (d.online) "Online" else "Offline", if (d.online) th.success else th.muted, dot = true)
            )
            val chips = row(th.chip(if (d.isParent) "Parent ${d.parentSlot}" else "Child", if (d.isParent) th.accent else th.text2)).apply { layoutParams = th.lp(top = 12) }
            val meta = row().apply { layoutParams = th.lp(top = 12) }
            d.batteryPct?.let { meta.addView(batteryBar(it, d.charging)) }
            if (d.online || d.lastSeenMs != null) meta.addView(
                th.text(listOfNotNull(connLabel(d.connection).takeIf { d.online }, if (d.online) null else "Last seen ${ago(d.lastSeenMs)}").joinToString(" · "), 12f, th.text2),
                th.lp(w = ViewGroup.LayoutParams.WRAP_CONTENT, start = if (d.batteryPct != null) 14 else 0)
            )
            val c = card(if (d.online) th.accent else null, 16, top, chips, meta)
            c.isClickable = true; c.isFocusable = true
            c.setOnClickListener { showDevice(d, isSelf) }
            bodyHolder.addView(c)
        }
    }

    // ---- device sheet

    private fun showDevice(d0: UdnDevice, isSelf: Boolean) {
        val s = mgr.state.value
        val d = s.devices.firstOrNull { it.id == d0.id } ?: d0
        val dialog = BottomSheetDialog(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(th.dp(20), th.dp(20), th.dp(20), th.dp(28)) }
        col.background = LayerDrawableOf(th.bg, th.glass(28, th.accent))

        col.addView(row(
            th.iconTile(platformIcon(d), th.accent, 52, 26),
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(th.text(d.name, 19f, th.text1, UdnTheme.Weight.Bold))
                addView(th.text(listOfNotNull(d.manufacturer, d.model, d.osVersion).joinToString(" · ").ifBlank { d.platform }, 12f, th.text2), th.lp(top = 4))
            }.also { it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = th.dp(14) } }
        ))
        col.addView(row(
            th.chip(if (d.online) "Online" else "Offline", if (d.online) th.success else th.muted, dot = true),
            th.chip(if (d.isParent) "Parent ${d.parentSlot}" else "Child", if (d.isParent) th.accent else th.text2).also { it.layoutParams = th.lp(w = ViewGroup.LayoutParams.WRAP_CONTENT, start = 8) }
        ).apply { layoutParams = th.lp(top = 14) })

        col.addView(row(
            weighted(statTile(d.batteryPct?.let { "$it%" } ?: "—", "Battery"), 8),
            weighted(statTile(connLabel(d.connection)?.takeIf { d.online } ?: "—", "Network"), 8),
            weighted(statTile(if (d.online) "Now" else ago(d.lastSeenMs).replace(" ago", ""), "Last seen"))
        ).apply { layoutParams = th.lp(top = 18) })

        val result = th.text("", 13f, th.text1).apply { setLineSpacing(0f, 1.2f); visibility = View.GONE }
        val resultBox = LinearLayout(this).apply {
            background = th.glass(16, th.accent); setPadding(th.dp(14), th.dp(12), th.dp(14), th.dp(12)); visibility = View.GONE
            addView(result)
        }
        fun quick(cap: String, params: JSONObject = JSONObject(), confirmed: Boolean = false) {
            resultBox.visibility = View.VISIBLE; result.visibility = View.VISIBLE; result.text = "Working…"
            lifecycleScope.launch {
                mgr.refreshRegistry()
                result.text = UdnVoiceRouter.runOne(mgr, UdnLang.EN, d, cap, params, confirmed)
            }
        }
        if (s.isParent && !isSelf) {
            fun action(label: String, icon: Int, tint: Int = th.text1, on: () -> Unit): View = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                background = GradientDrawable().apply { cornerRadius = th.dpf(16f); setColor(th.alpha(th.text1, 0x10)); setStroke(th.dp(1), th.alpha(th.text1, 0x18)) }
                setPadding(th.dp(4), th.dp(12), th.dp(4), th.dp(10)); isClickable = true; isFocusable = true
                addView(th.icon(icon, 20, tint)); addView(th.text(label, 11f, th.text1, UdnTheme.Weight.Medium), th.lp(top = 8))
                setOnClickListener { on() }
            }
            col.addView(th.text("QUICK ACTIONS", 10f, th.text2, UdnTheme.Weight.Bold).apply { letterSpacing = 0.14f }, th.lp(top = 22, bottom = 10))
            col.addView(row(
                weighted(action("Battery", R.drawable.udn_ic_battery) { quick(UdnCapabilities.DEVICE_BATTERY) }, 8),
                weighted(action("Lock", R.drawable.udn_ic_lock) { confirm("Lock ${d.name}?", "Its screen will turn off and lock.") { quick(UdnCapabilities.DEVICE_LOCK, confirmed = true) } }, 8),
                weighted(action("Message", R.drawable.udn_ic_message) { dialog.dismiss(); composeMessage(d.id) }, 8),
                weighted(action("Rename", R.drawable.udn_ic_edit) { dialog.dismiss(); rename(d) }, 8),
                weighted(action("Remove", R.drawable.udn_ic_remove, th.error) {
                    confirm("Remove ${d.name}?", "It loses access immediately and must rejoin to come back.") { dialog.dismiss(); run({ mgr.revokeDevice(d.id) }) }
                })
            ))
            col.addView(resultBox, th.lp(top = 12))
        } else if (isSelf) {
            col.addView(button("Leave network", false) {
                confirm("Leave the network?", "This device is removed and its key deleted. It can rejoin later.") { dialog.dismiss(); run({ mgr.revokeDevice(d.id) }) }
            }, th.lp(w = ViewGroup.LayoutParams.WRAP_CONTENT, top = 18))
        }

        col.addView(th.text("CAPABILITIES", 10f, th.text2, UdnTheme.Weight.Bold).apply { letterSpacing = 0.14f }, th.lp(top = 22, bottom = 8))
        if (d.capabilities.isEmpty()) col.addView(th.text("Not reported yet.", 12f, th.text2))
        d.capabilities.forEach { c ->
            val (color, state) = when {
                !c.available -> th.muted to (c.reason ?: "Unavailable")
                c.permissionGranted == false -> th.warning to "Needs permission"
                c.policy == "deny" -> th.error to "Blocked by policy"
                c.policy == "confirm" -> th.accent to "Asks first"
                else -> th.success to "Ready"
            }
            col.addView(row(
                View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }; layoutParams = LinearLayout.LayoutParams(th.dp(7), th.dp(7)) },
                th.text(UdnCapabilities.label(c.capability), 13f, th.text1).also { it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = th.dp(10) } },
                th.text(state, 12f, th.text2)
            ).apply { layoutParams = th.lp(top = 8) })
        }

        if (s.isParent) {
            col.addView(th.text("RECENT COMMANDS", 10f, th.text2, UdnTheme.Weight.Bold).apply { letterSpacing = 0.14f }, th.lp(top = 22, bottom = 8))
            val hist = th.text("Loading…", 12f, th.text2)
            col.addView(hist)
            lifecycleScope.launch {
                hist.text = when (val r = mgr.commandHistory(d.id)) {
                    is UdnResult.Ok -> if (r.value.isEmpty()) "No commands yet." else r.value.take(8).joinToString("\n") { "${UdnCapabilities.label(it.capability)} · ${it.status.replace('_', ' ')} · ${ago(it.createdMs)}" }
                    is UdnResult.Err -> r.message
                }
            }
        }
        dialog.setContentView(ScrollView(this).apply { addView(col); isVerticalScrollBarEnabled = false })
        dialog.setOnShowListener {
            (col.parent?.parent as? View)?.setBackgroundColor(Color.TRANSPARENT)
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.setBackgroundColor(Color.TRANSPARENT)
        }
        dialog.show()
    }

    @Suppress("FunctionName")
    private fun LayerDrawableOf(solid: Int, overlay: android.graphics.drawable.Drawable): android.graphics.drawable.Drawable =
        android.graphics.drawable.LayerDrawable(arrayOf(
            GradientDrawable().apply { cornerRadius = th.dpf(28f); setColor(solid) }, overlay
        ))

    // ---- messages

    private fun renderMessages(s: UdnState) {
        bodyHolder.addView(sectionTitle(if (s.isParent) "Messages" else "Messages for this device",
            if (s.isParent) button("New", true, R.drawable.udn_ic_message) { composeMessage(null) } else null))
        val msgs = messageCache
        val byId = s.devices.associateBy { it.id }
        val c = card(null, 16)
        when {
            msgs == null -> c.addView(th.text("Loading…", 12f, th.text2))
            msgs.isEmpty() -> c.addView(th.text(if (s.isParent) "No messages yet. Send one to any device and it's saved here." else "Nothing yet.", 13f, th.text2))
            else -> msgs.take(20).forEachIndexed { i, m ->
                if (i > 0) c.addView(th.hRule())
                val from = byId[m.fromDeviceId]?.name ?: "A parent device"
                val to = byId[m.toDeviceId]?.name ?: "a device"
                val (label, color) = when (m.delivery) {
                    "succeeded" -> "Delivered" to th.success
                    "failed", "rejected", "cancelled" -> "Not shown" to th.error
                    "expired", "timed_out" -> "Not delivered" to th.warning
                    null -> "Saved" to th.text2
                    else -> "Waiting" to th.accent
                }
                c.addView(row(
                    th.text(if (s.isParent) "$from  →  $to" else "From $from", 12f, th.accent, UdnTheme.Weight.Bold).also { it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) },
                    th.chip(label, color)
                ))
                c.addView(th.text(m.body, 14f, th.text1).apply { setLineSpacing(0f, 1.2f) }, th.lp(top = 8))
                c.addView(th.text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(m.createdMs ?: 0L)), 11f, th.muted), th.lp(top = 6))
            }
        }
        bodyHolder.addView(c)
    }

    private fun composeMessage(preselect: String?) {
        val s = mgr.state.value
        val targets = s.devices.filter { it.isAuthorized && it.id != s.deviceId }
        if (targets.isEmpty()) { toast("No other devices have joined yet."); return }
        val dialog = BottomSheetDialog(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(th.dp(20), th.dp(22), th.dp(20), th.dp(28)); background = LayerDrawableOf(th.bg, th.glass(28, th.accent)) }
        col.addView(th.text("New message", 20f, th.text1, UdnTheme.Weight.Bold))
        col.addView(th.text("Shown as a notification on the device and saved in Messages.", 12f, th.text2), th.lp(top = 6, bottom = 16))
        val selected = HashSet<String>().apply { if (preselect != null) add(preselect) }
        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun styleChip(v: TextView, on: Boolean) {
            v.background = if (on) th.pill(th.accent) else th.pill(th.alpha(th.text1, 0x14), th.border)
            v.setTextColor(if (on) th.onAccent else th.text1)
        }
        val chips = ArrayList<Pair<String?, TextView>>()
        fun refresh() {
            chips.forEach { (id, v) -> styleChip(v, if (id == null) selected.size == targets.size else id in selected) }
        }
        fun addChip(label: String, id: String?) {
            val v = th.text(label, 13f, th.text1, UdnTheme.Weight.Medium).apply { setPadding(th.dp(14), th.dp(9), th.dp(14), th.dp(9)); isClickable = true }
            v.setOnClickListener {
                if (id == null) { if (selected.size == targets.size) selected.clear() else targets.forEach { selected.add(it.id) } }
                else if (!selected.add(id)) selected.remove(id)
                refresh()
            }
            chips += id to v
            chipRow.addView(v, th.lp(w = ViewGroup.LayoutParams.WRAP_CONTENT, end = 8))
        }
        addChip("All devices", null)
        targets.forEach { addChip(it.name + if (it.online) "" else " · offline", it.id) }
        refresh()
        col.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chipRow) })
        val input = EditText(this).apply {
            hint = "Write your message…"; setHintTextColor(th.muted); setTextColor(th.text1); textSize = 15f
            minLines = 3; maxLines = 6; gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = th.glass(16); setPadding(th.dp(14), th.dp(12), th.dp(14), th.dp(12))
        }
        col.addView(input, th.lp(top = 16))
        col.addView(button("Send message", true, R.drawable.udn_ic_send) {
            val text = input.text.toString().trim()
            when {
                text.isEmpty() -> input.error = "Write a message"
                selected.isEmpty() -> toast("Choose at least one device.")
                else -> {
                    dialog.dismiss()
                    lifecycleScope.launch {
                        when (val r = mgr.sendMessage(selected.toList(), text)) {
                            is UdnResult.Ok -> { toast("Message sent and saved."); (mgr.messages() as? UdnResult.Ok)?.let { messageCache = it.value }; forceRender() }
                            is UdnResult.Err -> toast(r.message)
                        }
                    }
                }
            }
        }, th.lp(w = ViewGroup.LayoutParams.WRAP_CONTENT, top = 16))
        dialog.setContentView(col)
        dialog.setOnShowListener {
            (col.parent as? View)?.setBackgroundColor(Color.TRANSPARENT)
            dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        }
        dialog.show()
    }

    // ---- groups

    private fun renderGroups(s: UdnState) {
        bodyHolder.addView(sectionTitle("Groups", button("New", false, R.drawable.udn_ic_group) {
            val et = EditText(this).apply { hint = "Group name"; setSingleLine() }
            AlertDialog.Builder(this).setTitle("New group").setView(et)
                .setPositiveButton("Create") { _, _ -> et.text.toString().trim().takeIf { it.isNotEmpty() }?.let { n -> run({ mgr.createGroup(n) }) } }
                .setNegativeButton("Cancel", null).show()
        }))
        val c = card(null, 16)
        if (s.groups.isEmpty()) c.addView(th.text("Group devices (for example “Family tablets”) to control them together.", 13f, th.text2))
        s.groups.forEachIndexed { i, g ->
            if (i > 0) c.addView(th.hRule())
            val names = g.deviceIds.mapNotNull { id -> s.devices.firstOrNull { it.id == id }?.name }
            c.addView(row(
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(th.text(g.name, 15f, th.text1, UdnTheme.Weight.Bold))
                    addView(th.text(if (names.isEmpty()) "No devices" else names.joinToString(", "), 12f, th.text2), th.lp(top = 4))
                }.also { it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) },
                button("Edit", false) { editGroup(g, s) },
                button("Delete", false) { run({ mgr.deleteGroup(g.id) }) }.also { it.layoutParams = th.lp(w = ViewGroup.LayoutParams.WRAP_CONTENT, start = 8) }
            ))
        }
        bodyHolder.addView(c)
    }

    private fun editGroup(g: UdnGroup, s: UdnState) {
        val devs = s.devices.filter { it.isAuthorized }
        val checked = BooleanArray(devs.size) { devs[it].id in g.deviceIds }
        AlertDialog.Builder(this).setTitle(g.name)
            .setMultiChoiceItems(devs.map { it.name }.toTypedArray(), checked) { _, i, v -> checked[i] = v }
            .setPositiveButton("Save") { _, _ ->
                lifecycleScope.launch {
                    devs.forEachIndexed { i, d -> if (checked[i] != (d.id in g.deviceIds)) mgr.setGroupMember(g.id, d.id, remove = !checked[i]) }
                    mgr.refreshRegistry(); forceRender()
                }
            }.setNegativeButton("Cancel", null).show()
    }

    // ---- this device

    private fun switchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val sw = SwitchCompat(this).apply { isChecked = checked; ThemeManager.tintSwitch(this, th.accent); setOnCheckedChangeListener { _, v -> onChange(v) } }
        return row(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(th.text(title, 14f, th.text1, UdnTheme.Weight.Medium))
                addView(th.text(sub, 12f, th.text2).apply { setLineSpacing(0f, 1.15f) }, th.lp(top = 4))
            }.also { it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = th.dp(12) } },
            sw
        )
    }

    private fun renderLocalSettings(s: UdnState) {
        if (!s.enrolled) return
        bodyHolder.addView(sectionTitle("This device"))
        val c = card(null, 16)
        if (s.authorized) {
            c.addView(switchRow("Allow remote control", "When off, this device refuses every command from other devices.", settings.remoteControlEnabled) {
                settings.remoteControlEnabled = it; lifecycleScope.launch { mgr.pushCapabilities(force = true) }; forceRender()
            })
            c.addView(th.hRule())
            c.addView(switchRow("Share location on request", "Off by default. Even when on, each request still needs your Allow tap here.", settings.locationSharingEnabled) {
                settings.locationSharingEnabled = it; lifecycleScope.launch { mgr.pushCapabilities(force = true) }; forceRender()
            })
            c.addView(th.hRule())
            c.addView(switchRow("Keep network link running", "A quiet foreground service so this device can receive commands while the app is closed.", settings.linkEnabled) {
                settings.linkEnabled = it
                if (it) UdnLinkService.start(applicationContext) else UdnLinkService.stop(applicationContext)
                forceRender()
            })
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                c.addView(th.hRule())
                c.addView(th.text("Notifications are off, so approval prompts and messages can't appear.", 12f, th.warning))
                c.addView(button("Allow notifications", false) { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }, th.lp(w = ViewGroup.LayoutParams.WRAP_CONTENT, top = 10))
            }
        }
        if (!s.authorized) {
            c.addView(button("Leave network", false) { confirm("Leave the network?", "This device is removed and its key deleted. It can rejoin later.") { s.deviceId?.let { id -> run({ mgr.revokeDevice(id) }) } } })
        }
        bodyHolder.addView(c)
    }

    // ---- voice

    private fun statusRow(label: String, value: String, ok: Boolean?): View = row(
        View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(when (ok) { true -> th.success; false -> th.warning; null -> th.muted }) }; layoutParams = LinearLayout.LayoutParams(th.dp(8), th.dp(8)) },
        th.text(label, 13f, th.text1).also { it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = th.dp(12) } },
        th.text(value, 12f, th.text2)
    ).apply { layoutParams = th.lp(top = 10) }

    private fun renderVoice() {
        bodyHolder.addView(sectionTitle("Voice control"))
        val micOk = PermissionHelper.hasRecordAudioPermission(this)
        val wakeOn = WakeKeepAlive.isEnabled(this)
        val listening = JarvisConversationService.isSessionOn.value || JarvisConversationService.isStandby.value
        val ignoringOpt = PermissionHelper.hasBatteryOptimizationPermission(this)
        val c = card(null, 16,
            th.text("Say it to JARVIS — in English, Hindi or Hinglish — and results come back from the device itself.", 13f, th.text2).apply { setLineSpacing(0f, 1.2f) },
            statusRow("Microphone", if (micOk) "Allowed" else "Off", micOk),
            statusRow("“Hey JARVIS” standby", if (listening) "Listening" else if (wakeOn) "Enabled, not running" else "Off", if (listening) true else if (wakeOn) false else null),
            statusRow("Battery optimisation", if (ignoringOpt) "Exempt" else "Restricted", ignoringOpt)
        )
        c.addView(th.text("Android shows a notification while the microphone is used in the background, and some manufacturers stop background apps unless battery optimisation is off. Listening pauses during phone calls.", 11f, th.muted).apply { setLineSpacing(0f, 1.2f) }, th.lp(top = 14))
        val actions = row().apply { layoutParams = th.lp(top = 14) }
        if (!ignoringOpt) actions.addView(button("Allow background", false) {
            runCatching { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
                .onFailure { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }, th.lp(w = ViewGroup.LayoutParams.WRAP_CONTENT, end = 8))
        actions.addView(button(if (wakeOn) "Turn off standby" else "Turn on standby", false, R.drawable.udn_ic_mic) { WakeKeepAlive.setEnabled(this, !wakeOn); forceRender() })
        c.addView(actions)
        bodyHolder.addView(c)
    }

    // ---- audit

    private fun renderAudit() {
        bodyHolder.addView(sectionTitle("Security log"))
        val events = auditCache
        val c = card(null, 16)
        when {
            events == null -> c.addView(th.text("Loading…", 12f, th.text2))
            events.isEmpty() -> c.addView(th.text("No events yet.", 13f, th.text2))
            else -> events.take(12).forEachIndexed { i, e ->
                if (i > 0) c.addView(View(this), th.lp(h = th.dp(10)))
                c.addView(row(
                    View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(th.accent) }; layoutParams = LinearLayout.LayoutParams(th.dp(7), th.dp(7)) },
                    LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(th.text(e.action.replace('_', ' ').replaceFirstChar { it.uppercase() }, 13f, th.text1, UdnTheme.Weight.Medium))
                        addView(th.text(ago(e.createdMs), 11f, th.muted), th.lp(top = 2))
                    }.also { it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = th.dp(12) } }
                ))
            }
        }
        bodyHolder.addView(c)
    }

    // ---- helpers

    private fun <T> run(op: suspend () -> UdnResult<T>, ok: (T) -> Unit = {}) {
        lifecycleScope.launch {
            when (val r = op()) {
                is UdnResult.Ok -> { ok(r.value); forceRender() }
                is UdnResult.Err -> toast(r.message)
            }
        }
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
