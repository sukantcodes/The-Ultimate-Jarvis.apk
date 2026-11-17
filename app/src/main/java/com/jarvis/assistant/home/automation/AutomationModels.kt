package com.jarvis.assistant.home.automation

import com.jarvis.assistant.home.DeviceAction
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One thing a routine does. Exactly one of [deviceId], [groupId], [roomId] picks the target. */
data class AutomationStep(
    val deviceId: String? = null,
    val groupId: String? = null,
    val roomId: String? = null,
    val action: DeviceAction,
    val delaySeconds: Int = 0
) {
    fun toJson() = JSONObject().put("device", deviceId ?: JSONObject.NULL).put("group", groupId ?: JSONObject.NULL)
        .put("room", roomId ?: JSONObject.NULL).put("action", action.toJson()).put("delay", delaySeconds)

    companion object {
        fun fromJson(o: JSONObject) = AutomationStep(
            if (o.isNull("device")) null else o.optString("device"),
            if (o.isNull("group")) null else o.optString("group"),
            if (o.isNull("room")) null else o.optString("room"),
            DeviceAction.fromJson(o.getJSONObject("action")), o.optInt("delay")
        )
    }
}

/** Runs only when the device's last reported state matches. Unknown state never satisfies a condition. */
data class Condition(val deviceId: String, val poweredOn: Boolean? = null, val online: Boolean? = null) {
    fun toJson() = JSONObject().put("device", deviceId).put("on", poweredOn ?: JSONObject.NULL).put("online", online ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject) = Condition(o.getString("device"), if (o.isNull("on")) null else o.optBoolean("on"), if (o.isNull("online")) null else o.optBoolean("online"))
    }
}

sealed class Trigger {
    object Manual : Trigger()

    /** [days] uses java.time DayOfWeek numbers 1 (Mon) to 7 (Sun); empty means every day. [repeat] false fires once, then pauses. */
    data class Time(val hour: Int, val minute: Int, val days: Set<Int> = emptySet(), val repeat: Boolean = true) : Trigger()

    /** Fires when a device reports it turned on/off. Only devices that report state can be used. */
    data class DeviceState(val deviceId: String, val becomesOn: Boolean) : Trigger()

    fun toJson(): JSONObject = when (this) {
        is Manual -> JSONObject().put("type", "manual")
        is Time -> JSONObject().put("type", "time").put("hour", hour).put("minute", minute).put("days", JSONArray(days.sorted())).put("repeat", repeat)
        is DeviceState -> JSONObject().put("type", "state").put("device", deviceId).put("on", becomesOn)
    }

    companion object {
        fun fromJson(o: JSONObject): Trigger = when (o.optString("type")) {
            "time" -> Time(o.optInt("hour").coerceIn(0, 23), o.optInt("minute").coerceIn(0, 59),
                o.optJSONArray("days")?.let { a -> (0 until a.length()).map { a.getInt(it) }.filter { it in 1..7 }.toSet() } ?: emptySet(), o.optBoolean("repeat", true))
            "state" -> DeviceState(o.getString("device"), o.optBoolean("on"))
            else -> Manual
        }
    }
}

/** A routine. A scene is a routine with a manual trigger shown on the Smart Scenes page. */
data class Automation(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val isScene: Boolean,
    val enabled: Boolean = true,
    val trigger: Trigger = Trigger.Manual,
    val conditions: List<Condition> = emptyList(),
    val steps: List<AutomationStep> = emptyList(),
    val templateKey: String? = null
) {
    /** A template or new routine with no steps cannot run: the user must choose devices and actions first. */
    val isConfigured: Boolean get() = steps.isNotEmpty()

    fun toJson() = JSONObject().put("id", id).put("name", name).put("scene", isScene).put("enabled", enabled)
        .put("trigger", trigger.toJson()).put("conditions", JSONArray(conditions.map { it.toJson() }))
        .put("steps", JSONArray(steps.map { it.toJson() })).put("template", templateKey ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject): Automation {
            val conds = o.optJSONArray("conditions")?.let { a -> (0 until a.length()).mapNotNull { runCatching { Condition.fromJson(a.getJSONObject(it)) }.getOrNull() } } ?: emptyList()
            val steps = o.optJSONArray("steps")?.let { a -> (0 until a.length()).mapNotNull { runCatching { AutomationStep.fromJson(a.getJSONObject(it)) }.getOrNull() } } ?: emptyList()
            return Automation(
                o.getString("id"), o.getString("name"), o.optBoolean("scene"), o.optBoolean("enabled", true),
                Trigger.fromJson(o.optJSONObject("trigger") ?: JSONObject()), conds, steps,
                if (o.isNull("template")) null else o.optString("template")
            )
        }

        /** Editable starting points. They have no steps on purpose; nothing runs until the user picks real devices. */
        val TEMPLATES: List<Pair<String, String>> = listOf(
            "good_morning" to "Good Morning", "good_night" to "Good Night", "movie_mode" to "Movie Mode",
            "away_mode" to "Away Mode", "work_mode" to "Work Mode", "all_off" to "All Off"
        )
    }
}

data class StepOutcome(val description: String, val success: Boolean, val message: String)

data class AutomationRun(val automationId: String, val name: String, val timeMillis: Long, val success: Boolean, val summary: String, val steps: List<StepOutcome>) {
    fun toJson() = JSONObject().put("id", automationId).put("name", name).put("t", timeMillis).put("ok", success).put("sum", summary)
        .put("steps", JSONArray(steps.map { JSONObject().put("d", it.description).put("ok", it.success).put("m", it.message) }))

    companion object {
        fun fromJson(o: JSONObject): AutomationRun {
            val steps = o.optJSONArray("steps")?.let { a -> (0 until a.length()).map { val s = a.getJSONObject(it); StepOutcome(s.optString("d"), s.optBoolean("ok"), s.optString("m")) } } ?: emptyList()
            return AutomationRun(o.optString("id"), o.optString("name"), o.optLong("t"), o.optBoolean("ok"), o.optString("sum"), steps)
        }
    }
}
