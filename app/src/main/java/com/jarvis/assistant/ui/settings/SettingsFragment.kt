package com.jarvis.assistant.ui.settings

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.data.model.GeminiConstants
import com.jarvis.assistant.databinding.FragmentSettingsBinding
import com.jarvis.assistant.ui.memory.MemoryActivity
import com.jarvis.assistant.ui.permission.PermissionActivity
import com.jarvis.assistant.ui.voice.VoiceEnrollmentActivity

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val preferences by lazy { JarvisApp.instance.preferences }

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
        com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)

        loadUserData()
        setupClickListeners()
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
            loadUserData()
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) {
            binding.root.visibility = View.VISIBLE
            com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
            loadUserData()
        } else if (hidden && _binding != null) {
            binding.root.visibility = View.GONE
        }
    }

    private fun loadUserData() {
        val userName = preferences.userName.ifBlank { "Sukant" }
        binding.tvProfileName.text = userName
        binding.tvProfileSubtitle.text = "JARVIS OPERATOR • PRIMARY USER"

        // Owner Voice Profile Status
        val hasVoiceProfile = preferences.hasOwnerVoiceProfile()
        if (hasVoiceProfile) {
            binding.tvOwnerVoiceBadge.text = "ACTIVE"
            binding.tvOwnerVoiceBadge.setTextColor(Color.parseColor("#10B981"))
            binding.tvOwnerVoiceBadge.setBackgroundResource(R.drawable.bg_jarvis_status_badge_green)
            binding.tvOwnerVoiceSubtitle.text = "Voice Profile Active • Only you can speak"

            binding.tvProfileVoiceStatus.text = "VOICE ENROLLED"
            binding.tvProfileVoiceStatus.setTextColor(Color.parseColor("#10B981"))
            binding.chipProfileVoiceStatus.setBackgroundResource(R.drawable.bg_jarvis_status_badge_green)
            binding.ivProfileVoiceBadgeIcon.setImageResource(R.drawable.ic_verified_badge)
            binding.ivProfileVoiceBadgeIcon.imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#10B981"))
        } else {
            binding.tvOwnerVoiceBadge.text = "SETUP"
            binding.tvOwnerVoiceBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.accent_sapphire))
            binding.tvOwnerVoiceBadge.setBackgroundResource(R.drawable.bg_jarvis_status_badge)
            binding.tvOwnerVoiceSubtitle.text = "Not enrolled • Tap to record voice profile"

            binding.tvProfileVoiceStatus.text = "ENROLL VOICE"
            binding.tvProfileVoiceStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.accent_sapphire))
            binding.chipProfileVoiceStatus.setBackgroundResource(R.drawable.bg_jarvis_status_badge)
            binding.ivProfileVoiceBadgeIcon.setImageResource(R.drawable.ic_settings_owner_voice)
            binding.ivProfileVoiceBadgeIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.accent_sapphire)
            )
        }

        // Language
        binding.tvCurrentLanguage.text = preferences.language

        // Persona & Voice
        val currentPersona = preferences.personality
        val currentVoice = preferences.voice
        binding.tvCurrentPersona.text = "$currentPersona • Voice: $currentVoice"
        binding.tvProfilePersonaChip.text = currentPersona.uppercase()

        // Silent Execution Mode
        binding.switchSilentMode.isChecked = preferences.isSilentExecutionMode

        // Model
        val modelDisplay = preferences.aiModel.removePrefix("models/")
        binding.tvCurrentModel.text = "$modelDisplay • Live WebSocket"

        // Theme
        binding.tvCurrentTheme.text = when (preferences.appTheme) {
            "dark" -> "Dark Theme"
            "red" -> "Red Theme"
            "sunset" -> "Sunset Theme"
            "custom" -> "Custom (${preferences.customThemeColor})"
            else -> "Light Theme"
        }
    }

    private fun setupClickListeners() {
        // Profile Card -> Change Name
        binding.cardProfile.setOnClickListener {
            showEditNameDialog()
        }

        // Voice Status Chip on Profile Card -> Opens Voice Enrollment
        binding.chipProfileVoiceStatus.setOnClickListener {
            startActivity(Intent(requireContext(), VoiceEnrollmentActivity::class.java))
        }

        // Section 1: AI & Voice Intelligence
        binding.rowOwnerVoice.setOnClickListener {
            startActivity(Intent(requireContext(), VoiceEnrollmentActivity::class.java))
        }

        binding.rowLanguage.setOnClickListener {
            showLanguageDialog()
        }

        binding.rowPersonaVoice.setOnClickListener {
            startActivity(Intent(requireContext(), PersonaActivity::class.java))
        }

        binding.switchSilentMode.setOnCheckedChangeListener { _, isChecked ->
            preferences.isSilentExecutionMode = isChecked
            val msg = if (isChecked) "Silent Execution Mode Enabled" else "Silent Execution Mode Disabled"
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }

        // Section 2: System & Intelligence Data
        binding.rowApiConnection.setOnClickListener {
            startActivity(Intent(requireContext(), ApiConnectionActivity::class.java))
        }

        binding.rowMemory.setOnClickListener {
            startActivity(Intent(requireContext(), MemoryActivity::class.java))
        }

        binding.rowPermissions.setOnClickListener {
            startActivity(Intent(requireContext(), PermissionActivity::class.java))
        }

        binding.rowOverlayAvatars.setOnClickListener {
            startActivity(Intent(requireContext(), OverlayAvatarActivity::class.java))
        }

        binding.rowApplications.setOnClickListener {
            startActivity(Intent(requireContext(), HandsFreeActivity::class.java))
        }

        // Section 3: Appearance & Security
        binding.rowAppTheme.setOnClickListener {
            ThemeSelectionBottomSheet.newInstance().show(childFragmentManager, ThemeSelectionBottomSheet.TAG)
        }

        binding.rowPrivacyPolicy.setOnClickListener {
            startActivity(Intent(requireContext(), PrivacyShieldActivity::class.java))
        }

        binding.rowScreenUnlock.setOnClickListener {
            startActivity(Intent(requireContext(), ScreenUnlockActivity::class.java))
        }

        updateAiEngineSubtitle()
        binding.rowAiEngine.setOnClickListener { showAiEngineDialog() }
        updateVoiceGenderSubtitle()
        binding.rowVoiceGender.setOnClickListener { showVoiceGenderDialog() }
        requestMissingCallPermissions()

        val alertSettings = com.jarvis.assistant.power.AlertSettings(requireContext())
        binding.switchChargingAlerts.isChecked = alertSettings.chargingAlerts
        binding.switchChargingAlerts.setOnCheckedChangeListener { _, isChecked ->
            alertSettings.chargingAlerts = isChecked
        }
        binding.switchAutoBatterySaver.isChecked = alertSettings.autoBatterySaver
        binding.switchAutoBatterySaver.setOnCheckedChangeListener { _, isChecked ->
            alertSettings.autoBatterySaver = isChecked
        }

        val activityLogSettings = com.jarvis.assistant.activitylog.ActivityLogSettings(requireContext())
        binding.switchActivityMemory.isChecked = activityLogSettings.enabled
        binding.switchActivityMemory.setOnCheckedChangeListener { _, isChecked ->
            activityLogSettings.enabled = isChecked
        }
        binding.rowClearActivityMemory.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Clear activity memory?")
                .setMessage("Jarvis will forget everything from the last 2 days. This cannot be undone.")
                .setPositiveButton("Clear") { _, _ ->
                    com.jarvis.assistant.activitylog.ActivityLog.get(requireContext()).clear()
                    Toast.makeText(requireContext(), "Activity memory cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        val voiceModeStore = com.jarvis.assistant.security.VoiceModeStore(requireContext())
        binding.switchOwnerOnly.isChecked = voiceModeStore.ownerOnly
        binding.switchOwnerOnly.setOnCheckedChangeListener { _, isChecked ->
            voiceModeStore.ownerOnly = isChecked
            val msg = if (isChecked) {
                if (preferences.hasOwnerVoiceProfile()) "Owner Voice Only: other voices are ignored" else "Owner Voice Only is on, but you still need to enroll your voice"
            } else {
                "Everyone mode: Jarvis answers every voice it hears"
            }
            Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
        }

        binding.rowDefaultAssistant.setOnClickListener {
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    val roleManager = requireContext().getSystemService(android.app.role.RoleManager::class.java)
                    if (roleManager != null && roleManager.isRoleAvailable(android.app.role.RoleManager.ROLE_ASSISTANT)) {
                        if (roleManager.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT)) {
                            Toast.makeText(requireContext(), "Jarvis is already your default assistant", Toast.LENGTH_LONG).show()
                        } else {
                            startActivity(roleManager.createRequestRoleIntent(android.app.role.RoleManager.ROLE_ASSISTANT))
                        }
                        return@setOnClickListener
                    }
                }
                startActivity(Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Open Android Settings > Apps > Default apps > Digital assistant app and choose Jarvis.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        binding.rowAppIdentity.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("JARVIS App Identity")
                .setMessage(com.jarvis.assistant.security.AppIdentityVerifier.report(requireContext()))
                .setPositiveButton("Close", null)
                .show()
        }

        // Social Media Community Buttons
        binding.btnSocialTelegram.setOnClickListener {
            openUrl("https://t.me/sukantcodesbycreator")
        }

        binding.btnSocialInstagram.setOnClickListener {
            openUrl("https://www.instagram.com/___itz__sukanta__?vrfl=bzg3djZ5MDh3NmY2")
        }

        binding.btnSocialYoutube.setOnClickListener {
            openUrl("https://www.youtube.com/@Sukant_Codes")
        }

        binding.tvFooterCredit.setOnClickListener {
            openUrl("https://t.me/sukantcodesbycreator")
        }
    }

    private fun showLanguageDialog() {
        val languages = GeminiConstants.SUPPORTED_LANGUAGES.toTypedArray()
        val currentIndex = languages.indexOfFirst { it.equals(preferences.language, ignoreCase = true) }.let { if (it >= 0) it else 0 }

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

    private fun showWakeWordDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Wake Words & Sensitivity")
            .setMessage("• Wake Words: 'Jarvis', 'Hey Jarvis'\n• Acoustic Trigger: Double Clap detection\n• Standby Mode: Low-power local Vosk offline engine\n\nWhen standby listening is active, Jarvis awakens instantly upon hearing your voice.")
            .setPositiveButton("Got It", null)
            .show()
    }

    private fun showAppAutomationDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Autonomous App Automation")
            .setMessage("Jarvis is integrated with your device capabilities:\n\n• WhatsApp: Hands-free messaging & calls\n• YouTube: Search and playback automation\n• Phone Controls: Flashlight, Volume, Brightness, Bluetooth\n• Apps & Tools: Launch, search, and manage apps via voice")
            .setPositiveButton("Close", null)
            .show()
    }

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

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(requireContext(), "Unable to open link", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun updateAiEngineSubtitle() {
        val store = com.jarvis.assistant.security.AiEngineStore(requireContext())
        binding.tvAiEngineSubtitle.text = if (store.useGroq) {
            "Active: Groq (" + store.groqChatModel + ")"
        } else {
            "Active: Gemini Live"
        }
    }

    private fun showAiEngineDialog() {
        val ctx = requireContext()
        val store = com.jarvis.assistant.security.AiEngineStore(ctx)
        val pad = (20 * resources.displayMetrics.density).toInt()

        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val group = android.widget.RadioGroup(ctx)
        val rbGemini = android.widget.RadioButton(ctx).apply {
            id = android.view.View.generateViewId()
            text = "Gemini Live (voice to voice)"
        }
        val rbGroq = android.widget.RadioButton(ctx).apply {
            id = android.view.View.generateViewId()
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

    /** Asks (in front of the user) for the contact and phone permissions that calling and SIM choice need. */
    private fun requestMissingCallPermissions() {
        val wanted = ArrayList<String>()
        wanted.add(android.Manifest.permission.READ_CONTACTS)
        wanted.add(android.Manifest.permission.CALL_PHONE)
        wanted.add(android.Manifest.permission.READ_PHONE_STATE)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            wanted.add(android.Manifest.permission.READ_PHONE_NUMBERS)
        }
        val missing = wanted.filter {
            androidx.core.content.ContextCompat.checkSelfPermission(requireContext(), it) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 4108)
    }

    private fun updateVoiceGenderSubtitle() {
        binding.tvVoiceGenderSubtitle.text = com.jarvis.assistant.voice.VoiceGenderStore(requireContext()).summary()
    }

    private fun showVoiceGenderDialog() {
        val ctx = requireContext()
        val store = com.jarvis.assistant.voice.VoiceGenderStore(ctx)
        val pad = (20 * resources.displayMetrics.density).toInt()

        var currentGender = store.gender
        var maleChoice = store.maleVoice
        var femaleChoice = store.femaleVoice

        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val group = android.widget.RadioGroup(ctx)
        val buttons = HashMap<com.jarvis.assistant.voice.VoiceGender, android.widget.RadioButton>()
        for (option in com.jarvis.assistant.voice.VoiceGender.values()) {
            val button = android.widget.RadioButton(ctx).apply {
                id = android.view.View.generateViewId()
                text = if (option == com.jarvis.assistant.voice.VoiceGender.UNSPECIFIED) option.label + " (default)" else option.label
            }
            group.addView(button)
            buttons[option] = button
        }
        buttons[currentGender]?.isChecked = true

        val note = android.widget.TextView(ctx).apply {
            text = "Jarvis keeps using the voice you already chose in Settings."
            textSize = 12f
        }
        val spinner = android.widget.Spinner(ctx)

        fun refreshVoiceList() {
            val voices = com.jarvis.assistant.voice.VoiceCatalog.voicesFor(currentGender)
            if (voices.isEmpty()) {
                spinner.visibility = android.view.View.GONE
                note.visibility = android.view.View.VISIBLE
                return
            }
            spinner.visibility = android.view.View.VISIBLE
            note.visibility = android.view.View.GONE
            val labels = voices.map { it.name + " - " + it.style }
            spinner.adapter = android.widget.ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, labels)
            val chosen = if (currentGender == com.jarvis.assistant.voice.VoiceGender.MALE) maleChoice else femaleChoice
            spinner.setSelection(voices.indexOfFirst { it.name == chosen }.coerceAtLeast(0))
        }

        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val voices = com.jarvis.assistant.voice.VoiceCatalog.voicesFor(currentGender)
                val picked = voices.getOrNull(position)?.name ?: return
                if (currentGender == com.jarvis.assistant.voice.VoiceGender.MALE) maleChoice = picked else femaleChoice = picked
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
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
}
