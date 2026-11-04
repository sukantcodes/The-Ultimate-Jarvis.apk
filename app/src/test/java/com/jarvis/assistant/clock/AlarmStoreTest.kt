package com.jarvis.assistant.clock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class AlarmStoreTest {

    private val full = AlarmItem(
        id = 7, hour = 5, minute = 30, label = "College", enabled = true,
        repeatDays = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY),
        oneTimeDate = null, toneMode = ToneMode.CUSTOM_URI,
        toneUri = "content://media/internal/audio/media/42?title=a|b&x=1",
        toneTitle = "Morning | Flower", vibrate = false, snoozeMinutes = 7,
        snoozedUntilMillis = 123456789L, snoozeCount = 2, lastTriggerMillis = 99L, scheduledForMillis = 555L
    )

    @Test fun codecRoundTripKeepsEveryField() {
        assertEquals(full, AlarmCodec.decode(AlarmCodec.encode(full)))
    }

    @Test fun codecKeepsUnicodeLabelsInEveryScript() {
        val labels = listOf("কলেজ", "कॉलेज", "ಕಾಲೇಜು", "கல்லூரி", "కళాశాల", "കോളേജ്", "કૉલેજ", "ਕਾਲਜ", "کالج", "କଲେଜ", "মহাবিদ্যালয়", "Café ☕ 🎓", "line1\nline2", "a|b|c")
        for (label in labels) {
            val back = AlarmCodec.decode(AlarmCodec.encode(full.copy(label = label)))
            assertEquals(label, back?.label)
        }
    }

    @Test fun codecKeepsEmptyLabelAndNullTone() {
        val a = full.copy(label = "", toneMode = ToneMode.SYSTEM_DEFAULT, toneUri = null, toneTitle = null)
        val back = AlarmCodec.decode(AlarmCodec.encode(a))!!
        assertEquals("", back.label)
        assertNull(back.toneUri)
        assertEquals(a, back)
    }

    @Test fun codecKeepsDatedOneTime() {
        val a = full.copy(repeatDays = emptySet(), oneTimeDate = LocalDate.of(2027, 2, 28))
        assertEquals(a, AlarmCodec.decode(AlarmCodec.encode(a)))
    }

    @Test fun codecRejectsDamagedLines() {
        assertNull(AlarmCodec.decode(""))
        assertNull(AlarmCodec.decode("garbage"))
        assertNull(AlarmCodec.decode(AlarmCodec.encode(full).replace("1|7|5|30", "1|7|25|30")))
        assertNull(AlarmCodec.decode(AlarmCodec.encode(full).replace("1|7|5|30", "9|7|5|30")))
        assertNull(AlarmCodec.decode(AlarmCodec.encode(full).removeSuffix("|end")))
        assertNull(AlarmCodec.decode(AlarmCodec.encode(full).replace("CUSTOM_URI", "NOPE")))
    }

    @Test fun oneDamagedLineDoesNotLoseTheOthers() {
        val kv = MemoryKeyValueStore()
        val store = AlarmStore(kv)
        store.put(full.copy(id = 1))
        store.put(full.copy(id = 2))
        val raw = kv.getString("clock_alarms_v1")!!
        kv.putString("clock_alarms_v1", raw + "\nthis line is broken")
        assertEquals(listOf(1L, 2L), AlarmStore(kv).all().map { it.id })
    }

    @Test fun storePersistsAcrossInstances() {
        val kv = MemoryKeyValueStore()
        AlarmStore(kv).put(full)
        val again = AlarmStore(kv).get(7)
        assertEquals(full, again)
    }

    @Test fun putReplacesSameId() {
        val store = AlarmStore(MemoryKeyValueStore())
        store.put(full)
        store.put(full.copy(label = "Gym"))
        assertEquals(1, store.all().size)
        assertEquals("Gym", store.get(7)!!.label)
    }

    @Test fun removeReportsWhetherAnythingWasRemoved() {
        val store = AlarmStore(MemoryKeyValueStore())
        store.put(full)
        assertTrue(store.remove(7))
        assertFalse(store.remove(7))
        assertTrue(store.all().isEmpty())
    }

    @Test fun idsNeverRepeatEvenAfterDelete() {
        val store = AlarmStore(MemoryKeyValueStore())
        val a = store.nextId()
        store.put(full.copy(id = a))
        val b = store.nextId()
        store.put(full.copy(id = b))
        store.remove(b)
        val c = store.nextId()
        assertTrue(c > b)
        assertEquals(setOf(1L, 2L, 3L), setOf(a, b, c))
    }

    @Test fun snoozeMinutesAreClampedOnLoad() {
        val raw = AlarmCodec.encode(full.copy(snoozeMinutes = 7)).replace("|7|123456789", "|999|123456789")
        assertEquals(ClockSettings.MAX_SNOOZE, AlarmCodec.decode(raw)!!.snoozeMinutes)
    }

    // ---- Settings ----

    @Test fun settingsDefaultsWhenNothingSaved() {
        assertEquals(ClockSettings(), ClockSettingsStore(MemoryKeyValueStore()).get())
    }

    @Test fun settingsRoundTrip() {
        val kv = MemoryKeyValueStore()
        val s = ClockSettings(
            timeFormat = TimeFormat.H24, weekStart = DayOfWeek.MONDAY, showNextAlarmBanner = false,
            showRingScreenDate = false, defaultToneMode = ToneMode.CUSTOM_URI,
            defaultToneUri = "content://settings/system/alarm_alert?x=1&y=2", defaultToneTitle = "Cesium",
            defaultVibrate = false, defaultSnoozeMinutes = 5, silenceAfterMinutes = 3, maxSnoozes = 4,
            gradualVolume = false, volumeKeyAction = VolumeKeyAction.DISMISS
        )
        ClockSettingsStore(kv).save(s)
        assertEquals(s, ClockSettingsStore(kv).get())
    }

    @Test fun settingsOutOfRangeValuesAreClamped() {
        val kv = MemoryKeyValueStore()
        kv.putString("clock_setting_defaultSnoozeMinutes", "500")
        kv.putString("clock_setting_silenceAfterMinutes", "-4")
        kv.putString("clock_setting_maxSnoozes", "99")
        val s = ClockSettingsStore(kv).get()
        assertEquals(ClockSettings.MAX_SNOOZE, s.defaultSnoozeMinutes)
        assertEquals(0, s.silenceAfterMinutes)
        assertEquals(ClockSettings.MAX_SNOOZE_LIMIT, s.maxSnoozes)
    }

    @Test fun settingsUnknownEnumFallsBackToDefault() {
        val kv = MemoryKeyValueStore()
        kv.putString("clock_setting_timeFormat", "BANANA")
        assertEquals(TimeFormat.SYSTEM, ClockSettingsStore(kv).get().timeFormat)
    }

    @Test fun settingsUpdateIsSaved() {
        val kv = MemoryKeyValueStore()
        val store = ClockSettingsStore(kv)
        store.update { it.copy(defaultSnoozeMinutes = 15) }
        assertEquals(15, ClockSettingsStore(kv).get().defaultSnoozeMinutes)
        assertNotNull(kv.getString("clock_setting_defaultSnoozeMinutes"))
    }
}
