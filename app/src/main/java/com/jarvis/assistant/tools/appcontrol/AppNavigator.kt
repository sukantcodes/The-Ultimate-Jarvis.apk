package com.jarvis.assistant.tools.appcontrol

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.jarvis.assistant.util.ActivityLauncherHelper
import java.util.Locale

/** A screen Jarvis can open directly. [className] is the screen's class name, as declared in the manifest. */
data class AppDestination(
    val id: String,
    val label: String,
    val section: String,
    val description: String,
    val className: String,
    /** Boolean extras the screen reads to open on the right page. */
    val boolExtras: Map<String, Boolean> = emptyMap(),
    /** Name of the one string extra this screen accepts, or null when it takes none. */
    val argumentExtra: String? = null,
    val argumentHelp: String = ""
)

/**
 * The map of the app. Opening a screen is a plain in-app jump, so no Android permission is involved. Screens that
 * are protected (owner dashboard and password) or need data Jarvis does not have (an alarm that is ringing) are
 * deliberately not listed.
 */
object AppNavigator {
    private const val P = "com.jarvis.assistant."

    val destinations: List<AppDestination> = listOf(
        AppDestination("home", "Home (orb) and ChatBox", "Main", "The main screen with the orb, the ChatBox and the navigation drawer.", P + "ui.home.MainActivity"),
        AppDestination("updates", "App updates", "Main", "The updates page of the main screen.", P + "ui.home.MainActivity", mapOf("com.jarvis.assistant.OPEN_UPDATES" to true)),
        AppDestination("settings", "Settings", "Settings", "The full Settings screen.", P + "ui.settings.SettingsActivity"),
        AppDestination("api_connection", "API connection", "Settings", "Custom AI providers: add, edit, test and switch the API.", P + "ui.settings.ApiConnectionActivity"),
        AppDestination("provider_editor", "Provider editor", "Settings", "Edit one provider's address, models and options. Give a provider id, or none to add a new provider.", P + "ui.settings.CustomProviderEditorActivity", argumentExtra = "provider_id", argumentHelp = "provider id from api_settings_read (leave out for a new provider)"),
        AppDestination("key_manager", "API key manager", "Settings", "The saved API keys and key rotation.", P + "ui.settings.KeyManagerActivity"),
        AppDestination("work_routing", "Work routing", "Settings", "Which provider and model handles which kind of work.", P + "ui.settings.WorkRoutingActivity"),
        AppDestination("tool_settings", "Tool and permission settings", "Settings", "What Jarvis tools may do and when they ask.", P + "ui.settings.ToolSettingsActivity"),
        AppDestination("persona", "Persona", "Settings", "Name card and personality.", P + "ui.settings.PersonaActivity"),
        AppDestination("hands_free", "Hands-free", "Settings", "Wake word and hands-free options.", P + "ui.settings.HandsFreeActivity"),
        AppDestination("privacy_shield", "Privacy shield", "Settings", "Privacy options.", P + "ui.settings.PrivacyShieldActivity"),
        AppDestination("face_lock", "Face lock", "Settings", "Face lock setup.", P + "ui.settings.FaceLockActivity"),
        AppDestination("screen_unlock", "Screen unlock", "Settings", "Screen-lock credential and voice unlock.", P + "ui.settings.ScreenUnlockActivity"),
        AppDestination("phone_permission", "Phone permissions", "Settings", "Phone permission status and requests.", P + "ui.settings.PhonePermissionActivity"),
        AppDestination("maya_mode", "Maya mode", "Settings", "Maya mode options.", P + "ui.settings.MayaModeActivity"),
        AppDestination("overlay_avatar", "Task overlay avatar", "Settings", "The floating avatar shown while Jarvis works.", P + "ui.settings.OverlayAvatarActivity"),
        AppDestination("theme_colours", "More theme colours", "Settings", "Custom theme colours.", P + "ui.settings.MoreThemeColoursActivity"),
        AppDestination("clock_settings", "Clock settings", "Clock", "Entry point for the clock settings.", P + "ui.clock.ClockSettingsActivity"),
        AppDestination("alarms", "Alarms", "Clock", "The alarm list.", P + "ui.clock.AlarmsActivity"),
        AppDestination("alarm_tones", "Alarm tones", "Clock", "Choose alarm tones.", P + "ui.clock.AlarmTonesActivity"),
        AppDestination("clock_display", "Clock display", "Clock", "Clock display settings.", P + "ui.clock.ClockDisplayActivity"),
        AppDestination("alarm_behaviour", "Alarm behaviour", "Clock", "Snooze, volume and ring behaviour.", P + "ui.clock.AlarmBehaviourActivity"),
        AppDestination("skill_store", "Skill store", "Features", "Installed skills and the skill store.", P + "ui.skills.SkillStoreActivity"),
        AppDestination("study_board", "Study Board", "Features", "AI classroom whiteboard teacher for math, science, code and PDFs.", P + "study.ui.StudyBoardActivity"),
        AppDestination("memory", "Memory", "Features", "What Jarvis remembers.", P + "ui.memory.MemoryActivity"),
        AppDestination("terminal", "Terminal", "Features", "The Jarvis terminal.", P + "ui.terminal.JarvisTerminalActivity"),
        AppDestination("automation_studio", "Automation studio", "Features", "Build and run automations.", P + "studio.ui.AutomationStudioActivity"),
        AppDestination("gallery", "Gallery", "Features", "Jarvis gallery.", P + "gallery.GalleryActivity"),
        AppDestination("notification_triage", "Notification triage", "Features", "Message and notification handling.", P + "ui.notifications.NotificationTriageActivity"),
        AppDestination("voice_enrollment", "Voice enrollment", "Features", "Teach Jarvis your voice.", P + "ui.voice.VoiceEnrollmentActivity"),
        AppDestination("device_diagnostics", "Device diagnostics", "Features", "Hardware and device checks.", P + "ui.diagnostics.DeviceDiagnosticsActivity"),
        AppDestination("cleanup", "Cleanup", "Features", "Storage cleanup.", P + "ui.diagnostics.CleanupFeatureActivity"),
        AppDestination("permissions", "Permission setup", "Features", "Android permissions Jarvis needs.", P + "ui.permission.PermissionActivity")
    )

    private val byId: Map<String, AppDestination> = destinations.associateBy { it.id }

    fun find(id: String): AppDestination? = byId[id.trim().lowercase(Locale.ROOT).replace('-', '_').replace(' ', '_')]

    sealed class Result {
        data class Opened(val destination: AppDestination) : Result()
        data class Failed(val reason: String) : Result()
    }

    fun open(context: Context, id: String, argument: String? = null): Result {
        val dest = find(id) ?: return Result.Failed("There is no screen called \"$id\". Use app_overview to see the screen ids.")
        val cls = try { Class.forName(dest.className) } catch (e: ClassNotFoundException) {
            return Result.Failed("The ${dest.label} screen is not part of this build.")
        }
        if (!Activity::class.java.isAssignableFrom(cls)) return Result.Failed("${dest.label} is not a screen.")
        val intent = Intent(context.applicationContext, cls)
        dest.boolExtras.forEach { (k, v) -> intent.putExtra(k, v) }
        val arg = argument?.trim()?.takeIf { it.isNotEmpty() }
        if (arg != null) {
            val extra = dest.argumentExtra ?: return Result.Failed("${dest.label} does not take an argument.")
            intent.putExtra(extra, arg)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (ActivityLauncherHelper.startActivitySafely(context.applicationContext, intent)) Result.Opened(dest)
        else Result.Failed("Android did not open ${dest.label}. Bring Jarvis to the front and try again.")
    }
}
