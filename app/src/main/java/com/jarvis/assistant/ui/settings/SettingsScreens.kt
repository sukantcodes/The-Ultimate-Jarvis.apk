package com.jarvis.assistant.ui.settings

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.jarvis.assistant.BuildConfig
import com.jarvis.assistant.R
import com.jarvis.assistant.updates.OtaSettings
import com.jarvis.assistant.activitylog.ActivityLog
import com.jarvis.assistant.activitylog.ActivityLogSettings
import com.jarvis.assistant.audio.AutoSleepSettings
import com.jarvis.assistant.audio.NoiseCancelSettings
import com.jarvis.assistant.audio.NoiseLevel
import com.jarvis.assistant.data.model.GeminiConstants
import com.jarvis.assistant.databinding.SettingsScreenAboutBinding
import com.jarvis.assistant.databinding.SettingsScreenAiBinding
import com.jarvis.assistant.databinding.SettingsScreenAlertsBinding
import com.jarvis.assistant.databinding.SettingsScreenAppearanceBinding
import com.jarvis.assistant.databinding.SettingsScreenMemoryBinding
import com.jarvis.assistant.databinding.SettingsScreenOwnerBinding
import com.jarvis.assistant.databinding.SettingsScreenPermissionsBinding
import com.jarvis.assistant.databinding.SettingsScreenSecurityBinding
import com.jarvis.assistant.databinding.SettingsScreenVoiceBinding
import com.jarvis.assistant.notifications.MessageAlertSettings
import com.jarvis.assistant.power.AlertSettings
import com.jarvis.assistant.security.AiEngineStore
import com.jarvis.assistant.security.AppIdentityVerifier
import com.jarvis.assistant.security.VoiceModeStore
import com.jarvis.assistant.studio.ui.AutomationStudioActivity
import com.jarvis.assistant.ui.memory.MemoryActivity
import com.jarvis.assistant.ui.permission.PermissionActivity
import com.jarvis.assistant.ui.premium.PremiumDialog
import com.jarvis.assistant.ui.premium.PremiumOptionList
import com.jarvis.assistant.ui.voice.VoiceEnrollmentActivity
import com.jarvis.assistant.util.ThemeManager
import com.jarvis.assistant.util.TouchGlowController
import com.jarvis.assistant.voice.VoiceCatalog
import com.jarvis.assistant.voice.VoiceGender
import com.jarvis.assistant.voice.VoiceGenderStore

// ==============================================================================================
// 1. AI & Intelligence
// ==============================================================================================

class AiSettingsActivity : SettingsScreenActivity() {
    private lateinit var binding: SettingsScreenAiBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = SettingsScreenAiBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyChrome(binding.root, binding.glassCardAi, R.color.set_accent_ai, binding.btnBack, binding.tvScreenTitle)

