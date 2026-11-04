package com.jarvis.assistant.clock

import java.net.URLDecoder
import java.net.URLEncoder
import java.time.DayOfWeek
import java.time.LocalDate

/** The smallest storage the clock needs. Android backs it with SharedPreferences; tests use a map. */
interface KeyValueStore {
    fun getString(key: String): String?

    /** Writes and returns only once the value is saved, so an alarm is never "created" and then lost. */
    fun putString(key: String, value: String)
}

/** A [KeyValueStore] that lives in memory. Used by tests, and as a safe stand-in if storage is unavailable. */
class MemoryKeyValueStore : KeyValueStore {
    private val map = HashMap<String, String>()
    @Synchronized override fun getString(key: String): String? = map[key]
    @Synchronized override fun putString(key: String, value: String) { map[key] = value }
}

/**
 * Turns an [AlarmItem] into one line of text and back. Text fields (label, tone) are URL-encoded so any language,
 * emoji, newline or separator character in them is safe. The first field is a format version.
 */
object AlarmCodec {
    private const val VERSION = "1"
    private const val SEP = "|"
    private const val FIELDS = 15

    private fun enc(value: String?): String = if (value == null) "-" else "~" + URLEncoder.encode(value, "UTF-8")

    private fun dec(raw: String): String? = when {
        raw == "-" -> null
        raw.startsWith("~") -> URLDecoder.decode(raw.substring(1), "UTF-8")
        else -> null
    }

    fun encode(a: AlarmItem): String = listOf(
        VERSION,
        a.id.toString(),
        a.hour.toString(),
        a.minute.toString(),
        if (a.enabled) "1" else "0",
        a.repeatDays.map { it.value }.sorted().joinToString(","),
        a.oneTimeDate?.toString() ?: "-",
        a.toneMode.name,
        enc(a.toneUri),
        enc(a.toneTitle),
        if (a.vibrate) "1" else "0",
        a.snoozeMinutes.toString(),
        a.snoozedUntilMillis.toString() + ":" + a.snoozeCount + ":" + a.lastTriggerMillis + ":" + a.scheduledForMillis,
        enc(a.label),
        "end"
    ).joinToString(SEP)

    /** Returns null for a line that is damaged or from an unknown version, so one bad line never loses the others. */
    fun decode(line: String): AlarmItem? {
        return try {
            val f = line.split(SEP)
            if (f.size != FIELDS || f[0] != VERSION || f[14] != "end") return null
            val hour = f[2].toInt()
            val minute = f[3].toInt()
            if (hour !in 0..23 || minute !in 0..59) return null
            val days = if (f[5].isEmpty()) emptySet() else f[5].split(",").map { DayOfWeek.of(it.toInt()) }.toSet()
            val runtime = f[12].split(":")
            AlarmItem(
                id = f[1].toLong(),
                hour = hour,
                minute = minute,
                enabled = f[4] == "1",
                repeatDays = days,
                oneTimeDate = if (f[6] == "-") null else LocalDate.parse(f[6]),
                toneMode = ToneMode.valueOf(f[7]),
                toneUri = dec(f[8]),
                toneTitle = dec(f[9]),
                vibrate = f[10] == "1",
                snoozeMinutes = f[11].toInt().coerceIn(ClockSettings.MIN_SNOOZE, ClockSettings.MAX_SNOOZE),
                snoozedUntilMillis = runtime[0].toLong(),
                snoozeCount = runtime[1].toInt(),
                lastTriggerMillis = runtime[2].toLong(),
                scheduledForMillis = runtime[3].toLong(),
                label = dec(f[13]) ?: ""
            )
        } catch (_: Exception) {
            null
        }
    }
}

/** Saves the alarm list and settings. Every change is written before the call returns. */
class AlarmStore(private val kv: KeyValueStore) {

    @Synchronized
    fun all(): List<AlarmItem> {
        val raw = kv.getString(KEY_ALARMS) ?: return emptyList()
        return raw.split("\n").filter { it.isNotBlank() }.mapNotNull { AlarmCodec.decode(it) }.sortedBy { it.id }
    }

    @Synchronized
    fun get(id: Long): AlarmItem? = all().firstOrNull { it.id == id }

    @Synchronized
    fun nextId(): Long {
        val existingMax = all().maxOfOrNull { it.id } ?: 0L
        val saved = kv.getString(KEY_NEXT_ID)?.toLongOrNull() ?: 0L
        val id = maxOf(existingMax, saved) + 1
        kv.putString(KEY_NEXT_ID, id.toString())
        return id
    }

