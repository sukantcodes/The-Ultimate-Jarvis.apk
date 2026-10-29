package com.jarvis.assistant.ui.settings

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.BuildConfig
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.activitylog.ActivityLog
import com.jarvis.assistant.activitylog.ActivityLogSettings
import com.jarvis.assistant.audio.AutoSleepSettings
import com.jarvis.assistant.audio.NoiseCancelSettings
import com.jarvis.assistant.audio.NoiseLevel
import com.jarvis.assistant.data.model.GeminiConstants
import com.jarvis.assistant.databinding.FragmentSettingsBinding
import com.jarvis.assistant.notifications.MessageAlertSettings
import com.jarvis.assistant.power.AlertSettings
import com.jarvis.assistant.security.AiEngineStore
import com.jarvis.assistant.security.AppIdentityVerifier
import com.jarvis.assistant.security.VoiceModeStore
import com.jarvis.assistant.studio.ui.AutomationStudioActivity
import com.jarvis.assistant.ui.memory.MemoryActivity
import com.jarvis.assistant.ui.permission.PermissionActivity
import com.jarvis.assistant.ui.voice.VoiceEnrollmentActivity
import com.jarvis.assistant.util.ThemeManager
import com.jarvis.assistant.voice.VoiceCatalog
import com.jarvis.assistant.voice.VoiceGender
import com.jarvis.assistant.voice.VoiceGenderStore