        binding.rowAiEngine.setOnClickListener { showAiEngineDialog() }
        binding.rowApiConnection.setOnClickListener { open(ApiConnectionActivity::class.java) }
        binding.rowLanguage.setOnClickListener { showLanguageDialog() }
        binding.rowPersonaVoice.setOnClickListener { open(PersonaActivity::class.java) }
        bindSwitchRow(binding.rowSilentMode, binding.switchSilentMode) { checked ->
            preferences.isSilentExecutionMode = checked
            toast(if (checked) "Silent Execution Mode Enabled" else "Silent Execution Mode Disabled")
        }
    }

    override fun onResume() {
        super.onResume()
        syncingUi = true
        try {
            binding.switchSilentMode.isChecked = preferences.isSilentExecutionMode
        } finally {
            syncingUi = false
        }
        binding.tvCurrentLanguage.text = preferences.language
        binding.tvCurrentPersona.text = "${preferences.personality} • Voice: ${preferences.voice}"
        binding.tvCurrentModel.text = preferences.aiModel.removePrefix("models/") + " • Live WebSocket"
        updateAiEngineSubtitle()
    }

    private fun updateAiEngineSubtitle() {
        val store = AiEngineStore(this)
        binding.tvAiEngineSubtitle.text = if (store.useGroq) {
            "Active: Groq (" + store.groqChatModel + ")"
        } else {
            "Active: Gemini Live"
        }
    }

    private fun showLanguageDialog() {
        val languages = GeminiConstants.SUPPORTED_LANGUAGES.toTypedArray()
        val currentIndex = languages.indexOfFirst { it.equals(preferences.language, ignoreCase = true) }
            .let { if (it >= 0) it else 0 }
        PremiumDialog.choice(
            this, "VOICE & LANGUAGE", "Conversation Language",
            "Jarvis listens and replies in the language you pick.",
            languages.toList(), currentIndex, instant = true
        ) { which ->
            val chosen = languages[which]
            preferences.language = chosen
            binding.tvCurrentLanguage.text = chosen
        }
    }

    private fun showAiEngineDialog() {
        val ctx = this
        val store = AiEngineStore(ctx)
        val d = PremiumDialog.create(ctx).eyebrow("AI CORE").title("AI Engine")
            .message("Choose the brain behind Jarvis's voice.")
        val engines = PremiumOptionList(
            ctx, d.palette,
            listOf("Gemini Live (voice to voice)", "Groq (Whisper + Llama, phone voice)"),
            if (store.useGroq) 1 else 0
        ) { }
        val keyInput = PremiumDialog.styledField(
            ctx, d.palette.accent1,
            if (store.hasGroqKey()) "Groq key saved. Leave empty to keep it" else "Groq API key (starts with gsk_)",
            "", password = true
        )
        val modelInput = PremiumDialog.styledField(ctx, d.palette.accent1, "Groq chat model", store.groqChatModel, password = false)

        d.add(engines.view)
            .add(PremiumDialog.fieldLabel(ctx, "Groq API key", d.palette.accent1))
            .add(keyInput)
            .add(PremiumDialog.fieldLabel(ctx, "Groq chat model", d.palette.accent1))
            .add(modelInput)
            .negative("Cancel")
            .positive("Save") {
                val newKey = keyInput.text.toString().trim()
                if (newKey.isNotEmpty() && !store.setGroqKey(newKey)) {
                    Toast.makeText(ctx, "Could not store the Groq key securely on this phone", Toast.LENGTH_LONG).show()
                }
                val model = modelInput.text.toString().trim()
                if (model.isNotEmpty()) store.groqChatModel = model

                val wantGroq = engines.selectedIndex == 1
                if (wantGroq && !store.hasGroqKey()) {
                    store.useGroq = false
                    Toast.makeText(ctx, "Enter your Groq API key first. Staying on Gemini.", Toast.LENGTH_LONG).show()
                } else {
                    store.useGroq = wantGroq
                    Toast.makeText(ctx, "Saved. It takes effect the next time Jarvis starts listening.", Toast.LENGTH_LONG).show()
                }
                updateAiEngineSubtitle()
                true
            }
            .show()
    }
}

// ==============================================================================================
// 2. Voice & Listening
// ==============================================================================================

class VoiceSettingsActivity : SettingsScreenActivity() {
    private lateinit var binding: SettingsScreenVoiceBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = SettingsScreenVoiceBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyChrome(binding.root, binding.glassCardVoice, R.color.set_accent_voice, binding.btnBack, binding.tvScreenTitle)

