package com.jarvis.assistant.tools.appcontrol

import android.content.Context
import com.google.gson.JsonObject
import com.jarvis.assistant.data.preferences.AppPreferences
import com.jarvis.assistant.data.repository.ChatRepository
import com.jarvis.assistant.provider.ActivationResult
import com.jarvis.assistant.provider.AuthMethod
import com.jarvis.assistant.provider.ProviderConfigManager
import com.jarvis.assistant.provider.ProviderProfile
import com.jarvis.assistant.provider.ProviderProtocol
import com.jarvis.assistant.tools.AuditLog
import com.jarvis.assistant.tools.Risk
import com.jarvis.assistant.tools.SecretRedactor
import com.jarvis.assistant.tools.Tool
import com.jarvis.assistant.tools.ToolContext
import com.jarvis.assistant.tools.ToolParam
import com.jarvis.assistant.tools.ToolResult
import com.jarvis.assistant.tools.ToolSpec
import com.jarvis.assistant.tools.builtin.bool
import com.jarvis.assistant.tools.builtin.int
import com.jarvis.assistant.tools.builtin.str
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun JsonObject.strings(name: String): List<String> {
    val e = get(name) ?: return emptyList()
    return when {
        e.isJsonArray -> e.asJsonArray.mapNotNull { x -> x.takeIf { it.isJsonPrimitive }?.asString }
        e.isJsonPrimitive -> listOf(e.asString)
        else -> emptyList()
    }
}

private fun stamp(t: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(t))

private fun apiSummary(d: AppControlTools.Deps): String {
    val m = d.providers()
    val active = m.activeProfile()
    val all = m.profiles()
    if (active == null) return "  No custom provider is active; the built-in Gemini engine (model ${d.prefs().aiModel}) is used. ${all.size} saved provider(s)."
    return "  Active: ${active.displayName}, model ${active.chatModel?.id ?: "(none)"}, key ${if (m.hasKey(active.id)) "saved" else "missing"}. ${all.size} saved provider(s). Use api_settings_read for details."
}

private fun chatSummary(d: AppControlTools.Deps): String {
    val repo = d.chat()
    val turns = repo.turns.value
    val pending = turns.size - repo.savedTurns().size
    val last = turns.lastOrNull()
    return "  ${turns.size} turn(s), $pending still being answered" +
        (last?.let { ", last at ${stamp(it.timestamp)}" } ?: "") +
        ". Tokens used in this chat: ${d.prefs().cumulativeChatTokens}" + (if (d.prefs().chatTokensApproximate) " (approximate)" else "") + "."
}

private fun describeProfile(m: ProviderConfigManager, p: ProviderProfile, active: Boolean): String = buildString {
    append(if (active) "* " else "- ").append(p.displayName).append("  (id ").append(p.id).append(")")
    if (active) append("  [ACTIVE]")
    append("\n    address: ").append(p.baseUrl)
    append("\n    protocol: ").append(p.protocol.label).append("; authentication: ").append(p.authMethod.label)
    if (p.authName.isNotBlank()) append(" (").append(p.authName).append(")")
    append("\n    models: ").append(p.models.size).append(" known; chat model: ").append(p.chatModel?.id ?: "(none)")
    append("; main model: ").append(p.selectedModelId.ifBlank { "(none)" })
    if (p.sttModelId.isNotBlank()) append("; speech-to-text model: ").append(p.sttModelId)
    append("\n    key: ").append(if (m.hasKey(p.id)) "saved (" + m.keyHint(p.id) + ")" else "none saved")
    val extra = ProviderProfile.SETTING_EXTRA_HEADERS
    val shown = p.settings.filterKeys { it != extra }.entries.joinToString(", ") { "${it.key}=${it.value}" }
    if (shown.isNotBlank()) append("\n    options: ").append(shown)
    if (!p.settings[extra].isNullOrBlank()) append("\n    extra headers: set (hidden)")
    if (p.discoveryPath.isNotBlank()) append("\n    model list path: ").append(p.discoveryPath)
    if (p.realtimeUrl.isNotBlank()) append("\n    realtime address: ").append(p.realtimeUrl)
}