    /** Adds the alarm, or replaces the one with the same id. */
    @Synchronized
    fun put(alarm: AlarmItem) {
        val list = all().filter { it.id != alarm.id } + alarm
        write(list)
    }

    @Synchronized
    fun remove(id: Long): Boolean {
        val before = all()
        if (before.none { it.id == id }) return false
        write(before.filter { it.id != id })
        return true
    }

    private fun write(list: List<AlarmItem>) {
        kv.putString(KEY_ALARMS, list.sortedBy { it.id }.joinToString("\n") { AlarmCodec.encode(it) })
    }

    companion object {
        private const val KEY_ALARMS = "clock_alarms_v1"
        private const val KEY_NEXT_ID = "clock_next_id"
    }
}

/** Saves [ClockSettings]. Each value has its own key, so a new setting later never breaks an old save. */
class ClockSettingsStore(private val kv: KeyValueStore) {

    @Synchronized
    fun get(): ClockSettings {
        val d = ClockSettings()
        fun str(key: String): String? = kv.getString(PREFIX + key)
        fun bool(key: String, default: Boolean): Boolean = str(key)?.let { it == "1" } ?: default
        fun int(key: String, default: Int): Int = str(key)?.toIntOrNull() ?: default
        fun text(key: String): String? = str(key)?.let { if (it == "-") null else it }
        return ClockSettings(
            timeFormat = str("timeFormat")?.let { v -> TimeFormat.values().firstOrNull { it.name == v } } ?: d.timeFormat,
            weekStart = str("weekStart")?.toIntOrNull()?.takeIf { it in 1..7 }?.let { DayOfWeek.of(it) },
            showNextAlarmBanner = bool("showNextAlarmBanner", d.showNextAlarmBanner),
            showRingScreenDate = bool("showRingScreenDate", d.showRingScreenDate),
            defaultToneMode = str("defaultToneMode")?.let { v -> ToneMode.values().firstOrNull { it.name == v } } ?: d.defaultToneMode,
            defaultToneUri = text("defaultToneUri")?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() },
            defaultToneTitle = text("defaultToneTitle")?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() },
            defaultVibrate = bool("defaultVibrate", d.defaultVibrate),
            defaultSnoozeMinutes = int("defaultSnoozeMinutes", d.defaultSnoozeMinutes)
                .coerceIn(ClockSettings.MIN_SNOOZE, ClockSettings.MAX_SNOOZE),
            silenceAfterMinutes = int("silenceAfterMinutes", d.silenceAfterMinutes).coerceIn(0, ClockSettings.MAX_SILENCE_AFTER),
            maxSnoozes = int("maxSnoozes", d.maxSnoozes).coerceIn(0, ClockSettings.MAX_SNOOZE_LIMIT),
            gradualVolume = bool("gradualVolume", d.gradualVolume),
            volumeKeyAction = str("volumeKeyAction")?.let { v -> VolumeKeyAction.values().firstOrNull { it.name == v } } ?: d.volumeKeyAction
        )
    }

    @Synchronized
    fun save(s: ClockSettings) {
        fun put(key: String, value: String) = kv.putString(PREFIX + key, value)
        fun flag(b: Boolean) = if (b) "1" else "0"
        put("timeFormat", s.timeFormat.name)
        put("weekStart", s.weekStart?.value?.toString() ?: "-")
        put("showNextAlarmBanner", flag(s.showNextAlarmBanner))
        put("showRingScreenDate", flag(s.showRingScreenDate))
        put("defaultToneMode", s.defaultToneMode.name)
        put("defaultToneUri", s.defaultToneUri?.let { URLEncoder.encode(it, "UTF-8") } ?: "-")
        put("defaultToneTitle", s.defaultToneTitle?.let { URLEncoder.encode(it, "UTF-8") } ?: "-")
        put("defaultVibrate", flag(s.defaultVibrate))
        put("defaultSnoozeMinutes", s.defaultSnoozeMinutes.toString())
        put("silenceAfterMinutes", s.silenceAfterMinutes.toString())
        put("maxSnoozes", s.maxSnoozes.toString())
        put("gradualVolume", flag(s.gradualVolume))
        put("volumeKeyAction", s.volumeKeyAction.name)
    }

    @Synchronized
    fun update(change: (ClockSettings) -> ClockSettings): ClockSettings {
        val updated = change(get())
        save(updated)
        return updated
    }

    companion object {
        private const val PREFIX = "clock_setting_"
    }
}
