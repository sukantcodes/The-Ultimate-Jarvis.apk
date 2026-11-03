package com.jarvis.assistant.ui.clock

import android.widget.LinearLayout
import android.widget.Toast
import com.jarvis.assistant.R
import com.jarvis.assistant.clock.AlarmSoundPlayer
import com.jarvis.assistant.clock.ToneCatalog
import com.jarvis.assistant.clock.ToneChoice
import com.jarvis.assistant.clock.ToneMode

/**
 * Settings > Clock > Alarm Tones: the phone's own alarm sounds. Tap Play to hear one, "Use for new alarms" to make it the
 * default. Each alarm can still pick its own tone in the alarm editor.
 */
class AlarmTonesActivity : ClockPageActivity() {

    override val titleRes = R.string.clock_tones_title
    override val subtitleRes = R.string.clock_tones_subtitle

    private val player by lazy { AlarmSoundPlayer(applicationContext) }

    override fun onStop() {
        player.stop()
        super.onStop()
    }

    override fun buildContent(container: LinearLayout) {
        val settings = engine.settings()
        addNotices(container)

        val current = ToneChoice.ofDefault(settings)
        val defaultCard = card()
        defaultCard.addView(text(getString(R.string.clock_default_tone), 18f, primary = true, bold = true))
        defaultCard.addView(text(getString(R.string.clock_default_tone_hint), 12f, primary = false).apply { setPadding(0, dp(2), 0, dp(4)) })
        defaultCard.addView(actionRow(ToneCatalog.describe(this, current), getString(R.string.clock_tap_to_change)) {
            ToneChooser.show(this, current, R.string.clock_default_tone) { setDefault(it) }
        })
        if (ToneCatalog.isMissing(this, current)) {
            defaultCard.addView(text(getString(R.string.clock_tone_missing_hint), 12f, primary = false))
        }
        container.addView(defaultCard)

        val listCard = card()
        listCard.addView(text(getString(R.string.clock_phone_tones), 18f, primary = true, bold = true))
        listCard.addView(text(getString(R.string.clock_phone_tones_hint), 12f, primary = false).apply { setPadding(0, dp(2), 0, dp(8)) })

        addToneRow(listCard, getString(R.string.clock_tone_phone_default_named, ToneCatalog.phoneDefaultTitle(this)), ToneChoice.PHONE_DEFAULT, current, first = true)
        val tones = ToneCatalog.list(this)
        tones.forEach { addToneRow(listCard, it.title, ToneChoice(ToneMode.CUSTOM_URI, it.uri, it.title), current, first = false) }
        addToneRow(listCard, getString(R.string.clock_tone_vibration_only), ToneChoice.SILENT, current, first = false)
        if (tones.isEmpty()) listCard.addView(text(getString(R.string.clock_no_phone_tones), 12f, primary = false).apply { setPadding(0, dp(8), 0, 0) })
        container.addView(listCard)
    }

    private fun addToneRow(card: LinearLayout, title: String, choice: ToneChoice, current: ToneChoice, first: Boolean) {
        if (!first) card.addView(divider())
        val selected = choice.mode == current.mode && (choice.mode != ToneMode.CUSTOM_URI || choice.uri == current.uri)
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(4)) }
        row.addView(text(title + if (selected) "  ✓" else "", 15f, primary = true, bold = selected))
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
        if (choice.mode != ToneMode.SILENT) {
            buttons.addView(button(getString(R.string.clock_play)) {
                if (!ToneChooser.previewOnce(this, player, choice)) {
                    Toast.makeText(this, R.string.clock_tone_could_not_play, Toast.LENGTH_SHORT).show()
                }
            }.apply { (layoutParams as LinearLayout.LayoutParams).marginStart = 0 })
            buttons.addView(button(getString(R.string.clock_stop)) { player.stop() })
        }
        if (!selected) buttons.addView(button(getString(R.string.clock_use_for_new)) { setDefault(choice) })
        row.addView(buttons)
        card.addView(row)
    }

    private fun setDefault(choice: ToneChoice) {
        player.stop()
        engine.updateSettings { it.copy(defaultToneMode = choice.mode, defaultToneUri = choice.uri, defaultToneTitle = choice.title) }
        Toast.makeText(this, getString(R.string.clock_default_tone_saved, ToneCatalog.describe(this, choice)), Toast.LENGTH_SHORT).show()
    }
}