/**
 * Lets Jarvis see and run its own app: read and change every ordinary setting, read and change the API
 * configuration, open any screen directly, look at the ChatBox, and say exactly what changed and when. All of it
 * happens inside this app's own process, so it needs no Android permission. Plain setting changes and screen jumps
 * do not ask first (the user's request is the permission); saving an API key or editing a raw preference always asks.
 */
object AppControlTools {
    const val GROUP = "app_control"

    class Deps(
        val app: Context,
        val journal: ChangeJournal,
        val audit: AuditLog,
        val redactor: SecretRedactor,
        val prefs: () -> AppPreferences,
        val chat: () -> ChatRepository,
        val providers: () -> ProviderConfigManager
    ) {
        val registry: SettingsRegistry by lazy {
            SettingsRegistry(AppSettingsCatalog.build(prefs())) { def -> AppChangeWatcher.markJarvisWrite(def.id) }
        }
    }

    fun all(d: Deps): List<Tool> = listOf(
        Overview(d), SettingsRead(d), SettingsChange(d), Navigate(d),
        ApiRead(d), ApiChange(d), ApiSwitch(d), ApiTest(d),
        ChatboxStatus(d), Changes(d), RawRead(d), RawWrite(d)
    )

    // ------------------------------------------------------------------------------------------------------
    // Knowing the app
    // ------------------------------------------------------------------------------------------------------

    class Overview(private val d: Deps) : Tool {
        override val spec = ToolSpec(
            "app_overview",
            "Everything Jarvis needs to know about its own app in one answer: every screen it can open, every settings section, the active API provider, the ChatBox state and the latest changes. Call this first when unsure where something is.",
            emptyList(), Risk.READ, GROUP
        )

        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult = ToolResult.Ok(buildString {
            appendLine("SCREENS (open with app_navigate):")
            AppNavigator.destinations.groupBy { it.section }.forEach { (section, list) ->
                appendLine("  $section: " + list.joinToString("; ") { "${it.id} (${it.label})" })
            }
            appendLine()
            appendLine("SETTINGS (read with app_settings_read, change with app_settings_change):")
            d.registry.sections().forEach { s -> appendLine("  $s: " + d.registry.inSection(s).joinToString(", ") { it.id }) }
            appendLine()
            appendLine("API:")
            appendLine(apiSummary(d))
            appendLine()
            appendLine("CHATBOX:")
            appendLine(chatSummary(d))
            appendLine()
            val recent = d.journal.recent(5)
            appendLine("LATEST CHANGES (" + d.journal.count() + " on record, use app_changes for more):")
            if (recent.isEmpty()) appendLine("  none yet") else recent.forEach { appendLine("  " + d.journal.describe(it)) }
            appendLine()
            append("LIMITS: Jarvis controls its own app here without any permission. Other apps and Android's own settings can only be reached through the Accessibility service or an Android settings screen, never from these tools.")
        })
    }