/**
 * The Settings screen. Rows are grouped into eight sections, in the same order as the layout
 * (fragment_settings.xml):
 *
 *  1. AI & Intelligence          5. Memory & Data
 *  2. Voice & Listening          6. Permissions & Automation
 *  3. Voice Security & Unlock    7. Appearance
 *  4. Alerts & Announcements     8. Privacy & About
 *
 * Each section has one `setup...Section()` function that wires its rows, and the values shown on
 * the rows are refreshed together in [refreshState].
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val preferences by lazy { JarvisApp.instance.preferences }

    /** True while switches are being set from stored values, so their listeners do not fire. */
    private var syncingUi = false

    /**
     * Calling and SIM choice need contacts and phone permissions. They are asked for when the user
     * opens System Permissions (not every time Settings opens), then the permissions screen follows.
     */
    private val callPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { openPermissionsScreen() }

    private val profilePhotoPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null && isAdded) {
            try {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Some document providers grant a temporary read permission only.
            }
            preferences.profilePhotoUri = uri.toString()
            showProfilePhoto()
            toast("Profile photo saved")
        }
    }

    // ------------------------------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------------------------------

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ThemeManager.applyDynamicBackground(binding.root)

        // Secret Maya companion settings: long-press the Settings heading to open.
        binding.tvSettingsTitle.setOnLongClickListener {
            startActivity(Intent(requireContext(), MayaModeActivity::class.java))
            true
        }

        markSectionHeadings()
        setupProfileCard()
        setupAiSection()
        setupVoiceSection()
        setupSecuritySection()
        setupAlertsSection()
        setupMemorySection()
        setupPermissionsSection()
        setupAppearanceSection()
        setupAboutSection()
        applyLiquidGlassStrength(binding.root)
        applyDimensionalIconColors(binding.root)

        refreshState()
    }

    /**
     * Tint the existing settings icons with the selected theme colour. The icon containers
     * remain transparent, so opaque coloured squares do not appear behind them.
     */
    private fun applyDimensionalIconColors(root: View) {
        val accent = ThemeManager.getThemeAccentColor()

        fun visit(view: View) {
            if (view !is ViewGroup) return
            if (view.childCount > 0 && view.getChildAt(0) is android.widget.FrameLayout) {
                val iconBox = view.getChildAt(0) as android.widget.FrameLayout
                val width = iconBox.layoutParams?.width ?: 0
                val height = iconBox.layoutParams?.height ?: 0
                val isSettingsIconSize = width in dp(36)..dp(44) && height in dp(36)..dp(44)
                if (isSettingsIconSize && iconBox.childCount > 0 &&
                    iconBox.getChildAt(0) is android.widget.ImageView
                ) {
                    iconBox.background = null
                    iconBox.backgroundTintList = null
                    iconBox.elevation = 0f
                    iconBox.translationZ = 0f
                    iconBox.clipToOutline = false
                    applyIconTile(iconBox, accent)
                    (iconBox.getChildAt(0) as android.widget.ImageView).apply {
                        imageTintList = ColorStateList.valueOf(accent)
                        elevation = dp(2).toFloat()
                    }
                }
            }
            for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }

        visit(root)
    }

    /** Repaints every Settings icon with the shape chosen in Theme & 3D Orb Colors. */
    fun applyIconShape() {
        if (_binding != null) applyDimensionalIconColors(binding.root)
    }

    /**
     * The tile behind one icon. Plain keeps the original look (no tile); Rectangular is a flat square with
     * small corners; Pillowy is a soft, raised squircle; Circular is a round badge.
     */
    private fun applyIconTile(box: android.widget.FrameLayout, accent: Int) {
        val shape = JarvisApp.instance.preferences.settingsIconShape
        if (shape == 0) return
        val tile = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 70),
                androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 22)
            )
        )
        when (shape) {
            1 -> { tile.shape = GradientDrawable.RECTANGLE; tile.cornerRadius = dp(4).toFloat() }
            2 -> { tile.shape = GradientDrawable.RECTANGLE; tile.cornerRadius = dp(15).toFloat() }
            else -> tile.shape = GradientDrawable.OVAL
        }
        tile.setStroke(dp(1), androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 90))
        box.background = tile
        box.elevation = if (shape == 2) dp(3).toFloat() else 0f
    }

    private fun applyLiquidGlassStrength(view: View) {
        if (view.tag == "liquid_glass_surface") {
            view.background?.alpha = ThemeManager.getLiquidGlassAlpha()
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                applyLiquidGlassStrength(view.getChildAt(index))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            ThemeManager.applyDynamicBackground(binding.root)
            applyDimensionalIconColors(binding.root)
            refreshState()
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (_binding == null) return
        if (!hidden) {
            binding.root.visibility = View.VISIBLE
            ThemeManager.applyDynamicBackground(binding.root)
            refreshState()
        } else {
            binding.root.visibility = View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /** Lets screen readers jump between sections by heading. */
    private fun markSectionHeadings() {
        listOf(
            binding.headerAiIntelligence,
            binding.headerVoiceListening,
            binding.headerVoiceSecurity,
            binding.headerAlerts,
            binding.headerMemoryData,
            binding.headerPermissionsAutomation,
            binding.headerAppearance,
            binding.headerPrivacyAbout
        ).forEach { ViewCompat.setAccessibilityHeading(it, true) }
    }

    // ------------------------------------------------------------------------------------------
    // Profile card
    // ------------------------------------------------------------------------------------------

    private fun setupProfileCard() {
        binding.cardProfile.setOnClickListener { showEditNameDialog() }
        binding.ivProfileAvatar.setOnClickListener {
            profilePhotoPicker.launch(arrayOf("image/*"))
        }
        binding.ivProfileAvatar.setOnLongClickListener {
            preferences.profilePhotoUri = ""
            showProfilePhoto()
            toast("Profile photo removed")
            true
        }
        binding.chipProfileVoiceStatus.setOnClickListener { open(VoiceEnrollmentActivity::class.java) }
    }

    private fun showProfilePhoto() {
        val avatar = binding.ivProfileAvatar
        val savedUri = preferences.profilePhotoUri
        if (savedUri.isBlank()) {
            avatar.setImageResource(R.drawable.ic_user_profile)
            avatar.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.accent_sapphire)
            )
            avatar.scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            avatar.setPadding(dp(12), dp(12), dp(12), dp(12))
            return
        }
        try {
            avatar.setImageURI(Uri.parse(savedUri))
            avatar.imageTintList = null
            avatar.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            avatar.setPadding(0, 0, 0, 0)
        } catch (_: Exception) {
            preferences.profilePhotoUri = ""
            avatar.setImageResource(R.drawable.ic_user_profile)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------------------------------
    // 1. AI & Intelligence
    // ------------------------------------------------------------------------------------------

    private fun setupAiSection() {
        binding.rowAiEngine.setOnClickListener { showAiEngineDialog() }
        binding.rowApiConnection.setOnClickListener { open(ApiConnectionActivity::class.java) }
        binding.rowLanguage.setOnClickListener { showLanguageDialog() }
        binding.rowPersonaVoice.setOnClickListener { open(PersonaActivity::class.java) }

        bindSwitchRow(binding.rowSilentMode, binding.switchSilentMode) { checked ->
            preferences.isSilentExecutionMode = checked
            toast(if (checked) "Silent Execution Mode Enabled" else "Silent Execution Mode Disabled")
        }
    }

    // ------------------------------------------------------------------------------------------
    // 2. Voice & Listening
    // ------------------------------------------------------------------------------------------

    private fun setupVoiceSection() {
        binding.rowVoiceGender.setOnClickListener { showVoiceGenderDialog() }
        binding.rowPocketTts.setOnClickListener { open(PocketTtsSetupActivity::class.java) }
        binding.rowNoiseCancel.setOnClickListener { showNoiseCancelDialog() }
        binding.rowAutoSleep.setOnClickListener { showAutoSleepDialog() }
        binding.rowDefaultAssistant.setOnClickListener { requestDefaultAssistant() }
    }

    /**
     * Prefer opening Pocket TTS itself. JARVIS also selects this engine directly for synthesis
     * when the engine package is installed; the Android panel is only the setup fallback.
     */
    private fun openPocketTtsSettings() {
        val context = requireContext()
        val enginePackage = "org.pockettts.android.engine"
        val launchIntent = try {
            context.packageManager.getLaunchIntentForPackage(enginePackage)
        } catch (_: Exception) {
            null
        }
        if (launchIntent != null) {
            startActivity(launchIntent)
            Toast.makeText(context, "Pocket TTS opened. Import a supported voice pack there; JARVIS will request this engine directly when speaking offline.", Toast.LENGTH_LONG).show()
            return
        }

        // Pocket TTS is a separate Android system engine and its language weights are
        // distributed as model packs. Direct users to the upstream release page rather than
        // falsely implying that opening Android settings installs the runtime.
        val releaseIntent = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("https://github.com/The-unknown-Shadowman/PocketTTS-Android-Engine/releases/latest")
        )
        try {
            startActivity(releaseIntent)
            Toast.makeText(context, "Install Pocket TTS Android Engine from its official releases, import a language pack, then reopen JARVIS. Until then, JARVIS uses the device's available TTS voice.", Toast.LENGTH_LONG).show()
        } catch (error: android.content.ActivityNotFoundException) {
            val settingsIntent = android.content.Intent("com.android.settings.TTS_SETTINGS")
            try {
                startActivity(settingsIntent)
                Toast.makeText(context, "Pocket TTS is not installed. Android speech settings cannot install it.", Toast.LENGTH_LONG).show()
            } catch (_: android.content.ActivityNotFoundException) {
                Toast.makeText(context, "Pocket TTS is not installed and no browser or speech settings app is available.", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // 3. Voice Security & Unlock
    // ------------------------------------------------------------------------------------------

    private fun setupSecuritySection() {
        binding.rowOwnerVoice.setOnClickListener { open(VoiceEnrollmentActivity::class.java) }

        bindSwitchRow(binding.rowVoiceMode, binding.switchOwnerOnly) { checked ->
            VoiceModeStore(requireContext()).ownerOnly = checked
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

    // ------------------------------------------------------------------------------------------
    // 4. Alerts & Announcements
    // ------------------------------------------------------------------------------------------

    private fun setupAlertsSection() {
        binding.rowNotificationAccess.setOnClickListener { openNotificationAccessSettings() }

        bindSwitchRow(binding.rowMessageAnnounce, binding.switchMessageAnnounce) { checked ->
            MessageAlertSettings(requireContext()).enabled = checked
        }
        bindSwitchRow(binding.rowMessageJarvisVoice, binding.switchMessageJarvisVoice) { checked ->
            MessageAlertSettings(requireContext()).useJarvisVoice = checked
        }
        bindSwitchRow(binding.rowChargingAlerts, binding.switchChargingAlerts) { checked ->
            AlertSettings(requireContext()).chargingAlerts = checked
        }
        bindSwitchRow(binding.rowAutoBatterySaver, binding.switchAutoBatterySaver) { checked ->
            AlertSettings(requireContext()).autoBatterySaver = checked
        }
    }

    // ------------------------------------------------------------------------------------------
    // 5. Memory & Data
    // ------------------------------------------------------------------------------------------

    private fun setupMemorySection() {
        binding.rowMemory.setOnClickListener { open(MemoryActivity::class.java) }

        bindSwitchRow(binding.rowActivityMemory, binding.switchActivityMemory) { checked ->
            ActivityLogSettings(requireContext()).enabled = checked
        }

        binding.rowClearActivityMemory.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Clear activity memory?")
                .setMessage("Jarvis will forget everything from the last 2 days. This cannot be undone.")
                .setPositiveButton("Clear") { _, _ ->
                    ActivityLog.get(requireContext()).clear()
                    toast("Activity memory cleared")
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    // ------------------------------------------------------------------------------------------
    // 6. Permissions & Automation
    // ------------------------------------------------------------------------------------------

    private fun setupPermissionsSection() {
        binding.rowPermissions.setOnClickListener {
            val missing = missingCallPermissions()
            if (missing.isEmpty()) openPermissionsScreen() else callPermissionLauncher.launch(missing)
        }
        binding.rowApplications.setOnClickListener { open(HandsFreeActivity::class.java) }
        binding.rowAutomationStudio.setOnClickListener { open(AutomationStudioActivity::class.java) }
        binding.rowSkillStore.setOnClickListener { open(com.jarvis.assistant.ui.skills.SkillStoreActivity::class.java) }
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
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
    }

    private fun openPermissionsScreen() {
        if (isAdded) open(PermissionActivity::class.java)
    }

    // ------------------------------------------------------------------------------------------
    // 7. Appearance
    // ------------------------------------------------------------------------------------------

    private fun setupAppearanceSection() {
        binding.rowAppTheme.setOnClickListener {
            ThemeSelectionBottomSheet.newInstance().show(childFragmentManager, ThemeSelectionBottomSheet.TAG)
        }
        binding.rowOverlayAvatars.setOnClickListener { open(OverlayAvatarActivity::class.java) }


    }

    // ------------------------------------------------------------------------------------------
    // 8. Privacy & About
    // ------------------------------------------------------------------------------------------

    private fun setupAboutSection() {
        binding.rowPrivacyPolicy.setOnClickListener { open(PrivacyShieldActivity::class.java) }

        binding.rowAppIdentity.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("JARVIS App Identity")
                .setMessage(AppIdentityVerifier.report(requireContext()))
                .setPositiveButton("Close", null)
                .show()
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

    // ------------------------------------------------------------------------------------------
    // Showing current values on the rows
    // ------------------------------------------------------------------------------------------

    /** Refreshes every value, subtitle and switch from stored settings. Safe to call repeatedly. */
    private fun refreshState() {
        loadUserData()
        refreshSwitchStates()
        updateAiEngineSubtitle()
        updateVoiceGenderSubtitle()
        updateNoiseCancelSubtitle()
        updateAutoSleepSubtitle()
        updateNotificationAccessSubtitle()
    }

    private fun loadUserData() {
        val ctx = requireContext()
        val green = ContextCompat.getColor(ctx, R.color.status_green)
        val sapphire = ContextCompat.getColor(ctx, R.color.accent_sapphire)

        binding.tvProfileName.text = preferences.userName.ifBlank { "Sukant" }
        showProfilePhoto()
        binding.tvProfileSubtitle.text = "JARVIS OPERATOR • PRIMARY USER"

        // Owner voice profile status (profile card chip and the Owner Voice Profile row)
        if (preferences.hasOwnerVoiceProfile()) {
            binding.tvOwnerVoiceBadge.text = "ACTIVE"
            binding.tvOwnerVoiceBadge.setTextColor(green)
            binding.tvOwnerVoiceBadge.setBackgroundResource(R.drawable.bg_jarvis_status_badge_green)
            binding.tvOwnerVoiceSubtitle.text = "Voice Profile Active • Only you can speak"

            binding.tvProfileVoiceStatus.text = "VOICE ENROLLED"
            binding.tvProfileVoiceStatus.setTextColor(green)
            binding.chipProfileVoiceStatus.setBackgroundResource(R.drawable.bg_jarvis_status_badge_green)
            binding.ivProfileVoiceBadgeIcon.setImageResource(R.drawable.ic_verified_badge)
            binding.ivProfileVoiceBadgeIcon.imageTintList = ColorStateList.valueOf(green)
        } else {
            binding.tvOwnerVoiceBadge.text = "SETUP"
            binding.tvOwnerVoiceBadge.setTextColor(sapphire)
            binding.tvOwnerVoiceBadge.setBackgroundResource(R.drawable.bg_jarvis_status_badge)
            binding.tvOwnerVoiceSubtitle.text = "Not enrolled • Tap to record voice profile"

            binding.tvProfileVoiceStatus.text = "ENROLL VOICE"
            binding.tvProfileVoiceStatus.setTextColor(sapphire)
            binding.chipProfileVoiceStatus.setBackgroundResource(R.drawable.bg_jarvis_status_badge)
            binding.ivProfileVoiceBadgeIcon.setImageResource(R.drawable.ic_settings_owner_voice)
            binding.ivProfileVoiceBadgeIcon.imageTintList = ColorStateList.valueOf(sapphire)
        }

        // AI & Intelligence rows
        binding.tvCurrentLanguage.text = preferences.language

        val persona = preferences.personality
        binding.tvCurrentPersona.text = "$persona • Voice: ${preferences.voice}"
        binding.tvProfilePersonaChip.text = persona.uppercase()

        binding.tvCurrentModel.text = preferences.aiModel.removePrefix("models/") + " • Live WebSocket"

        // Appearance row
        binding.tvCurrentTheme.text = when (preferences.appTheme) {
            "dark" -> "Dark Theme"
            "red" -> "Red Theme"
            "sunset" -> "Sunset Theme"
            "custom" -> "Custom (${preferences.customThemeColor})"
            else -> "Light Theme"
        }
    }

    private fun refreshSwitchStates() {
        val ctx = requireContext()
        val messageSettings = MessageAlertSettings(ctx)
        val alertSettings = AlertSettings(ctx)
        syncingUi = true
        try {
            binding.switchSilentMode.isChecked = preferences.isSilentExecutionMode
            binding.switchOwnerOnly.isChecked = VoiceModeStore(ctx).ownerOnly
            binding.switchMessageAnnounce.isChecked = messageSettings.enabled
            binding.switchMessageJarvisVoice.isChecked = messageSettings.useJarvisVoice
            binding.switchChargingAlerts.isChecked = alertSettings.chargingAlerts
            binding.switchAutoBatterySaver.isChecked = alertSettings.autoBatterySaver
            binding.switchActivityMemory.isChecked = ActivityLogSettings(ctx).enabled
        } finally {
            syncingUi = false
        }
    }

    private fun updateAiEngineSubtitle() {
        val store = AiEngineStore(requireContext())
        binding.tvAiEngineSubtitle.text = if (store.useGroq) {
            "Active: Groq (" + store.groqChatModel + ")"
        } else {
            "Active: Gemini Live"
        }
    }

    private fun updateVoiceGenderSubtitle() {
        binding.tvVoiceGenderSubtitle.text = VoiceGenderStore(requireContext()).summary()
    }

    private fun updateNoiseCancelSubtitle() {
        val level = NoiseCancelSettings(requireContext()).level
        binding.tvNoiseCancelSubtitle.text = "Currently: " + level.label
    }

    private fun updateAutoSleepSubtitle() {
        val settings = AutoSleepSettings(requireContext())
        binding.tvAutoSleepSubtitle.text = if (settings.timeoutSeconds == 0) {
            "Never goes back to standby by itself"
        } else {
            "After " + AutoSleepSettings.labelFor(settings.timeoutSeconds) +
                (if (settings.countFromWake) " from waking up" else " without conversation")
        }
    }

    private fun updateNotificationAccessSubtitle() {
        val granted = isNotificationAccessGranted()
        val status = com.jarvis.assistant.notifications.MessageAlertSettings(requireContext()).lastStatus
        val base = if (granted) {
            "Allowed. Tap to change."
        } else {
            "Not allowed yet. Tap to let Jarvis see new message notifications."
        }
        binding.tvNotificationAccessSubtitle.text = if (granted && status.isNotBlank()) base + " Last: " + status else base
    }

    // ------------------------------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------------------------------

    /** Makes the whole row toggle its switch, and reports only changes made by the user. */
    private fun bindSwitchRow(row: View, toggle: CompoundButton, onChanged: (Boolean) -> Unit) {
        toggle.setOnCheckedChangeListener { _, isChecked ->
            if (!syncingUi) onChanged(isChecked)
        }
        row.setOnClickListener { toggle.toggle() }
    }

    private fun open(target: Class<*>) {
        startActivity(Intent(requireContext(), target))
    }

    private fun toast(message: String, long: Boolean = false) {
        Toast.makeText(
            requireContext(),
            message,
            if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        ).show()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            toast("Unable to open link")
        }
    }

    // ------------------------------------------------------------------------------------------
    // Dialogs and system screens
    // ------------------------------------------------------------------------------------------

    private fun showEditNameDialog() {
        val input = EditText(requireContext()).apply {
            setText(preferences.userName.ifBlank { "Sukant" })
            setSelection(text.length)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Operator Name")
            .setMessage("Enter the name Jarvis should use to address you:")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotBlank()) {
                    preferences.userName = newName
                    loadUserData()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLanguageDialog() {
        val languages = GeminiConstants.SUPPORTED_LANGUAGES.toTypedArray()
        val currentIndex = languages.indexOfFirst { it.equals(preferences.language, ignoreCase = true) }
            .let { if (it >= 0) it else 0 }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Select Conversation Language")
            .setSingleChoiceItems(languages, currentIndex) { dialog, which ->
                val chosen = languages[which]
                preferences.language = chosen
                binding.tvCurrentLanguage.text = chosen
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAiEngineDialog() {
        val ctx = requireContext()
        val store = AiEngineStore(ctx)
        val pad = (20 * resources.displayMetrics.density).toInt()

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val group = RadioGroup(ctx)
        val rbGemini = RadioButton(ctx).apply {
            id = View.generateViewId()
            text = "Gemini Live (voice to voice)"
        }
        val rbGroq = RadioButton(ctx).apply {
            id = View.generateViewId()
            text = "Groq (Whisper + Llama, phone voice)"
        }
        group.addView(rbGemini)
        group.addView(rbGroq)
        if (store.useGroq) rbGroq.isChecked = true else rbGemini.isChecked = true

        val keyInput = EditText(ctx).apply {
            hint = if (store.hasGroqKey()) "Groq key saved. Leave empty to keep it" else "Groq API key (starts with gsk_)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        val modelInput = EditText(ctx).apply {
            setText(store.groqChatModel)
            hint = "Groq chat model"
            setSingleLine()
        }
        container.addView(group)
        container.addView(keyInput)
        container.addView(modelInput)

        MaterialAlertDialogBuilder(ctx)
            .setTitle("AI Engine")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val newKey = keyInput.text.toString().trim()
                if (newKey.isNotEmpty() && !store.setGroqKey(newKey)) {
                    Toast.makeText(ctx, "Could not store the Groq key securely on this phone", Toast.LENGTH_LONG).show()
                }
                val model = modelInput.text.toString().trim()
                if (model.isNotEmpty()) store.groqChatModel = model

                val wantGroq = rbGroq.isChecked
                if (wantGroq && !store.hasGroqKey()) {
                    store.useGroq = false
                    Toast.makeText(ctx, "Enter your Groq API key first. Staying on Gemini.", Toast.LENGTH_LONG).show()
                } else {
                    store.useGroq = wantGroq
                    Toast.makeText(ctx, "Saved. It takes effect the next time Jarvis starts listening.", Toast.LENGTH_LONG).show()
                }
                updateAiEngineSubtitle()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showVoiceGenderDialog() {
        val ctx = requireContext()
        val store = VoiceGenderStore(ctx)
        val pad = (20 * resources.displayMetrics.density).toInt()

        var currentGender = store.gender
        var maleChoice = store.maleVoice
        var femaleChoice = store.femaleVoice

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val group = RadioGroup(ctx)
        val buttons = HashMap<VoiceGender, RadioButton>()
        for (option in VoiceGender.values()) {
            val button = RadioButton(ctx).apply {
                id = View.generateViewId()
                text = if (option == VoiceGender.UNSPECIFIED) option.label + " (default)" else option.label
            }
            group.addView(button)
            buttons[option] = button
        }
        buttons[currentGender]?.isChecked = true

        val note = TextView(ctx).apply {
            text = "Jarvis keeps using the voice you already chose in Settings."
            textSize = 12f
        }
        val spinner = Spinner(ctx)

        fun refreshVoiceList() {
            val voices = VoiceCatalog.voicesFor(currentGender)
            if (voices.isEmpty()) {
                spinner.visibility = View.GONE
                note.visibility = View.VISIBLE
                return
            }
            spinner.visibility = View.VISIBLE
            note.visibility = View.GONE
            val labels = voices.map { it.name + " - " + it.style }
            spinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, labels)
            val chosen = if (currentGender == VoiceGender.MALE) maleChoice else femaleChoice
            spinner.setSelection(voices.indexOfFirst { it.name == chosen }.coerceAtLeast(0))
        }

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val voices = VoiceCatalog.voicesFor(currentGender)
                val picked = voices.getOrNull(position)?.name ?: return
                if (currentGender == VoiceGender.MALE) maleChoice = picked else femaleChoice = picked
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        group.setOnCheckedChangeListener { _, checkedId ->
            val selected = buttons.entries.firstOrNull { it.value.id == checkedId }?.key
            if (selected != null) {
                currentGender = selected
                refreshVoiceList()
            }
        }

        container.addView(group)
        container.addView(note)
        container.addView(spinner)
        refreshVoiceList()

        MaterialAlertDialogBuilder(ctx)
            .setTitle("Voice & Gender")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                store.gender = currentGender
                store.maleVoice = maleChoice
                store.femaleVoice = femaleChoice
                updateVoiceGenderSubtitle()
                Toast.makeText(ctx, "Saved. It takes effect the next time Jarvis starts listening.", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showNoiseCancelDialog() {
        val ctx = requireContext()
        val settings = NoiseCancelSettings(ctx)
        val levels = NoiseLevel.values()
        var selected = levels.indexOf(settings.level).coerceAtLeast(0)
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Noise Cancellation")
            .setSingleChoiceItems(levels.map { it.label }.toTypedArray(), selected) { _, which -> selected = which }
            .setPositiveButton("Save") { _, _ ->
                settings.level = levels[selected]
                updateNoiseCancelSubtitle()
                Toast.makeText(ctx, "Saved. It takes effect the next time Jarvis wakes up.", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAutoSleepDialog() {
        val ctx = requireContext()
        val settings = AutoSleepSettings(ctx)
        val choices = AutoSleepSettings.CHOICES_SECONDS
        var selected = choices.indexOf(settings.timeoutSeconds).let { if (it < 0) 1 else it }
        var fromWake = settings.countFromWake
        val pad = (20 * resources.displayMetrics.density).toInt()

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val group = RadioGroup(ctx)
        val ids = ArrayList<Int>()
        for ((index, seconds) in choices.withIndex()) {
            val button = RadioButton(ctx).apply {
                id = View.generateViewId()
                text = AutoSleepSettings.labelFor(seconds) + (if (seconds == 60) " (default)" else "")
                isChecked = index == selected
            }
            ids.add(button.id)
            group.addView(button)
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            val position = ids.indexOf(checkedId)
            if (position >= 0) selected = position
        }
        val box = CheckBox(ctx).apply {
            text = "Count from waking up, even if I am still talking"
            isChecked = fromWake
            setOnCheckedChangeListener { _, checked -> fromWake = checked }
        }
        container.addView(group)
        container.addView(box)

        MaterialAlertDialogBuilder(ctx)
            .setTitle("Auto-Sleep")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                settings.timeoutSeconds = choices[selected]
                settings.countFromWake = fromWake
                updateAutoSleepSubtitle()
                Toast.makeText(ctx, "Saved. Jarvis sleeps after that time, and wakes when you say Jarvis.", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun requestDefaultAssistant() {
        try {
            // RoleManager only exists on Android 10+. Older phones use the voice input settings page.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = requireContext().getSystemService(RoleManager::class.java)
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

    private fun isNotificationAccessGranted(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(requireContext())
            .contains(requireContext().packageName)

    private fun openNotificationAccessSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (e: Exception) {
            toast("Open Android Settings > Notifications > Notification access and allow Jarvis.", long = true)
        }
    }
}
