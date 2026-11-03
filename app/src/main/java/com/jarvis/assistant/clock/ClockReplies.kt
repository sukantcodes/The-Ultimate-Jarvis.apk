package com.jarvis.assistant.clock

import android.content.Context
import android.content.res.Configuration
import com.jarvis.assistant.R
import com.jarvis.assistant.clock.voice.ClockOutcome
import com.jarvis.assistant.clock.voice.Done
import com.jarvis.assistant.clock.voice.Question
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/**
 * Words for what a clock command did, in the language the person spoke. Strings come from the app's own resources
 * (complete for English, Hindi, Bengali and Kannada; the other languages show their main labels and fall back to English for
 * longer sentences). When the AI model is doing the talking it writes its own reply in the person's language instead.
 */
object ClockReplies {

    /** A context whose strings are in [language], whatever the phone's own language is. */
    fun localized(context: Context, language: String): Pair<Context, Locale> {
        val code = language.ifBlank { "en" }
        val locale = Locale.forLanguageTag(code)
        val config = Configuration(context.resources.configuration).apply { setLocale(locale) }
        return context.createConfigurationContext(config) to locale
    }

    fun render(context: Context, outcome: ClockOutcome, language: String, settings: ClockSettings): String {
        val (ctx, locale) = localized(context, language)
        fun time(a: AlarmItem) = ClockFormat.time(ctx, a.hour, a.minute, settings, locale)
        fun whenText(millis: Long) = ClockFormat.whenText(ctx, millis, settings, ZoneId.systemDefault(), locale)

        return when (outcome) {
            is ClockOutcome.Failure -> ClockMessages.forError(ctx, outcome.error)
            is ClockOutcome.Ask -> when (outcome.question) {
                Question.TIME -> ctx.getString(R.string.clock_ask_time)
                Question.AM_PM -> {
                    val h = outcome.hour ?: 0
                    val m = outcome.minute ?: 0
                    ctx.getString(R.string.clock_ask_ampm, "%d:%02d".format(locale, if (h == 0) 12 else h, m))
                }
                Question.WHICH_ALARM -> ctx.getString(
                    R.string.clock_ask_which,
                    outcome.candidates.mapIndexed { i, a -> "${i + 1}) ${describe(ctx, a, settings, locale)}" }.joinToString("  ")
                )
            }
            is ClockOutcome.Success -> {
                val alarm = outcome.alarm
                when (outcome.kind) {
                    Done.CREATED -> {
                        val at = whenText(outcome.atMillis ?: 0L)
                        if (alarm != null && alarm.label.isNotBlank()) ctx.getString(R.string.clock_reply_set_named, at, alarm.label)
                        else ctx.getString(R.string.clock_reply_set, at)
                    }
                    Done.UPDATED -> ctx.getString(R.string.clock_reply_updated, whenText(outcome.atMillis ?: 0L))
                    Done.ENABLED -> if (outcome.unchanged) ctx.getString(R.string.clock_reply_enabled_already, whenText(outcome.atMillis ?: 0L))
                    else ctx.getString(R.string.clock_reply_enabled, whenText(outcome.atMillis ?: 0L))
                    Done.DISABLED -> if (outcome.unchanged || alarm == null) ctx.getString(R.string.clock_reply_disabled_already)
                    else ctx.getString(R.string.clock_reply_disabled, time(alarm))
                    Done.DELETED -> ctx.getString(R.string.clock_reply_deleted, alarm?.let { time(it) } ?: "")
                    Done.LISTED -> if (outcome.alarms.isEmpty()) ctx.getString(R.string.clock_reply_list_none)
                    else ctx.getString(R.string.clock_reply_list, outcome.alarms.size, outcome.alarms.joinToString("; ") { describe(ctx, it, settings, locale) })
                    Done.NEXT -> if (alarm == null || outcome.atMillis == null) ctx.getString(R.string.clock_reply_next_none)
                    else ctx.getString(R.string.clock_reply_next, whenText(outcome.atMillis), ClockFormat.timeUntil(ctx, outcome.atMillis))
                    Done.SNOOZED -> {
                        val at = ZonedDateTime.ofInstant(Instant.ofEpochMilli(outcome.atMillis ?: 0L), ZoneId.systemDefault())
                        ctx.getString(R.string.clock_reply_snoozed, ClockFormat.time(ctx, at.hour, at.minute, settings, locale))
                    }
                    Done.STOPPED -> ctx.getString(R.string.clock_reply_stopped)
                    Done.OPEN_SETTINGS -> ctx.getString(R.string.clock_reply_opened)
                    Done.CANCELLED -> ctx.getString(R.string.clock_reply_cancelled)
                }
            }
        }
    }

    /** "6:00 AM College" or "6:00 AM College (off)". */
    private fun describe(ctx: Context, a: AlarmItem, settings: ClockSettings, locale: Locale): String {
        val sb = StringBuilder(ClockFormat.time(ctx, a.hour, a.minute, settings, locale))
        if (a.label.isNotBlank()) sb.append(' ').append(a.label)
        if (!a.enabled) sb.append(ctx.getString(R.string.clock_reply_off_marker))
        return sb.toString()
    }
}
