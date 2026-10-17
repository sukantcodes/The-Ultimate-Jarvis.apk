package com.jarvis.assistant.automation.studio

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParseException
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.IOException
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * JSON-backed local repository.
 *
 * Writes use a temporary file and rename to avoid leaving a partially-written
 * automation_rules.json after process death. This is synchronous by design; call
 * from Dispatchers.IO for large rule sets.
 */
class RuleRepository(context: Context) {
    companion object {
        private const val FILE_NAME = "automation_rules.json"
        private const val TEMP_NAME = "automation_rules.json.tmp"
        private const val BACKUP_NAME = "automation_rules.json.bak"
        private const val SCHEMA_VERSION = 1
    }

    private data class StoredRules(
        val schemaVersion: Int = SCHEMA_VERSION,
        val rules: List<AutomationRule> = emptyList()
    )

    private val appContext = context.applicationContext
    private val protectedEnabledPrefs = appContext.getSharedPreferences("automation_studio_protected_rules", Context.MODE_PRIVATE)
    private val lock = ReentrantReadWriteLock()
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()
    private val rulesFile get() = File(appContext.filesDir, FILE_NAME)
    private val tempFile get() = File(appContext.filesDir, TEMP_NAME)
    private val backupFile get() = File(appContext.filesDir, BACKUP_NAME)
    private val listType = object : TypeToken<List<AutomationRule>>() {}.type

    fun getAll(): List<AutomationRule> = lock.read {
        val stored = readStored()
        mergeProtectedDefaults(stored)
    }

    fun findById(id: String): AutomationRule? = getAll().firstOrNull { it.id == id }

    @Throws(IOException::class)
    fun upsert(rule: AutomationRule): List<AutomationRule> = lock.write {
        require(!rule.protected) { "Protected default rules cannot be edited." }
        val current = readStored().toMutableList()
        val index = current.indexOfFirst { it.id == rule.id }
        val now = System.currentTimeMillis()
        val normalized = rule.copy(updatedAtEpochMs = now)
        if (index >= 0) current[index] = normalized else current.add(normalized)
        writeStored(current)
        mergeProtectedDefaults(current)
    }

    @Throws(IOException::class)
    fun delete(id: String): Boolean = lock.write {
        val current = readStored().toMutableList()
        val found = current.firstOrNull { it.id == id } ?: return@write false
        require(!found.protected) { "Protected default rules cannot be deleted." }
        current.removeAll { it.id == id }
        writeStored(current)
        true
    }

    @Throws(IOException::class)
    fun setEnabled(id: String, enabled: Boolean): Boolean = lock.write {
        if (ProtectedDefaultRules.all.any { it.id == id }) {
            protectedEnabledPrefs.edit().putBoolean(id, enabled).apply()
            return@write true
        }
        val current = readStored().toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return@write false
        current[index] = current[index].copy(enabled = enabled, updatedAtEpochMs = System.currentTimeMillis())
        writeStored(current)
        true
    }

    /** Export only custom rules; protected defaults are shipped with the app. */
    fun exportCustomRules(): String = gson.toJson(
        StoredRules(rules = getAll().filterNot { it.protected })
    )

    /** Imports only custom rules. Existing custom rules with matching IDs are replaced. */
    @Throws(IOException::class, IllegalArgumentException::class)
    fun importCustomRules(json: String): Int = lock.write {
        require(json.length <= 2_000_000) { "Import file is too large." }
        val imported = try {
            val parsed = gson.fromJson(json, StoredRules::class.java)
                ?: throw IllegalArgumentException("Invalid rules JSON.")
            require(parsed.schemaVersion in 1..SCHEMA_VERSION) { "Unsupported JSON schema version." }
            parsed.rules
        } catch (e: JsonParseException) {
            throw IllegalArgumentException("Invalid rules JSON.", e)
        }
        require(imported.size <= 500) { "Maximum 500 custom rules can be imported." }
        require(imported.none { it.protected }) { "Imported rules cannot replace protected defaults." }
        imported.forEach {
            require(it.name.isNotBlank() && it.triggerPattern.isNotBlank() && it.actions.isNotEmpty()) {
                "Imported rule has missing required fields."
            }
        }
        val merged = readStored().associateBy { it.id }.toMutableMap()
        imported.forEach { merged[it.id] = it.copy(protected = false) }
        writeStored(merged.values.toList())
        imported.size
    }

    private fun readStored(): List<AutomationRule> {
        if (!rulesFile.exists()) {
            if (backupFile.exists()) {
                backupFile.copyTo(rulesFile, overwrite = true)
            } else {
                return emptyList()
            }
        }
        return try {
            val parsed = gson.fromJson(rulesFile.readText(Charsets.UTF_8), StoredRules::class.java)
            if (parsed == null || parsed.schemaVersion !in 1..SCHEMA_VERSION) emptyList()
            else parsed.rules.filterNot { it.protected }
        } catch (_: Exception) {
            if (backupFile.exists()) {
                try {
                    val backup = gson.fromJson(backupFile.readText(Charsets.UTF_8), StoredRules::class.java)
                    backup?.rules.orEmpty().filterNot { it.protected }
                } catch (_: Exception) {
                    emptyList()
                }
            } else emptyList()
        }
    }

    private fun writeStored(rules: List<AutomationRule>) {
        val custom = rules.filterNot { it.protected }.distinctBy { it.id }
        tempFile.writeText(gson.toJson(StoredRules(rules = custom)), Charsets.UTF_8)
        if (rulesFile.exists()) rulesFile.copyTo(backupFile, overwrite = true)
        if (rulesFile.exists() && !rulesFile.delete()) {
            tempFile.delete()
            throw IOException("Could not replace automation rules file.")
        }
        if (!tempFile.renameTo(rulesFile)) {
            if (backupFile.exists()) backupFile.copyTo(rulesFile, overwrite = true)
            tempFile.delete()
            throw IOException("Could not atomically save automation rules.")
        }
    }

    private fun mergeProtectedDefaults(custom: List<AutomationRule>): List<AutomationRule> =
        (ProtectedDefaultRules.all + custom.filterNot { it.protected })
            .distinctBy { it.id }
            .map { rule ->
                if (rule.protected) rule.copy(enabled = protectedEnabledPrefs.getBoolean(rule.id, rule.enabled)) else rule
            }
            .sortedWith(compareBy<AutomationRule> { it.priority }.thenBy { it.name.lowercase() })

    private object ProtectedDefaultRules {
        val all = listOf(
            AutomationRule(
                id = "system.call.contact",
                name = "Call contact",
                triggerPattern = "call {contact}",
                actions = listOf(AutomationAction(RuleActionType.PLACE_CALL, mapOf("contact" to "{contact}"))),
                protected = true,
                priority = 10
            ),
            AutomationRule(
                id = "system.whatsapp.message",
                name = "WhatsApp message",
                triggerPattern = "message {contact} {message}",
                actions = listOf(
                    AutomationAction(
                        RuleActionType.WHATSAPP_MESSAGE,
                        mapOf("contact" to "{contact}", "message" to "{message}")
                    )
                ),
                protected = true,
                priority = 20
            ),
            AutomationRule(
                id = "system.volume.level",
                name = "Set volume",
                triggerPattern = "set volume to {level}",
                actions = listOf(AutomationAction(RuleActionType.SET_VOLUME, mapOf("level" to "{level}"))),
                protected = true,
                priority = 30
            )
        )
    }
}
