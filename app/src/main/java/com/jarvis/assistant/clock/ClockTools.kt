package com.jarvis.assistant.clock

import android.content.Context
import com.jarvis.assistant.clock.voice.ClockOutcome
import com.jarvis.assistant.clock.voice.ClockToolMapper
import com.jarvis.assistant.clock.voice.Done
import com.jarvis.assistant.clock.voice.Question
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/**
 * The alarm tools offered to the AI (Gemini Live and Groq). They run the same [com.jarvis.assistant.clock.voice.ClockOps] as
 * the voice and offline paths, on the same alarm engine as the Clock screens. A result says `success: true` only when the
 * alarm was read back after the change.
 */
object ClockTools {
    val NAMES: Set<String> = ClockToolMapper.NAMES

    /** A call can wait a few seconds to see Stop/Snooze take effect, but never longer. */
    const val TOOL_TIMEOUT_MS = 15_000L

    private fun obj(vararg props: Pair<String, JSONObject>) = JSONObject().apply {
        put("type", "OBJECT")
        put("properties", JSONObject().apply { props.forEach { put(it.first, it.second) } })
    }

    private fun p(type: String, description: String, vararg enum: String) = JSONObject().apply {
        put("type", type)
        put("description", description)
        if (enum.isNotEmpty()) put("enum", JSONArray(enum.toList()))
    }

    private fun days(description: String) = JSONObject().apply {
        put("type", "ARRAY")
        put("description", description)
        put("items", JSONObject().put("type", "STRING"))
    }

    private fun decl(name: String, description: String, params: JSONObject, required: List<String> = emptyList()) = JSONObject().apply {
        put("name", name)
        put("description", description)
        put("parameters", params.apply { if (required.isNotEmpty()) put("required", JSONArray(required)) })
    }

