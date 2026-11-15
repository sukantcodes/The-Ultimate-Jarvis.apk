package com.jarvis.assistant.tools.appcontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRegistryTest {
    private class Store(var flag: String = "false", var level: String = "1.0", var shape: String = "0", var theme: String = "light", var name: String = "Boss")

    private fun registry(s: Store, onWrite: (SettingDef) -> Unit = {}, clampShapeTo: Int? = null): SettingsRegistry = SettingsRegistry(listOf(
        SettingDef("touch_glow", "Appearance", "Touch glow", SettingType.BOOLEAN, read = { s.flag }, write = { s.flag = it }),
        SettingDef("orb_glow", "Orb", "Orb glow", SettingType.FLOAT, min = 0.0, max = 2.0, read = { s.level }, write = { s.level = it }),
        SettingDef("settings_icon_shape", "Appearance", "Icon shape", SettingType.INT, min = 0.0, max = 3.0, read = { s.shape },
            write = { s.shape = (clampShapeTo?.toString() ?: it) }),
        SettingDef("app_theme", "Appearance", "Theme", SettingType.CHOICE, choices = listOf("light", "dark", "custom"), read = { s.theme }, write = { s.theme = it }),
        SettingDef("user_name", "Assistant", "Name", SettingType.TEXT, validator = { if (it.contains("<")) "No markup." else null }, read = { s.name }, write = { s.name = it })
    ), onWrite)

    @Test fun booleanAcceptsEverydayWords() {
        val d = registry(Store()).find("touch_glow")!!
        assertEquals(Normalised.Ok("true"), d.normalise(" ON "))
        assertEquals(Normalised.Ok("false"), d.normalise("disable"))
        assertTrue(d.normalise("maybe") is Normalised.Bad)
    }

    @Test fun numbersAreRangeChecked() {
        val r = registry(Store())
        assertEquals(Normalised.Ok("1.5"), r.find("orb_glow")!!.normalise("1.50"))
        assertTrue(r.find("orb_glow")!!.normalise("2.5") is Normalised.Bad)
        assertTrue(r.find("orb_glow")!!.normalise("NaN") is Normalised.Bad)
        assertTrue(r.find("settings_icon_shape")!!.normalise("4") is Normalised.Bad)
        assertTrue(r.find("settings_icon_shape")!!.normalise("1.5") is Normalised.Bad)
    }

    @Test fun choiceIsMatchedIgnoringCaseAndListsTheOptionsOnFailure() {
        val d = registry(Store()).find("app_theme")!!
        assertEquals(Normalised.Ok("dark"), d.normalise("DARK"))
        val bad = d.normalise("neon") as Normalised.Bad
        assertTrue(bad.reason.contains("light, dark, custom"))
    }

    @Test fun textValidatorRuns() {
        val d = registry(Store()).find("user_name")!!
        assertTrue(d.normalise("<b>x</b>") is Normalised.Bad)
        assertEquals(Normalised.Ok("Sukant"), d.normalise("  Sukant "))
    }

    @Test fun idsAreFoundWhateverTheSpellingStyle() {
        val r = registry(Store())
        assertNotNull(r.find("Orb-Glow"))
        assertNotNull(r.find("orb glow"))
        assertNull(r.find("nothing_like_this"))
    }

    @Test fun changeWritesThenReadsBack() {
        val s = Store()
        var marked = 0
        val r = registry(s, onWrite = { marked++ })
        val out = r.change("orb_glow", "1.5") as SettingsRegistry.Outcome.Changed
        assertEquals("1.0", out.before)
        assertEquals("1.5", out.after)
        assertNull(out.note)
        assertEquals("1.5", s.level)
        assertEquals(1, marked)
    }

    @Test fun sameValueIsReportedUnchangedAndNothingIsWritten() {
        val s = Store(level = "1.0")
        var marked = 0
        val r = registry(s, onWrite = { marked++ })
        assertTrue(r.change("orb_glow", "1") is SettingsRegistry.Outcome.Unchanged)
        assertEquals(0, marked)
    }

    @Test fun whenTheAppAdjustsTheValueTheAnswerSaysSo() {
        val s = Store(shape = "0")
        val out = registry(s, clampShapeTo = 2).change("settings_icon_shape", "3") as SettingsRegistry.Outcome.Changed
        assertEquals("2", out.after)
        assertTrue(out.note!!.contains("2"))
    }

    @Test fun whenTheAppIgnoresTheChangeItIsNotReportedAsDone() {
        val s = Store(shape = "0")
        val out = registry(s, clampShapeTo = 0).change("settings_icon_shape", "3")
        assertTrue(out is SettingsRegistry.Outcome.Failed)
    }

    @Test fun badValuesAndUnknownIdsFailWithAReason() {
        val s = Store()
        val r = registry(s)
        val bad = r.change("orb_glow", "9") as SettingsRegistry.Outcome.Failed
        assertTrue(bad.reason.contains("highest"))
        assertEquals("1.0", s.level)
        assertTrue(r.change("missing", "1") is SettingsRegistry.Outcome.Failed)
    }

    @Test fun searchAndSectionsWork() {
        val r = registry(Store())
        assertEquals(listOf("Appearance", "Orb", "Assistant"), r.sections())
        assertEquals(listOf("orb_glow"), r.search("orb").map { it.id })
        assertEquals(3, r.inSection("appearance").size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateIdsAreRejected() {
        SettingsRegistry(listOf(
            SettingDef("a", "S", "A", SettingType.TEXT, read = { "" }, write = {}),
            SettingDef("a", "S", "B", SettingType.TEXT, read = { "" }, write = {})
        ))
    }
}
