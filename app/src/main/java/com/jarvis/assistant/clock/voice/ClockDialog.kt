package com.jarvis.assistant.clock.voice

import com.jarvis.assistant.clock.AlarmItem
import java.text.Normalizer
import java.util.Locale

/** The words said back, with the language to answer in. The text itself is produced by the Android layer from string resources. */
data class ClockReply(val outcome: ClockOutcome, val language: String)

/**
 * Holds a spoken/typed alarm conversation across turns, for the paths where the AI model is not doing the talking
 * (offline voice commands, typed commands): when a command needs an answer ("AM or PM?", "which alarm?"), it remembers the
 * question and reads the next sentence as the answer. Nothing is changed until the answer makes the command certain.
 */
class ClockDialog(private val ops: ClockOps, private val now: () -> Long = { System.currentTimeMillis() }) {

    private sealed class Pending {
        abstract val intent: ClockIntent
        abstract val language: String
        abstract val spoken: String?
        var askedAt: Long = 0L

        class Meridiem(override val intent: ClockIntent, val hour: Int, val minute: Int, val targetId: Long?, override val language: String, override val spoken: String?) : Pending()
        class Choice(override val intent: ClockIntent, val candidates: List<AlarmItem>, override val language: String, override val spoken: String?) : Pending()
        class Time(override val intent: ClockIntent, val targetId: Long?, override val language: String, override val spoken: String?) : Pending()
    }

    @Volatile private var pending: Pending? = null

    val hasPending: Boolean get() = current() != null
    fun clear() { pending = null }

    private fun current(): Pending? {
        val p = pending ?: return null
        if (now() - p.askedAt > EXPIRY_MS) { pending = null; return null }
        return p
    }

    /** Returns a reply if the sentence belongs to the alarm clock (a new command, or the answer to the last question), else null. */
    @Synchronized
    fun handle(raw: String): ClockReply? {
        current()?.let { p ->
            val lang = ClockCommandParser.detectLanguage(raw).let { if (it == "en") p.language else it }
            answer(p, raw, lang)?.let { return it }
        }
        val parsed = ClockCommandParser.parse(raw) ?: return null
        pending = null
        return run(parsed.intent, null, parsed.language, raw)
    }

    private fun run(intent: ClockIntent, targetId: Long?, language: String, spoken: String?): ClockReply {
        val outcome = ops.execute(intent, targetId, spoken)
        if (outcome is ClockOutcome.Ask) {
            pending = when (outcome.question) {
                Question.AM_PM -> Pending.Meridiem(intent, outcome.hour ?: 0, outcome.minute ?: 0, outcome.candidates.singleOrNull()?.id ?: targetId, language, spoken)
                Question.WHICH_ALARM -> Pending.Choice(intent, outcome.candidates, language, spoken)
                Question.TIME -> Pending.Time(intent, outcome.candidates.singleOrNull()?.id ?: targetId, language, spoken)
            }.also { it.askedAt = now() }
        } else {
            pending = null
        }
        return ClockReply(outcome, language)
    }

    /** Reads [raw] as the answer to the pending question. Null means "this is not an answer": the caller then reads it as a new command. */
    private fun answer(p: Pending, raw: String, lang: String): ClockReply? {
        val isNewCommand = ClockCommandParser.parse(raw) != null
        when (p) {
            is Pending.Meridiem -> {
                val m = ClockCommandParser.readMeridiemAnswer(raw, p.hour)
                if (m != null && !isFullCommand(raw)) {
                    pending = null
                    return run(withTime(p.intent, ParsedTime(p.hour, p.minute, m, false)), p.targetId, lang, p.spoken)
                }
                if (isNewCommand) return null
                return declineOrAskAgain(p, raw, lang)
            }
            is Pending.Time -> {
                val t = ClockCommandParser.readTimes(raw).firstOrNull()
                if (t != null && !isFullCommand(raw)) {
                    pending = null
                    return run(withTime(p.intent, t), p.targetId, lang, p.spoken)
                }
                if (isNewCommand) return null
                return declineOrAskAgain(p, raw, lang)
            }
            is Pending.Choice -> {
                // "the college alarm" parses as a bare "alarm" sentence; as an answer it only names an alarm.
                val parsed = ClockCommandParser.parse(raw)?.intent
                val justNamesAnAlarm = parsed is ClockIntent.SetAlarm && parsed.time == null && parsed.repeatDays.isEmpty() && parsed.dateWord == null
                val picked = if (parsed == null) pick(p.candidates, raw, allowNumbers = true) else if (justNamesAnAlarm) pick(p.candidates, raw, allowNumbers = false) else null
                if (picked != null) {
                    pending = null
                    return run(p.intent, picked.id, lang, p.spoken)
                }
                if (isNewCommand) return null
                return declineOrAskAgain(p, raw, lang)
            }
        }
    }

    /** "6 pm" or "the second one" are answers; "set an alarm for 6 pm" is a fresh command even if a question is open. */
    private fun isFullCommand(raw: String): Boolean = ClockCommandParser.parse(raw) != null

    private fun declineOrAskAgain(p: Pending, raw: String, lang: String): ClockReply? {
        if (ClockCommandParser.readYesNo(raw) == false) {
            pending = null
            return ClockReply(ClockOutcome.Success(Done.CANCELLED), lang)
        }
        // Not an answer and not a clock command: leave the question open and let the sentence go elsewhere.
        return null
    }

    private fun pick(candidates: List<AlarmItem>, raw: String, allowNumbers: Boolean): AlarmItem? {
        if (allowNumbers) ClockCommandParser.readChoiceNumber(raw)?.let { n -> if (n in 1..candidates.size) return candidates[n - 1] }
        val said = ClockCommandParser.normalize(raw)
        val byLabel = candidates.filter { a ->
            val l = ClockCommandParser.normalize(a.label)
            l.length >= 2 && said.contains(l)
        }
        if (byLabel.size == 1) return byLabel[0]
        val t = ClockCommandParser.readTimes(raw).firstOrNull() ?: return null
        val byTime = candidates.filter { a ->
            val h = t.hour24
            if (h != null) a.hour == h && a.minute == t.minute else a.hour % 12 == t.hour % 12 && a.minute == t.minute
        }
        return byTime.singleOrNull()
    }

    private fun withTime(intent: ClockIntent, time: ParsedTime): ClockIntent = when (intent) {
        is ClockIntent.SetAlarm -> intent.copy(time = time)
        is ClockIntent.Change -> intent.copy(newTime = time)
        else -> intent
    }

    private companion object {
        const val EXPIRY_MS = 3 * 60_000L
    }
}
