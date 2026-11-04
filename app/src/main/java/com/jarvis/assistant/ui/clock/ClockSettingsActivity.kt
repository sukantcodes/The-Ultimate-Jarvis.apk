package com.jarvis.assistant.ui.clock

import android.content.Intent
import android.widget.LinearLayout
import com.jarvis.assistant.R
import com.jarvis.assistant.clock.ClockFormat

/** Settings > Clock: the front page with the next alarm and the four Clock pages. */
class ClockSettingsActivity : ClockPageActivity() {

    override val titleRes = R.string.clock_title
    override val subtitleRes = R.string.clock_subtitle

    override fun buildContent(container: LinearLayout) {
        addNotices(container)

        val settings = engine.settings()
        val next = engine.nextAlarm()
        val summary = card()
        summary.addView(text(getString(R.string.clock_next_alarm), 13f, primary = false))
        if (next == null) {
            summary.addView(text(getString(R.string.clock_next_alarm_none), 16f, primary = true, bold = true).apply { setPadding(0, dp(4), 0, 0) })
        } else {
            summary.addView(text(ClockFormat.whenText(this, next.second, settings), 24f, primary = true, bold = true).apply { setPadding(0, dp(4), 0, 0) })
            summary.addView(text(ClockFormat.title(this, next.first) + " · " + ClockFormat.timeUntil(this, next.second), 13f, primary = false))
        }
        container.addView(summary)

        val pages = card()
        val rows = listOf(
            Triple(R.string.clock_alarms_title, R.string.clock_alarms_subtitle, AlarmsActivity::class.java),
            Triple(R.string.clock_tones_title, R.string.clock_tones_subtitle, AlarmTonesActivity::class.java),
            Triple(R.string.clock_display_title, R.string.clock_display_subtitle, ClockDisplayActivity::class.java),
            Triple(R.string.clock_behaviour_title, R.string.clock_behaviour_subtitle, AlarmBehaviourActivity::class.java)
        )
        rows.forEachIndexed { index, (title, subtitle, target) ->
            if (index > 0) pages.addView(divider())
            pages.addView(actionRow(getString(title), getString(subtitle)) { startActivity(Intent(this, target)) })
        }
        container.addView(pages)
    }
}
