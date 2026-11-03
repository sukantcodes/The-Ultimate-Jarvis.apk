package com.jarvis.assistant.ui.clock

import android.widget.LinearLayout
import com.jarvis.assistant.R
import com.jarvis.assistant.clock.ClockFormat
import com.jarvis.assistant.clock.TimeFormat
import java.time.DayOfWeek
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

/** Settings > Clock > Clock Display Settings: how times and days are shown on every Clock screen, the alarm list and the ring screen. */
class ClockDisplayActivity : ClockPageActivity() {

    override val titleRes = R.string.clock_display_title
    override val subtitleRes = R.string.clock_display_subtitle

    override fun buildContent(container: LinearLayout) {
        val s = engine.settings()

        val card = card()
        val formats = listOf(TimeFormat.SYSTEM, TimeFormat.H12, TimeFormat.H24)
        val formatNames = listOf(R.string.clock_format_system, R.string.clock_format_12, R.string.clock_format_24).map { getString(it) }
        card.addView(actionRow(getString(R.string.clock_time_format), formatNames[formats.indexOf(s.timeFormat)]) {
            chooseOne(R.string.clock_time_format, formatNames, formats.indexOf(s.timeFormat)) { i ->
                engine.updateSettings { it.copy(timeFormat = formats[i]) }
            }
        })
        val now = ZonedDateTime.now()
        card.addView(text(getString(R.string.clock_preview_now, ClockFormat.time(this, now.hour, now.minute, s)), 12f, primary = false).apply { setPadding(0, 0, 0, dp(6)) })
        card.addView(divider())

        val starts: List<DayOfWeek?> = listOf(null, DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.SATURDAY)
        val startNames = starts.map { it?.getDisplayName(TextStyle.FULL, Locale.getDefault()) ?: getString(R.string.clock_week_start_system) }
        card.addView(actionRow(getString(R.string.clock_week_start), startNames[starts.indexOf(s.weekStart).coerceAtLeast(0)]) {
            chooseOne(R.string.clock_week_start, startNames, starts.indexOf(s.weekStart).coerceAtLeast(0)) { i ->
                engine.updateSettings { it.copy(weekStart = starts[i]) }
            }
        })
        card.addView(divider())
        card.addView(switchRow(getString(R.string.clock_show_next_banner), getString(R.string.clock_show_next_banner_hint), s.showNextAlarmBanner) { on ->
            engine.updateSettings { it.copy(showNextAlarmBanner = on) }
        })
        card.addView(divider())
        card.addView(switchRow(getString(R.string.clock_show_ring_date), getString(R.string.clock_show_ring_date_hint), s.showRingScreenDate) { on ->
            engine.updateSettings { it.copy(showRingScreenDate = on) }
        })
        container.addView(card)
    }
}
