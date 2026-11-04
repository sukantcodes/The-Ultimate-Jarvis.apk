package com.jarvis.assistant.clock.voice

import com.jarvis.assistant.clock.PartOfDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY

class ClockCommandParserTest {

    private fun parse(s: String): ClockIntent = ClockCommandParser.parse(s)?.intent ?: error("not understood: $s")
    private fun set(s: String): ClockIntent.SetAlarm = parse(s) as ClockIntent.SetAlarm
    private fun hour24(a: ClockIntent.SetAlarm): Int? = a.time?.hour24

    private fun assertSet(text: String, hour24: Int?, minute: Int = 0) {
        val a = set(text)
        assertNotNull("no time read from: $text", a.time)
        assertEquals("hour of: $text", hour24, a.time!!.hour24)
        assertEquals("minute of: $text", minute, a.time!!.minute)
    }

    // ---- English ----

    @Test fun englishSetSixAm() = assertSet("Set an alarm for 6 AM.", 6)
    @Test fun englishSetSixPm() = assertSet("set an alarm for 6 pm", 18)
    @Test fun englishDottedAmPm() = assertSet("Set alarm for 7:15 a.m.", 7, 15)
    @Test fun englishTwelveAmIsMidnight() = assertSet("set an alarm for 12 am", 0)
    @Test fun englishTwelvePmIsNoon() = assertSet("set an alarm for 12 pm", 12)
    @Test fun englishTwentyFourHourIsClear() = assertSet("set an alarm for 18:30", 18, 30)
    @Test fun englishZeroHourIsClear() = assertSet("set an alarm for 00:15", 0, 15)
    @Test fun englishWordsHalfPastSixAm() = assertSet("set an alarm for half past six am", 6, 30)
    @Test fun englishWordsQuarterToSevenPm() = assertSet("set an alarm for quarter to seven pm", 18, 45)
    @Test fun englishSixThirtyAm() = assertSet("set an alarm for six thirty am", 6, 30)
    @Test fun englishEveningWord() = assertSet("alarm at 7 in the evening", 19)
    @Test fun englishNightNineIsPm() = assertSet("set alarm tonight 9", 21)
    @Test fun englishMidnight() = assertSet("set an alarm for midnight", 0)
    @Test fun englishNoon() = assertSet("set an alarm for noon", 12)

    @Test fun englishNoAmPmMeansAsk() {
        val a = set("Set an alarm for 6")
        assertNotNull(a.time)
        assertTrue(a.time!!.needsMeridiem)
        assertNull(a.time!!.hour24)
    }

    @Test fun wakeMeUpTomorrowAtFiveThirtyIsAskedNotGuessed() {
        val a = set("Wake me up tomorrow at 5:30")
        assertEquals(DateWord.TOMORROW, a.dateWord)
        assertEquals(5, a.time!!.hour)
        assertEquals(30, a.time!!.minute)
        assertTrue("must ask AM or PM", a.time!!.needsMeridiem)
    }

    @Test fun wakeMeUpWithAmIsFine() {
        val a = set("wake me up tomorrow at 5:30 am")
        assertEquals(DateWord.TOMORROW, a.dateWord)
        assertEquals(5, a.time!!.hour24)
    }

    @Test fun everyMondayWithNoTimeKeepsDayAndAsksTime() {
        val a = set("Set an alarm every Monday")
        assertEquals(setOf(MONDAY), a.repeatDays)
        assertNull(a.time)
    }

    @Test fun everyWeekdayAt() {
        val a = set("set an alarm for 6:30 am every weekday")
        assertEquals(setOf(MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, FRIDAY), a.repeatDays)
        assertEquals(6, a.time!!.hour24)
    }

    @Test fun weekendAndDaily() {
        assertEquals(setOf(SATURDAY, SUNDAY), set("alarm 8 am on weekends").repeatDays)
        assertEquals(DayOfWeek.values().toSet(), set("set an alarm for 5 am every day").repeatDays)
        assertEquals(DayOfWeek.values().toSet(), set("set a daily alarm for 5 am").repeatDays)
    }