        binding.rowVoiceGender.setOnClickListener { showVoiceGenderDialog() }
        binding.rowNoiseCancel.setOnClickListener { showNoiseCancelDialog() }
        binding.rowAutoSleep.setOnClickListener { showAutoSleepDialog() }
        binding.rowDefaultAssistant.setOnClickListener { requestDefaultAssistant() }
    }

    override fun onResume() {
        super.onResume()
        updateVoiceGenderSubtitle()
        updateNoiseCancelSubtitle()
        updateAutoSleepSubtitle()
    }

    private fun updateVoiceGenderSubtitle() {
        binding.tvVoiceGenderSubtitle.text = VoiceGenderStore(this).summary()
    }

    private fun updateNoiseCancelSubtitle() {
        val level = NoiseCancelSettings(this).level
        binding.tvNoiseCancelSubtitle.text = "Currently: " + level.label
    }

    private fun updateAutoSleepSubtitle() {
        val settings = AutoSleepSettings(this)
        binding.tvAutoSleepSubtitle.text = if (settings.timeoutSeconds == 0) {
            "Never goes back to standby by itself"
        } else {
            "After " + AutoSleepSettings.labelFor(settings.timeoutSeconds) +
                (if (settings.countFromWake) " from waking up" else " without conversation")
        }
    }

    private fun showVoiceGenderDialog() {
        val ctx = this
        val store = VoiceGenderStore(ctx)

        var currentGender = store.gender
        var maleChoice = store.maleVoice
        var femaleChoice = store.femaleVoice

        val d = PremiumDialog.create(ctx).eyebrow("VOICE").title("Voice & Gender")
        val genders = VoiceGender.values()
        val genderLabels = genders.map {
            if (it == VoiceGender.UNSPECIFIED) it.label + " (default)" else it.label
        }
        val voiceHolder = android.widget.FrameLayout(ctx)
        val note = TextView(ctx).apply {
            text = "Jarvis keeps using the voice you already chose in Settings."
            textSize = 12.5f
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.rgb(176, 189, 216))
        }

        fun refreshVoiceList() {
            voiceHolder.removeAllViews()
            val voices = VoiceCatalog.voicesFor(currentGender)
            if (voices.isEmpty()) {
                voiceHolder.addView(note)
                return
            }
            val chosen = if (currentGender == VoiceGender.MALE) maleChoice else femaleChoice
            val list = PremiumOptionList(
                ctx, d.palette,
                voices.map { it.name + " - " + it.style },
                voices.indexOfFirst { it.name == chosen }.coerceAtLeast(0)
            ) { index ->
                val picked = voices.getOrNull(index)?.name
                if (picked != null) {
                    if (currentGender == VoiceGender.MALE) maleChoice = picked else femaleChoice = picked
                }
            }
            voiceHolder.addView(list.view)
        }

        val genderList = PremiumOptionList(
            ctx, d.palette, genderLabels, genders.indexOf(currentGender).coerceAtLeast(0)
        ) { index ->
            currentGender = genders[index]
            refreshVoiceList()
        }
        refreshVoiceList()

        d.add(genderList.view)
            .add(PremiumDialog.spacer(ctx, 14f))
            .add(voiceHolder)
            .negative("Cancel")
            .positive("Save") {
                store.gender = currentGender
                store.maleVoice = maleChoice
                store.femaleVoice = femaleChoice
                updateVoiceGenderSubtitle()
                Toast.makeText(ctx, "Saved. It takes effect the next time Jarvis starts listening.", Toast.LENGTH_LONG).show()
                true
            }
            .show()
    }

    private fun showNoiseCancelDialog() {
        val ctx = this
        val settings = NoiseCancelSettings(ctx)
        val levels = NoiseLevel.values()
        val selected = levels.indexOf(settings.level).coerceAtLeast(0)
        PremiumDialog.choice(
            ctx, "AUDIO", "Noise Cancellation", null,
            levels.map { it.label }, selected, instant = false
        ) { index ->
            settings.level = levels[index]
            updateNoiseCancelSubtitle()
            Toast.makeText(ctx, "Saved. It takes effect the next time Jarvis wakes up.", Toast.LENGTH_LONG).show()
        }
    }

    private fun showAutoSleepDialog() {
        val ctx = this
        val settings = AutoSleepSettings(ctx)
        val choices = AutoSleepSettings.CHOICES_SECONDS
        var selected = choices.indexOf(settings.timeoutSeconds).let { if (it < 0) 1 else it }
        var fromWake = settings.countFromWake

        val d = PremiumDialog.create(ctx).eyebrow("POWER").title("Auto-Sleep")
            .message("How long Jarvis stays awake while it hears nothing.")
        val list = PremiumOptionList(
            ctx, d.palette,
            choices.map { AutoSleepSettings.labelFor(it) + (if (it == 60) " (default)" else "") },
            selected
        ) { index -> selected = index }
        val box = PremiumDialog.checkRow(
            ctx, d.palette, "Count from waking up, even if I am still talking", fromWake
        ) { fromWake = it }

        d.add(list.view)
            .add(PremiumDialog.spacer(ctx, 12f))
            .add(box)
            .negative("Cancel")
            .positive("Save") {
                settings.timeoutSeconds = choices[selected]
                settings.countFromWake = fromWake
                updateAutoSleepSubtitle()
                Toast.makeText(ctx, "Saved. Jarvis sleeps after that time, and wakes when you say Jarvis.", Toast.LENGTH_LONG).show()
                true
            }
            .show()
    }

    private fun requestDefaultAssistant() {
        try {
            // RoleManager only exists on Android 10+. Older phones use the voice input settings page.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = getSystemService(RoleManager::class.java)
                if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                    if (roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)) {
                        toast("Jarvis is already your default assistant", long = true)
                    } else {
                        startActivity(roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT))
                    }
                    return
                }
            }
            startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        } catch (e: Exception) {
            toast("Open Android Settings > Apps > Default apps > Digital assistant app and choose Jarvis.", long = true)
        }
    }
}

