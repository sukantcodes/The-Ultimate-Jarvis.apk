package com.jarvis.assistant.tools.appcontrol

import java.math.BigDecimal
import java.util.Locale
import kotlin.math.abs

enum class SettingType(val label: String) {
    BOOLEAN("on/off"),
    INT("whole number"),
    FLOAT("number"),
    CHOICE("one of the listed values"),
    TEXT("text")
}

/** What a requested value turned into: the canonical text to store, or the reason it can not be stored. */
sealed class Normalised {
    data class Ok(val value: String) : Normalised()
    data class Bad(val reason: String) : Normalised()
}

/**
 * One setting of the app, described once so Jarvis can list it, read it and change it the same way the Settings
 * screen does. [read] and [write] go through the app's own preference class, so every rule that class applies
 * (clamping, migrations, allowed values) still applies.
 */
class SettingDef(
    val id: String,
    val section: String,
    val label: String,
    val type: SettingType,
    val description: String = "",
    val choices: List<String> = emptyList(),
    val min: Double? = null,
    val max: Double? = null,
    private val validator: ((String) -> String?)? = null,
    val read: () -> String,
    val write: (String) -> Unit
) {
    fun normalise(raw: String): Normalised {
        val text = raw.trim()
        val value: String = when (type) {
            SettingType.BOOLEAN -> when (text.lowercase(Locale.ROOT)) {
                "true", "on", "yes", "1", "enable", "enabled" -> "true"
                "false", "off", "no", "0", "disable", "disabled" -> "false"
                else -> return Normalised.Bad("\"$raw\" is not on or off. Use true or false.")
            }
            SettingType.INT -> {
                val n = text.toIntOrNull() ?: return Normalised.Bad("\"$raw\" is not a whole number.")
                rangeProblem(n.toDouble())?.let { return Normalised.Bad(it) }
                n.toString()
            }
            SettingType.FLOAT -> {
                val d = text.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return Normalised.Bad("\"$raw\" is not a number.")
                rangeProblem(d)?.let { return Normalised.Bad(it) }
                BigDecimal.valueOf(d).stripTrailingZeros().toPlainString()
            }
            SettingType.CHOICE -> choices.firstOrNull { it.equals(text, ignoreCase = true) }
                ?: return Normalised.Bad("\"$raw\" is not allowed. Choose one of: ${choices.joinToString(", ")}.")
            SettingType.TEXT -> {
                if (text.length > MAX_TEXT) return Normalised.Bad("That text is longer than $MAX_TEXT characters.")
                text
            }
        }
        validator?.invoke(value)?.let { return Normalised.Bad(it) }
        return Normalised.Ok(value)
    }

    /** True when two readings of this setting mean the same value (numbers are compared as numbers). */
    fun sameValue(a: String, b: String): Boolean {
        if (type == SettingType.FLOAT) {
            val x = a.toDoubleOrNull()
            val y = b.toDoubleOrNull()
            if (x != null && y != null) return abs(x - y) < 0.0005
        }
        return a.trim().equals(b.trim(), ignoreCase = true)
    }

    /** What may be entered, in one short phrase, for the listing shown to the model. */
    fun allowed(): String = when (type) {
        SettingType.BOOLEAN -> "true or false"
        SettingType.CHOICE -> choices.joinToString(" | ")
        SettingType.INT, SettingType.FLOAT -> when {
            min != null && max != null -> "${type.label} from ${fmt(min)} to ${fmt(max)}"
            else -> type.label
        }
        SettingType.TEXT -> "text"
    }

    private fun rangeProblem(v: Double): String? {
        if (min != null && v < min) return "The lowest allowed value is ${fmt(min)}."
        if (max != null && v > max) return "The highest allowed value is ${fmt(max)}."
        return null
    }

    private fun fmt(d: Double): String = BigDecimal.valueOf(d).stripTrailingZeros().toPlainString()

    companion object { const val MAX_TEXT = 500 }
}

/** The settings Jarvis may list, read and change, found by id. */
class SettingsRegistry(defs: List<SettingDef>, private val beforeWrite: (SettingDef) -> Unit = {}) {
    private val byId: Map<String, SettingDef> = defs.associateBy { it.id }

    init { require(byId.size == defs.size) { "Two settings share an id." } }

    val all: List<SettingDef> = defs

    sealed class Outcome {
        data class Changed(val def: SettingDef, val before: String, val after: String, val note: String?) : Outcome()
        data class Unchanged(val def: SettingDef, val value: String) : Outcome()
        data class Failed(val id: String, val reason: String) : Outcome()
    }

    fun find(id: String): SettingDef? = byId[canonicalId(id)]

    fun sections(): List<String> = all.map { it.section }.distinct()

    fun inSection(section: String): List<SettingDef> = all.filter { it.section.equals(section.trim(), ignoreCase = true) }

    fun search(query: String): List<SettingDef> {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return all
        return all.filter { it.id.contains(q) || it.label.lowercase(Locale.ROOT).contains(q) || it.description.lowercase(Locale.ROOT).contains(q) || it.section.lowercase(Locale.ROOT).contains(q) }
    }

    /** Changes one setting and reads it back, so the answer is what the app really holds, never what was asked for. */
    fun change(id: String, raw: String): Outcome {
        val def = find(id) ?: return Outcome.Failed(id, "No setting called \"$id\". Use app_settings_read to see the ids.")
        val wanted = when (val n = def.normalise(raw)) {
            is Normalised.Ok -> n.value
            is Normalised.Bad -> return Outcome.Failed(def.id, n.reason)
        }
        val before = try { def.read() } catch (e: Exception) { return Outcome.Failed(def.id, "The current value could not be read: ${e.message.orEmpty()}") }
        if (def.sameValue(before, wanted)) return Outcome.Unchanged(def, before)
        try {
            beforeWrite(def)
            def.write(wanted)
        } catch (e: Exception) {
            return Outcome.Failed(def.id, "The app refused the change: ${e.message.orEmpty()}")
        }
        val after = try { def.read() } catch (e: Exception) { return Outcome.Failed(def.id, "The change was sent, but the new value could not be read back.") }
        return when {
            def.sameValue(after, wanted) -> Outcome.Changed(def, before, after, null)
            !def.sameValue(after, before) -> Outcome.Changed(def, before, after, "The app adjusted the value to $after.")
            else -> Outcome.Failed(def.id, "The app did not accept the change. It still holds $after.")
        }
    }

    companion object {
        fun canonicalId(id: String): String = id.trim().lowercase(Locale.ROOT).replace('-', '_').replace(' ', '_')
    }
}