    @Test fun twoDaysRepeat() {
        assertEquals(setOf(MONDAY, FRIDAY), set("set an alarm for 6 am on monday and friday").repeatDays)
    }

    @Test fun singleWeekdayWithoutEveryIsOneTimeOnThatDay() {
        val a = set("set an alarm for 6 am on friday")
        assertTrue(a.repeatDays.isEmpty())
        assertEquals(FRIDAY, a.onWeekday)
    }

    @Test fun createAlarmCalledCollegeKeepsLabelAndAsksTime() {
        val a = set("Create an alarm called College")
        assertEquals("College", a.label)
        assertNull(a.time)
    }

    @Test fun labelWithTime() {
        val a = set("set an alarm for 6 am called Gym")
        assertEquals("Gym", a.label)
        assertEquals(6, a.time!!.hour24)
    }

    @Test fun labelIsNotConfusedWithTheTime() {
        assertNull(set("Set an alarm for 6 AM").label)
    }

    @Test fun showAllAlarms() = assertEquals(ClockIntent.ListAlarms, parse("Show all my alarms"))
    @Test fun nextAlarm() = assertEquals(ClockIntent.NextAlarm, parse("What is my next alarm?"))
    @Test fun snooze() = assertEquals(ClockIntent.Snooze, parse("Snooze my alarm"))
    @Test fun stop() = assertEquals(ClockIntent.Stop, parse("Stop the alarm"))
    @Test fun openSettings() = assertEquals(ClockIntent.OpenSettings, parse("Open Clock settings"))

    @Test fun disableMorningAlarm() {
        val i = parse("Disable my morning alarm") as ClockIntent.Disable
        assertEquals(PartOfDay.MORNING, i.selector.partOfDay)
    }

    @Test fun enableSixAmAlarm() {
        val i = parse("Enable my 6 AM alarm") as ClockIntent.Enable
        assertEquals(6, i.selector.time!!.hour24)
    }

    @Test fun enableSixWithoutAmPmIsAmbiguousSelector() {
        val i = parse("Enable my 6 o'clock alarm") as ClockIntent.Enable
        assertTrue(i.selector.time!!.needsMeridiem)
    }

    @Test fun deleteCollegeAlarmHasNoTimeSoLabelIsMatchedAgainstRealAlarms() {
        val i = parse("Delete my college alarm") as ClockIntent.Delete
        assertNull(i.selector.time)
        assertTrue(!i.selector.all)
    }

    @Test fun deleteAllIsFlagged() {
        val i = parse("delete all my alarms") as ClockIntent.Delete
        assertTrue(i.selector.all)
    }

    @Test fun changeAlarmTo730() {
        val i = parse("Change my alarm to 7:30 AM") as ClockIntent.Change
        assertEquals(7, i.newTime!!.hour24)
        assertEquals(30, i.newTime!!.minute)
    }

    @Test fun changeSixToSevenThirty() {
        val i = parse("change my 6 am alarm to 7:30 am") as ClockIntent.Change
        assertEquals(6, i.selector.time!!.hour24)
        assertEquals(7, i.newTime!!.hour24)
    }

    @Test fun changeWithoutAmPmAsksAboutTheNewTime() {
        val i = parse("Change my alarm to 7:30") as ClockIntent.Change
        assertTrue(i.newTime!!.needsMeridiem)
    }

    // ---- Hindi (Devanagari) ----