// ==============================================================================================
// 3. Voice Security & Unlock
// ==============================================================================================

class SecuritySettingsActivity : SettingsScreenActivity() {
    private lateinit var binding: SettingsScreenSecurityBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = SettingsScreenSecurityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyChrome(binding.root, binding.glassCardSecurity, R.color.set_accent_security, binding.btnBack, binding.tvScreenTitle)

        binding.rowOwnerVoice.setOnClickListener { open(VoiceEnrollmentActivity::class.java) }
        binding.rowReplayTutorial.setOnClickListener {
            startActivity(com.jarvis.assistant.ui.onboarding.OnboardingActivity.intent(this, replay = true))
        }
        bindSwitchRow(binding.rowVoiceMode, binding.switchOwnerOnly) { checked ->
            VoiceModeStore(this).ownerOnly = checked
            val message = if (checked) {
                if (preferences.hasOwnerVoiceProfile()) {
                    "Owner Voice Only: other voices are ignored"
                } else {
                    "Owner Voice Only is on, but you still need to enroll your voice"
                }
            } else {
                "Everyone mode: Jarvis answers every voice it hears"
            }
            toast(message, long = true)
        }
        binding.rowScreenUnlock.setOnClickListener { open(ScreenUnlockActivity::class.java) }
        binding.rowFaceLockSettings.setOnClickListener { open(FaceLockActivity::class.java) }
    }

    override fun onResume() {
        super.onResume()
        syncingUi = true
        try {
            binding.switchOwnerOnly.isChecked = VoiceModeStore(this).ownerOnly
        } finally {
            syncingUi = false
        }
        val green = ContextCompat.getColor(this, R.color.status_green)
        val sapphire = ContextCompat.getColor(this, R.color.accent_sapphire)
        if (preferences.hasOwnerVoiceProfile()) {
            binding.tvOwnerVoiceBadge.text = "ACTIVE"
            binding.tvOwnerVoiceBadge.setTextColor(green)
            binding.tvOwnerVoiceBadge.setBackgroundResource(R.drawable.bg_jarvis_status_badge_green)
            binding.tvOwnerVoiceSubtitle.text = "Voice Profile Active • Only you can speak"
        } else {
            binding.tvOwnerVoiceBadge.text = "SETUP"
            binding.tvOwnerVoiceBadge.setTextColor(sapphire)
            binding.tvOwnerVoiceBadge.setBackgroundResource(R.drawable.bg_jarvis_status_badge)
            binding.tvOwnerVoiceSubtitle.text = "Not enrolled • Tap to record voice profile"
        }
    }
}

// ==============================================================================================
// 4. Alerts & Announcements
// ==============================================================================================

class AlertsSettingsActivity : SettingsScreenActivity() {
    private lateinit var binding: SettingsScreenAlertsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = SettingsScreenAlertsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyChrome(binding.root, binding.glassCardAlerts, R.color.set_accent_alerts, binding.btnBack, binding.tvScreenTitle)

