package com.jarvis.assistant.tools.appcontrol

import android.content.Context
import java.util.Locale

/**
 * Last-resort access to the app's own preference files, for a setting that has no entry in [AppSettingsCatalog].
 * It can only change a key that already exists and keeps its type, never touches secrets, and refuses anything that
 * would let the model loosen its own permission rules.
 */
object RawPrefs {
    /** Files that may never be written through here, because they hold the rules Jarvis itself must obey. */
    private val WRITE_BLOCKED_FILES = setOf("tool_permissions", "jarvis_providers")
    private val WRITE_BLOCKED_PARTS = listOf("auto_approve", "mode.", "internet_enabled", "permission")

    fun isKnownFile(name: String): Boolean = name in AppChangeWatcher.WATCHED_FILES

    /** One line per key: "key = value (type)". Secrets, voice prints and very long values show only their size. */
    fun dump(context: Context, file: String, limit: Int = 200): List<String> {
        if (!isKnownFile(file)) return emptyList()
        val sp = context.applicationContext.getSharedPreferences(file, Context.MODE_PRIVATE)
        return sp.all.entries.sortedBy { it.key }.take(limit).map { (k, v) ->
            val type = when (v) {
                is Boolean -> "true/false"; is Int -> "whole number"; is Long -> "whole number"; is Float -> "number"
                is Set<*> -> "list"; else -> "text"
            }
            val shown = when {
                ChangeJournal.isSensitive(k) -> "(hidden)"
                v is Set<*> -> v.joinToString(", ").take(120)
                else -> v.toString().let { if (it.length > 120) it.take(120) + "… (${it.length} characters)" else it }
            }
            "$k = $shown  ($type)"
        }
    }

    /** Returns null when the value was stored, otherwise the reason it was not. */
    fun write(context: Context, file: String, key: String, value: String): String? {
        if (!isKnownFile(file)) return "\"$file\" is not a settings file of this app. Known files: ${AppChangeWatcher.WATCHED_FILES.joinToString(", ")}."
        if (file in WRITE_BLOCKED_FILES) return "\"$file\" holds permission and provider rules, so it can only be changed from its own settings screen or the API tools."
        val lower = key.lowercase(Locale.ROOT)
        if (ChangeJournal.isSensitive(key)) return "\"$key\" looks like a secret or personal data, so it can not be read or changed here."
        if (WRITE_BLOCKED_PARTS.any { lower.contains(it) }) return "\"$key\" is part of the permission rules and can not be changed here."
        val sp = context.applicationContext.getSharedPreferences(file, Context.MODE_PRIVATE)
        val current = sp.all[key] ?: return "\"$key\" does not exist in $file. Only existing keys can be changed."
        val edit = sp.edit()
        when (current) {
            is Boolean -> when (value.trim().lowercase(Locale.ROOT)) {
                "true", "on", "yes", "1" -> edit.putBoolean(key, true)
                "false", "off", "no", "0" -> edit.putBoolean(key, false)
                else -> return "\"$key\" is on/off. Use true or false."
            }
            is Int -> edit.putInt(key, value.trim().toIntOrNull() ?: return "\"$key\" is a whole number.")
            is Long -> edit.putLong(key, value.trim().toLongOrNull() ?: return "\"$key\" is a whole number.")
            is Float -> edit.putFloat(key, value.trim().toFloatOrNull()?.takeIf { it.isFinite() } ?: return "\"$key\" is a number.")
            is String -> edit.putString(key, value)
            else -> return "\"$key\" is a list, which can not be edited here."
        }
        AppChangeWatcher.markJarvisWrite("$file/$key")
        return if (edit.commit()) null else "Android could not save the change."
    }
}