    @Test fun hindiSetSixMorning() = assertSet("सुबह 6 बजे का अलार्म लगाओ।", 6)
    @Test fun hindiSetSixMorningWords() = assertSet("सुबह छह बजे का अलार्म लगाओ", 6)
    @Test fun hindiDevanagariDigits() = assertSet("सुबह ६ बजे का अलार्म लगाओ", 6)
    @Test fun hindiEveningSeven() = assertSet("शाम 7 बजे अलार्म लगा दो", 19)
    @Test fun hindiNightTwoIsAm() = assertSet("रात 2 बजे का अलार्म लगाओ", 2)
    @Test fun hindiAfternoonTwo() = assertSet("दोपहर 2 बजे का अलार्म लगाओ", 14)
    @Test fun hindiHalfPastSix() = assertSet("सुबह साढ़े छह बजे अलार्म लगाओ", 6, 30)
    @Test fun hindiQuarterPastSeven() = assertSet("शाम सवा सात बजे अलार्म लगाओ", 19, 15)
    @Test fun hindiQuarterToSeven() = assertSet("सुबह पौने सात बजे अलार्म लगाओ", 6, 45)
    @Test fun hindiBajkar() = assertSet("सुबह 6 बजकर 20 मिनट का अलार्म लगाओ", 6, 20)
    @Test fun hindiColonTime() = assertSet("सुबह 6:45 का अलार्म लगाओ", 6, 45)
    @Test fun hindiWakeMe() = assertSet("मुझे सुबह 5 बजे जगा देना", 5)
    @Test fun hindiAmPmLetters() = assertSet("अलार्म 6 ए एम पर लगाओ", 6)

    @Test fun hindiNoPartOfDayAsks() {
        val a = set("6 बजे का अलार्म लगाओ")
        assertTrue(a.time!!.needsMeridiem)
    }

    @Test fun hindiTomorrow() {
        val a = set("कल सुबह 6 बजे का अलार्म लगाओ")
        assertEquals(DateWord.TOMORROW, a.dateWord)
        assertEquals(6, a.time!!.hour24)
    }

    @Test fun hindiEveryMonday() {
        val a = set("हर सोमवार सुबह 6 बजे का अलार्म लगाओ")
        assertEquals(setOf(MONDAY), a.repeatDays)
        assertEquals(6, a.time!!.hour24)
    }

    @Test fun hindiEveryDay() {
        assertEquals(DayOfWeek.values().toSet(), set("रोज सुबह 6 बजे अलार्म लगाओ").repeatDays)
    }

    @Test fun hindiLabel() {
        val a = set("कॉलेज के नाम से सुबह 6 बजे का अलार्म लगाओ")
        assertEquals("कॉलेज", a.label)
    }

    @Test fun hindiList() = assertEquals(ClockIntent.ListAlarms, parse("मेरे सभी अलार्म दिखाओ"))
    @Test fun hindiNext() = assertEquals(ClockIntent.NextAlarm, parse("मेरा अगला अलार्म कब है"))
    @Test fun hindiSnooze() = assertEquals(ClockIntent.Snooze, parse("अलार्म स्नूज़ करो"))
    @Test fun hindiStop() = assertEquals(ClockIntent.Stop, parse("अलार्म रोको"))
    @Test fun hindiDelete() = assertTrue(parse("मेरा कॉलेज वाला अलार्म हटाओ") is ClockIntent.Delete)
    @Test fun hindiDisable() = assertTrue(parse("सुबह वाला अलार्म बंद करो") is ClockIntent.Disable)
    @Test fun hindiEnable() = assertTrue(parse("सुबह 6 बजे का अलार्म चालू करो") is ClockIntent.Enable)
    @Test fun hindiChange() {
        val i = parse("मेरा अलार्म बदलकर सुबह 7:30 कर दो") as ClockIntent.Change
        assertEquals(7, i.newTime!!.hour24)
    }
    @Test fun hindiOpenSettings() = assertEquals(ClockIntent.OpenSettings, parse("क्लॉक सेटिंग्स खोलो"))

    // ---- Hinglish (Hindi in Latin letters) ----