        binding.rowNotificationAccess.setOnClickListener { openNotificationAccessSettings() }
        bindSwitchRow(binding.rowMessageAnnounce, binding.switchMessageAnnounce) { checked ->
            MessageAlertSettings(this).enabled = checked
        }
        binding.rowNotificationTriage.setOnClickListener {
            open(com.jarvis.assistant.ui.notifications.NotificationTriageActivity::class.java)
        }
        bindSwitchRow(binding.rowMessageJarvisVoice, binding.switchMessageJarvisVoice) { checked ->
            MessageAlertSettings(this).useJarvisVoice = checked
        }
        bindSwitchRow(binding.rowChargingAlerts, binding.switchChargingAlerts) { checked ->
            AlertSettings(this).chargingAlerts = checked
        }
        bindSwitchRow(binding.rowAutoBatterySaver, binding.switchAutoBatterySaver) { checked ->
            AlertSettings(this).autoBatterySaver = checked
        }
    }

    override fun onResume() {
        super.onResume()
        val messageSettings = MessageAlertSettings(this)
        val alertSettings = AlertSettings(this)
        syncingUi = true
        try {
            binding.switchMessageAnnounce.isChecked = messageSettings.enabled
            binding.switchMessageJarvisVoice.isChecked = messageSettings.useJarvisVoice
            binding.switchChargingAlerts.isChecked = alertSettings.chargingAlerts
            binding.switchAutoBatterySaver.isChecked = alertSettings.autoBatterySaver
        } finally {
            syncingUi = false
        }
        updateNotificationAccessSubtitle()
    }

    private fun updateNotificationAccessSubtitle() {
        val granted = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        val status = MessageAlertSettings(this).lastStatus
        val base = if (granted) {
            "Allowed. Tap to change."
        } else {
            "Not allowed yet. Tap to let Jarvis see new message notifications."
        }
        binding.tvNotificationAccessSubtitle.text = if (granted && status.isNotBlank()) base + " Last: " + status else base
    }

    private fun openNotificationAccessSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (e: Exception) {
            toast("Open Android Settings > Notifications > Notification access and allow Jarvis.", long = true)
        }
    }
}

// ==============================================================================================
// 5. Memory & Data
// ==============================================================================================

class MemorySettingsActivity : SettingsScreenActivity() {
    private lateinit var binding: SettingsScreenMemoryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = SettingsScreenMemoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyChrome(binding.root, binding.glassCardMemory, R.color.set_accent_memory, binding.btnBack, binding.tvScreenTitle)

        binding.rowMemory.setOnClickListener { open(MemoryActivity::class.java) }
        bindSwitchRow(binding.rowActivityMemory, binding.switchActivityMemory) { checked ->
            ActivityLogSettings(this).enabled = checked
        }
        binding.rowClearActivityMemory.setOnClickListener {
            PremiumDialog.confirm(
                this, "MEMORY", "Clear activity memory?",
                "Jarvis will forget everything from the last 2 days. This cannot be undone.",
                "Clear", destructive = true
            ) {
                ActivityLog.get(this).clear()
                toast("Activity memory cleared")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        syncingUi = true
        try {
            binding.switchActivityMemory.isChecked = ActivityLogSettings(this).enabled
        } finally {
            syncingUi = false
        }
    }
}

// ==============================================================================================
// 6. Permissions & Automation
// ==============================================================================================

class PermissionsSettingsActivity : SettingsScreenActivity() {
    private lateinit var binding: SettingsScreenPermissionsBinding

