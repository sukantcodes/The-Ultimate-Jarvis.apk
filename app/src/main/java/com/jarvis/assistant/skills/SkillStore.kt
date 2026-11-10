package com.jarvis.assistant.skills

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** One skill, either shipped inside the app (built in) or downloaded from a skill store. */
data class Skill(
    val name: String,
    val description: String,
    val body: String,
    val updated: String,
    val builtIn: Boolean
)

/** One line of a store's index.json. */
data class CatalogEntry(val name: String, val description: String, val file: String, val updated: String)

/**
 * Skills are step-by-step playbooks (Hinglish instructions) that teach Jarvis how to do a job well.
 *
 *  - Built-in skills ship in assets/skills and are written for Jarvis's real tools.
 *  - Store skills are downloaded from a GitHub skill store (an index.json plus a skills folder of .md files) when you tap Install.
 *    Nothing is updated silently: when the store has a newer copy you see "Update" and choose.
 *  - Only enabled skills reach the AI. Their one-line descriptions go into the system prompt, and the full
 *    steps are loaded on demand by the use_skill tool, so many skills cost very little context.
 */
class SkillStore private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val dir = File(appContext.filesDir, "skills").apply { mkdirs() }
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** The GitHub store as "owner/repo". */
    var repo: String
        get() = prefs.getString(KEY_REPO, DEFAULT_REPO)?.takeIf { SkillParser.isValidRepo(it) } ?: DEFAULT_REPO
        set(value) { if (SkillParser.isValidRepo(value.trim())) prefs.edit().putString(KEY_REPO, value.trim()).apply() }

    // ---- Installed skills --------------------------------------------------------------------

    @Synchronized
    fun installed(): List<Skill> {
        val out = LinkedHashMap<String, Skill>()
        builtIns().forEach { out[it.name] = it }
        dir.listFiles { f -> f.isFile && f.name.endsWith(".md") }?.sortedBy { it.name }?.forEach { file ->
            val parsed = try { SkillParser.parse(file.readText()) } catch (_: Exception) { null } ?: return@forEach
            if (parsed.name in out) return@forEach // a built-in always wins over a downloaded copy of the same name
            out[parsed.name] = Skill(parsed.name, parsed.description, parsed.body, prefs.getString(KEY_UPDATED + parsed.name, "") ?: "", false)
        }
        return out.values.toList()
    }

    fun find(name: String): Skill? = installed().firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    fun isEnabled(name: String): Boolean = prefs.getBoolean(KEY_ENABLED + name, true)

    fun setEnabled(name: String, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED + name, enabled).apply()
    }

    fun enabledSkills(): List<Skill> = installed().filter { isEnabled(it.name) }

    /** Switches every installed skill on or off in one step. */
    fun setAllEnabled(enabled: Boolean) {
        val editor = prefs.edit()
        installed().forEach { editor.putBoolean(KEY_ENABLED + it.name, enabled) }
        editor.apply()
    }

    private fun builtIns(): List<Skill> {
        val root = "skills"
        val flatFiles = try {
            appContext.assets.list(root)?.filter { it.endsWith(".md") }?.sorted().orEmpty()
        } catch (error: Exception) {
            android.util.Log.w("JarvisSkills", "Could not enumerate built-in skill assets at $root", error)
            emptyList()
        }
        val clozedDirectories = try {
            appContext.assets.list("$root/clozed")?.filter { it.matches(Regex("^[a-z0-9][a-z0-9-]{1,39}$")) }?.sorted().orEmpty()
        } catch (error: Exception) {
            android.util.Log.w("JarvisSkills", "Could not enumerate Clozed skill assets at $root/clozed", error)
            emptyList()
        }
        val paths = flatFiles.map { "$root/$it" } +
            clozedDirectories.map { "$root/clozed/$it/SKILL.md" }
        val out = LinkedHashMap<String, Skill>()
        paths.forEach { path ->
            try {
                val text = appContext.assets.open(path).bufferedReader().use { it.readText() }
                val parsed = SkillParser.parse(text)
                if (parsed == null) {
                    android.util.Log.w("JarvisSkills", "Ignoring malformed built-in skill asset: $path")
                } else if (parsed.name !in out) {
                    out[parsed.name] = Skill(parsed.name, parsed.description, parsed.body, "built-in", true)
                }
            } catch (error: Exception) {
                android.util.Log.w("JarvisSkills", "Could not load built-in skill asset: $path", error)
            }
        }
        return out.values.toList()
    }

    // ---- Store (network) ---------------------------------------------------------------------

    private fun rawUrl(path: String) = "https://raw.githubusercontent.com/${repo}/main/$path"

    private fun download(url: String): String {
        val request = Request.Builder().url(url).header("Accept", "text/plain").build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("The store answered HTTP ${response.code}.")
            val source = response.body?.byteStream() ?: error("The store sent an empty answer.")
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            var read = source.read(chunk)
            while (read >= 0) {
                buffer.write(chunk, 0, read)
                if (buffer.size() > MAX_DOWNLOAD_BYTES) error("The file is too large to be a skill.")
                read = source.read(chunk)
            }
            buffer.toString(Charsets.UTF_8.name())
        }
    }

    /** Reads the store's index.json and remembers it for offline viewing. Call from a background thread. */
    fun fetchCatalog(): List<CatalogEntry> {
        val raw = try { download(rawUrl("index.json")) } catch (e: Exception) { throw friendly(e) }
        val entries = try { parseCatalog(raw) } catch (e: Exception) { throw friendly(e) }
        prefs.edit().putString(KEY_CATALOG, raw).putString(KEY_CATALOG_REPO, repo).apply()
        return entries
    }

    /** The last catalog downloaded from the current store, or empty when there is none yet. */
    fun cachedCatalog(): List<CatalogEntry> {
        if (prefs.getString(KEY_CATALOG_REPO, "") != repo) return emptyList()
        val raw = prefs.getString(KEY_CATALOG, null) ?: return emptyList()
        return try { parseCatalog(raw) } catch (_: Exception) { emptyList() }
    }

    private fun parseCatalog(raw: String): List<CatalogEntry> {
        val array = JSONObject(raw).getJSONArray("skills")
        val seen = HashSet<String>()
        val out = ArrayList<CatalogEntry>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val name = item.optString("name")
            val file = item.optString("file")
            if (!SkillParser.isValidName(name) || !SkillParser.isSafeSkillPath(file) || !seen.add(name)) continue
            out.add(
                CatalogEntry(
                    name,
                    SkillParser.shorten(item.optString("description").trim(), SkillParser.MAX_DESCRIPTION),
                    file,
                    item.optString("updated")
                )
            )
        }
        return out.sortedBy { it.name }
    }

    /** Turns low-level network errors into a sentence a person can act on. */
    private fun friendly(e: Exception): Exception = when (e) {
        is java.net.UnknownHostException, is java.net.ConnectException ->
            java.io.IOException("Could not reach the store. Check your internet connection.", e)
        is java.net.SocketTimeoutException ->
            java.io.IOException("The store took too long to answer. Try again in a moment.", e)
        is org.json.JSONException ->
            java.io.IOException("The store's list is not in the expected format.", e)
        else -> e
    }

    /** Downloads and installs (or updates) one store skill. Call from a background thread. */
    fun install(entry: CatalogEntry): Result<Skill> = try {
        val text = try { download(rawUrl(entry.file)) } catch (e: Exception) { throw friendly(e) }
        val parsed = SkillParser.parse(text) ?: error("That file is not a valid skill.")
        if (parsed.name != entry.name) error("The skill's name does not match the store list.")
        if (builtIns().any { it.name == parsed.name }) error("${parsed.name} is already built in.")
        // Write to a temporary file first so a failed write can never leave a half-installed skill behind.
        val target = File(dir, parsed.name + ".md")
        val temp = File(dir, parsed.name + ".tmp")
        try {
            temp.writeText("---\nname: ${parsed.name}\ndescription: ${parsed.description}\n---\n${parsed.body}\n")
            if (!temp.renameTo(target)) {
                target.writeText(temp.readText())
            }
        } finally {
            temp.delete()
        }
        prefs.edit().putString(KEY_UPDATED + parsed.name, entry.updated).putBoolean(KEY_ENABLED + parsed.name, true).apply()
        Result.success(Skill(parsed.name, parsed.description, parsed.body, entry.updated, false))
    } catch (e: Exception) {
        Result.failure(e)
    }

    fun uninstall(name: String) {
        if (!SkillParser.isValidName(name)) return
        if (builtIns().any { it.name == name }) return // built-in skills can be switched off, not removed
        File(dir, "$name.md").delete()
        prefs.edit().remove(KEY_UPDATED + name).remove(KEY_ENABLED + name).apply()
    }

    // ---- What the AI sees --------------------------------------------------------------------

    /** The one-line index added to the system prompt. Empty when no skill is switched on. */
    fun promptSection(): String {
        val skills = enabledSkills().take(MAX_IN_PROMPT)
        if (skills.isEmpty()) return ""
        val sb = StringBuilder("\n\n=== INSTALLED SKILLS ===\n")
        sb.append("Skills are step-by-step playbooks. When the user's request matches one below, call the use_skill tool ")
        sb.append("with its name FIRST, then follow its steps using only the tools you really have. ")
        sb.append("The no-confirmation policy overrides any step that says to ask or confirm, EXCEPT the owner's exceptions written in that policy ")
        sb.append("(final Amazon orders, Rapido bookings and cancellations, Blinkit orders and cancellations, and alarms): those confirmations always stay in force.\n")
        sb.append("Never claim a step succeeded unless a tool result or the screen confirms it, and never invent a tool that is not in your tool list.\n")
        skills.forEach { sb.append("- ").append(it.name).append(": ").append(it.description).append('\n') }
        sb.append("=== END OF SKILLS ===\n")
        return sb.toString()
    }

    /** The text returned to the AI when it calls use_skill. */
    fun useSkill(name: String): String {
        val skill = find(name)
        if (skill == null || !isEnabled(skill.name)) {
            val names = enabledSkills().joinToString(", ") { it.name }.ifBlank { "none" }
            val hint = if (skill != null) " It is installed but switched off in Settings > Skill Store." else ""
            return "No enabled skill called '${name.trim()}'.$hint Enabled skills: $names."
        }
        val tail = if (skill.builtIn) "" else "\n\n" + TOOL_MAP
        return "SKILL: ${skill.name}\n${skill.body}$tail"
    }

    companion object {
        const val DEFAULT_REPO = "HunterisLive-1/mobile-maya-skills"
        private const val PREF_NAME = "jarvis_skills"
        private const val KEY_REPO = "store_repo"
        private const val KEY_ENABLED = "enabled_"
        private const val KEY_UPDATED = "updated_"
        private const val KEY_CATALOG = "catalog_cache"
        private const val KEY_CATALOG_REPO = "catalog_cache_repo"
        private const val MAX_IN_PROMPT = 40
        private const val MAX_DOWNLOAD_BYTES = 64 * 1024

        /** Downloaded skills may be written for another app's tool names; this maps them onto Jarvis's real tools. */
        const val TOOL_MAP =
            "TOOL NAME MAP: this playbook may use another assistant's tool names. Your real tools are: " +
                "send_whatsapp_message(recipient_name, message), whatsapp_call(recipient_name, call_type), " +
                "call_contact(contact_name, sim), call_control(action), recall_activity(query, hours), " +
                "device_status, set_volume(action, percentage), set_brightness(action, percentage), set_flashlight(enabled), " +
                "open_app(app_name), open_website(urls), open_settings_page(page), search_in_chrome(query), builtin_chrome_search(query), " +
                "search_youtube(query), search_and_play_youtube(query), youtube_accessibility_action(action), media_playback_control(action), " +
                "social_app_action(app, action, query), run_macro(name, input), stop_macro, " +
                "lock_device, unlock_device, show_power_menu, go_to_background, clean_jarvis_cache, " +
                "inspect_screen, click_ui_element, type_text, scroll_screen, press_key(back|home|recents), task_completed(summary), " +
                "the amazon_*, blinkit_* and rapido_* shopping and ride tools, and the clock_* alarm tools. " +
                "Map whatsapp_send_message to send_whatsapp_message, whatsapp_read_messages to recall_activity with query 'WhatsApp', " +
                "make_call to call_contact, and any answer/reject, camera, file-sharing or sub-agent step to inspect_screen + click_ui_element " +
                "(or run_macro when a matching macro exists). There is no remember_fact or recall_memory tool: skip those steps. " +
                "If a step needs something you cannot do, tell the user honestly instead of pretending."

        @Volatile private var instance: SkillStore? = null
        fun get(context: Context): SkillStore =
            instance ?: synchronized(this) { instance ?: SkillStore(context).also { instance = it } }
    }
}