    class ChatboxStatus(private val d: Deps) : Tool {
        override val spec = ToolSpec(
            "chatbox_status",
            "Analyse the ChatBox as a whole: how many turns, what is still being answered, attachments, token use, which provider and model answers, and the ChatBox's own saved options. To read the messages themselves use chatbox_read.",
            emptyList(), Risk.READ, GROUP
        )

        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val repo = d.chat()
            val turns = repo.turns.value
            val pending = turns.size - repo.savedTurns().size
            val withFiles = turns.count { !it.attachmentName.isNullOrBlank() }
            val chars = turns.sumOf { it.userTranscript.length + it.jarvisResponse.length }
            val m = d.providers()
            val active = m.activeProfile()
            return ToolResult.Ok(buildString {
                appendLine("Turns: ${turns.size} (${turns.size - pending} finished, $pending being answered); $withFiles with an attachment; about $chars characters in all.")
                turns.firstOrNull()?.let { appendLine("First turn: ${stamp(it.timestamp)}") }
                turns.lastOrNull()?.let { appendLine("Last turn: ${stamp(it.timestamp)}") }
                appendLine("Tokens used in this chat: ${d.prefs().cumulativeChatTokens}" + if (d.prefs().chatTokensApproximate) " (approximate)" else "")
                appendLine("Answered by: " + (active?.let { "${it.displayName}, model ${it.chatModel?.id ?: "(none)"}" } ?: "built-in Gemini, model ${d.prefs().aiModel}"))
                listOf("jarvis_chatbox_preferences", "jarvis_chatbox_tools").forEach { f ->
                    val lines = RawPrefs.dump(d.app, f, 60)
                    appendLine()
                    appendLine("ChatBox options in $f (${lines.size}):")
                    if (lines.isEmpty()) appendLine("  none saved") else lines.forEach { appendLine("  $it") }
                }
            })
        }
    }

    // ------------------------------------------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------------------------------------------

    class SettingsRead(private val d: Deps) : Tool {
        override val spec = ToolSpec(
            "app_settings_read",
            "List the app's settings with their current values and what each accepts. Give id for one setting, section for one section, search to find by words, or nothing for all.",
            listOf(
                ToolParam("id", description = "One setting id, such as orb_glow."),
                ToolParam("section", description = "A section name such as Orb, Appearance, Assistant, Task overlay, Voice security."),
                ToolParam("search", description = "Words to look for in names and descriptions.")
            ), Risk.READ, GROUP
        )

        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val r = d.registry
            val id = args.str("id")?.takeIf { it.isNotBlank() }
            val pool = when {
                id != null -> listOf(r.find(id) ?: return ToolResult.Error("No setting called \"$id\". Call app_settings_read with no arguments to see them all."))
                !args.str("section").isNullOrBlank() -> r.inSection(args.str("section").orEmpty()).also {
                    if (it.isEmpty()) return ToolResult.Error("No section called \"${args.str("section")}\". Sections: ${r.sections().joinToString(", ")}.")
                }
                !args.str("search").isNullOrBlank() -> r.search(args.str("search").orEmpty()).also {
                    if (it.isEmpty()) return ToolResult.Ok("No setting matches \"${args.str("search")}\".")
                }
                else -> r.all
            }
            return ToolResult.Ok(pool.groupBy { it.section }.entries.joinToString("\n\n") { (section, list) ->
                section + ":\n" + list.joinToString("\n") { s ->
                    "  ${s.id} = ${runCatching { s.read() }.getOrDefault("(unreadable)")}   [${s.allowed()}]  ${s.label}. ${s.description}"
                }
            })
        }
    }

    class SettingsChange(private val d: Deps) : Tool {
        override val spec = ToolSpec(
            "app_settings_change",
            "Change one or more app settings. Give id and value, or changes as a list of \"id=value\" texts. Every change is read back, so the answer shows the value the app really holds.",
            listOf(
                ToolParam("id", description = "Setting id from app_settings_read."),
                ToolParam("value", description = "The new value."),
                ToolParam("changes", "array", "Several changes at once, each written id=value.")
            ), Risk.WRITE, GROUP
        )

        override fun describe(args: JsonObject): String {
            val pairs = pairsOf(args)
            return if (pairs.isEmpty()) "Change app settings" else "Change app settings: " + pairs.joinToString(", ") { it.first }
        }

        private fun pairsOf(args: JsonObject): List<Pair<String, String>> {
            val out = ArrayList<Pair<String, String>>()
            val id = args.str("id")?.takeIf { it.isNotBlank() }
            val value = args.str("value")
            if (id != null && value != null) out += id to value
            args.strings("changes").forEach { c ->
                val i = c.indexOf('=')
                if (i > 0) out += c.substring(0, i).trim() to c.substring(i + 1)
            }
            return out
        }

        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val pairs = pairsOf(args)
            if (pairs.isEmpty()) return ToolResult.Error("Give id and value, or changes written as id=value.")
            var failed = 0
            val lines = pairs.map { (id, raw) ->
                when (val o = d.registry.change(id, raw)) {
                    is SettingsRegistry.Outcome.Changed -> "changed ${o.def.id}: ${o.before} -> ${o.after}" + (o.note?.let { " ($it)" } ?: "")
                    is SettingsRegistry.Outcome.Unchanged -> "unchanged ${o.def.id}: already ${o.value}"
                    is SettingsRegistry.Outcome.Failed -> { failed++; "NOT changed ${o.id}: ${o.reason}" }
                }
            }
            val text = lines.joinToString("\n") + if (failed < pairs.size) "\nSaved. A screen that is already open may need to be reopened to show the new value." else ""
            return if (failed == pairs.size) ToolResult.Error(text) else ToolResult.Ok(text)
        }
    }

    // ------------------------------------------------------------------------------------------------------
    // Navigation
    // ------------------------------------------------------------------------------------------------------

    class Navigate(private val d: Deps) : Tool {
        override val spec = ToolSpec(
            "app_navigate",
            "Open any screen of the app directly, with no searching through menus. Screen ids are listed by app_overview.",
            listOf(
                ToolParam("screen", required = true, description = "Screen id, such as api_connection, key_manager, alarms or skill_store.", enumValues = AppNavigator.destinations.map { it.id }),
                ToolParam("argument", description = "Only for provider_editor: the provider id to edit.")
            ), Risk.READ, GROUP
        )

        override fun describe(args: JsonObject) = "Open the ${args.str("screen").orEmpty()} screen"

        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult =
            when (val r = AppNavigator.open(d.app, args.str("screen").orEmpty(), args.str("argument"))) {
                is AppNavigator.Result.Opened -> ToolResult.Ok("Opened ${r.destination.label}.")
                is AppNavigator.Result.Failed -> ToolResult.Error(r.reason)
            }
    }

    // ------------------------------------------------------------------------------------------------------
    // API configuration
    // ------------------------------------------------------------------------------------------------------

    class ApiRead(private val d: Deps) : Tool {
        override val spec = ToolSpec(
            "api_settings_read",
            "Read the API configuration: every saved provider with its address, models, authentication and options, and which one is active. Keys are shown only as a short hint, never in full.",
            listOf(ToolParam("profile_id", description = "Show only this provider.")), Risk.READ, GROUP
        )

        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val m = d.providers()
            val activeId = m.activeProfile()?.id
            val only = args.str("profile_id")?.takeIf { it.isNotBlank() }
            val list = if (only != null) listOfNotNull(m.profile(only)).also { if (it.isEmpty()) return ToolResult.Error("No provider with id $only.") } else m.profiles()
            if (list.isEmpty()) return ToolResult.Ok("No custom provider is saved. The built-in Gemini engine (model ${d.prefs().aiModel}) is used. Add one with api_settings_change and create_new.")
            val extra = if (activeId == null) "\nNo custom provider is active; the built-in Gemini engine is used." else ""
            return ToolResult.Ok(list.joinToString("\n") { describeProfile(m, it, it.id == activeId) } + extra)
        }
    }

    class ApiChange(private val d: Deps) : Tool {
        override val spec = ToolSpec(
            "api_settings_change",
            "Change the API configuration of a provider (the active one unless profile_id is given), or add a provider with create_new. Only the fields you give are changed. Saving an api_key always asks the user first.",
            listOf(
                ToolParam("profile_id", description = "Provider to change. Default: the active one."),
                ToolParam("create_new", "boolean", "true adds a new provider (needs base_url)."),
                ToolParam("name", description = "Display name."),
                ToolParam("base_url", description = "Provider address, https only."),
                ToolParam("protocol", description = "openai or gemini.", enumValues = ProviderProtocol.values().map { it.id }),
                ToolParam("auth_method", description = "How the key is sent.", enumValues = AuthMethod.values().map { it.id }),
                ToolParam("auth_name", description = "Header or parameter name for custom-header and query methods."),
                ToolParam("selected_model", description = "Main model id."),
                ToolParam("chat_model", description = "Model id for the ChatBox (empty to use the main model)."),
                ToolParam("stt_model", description = "Speech-to-text model id."),
                ToolParam("discovery_path", description = "Model-list path override."),
                ToolParam("realtime_url", description = "wss address for the live protocol."),
                ToolParam("temperature", description = "0 to 2 (empty to clear)."),
                ToolParam("max_tokens", description = "Positive whole number (empty to clear)."),
                ToolParam("extra_headers", description = "Extra HTTP headers, one per line (empty to clear)."),
                ToolParam("api_key", description = "A new API key to store encrypted. Asks the user first."),
                ToolParam("activate", "boolean", "true makes this provider the active one after saving.")
            ), Risk.WRITE, GROUP
        )

        override fun isDestructive(args: JsonObject, ctx: ToolContext) = !args.str("api_key").isNullOrBlank()

        override fun describe(args: JsonObject): String =
            if (!args.str("api_key").isNullOrBlank()) "Save a new API key (the key is not shown here)"
            else "Change API settings: " + listOf("name", "base_url", "protocol", "auth_method", "auth_name", "selected_model", "chat_model", "stt_model", "discovery_path", "realtime_url", "temperature", "max_tokens", "extra_headers")
                .filter { args.has(it) }.joinToString(", ").ifEmpty { "nothing" }

        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val m = d.providers()
            val create = args.bool("create_new") == true
            val base: ProviderProfile = if (create) {
                val url = args.str("base_url")?.trim().orEmpty()
                if (url.isEmpty()) return ToolResult.Error("base_url is required to add a provider.")
                ProviderProfile(id = m.newId(), name = args.str("name")?.trim().orEmpty(), baseUrl = url)
            } else {
                val id = args.str("profile_id")?.trim()?.takeIf { it.isNotEmpty() } ?: m.activeProfile()?.id
                    ?: return ToolResult.Error("No provider is active. Give profile_id, or use create_new with base_url.")
                m.profile(id) ?: return ToolResult.Error("No provider with id $id. Use api_settings_read to list them.")
            }
            var p = base
            val fields = ArrayList<Triple<String, String, String>>()
            fun track(field: String, before: String, after: String) { if (before != after) fields += Triple(field, before, after) }

            args.str("name")?.let { p = p.copy(name = it.trim()); track("name", base.name, p.name) }
            if (!create) args.str("base_url")?.let { p = p.copy(baseUrl = it.trim()); track("base_url", base.baseUrl, p.baseUrl) }
            args.str("protocol")?.let { v ->
                val proto = ProviderProtocol.values().firstOrNull { it.id.equals(v.trim(), true) } ?: return ToolResult.Error("protocol must be openai or gemini.")
                p = p.copy(protocol = proto); track("protocol", base.protocol.id, proto.id)
            }
            args.str("auth_method")?.let { v ->
                val method = AuthMethod.values().firstOrNull { it.id.equals(v.trim(), true) } ?: return ToolResult.Error("auth_method must be one of: ${AuthMethod.values().joinToString { it.id }}.")
                p = p.copy(authMethod = method); track("auth_method", base.authMethod.id, method.id)
            }
            args.str("auth_name")?.let { p = p.copy(authName = it.trim()); track("auth_name", base.authName, p.authName) }
            args.str("selected_model")?.let { p = p.copy(selectedModelId = it.trim()); track("selected_model", base.selectedModelId, p.selectedModelId) }
            args.str("chat_model")?.let { p = p.copy(chatModelId = it.trim()); track("chat_model", base.chatModelId, p.chatModelId) }
            args.str("stt_model")?.let { p = p.copy(sttModelId = it.trim()); track("stt_model", base.sttModelId, p.sttModelId) }
            args.str("discovery_path")?.let { p = p.copy(discoveryPath = it.trim()); track("discovery_path", base.discoveryPath, p.discoveryPath) }
            args.str("realtime_url")?.let { p = p.copy(realtimeUrl = it.trim()); track("realtime_url", base.realtimeUrl, p.realtimeUrl) }
            fun option(arg: String, key: String) {
                val v = args.str(arg) ?: return
                val next = p.settings.toMutableMap().apply { if (v.isBlank()) remove(key) else put(key, v.trim()) }
                p = p.copy(settings = next)
                track(arg, base.settings[key].orEmpty(), next[key].orEmpty())
            }
            option("temperature", ProviderProfile.SETTING_TEMPERATURE)
            option("max_tokens", ProviderProfile.SETTING_MAX_TOKENS)
            option("extra_headers", ProviderProfile.SETTING_EXTRA_HEADERS)

            val newKey = args.str("api_key")?.trim()?.takeIf { it.isNotEmpty() }
            if (newKey != null) d.redactor.register(newKey)
            if (fields.isEmpty() && newKey == null && !create && args.bool("activate") != true) return ToolResult.Ok("Nothing to change: the provider already has those values.")

            AppChangeWatcher.markJarvisWrite("api")
            m.save(p, newKey)?.let { return ToolResult.Error("Not saved: $it") }
            fields.forEach { (f, before, after) ->
                val hide = f == "extra_headers"
                d.journal.record("api", "${p.displayName}.$f", if (hide) null else before, if (hide) null else after, "jarvis")
            }
            if (newKey != null) d.journal.record("api", "${p.displayName}.api_key", null, null, "jarvis")
            if (create) d.journal.record("api", p.displayName, null, "added", "jarvis")

            val sb = StringBuilder()
            sb.append(if (create) "Added provider ${p.displayName} (id ${p.id}).\n" else "Saved ${p.displayName}.\n")
            fields.forEach { (f, before, after) ->
                sb.append("  $f: ").append(if (f == "extra_headers") "updated" else "\"$before\" -> \"$after\"").append('\n')
            }
            if (newKey != null) sb.append("  api_key: replaced (stored encrypted)\n")
            if (args.bool("activate") == true) {
                when (val r = m.activate(p.id)) {
                    is ActivationResult.Activated -> sb.append("Now active.").append(r.warning?.let { " Note: $it" } ?: "")
                    is ActivationResult.Rejected -> sb.append("Saved, but it could not be made active: ${r.reason}")
                }
            }
            return ToolResult.Ok(sb.toString().trimEnd())
        }
    }

    class ApiSwitch(private val d: Deps) : Tool {
        override val spec = ToolSpec(
            "api_provider_switch",
            "Make a saved provider the active one for the ChatBox, or switch back to the built-in Gemini engine with deactivate=true.",
            listOf(
                ToolParam("profile_id", description = "Provider id from api_settings_read."),
                ToolParam("deactivate", "boolean", "true stops using a custom provider.")
            ), Risk.WRITE, GROUP
        )

        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val m = d.providers()
            val before = m.activeProfile()?.displayName ?: "built-in Gemini"
            AppChangeWatcher.markJarvisWrite("api")
            if (args.bool("deactivate") == true) {
                m.deactivate()
                d.journal.record("api", "active_provider", before, "built-in Gemini", "jarvis")
                return ToolResult.Ok("Switched from $before to the built-in Gemini engine.")
            }
            val id = args.str("profile_id")?.trim()?.takeIf { it.isNotEmpty() } ?: return ToolResult.Error("Give profile_id, or deactivate=true.")
            if (m.profile(id) == null) return ToolResult.Error("No provider with id $id. Use api_settings_read to list them.")
            return when (val r = m.activate(id)) {
                is ActivationResult.Activated -> {
                    d.journal.record("api", "active_provider", before, r.profile.displayName, "jarvis")
                    ToolResult.Ok("Switched from $before to ${r.profile.displayName}." + (r.warning?.let { " Note: $it" } ?: ""))
                }
                is ActivationResult.Rejected -> ToolResult.Error("Not switched: ${r.reason}")
            }
        }
    }

    class ApiTest(private val d: Deps) : Tool {
        override val spec = ToolSpec(
            "api_connection_test",
            "Test a saved provider's connection with its saved key and report each step honestly.",
            listOf(ToolParam("profile_id", description = "Provider to test. Default: the active one.")), Risk.NETWORK, GROUP
        )

        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val m = d.providers()
            val id = args.str("profile_id")?.trim()?.takeIf { it.isNotEmpty() } ?: m.activeProfile()?.id ?: return ToolResult.Error("No provider is active. Give profile_id.")
            val p = m.profile(id) ?: return ToolResult.Error("No provider with id $id.")
            val report = m.testConnection(p, null, p.chatModel?.id.orEmpty())
            val text = "Test of ${p.displayName}: " + (if (report.ok) "passed" else "FAILED") + (report.latencyMs?.let { " (${it} ms)" } ?: "") + "\n" +
                report.steps.joinToString("\n") { "  ${it.status}  ${it.title}: ${it.detail}" }
            return if (report.ok) ToolResult.Ok(text) else ToolResult.Error(text)
        }
    }

    // ------------------------------------------------------------------------------------------------------
    // What changed
    // ------------------------------------------------------------------------------------------------------

    class Changes(private val d: Deps) : Tool {
        override val spec = ToolSpec(
            "app_changes",
            "Say what changed in the app, when and by whom: every setting change made on a screen, by the app or by Jarvis, plus the latest tool actions. Secret values are never shown.",
            listOf(
                ToolParam("limit", "integer", "How many changes (default 30, at most 200)."),
                ToolParam("search", description = "Only changes whose name or value contains this text."),
                ToolParam("source", description = "jarvis for changes Jarvis made, user for changes made on screen or by the app.", enumValues = listOf("jarvis", "user")),
                ToolParam("last_minutes", "integer", "Only changes in the last N minutes.")
            ), Risk.READ, GROUP
        )

        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val since = args.int("last_minutes")?.takeIf { it > 0 }?.let { System.currentTimeMillis() - it * 60_000L }
            val changes = d.journal.recent(args.int("limit") ?: 30, args.str("search"), args.str("source"), since)
            val tools = d.audit.recent(10)
            return ToolResult.Ok(buildString {
                appendLine("CHANGES (${changes.size} shown, ${d.journal.count()} on record):")
                if (changes.isEmpty()) appendLine("  none matched") else changes.forEach { appendLine("  " + d.journal.describe(it)) }
                appendLine()
                appendLine("LATEST TOOL ACTIONS:")
                if (tools.isEmpty()) append("  none in this session") else tools.forEach {
                    appendLine("  ${stamp(it.time)}  ${it.tool}  (${it.decision})  ${it.summary}  -> ${it.outcome}")
                }
            }.trimEnd())
        }
    }

    // ------------------------------------------------------------------------------------------------------
    // Raw preference files (for anything the catalog does not list)
    // ------------------------------------------------------------------------------------------------------

    class RawRead(private val d: Deps) : Tool {
        override val spec = ToolSpec(
            "app_prefs_raw_read",
            "Read the app's raw saved options, including ones with no entry in app_settings_read. Without file, lists the files; with file, lists its keys. Secrets are hidden.",
            listOf(ToolParam("file", description = "A preference file name from the first listing.")), Risk.READ, GROUP
        )

        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val file = args.str("file")?.trim()?.takeIf { it.isNotEmpty() }
                ?: return ToolResult.Ok("Preference files:\n" + AppChangeWatcher.WATCHED_FILES.joinToString("\n") { f ->
                    val n = d.app.applicationContext.getSharedPreferences(f, Context.MODE_PRIVATE).all.size
                    "  $f ($n keys)"
                })
            if (!RawPrefs.isKnownFile(file)) return ToolResult.Error("\"$file\" is not a settings file of this app. Call without file to list them.")
            val lines = RawPrefs.dump(d.app, file)
            return ToolResult.Ok(if (lines.isEmpty()) "$file holds no keys." else "$file:\n" + lines.joinToString("\n") { "  $it" })
        }
    }

    class RawWrite(private val d: Deps) : Tool {
        override val spec = ToolSpec(
            "app_prefs_raw_write",
            "Change one existing raw option that app_settings_change can not reach. Always asks the user first. The key keeps its type; secrets, permission rules and provider storage are refused.",
            listOf(
                ToolParam("file", required = true, description = "Preference file name."),
                ToolParam("key", required = true, description = "An existing key in that file."),
                ToolParam("value", required = true, description = "The new value.")
            ), Risk.DESTRUCTIVE, GROUP
        )

        override fun describe(args: JsonObject) = "Change raw option ${args.str("file").orEmpty()}/${args.str("key").orEmpty()}"

        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val file = args.str("file").orEmpty().trim()
            val key = args.str("key").orEmpty().trim()
            val value = args.str("value").orEmpty()
            val before = RawPrefs.dump(d.app, file, 10_000).firstOrNull { it.startsWith("$key = ") }
            RawPrefs.write(d.app, file, key, value)?.let { return ToolResult.Error("Not changed: $it") }
            val after = RawPrefs.dump(d.app, file, 10_000).firstOrNull { it.startsWith("$key = ") }
            return ToolResult.Ok("Changed $file/$key.\n  before: ${before ?: "(unknown)"}\n  now:    ${after ?: "(unknown)"}")
        }
    }
}
