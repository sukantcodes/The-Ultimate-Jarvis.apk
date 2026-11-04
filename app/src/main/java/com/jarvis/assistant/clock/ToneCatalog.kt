package com.jarvis.assistant.clock

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import com.jarvis.assistant.R

/** One alarm sound that already exists on the phone. [uri] is its system URI. */
data class ToneOption(val uri: String, val title: String)

/** A tone choice for an alarm or for the default: the mode plus, for a specific sound, its URI and name. */
data class ToneChoice(val mode: ToneMode, val uri: String? = null, val title: String? = null) {
    companion object {
        val PHONE_DEFAULT = ToneChoice(ToneMode.SYSTEM_DEFAULT)
        val SILENT = ToneChoice(ToneMode.SILENT)

        fun of(alarm: AlarmItem) = ToneChoice(alarm.toneMode, alarm.toneUri, alarm.toneTitle)
        fun ofDefault(s: ClockSettings) = ToneChoice(s.defaultToneMode, s.defaultToneUri, s.defaultToneTitle)
    }
}

/**
 * The alarm sounds of this phone, through Android's own RingtoneManager (the system alarm ringtone type). Nothing is
 * bundled, downloaded or imported, and the phone's sounds are never changed: a choice is just the sound's system URI.
 */
object ToneCatalog {

    /** Every sound in the phone's alarm list, in the order the phone gives them. */
    fun list(context: Context): List<ToneOption> {
        val out = ArrayList<ToneOption>()
        try {
            val manager = RingtoneManager(context)
            manager.setType(RingtoneManager.TYPE_ALARM)
            val cursor = manager.cursor ?: return out
            cursor.use { c ->
                while (c.moveToNext()) {
                    val title = c.getString(RingtoneManager.TITLE_COLUMN_INDEX) ?: continue
                    val uri = manager.getRingtoneUri(c.position) ?: continue
                    out += ToneOption(uri.toString(), title)
                }
            }
        } catch (_: Exception) {
            // A phone that will not list its sounds still has its default alarm sound; the list is just empty.
        }
        return out
    }

    /** The phone's current default alarm sound, or null if the phone has none set. */
    fun systemDefaultUri(context: Context): Uri? = try {
        RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
    } catch (_: Exception) {
        null
    }

    /** The display name of a sound, or null when Android cannot name it (for example it was deleted). */
    fun titleOf(context: Context, uri: Uri): String? = try {
        if (!isUsable(context, uri)) null else RingtoneManager.getRingtone(context, uri)?.getTitle(context)
    } catch (_: Exception) {
        null
    }

    /** True when the sound can still be opened, i.e. it has not been deleted or become unreadable. */
    fun isUsable(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
    } catch (_: Exception) {
        false
    }

    fun phoneDefaultTitle(context: Context): String =
        systemDefaultUri(context)?.let { titleOf(context, it) } ?: context.getString(R.string.clock_tone_phone_default)

    /**
     * What the alarm will play, in order: the chosen sound, then the phone's default alarm sound, then the default
     * notification and ringtone sounds as a last resort so a deleted tone never means a silent alarm. Empty for vibration only.
     */
    fun playbackCandidates(context: Context, mode: ToneMode, toneUri: String?): List<Uri> {
        if (mode == ToneMode.SILENT) return emptyList()
        val out = LinkedHashSet<Uri>()
        if (mode == ToneMode.CUSTOM_URI && !toneUri.isNullOrBlank()) {
            runCatching { Uri.parse(toneUri) }.getOrNull()?.let { out += it }
        }
        systemDefaultUri(context)?.let { out += it }
        runCatching { RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) }.getOrNull()?.let { out += it }
        runCatching { RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) }.getOrNull()?.let { out += it }
        runCatching { RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE) }.getOrNull()?.let { out += it }
        return out.toList()
    }

    /** True when a saved specific tone can no longer be played (the alarm will use the phone default instead). */
    fun isMissing(context: Context, choice: ToneChoice): Boolean =
        choice.mode == ToneMode.CUSTOM_URI &&
            (choice.uri.isNullOrBlank() || !isUsable(context, Uri.parse(choice.uri)))

    /** A short line for lists: the tone's name, "Phone default (name)", "Vibration only", or a note that it is missing. */
    fun describe(context: Context, choice: ToneChoice): String = when (choice.mode) {
        ToneMode.SILENT -> context.getString(R.string.clock_tone_vibration_only)
        ToneMode.SYSTEM_DEFAULT -> context.getString(R.string.clock_tone_phone_default_named, phoneDefaultTitle(context))
        ToneMode.CUSTOM_URI -> {
            val name = choice.title ?: choice.uri?.let { runCatching { titleOf(context, Uri.parse(it)) }.getOrNull() }
                ?: context.getString(R.string.clock_tone_unknown)
            if (isMissing(context, choice)) context.getString(R.string.clock_tone_missing_named, name) else name
        }
    }
}