    /**
     * Calling and SIM choice need contacts and phone permissions. They are asked for when the user
     * opens System Permissions, then the permissions screen follows.
     */
    private val callPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { openPermissionsScreen() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = SettingsScreenPermissionsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyChrome(binding.root, binding.glassCardPermissions, R.color.set_accent_permissions, binding.btnBack, binding.tvScreenTitle)

        binding.rowPermissions.setOnClickListener {
            val missing = missingCallPermissions()
            if (missing.isEmpty()) openPermissionsScreen() else callPermissionLauncher.launch(missing)
        }
        binding.rowApplications.setOnClickListener { open(HandsFreeActivity::class.java) }
        binding.rowAutomationStudio.setOnClickListener { open(AutomationStudioActivity::class.java) }
        binding.rowKeepAlive.setOnClickListener { com.jarvis.assistant.util.KeepAliveHelper.showDialog(this) }
        binding.rowJarvisTerminal.setOnClickListener { open(com.jarvis.assistant.ui.terminal.JarvisTerminalActivity::class.java) }
        binding.rowActionRouter.setOnClickListener { open(com.jarvis.assistant.ui.debug.ActionRouterActivity::class.java) }
        binding.rowGallery.setOnClickListener { open(com.jarvis.assistant.gallery.GalleryActivity::class.java) }
        binding.rowSkillStore.setOnClickListener { open(com.jarvis.assistant.ui.skills.SkillStoreActivity::class.java) }
        binding.rowClock.setOnClickListener { open(com.jarvis.assistant.ui.clock.ClockSettingsActivity::class.java) }
        binding.rowDeviceDiagnostics.setOnClickListener { open(com.jarvis.assistant.ui.diagnostics.DeviceDiagnosticsActivity::class.java) }
    }

    private fun missingCallPermissions(): Array<String> {
        val wanted = listOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_PHONE_NUMBERS
        )
        return wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
    }

    private fun openPermissionsScreen() {
        if (!isFinishing) open(PermissionActivity::class.java)
    }
}

// ==============================================================================================
// 7. Appearance
// ==============================================================================================

class AppearanceSettingsActivity : SettingsScreenActivity() {
    private lateinit var binding: SettingsScreenAppearanceBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = SettingsScreenAppearanceBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyChrome(binding.root, binding.glassCardAppearance, R.color.set_accent_appearance, binding.btnBack, binding.tvScreenTitle)

        binding.rowAppTheme.setOnClickListener {
            ThemeSelectionBottomSheet.newInstance().show(supportFragmentManager, ThemeSelectionBottomSheet.TAG)
        }
        binding.rowOverlayAvatars.setOnClickListener { open(OverlayAvatarActivity::class.java) }
        binding.rowCrystalBackground.setOnClickListener { open(CrystalBackgroundActivity::class.java) }
        binding.rowRefreshRate.setOnClickListener { showRefreshRateDialog() }

        binding.switchLiquidGlass.isChecked = preferences.liquidGlassEnabled
        binding.switchLiquidGlass.setOnCheckedChangeListener { _, enabled ->
            if (enabled == preferences.liquidGlassEnabled) return@setOnCheckedChangeListener
            toast(getString(R.string.set_liquidglass_restarting))
            // Save the choice and restart so every screen is rebuilt in the chosen look (the dock bar never changes).
            binding.root.postDelayed({
                if (!isFinishing) com.jarvis.assistant.ui.glass.GlassMode.setEnabledAndRestart(this, enabled)
            }, 250L)
        }
        binding.rowLiquidGlass.setOnClickListener { binding.switchLiquidGlass.toggle() }

