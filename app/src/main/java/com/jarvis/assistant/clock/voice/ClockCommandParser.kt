package com.jarvis.assistant.clock.voice

import com.jarvis.assistant.clock.AlarmTimeCalculator
import com.jarvis.assistant.clock.PartOfDay
import java.text.Normalizer
import java.time.DayOfWeek
import java.util.Locale

enum class Meridiem { AM, PM }

/**
 * A time as the person said it. [meridiem] is only set when they actually said am/pm or a part of the day
 * ("morning", "सुबह", "সকাল", "ಬೆಳಿಗ್ಗೆ" ...). [explicit24h] is true when the hour cannot be read two ways (0, or 13 to 23).
 * With neither, the time is NOT known, and [hour24] is null: callers must ask, never guess.
 */
data class ParsedTime(val hour: Int, val minute: Int, val meridiem: Meridiem?, val explicit24h: Boolean) {
    val hour24: Int?
        get() = when {
            explicit24h -> hour
            meridiem != null && hour in 1..12 -> AlarmTimeCalculator.to24Hour(hour, meridiem == Meridiem.PM)
            else -> null
        }
    val needsMeridiem: Boolean get() = hour24 == null
}

enum class DateWord { TODAY, TOMORROW, DAY_AFTER }

/** How the person pointed at an existing alarm. Labels are matched later against the real alarms. */
data class Selector(
    val time: ParsedTime? = null,
    val partOfDay: PartOfDay? = null,
    val weekday: DayOfWeek? = null,
    /** "all my alarms": never acted on for delete, so the assistant asks which one. */
    val all: Boolean = false
)

sealed class ClockIntent {
    data class SetAlarm(
        val time: ParsedTime?,
        val repeatDays: Set<DayOfWeek> = emptySet(),
        val dateWord: DateWord? = null,
        /** "Monday 6 am" with no "every": the next Monday, once. */
        val onWeekday: DayOfWeek? = null,
        val label: String? = null
    ) : ClockIntent()

    object ListAlarms : ClockIntent()
    object NextAlarm : ClockIntent()
    object Snooze : ClockIntent()
    object Stop : ClockIntent()
    object OpenSettings : ClockIntent()
    data class Enable(val selector: Selector) : ClockIntent()
    data class Disable(val selector: Selector) : ClockIntent()
    data class Delete(val selector: Selector) : ClockIntent()
    data class Change(
        val selector: Selector,
        val newTime: ParsedTime? = null,
        val newRepeatDays: Set<DayOfWeek>? = null,
        val newLabel: String? = null
    ) : ClockIntent()
}

data class ClockParse(val intent: ClockIntent, val language: String, val normalized: String)

/**
 * Reads a spoken or typed alarm command in English, Hindi, Hinglish, Bengali, Kannada, Tamil, Telugu, Malayalam, Marathi,
 * Gujarati, Punjabi, Urdu, Odia or Assamese into a [ClockIntent]. Pure text work, no Android calls.
 *
 * It only understands the words and numbers; every decision about alarms is taken by the shared ClockEngine. Hindi,
 * Hinglish, Bengali, Kannada and English have the fullest coverage (they are the languages this was tested in). The other
 * languages are recognised for the main phrases, digits and times; for anything it cannot read it returns null so the
 * request goes to the AI instead of being guessed.
 */
object ClockCommandParser {

    // ---- Normalising ----------------------------------------------------------------------------

    /** Lower case, composed form, no zero-width joiners. Used for both the text and the keyword lists. */
    fun fold(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKC)
            .replace("‌", "").replace("‍", "").replace("​", "")
            .lowercase(Locale.ROOT)

    /** Folds, turns every script's digits into 0-9, unifies am/pm spellings and strips punctuation (keeping times like 5:30). */
    fun normalize(raw: String): String {
        val sb = StringBuilder()
        for (ch in fold(raw)) {
            if (Character.isDigit(ch)) sb.append(Character.digit(ch, 10)) else sb.append(ch)
        }
        var t = sb.toString()
        t = t.replace(Regex("(\\d)\\s*[.:·]\\s*(\\d{2})(?!\\d)"), "$1:$2")
        t = t.replace(Regex("(?<![a-z])([ap])\\s*\\.\\s*m\\b\\.?"), "$1m")
        t = t.replace(Regex("(?<![a-z])([ap])\\s+m\\b"), "$1m")
        // "ए एम" / "पी एम" and the same in other scripts.
        for (w in listOf("ए एम", "एएम", "এ এম", "এএম", "ಎ ಎಂ", "ಎಎಂ", "ஏ எம்", "ఏ ఎం", "എ എം", "એ એમ", "ਏ ਐਮ", "اے ایم", "ଏ ଏମ")) t = t.replace(fold(w), " am ")
        for (w in listOf("पी एम", "पीएम", "পি এম", "পিএম", "ಪಿ ಎಂ", "ಪಿಎಂ", "பி எம்", "పి ఎం", "പി എം", "પી એમ", "ਪੀ ਐਮ", "پی ایم", "ପି ଏମ")) t = t.replace(fold(w), " pm ")
        t = t.replace("'", "").replace("’", "")
        t = t.replace(Regex("[,;!?\"“”()\\[\\]{}।॥|]"), " ")
        t = t.replace(Regex("(?<!\\d):|:(?!\\d)"), " ")
        t = t.replace(Regex("(?<!\\d)\\.|\\.(?!\\d)"), " ")
        t = t.replace(Regex("\\s+"), " ").trim()
        return t
    }

    // ---- Language -------------------------------------------------------------------------------

    /** The language to answer in, from the script of the words (and a few marker words to tell close languages apart). */
    fun detectLanguage(raw: String): String {
        val counts = HashMap<String, Int>()
        for (ch in raw) {
            val key = when (Character.UnicodeScript.of(ch.code)) {
                Character.UnicodeScript.DEVANAGARI -> "hi"
                Character.UnicodeScript.BENGALI -> "bn"
                Character.UnicodeScript.GUJARATI -> "gu"
                Character.UnicodeScript.GURMUKHI -> "pa"
                Character.UnicodeScript.ORIYA -> "or"
                Character.UnicodeScript.TAMIL -> "ta"
                Character.UnicodeScript.TELUGU -> "te"
                Character.UnicodeScript.KANNADA -> "kn"
                Character.UnicodeScript.MALAYALAM -> "ml"
                Character.UnicodeScript.ARABIC -> "ur"
                else -> null
            } ?: continue
            counts[key] = (counts[key] ?: 0) + 1
        }
        val top = counts.maxByOrNull { it.value }?.key
        if (top == null) return if (looksLikeRomanHindi(normalize(raw))) "hi" else "en"
        val t = fold(raw)
        if (top == "hi" && listOf("वाजता", "उद्या", "सकाळी", "दुपारी", "संध्याकाळी", "रात्री", "करा", "लावा", "गजर").any { t.contains(fold(it)) }) return "mr"
        if (top == "bn" && listOf("বজাত", "আজি", "কালি", "ৰ", "ৱ", "এলাৰ্ম", "কৰক", "কৰা").any { t.contains(it) }) return "as"
        return top
    }

