package com.jarvis.assistant.ui.jarvishome

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateUtils
import android.widget.EditText
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.jarvis.assistant.auth.AuthManager
import com.jarvis.assistant.home.CommandResult
import com.jarvis.assistant.home.DeviceStatus
import com.jarvis.assistant.home.HomeAssistant
import com.jarvis.assistant.home.HomeServices
import com.jarvis.assistant.home.voice.HomeIntent
import com.jarvis.assistant.home.voice.SmartDeviceIntentEngine
import org.json.JSONObject

/** Voice Control, Device Activity and Home Settings pages. */
object MiscPages {

    private var voiceResult: String? = null
    private var voiceInput: String = ""
    private var activityFailuresOnly = false
    private var hubLines: List<String>? = null
    private var hubError: String? = null

    // ---- Voice Control ----------------------------------------------------------------------------

    fun voice(host: HomeHost, c: LinearLayout) {
        val ui = host.ui
        c.addView(ui.glassCard().apply {
            addView(ui.gradientText("TALK TO JARVIS", 14f))
            addView(ui.text("Smart-home voice control uses the JARVIS assistant you already have, from any screen. Say things like:", 13f, ui.muted).apply { setPadding(0, ui.dp(4), 0, ui.dp(6)) })
            listOf("“Jarvis, turn on my bedroom lights”", "“Jarvis, living room ka fan band karo”", "“Jarvis, activate movie mode”", "“Jarvis, show all offline devices”", "“Jarvis, what devices are connected?”")
                .forEach { addView(ui.text(it, 13f)) }
            addView(ui.text("Names resolve from your device names, aliases, rooms, groups and scenes (English, Hindi, Hinglish). JARVIS only says a device changed when the device itself confirmed it. Needs the AI voice assistant to be set up with a Gemini key.", 12f, ui.muted).apply { setPadding(0, ui.dp(8), 0, 0) })
        })
        c.addView(ui.glassCard().apply {
            addView(ui.gradientText("TRY A COMMAND", 14f))
            val input = EditText(host.activity).apply { setText(voiceInput); hint = "turn on bedroom lights"; setSingleLine(true); setTextColor(ui.primary); setHintTextColor(ui.muted) }
            addView(input, LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(LinearLayout(host.activity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(ui.button("What would it do?") {
                    voiceInput = input.text.toString()
                    voiceResult = explain(host, voiceInput); host.rerender()
                })
                addView(ui.button("Run it", true) {
                    voiceInput = input.text.toString()
                    if (voiceInput.isBlank()) return@button
                    host.background({ HomeAssistant.handle(host.activity, voiceInput) }) { o ->
                        voiceResult = o?.let { (if (it.success && it.verified) "✓ " else if (it.success) "• " else "✗ ") + it.spoken } ?: "That is not about any device, room, group or scene in your home."
                        host.rerender()
                    }
                })
            })
            voiceResult?.let { addView(ui.text(it, 13f, ui.primary).apply { setPadding(0, ui.dp(8), 0, 0) }) }
        })
    }

    private fun explain(host: HomeHost, text: String): String {
        val snap = HomeAssistant.snapshot(host.activity)
        return when (val i = SmartDeviceIntentEngine.parse(text, snap)) {
            null -> "Not a smart-home command for your current devices."
            is HomeIntent.Control -> "${i.action.type.name.lowercase().replace('_', ' ')} → ${i.devices.joinToString { it.name }}"
            is HomeIntent.RunScene -> "Run scene “${i.scene.name}”" + if (i.scene.isConfigured) "" else " (not configured yet, it will refuse)"
            is HomeIntent.ListDevices -> "List ${i.filter.name.lowercase()} devices"
            is HomeIntent.Connect -> "Connect ${i.device.name}"
            is HomeIntent.Clarify -> i.question
        }
    }

    // ---- Device Activity --------------------------------------------------------------------------

    fun activity(host: HomeHost, c: LinearLayout) {
        val ui = host.ui
        val devices = host.registry.devices()
        c.addView(ui.glassCard().apply {
            addView(ui.gradientText("LAST SEEN", 14f))
            if (devices.isEmpty()) addView(ui.text("No devices registered.", 13f, ui.muted))
            devices.forEach { d ->
                addView(ui.text("${d.name} · ${d.connection.label} · " + (if (d.lastSeenMillis > 0) DateUtils.getRelativeTimeSpanString(d.lastSeenMillis).toString() else "never reached"), 12f, ui.statusColor(d.status)).apply { setPadding(0, ui.dp(4), 0, 0) })
            }
        })
        c.addView(LinearLayout(host.activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(ui.chip("All", !activityFailuresOnly) { activityFailuresOnly = false; host.rerender() })
            addView(ui.chip("Failures", activityFailuresOnly) { activityFailuresOnly = true; host.rerender() })
        })
        c.addView(ui.space(8))
        val events = host.registry.activity().filter { !activityFailuresOnly || !it.success }.take(80)
        if (events.isEmpty()) c.addView(ui.notice("No activity yet", "Connections, commands and routine runs appear here as they really happen."))
        events.forEach { e ->
            val name = e.deviceId?.let { host.registry.device(it)?.name }
            c.addView(ui.text("${if (e.success) "✓" else "✗"} ${DateUtils.getRelativeTimeSpanString(e.timeMillis)} · ${e.kind}${name?.let { " · $it" } ?: ""}\n${e.summary}", 12f, if (e.success) ui.muted else ui.warning).apply { setPadding(ui.dp(4), ui.dp(2), 0, ui.dp(6)) })
        }
    }

    // ---- Settings ---------------------------------------------------------------------------------

    fun settings(host: HomeHost, c: LinearLayout) {
        val ui = host.ui
        val act = host.activity
        fun open(intent: Intent) = runCatching { act.startActivity(intent) }.onFailure { host.toast("Could not open that settings screen.") }
        fun granted(p: String) = ContextCompat.checkSelfPermission(act, p) == PackageManager.PERMISSION_GRANTED
        val btOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || (granted(Manifest.permission.BLUETOOTH_CONNECT) && granted(Manifest.permission.BLUETOOTH_SCAN))
        val devices = host.registry.devices()

        fun group(title: String, build: LinearLayout.() -> Unit) {
            c.addView(ui.glassCard().apply { addView(ui.gradientText(title.uppercase(), 13f).apply { setPadding(0, 0, 0, ui.dp(6)) }); build() })
        }

        group("1 · Device Discovery") {
            addView(ui.text("Scans are started by you, last 12 seconds and stop when you leave the page. Nothing scans in the background.", 12f, ui.muted))
            addView(ui.actionRow("Open Device Discovery", null) { host.navigate(HomePage.DISCOVERY) })
        }
        group("2 · Bluetooth Management") {
            addView(ui.text("Permission: ${if (btOk) "granted" else "Nearby devices not granted"} · ${devices.count { it.connection.isBluetooth }} registered", 12f, ui.muted))
            addView(ui.actionRow("Android Bluetooth settings", "Pair or connect speakers here") { open(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) })
            if (!btOk) addView(ui.actionRow("Allow Nearby devices", null) { host.requirePermissions(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT) else emptyArray()) { host.rerender() } })
        }
        group("3 · Wi-Fi Management") {
            addView(ui.text("${devices.count { it.connection.isWifi }} Wi-Fi / LAN devices registered", 12f, ui.muted))
            addView(ui.actionRow("Android Wi-Fi settings", null) { open(Intent(Settings.ACTION_WIFI_SETTINGS)) })
            addView(ui.actionRow("Check every device now", "Reads each device's real state") {
                host.toast("Checking…")
                host.background({ devices.map { host.controller.refresh(it.id) } }) { r -> host.toast("${r.count { it is CommandResult.Success }} of ${r.size} answered.") }
            })
        }
        group("4 · Manufacturer Integrations") {
            addView(ui.text("Working: Tasmota (HTTP), Shelly Gen1 relays (HTTP), custom HTTP on/off URLs, BLE GATT characteristics, paired Bluetooth status.\nNot available yet: Matter control, MQTT, and manufacturer cloud APIs (they need official credentials and approval). JARVIS lists such devices only as unsupported.", 12f, ui.muted))
        }
        group("5 · Home Hub Configuration") {
            val signedIn = AuthManager.session.value != null
            addView(ui.text(if (signedIn) "Signed in. The Home Hub signs in with the same account." else "Sign in to JARVIS (drawer → Account) to use remote control.", 12f, ui.muted))
            hubLines?.forEach { addView(ui.text(it, 12f)) }
            hubError?.let { addView(ui.text(it, 12f, ui.warning)) }
            addView(ui.actionRow("Check Home Hubs", "Shows which hubs are online right now") {
                host.background({ runCatching { HomeServices.remote(act).hubs() } }) { r ->
                    r.onSuccess { list -> hubError = null; hubLines = if (list.isEmpty()) listOf("No Home Hub registered yet. Install the hub from the hub/ folder of the repository.") else list.map { "${it.name}: ${if (it.isOnline()) "online" else "offline"}" } }
                        .onFailure { hubError = it.message ?: "Could not reach the cloud."; hubLines = null }
                    host.rerender()
                }
            })
            addView(ui.actionRow("Sync device list to the cloud", "Names and settings only, never passwords") {
                host.background({ runCatching { devices.forEach { HomeServices.remote(act).syncDevice(it, host.registry.room(it.roomId)?.name) } } }) { r ->
                    host.toast(if (r.isSuccess) "Synced ${devices.size} device(s)." else "Sync failed: ${r.exceptionOrNull()?.message}")
                }
            })
        }
        group("6 · Voice Control") {
            addView(ui.actionRow("Open Voice Control", "Aliases are set per device") { host.navigate(HomePage.VOICE) })
        }
        group("7 · Automation Preferences") {
            addView(ui.text("Schedules run through Android WorkManager, which can start a job a few minutes late while the phone sleeps.", 12f, ui.muted))
            addView(ui.actionRow("Re-plan all schedules", null) { (act as? HomeActivity)?.reschedule(); host.toast("Schedules re-planned.") })
            addView(ui.actionRow("Battery optimisation", "Allow JARVIS to run routines on time") { open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) })
        }
        group("8 · Room Management") {
            addView(ui.actionRow("Manage rooms and groups", "${host.registry.rooms().size} room(s)") { host.navigate(HomePage.ROOMS) })
        }
        group("9 · Notifications") {
            addView(ui.actionRow("Android notification settings", "Control how JARVIS may notify you") { open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, act.packageName)) })
        }
        group("10 · Security and Permissions") {
            addView(ui.text("Device passwords are kept in encrypted storage on this phone and are never synced or backed up. Remote commands expire after at most 10 minutes and run once.", 12f, ui.muted))
            addView(ui.actionRow("App permissions", null) { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${act.packageName}"))) })
        }
        group("11 · Diagnostics") {
            val fails = host.registry.activity().filter { !it.success }.take(3)
            addView(ui.text("${devices.size} devices · ${devices.count { it.status == DeviceStatus.ONLINE }} online · ${devices.count { it.status == DeviceStatus.OFFLINE }} offline · ${devices.count { it.status == DeviceStatus.UNKNOWN }} not checked", 12f, ui.muted))
            fails.forEach { addView(ui.text("✗ ${DateUtils.getRelativeTimeSpanString(it.timeMillis)}: ${it.summary}", 12f, ui.warning)) }
            addView(ui.actionRow("Open Device Activity", null) { host.navigate(HomePage.ACTIVITY) })
        }
        group("12 · Backup and Restore") {
            addView(ui.text("A backup holds devices, rooms, groups and routines, without passwords. It is copied to the clipboard.", 12f, ui.muted))
            addView(ui.actionRow("Copy backup", null) {
                val json = JSONObject().put("registry", JSONObject(host.registry.exportState())).put("automations", org.json.JSONArray(host.automationStore.exportJson())).toString()
                (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("JARVIS Home backup", json))
                host.toast("Backup copied.")
            })
            addView(ui.actionRow("Restore from clipboard", "Replaces what you have now") {
                val clip = (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                host.confirm("Restore backup?", "This replaces your current devices, rooms, groups and routines.", "Restore") {
                    val ok = runCatching {
                        val o = JSONObject(clip)
                        val a = host.automationStore.importJson(o.getJSONArray("automations").toString())
                        a && host.registry.importState(o.getJSONObject("registry").toString())
                    }.getOrDefault(false)
                    if (ok) (act as? HomeActivity)?.reschedule()
                    host.toast(if (ok) "Restored." else "The clipboard does not contain a JARVIS Home backup.")
                    host.rerender()
                }
            })
        }
    }
}
