package com.jarvis.assistant.ui.clock

import android.widget.LinearLayout
import com.jarvis.assistant.R
import com.jarvis.assistant.clock.ClockSettings
import com.jarvis.assistant.clock.VolumeKeyAction

/** Settings > Clock > Alarm Behaviour: defaults for new alarms and how a ringing alarm acts. Every value is stored and used by the ring service. */
class AlarmBehaviourActivity : ClockPageActivity() {

    override val titleRes = R.string.clock_behaviour_title
    override val subtitleRes = R.string.clock_behaviour_subtitle

    override fun buildContent(container: LinearLayout) {
        val s = engine.settings()
        addNotices(container)

        val newAlarms = card()
        newAlarms.addView(sectionTitle(getString(R.string.clock_new_alarm_defaults), getString(R.string.clock_new_alarm_defaults_hint)))
        newAlarms.addView(switchRow(getString(R.string.clock_vibrate), getString(R.string.clock_default_vibrate_hint), s.defaultVibrate) { on ->
            engine.updateSettings { it.copy(defaultVibrate = on) }
        })
        newAlarms.addView(divider())
        val snoozeOptions = listOf(1, 2, 3, 5, 10, 15, 20, 30, 45, 60).let { if (s.defaultSnoozeMinutes in it) it else (it + s.defaultSnoozeMinutes).sorted() }
        newAlarms.addView(actionRow(getString(R.string.clock_snooze_length), getString(R.string.clock_minutes_fmt, s.defaultSnoozeMinutes)) {
            chooseOne(R.string.clock_snooze_length, snoozeOptions.map { getString(R.string.clock_minutes_fmt, it) }, snoozeOptions.indexOf(s.defaultSnoozeMinutes)) { i ->
                engine.updateSettings { it.copy(defaultSnoozeMinutes = snoozeOptions[i].coerceIn(ClockSettings.MIN_SNOOZE, ClockSettings.MAX_SNOOZE)) }
            }
        })
        container.addView(newAlarms)

        val ringing = card()
        ringing.addView(sectionTitle(getString(R.string.clock_when_ringing)))

        val silenceOptions = listOf(0, 1, 5, 10, 15, 20, 30, 60).let { if (s.silenceAfterMinutes in it) it else (it + s.silenceAfterMinutes).sorted() }
        fun silenceLabel(m: Int) = if (m == 0) getString(R.string.clock_never_silence) else getString(R.string.clock_minutes_fmt, m)
        ringing.addView(actionRow(getString(R.string.clock_silence_after), silenceLabel(s.silenceAfterMinutes)) {
            chooseOne(R.string.clock_silence_after, silenceOptions.map { silenceLabel(it) }, silenceOptions.indexOf(s.silenceAfterMinutes)) { i ->
                engine.updateSettings { it.copy(silenceAfterMinutes = silenceOptions[i].coerceIn(0, ClockSettings.MAX_SILENCE_AFTER)) }
            }
        })
        ringing.addView(divider())

        val limitOptions = listOf(0, 1, 2, 3, 5, 10)
        fun limitLabel(n: Int) = if (n == 0) getString(R.string.clock_unlimited) else getString(R.string.clock_times_fmt, n)
        ringing.addView(actionRow(getString(R.string.clock_snooze_limit), limitLabel(s.maxSnoozes)) {
            chooseOne(R.string.clock_snooze_limit, limitOptions.map { limitLabel(it) }, limitOptions.indexOf(s.maxSnoozes).coerceAtLeast(0)) { i ->
                engine.updateSettings { it.copy(maxSnoozes = limitOptions[i].coerceIn(0, ClockSettings.MAX_SNOOZE_LIMIT)) }
            }
        })
        ringing.addView(divider())
        ringing.addView(switchRow(getString(R.string.clock_gradual_volume), getString(R.string.clock_gradual_volume_hint), s.gradualVolume) { on ->
            engine.updateSettings { it.copy(gradualVolume = on) }
        })
        ringing.addView(divider())

        val keyOptions = listOf(VolumeKeyAction.SNOOZE, VolumeKeyAction.DISMISS, VolumeKeyAction.NOTHING)
        val keyNames = listOf(R.string.clock_key_snooze, R.string.clock_key_dismiss, R.string.clock_key_nothing).map { getString(it) }
        ringing.addView(actionRow(getString(R.string.clock_volume_key), keyNames[keyOptions.indexOf(s.volumeKeyAction)]) {
            chooseOne(R.string.clock_volume_key, keyNames, keyOptions.indexOf(s.volumeKeyAction)) { i ->
                engine.updateSettings { it.copy(volumeKeyAction = keyOptions[i]) }
            }
        })
        container.addView(ringing)
    }
}