    private val romanHindiWords = listOf(
        "baje", "bje", "subah", "subha", "shaam", "sham", "raat", "dopahar", "kal", "aaj", "parso", "lagao", "laga do", "laga de",
        "jaga", "uthana", "utha do", "hatao", "hata do", "mita do", "band kar", "chalu", "badlo", "badal", "dikhao", "batao", "agla", "mera", "meri",
        "mujhe", "roz", "har ", "saade", "sade ", "sawa", "paune", "sab alarm", "saare", "kaun sa", "kitne"
    )

    private fun looksLikeRomanHindi(t: String): Boolean = romanHindiWords.any { w -> Regex("(?<![a-z])" + Regex.escape(w.trim()) + (if (w.endsWith(" ")) "" else "(?![a-z])")).containsMatchIn(t) }

    // ---- Keyword helpers ------------------------------------------------------------------------

    private fun isAscii(s: String) = s.all { it.code < 128 }

    /** True when any of [words] is in [t]. Latin words must be whole words; other scripts match inside a word (they inflect). */
    private fun has(t: String, words: List<String>): Boolean = words.any { w ->
        if (isAscii(w)) Regex("(?<![a-z0-9])" + Regex.escape(w) + "(?![a-z0-9])").containsMatchIn(t) else t.contains(w)
    }

    private fun k(vararg w: String): List<String> = w.map { fold(it) }

    private val ALARM_NOUN = k(
        "alarm", "alarms", "wake up call", "अलार्म", "अलारम", "अलार्मों", "গজর", "অ্যালার্ম", "এলার্ম", "অ্যালারাম", "এলারাম", "এলাৰ্ম", "এলাৰাম",
        "ಅಲಾರಂ", "ಅಲಾರಾಂ", "ಅಲಾರ್ಮ್", "ಅಲಾರ್ಮ", "ಅಲಾರಮ್", "அலாரம்", "அலார்ம்", "అలారం", "అలారమ్", "అలార్మ్", "അലാറം", "അലാറ", "गजर",
        "એલાર્મ", "અલાર્મ", "ਅਲਾਰਮ", "الارم", "الارم", "ایلارم", "ଆଲାର୍ମ", "ଆଲାରାମ"
    )
    private val WAKE_PHRASE = k(
        "wake me up", "wake me", "wake up at", "जगा", "उठा देना", "उठाना", "उठा दो", "मुझे उठा", "jaga do", "jagana", "jaga dena", "utha dena", "uthana",
        "ঘুম থেকে ডেকে", "ঘুম থেকে তুলে", "জাগিয়ে", "ডেকে দিও", "ডেকে দাও", "তুলে দিও", "ಎಬ್ಬಿಸು", "ಎಬ್ಬಿಸಿ", "ಎಚ್ಚರಿಸು", "ಎಚ್ಚರಿಸಿ", "ಎಚ್ಚರ",
        "எழுப்பு", "లేపు", "లేపండి", "ഉണർത്ത", "വിളിച്ചുണർത്ത", "उठव", "जागे कर", "ઉઠાડ", "ਜਗਾ", "جگا", "ଉଠାଅ", "ଉଠାଇ", "জগাব", "সাৰ পোৱা"
    )
    private val CLOCK_WORD = k("clock", "घड़ी", "क्लॉक", "क्लोक", "ঘড়ি", "ক্লক", "ಗಡಿಯಾರ", "ಕ್ಲಾಕ್", "கடிகாரம்", "క్లాక్", "గడియారం", "ക്ലോക്ക്", "ઘડિયાળ", "ਘੜੀ", "گھڑی", "ଘଡ଼ି")
    private val SETTINGS_WORD = k("setting", "settings", "सेटिंग", "सेटिंग्स", "सेटिंग्ज", "সেটিং", "সেটিংস", "ಸೆಟ್ಟಿಂಗ್", "ಸೆಟ್ಟಿಂಗ್ಸ್", "ಸೆಟ್ಟಿಂಗ್‌ಗಳು", "செட்டிங்", "செட்டிங்ஸ்", "సెట్టింగ్", "సెట్టింగ్స్", "സെറ്റിംഗ്", "സെറ്റിംഗ്സ്", "સેટિંગ", "ਸੈਟਿੰਗ", "سیٹنگ", "ସେଟିଂ")
    private val OPEN_WORD = k("open", "launch", "go to", "खोल", "खोलो", "खोलिए", "kholo", "khol do", "খোল", "খুলুন", "খোলো", "খুলে", "ತೆರೆ", "ತೆರೆಯಿರಿ", "ತೆರೆಯಿರಿ", "திற", "తెరువు", "తెరవండి", "തുറക്ക", "ખોલ", "ਖੋਲ", "کھول", "ଖୋଲ", "খোল")