    @Test fun hinglishSubah6Baje() = assertSet("subah 6 baje ka alarm lagao", 6)
    @Test fun hinglishShaam() = assertSet("shaam 7 baje alarm laga do", 19)
    @Test fun hinglishRaat() = assertSet("raat 11 baje alarm lagao", 23)
    @Test fun hinglishSaadeChhe() = assertSet("subah saade chhe baje alarm lagao", 6, 30)
    @Test fun hinglishNoPartOfDayAsks() = assertTrue(set("6 baje alarm lagao").time!!.needsMeridiem)
    @Test fun hinglishEveryMonday() = assertEquals(setOf(MONDAY), set("har somvar subah 6 baje alarm lagao").repeatDays)
    @Test fun hinglishTomorrow() = assertEquals(DateWord.TOMORROW, set("kal subah 6 baje alarm lagao").dateWord)
    @Test fun hinglishLanguageIsHindi() = assertEquals("hi", ClockCommandParser.parse("kal subah 6 baje alarm lagao")!!.language)
    @Test fun hinglishDelete() = assertTrue(parse("mera alarm hatao") is ClockIntent.Delete)
    @Test fun hinglishNext() = assertEquals(ClockIntent.NextAlarm, parse("mera agla alarm kab hai"))

    // ---- Bengali ----

    @Test fun bengaliSetSixMorning() = assertSet("সকাল ৬টায় একটা অ্যালার্ম সেট করো।", 6)
    @Test fun bengaliAsciiDigits() = assertSet("সকাল 6টায় একটা অ্যালার্ম সেট করো", 6)
    @Test fun bengaliEvening() = assertSet("সন্ধ্যা ৭টায় অ্যালার্ম সেট করো", 19)
    @Test fun bengaliNightTwoIsAm() = assertSet("রাত ২টায় অ্যালার্ম দাও", 2)
    @Test fun bengaliHalfPast() = assertSet("সকাল সাড়ে ছয়টায় অ্যালার্ম সেট করো", 6, 30)
    @Test fun bengaliWordHour() = assertSet("সকাল ছয়টায় অ্যালার্ম সেট করো", 6)
    @Test fun bengaliWithMinutes() = assertSet("সকাল ৬টা ৩০ মিনিটে অ্যালার্ম সেট করো", 6, 30)

    @Test fun bengaliEkTaDoesNotBecomeOneOClock() {
        // "একটা" is "an / one" in "একটা অ্যালার্ম"; only 6 is the hour.
        assertEquals(6, set("সকাল ৬টায় একটা অ্যালার্ম সেট করো").time!!.hour)
    }

    @Test fun bengaliNoPartOfDayAsks() = assertTrue(set("৬টায় অ্যালার্ম সেট করো").time!!.needsMeridiem)
    @Test fun bengaliEveryMonday() = assertEquals(setOf(MONDAY), set("প্রতি সোমবার সকাল ৬টায় অ্যালার্ম সেট করো").repeatDays)
    @Test fun bengaliEveryDay() = assertEquals(DayOfWeek.values().toSet(), set("প্রতিদিন সকাল ৬টায় অ্যালার্ম সেট করো").repeatDays)
    @Test fun bengaliTomorrow() = assertEquals(DateWord.TOMORROW, set("কাল সকাল ৬টায় অ্যালার্ম সেট করো").dateWord)
    @Test fun bengaliList() = assertEquals(ClockIntent.ListAlarms, parse("আমার সব অ্যালার্ম দেখাও"))
    @Test fun bengaliNext() = assertEquals(ClockIntent.NextAlarm, parse("আমার পরবর্তী অ্যালার্ম কখন"))
    @Test fun bengaliSnooze() = assertEquals(ClockIntent.Snooze, parse("অ্যালার্ম স্নুজ করো"))
    @Test fun bengaliStop() = assertEquals(ClockIntent.Stop, parse("অ্যালার্ম থামাও"))
    @Test fun bengaliDelete() = assertTrue(parse("আমার কলেজের অ্যালার্ম মুছে দাও") is ClockIntent.Delete)
    @Test fun bengaliDisable() = assertTrue(parse("সকালের অ্যালার্ম বন্ধ করো") is ClockIntent.Disable)
    @Test fun bengaliEnable() = assertTrue(parse("সকাল ৬টার অ্যালার্ম চালু করো") is ClockIntent.Enable)
    @Test fun bengaliLanguage() = assertEquals("bn", ClockCommandParser.parse("সকাল ৬টায় একটা অ্যালার্ম সেট করো")!!.language)
    @Test fun bengaliLabel() = assertEquals("কলেজ", set("কলেজ নামে সকাল ৬টায় অ্যালার্ম সেট করো").label)