    private val SELECTOR = listOf(
        "alarm_id" to p("INTEGER", "Exact id of the alarm, only if it came from an earlier clock_list_alarms result or a question you asked."),
        "target_hour" to p("INTEGER", "Hour of the alarm the user means, as said (1-12) or 0-23 if said in 24-hour form."),
        "target_minute" to p("INTEGER", "Minute of the alarm the user means."),
        "target_meridiem" to p("STRING", "am or pm of the alarm the user means. ONLY if the user actually said am/pm or a part of the day. Never guess it.", "am", "pm"),
        "target_part_of_day" to p("STRING", "For 'my morning alarm': morning, afternoon, evening or night.", "morning", "afternoon", "evening", "night"),
        "target_weekday" to p("STRING", "For 'my Monday alarm': the weekday name in English.", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"),
        "target_all" to p("BOOLEAN", "True if the user said all alarms. The assistant will then ask which one; nothing is changed for 'all'.")
    )

    fun declarations(): List<JSONObject> = listOf(
        decl(
            ClockToolMapper.SET,
            "Creates a real alarm in JARVIS's own Clock (Settings > Clock > Alarms). Use for 'set an alarm for 6 AM', 'wake me up tomorrow at 5:30', 'every Monday at 7', 'create an alarm called College'. Works in any language: read the user's words and fill the fields in English. NEVER guess am/pm: if the user did not say AM, PM or a part of the day (morning, evening, subah, সকাল, ಬೆಳಿಗ್ಗೆ ...) for an hour from 1 to 12, leave meridiem empty and the tool will return a question to ask the user. Do not open an external clock app.",
            obj(
                "hour" to p("INTEGER", "Hour: 1-12 as the user said it, or 0-23 if they used 24-hour time (for example 18 or 0)."),
                "minute" to p("INTEGER", "Minute, 0-59. 0 if none was said."),
                "meridiem" to p("STRING", "am or pm ONLY if the user said so or named a part of the day (morning=am, evening/night/afternoon=pm). Otherwise omit.", "am", "pm"),
                "label" to p("STRING", "The alarm's name if the user gave one, in the user's own script (for example College or कॉलेज)."),
                "repeat_days" to days("Days it repeats: monday..sunday, or daily, weekdays, weekends. Omit for a one-time alarm."),
                "date" to p("STRING", "For a one-time alarm: today, tomorrow or day_after_tomorrow. Omit for 'the next time this time comes'.", "today", "tomorrow", "day_after_tomorrow"),
                "weekday" to p("STRING", "For a one-time alarm on a named day ('Monday at 6'): the weekday in English.", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
            ),
            listOf("hour")
        ),
        decl(ClockToolMapper.LIST, "Lists all alarms with their times, names and on/off state ('show all alarms').", JSONObject().put("type", "OBJECT").put("properties", JSONObject())),
        decl(ClockToolMapper.NEXT, "Tells which alarm will ring next and when ('when is my next alarm').", JSONObject().put("type", "OBJECT").put("properties", JSONObject())),
        decl(
            ClockToolMapper.CHANGE,
            "Changes an existing alarm: its time, repeat days or name ('change the alarm to 7:30 AM'). Say which alarm with the target_* fields or alarm_id. If several alarms could match, the tool returns a question to ask the user; never pick one yourself.",
            obj(
                *(SELECTOR.toTypedArray()),
                "new_hour" to p("INTEGER", "New hour (1-12 as said, or 0-23)."),
                "new_minute" to p("INTEGER", "New minute."),
                "new_meridiem" to p("STRING", "am or pm ONLY if the user said so. Never guess.", "am", "pm"),
                "new_label" to p("STRING", "New name."),
                "new_repeat_days" to days("New repeat days (monday..sunday, daily, weekdays, weekends); empty list makes it a one-time alarm.")
            )
        ),
        decl(
            ClockToolMapper.ENABLE,
            "Switches an alarm on or off without deleting it ('disable the morning alarm', 'enable the 6 AM alarm'). Say which alarm with the target_* fields or alarm_id.",
            obj(*(SELECTOR.toTypedArray()), "enabled" to p("BOOLEAN", "true to switch on, false to switch off.")),
            listOf("enabled")
        ),
        decl(
            ClockToolMapper.DELETE,
            "Deletes an alarm ('delete the college alarm'). Say which alarm with target_* fields (the name is found automatically from the user's words) or alarm_id. The tool asks the user when it is not certain which alarm; never guess.",
            obj(*(SELECTOR.toTypedArray()))
        ),
        decl(
            ClockToolMapper.RING,
            "Snoozes or stops the alarm that is ringing right now ('snooze', 'stop the alarm').",
            obj("action" to p("STRING", "snooze or stop.", "snooze", "stop")),
            listOf("action")
        ),
        decl(ClockToolMapper.OPEN, "Opens JARVIS Settings > Clock ('open clock settings').", JSONObject().put("type", "OBJECT").put("properties", JSONObject()))
    )

    /** Runs a clock tool call. Blocking (may wait to confirm Stop/Snooze), so call it off the main thread. */
    fun execute(context: Context, name: String, args: JSONObject, transcript: String): JSONObject {
        val app = context.applicationContext
        val map = HashMap<String, Any?>()
        args.keys().forEach { key -> map[key] = toPlain(args.opt(key)) }
        val call = ClockToolMapper.map(name, map, transcript)
            ?: return JSONObject().put("success", false).put("message", "That alarm request was not understood. Ask the user what they want.")

        val outcome = try {
            ClockAssistant.ops(app).execute(call.intent, call.targetId, transcript.ifBlank { null })
        } catch (e: Exception) {
            return JSONObject().put("success", false).put("verified", false).put("message", "The alarm could not be changed: ${e.message}")
        }
        if (outcome is ClockOutcome.Success && outcome.kind == Done.OPEN_SETTINGS) ClockAssistant.openSettings(app)
        return toJson(app, outcome)
    }

    private fun toPlain(v: Any?): Any? = when (v) {
        null, JSONObject.NULL -> null
        is JSONArray -> (0 until v.length()).map { toPlain(v.opt(it)) }
        else -> v
    }

    private fun describe(app: Context, a: AlarmItem, settings: ClockSettings): JSONObject = JSONObject().apply {
        put("id", a.id)
        put("time", "%02d:%02d".format(a.hour, a.minute))
        put("time_display", ClockFormat.time(app, a.hour, a.minute, settings, java.util.Locale.ENGLISH))
        put("name", a.label)
        put("enabled", a.enabled)
        put("repeat", ClockFormat.repeatSummary(app, a, settings, java.util.Locale.ENGLISH))
        if (a.enabled && a.scheduledForMillis > 0L) put("next_ring", whenIso(a.scheduledForMillis))
    }

    private fun whenIso(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toOffsetDateTime().toString()

    private fun toJson(app: Context, outcome: ClockOutcome): JSONObject {
        val settings = ClockServices.engine(app).settings()
        val lang = "Reply in the same language the user spoke, in one or two short sentences."
        return when (outcome) {
            is ClockOutcome.Failure -> JSONObject()
                .put("success", false).put("verified", false)
                .put("error", outcome.error.name)
                .put("message", outcome.message)
                .put("system_directive", "Nothing was changed. Tell the user honestly why, in their language. Do NOT say the alarm was set. $lang")
            is ClockOutcome.Ask -> JSONObject().apply {
                put("success", false)
                put("needs_user_answer", true)
                put("question", outcome.question.name)
                when (outcome.question) {
                    Question.TIME -> put("message", "No time was given. Nothing was changed.")
                    Question.AM_PM -> put("message", "The user did not say AM or PM for ${outcome.hour}:${"%02d".format(outcome.minute ?: 0)}. Nothing was changed.")
                    Question.WHICH_ALARM -> {
                        put("message", "More than one alarm could be meant, or none was named. Nothing was changed.")
                        put("candidates", JSONArray().apply { outcome.candidates.forEach { put(describe(app, it, settings)) } })
                    }
                }
                put("system_directive", "Ask the user this one question now, in their language, and wait for the answer. Never guess AM/PM or which alarm. After they answer, call the tool again (use alarm_id from candidates when they pick one). $lang")
            }
            is ClockOutcome.Success -> JSONObject().apply {
                put("success", true)
                put("verified", true)
                put("result", outcome.kind.name)
                outcome.alarm?.let { put("alarm", describe(app, it, settings)) }
                if (outcome.alarms.isNotEmpty() || outcome.kind == Done.LISTED) put("alarms", JSONArray().apply { outcome.alarms.forEach { put(describe(app, it, settings)) } })
                outcome.atMillis?.let {
                    put("rings_at", whenIso(it))
                    put("rings_at_display", ClockFormat.whenText(app, it, settings, ZoneId.systemDefault(), java.util.Locale.ENGLISH))
                }
                if (outcome.unchanged) put("note", "It was already in that state.")
                put("message", messageFor(outcome))
                put("system_directive", "Confirm to the user what was done, using the exact times in this result. $lang")
            }
        }
    }

    private fun messageFor(o: ClockOutcome.Success): String = when (o.kind) {
        Done.CREATED -> "Alarm created and confirmed with Android."
        Done.UPDATED -> "Alarm changed and confirmed with Android."
        Done.ENABLED -> if (o.unchanged) "Alarm was already on." else "Alarm switched on and confirmed."
        Done.DISABLED -> if (o.unchanged) "Alarm was already off." else "Alarm switched off."
        Done.DELETED -> "Alarm deleted."
        Done.LISTED -> if (o.alarms.isEmpty()) "There are no alarms." else "Listed ${o.alarms.size} alarms."
        Done.NEXT -> if (o.alarm == null) "No alarm is set to ring." else "Next alarm found."
        Done.SNOOZED -> "Alarm snoozed."
        Done.STOPPED -> "Alarm stopped."
        Done.OPEN_SETTINGS -> "Clock settings opened."
        Done.CANCELLED -> "Cancelled."
    }
}