    private val SNOOZE_WORD = k("snooze", "स्नूज़", "स्नूज", "স্নুজ", "ಸ್ನೂಜ್", "ஸ்னூஸ்", "స్నూజ్", "സ്നൂസ്", "સ્નૂઝ", "ਸਨੂਜ਼", "ਸਨੂਜ", "سنوز", "ସ୍ନୁଜ୍")
    private val DELETE_WORD = k(
        "delete", "remove", "cancel", "erase", "hata", "hatao", "hata do", "mita", "mita do", "mitao", "हटा", "मिटा", "डिलीट", "रद्द", "निकाल", "हटव", "काढून",
        "মুছ", "ডিলিট", "বাতিল", "সরিয়ে", "সরাও", "ಅಳಿಸ", "ಡಿಲೀಟ್", "ರದ್ದು", "ತೆಗೆ", "ತೆಗೆದು", "நீக்கு", "நீக்க", "அழி", "ரத்து", "తొలగ", "డిలీట్", "రద్దు", "తీసివేయ",
        "ഒഴിവാക്ക", "ഡിലീറ്റ്", "നീക്ക", "ഇല്ലാതാക്ക", "કાઢી", "ડિલીટ", "રદ", "હટાવ", "ਹਟਾ", "ਮਿਟਾ", "ਡਿਲੀਟ", "ਰੱਦ", "ہٹا", "حذف", "ڈیلیٹ", "منسوخ", "ହଟା", "ଡିଲିଟ", "ବାତିଲ", "ଲିଭା", "মচি", "আঁতৰা"
    )
    private val CHANGE_WORD = k(
        "change", "update", "edit", "modify", "reschedule", "shift", "move", "badlo", "badal", "badal do", "बदल", "चेंज", "अपडेट", "एडिट", "বদল", "পরিবর্তন", "চেঞ্জ", "আপডেট",
        "ಬದಲ", "ಬದಲಿಸ", "ಬದಲಾಯಿಸ", "ಚೇಂಜ್", "ಅಪ್ಡೇಟ್", "மாற்று", "மாற்ற", "మార్చ", "മാറ്റ", "બદલ", "ਬਦਲ", "بدل", "تبدیل", "ବଦଳ", "ପରିବର୍ତ୍ତନ", "সলনি"
    )
    private val ENABLE_WORD = k(
        "enable", "turn on", "switch on", "activate", "on kar", "chalu", "chalu kar", "चालू", "चालु", "ऑन", "शुरू कर", "सक्रिय", "চালু", "অন করো", "অন কর", "ಆನ್", "ಚಾಲು", "ಸಕ್ರಿಯ",
        "ஆன்", "இயக்கு", "ఆన్", "ప్రారంభించ", "ഓൺ", "ആക്ടീവ്", "ઓન", "ਆਨ", "چالو", "آن کر", "ଅନ୍", "ଅନ", "অন কৰ"
    )
    private val DISABLE_WORD = k(
        "disable", "turn off", "switch off", "deactivate", "off kar", "off karo", "band kar", "band karo", "band kardo", "बंद कर", "बन्द कर", "ऑफ", "बंद करो", "বন্ধ কর", "অফ", "ಆಫ್", "ಬಂದ್ ಮಾಡ", "ನಿಷ್ಕ್ರಿಯ",
        "ஆஃப்", "ஆப்", "ఆఫ్", "നിർജ്ജീവ", "ഓഫ്", "બંધ કર", "ઓફ", "ਬੰਦ ਕਰ", "ਆਫ", "بند کر", "آف", "ବନ୍ଦ କର", "ଅଫ", "বন্ধ কৰ"
    )
    private val STOP_WORD = k(
        "stop", "dismiss", "silence", "shut", "roko", "ruko", "chup", "रोको", "रुको", "चुप", "বন্ধ", "থামাও", "থামো", "ನಿಲ್ಲಿಸು", "ನಿಲ್ಲಿಸಿ", "ಸ್ಟಾಪ್", "நிறுத்து", "நிறுத்தவும்",
        "ఆపు", "ఆపండి", "స్టాప్", "നിർത്ത", "थांबव", "બંધ કરો", "અટકાવ", "ਰੋਕੋ", "ਬੰਦ", "روکو", "بند", "ଅଟକାଅ", "ବନ୍ଦ", "ৰখাওক"
    )
    private val NEXT_WORD = k(
        "next", "upcoming", "coming", "when is", "kab", "agla", "agli", "अगला", "अगली", "अगले", "कब", "পরবর্তী", "পরের", "পরেরটা", "ಮುಂದಿನ", "அடுத்த", "తదుపరి", "తర్వాతి", "അടുത്ത",
        "पुढचा", "पुढील", "पुढचे", "આગામી", "ਅਗਲਾ", "ਅਗਲੀ", "اگلا", "اگلی", "ପରବର୍ତ୍ତୀ", "ପରବର୍ତୀ", "পিছৰ", "পৰৱৰ্তী"
    )
    private val LIST_WORD = k(
        "show", "list", "display", "see", "read out", "dikhao", "dikha", "batao", "दिखा", "दिखाओ", "दिखाइए", "बताओ", "सभी", "सारे", "सब", "দেখাও", "দেখাও", "সব", "সবগুলো", "সবকটা", "ತೋರಿಸು", "ತೋರಿಸಿ",
        "ಎಲ್ಲಾ", "ಎಲ್ಲ", "காட்டு", "எல்லா", "చూపించు", "అన్ని", "കാണിക്ക", "എല്ലാ", "दाखवा", "सर्व", "બતાવો", "બધા", "ਦਿਖਾਓ", "ਸਾਰੇ", "دکھاؤ", "سب", "ଦେଖାଅ", "ସବୁ", "দেখুৱাওক", "সকলো"
    )
    private val ALL_WORD = k("all", "every alarm", "saare", "sare", "sabhi", "सभी", "सारे", "सब", "সব", "সবগুলো", "সবকটা", "ಎಲ್ಲಾ", "ಎಲ್ಲ", "எல்லா", "అన్ని", "എല്ലാ", "सर्व", "બધા", "ਸਾਰੇ", "سب", "ସବୁ", "সকলো")

    private val REPEAT_MARK = k(
        "every", "each", "daily", "weekly", "repeat", "repeating", "recurring", "har", "roz", "rozana", "hafte", "हर", "रोज़", "रोज", "रोजाना", "प्रतिदिन", "हफ्ते",
        "প্রতি", "প্রতিদিন", "রোজ", "ಪ್ರತಿ", "ಪ್ರತಿದಿನ", "ಪ್ರತಿ ದಿನ", "ಹರ", "ஒவ்வொரு", "தினமும்", "ప్రతి", "ప్రతిరోజు", "ഓരോ", "എല്ലാ ദിവസവും", "ദിവസവും", "दररोज", "દર", "દરરોજ", "ਹਰ", "ਰੋਜ਼", "ہر", "روزانہ", "ପ୍ରତି", "ପ୍ରତିଦିନ", "প্ৰতি", "প্ৰতিদিনে"
    )
    private val DAILY_WORD = k(
        "daily", "every day", "everyday", "each day", "roz", "rozana", "har din", "रोज़", "रोज", "रोजाना", "हर दिन", "प्रतिदिन", "প্রতিদিন", "রোজ", "ಪ್ರತಿದಿನ", "ಪ್ರತಿ ದಿನ", "ದಿನವೂ", "தினமும்",
        "ప్రతిరోజు", "ప్రతి రోజు", "ദിവസവും", "എല്ലാ ദിവസവും", "दररोज", "દરરોજ", "ਰੋਜ਼", "روزانہ", "ପ୍ରତିଦିନ", "প্ৰতিদিনে"
    )
    private val WEEKDAYS_WORD = k("weekdays", "weekday", "week days", "workdays", "workday", "working days", "सप्ताह के दिन", "कार्य दिवस", "কর্মদিবস", "ಕೆಲಸದ ದಿನ")
    private val WEEKEND_WORD = k("weekend", "weekends", "वीकेंड", "सप्ताहांत", "উইকেন্ড", "ವಾರಾಂತ್ಯ", "வார இறுதி", "వారాంతం", "വാരാന്ത്യം")