    // ---- Kannada ----

    @Test fun kannadaSetSixMorning() = assertSet("ಬೆಳಿಗ್ಗೆ 6 ಗಂಟೆಗೆ ಅಲಾರಂ ಇಡು।", 6)
    @Test fun kannadaDigits() = assertSet("ಬೆಳಿಗ್ಗೆ ೬ ಗಂಟೆಗೆ ಅಲಾರಂ ಇಡು", 6)
    @Test fun kannadaEvening() = assertSet("ಸಂಜೆ 7 ಗಂಟೆಗೆ ಅಲಾರಂ ಇಡು", 19)
    @Test fun kannadaNight() = assertSet("ರಾತ್ರಿ 10 ಗಂಟೆಗೆ ಅಲಾರಂ ಇಡು", 22)
    @Test fun kannadaWordHour() = assertSet("ಬೆಳಿಗ್ಗೆ ಆರು ಗಂಟೆಗೆ ಅಲಾರಂ ಇಡು", 6)
    @Test fun kannadaDative() = assertSet("ಬೆಳಿಗ್ಗೆ ಆರಕ್ಕೆ ಅಲಾರಂ ಇಡು", 6)
    @Test fun kannadaHalf() = assertSet("ಬೆಳಿಗ್ಗೆ ಆರೂವರೆಗೆ ಅಲಾರಂ ಇಡು", 6, 30)
    @Test fun kannadaNoPartOfDayAsks() = assertTrue(set("6 ಗಂಟೆಗೆ ಅಲಾರಂ ಇಡು").time!!.needsMeridiem)
    @Test fun kannadaTomorrow() = assertEquals(DateWord.TOMORROW, set("ನಾಳೆ ಬೆಳಿಗ್ಗೆ 6 ಗಂಟೆಗೆ ಅಲಾರಂ ಇಡು").dateWord)
    @Test fun kannadaEveryMonday() = assertEquals(setOf(MONDAY), set("ಪ್ರತಿ ಸೋಮವಾರ ಬೆಳಿಗ್ಗೆ 6 ಗಂಟೆಗೆ ಅಲಾರಂ ಇಡು").repeatDays)
    @Test fun kannadaList() = assertEquals(ClockIntent.ListAlarms, parse("ನನ್ನ ಎಲ್ಲಾ ಅಲಾರಂ ತೋರಿಸು"))
    @Test fun kannadaNext() = assertEquals(ClockIntent.NextAlarm, parse("ನನ್ನ ಮುಂದಿನ ಅಲಾರಂ ಯಾವಾಗ"))
    @Test fun kannadaSnooze() = assertEquals(ClockIntent.Snooze, parse("ಅಲಾರಂ ಸ್ನೂಜ್ ಮಾಡು"))
    @Test fun kannadaStop() = assertEquals(ClockIntent.Stop, parse("ಅಲಾರಂ ನಿಲ್ಲಿಸು"))
    @Test fun kannadaDelete() = assertTrue(parse("ನನ್ನ ಕಾಲೇಜು ಅಲಾರಂ ಅಳಿಸು") is ClockIntent.Delete)
    @Test fun kannadaDisable() = assertTrue(parse("ಬೆಳಿಗ್ಗೆ ಅಲಾರಂ ಆಫ್ ಮಾಡು") is ClockIntent.Disable)
    @Test fun kannadaEnable() = assertTrue(parse("ಬೆಳಿಗ್ಗೆ 6 ಗಂಟೆ ಅಲಾರಂ ಆನ್ ಮಾಡು") is ClockIntent.Enable)
    @Test fun kannadaLanguage() = assertEquals("kn", ClockCommandParser.parse("ಬೆಳಿಗ್ಗೆ 6 ಗಂಟೆಗೆ ಅಲಾರಂ ಇಡು")!!.language)

