package com.jarvis.assistant.ui.clock

import android.app.DatePickerDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.TimePicker
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.R
import com.jarvis.assistant.clock.AlarmItem
import com.jarvis.assistant.clock.AlarmPatch
import com.jarvis.assistant.clock.AlarmDraft
import com.jarvis.assistant.clock.ClockEngine
import com.jarvis.assistant.clock.ClockFormat
import com.jarvis.assistant.clock.ClockResult
import com.jarvis.assistant.clock.ClockSettings
import com.jarvis.assistant.clock.ToneCatalog
import com.jarvis.assistant.clock.ToneChoice
import com.jarvis.assistant.clock.ToneMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Settings > Clock > Alarms: the real alarm list, with add, edit, switch on/off and delete. Everything goes through [ClockEngine]. */
class AlarmsActivity : ClockPageActivity() {

    private var searchQuery: String = ""
    private var alarmFilter: AlarmFilter = AlarmFilter.ALL
    private var alarmListContainer: LinearLayout? = null
    private var alarmListHeader: TextView? = null
    private enum class AlarmFilter { ALL, ON, OFF }

    override val titleRes = R.string.clock_alarms_title
    override val subtitleRes = R.string.clock_alarms_subtitle

    override fun buildContent(container: LinearLayout) {
        val settings = engine.settings()
        addNotices(container)

        val allAlarms = engine.alarms().sortedWith(compareBy({ it.hour }, { it.minute }, { it.id }))
        val enabledCount = allAlarms.count { it.enabled }
        val disabledCount = allAlarms.size - enabledCount

        if (settings.showNextAlarmBanner) container.addView(nextAlarmCard())

        val summary = card().apply {
            addView(text(getString(R.string.clock_alarm_overview), 13f, primary = false))
            addView(text(getString(R.string.clock_alarm_count, allAlarms.size), 25f, primary = true, bold = true).apply {
                setPadding(0, dp(3), 0, dp(8))
            })
            val stats = LinearLayout(this@AlarmsActivity).apply { orientation = LinearLayout.HORIZONTAL }
            stats.addView(statPill(getString(R.string.clock_alarm_active_count, enabledCount), true), LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(6) })
            stats.addView(statPill(getString(R.string.clock_alarm_off_count, disabledCount), false), LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(6) })
            addView(stats)
        }
        container.addView(summary)

        container.addView(button("＋  " + getString(R.string.clock_add_alarm)) { openEditor(null) }.apply {
            (layoutParams as LinearLayout.LayoutParams).apply { marginStart = 0; bottomMargin = dp(14); height = dp(52) }
            setTextColor(themeColor(R.attr.themeTextPrimary, 0xFFFFFFFF.toInt()))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(themeColor(R.attr.themeAccent, 0xFF4F8CFF.toInt()))
            }
        })

        val search = EditText(this).apply {
            hint = getString(R.string.clock_search_alarms)
            setSingleLine(true)
            textSize = 14f
            setTextColor(themeColor(R.attr.themeTextPrimary, 0xFFFFFFFF.toInt()))
            setHintTextColor(themeColor(R.attr.themeTextMuted, 0xFF9AA4B2.toInt()))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(themeColor(R.attr.themeCardBg, 0x22FFFFFF))
                setStroke(dp(1), themeColor(R.attr.themeCardBorder, 0x33FFFFFF))
            }
            setText(searchQuery)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    searchQuery = s?.toString().orEmpty()
                    renderAlarmList(engine.settings())
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        container.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        })

        val filters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        listOf(
            AlarmFilter.ALL to getString(R.string.clock_filter_all),
            AlarmFilter.ON to getString(R.string.clock_filter_on),
            AlarmFilter.OFF to getString(R.string.clock_filter_off)
        ).forEach { (filter, label) ->
            val selected = alarmFilter == filter
            filters.addView(button(label, { alarmFilter = filter; render() }).apply {
                setTextColor(if (selected) 0xFFFFFFFF.toInt() else themeColor(R.attr.themeTextPrimary, 0xFFFFFFFF.toInt()))
                background = GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    setColor(if (selected) themeColor(R.attr.themeAccent, 0xFF4F8CFF.toInt()) else themeColor(R.attr.themeChipBg, 0x22FFFFFF))
                }
                (layoutParams as LinearLayout.LayoutParams).apply { marginStart = 0; marginEnd = dp(8) }
            }, LinearLayout.LayoutParams(0, dp(40), 1f))
        }
        container.addView(filters)

        alarmListHeader = text("", 16f, primary = true, bold = true).apply {
            setPadding(0, dp(18), 0, dp(10))
        }
        container.addView(alarmListHeader)
        alarmListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(alarmListContainer)
        renderAlarmList(settings)
    }

    private fun renderAlarmList(settings: ClockSettings) {
        val target = alarmListContainer ?: return
        val allAlarms = engine.alarms()
        val alarms = allAlarms.sortedWith(compareBy({ it.hour }, { it.minute }, { it.id })).filter { alarm ->
            (when (alarmFilter) {
                AlarmFilter.ALL -> true
                AlarmFilter.ON -> alarm.enabled
                AlarmFilter.OFF -> !alarm.enabled
            }) && (searchQuery.isBlank() ||
                ClockFormat.title(this, alarm).contains(searchQuery, ignoreCase = true) ||
                ClockFormat.time(this, alarm.hour, alarm.minute, settings).contains(searchQuery, ignoreCase = true) ||
                ClockFormat.repeatSummary(this, alarm, settings).contains(searchQuery, ignoreCase = true))
        }
        alarmListHeader?.text = getString(R.string.clock_alarm_list_heading, alarms.size)
        target.removeAllViews()
        when {
            allAlarms.isEmpty() -> target.addView(emptyAlarmsCard())
            alarms.isEmpty() -> target.addView(card().apply {
                addView(text(getString(R.string.clock_alarm_no_match), 14f, primary = false))
            })
            else -> alarms.forEach { target.addView(alarmCard(it, settings)) }
        }
    }

    private fun statPill(label: String, active: Boolean): View = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 12f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setTextColor(themeColor(R.attr.themeTextPrimary, 0xFFFFFFFF.toInt()))
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(if (active) themeColor(R.attr.themeAccent, 0xFF4F8CFF.toInt()) else themeColor(R.attr.themeChipBg, 0x22FFFFFF))
        }
    }

    private fun emptyAlarmsCard(): View = card().apply {
        addView(text("◷", 34f, primary = true, bold = true))
        addView(text(getString(R.string.clock_no_alarms), 15f, primary = true, bold = true).apply { setPadding(0, dp(6), 0, dp(4)) })
        addView(text(getString(R.string.clock_empty_alarm_hint), 13f, primary = false))
        addView(button(getString(R.string.clock_add_alarm)) { openEditor(null) }.apply {
            (layoutParams as LinearLayout.LayoutParams).apply { marginStart = 0; topMargin = dp(12) }
        })
    }

    private fun nextAlarmCard(): View {
        val card = card().apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(themeColor(R.attr.themeAccent, 0xFF4F8CFF.toInt()), themeColor(R.attr.themeCardBg, 0xFF20283A.toInt()))).apply {
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), themeColor(R.attr.themeCardBorder, 0x33FFFFFF))
            }
        }
        val next = engine.nextAlarm()
        card.addView(text("◷  " + getString(R.string.clock_next_alarm), 13f, primary = false))
        if (next == null) {
            card.addView(text(getString(R.string.clock_next_alarm_none), 19f, primary = true, bold = true).apply { setPadding(0, dp(8), 0, dp(2)) })
            card.addView(text(getString(R.string.clock_next_alarm_hint), 12f, primary = false))
        } else {
            val (alarm, at) = next
            val settings = engine.settings()
            card.addView(text(ClockFormat.time(this, alarm.hour, alarm.minute, settings), 38f, primary = true, bold = true).apply { setPadding(0, dp(6), 0, 0) })
            card.addView(text(ClockFormat.whenText(this, at, settings) + " · " + ClockFormat.timeUntil(this, at), 13f, primary = false))
            card.addView(text(ClockFormat.title(this, alarm) + " · " + ClockFormat.repeatSummary(this, alarm, settings), 12f, primary = false).apply { setPadding(0, dp(5), 0, 0) })
        }
        return card
    }

    private fun alarmCard(alarm: AlarmItem, settings: ClockSettings): View {
        val card = card().apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(themeColor(R.attr.themeCardBg, 0x33FFFFFF))
                setStroke(dp(1), if (alarm.enabled) themeColor(R.attr.themeAccent, 0x665F8FFF) else themeColor(R.attr.themeCardBorder, 0x33FFFFFF))
            }
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            isClickable = true
            isFocusable = true
            setOnClickListener { openEditor(alarm) }
        }
        val timeView = text(ClockFormat.time(this, alarm.hour, alarm.minute, settings), 36f, primary = alarm.enabled, bold = true)
        info.addView(timeView)
        info.addView(text(ClockFormat.title(this, alarm), 14f, primary = true, bold = true).apply { setPadding(0, dp(1), 0, 0) })
        info.addView(text(ClockFormat.repeatSummary(this, alarm, settings), 12f, primary = false))
        info.addView(text(ToneCatalog.describe(this, ToneChoice.of(alarm)) + if (alarm.vibrate) " · " + getString(R.string.clock_vibrate_short) else "", 12f, primary = false))
        if (alarm.snoozedUntilMillis > System.currentTimeMillis()) {
            val zdt = java.time.ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(alarm.snoozedUntilMillis), java.time.ZoneId.systemDefault())
            info.addView(text(getString(R.string.clock_snoozed_until, ClockFormat.time(this, zdt.hour, zdt.minute, settings)), 12f, primary = false))
        }
        top.addView(info)

        val toggle = androidx.appcompat.widget.SwitchCompat(this).apply {
            isChecked = alarm.enabled
            contentDescription = getString(R.string.clock_alarm_switch_desc, ClockFormat.title(this@AlarmsActivity, alarm))
            setOnCheckedChangeListener { _, on ->
                when (val r = engine.setEnabled(alarm.id, on)) {
                    is ClockResult.Ok -> toastScheduled(if (on) r.nextRingMillis else null).also { if (!on) Toast.makeText(this@AlarmsActivity, R.string.clock_alarm_off_toast, Toast.LENGTH_SHORT).show() }
                    is ClockResult.Failed -> { showFailure(r); render() }
                }
            }
        }
        top.addView(toggle)
        card.addView(top)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        actions.addView(button(getString(R.string.clock_edit)) { openEditor(alarm) }.apply { (layoutParams as LinearLayout.LayoutParams).marginStart = 0 })
        actions.addView(button(getString(R.string.clock_delete)) { confirmDelete(alarm) })
        card.addView(actions)
        return card
    }

    private fun confirmDelete(alarm: AlarmItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clock_delete_title)
            .setMessage(getString(R.string.clock_delete_message, ClockFormat.title(this, alarm), ClockFormat.time(this, alarm.hour, alarm.minute, engine.settings())))
            .setPositiveButton(R.string.clock_delete) { _, _ ->
                when (val r = engine.delete(alarm.id)) {
                    is ClockResult.Ok -> Toast.makeText(this, R.string.clock_deleted_toast, Toast.LENGTH_SHORT).show()
                    is ClockResult.Failed -> showFailure(r)
                }
            }
            .setNegativeButton(R.string.clock_cancel, null)
            .show()
    }

    // ---- Editor ---------------------------------------------------------------------------------

    private fun openEditor(existing: AlarmItem?) {
        val settings = engine.settings()
        var days: Set<DayOfWeek> = existing?.repeatDays ?: emptySet()
        var date: LocalDate? = existing?.oneTimeDate
        var tone: ToneChoice = if (existing != null) ToneChoice.of(existing) else ToneChoice.ofDefault(settings)
        var vibrate = existing?.vibrate ?: settings.defaultVibrate
        var snooze = existing?.snoozeMinutes ?: settings.defaultSnoozeMinutes

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val picker = TimePicker(this).apply {
            setIs24HourView(ClockFormat.is24Hour(this@AlarmsActivity, settings))
            hour = existing?.hour ?: java.time.LocalTime.now().plusMinutes(1).hour
            minute = existing?.minute ?: java.time.LocalTime.now().plusMinutes(1).minute
        }
        body.addView(picker)

        val label = EditText(this).apply {
            hint = getString(R.string.clock_label_hint)
            setText(existing?.label ?: "")
            setSingleLine()
            filters = arrayOf(InputFilter.LengthFilter(ClockEngine.MAX_LABEL))
            setTextColor(themeColor(R.attr.themeTextPrimary, 0xFFFFFFFF.toInt()))
            setHintTextColor(themeColor(R.attr.themeTextMuted, 0xFF9AA4B2.toInt()))
        }
        body.addView(label)

        body.addView(text(getString(R.string.clock_repeat), 13f, primary = false).apply { setPadding(0, dp(14), 0, dp(6)) })
        val dayRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val dateRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val dateText = text("", 14f, primary = true)
        fun refreshDate() {
            dateRow.visibility = if (days.isEmpty()) View.VISIBLE else View.GONE
            dateText.text = date?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) ?: getString(R.string.clock_date_next)
        }
        val dayViews = HashMap<DayOfWeek, TextView>()
        fun styleDay(v: TextView, on: Boolean) {
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (on) themeColor(R.attr.themeAccent, 0xFF4F8CFF.toInt()) else themeColor(R.attr.themeChipBg, 0x22FFFFFF))
            }
            v.setTextColor(if (on) 0xFFFFFFFF.toInt() else themeColor(R.attr.themeTextPrimary, 0xFFFFFFFF.toInt()))
        }
        ClockFormat.orderedDays(settings).forEach { d ->
            val v = TextView(this).apply {
                text = ClockFormat.dayShort(d).take(2)
                contentDescription = d.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(4) }
                isClickable = true
                isFocusable = true
            }
            styleDay(v, d in days)
            v.setOnClickListener {
                days = if (d in days) days - d else days + d
                styleDay(v, d in days)
                refreshDate()
            }
            dayViews[d] = v
            dayRow.addView(v)
        }
        body.addView(dayRow)

        dateRow.addView(text(getString(R.string.clock_date), 13f, primary = false).apply { setPadding(0, 0, dp(8), 0) })
        dateRow.addView(dateText)
        dateRow.addView(button(getString(R.string.clock_pick_date)) {
            val base = date ?: LocalDate.now()
            DatePickerDialog(this, { _, y, m, d -> date = LocalDate.of(y, m + 1, d); refreshDate() }, base.year, base.monthValue - 1, base.dayOfMonth).apply {
                datePicker.minDate = System.currentTimeMillis() - 1000
            }.show()
        })
        dateRow.addView(button(getString(R.string.clock_clear)) { date = null; refreshDate() })
        dateRow.setPadding(0, dp(8), 0, 0)
        body.addView(dateRow)
        refreshDate()

        val toneText = text("", 14f, primary = true)
        fun refreshTone() { toneText.text = ToneCatalog.describe(this, tone) }
        refreshTone()
        val toneRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(6))
            isClickable = true
            isFocusable = true
        }
        toneRow.addView(text(getString(R.string.clock_tone), 13f, primary = false).apply { setPadding(0, 0, dp(8), 0) })
        toneRow.addView(toneText.apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
        toneRow.addView(text("›", 22f, primary = false))
        toneRow.setOnClickListener { ToneChooser.show(this, tone) { tone = it; refreshTone() } }
        body.addView(toneRow)

        body.addView(switchRow(getString(R.string.clock_vibrate), null, vibrate) { vibrate = it })

        val snoozeText = text("", 14f, primary = true)
        fun refreshSnooze() { snoozeText.text = getString(R.string.clock_minutes_fmt, snooze) }
        refreshSnooze()
        val snoozeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(6))
            isClickable = true
            isFocusable = true
        }
        snoozeRow.addView(text(getString(R.string.clock_snooze_length), 13f, primary = false).apply { setPadding(0, 0, dp(8), 0) })
        snoozeRow.addView(snoozeText.apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
        snoozeRow.addView(text("›", 22f, primary = false))
        snoozeRow.setOnClickListener {
            val options = SNOOZE_CHOICES.let { if (snooze in it) it else (it + snooze).sorted() }
            chooseOne(R.string.clock_snooze_length, options.map { getString(R.string.clock_minutes_fmt, it) }, options.indexOf(snooze)) {
                snooze = options[it]; refreshSnooze()
            }
        }
        body.addView(snoozeRow)

        val scroll = android.widget.ScrollView(this).apply { addView(body) }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) R.string.clock_new_alarm else R.string.clock_edit_alarm)
            .setView(scroll)
            .setPositiveButton(R.string.clock_save, null)
            .setNegativeButton(R.string.clock_cancel, null)
            .create()
        dialog.show()
        // The dialog stays open when saving fails, so nothing the person typed is lost.
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            picker.clearFocus()
            val h = picker.hour
            val m = picker.minute
            val name = label.text.toString()
            val result: ClockResult<AlarmItem> = if (existing == null) {
                engine.create(AlarmDraft(
                    hour = h, minute = m, label = name, repeatDays = days, oneTimeDate = if (days.isEmpty()) date else null,
                    toneMode = tone.mode, toneUri = tone.uri, toneTitle = tone.title, vibrate = vibrate, snoozeMinutes = snooze
                ))
            } else {
                engine.update(existing.id, AlarmPatch(
                    hour = h, minute = m, label = name, repeatDays = days,
                    oneTimeDate = if (days.isEmpty()) date else null, clearOneTimeDate = days.isEmpty() && date == null,
                    toneMode = tone.mode, toneUri = tone.uri, toneTitle = tone.title, vibrate = vibrate, snoozeMinutes = snooze
                ))
            }
            when (result) {
                is ClockResult.Ok -> { dialog.dismiss(); toastScheduled(result.nextRingMillis) }
                is ClockResult.Failed -> showFailure(result)
            }
        }
    }

    private companion object {
        val SNOOZE_CHOICES = listOf(1, 2, 3, 5, 10, 15, 20, 30, 45, 60)
    }
}
