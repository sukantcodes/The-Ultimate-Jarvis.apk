package com.jarvis.assistant.tools

import com.google.gson.JsonObject
import com.jarvis.assistant.skills.SkillStore

/** ChatBox tools backed by the same Skill Store shown in JARVIS Settings. */
object SkillTools {
    fun all(store: SkillStore): List<Tool> = listOf(
        object : Tool {
            override val spec = ToolSpec("list_skills", "List installed and enabled JARVIS skills.",
                emptyList(), Risk.READ, "skills")
            override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
                val skills = store.installed()
                if (skills.isEmpty()) return ToolResult.Ok("No skills are installed.")
                return ToolResult.Ok(skills.joinToString("\n") {
                    "${it.name} [${if (store.isEnabled(it.name)) "enabled" else "disabled"}] — ${it.description}"
                })
            }
        },
        object : Tool {
            override val spec = ToolSpec("use_skill", "Load the full instructions for an installed, enabled JARVIS skill.",
                listOf(ToolParam("name", required = true, description = "Exact skill name from list_skills.")),
                Risk.READ, "skills")
            override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
                val name = args.get("name")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                if (name.isBlank()) return ToolResult.Error("Give the skill name.")
                return ToolResult.Ok(store.useSkill(name))
            }
        },
        object : Tool {
            override val spec = ToolSpec("refresh_skill_catalog", "Refresh the configured GitHub skill-store catalog.",
                emptyList(), Risk.NETWORK, "skills")
            override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult = try {
                val entries = store.fetchCatalog()
                ToolResult.Ok(if (entries.isEmpty()) "The skill catalog is empty." else entries.joinToString("\n") { "${it.name} — ${it.description}" })
            } catch (e: Exception) {
                ToolResult.Error(e.message ?: "Could not refresh the skill catalog.")
            }
        },
        object : Tool {
            override val spec = ToolSpec("install_skill", "Download and install one Markdown skill from the configured JARVIS Skill Store.",
                listOf(ToolParam("name", required = true, description = "Skill name from refresh_skill_catalog.")),
                Risk.WRITE, "skills")
            override fun describe(args: JsonObject) = "Download and install skill '${args.get("name")?.asString.orEmpty()}' from the configured skill store"
            override fun isDestructive(args: JsonObject, ctx: ToolContext): Boolean {
                val name = args.get("name")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                return store.find(name)?.builtIn == false
            }
            override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
                val name = args.get("name")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
                if (name.isBlank()) return ToolResult.Error("Give the skill name.")
                val catalog = try { store.cachedCatalog().ifEmpty { store.fetchCatalog() } } catch (e: Exception) {
                    return ToolResult.Error(e.message ?: "Could not read the skill catalog.")
                }
                val entry = catalog.firstOrNull { it.name.equals(name, true) }
                    ?: return ToolResult.Error("Skill '$name' was not found in the configured catalog.")
                return store.install(entry).fold(
                    onSuccess = { ToolResult.Ok("Installed skill '${it.name}'. It is now available to JARVIS.") },
                    onFailure = { ToolResult.Error(it.message ?: "The skill could not be installed.") }
                )
            }
        },
        object : Tool {
            override val spec = ToolSpec("set_skill_enabled", "Enable or disable an installed skill for future ChatBox turns.",
                listOf(ToolParam("name", required = true), ToolParam("enabled", "boolean", "true to enable; false to disable.", required = true)),
                Risk.WRITE, "skills")
            override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
                val name = args.get("name")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                val enabled = args.get("enabled")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asBoolean }.getOrNull() }
                    ?: return ToolResult.Error("The enabled argument must be true or false.")
                val skill = store.find(name) ?: return ToolResult.Error("That skill is not installed.")
                store.setEnabled(skill.name, enabled)
                return ToolResult.Ok("${skill.name} is now ${if (enabled) "enabled" else "disabled"}.")
            }
        },
        object : Tool {
            override val spec = ToolSpec("remove_skill", "Uninstall a downloaded skill. Built-in skills cannot be removed.",
                listOf(ToolParam("name", required = true)), Risk.DESTRUCTIVE, "skills")
            override fun describe(args: JsonObject) = "Uninstall skill '${args.get("name")?.asString.orEmpty()}'"
            override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
                val name = args.get("name")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                val skill = store.find(name) ?: return ToolResult.Error("That skill is not installed.")
                if (skill.builtIn) return ToolResult.Error("Built-in skills cannot be removed. You can disable them with set_skill_enabled.")
                store.uninstall(skill.name)
                return ToolResult.Ok("Removed skill '${skill.name}'.")
            }
        }
    )
}