    // ---- Other languages: digits, parts of the day, and the main verbs ----

    @Test fun tamil() = assertSet("காலை 6 மணிக்கு அலாரம் வை", 6)
    @Test fun telugu() = assertSet("ఉదయం 6 గంటలకు అలారం పెట్టు", 6)
    @Test fun malayalam() = assertSet("രാവിലെ 6 മണിക്ക് അലാറം വെക്കൂ", 6)
    @Test fun marathi() = assertSet("सकाळी 6 वाजता अलार्म लावा", 6)
    @Test fun gujarati() = assertSet("સવારે 6 વાગ્યે એલાર્મ મૂકો", 6)
    @Test fun punjabi() = assertSet("ਸਵੇਰੇ 6 ਵਜੇ ਅਲਾਰਮ ਲਗਾਓ", 6)
    @Test fun urdu() = assertSet("صبح 6 بجے الارم لگاؤ", 6)
    @Test fun odia() = assertSet("ସକାଳ 6 ଟା ଆଲାର୍ମ ଲଗାଅ", 6)
    @Test fun assamese() = assertSet("ৰাতিপুৱা 6 বজাত এলাৰ্ম দিয়ক", 6)

    @Test fun languageDetection() {
        assertEquals("ta", ClockCommandParser.detectLanguage("காலை 6 மணிக்கு அலாரம் வை"))
        assertEquals("te", ClockCommandParser.detectLanguage("ఉదయం 6 గంటలకు అలారం పెట్టు"))
        assertEquals("ml", ClockCommandParser.detectLanguage("രാവിലെ 6 മണിക്ക് അലാറം വെക്കൂ"))
        assertEquals("mr", ClockCommandParser.detectLanguage("सकाळी 6 वाजता अलार्म लावा"))
        assertEquals("gu", ClockCommandParser.detectLanguage("સવારે 6 વાગ્યે એલાર્મ મૂકો"))
        assertEquals("pa", ClockCommandParser.detectLanguage("ਸਵੇਰੇ 6 ਵਜੇ ਅਲਾਰਮ ਲਗਾਓ"))
        assertEquals("ur", ClockCommandParser.detectLanguage("صبح 6 بجے الارم لگاؤ"))
        assertEquals("or", ClockCommandParser.detectLanguage("ସକାଳ 6 ଟା ଆଲାର୍ମ ଲଗାଅ"))
        assertEquals("as", ClockCommandParser.detectLanguage("ৰাতিপুৱা 6 বজাত এলাৰ্ম দিয়ক"))
        assertEquals("en", ClockCommandParser.detectLanguage("set an alarm for 6 am"))
    }

    // ---- Not alarm commands: must be left alone so existing JARVIS features keep working ----

    @Test fun otherCommandsAreNotTakenOver() {
        for (s in listOf(
            "open youtube", "what time is it", "lock my phone", "set a timer for five minutes", "call mom", "what's the weather",
            "play some music", "stop", "pause music", "send a message to dad", "unlock the phone", "open settings", "turn on wifi",
            "मौसम कैसा है", "व्हाट्सएप खोलो", "আজকের আবহাওয়া কেমন", "ಯೂಟ್ಯೂಬ್ ತೆರೆ"
        )) {
            assertNull("should not be an alarm command: $s", ClockCommandParser.parse(s))
        }
    }

    @Test fun looksLikeClockCommandIsCheap() {
        assertTrue(ClockCommandParser.looksLikeClockCommand("set an alarm"))
        assertTrue(ClockCommandParser.looksLikeClockCommand("wake me up at 6"))
        assertTrue(!ClockCommandParser.looksLikeClockCommand("open youtube"))
    }

