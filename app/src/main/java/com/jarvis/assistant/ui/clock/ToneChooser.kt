package com.jarvis.assistant.ui.clock

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.R
import com.jarvis.assistant.clock.AlarmSoundPlayer
import com.jarvis.assistant.clock.ToneCatalog
import com.jarvis.assistant.clock.ToneChoice
import com.jarvis.assistant.clock.ToneMode
import com.jarvis.assistant.clock.ToneOption

/**
 * The tone picker: the phone's own alarm sounds, plus "Phone default" and "Vibration only". Tapping a sound plays it as a
 * preview on the alarm audio stream; the preview stops when another sound is tapped or the dialog closes. Nothing is
 * downloaded or imported, and the phone's sounds are never changed.
 */
object ToneChooser {

    fun show(activity: Activity, current: ToneChoice, titleRes: Int = R.string.clock_choose_tone, onChosen: (ToneChoice) -> Unit) {
        val tones: List<ToneOption> = ToneCatalog.list(activity)
        val choices = ArrayList<ToneChoice>()
        val labels = ArrayList<String>()
        choices += ToneChoice.PHONE_DEFAULT
        labels += activity.getString(R.string.clock_tone_phone_default_named, ToneCatalog.phoneDefaultTitle(activity))
        tones.forEach { choices += ToneChoice(ToneMode.CUSTOM_URI, it.uri, it.title); labels += it.title }
        choices += ToneChoice.SILENT
        labels += activity.getString(R.string.clock_tone_vibration_only)

        // A saved sound that the phone no longer lists keeps its own row, so it is shown rather than silently swapped.
        if (current.mode == ToneMode.CUSTOM_URI && !current.uri.isNullOrBlank() && tones.none { it.uri == current.uri }) {
            choices.add(1, current)
            labels.add(1, ToneCatalog.describe(activity, current))
        }

        var selected = choices.indexOfFirst { it.mode == current.mode && (it.mode != ToneMode.CUSTOM_URI || it.uri == current.uri) }
            .let { if (it < 0) 0 else it }

        val player = AlarmSoundPlayer(activity.applicationContext)
        fun preview(choice: ToneChoice) {
            player.stop()
            val candidates = ToneCatalog.playbackCandidates(activity, choice.mode, choice.uri)
            if (candidates.isNotEmpty()) player.start(candidates, loop = false, volume = 1f)
        }

        val dialog: AlertDialog = MaterialAlertDialogBuilder(activity, com.jarvis.assistant.R.style.ThemeOverlay_Jarvis_MaterialDialog)
            .setTitle(titleRes)
            .setSingleChoiceItems(labels.toTypedArray(), selected) { _, which ->
                selected = which
                preview(choices[which])
            }
            .setPositiveButton(R.string.clock_ok) { _, _ -> onChosen(choices[selected]) }
            .setNegativeButton(R.string.clock_cancel, null)
            .create()
        dialog.setOnDismissListener { player.stop() }
        dialog.show()
    }

    /** Previews one choice outside the dialog (used by the Alarm Tones page). Returns the player so the caller can stop it. */
    fun previewOnce(context: Context, player: AlarmSoundPlayer, choice: ToneChoice): Boolean {
        player.stop()
        val candidates = ToneCatalog.playbackCandidates(context, choice.mode, choice.uri)
        return candidates.isNotEmpty() && player.start(candidates, loop = false, volume = 1f)
    }
}
