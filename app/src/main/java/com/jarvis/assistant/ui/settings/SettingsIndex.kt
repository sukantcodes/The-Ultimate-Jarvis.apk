package com.jarvis.assistant.ui.settings

import com.jarvis.assistant.ui.udn.UdnActivity
import com.jarvis.assistant.ui.voice.VoiceEnrollmentActivity

/** Everything the Settings search can find, with where to open it. Row ids let the target screen scroll to and pulse the row. */
object SettingsIndex {

    class Entry(val title: String, val detail: String, val category: String, val target: Class<*>, val focusRow: String?, val keywords: String) {
        val haystack: String = (title + " " + detail + " " + category + " " + keywords).lowercase()
    }

    val entries: List<Entry> = listOf(
        Entry("AI & Models", "Open AI & Models settings", "Settings", AiSettingsActivity::class.java, null, "ai model gemini groq engine key language persona"),
        Entry("Voice", "Open Voice settings", "Settings", VoiceSettingsActivity::class.java, null, "voice speech speak sound microphone"),
        Entry("Security", "Open Security settings", "Settings", SecuritySettingsActivity::class.java, null, "security lock privacy owner face unlock pin"),
        Entry("Alerts", "Open Alerts settings", "Settings", AlertsSettingsActivity::class.java, null, "alerts notification message battery charging"),
        Entry("Memory", "Open Memory settings", "Settings", MemorySettingsActivity::class.java, null, "memory facts history remember"),
        Entry("Permissions", "Open Permissions settings", "Settings", PermissionsSettingsActivity::class.java, null, "permissions access apps automation terminal clock gallery"),
        Entry("Appearance", "Open Appearance settings", "Settings", AppearanceSettingsActivity::class.java, null, "appearance theme colour color look glass background animation transparent"),
        Entry("About", "Open About settings", "Settings", AboutSettingsActivity::class.java, null, "about privacy identity version"),
        Entry("Owner", "Open Owner settings", "Settings", OwnerSettingsActivity::class.java, null, "owner dashboard analytics"),
        Entry("AI Engine", "Choose Gemini Live or Groq", "AI & Models", AiSettingsActivity::class.java, "rowAiEngine", ""),
        Entry("API Key & Model", "Gemini key, model and connection test", "AI & Models", AiSettingsActivity::class.java, "rowApiConnection", ""),
        Entry("Conversation Language", "Auto (all Indian languages)", "AI & Models", AiSettingsActivity::class.java, "rowLanguage", ""),
        Entry("AI Persona", "Personality and voice tone", "AI & Models", AiSettingsActivity::class.java, "rowPersonaVoice", ""),
        Entry("Silent Execution", "Run device commands without a spoken reply", "AI & Models", AiSettingsActivity::class.java, "rowSilentMode", ""),
        Entry("Voice & Gender", "Choose the voice Jarvis speaks with", "Voice", VoiceSettingsActivity::class.java, "rowVoiceGender", ""),
        Entry("Noise Cancellation", "Filter background noise from what Jarvis hears", "Voice", VoiceSettingsActivity::class.java, "rowNoiseCancel", ""),
        Entry("Auto-Sleep", "Return to standby after a quiet period", "Voice", VoiceSettingsActivity::class.java, "rowAutoSleep", ""),
        Entry("Default Assistant", "Open Jarvis with a power-button hold or corner swipe", "Voice", VoiceSettingsActivity::class.java, "rowDefaultAssistant", ""),
        Entry("Owner Voice Profile", "Voice profile active", "Security", SecuritySettingsActivity::class.java, "rowOwnerVoice", ""),
        Entry("Replay setup tutorial", "Walk through permissions, profile, AI, Accessibility and voice again", "Security", SecuritySettingsActivity::class.java, "rowReplayTutorial", ""),
        Entry("Owner Voice Only", "Ignore every voice except your enrolled one", "Security", SecuritySettingsActivity::class.java, "rowVoiceMode", ""),
        Entry("Screen Unlock", "Save your PIN, password or pattern to unlock by voice", "Security", SecuritySettingsActivity::class.java, "rowScreenUnlock", ""),
        Entry("Face Lock Settings", "Guided face scan and profiles", "Security", SecuritySettingsActivity::class.java, "rowFaceLockSettings", ""),
        Entry("Notification Access", "Allow Jarvis to see new message notifications", "Alerts", AlertsSettingsActivity::class.java, "rowNotificationAccess", ""),
        Entry("Message Announcements", "Say who a new WhatsApp, Instazen or Instagram message is from", "Alerts", AlertsSettingsActivity::class.java, "rowMessageAnnounce", ""),
        Entry("Notification Triage & VIP Filter", "Filter spam, manage VIP contacts & hands-free replies", "Alerts", AlertsSettingsActivity::class.java, "rowNotificationTriage", ""),
        Entry("Announce in Jarvis Voice", "Only while Jarvis is listening, otherwise the phone voice is used", "Alerts", AlertsSettingsActivity::class.java, "rowMessageJarvisVoice", ""),
        Entry("Battery & Charging Alerts", "Speak when the charger changes, at 25%, and every 5 minutes at 100%", "Alerts", AlertsSettingsActivity::class.java, "rowChargingAlerts", ""),
        Entry("Auto Battery Saver", "Turn on Battery Saver at 25% when the phone is not charging", "Alerts", AlertsSettingsActivity::class.java, "rowAutoBatterySaver", ""),
        Entry("Neural Memory Bank", "Search, review and back up learned facts", "Memory", MemorySettingsActivity::class.java, "rowMemory", ""),
        Entry("Activity Memory", "Remember chats, actions, messages and battery events for 48 hours", "Memory", MemorySettingsActivity::class.java, "rowActivityMemory", ""),
        Entry("Clear Activity Memory", "Delete everything remembered from the last 2 days", "Memory", MemorySettingsActivity::class.java, "rowClearActivityMemory", ""),
        Entry("System Permissions", "Microphone, accessibility, notifications and system access", "Permissions", PermissionsSettingsActivity::class.java, "rowPermissions", ""),
        Entry("App Control & Hands-Free", "WhatsApp, YouTube, media and phone automation", "Permissions", PermissionsSettingsActivity::class.java, "rowApplications", ""),
        Entry("Automation Studio", "Instant voice rules and system-event automations", "Permissions", PermissionsSettingsActivity::class.java, "rowAutomationStudio", ""),
        Entry("Jarvis Terminal", "Run commands yourself. Jarvis can read, run, edit and clear everything here", "Permissions", PermissionsSettingsActivity::class.java, "rowJarvisTerminal", ""),
        Entry("Keep Jarvis listening 24/7", "\\\"Hey Jarvis\\\" stays on after the app is closed or the phone restarts", "Permissions", PermissionsSettingsActivity::class.java, "rowKeepAlive", ""),
        Entry("JARVIS Gallery", "Browse photos and videos with Android media access", "Permissions", PermissionsSettingsActivity::class.java, "rowGallery", ""),
        Entry("Native-first Action Router", "Enable routing and inspect local execution latency", "Permissions", PermissionsSettingsActivity::class.java, "rowActionRouter", ""),
        Entry("Skill Store", "Install skills and switch them on or off", "Permissions", PermissionsSettingsActivity::class.java, "rowSkillStore", ""),
        Entry("Clock", "Alarms, tones and how they behave", "Permissions", PermissionsSettingsActivity::class.java, "rowClock", ""),
        Entry("Device Diagnostics", "Battery health, storage space and network status", "Permissions", PermissionsSettingsActivity::class.java, "rowDeviceDiagnostics", ""),
        Entry("Theme & 3D Orb Colors", "Light Theme", "Appearance", AppearanceSettingsActivity::class.java, "rowAppTheme", ""),
        Entry("Transparent Background", "Daydream 4K scenes • Blur", "Appearance", AppearanceSettingsActivity::class.java, "rowCrystalBackground", ""),
        Entry("Overlay Avatars", "Choose a floating avatar or turn the overlay off", "Appearance", AppearanceSettingsActivity::class.java, "rowOverlayAvatars", ""),
        Entry("Motion & Refresh Rate", "Maximum available • system may adapt", "Appearance", AppearanceSettingsActivity::class.java, "rowRefreshRate", ""),
        Entry("Touch Glow Effect", "Show a soft theme-coloured halo around every touch", "Appearance", AppearanceSettingsActivity::class.java, "rowTouchGlow", ""),
        Entry("Liquid Glass", "On by default. Turning it off restarts the app in its original simple design; the dock bar stays the same", "Appearance", AppearanceSettingsActivity::class.java, "rowLiquidGlass", ""),
        Entry("Privacy & Data Shield", "On-device processing and encrypted connections", "About", AboutSettingsActivity::class.java, "rowPrivacyPolicy", ""),
        Entry("App Identity & Integrity", "Publisher, app ID and signing certificate status", "About", AboutSettingsActivity::class.java, "rowAppIdentity", ""),
        Entry("Dashboard", "Password-protected analytics", "Owner", OwnerSettingsActivity::class.java, "rowOwnerDashboard", ""),
        Entry("API Key & Model", "Gemini key, model and connection test", "AI & Models", ApiConnectionActivity::class.java, null, "api key gemini model groq provider connection"),
        Entry("AI Persona", "Personality and voice tone", "AI & Models", PersonaActivity::class.java, null, "persona personality tone character"),
        Entry("API Key Manager", "Store and switch your provider keys", "AI & Models", KeyManagerActivity::class.java, null, "keys manager provider token"),
        Entry("Custom AI Provider", "Add your own OpenAI-compatible provider", "AI & Models", CustomProviderEditorActivity::class.java, null, "custom provider endpoint openai"),
        Entry("Workspace & Tools", "Folders and tools Jarvis can use", "Permissions", ToolSettingsActivity::class.java, null, "workspace tools folder files"),
        Entry("Work Routing", "Decide which engine handles which job", "AI & Models", WorkRoutingActivity::class.java, null, "routing work engine"),
        Entry("Privacy & Data Shield", "On-device processing and encrypted connections", "About", PrivacyShieldActivity::class.java, null, "privacy shield data encryption"),
        Entry("Screen Unlock", "Save your PIN, password or pattern to unlock by voice", "Security", ScreenUnlockActivity::class.java, null, "unlock pin password pattern"),
        Entry("Face Lock Settings", "Guided face scan and profiles", "Security", FaceLockActivity::class.java, null, "face lock scan biometrics"),
        Entry("App Control & Hands-Free", "WhatsApp, YouTube, media and phone automation", "Permissions", HandsFreeActivity::class.java, null, "hands free whatsapp youtube media automation"),
        Entry("Transparent Background", "Daydream 4K scenes and blur", "Appearance", CrystalBackgroundActivity::class.java, null, "transparent crystal background wallpaper daydream blur"),
        Entry("More theme colours", "Pick any accent colour", "Appearance", MoreThemeColoursActivity::class.java, null, "theme colours colors accent palette"),
        Entry("Maya companion", "Secret companion settings", "Appearance", MayaModeActivity::class.java, null, "maya companion"),
        Entry("Settings background", "Animated scenes or see-through glass", "Appearance", AppearanceSettingsActivity::class.java, "rowSettingsScene", "animation animated scene wallpaper aurora nebula waves silk prism constellation mesh bokeh ripples fireflies dusk arc core reactor neural pulse quantum orbit hud cyber matrix solar corona holo helix see-through transparent 4k"),
        Entry("Voice enrollment", "Teach Jarvis your voice", "Security", VoiceEnrollmentActivity::class.java, null, "voice enroll profile owner"),
        Entry("Device network", "Parent and child devices", "Owner", UdnActivity::class.java, null, "network devices udn parent child join")
    )

    /** Entries whose text contains every word typed, best matches (title starts with the query) first. */
    fun search(query: String): List<Entry> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return entries.filter { e -> words.all { e.haystack.contains(it) } }
            .sortedBy { e ->
                val t = e.title.lowercase()
                when { t == words.joinToString(" ") -> 0; t.startsWith(words[0]) -> 1; words.all { t.contains(it) } -> 2; else -> 3 }
            }
            .distinctBy { it.title }
            .take(12)
    }
}