    // ---- Answers to follow-up questions ----

    @Test fun meridiemAnswers() {
        assertEquals(Meridiem.AM, ClockCommandParser.readMeridiemAnswer("AM", 6))
        assertEquals(Meridiem.PM, ClockCommandParser.readMeridiemAnswer("p.m.", 6))
        assertEquals(Meridiem.AM, ClockCommandParser.readMeridiemAnswer("in the morning", 6))
        assertEquals(Meridiem.PM, ClockCommandParser.readMeridiemAnswer("evening", 6))
        assertEquals(Meridiem.AM, ClockCommandParser.readMeridiemAnswer("सुबह", 6))
        assertEquals(Meridiem.PM, ClockCommandParser.readMeridiemAnswer("शाम", 6))
        assertEquals(Meridiem.AM, ClockCommandParser.readMeridiemAnswer("সকাল", 6))
        assertEquals(Meridiem.PM, ClockCommandParser.readMeridiemAnswer("সন্ধ্যা", 6))
        assertEquals(Meridiem.AM, ClockCommandParser.readMeridiemAnswer("ಬೆಳಿಗ್ಗೆ", 6))
        assertEquals(Meridiem.PM, ClockCommandParser.readMeridiemAnswer("ಸಂಜೆ", 6))
        assertNull(ClockCommandParser.readMeridiemAnswer("hmm", 6))
    }

    @Test fun choiceNumbers() {
        assertEquals(2, ClockCommandParser.readChoiceNumber("2"))
        assertEquals(1, ClockCommandParser.readChoiceNumber("the first one"))
        assertEquals(2, ClockCommandParser.readChoiceNumber("दूसरा"))
        assertEquals(1, ClockCommandParser.readChoiceNumber("प्रथम".replace("प्रथम", "पहला")))
        assertNull(ClockCommandParser.readChoiceNumber("whatever"))
    }

    @Test fun yesNo() {
        assertEquals(true, ClockCommandParser.readYesNo("yes please"))
        assertEquals(false, ClockCommandParser.readYesNo("no"))
        assertEquals(true, ClockCommandParser.readYesNo("हाँ"))
        assertEquals(false, ClockCommandParser.readYesNo("नहीं"))
        assertEquals(true, ClockCommandParser.readYesNo("হ্যাঁ"))
        assertEquals(false, ClockCommandParser.readYesNo("না"))
        assertEquals(true, ClockCommandParser.readYesNo("ಹೌದು"))
        assertEquals(false, ClockCommandParser.readYesNo("ಇಲ್ಲ"))
        assertNull(ClockCommandParser.readYesNo("maybe"))
    }

    @Test fun meridiemClueCheck() {
        assertTrue(ClockCommandParser.hasMeridiemClue("set an alarm for 6 am"))
        assertTrue(ClockCommandParser.hasMeridiemClue("सुबह 6 बजे"))
        assertTrue(ClockCommandParser.hasMeridiemClue("alarm at 18:30"))
        assertTrue(!ClockCommandParser.hasMeridiemClue("set an alarm for 6"))
        assertTrue(!ClockCommandParser.hasMeridiemClue("wake me up tomorrow at 5:30"))
    }

    @Test fun readTimesFindsBothInChangeCommands() {
        val t = ClockCommandParser.readTimes("change 6 am to 7:30 pm")
        assertEquals(2, t.size)
        assertEquals(6, t[0].hour24)
        assertEquals(19, t[1].hour24)
    }

    @Test fun normalizeUnifiesDigitsAndAmPm() {
        assertEquals("alarm 6 am", ClockCommandParser.normalize("Alarm ६ A.M."))
        assertEquals("5:30", ClockCommandParser.normalize("५:३०"))
        assertEquals("5:30", ClockCommandParser.normalize("5.30"))
    }
}
