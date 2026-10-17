package com.jarvis.assistant.studio

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Keeps all automations in one private JSON file on the phone (automations.json). There is no limit on how many
 * custom automations can be saved. Built-in (default) automations are added on first run and when new ones ship,
 * and they can never be deleted.
 */
class AutomationStore private constructor(context: Context) {
    private val file = File(context.applicationContext.filesDir, "automations.json")
    private val lock = Any()
    private var cache: MutableList<AutomationRule>? = null

    /** Every automation, defaults and custom. */
    fun all(): List<AutomationRule> = synchronized(lock) { ensureLoaded().toList() }

    fun get(id: String): AutomationRule? = synchronized(lock) { ensureLoaded().firstOrNull { it.id == id } }

    /** Adds a new rule or replaces the rule with the same id. Default rules can be edited this way too. */
    fun upsert(rule: AutomationRule) = synchronized(lock) {
        val rules = ensureLoaded()
        val index = rules.indexOfFirst { it.id == rule.id }
        if (index >= 0) rules[index] = rule else rules.add(rule)
        save(rules)
    }

    /** Deletes a custom rule. Returns false (and deletes nothing) for default rules or unknown ids. */
    fun delete(id: String): Boolean = synchronized(lock) {
        val rules = ensureLoaded()
        val rule = rules.firstOrNull { it.id == id } ?: return@synchronized false
        if (rule.isDefault) return@synchronized false
        rules.remove(rule)
        save(rules)
        true
    }

    fun setEnabled(id: String, enabled: Boolean) = synchronized(lock) {
        val rules = ensureLoaded()
        val index = rules.indexOfFirst { it.id == id }
        if (index >= 0) {
            rules[index] = rules[index].copy(enabled = enabled)
            save(rules)
        }
    }

    /** Puts an edited default rule back to how it shipped. */
    fun resetDefault(id: String): Boolean = synchronized(lock) {
        val factory = DefaultAutomations.rules.firstOrNull { it.id == id } ?: return@synchronized false
        upsert(factory)
        true
    }

    fun newId(): String = "custom_" + UUID.randomUUID().toString().take(12)

    private fun ensureLoaded(): MutableList<AutomationRule> {
        cache?.let { return it }
        val rules = ArrayList<AutomationRule>()
        var storedVersion = 0
        var existed = file.exists()
        if (existed) {
            try {
                val root = JSONObject(file.readText())
                storedVersion = root.optInt("defaultsVersion", 0)
                val array = root.optJSONArray("rules") ?: JSONArray()
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    AutomationJson.fromJson(obj)?.let { rules.add(it) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "automations.json could not be read, starting fresh: ${e.message}")
                try {
                    file.renameTo(File(file.parentFile, "automations.json.bad"))
                } catch (_: Exception) {
                }
                rules.clear()
                storedVersion = 0
                existed = false
            }
        }
        var changed = !existed
        if (storedVersion < DefaultAutomations.VERSION) {
            val known = rules.map { it.id }.toSet()
            for (default in DefaultAutomations.rules) {
                if (default.id !in known) rules.add(default)
            }
            changed = true
        }
        cache = rules
        if (changed) save(rules)
        return rules
    }

    private fun save(rules: List<AutomationRule>) {
        try {
            val root = JSONObject()
            root.put("defaultsVersion", DefaultAutomations.VERSION)
            root.put("rules", JSONArray().apply { for (rule in rules) put(AutomationJson.toJson(rule)) })
            val text = root.toString()
            val temp = File(file.parentFile, "automations.json.tmp")
            temp.writeText(text)
            if (!temp.renameTo(file)) {
                file.writeText(text)
                temp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not save automations: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "AutomationStore"

        @Volatile
        private var instance: AutomationStore? = null

        fun get(context: Context): AutomationStore =
            instance ?: synchronized(this) {
                instance ?: AutomationStore(context.applicationContext).also { instance = it }
            }
    }
}