        binding.switchTouchGlow.isChecked = preferences.touchGlowEnabled
        binding.switchTouchGlow.setOnCheckedChangeListener { _, enabled ->
            preferences.touchGlowEnabled = enabled
            TouchGlowController.setEnabled(this, enabled)
        }
    }

    override fun onResume() {
        super.onResume()
        repaintIcons(binding.root)
        binding.tvCurrentTheme.text = when (preferences.appTheme) {
            "dark" -> "Dark Theme"
            "red" -> "Red Theme"
            "sunset" -> "Sunset Theme"
            "crystal" -> "Crystal Mirror Theme"
            "custom" -> "Custom (${preferences.customThemeColor})"
            else -> "Light Theme"
        }
        binding.tvCurrentCrystalBackground.text = com.jarvis.assistant.ui.glass.CrystalBackdrop.summary()
        updateRefreshRateSubtitle()
    }

    /** Called by the theme sheet after the icon shape changes. */
    fun applyIconShape() {
        repaintIcons(binding.root)
    }

    private fun showRefreshRateDialog() {
        val rates = ThemeManager.supportedRefreshRates(this)
        val labels = mutableListOf("Maximum available (recommended)")
        val values = mutableListOf("system")
        rates.forEach { rate ->
            labels += "$rate Hz"
            values += rate.toString()
        }
        val selected = values.indexOf(preferences.uiRefreshRate).coerceAtLeast(0)

        PremiumDialog.choice(
            this, "DISPLAY", "Refresh rate",
            "Maximum uses the highest rate your display supports (120 Hz on 120 Hz screens). Android and ColorOS may still lower it to manage battery or heat, for example in battery saver mode.",
            labels, selected, instant = true
        ) { which ->
            preferences.uiRefreshRate = values[which]
            ThemeManager.applyPreferredRefreshRate(this)
            updateRefreshRateSubtitle()
        }
    }

    private fun updateRefreshRateSubtitle() {
        val requested = preferences.uiRefreshRate.toIntOrNull()
        binding.tvRefreshRateSubtitle.text = when {
            requested == null -> "Maximum available • system may adapt"
            requested in ThemeManager.supportedRefreshRates(this) ->
                "$requested Hz preferred • system may adapt"
            else -> "$requested Hz is not supported by this display"
        }
    }
}

// ==============================================================================================
// 8. Privacy & About
// ==============================================================================================

class AboutSettingsActivity : SettingsScreenActivity() {
    private lateinit var binding: SettingsScreenAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = SettingsScreenAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyChrome(binding.root, binding.glassCardAbout, R.color.set_accent_about, binding.btnBack, binding.tvScreenTitle)

        binding.rowPrivacyPolicy.setOnClickListener { open(PrivacyShieldActivity::class.java) }
        binding.rowSoftwareUpdates.setOnClickListener { open(SoftwareUpdatesActivity::class.java) }
        binding.tvSoftwareUpdatesSubtitle.text = getString(
            R.string.set_softwareupdates_version, BuildConfig.VERSION_NAME, OtaSettings(this).branch
        )
        binding.rowAppIdentity.setOnClickListener {
            PremiumDialog.info(
                this, "SECURITY", "JARVIS App Identity",
                AppIdentityVerifier.report(this)
            )
        }
        binding.btnSocialTelegram.setOnClickListener { openUrl("https://t.me/sukantcodesbycreator") }
        binding.btnSocialInstagram.setOnClickListener {
            openUrl("https://www.instagram.com/___itz__sukanta__?vrfl=bzg3djZ5MDh3NmY2")
        }
        binding.btnSocialYoutube.setOnClickListener { openUrl("https://www.youtube.com/@Sukant_Codes") }
        binding.tvFooterCredit.setOnClickListener { openUrl("https://t.me/sukantcodesbycreator") }
        binding.tvFooterCredit.text = getString(
            R.string.set_footer_credit,
            BuildConfig.VERSION_NAME,
            BuildConfig.DEVELOPER_NAME
        )
    }
}

// ==============================================================================================
// 9. Owner (Dashboard is gated by a server-verified password)
// ==============================================================================================

class OwnerSettingsActivity : SettingsScreenActivity() {
    private lateinit var binding: SettingsScreenOwnerBinding

    private val requireAccountForOwner =
        registerForActivityResult(com.jarvis.assistant.ui.auth.RequireAccountContract()) { signedIn ->
            if (signedIn) open(com.jarvis.assistant.ui.owner.OwnerPasswordActivity::class.java)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = SettingsScreenOwnerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyChrome(binding.root, binding.glassCardOwner, R.color.set_accent_owner, binding.btnBack, binding.tvScreenTitle)

        binding.rowOwnerDashboard.setOnClickListener {
            if (com.jarvis.assistant.auth.AuthManager.isSignedIn) {
                open(com.jarvis.assistant.ui.owner.OwnerPasswordActivity::class.java)
            } else {
                requireAccountForOwner.launch(Unit)
            }
        }
    }
}
