package com.jarvis.assistant.tools.appcontrol

import com.jarvis.assistant.data.preferences.AppPreferences
import java.util.Locale

/**
 * Every ordinary setting Jarvis may read and change, wired to [AppPreferences] so the same rules apply as on the
 * Settings screens. API keys, voice prints and personal details are not here: keys go through the API tools (which
 * always ask first) and the rest is never needed to run the app.
 */
object AppSettingsCatalog {
    private val HEX = Regex("^#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$")

    val ORB_STYLES = listOf("default", "eleptical_orb", "aurora_orb", "iron_man_head", "full_human", "quantum_core", "siri_glow")
    val OVERLAY_AVATARS = listOf("alien", "blob", "cat", "circle", "cloud", "droid", "drop", "flower", "ghost", "hexagon", "mech", "pebble", "pill", "puddle", "square", "star", "triangle")

    fun build(p: AppPreferences): List<SettingDef> {
        fun bool(id: String, section: String, label: String, desc: String, get: () -> Boolean, set: (Boolean) -> Unit) =
            SettingDef(id, section, label, SettingType.BOOLEAN, desc, read = { get().toString() }, write = { set(it.toBoolean()) })

        fun text(id: String, section: String, label: String, desc: String, get: () -> String, set: (String) -> Unit, validator: ((String) -> String?)? = null) =
            SettingDef(id, section, label, SettingType.TEXT, desc, validator = validator, read = get, write = set)

        fun choice(id: String, section: String, label: String, desc: String, options: List<String>, get: () -> String, set: (String) -> Unit) =
            SettingDef(id, section, label, SettingType.CHOICE, desc, choices = options, read = get, write = set)

        fun float(id: String, section: String, label: String, desc: String, min: Double, max: Double, get: () -> Float, set: (Float) -> Unit) =
            SettingDef(id, section, label, SettingType.FLOAT, desc, min = min, max = max, read = { get().toString() }, write = { set(it.toFloat()) })

        fun int(id: String, section: String, label: String, desc: String, min: Double, max: Double, get: () -> Int, set: (Int) -> Unit) =
            SettingDef(id, section, label, SettingType.INT, desc, min = min, max = max, read = { get().toString() }, write = { set(it.toInt()) })

        return listOf(
            // ---- Assistant ----
            text("user_name", "Assistant", "Your name", "What Jarvis calls you.", { p.userName }, { p.userName = it }),
            text("language", "Assistant", "Language", "Speech language, or Auto.", { p.language }, { p.language = it }),
            text("voice", "Assistant", "Voice", "The voice Jarvis speaks with.", { p.voice }, { p.voice = it }),
            text("personality", "Assistant", "Personality", "Personality style.", { p.personality }, { p.personality = it }),
            text("ai_model", "Assistant", "Gemini model", "The built-in Gemini model. The app keeps only models it supports.", { p.aiModel }, { p.aiModel = it }),
            bool("mic_muted", "Assistant", "Microphone muted", "Mutes Jarvis's microphone.", { p.isMicMuted }, { p.isMicMuted = it }),
            bool("ambient_context", "Assistant", "Ambient conversation context", "Lets Jarvis use recent surroundings as context.", { p.isAmbientContextEnabled }, { p.isAmbientContextEnabled = it }),
            bool("silent_execution", "Assistant", "Silent execution", "Runs tasks without speaking each step.", { p.isSilentExecutionMode }, { p.isSilentExecutionMode = it }),
            bool("voice_workspace_tools", "Assistant", "Workspace tools in voice", "Lets voice sessions use files, web, previews and GitHub tools.", { p.voiceWorkspaceTools }, { p.voiceWorkspaceTools = it }),
            bool("self_aware_mode", "Assistant", "Self-aware mode", "Jarvis may use knowledge of its own app and settings.", { p.selfAwareMode }, { p.selfAwareMode = it }),

            // ---- Voice security ----
            bool("owner_voice_enabled", "Voice security", "Owner voice check", "Only the enrolled voice can wake and unlock.", { p.isOwnerVoiceEnabled }, { p.isOwnerVoiceEnabled = it }),
            bool("owner_voice_only_wake_and_unlock", "Voice security", "Owner voice only for wake and unlock", "Limits the voice check to wake-up and unlock.", { p.ownerVoiceOnlyWakeAndUnlock }, { p.ownerVoiceOnlyWakeAndUnlock = it }),
            float("owner_voice_threshold", "Voice security", "Owner voice strictness", "Higher is stricter. Values at or below 0.70 are reset to 0.80 by the app.", 0.71, 0.99, { p.ownerVoiceThreshold }, { p.ownerVoiceThreshold = it }),

            // ---- Appearance ----
            choice("app_theme", "Appearance", "Theme", "The app theme.", listOf("light", "dark", "red", "sunset", "custom"), { p.appTheme }, { p.appTheme = it }),
            text("custom_theme_color", "Appearance", "Custom theme colour", "Hex colour such as #12AB34 (used by the custom theme).", { p.customThemeColor }, { p.customThemeColor = it },
                validator = { if (HEX.matches(it)) null else "Use a hex colour like #12AB34." }),
            text("custom_theme_color_2", "Appearance", "Custom theme second colour", "Optional second hex colour for a blend. Empty for a single colour.", { p.customThemeColor2 }, { p.customThemeColor2 = it },
                validator = { if (it.isEmpty() || HEX.matches(it)) null else "Use a hex colour like #12AB34, or leave it empty." }),
            int("liquid_glass_strength", "Appearance", "Liquid glass strength", "Strength of the glass effect.", 0.0, 100.0, { p.liquidGlassStrength }, { p.liquidGlassStrength = it }),
            int("settings_icon_shape", "Appearance", "Settings icon shape", "0 plain, 1 rectangular, 2 pillowy, 3 circular.", 0.0, 3.0, { p.settingsIconShape }, { p.settingsIconShape = it }),
            bool("touch_glow", "Appearance", "Touch glow", "The halo that follows your touch.", { p.touchGlowEnabled }, { p.touchGlowEnabled = it }),
            choice("ui_refresh_rate", "Appearance", "Refresh rate", "Preferred screen refresh rate in Hz, or system.", listOf("system", "60", "90", "120", "144"), { p.uiRefreshRate }, { p.uiRefreshRate = it }),

            // ---- Orb ----
            choice("orb_animation", "Orb", "Orb style", "Which orb is shown.", ORB_STYLES, { p.orbAnimation }, { p.orbAnimation = it }),
            float("orb_glow", "Orb", "Glow of the selected orb", "0 is off, 2 is intense. Applies to the orb style that is selected now.", 0.0, 2.0,
                { p.getOrbGlow(p.orbAnimation) }, { p.setOrbGlow(p.orbAnimation, it) }),
            float("orb_size", "Orb", "Size of the selected orb", "0.6 small to 1.4 large. Applies to the orb style that is selected now.", 0.6, 1.4,
                { p.getOrbSize(p.orbAnimation) }, { p.setOrbSize(p.orbAnimation, it) }),
            float("orb_speed", "Orb", "Orb animation speed", "1 is normal speed.", 0.5, 2.0, { p.orbSpeed }, { p.orbSpeed = it }),
            float("orb_hue", "Orb", "Orb hue", "Hue in degrees from 0 to 360, or -1 to follow the theme.", -1.0, 360.0, { p.orbHue }, { p.orbHue = it }),

            // ---- Task overlay ----
            bool("task_overlay_enabled", "Task overlay", "Task overlay", "The floating avatar shown while Jarvis works.", { p.isTaskOverlayEnabled }, { p.isTaskOverlayEnabled = it }),
            choice("task_overlay_avatar", "Task overlay", "Overlay avatar", "Which avatar floats on screen.", OVERLAY_AVATARS, { p.taskOverlayAvatar }, { p.taskOverlayAvatar = it }),
            choice("task_overlay_mode", "Task overlay", "Overlay visibility", "always, or only while working.", listOf("always", "working"), { p.taskOverlayMode }, { p.taskOverlayMode = it }),
            choice("task_overlay_edge", "Task overlay", "Overlay edge", "Which screen edge the avatar docks to.", listOf("left", "right", "top", "bottom"), { p.taskOverlayEdge }, { p.taskOverlayEdge = it }),
            bool("task_overlay_free_position", "Task overlay", "Overlay free position", "Lets the avatar stay wherever you drop it.", { p.taskOverlayFreePosition }, { p.taskOverlayFreePosition = it })
        ).also { list ->
            check(list.map { it.id }.toSet().size == list.size) { "Duplicate setting id" }
            check(list.all { it.id == it.id.lowercase(Locale.ROOT) })
        }
    }
}