    private val DAYS: List<Pair<DayOfWeek, List<String>>> = listOf(
        DayOfWeek.MONDAY to k("monday", "mondays", "mon", "somvar", "somwar", "सोमवार", "সোমবার", "সোমবাৰ", "ಸೋಮವಾರ", "திங்கள்", "సోమవారం", "తిങ్ങൾ", "തിങ്കൾ", "સોમવાર", "ਸੋਮਵਾਰ", "پیر", "ସୋମବାର"),
        DayOfWeek.TUESDAY to k("tuesday", "tuesdays", "tue", "tues", "mangalvar", "mangalwar", "मंगलवार", "মঙ্গলবার", "মঙ্গলবাৰ", "ಮಂಗಳವಾರ", "செவ்வாய்", "మంగళవారం", "ചൊവ്വ", "मंगळवार", "મંગળવાર", "ਮੰਗਲਵਾਰ", "منگل", "ମଙ୍ଗଳବାର"),
        DayOfWeek.WEDNESDAY to k("wednesday", "wednesdays", "wed", "budhvar", "budhwar", "बुधवार", "বুধবার", "বুধবাৰ", "ಬುಧವಾರ", "புதன்", "బుధవారం", "ബുധൻ", "બુધવાર", "ਬੁੱਧਵਾਰ", "بدھ", "ବୁଧବାର"),
        DayOfWeek.THURSDAY to k("thursday", "thursdays", "thu", "thur", "thurs", "guruvar", "brihaspativar", "veervar", "गुरुवार", "बृहस्पतिवार", "गुरूवार", "বৃহস্পতিবার", "বৃহস্পতিবাৰ", "ಗುರುವಾರ", "வியாழன்", "గురువారం", "വ്യാഴം", "ગુરુવાર", "ਵੀਰਵਾਰ", "جمعرات", "ଗୁରୁବାର"),
        DayOfWeek.FRIDAY to k("friday", "fridays", "fri", "shukravar", "shukrawar", "शुक्रवार", "শুক্রবার", "শুক্ৰবাৰ", "ಶುಕ್ರವಾರ", "வெள்ளி", "శుక్రవారం", "വെള്ളി", "શુક્રવાર", "ਸ਼ੁੱਕਰਵਾਰ", "جمعہ", "ଶୁକ୍ରବାର"),
        DayOfWeek.SATURDAY to k("saturday", "saturdays", "sat", "shanivar", "shaniwar", "शनिवार", "শনিবার", "শনিবাৰ", "ಶನಿವಾರ", "சனி", "శనివారం", "ശനി", "શનિવાર", "ਸ਼ਨੀਵਾਰ", "ہفتہ", "ଶନିବାର"),
        DayOfWeek.SUNDAY to k("sunday", "sundays", "sun", "ravivar", "itwar", "raviwar", "रविवार", "इतवार", "রবিবার", "দেওবাৰ", "ಭಾನುವಾರ", "ஞாயிறு", "ఆదివారం", "ഞായർ", "રવિવાર", "ਐਤਵਾਰ", "اتوار", "ରବିବାର")
    )

    private val TODAY_WORD = k("today", "aaj", "आज", "আজ", "আজি", "ಇಂದು", "இன்று", "ఈరోజు", "ఈ రోజు", "ഇന്ന്", "આજે", "ਅੱਜ", "آج", "ଆଜି")
    private val TOMORROW_WORD = k("tomorrow", "kal", "कल", "उद्या", "কাল", "কালি", "আগামীকাল", "ನಾಳೆ", "நாளை", "రేపు", "നാളെ", "કાલે", "ਕੱਲ੍ਹ", "ਕੱਲ", "کل", "ଆସନ୍ତାକାଲି", "ଆସନ୍ତା କାଲି", "ପରଦିନ")
    private val DAY_AFTER_WORD = k("day after tomorrow", "parso", "परसों", "परसो", "পরশু", "ನಾಡಿದ್ದು", "ನಾಡಿದ್ದು", "நாளைமறுநாள்", "ఎల్లుండి", "മറ്റന്നാൾ", "परवा", "પરમદિવસે", "ਪਰਸੋਂ", "پرسوں", "ପରଶୁ")

    private val MORNING_WORD = k("morning", "subah", "subha", "savere", "tadke", "सुबह", "सवेरे", "तड़के", "भोर", "सकाळी", "সকাল", "ভোর", "ಬೆಳಿಗ್ಗೆ", "ಬೆಳಗ್ಗೆ", "ಮುಂಜಾನೆ", "காலை", "ఉదయం", "తెల్లవారుజామున", "രാവിലെ", "સવારે", "ਸਵੇਰੇ", "صبح", "ସକାଳ", "ৰাতিপুৱা", "ৰাতিপুৱাই", "fajr")
    private val AFTERNOON_WORD = k("afternoon", "dopahar", "dopaher", "दोपहर", "दुपारी", "দুপুর", "দুপৰীয়া", "ಮಧ್ಯಾಹ್ನ", "மதியம்", "మధ్యాహ్నం", "ഉച്ച", "ഉച്ചയ്ക്ക്", "બપોરે", "ਦੁਪਹਿਰ", "دوپہر", "ଅପରାହ୍ନ", "ମଧ୍ୟାହ୍ନ")
    private val EVENING_WORD = k("evening", "shaam", "sham", "शाम", "संध्याकाळी", "সন্ধ্যা", "বিকেল", "বিকাল", "সন্ধিয়া", "ಸಂಜೆ", "மாலை", "సాయంత్రం", "വൈകുന്നേരം", "સાંજે", "ਸ਼ਾਮ", "شام", "ସନ୍ଧ୍ୟା")
    private val NIGHT_WORD = k("night", "tonight", "raat", "raatri", "रात", "रात्री", "রাত", "ৰাতি", "ರಾತ್ರಿ", "இரவு", "రాత్రి", "രാത്രി", "રાત્રે", "ਰਾਤ", "رات", "ରାତି")
    private val NOON_WORD = k("noon", "midday")
    private val MIDNIGHT_WORD = k("midnight", "aadhi raat", "आधी रात", "मध्यरात्रि", "মধ্যরাত", "ಮಧ್ಯರಾತ್ರಿ", "நள்ளிரவு", "అర్ధరాత్రి", "അർദ്ധരാത്രി")

