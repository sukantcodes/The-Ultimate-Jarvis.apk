package com.jarvis.assistant.ui.jarvishome

import android.app.TimePickerDialog
import android.text.format.DateUtils
import android.view.ViewGroup
import android.widget.LinearLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.home.Capability
import com.jarvis.assistant.home.DeviceAction
import com.jarvis.assistant.home.DeviceActionType
import com.jarvis.assistant.home.automation.Automation
import com.jarvis.assistant.home.automation.AutomationStep
import com.jarvis.assistant.home.automation.Condition
import com.jarvis.assistant.home.automation.Trigger
import java.time.DayOfWeek
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

/** Automations and Smart Scenes: create, edit, enable/pause, run now, delete, and see what really happened on the last run. */
object AutomationPages {

    fun render(host: HomeHost, c: LinearLayout, scenes: Boolean) {
        val ui = host.ui
        val all = host.automationStore.automations().filter { it.isScene == scenes }
        c.addView(ui.text(if (scenes) "One tap, many devices" else "Routines for your connected devices", 13f, ui.muted).apply { setPadding(ui.dp(4), 0, 0, ui.dp(8)) })
        c.addView(LinearLayout(host.activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(ui.button(if (scenes) "＋ Create scene" else "＋ Create automation", true) { edit(host, Automation(name = "", isScene = scenes, enabled = true), isNew = true) })
            if (scenes) addView(ui.button("From template") { template(host) })
        })
        if (all.isEmpty()) {
            c.addView(ui.notice(if (scenes) "No scenes yet" else "No automations yet",
                if (scenes) "Templates such as Good Night start empty on purpose: you choose which of your devices do what, so nothing runs on a guess."
                else "Create a routine with a time, a device-state trigger or a manual run, optional conditions and one or more steps."))
        }
        all.forEach { a -> c.addView(card(host, a)) }

        if (!scenes) {
            val runs = host.automationStore.runs().take(8)
            if (runs.isNotEmpty()) {
                c.addView(DevicePages.section(host, "Recent execution history", null) {})
                runs.forEach { r ->
                    c.addView(ui.text("${if (r.success) "✓" else "✗"} ${r.name} · ${DateUtils.getRelativeTimeSpanString(r.timeMillis)}\n${r.summary}", 12f, if (r.success) ui.muted else ui.warning).apply { setPadding(ui.dp(4), ui.dp(2), 0, ui.dp(6)) })
                }
            }
        }
    }

    private fun card(host: HomeHost, a: Automation): LinearLayout {
        val ui = host.ui
        val card = ui.glassCard()
        val header = LinearLayout(host.activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        header.addView(LinearLayout(host.activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(ui.text(a.name, 18f, bold = true))
            addView(ui.text(describeTrigger(a) + " · ${a.steps.size} step(s)", 12f, ui.muted))
        })
        if (!a.isScene) {
            header.addView(ui.bubbleSwitch(a.enabled) { v ->
                host.automationStore.save(a.copy(enabled = v)); (host.activity as? HomeActivity)?.reschedule()
            }.apply { contentDescription = "${a.name} enabled" })
        }
        card.addView(header)
        if (!a.isConfigured) card.addView(ui.text("Not configured: choose devices and actions before this can run.", 12f, ui.warning).apply { setPadding(0, ui.dp(6), 0, 0) })
        host.automationStore.lastRun(a.id)?.let { r ->
            card.addView(ui.text("Last run ${DateUtils.getRelativeTimeSpanString(r.timeMillis)}: ${r.summary}", 12f, if (r.success) ui.muted else ui.warning).apply { setPadding(0, ui.dp(6), 0, 0) })
        }
        card.addView(LinearLayout(host.activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(ui.button(if (a.isScene) "Activate" else "Run now", true) {
                host.toast("Running ${a.name}…")
                host.background({ host.engine.run(a.id) }) { r -> host.toast("${r.name}: ${r.summary}") }
            })
            addView(ui.button("Edit") { edit(host, a, isNew = false) })
            addView(ui.button("Delete") { host.confirm("Delete ${a.name}?", "This removes the routine and its schedule.", "Delete") { host.automationStore.delete(a.id); (host.activity as? HomeActivity)?.reschedule(); host.rerender() } })
        })
        return card
    }

    private fun describeTrigger(a: Automation): String = when (val t = a.trigger) {
        is Trigger.Manual -> "Manual"
        is Trigger.Time -> "At ${"%02d:%02d".format(t.hour, t.minute)} " + (if (!t.repeat) "once" else if (t.days.isEmpty()) "daily" else t.days.sorted().joinToString(" ") { DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, Locale.getDefault()) })
        is Trigger.DeviceState -> "When a device turns ${if (t.becomesOn) "on" else "off"}"
    }

