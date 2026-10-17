package com.jarvis.assistant.studio

import org.json.JSONArray
import org.json.JSONObject

/** What starts an automation. */
enum class TriggerType(val key: String, val label: String) {
    VOICE("voice", "Voice command"),
    BATTERY_LOW("battery_low", "Battery is low"),
    BATTERY_FULL("battery_full", "Battery is full"),
    PLUGGED_IN("plugged_in", "Charger connected"),
    UNPLUGGED("unplugged", "Charger disconnected"),
    MESSAGE_RECEIVED("message_received", "Message received");

    companion object {
        fun fromKey(key: String?): TriggerType = values().firstOrNull { it.key == key } ?: VOICE
    }
}

/** One step of an automation. [type] is an [ActionSpec.key] from the [ActionCatalog]. */
data class AutomationAction(val type: String, val params: Map<String, String> = emptyMap())

data class AutomationTrigger(
    val type: TriggerType,
    /** Voice phrases, for example "call {contact}" or "gaming mode on". Only used by voice triggers. */
    val phrases: List<String> = emptyList(),
    /** Battery percentage for the battery-low trigger. */
    val threshold: Int = 20,
    /** For message triggers: "", "whatsapp", "instagram" or "instazen". Empty means any app. */
    val appFilter: String = "",
    /** For message triggers: only senders whose name contains this text. Empty means anyone. */
    val senderFilter: String = ""
)

data class AutomationRule(
    val id: String,
    val name: String,
    /** Built-in rules ship with the app. They can be edited and switched off, but never deleted. */
    val isDefault: Boolean,
    val enabled: Boolean,
    val trigger: AutomationTrigger,
    val actions: List<AutomationAction>
)

/** Reading and writing rules as JSON, which is how they are stored on the phone. */
object AutomationJson {
    fun toJson(rule: AutomationRule): JSONObject = JSONObject().apply {
        put("id", rule.id)
        put("name", rule.name)
        put("isDefault", rule.isDefault)
        put("enabled", rule.enabled)
        put("trigger", JSONObject().apply {
            put("type", rule.trigger.type.key)
            put("phrases", JSONArray(rule.trigger.phrases))
            put("threshold", rule.trigger.threshold)
            put("appFilter", rule.trigger.appFilter)
            put("senderFilter", rule.trigger.senderFilter)
        })
        put("actions", JSONArray().apply {
            for (action in rule.actions) {
                put(JSONObject().apply {
                    put("type", action.type)
                    put("params", JSONObject().apply {
                        for ((key, value) in action.params) put(key, value)
                    })
                })
            }
        })
    }

    fun fromJson(obj: JSONObject): AutomationRule? {
        return try {
            val triggerObj = obj.getJSONObject("trigger")
            val phrases = ArrayList<String>()
            triggerObj.optJSONArray("phrases")?.let { array ->
                for (i in 0 until array.length()) {
                    val phrase = array.optString(i, "").trim()
                    if (phrase.isNotEmpty()) phrases.add(phrase)
                }
            }
            val actions = ArrayList<AutomationAction>()
            obj.optJSONArray("actions")?.let { array ->
                for (i in 0 until array.length()) {
                    val actionObj = array.optJSONObject(i) ?: continue
                    val params = LinkedHashMap<String, String>()
                    actionObj.optJSONObject("params")?.let { paramObj ->
                        val keys = paramObj.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            params[key] = paramObj.optString(key, "")
                        }
                    }
                    actions.add(AutomationAction(actionObj.getString("type"), params))
                }
            }
            AutomationRule(
                id = obj.getString("id"),
                name = obj.optString("name", "Untitled"),
                isDefault = obj.optBoolean("isDefault", false),
                enabled = obj.optBoolean("enabled", true),
                trigger = AutomationTrigger(
                    type = TriggerType.fromKey(triggerObj.optString("type", "voice")),
                    phrases = phrases,
                    threshold = triggerObj.optInt("threshold", 20),
                    appFilter = triggerObj.optString("appFilter", ""),
                    senderFilter = triggerObj.optString("senderFilter", "")
                ),
                actions = actions
            )
        } catch (e: Exception) {
            null
        }
    }
}