    private val LABEL_LATIN = Regex("(?:called|named|label(?:l?ed)?|with label|with name|with the name|titled)\\s+([a-z0-9][a-z0-9 ]*?)(?=\\s+(?:at|for|every|each|on|tomorrow|today|alarm|and|in)\\b|\\s+\\d|$)")
    private val LABEL_SUFFIXES = k(
        "के नाम से", "नाम से", "नाम का", "नाम वाला", "नाम की", "नाम वाली", "के लिए", "वाला", "वाली", "नामक",
        "নামে", "নামের", "নাম দিয়ে", "নামটি", "ಹೆಸರಿನ", "ಹೆಸರಿನಲ್ಲಿ", "ಎಂಬ", "ಹೆಸರಿನಲ್ಲಿ",
        "என்ற பெயரில்", "பெயரில்", "అనే పేరుతో", "పేరుతో", "എന്ന പേരിൽ", "പേരിൽ", "नावाचा", "नावाने", "નામથી", "નામનો", "ਨਾਮ ਨਾਲ", "ਨਾਮ ਦਾ", "نام سے", "نام کا", "ନାମରେ", "ନାମର", "নামেৰে", "নামৰ"
    )
    private val FUNCTION_WORDS = k(
        "my", "the", "an", "a", "please", "jarvis", "hey", "for", "at", "to", "me", "of", "up", "set", "create", "add", "make", "new", "alarm", "alarms",
        "mera", "meri", "mere", "mujhe", "ek", "laga", "lagao", "laga do", "bana", "banao", "rakh", "rakho", "do", "de", "dena", "kar", "karo", "ko", "ka", "ki", "ke", "se",
        "मेरा", "मेरी", "मेरे", "मुझे", "एक", "लगाओ", "लगा", "लगा दो", "लगा दे", "बनाओ", "बना", "रख", "रखो", "दो", "दे", "देना", "कर", "करो", "को", "का", "की", "के", "से", "अलार्म", "सेट", "बजे",
        "আমার", "আমাকে", "একটা", "একটি", "সেট", "করো", "কর", "দাও", "দিন", "অ্যালার্ম", "এলার্ম", "টা", "টায়", "ನನ್ನ", "ನನಗೆ", "ಒಂದು", "ಇಡು", "ಇಡಿ", "ಮಾಡು", "ಮಾಡಿ", "ಹಾಕು", "ಹಾಕಿ", "ಸೆಟ್", "ಅಲಾರಂ"
    )

    // ---- Number words (hours 1..12) -------------------------------------------------------------

    private val HOUR_WORDS: Map<String, Int> = HashMap<String, Int>().apply {
        fun put(n: Int, vararg ws: String) = ws.forEach { this[fold(it)] = n }
        put(1, "एक", "ek", "এক", "ಒಂದು", "ಒಂದ", "one")
        put(2, "दो", "do", "দুই", "ಎರಡು", "ಎರಡ", "two")
        put(3, "तीन", "teen", "তিন", "ಮೂರು", "ಮೂರ", "three")
        put(4, "चार", "char", "chaar", "চার", "ನಾಲ್ಕು", "ನಾಲ್ಕ", "four")
        put(5, "पाँच", "पांच", "paanch", "panch", "পাঁচ", "পাচ", "ಐದು", "ಐದ", "five")
        put(6, "छह", "छः", "छे", "छै", "छ", "chhe", "chhah", "chah", "che", "ছয়", "ছয়", "ছ", "ಆರು", "ಆರ", "six")
        put(7, "सात", "saat", "সাত", "ಏಳು", "ಏಳ", "seven")
        put(8, "आठ", "aath", "আট", "ಎಂಟು", "ಎಂಟ", "eight")
        put(9, "नौ", "nau", "নয়", "ಒಂಬತ್ತು", "ಒಂಬತ್ತ", "nine")
        put(10, "दस", "das", "দশ", "ಹತ್ತು", "ಹತ್ತ", "ten")
        put(11, "ग्यारह", "gyarah", "gyaarah", "এগারো", "এগার", "ಹನ್ನೊಂದು", "ಹನ್ನೊಂದ", "eleven")
        put(12, "बारह", "barah", "baarah", "বারো", "বার", "ಹನ್ನೆರಡು", "ಹನ್ನೆರಡ", "twelve")
    }
    private val HOUR_ALT: String = HOUR_WORDS.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
    private const val NB = "(?<![\\p{L}\\p{M}])"
    private const val NA = "(?![\\p{L}\\p{M}])"