    private fun template(host: HomeHost) {
        host.chooseOne("Scene template", Automation.TEMPLATES.map { it.second }) { i ->
            val (key, name) = Automation.TEMPLATES[i]
            edit(host, Automation(name = name, isScene = true, templateKey = key), isNew = true)
        }
    }

    /** One editing sheet: name, trigger, conditions, steps. Saving requires at least one real step. */
    private fun edit(host: HomeHost, original: Automation, isNew: Boolean) {
        val ui = host.ui
        var a = original
        val body = LinearLayout(host.activity).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8)) }
        val scroll = android.widget.ScrollView(host.activity).apply { addView(body) }
        lateinit var dialog: androidx.appcompat.app.AlertDialog

        fun redraw() {
            body.removeAllViews()
            body.addView(ui.actionRow("Name", a.name.ifBlank { "Tap to set" }) { host.promptText("Name", a.name, "e.g. Good Night") { n -> a = a.copy(name = n.take(40)); redraw() } })
            if (!a.isScene) {
                body.addView(ui.actionRow("Trigger", describeTrigger(a)) { pickTrigger(host, a) { t -> a = a.copy(trigger = t); redraw() } })
            }
            body.addView(ui.divider())
            body.addView(ui.text("Only if (all must be true)", 13f, ui.muted).apply { setPadding(0, ui.dp(8), 0, ui.dp(2)) })
            a.conditions.forEachIndexed { i, cnd ->
                val dev = host.registry.device(cnd.deviceId)?.name ?: "Removed device"
                body.addView(ui.actionRow("$dev is ${if (cnd.poweredOn == true) "on" else "off"}", "Tap to remove") { a = a.copy(conditions = a.conditions.filterIndexed { j, _ -> j != i }); redraw() })
            }
            body.addView(ui.button("＋ Condition") { pickDevice(host, "Condition device", requirePower = true) { d -> host.chooseOne("${d.name} is…", listOf("On", "Off")) { w -> a = a.copy(conditions = a.conditions + Condition(d.id, poweredOn = w == 0)); redraw() } } })
            body.addView(ui.divider())
            body.addView(ui.text("Steps (run in order)", 13f, ui.muted).apply { setPadding(0, ui.dp(8), 0, ui.dp(2)) })
            a.steps.forEachIndexed { i, s ->
                body.addView(ui.actionRow("${i + 1}. ${describeStep(host, s)}", "Tap to remove") { a = a.copy(steps = a.steps.filterIndexed { j, _ -> j != i }); redraw() })
            }
            body.addView(ui.button("＋ Step") { addStep(host) { s -> a = a.copy(steps = a.steps + s); redraw() } })
            body.addView(ui.text(if (a.isConfigured) "" else "Add at least one step before this can run.", 12f, ui.warning))
        }
        redraw()

        dialog = MaterialAlertDialogBuilder(host.activity, com.jarvis.assistant.R.style.ThemeOverlay_Jarvis_MaterialDialog)
            .setTitle(if (isNew) (if (a.isScene) "New scene" else "New automation") else "Edit ${original.name}")
            .setView(scroll)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                when {
                    a.name.isBlank() -> host.toast("Give it a name first.")
                    !a.isConfigured -> host.toast("Add at least one step first.")
                    else -> { host.automationStore.save(a); (host.activity as? HomeActivity)?.reschedule(); dialog.dismiss(); host.rerender() }
                }
            }
        }
        dialog.show()
    }

    private fun describeStep(host: HomeHost, s: AutomationStep): String {
        val target = s.deviceId?.let { host.registry.device(it)?.name } ?: s.groupId?.let { id -> host.registry.groups().firstOrNull { it.id == id }?.name?.let { "group $it" } }
            ?: s.roomId?.let { id -> host.registry.room(id)?.name?.let { "room $it" } } ?: "Missing target"
        val what = when (s.action.type) { DeviceActionType.POWER_ON -> "Turn on"; DeviceActionType.POWER_OFF -> "Turn off"; DeviceActionType.TOGGLE -> "Toggle"; DeviceActionType.SET_BRIGHTNESS -> "Brightness ${s.action.value}%"; else -> s.action.type.name.lowercase() }
        return "$what · $target" + if (s.delaySeconds > 0) " (after ${s.delaySeconds}s)" else ""
    }

    private fun pickDevice(host: HomeHost, title: String, requirePower: Boolean, onPick: (com.jarvis.assistant.home.HomeDevice) -> Unit) {
        val devices = host.registry.devices().filter { !requirePower || it.supports(Capability.POWER) }
        if (devices.isEmpty()) { host.toast("No registered device supports power control yet."); return }
        host.chooseOne(title, devices.map { it.name }) { onPick(devices[it]) }
    }

    private fun addStep(host: HomeHost, onAdd: (AutomationStep) -> Unit) {
        host.chooseOne("Step target", listOf("A device", "A room", "A group")) { kind ->
            fun pickAction(deviceId: String?, groupId: String?, roomId: String?) {
                host.chooseOne("Action", listOf("Turn on", "Turn off")) { w ->
                    val type = if (w == 0) DeviceActionType.POWER_ON else DeviceActionType.POWER_OFF
                    host.chooseOne("Wait before this step?", listOf("No wait", "5 seconds", "30 seconds", "5 minutes", "30 minutes")) { d ->
                        val delay = intArrayOf(0, 5, 30, 300, 1800)[d]
                        onAdd(AutomationStep(deviceId, groupId, roomId, DeviceAction(type), delay))
                    }
                }
            }
            when (kind) {
                0 -> pickDevice(host, "Device", requirePower = true) { pickAction(it.id, null, null) }
                1 -> { val rooms = host.registry.rooms(); if (rooms.isEmpty()) host.toast("Create a room first.") else host.chooseOne("Room", rooms.map { it.name }) { pickAction(null, null, rooms[it].id) } }
                else -> { val groups = host.registry.groups(); if (groups.isEmpty()) host.toast("Create a group first (Rooms page).") else host.chooseOne("Group", groups.map { it.name }) { pickAction(null, groups[it].id, null) } }
            }
        }
    }

    private fun pickTrigger(host: HomeHost, a: Automation, onPick: (Trigger) -> Unit) {
        host.chooseOne("Trigger", listOf("Manual only", "At a time (daily)", "At a time (chosen days)", "At a time (once)", "When a device turns on/off")) { w ->
            fun time(days: Set<Int>, repeat: Boolean) {
                val now = ZonedDateTime.now()
                TimePickerDialog(host.activity, { _, h, m -> onPick(Trigger.Time(h, m, days, repeat)) }, now.hour, now.minute, android.text.format.DateFormat.is24HourFormat(host.activity)).show()
            }
            when (w) {
                0 -> onPick(Trigger.Manual)
                1 -> time(emptySet(), true)
                2 -> {
                    val names = DayOfWeek.values().map { it.getDisplayName(TextStyle.FULL, Locale.getDefault()) }
                    val checked = BooleanArray(7)
                    MaterialAlertDialogBuilder(host.activity, com.jarvis.assistant.R.style.ThemeOverlay_Jarvis_MaterialDialog)
                        .setTitle("Days").setMultiChoiceItems(names.toTypedArray(), checked) { _, i, v -> checked[i] = v }
                        .setPositiveButton("Next") { _, _ -> val days = (0..6).filter { checked[it] }.map { it + 1 }.toSet(); if (days.isEmpty()) host.toast("Pick at least one day.") else time(days, true) }
                        .setNegativeButton("Cancel", null).show()
                }
                3 -> time(emptySet(), false)
                else -> pickDevice(host, "When this device…", requirePower = true) { d ->
                    if (!d.supports(Capability.READ_STATE)) { host.toast("${d.name} does not report its state, so it cannot trigger a routine."); return@pickDevice }
                    host.chooseOne("${d.name} turns…", listOf("On", "Off")) { s -> onPick(Trigger.DeviceState(d.id, s == 0)) }
                }
            }
        }
    }
}