    private val H_MARK = listOf(
        "बजकर", "बजे", "बज", "baje", "bje", "bajey", "টায়", "টার", "টা", "ಗಂಟೆಗೆ", "ಗಂಟೆ", "ಕ್ಕೆ", "மணிக்கு", "மணி", "గంటలకు", "గంటకు", "గంట", "മണിക്ക്", "മണിക്കു", "മണി",
        "वाजता", "वाजून", "વાગ્યે", "વાગે", "ਵਜੇ", "بجے", "ଟାରେ", "ଟା", "ଘଣ୍ଟା", "বজাত", "oclock", "hours", "hrs"
    ).map { fold(it) }.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
    private val MIN_MARK = listOf(
        "मिनट", "मिनिट", "minutes", "minute", "mins", "min", "মিনিটে", "মিনিট", "ನಿಮಿಷಕ್ಕೆ", "ನಿಮಿಷ", "நிமிடம்", "నిమిషాలకు", "నిమిషం", "മിനിറ്റ്", "मिनिटे", "મિનિટ", "ਮਿੰਟ", "منٹ", "ମିନିଟ"
    ).map { fold(it) }.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }

    private val FRACTION_HI = Regex("$NB(${fold("साढ़े|साढे|साड़े|सवा|पौने|पौन|saade|sade|sadhe|sawa|sava|paune|poune")})\\s*($HOUR_ALT)(?:\\s*(?:$H_MARK))?$NA")
    private val FRACTION_BN = Regex("(${fold("সাড়ে|সোয়া|সোওয়া|পৌনে")})\\s*($HOUR_ALT)(?:${fold("টায়|টার|টা|টে")})?")
    private val DEDH_DHAI = Regex("$NB(${fold("डेढ़|डेढ|dedh|ढाई|dhai|দেড়|দেড|আড়াই")})(?:\\s*(?:$H_MARK))?$NA")
    private val KN_HALF = Regex("(${fold("ಒಂದ|ಎರಡ|ಮೂರ|ನಾಲ್ಕ|ಐದ|ಆರ|ಏಳ|ಎಂಟ|ಒಂಬತ್ತ|ಹತ್ತ|ಹನ್ನೊಂದ|ಹನ್ನೆರಡ")})[${fold("ುೂ")}]?\\s*${fold("ವರೆ")}")
    // Bengali "একটা" means "a / one" (as in "an alarm"), not 1 o'clock, so only "একটায়" counts as the hour.
    private val HOUR_ALT_NO_BN_ONE: String =
        HOUR_WORDS.keys.filter { it != fold("এক") }.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
    private val WORD_HOUR = Regex("$NB($HOUR_ALT_NO_BN_ONE)(?:${fold("ು")})?\\s*(?:$H_MARK)$NA")
    private val BN_ONE_OCLOCK = Regex("$NB(${fold("এক")})(?:${fold("টায়|টার")})")
    private val KN_DATIVE = Regex("(${fold("ಒಂದ|ಎರಡ|ಮೂರ|ನಾಲ್ಕ|ಐದ|ಆರ|ಏಳ|ಎಂಟ|ಒಂಬತ್ತ|ಹತ್ತ|ಹನ್ನೊಂದ|ಹನ್ನೆರಡ")})${fold("ಕ್ಕೆ")}")

    private fun hourOf(word: String): Int? = HOUR_WORDS[fold(word)]
    private val SAADE = k("साढ़े", "साढे", "साड़े", "saade", "sade", "sadhe").toSet()
    private val SAWA = k("सवा", "sawa", "sava").toSet()
    private val DEDH = k("डेढ़", "डेढ", "dedh", "দেড়", "দেড").toSet()

    /** Rewrites spoken time words ("साढ़े छह", "সাড়ে ছয়টা", "ಆರೂವರೆ", "half past six") into digits so one reader handles them all. */
    private fun rewriteTimeWords(input: String): String {
        var t = input
        t = FRACTION_HI.replace(t) { m ->
            val h = hourOf(m.groupValues[2]) ?: return@replace m.value
            val w = m.groupValues[1]
            when {
                w in SAADE -> " $h:30 "
                w in SAWA -> " $h:15 "
                else -> " ${if (h == 1) 12 else h - 1}:45 "
            }
        }
        t = FRACTION_BN.replace(t) { m ->
            val h = hourOf(m.groupValues[2]) ?: return@replace m.value
            val w = m.groupValues[1]
            when {
                w == fold("সাড়ে") -> " $h:30 "
                w == fold("সোয়া") || w == fold("সোওয়া") -> " $h:15 "
                else -> " ${if (h == 1) 12 else h - 1}:45 "
            }
        }
        t = DEDH_DHAI.replace(t) { m ->
            if (m.groupValues[1] in DEDH) " 1:30 " else " 2:30 "
        }
        t = KN_HALF.replace(t) { m -> " ${hourOf(m.groupValues[1]) ?: return@replace m.value}:30 " }
        t = KN_DATIVE.replace(t) { m -> " ${hourOf(m.groupValues[1]) ?: return@replace m.value} baje " }
        t = BN_ONE_OCLOCK.replace(t, " 1 baje ")
        t = BN_ONE_OCLOCK.replace(t, " 1 baje ")
        t = WORD_HOUR.replace(t) { m ->
            val h = hourOf(m.groupValues[1]) ?: return@replace m.value
            " $h baje "
        }
        // English: "half past six", "quarter past six", "quarter to seven", "six thirty", "noon", "midnight".
        t = t.replace(Regex("(?<![a-z])half past ($HOUR_ALT)(?![a-z])")) { m -> " ${hourOf(m.groupValues[1])}:30 " }
        t = t.replace(Regex("(?<![a-z])quarter past ($HOUR_ALT)(?![a-z])")) { m -> " ${hourOf(m.groupValues[1])}:15 " }
        t = t.replace(Regex("(?<![a-z])quarter to ($HOUR_ALT)(?![a-z])")) { m -> val h = hourOf(m.groupValues[1])!!; " ${if (h == 1) 12 else h - 1}:45 " }
        t = t.replace(Regex("(?<![a-z])($HOUR_ALT) (fifteen|thirty|forty five|oh [1-9]|ten|twenty|forty|fifty)(?![a-z])")) { m ->
            val h = hourOf(m.groupValues[1])!!
            val min = when (val w = m.groupValues[2]) {
                "fifteen" -> 15; "thirty" -> 30; "forty five" -> 45; "ten" -> 10; "twenty" -> 20; "forty" -> 40; "fifty" -> 50
                else -> w.removePrefix("oh ").toInt()
            }
            " $h:${min.toString().padStart(2, '0')} "
        }
        t = t.replace(Regex("(?<![a-z])($HOUR_ALT)(?= ?(?:am|pm)(?![a-z]))")) { m -> " ${hourOf(m.groupValues[1])} " }
        t = t.replace(Regex("(?<![a-z])($HOUR_ALT) oclock(?![a-z])")) { m -> " ${hourOf(m.groupValues[1])} oclock " }
        t = t.replace(Regex("\\s+"), " ")
        return t
    }

    // ---- Reading times --------------------------------------------------------------------------

    private data class RawTime(val hour: Int, val minute: Int, val start: Int, val end: Int, val marker: Meridiem?)

    private val R_COLON = Regex("(?<!\\d)(\\d{1,2}):(\\d{2})(?!\\d)")
    private val R_AMPM = Regex("(?<![\\d:])(\\d{1,2})\\s*(am|pm)(?![a-z])")
    private val R_BAJKAR = Regex("(?<![\\d:])(\\d{1,2})\\s*(?:${fold("बजकर|bajkar|baj kar")})\\s*(\\d{1,2})(?!\\d)")
    private val R_MARKED = Regex("(?<![\\d:])(\\d{1,2})\\s*(?:$H_MARK)(?:\\s*(\\d{1,2})\\s*(?:$MIN_MARK))?")
    private val R_BARE = Regex("(?<![\\d:])(\\d{1,2})(?![\\d:])")
    private val AFTER_MARKER = Regex("^\\s*(am|pm)(?![a-z])")

    private fun findTimes(t: String): List<RawTime> {
        val found = ArrayList<RawTime>()
        fun overlaps(s: Int, e: Int) = found.any { s < it.end && e > it.start }
        fun add(h: Int, m: Int, s: Int, e: Int, marker: Meridiem?) {
            if (h !in 0..24 || m !in 0..59 || overlaps(s, e)) return
            val after = AFTER_MARKER.find(t.substring(e))
            val mk = marker ?: after?.let { if (it.groupValues[1] == "am") Meridiem.AM else Meridiem.PM }
            val end = e + (if (marker == null && after != null) after.value.length else 0)
            found += RawTime(h % 24, m, s, end, mk)
        }
        R_COLON.findAll(t).forEach { add(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.range.first, it.range.last + 1, null) }
        R_AMPM.findAll(t).forEach { add(it.groupValues[1].toInt(), 0, it.range.first, it.range.last + 1, if (it.groupValues[2] == "am") Meridiem.AM else Meridiem.PM) }
        R_BAJKAR.findAll(t).forEach { add(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.range.first, it.range.last + 1, null) }
        R_MARKED.findAll(t).forEach { m ->
            val minute = m.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 0
            add(m.groupValues[1].toInt(), minute, m.range.first, m.range.last + 1, null)
        }
        return found.sortedBy { it.start }
    }

    private enum class Daypart { MORNING, AFTERNOON, EVENING, NIGHT, NOON, MIDNIGHT }

    private fun dayparts(t: String): List<Daypart> {
        val out = ArrayList<Daypart>()
        if (has(t, MIDNIGHT_WORD)) out += Daypart.MIDNIGHT
        if (has(t, NOON_WORD)) out += Daypart.NOON
        if (has(t, MORNING_WORD)) out += Daypart.MORNING
        if (has(t, AFTERNOON_WORD)) out += Daypart.AFTERNOON
        if (has(t, EVENING_WORD)) out += Daypart.EVENING
        if (has(t, NIGHT_WORD) && !has(t, MIDNIGHT_WORD)) out += Daypart.NIGHT
        return out
    }

    /** What part of the day says about an hour. Null when it does not settle it (ask the person). */
    private fun meridiemFromDaypart(part: Daypart, hour: Int): Meridiem? = when (part) {
        Daypart.MORNING -> if (hour in 1..11) Meridiem.AM else null
        Daypart.AFTERNOON -> if (hour == 12 || hour in 1..6) Meridiem.PM else null
        Daypart.EVENING -> if (hour in 1..11) Meridiem.PM else null
        Daypart.NIGHT -> when (hour) { 12 -> Meridiem.AM; in 1..4 -> Meridiem.AM; in 5..11 -> Meridiem.PM; else -> null }
        Daypart.NOON -> if (hour == 12) Meridiem.PM else null
        Daypart.MIDNIGHT -> if (hour == 12) Meridiem.AM else null
    }

    private fun toParsed(raw: RawTime, parts: List<Daypart>, singleTime: Boolean): ParsedTime {
        val h = raw.hour
        if (h == 0 || h in 13..23) return ParsedTime(h, raw.minute, null, true)
        if (h == 24) return ParsedTime(0, raw.minute, null, true)
        raw.marker?.let { return ParsedTime(h, raw.minute, it, false) }
        if (singleTime) {
            for (p in parts) meridiemFromDaypart(p, h)?.let { return ParsedTime(h, raw.minute, it, false) }
        }
        return ParsedTime(h, raw.minute, null, false)
    }

    /** All times in the text, in order. Spoken words are turned into digits first. */
    fun readTimes(rawText: String): List<ParsedTime> {
        val t = rewriteTimeWords(normalize(rawText))
        return readTimesNormalized(t)
    }

    private fun readTimesNormalized(t: String): List<ParsedTime> {
        var times = findTimes(t)
        val parts = dayparts(t)
        // "noon" / "midnight" with no number is a time on its own.
        if (times.isEmpty()) {
            if (Daypart.MIDNIGHT in parts) return listOf(ParsedTime(0, 0, null, true))
            if (Daypart.NOON in parts) return listOf(ParsedTime(12, 0, Meridiem.PM, false))
        }
        // A lone bare number ("alarm for 7", "7 का अलार्म") counts as an hour when nothing else is numeric.
        if (times.isEmpty()) {
            val bare = R_BARE.findAll(t).toList()
            if (bare.size == 1) {
                val n = bare[0].groupValues[1].toInt()
                if (n in 0..23) times = listOf(RawTime(n, 0, bare[0].range.first, bare[0].range.last + 1, null))
            }
        }
        val single = times.size == 1
        val parsed = times.map { toParsed(it, parts, single) }.toMutableList()
        // Two times: give the unmarked one a part of day only when there is exactly one such word and one unmarked time.
        if (!single) {
            val unmarked = parsed.indices.filter { parsed[it].needsMeridiem && !parsed[it].explicit24h }
            if (unmarked.size == 1 && parts.size == 1) {
                val i = unmarked[0]
                meridiemFromDaypart(parts[0], parsed[i].hour)?.let { parsed[i] = parsed[i].copy(meridiem = it) }
            }
        }
        return parsed
    }

    // ---- Days and dates -------------------------------------------------------------------------

    private fun weekdaysIn(t: String): Set<DayOfWeek> {
        val set = LinkedHashSet<DayOfWeek>()
        for ((day, names) in DAYS) if (has(t, names)) set += day
        return set
    }

    private fun repeatDaysIn(t: String): Set<DayOfWeek> {
        if (has(t, DAILY_WORD)) return DayOfWeek.values().toSet()
        if (has(t, WEEKDAYS_WORD)) return setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        if (has(t, WEEKEND_WORD)) return setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        return emptySet()
    }

    private fun dateWordIn(t: String): DateWord? = when {
        has(t, DAY_AFTER_WORD) -> DateWord.DAY_AFTER
        has(t, TOMORROW_WORD) -> DateWord.TOMORROW
        has(t, TODAY_WORD) -> DateWord.TODAY
        else -> null
    }

    private fun partOfDayIn(t: String): PartOfDay? = when {
        has(t, MORNING_WORD) -> PartOfDay.MORNING
        has(t, AFTERNOON_WORD) -> PartOfDay.AFTERNOON
        has(t, EVENING_WORD) -> PartOfDay.EVENING
        has(t, NIGHT_WORD) -> PartOfDay.NIGHT
        else -> null
    }

    // ---- Labels ---------------------------------------------------------------------------------

    private fun labelFrom(rawText: String, t: String): String? {
        val original = Normalizer.normalize(rawText, Normalizer.Form.NFKC).trim()
        // The label keeps the user's own spelling and capitalisation; find it in the original by position when possible.
        LABEL_LATIN.find(t)?.let { m ->
            val value = m.groupValues[1].trim()
            if (value.isNotEmpty() && value !in setOf("me", "my", "the", "an", "a")) return recase(original, value)
        }
        for (suffix in LABEL_SUFFIXES.sortedByDescending { it.length }) {
            val idx = t.indexOf(suffix)
            if (idx <= 0) continue
            val before = t.substring(0, idx).trim().split(" ").filter { it.isNotEmpty() }
            val picked = ArrayList<String>()
            for (token in before.asReversed()) {
                if (token in FUNCTION_WORDS || token.any { it.isDigit() } || has(token, ALARM_NOUN)) break
                if (has(token, DELETE_WORD + CHANGE_WORD + ENABLE_WORD + DISABLE_WORD + LIST_WORD)) break
                picked.add(0, token)
                if (picked.size == 3) break
            }
            if (picked.isNotEmpty()) return recase(original, picked.joinToString(" "))
        }
        return null
    }

    /** Gives a lower-cased label back the capitalisation the person used, when it can be found in what they said. */
    private fun recase(original: String, label: String): String {
        val idx = fold(original).indexOf(label)
        if (idx >= 0 && idx + label.length <= original.length) {
            val slice = original.substring(idx, idx + label.length)
            if (fold(slice) == label) return slice
        }
        return if (label.any { it.isLetter() && it.code < 128 }) label.replaceFirstChar { it.uppercase(Locale.ROOT) } else label
    }

    // ---- Parsing a whole command ----------------------------------------------------------------

    /** True when the words could be an alarm or clock command in any supported language (cheap check before parsing). */
    fun looksLikeClockCommand(raw: String): Boolean {
        val t = normalize(raw)
        return has(t, ALARM_NOUN) || has(t, WAKE_PHRASE) || has(t, SNOOZE_WORD) ||
            (has(t, CLOCK_WORD) && has(t, SETTINGS_WORD))
    }

    fun parse(raw: String): ClockParse? {
        val t0 = normalize(raw)
        if (t0.isEmpty()) return null
        val language = detectLanguage(raw)
        val hasNoun = has(t0, ALARM_NOUN)
        val hasWake = has(t0, WAKE_PHRASE)
        val hasSnooze = has(t0, SNOOZE_WORD)
        val clockSettings = has(t0, CLOCK_WORD) && has(t0, SETTINGS_WORD)
        val alarmSettings = hasNoun && has(t0, SETTINGS_WORD)
        if (!hasNoun && !hasWake && !hasSnooze && !clockSettings) return null

        fun result(intent: ClockIntent) = ClockParse(intent, language, t0)

        if ((clockSettings || alarmSettings) && has(t0, OPEN_WORD)) return result(ClockIntent.OpenSettings)
        if (clockSettings || alarmSettings) return result(ClockIntent.OpenSettings)
        if (hasSnooze) return result(ClockIntent.Snooze)

        val t = rewriteTimeWords(t0)
        val times = readTimesNormalized(t)
        val weekdays = weekdaysIn(t)
        val repeating = repeatDaysIn(t)
        val hasRepeatMark = has(t, REPEAT_MARK)

        val wantsDelete = has(t, DELETE_WORD)
        val wantsChange = has(t, CHANGE_WORD)
        val wantsEnable = has(t, ENABLE_WORD)
        val wantsDisable = has(t, DISABLE_WORD)
        val wantsStop = has(t, STOP_WORD)
        val wantsNext = has(t, NEXT_WORD)
        val wantsList = has(t, LIST_WORD)
        val all = has(t, ALL_WORD)

        fun selector(first: ParsedTime?): Selector = Selector(
            time = first,
            partOfDay = if (first == null) partOfDayIn(t) else null,
            weekday = weekdays.singleOrNull(),
            all = all && first == null
        )

        return when {
            wantsDelete && hasNoun -> result(ClockIntent.Delete(selector(times.firstOrNull())))
            wantsChange && hasNoun -> {
                val sel: Selector
                val newTime: ParsedTime?
                if (times.size >= 2) { sel = selector(times[0]); newTime = times[1] } else { sel = selector(null); newTime = times.firstOrNull() }
                val newDays = (repeating.ifEmpty { if (hasRepeatMark || weekdays.size > 1) weekdays else emptySet() }).takeIf { it.isNotEmpty() }
                result(ClockIntent.Change(sel, newTime, newDays, null))
            }
            wantsEnable && hasNoun && !wantsDisable -> result(ClockIntent.Enable(selector(times.firstOrNull())))
            wantsDisable && hasNoun && !hasWake -> result(ClockIntent.Disable(selector(times.firstOrNull())))
            wantsStop && hasNoun && times.isEmpty() && !wantsNext && !wantsList -> result(ClockIntent.Stop)
            wantsNext && hasNoun && times.isEmpty() -> result(ClockIntent.NextAlarm)
            wantsList && hasNoun && times.isEmpty() && (all || !has(t, k("set", "create", "make", "add", "लगा", "बना", "সেট", "ಇಡು", "ಹಾಕು"))) -> result(ClockIntent.ListAlarms)
            else -> {
                if (!hasNoun && !hasWake) return null
                val days = repeating.ifEmpty { if ((hasRepeatMark && weekdays.isNotEmpty()) || weekdays.size > 1) weekdays else emptySet() }
                val onWeekday = if (days.isEmpty()) weekdays.singleOrNull() else null
                val dateWord = if (days.isEmpty() && onWeekday == null) dateWordIn(t) else null
                result(ClockIntent.SetAlarm(times.firstOrNull(), days, dateWord, onWeekday, labelFrom(raw, t0)))
            }
        }
    }

    // ---- Answers to a question the assistant asked ---------------------------------------------

    /** am/pm (or a part of the day) said on its own, as the answer to "AM or PM?". */
    fun readMeridiemAnswer(raw: String, hour: Int): Meridiem? {
        val t = normalize(raw)
        if (Regex("(?<![a-z])am(?![a-z])").containsMatchIn(t)) return Meridiem.AM
        if (Regex("(?<![a-z])pm(?![a-z])").containsMatchIn(t)) return Meridiem.PM
        for (p in dayparts(t)) meridiemFromDaypart(p, hour)?.let { return it }
        return null
    }

    /** A number or ordinal ("1", "second", "दूसरा", "দ্বিতীয়") given as the answer to "which one?". */
    fun readChoiceNumber(raw: String): Int? {
        val t = normalize(raw)
        Regex("(?<![\\d:])(\\d{1,2})(?![\\d:])").find(t)?.let { return it.groupValues[1].toInt() }
        val ordinals = listOf(
            1 to k("first", "pehla", "pehli", "पहला", "पहली", "প্রথম", "ಮೊದಲ", "ಮೊದಲನೆಯ"),
            2 to k("second", "doosra", "dusra", "दूसरा", "दूसरी", "দ্বিতীয়", "দ্বিতীয়", "ಎರಡನೆಯ", "ಎರಡನೇ"),
            3 to k("third", "teesra", "तीसरा", "तीसरी", "তৃতীয়", "ಮೂರನೆಯ", "ಮೂರನೇ"),
            4 to k("fourth", "chautha", "चौथा", "চতুর্থ", "ನಾಲ್ಕನೆಯ", "ನಾಲ್ಕನೇ")
        )
        for ((n, words) in ordinals) if (has(t, words)) return n
        return null
    }

    /** Is this a yes / no? Null when it is neither. */
    fun readYesNo(raw: String): Boolean? {
        val t = normalize(raw)
        val yes = k("yes", "yeah", "yep", "sure", "ok", "okay", "haan", "ha", "han", "हाँ", "हां", "हा", "जी", "ठीक है", "হ্যাঁ", "হ্যা", "হাঁ", "ಹೌದು", "ಸರಿ", "ஆம்", "అవును", "അതെ", "હા", "ਹਾਂ", "جی ہاں", "ہاں", "ହଁ", "হয়")
        val no = k("no", "nope", "nahi", "nahin", "na", "नहीं", "नही", "ना", "না", "ಇಲ್ಲ", "வேண்டாம்", "இல்லை", "కాదు", "వద్దు", "അല്ല", "വേണ്ട", "ના", "ਨਹੀਂ", "نہیں", "ନା", "নহয়")
        return when {
            has(t, no) -> false
            has(t, yes) -> true
            else -> null
        }
    }

    /** Heuristic: does the text carry an am/pm or part-of-day clue (used to check the AI did not invent one)? */
    fun hasMeridiemClue(raw: String): Boolean {
        val t = rewriteTimeWords(normalize(raw))
        if (Regex("(?<![a-z])(am|pm)(?![a-z])").containsMatchIn(t)) return true
        if (dayparts(t).isNotEmpty()) return true
        return findTimes(t).any { it.hour == 0 || it.hour >= 13 || it.marker != null }
    }
}
