package com.jarvis.assistant.ui.settings

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.jarvis.assistant.R
import com.jarvis.assistant.data.preferences.CustomProviderStore
import com.jarvis.assistant.databinding.ActivityCustomProviderBinding
import com.jarvis.assistant.network.provider.AuthMethod
import com.jarvis.assistant.network.provider.CustomProviderProfile
import com.jarvis.assistant.network.provider.DiscoveredModel
import com.jarvis.assistant.network.provider.ProviderConfigManager
import com.jarvis.assistant.network.provider.ProviderModelDiscovery
import com.jarvis.assistant.network.provider.ProviderProtocol
import com.jarvis.assistant.network.provider.ProviderUrlValidator
import com.jarvis.assistant.security.CustomProviderCredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class CustomProviderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCustomProviderBinding
    private val store by lazy { CustomProviderStore(this) }
    private val credentials by lazy { CustomProviderCredentialStore(this) }
    private val configManager by lazy { ProviderConfigManager(this) }

    /** The profile currently being edited, or null when the form is empty (Add). */
    private var editingProfileId: String? = null
    private var pendingFetchedModels: List<DiscoveredModel> = emptyList()
    private var pendingFetchSignature: String = ""

    private fun currentProviderSignature(
        baseUrl: String,
        protocol: ProviderProtocol,
        authMethod: AuthMethod,
        discoveryPath: String
    ): String = listOf(baseUrl.trim().trimEnd('/'), protocol.name, authMethod.name, discoveryPath.trim())
        .joinToString("|")

    override fun onCreate(savedInstanceState: Bundle?) {
        com.jarvis.assistant.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityCustomProviderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // ThemeManager.applyTheme() paints one continuous background on the window.
        // Keep this root transparent so it cannot cover that canvas with a second layer.
        binding.root.setBackgroundColor(android.graphics.Color.TRANSPARENT)

        setupSpinners()
        setupListeners()
        refreshList()
        refreshActiveCard()
    }

    // ------------------------------------------------------------------ setup

    private fun setupSpinners() {
        val protocolLabels = ProviderProtocol.entries.map { it.label }
        binding.spProtocol.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, protocolLabels)
        val authLabels = AuthMethod.entries.map { it.label }
        binding.spAuth.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, authLabels)
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener { finish() }

        binding.btnPasteKey.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val pasted = clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: ""
            if (pasted.isNotBlank()) {
                binding.etApiKey.setText(pasted)
                Toast.makeText(this, "API key pasted from clipboard", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnClearKey.setOnClickListener { binding.etApiKey.setText("") }

        binding.btnTestConnection.setOnClickListener { testConnection() }
        binding.btnFetchModels.setOnClickListener { fetchModels() }

        binding.btnSaveProfile.setOnClickListener { saveProfile() }
        binding.btnActivate.setOnClickListener { activateEditable() }
        binding.btnDeactivate.setOnClickListener {
            configManager.deactivateCustom()
            com.jarvis.assistant.background.JarvisConversationService.clearLiveError()
            com.jarvis.assistant.background.JarvisConversationService.restartSession()
            refreshActiveCard()
            refreshList()
            Snackbar.make(binding.root, "Custom provider deactivated. Back to Gemini/Groq.", Snackbar.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------ list

    private fun refreshList() {
        binding.layoutProviderList.removeAllViews()
        val profiles = store.profiles
        if (profiles.isEmpty()) {
            val empty = TextView(this).apply {
                text = "No providers saved yet."
                setTextColor(resources.getColor(R.color.text_muted, null))
                textSize = 13f
                setPadding(4, 8, 4, 8)
            }
            binding.layoutProviderList.addView(empty)
            return
        }
        profiles.forEachIndexed { index, profile ->
            binding.layoutProviderList.addView(buildProfileRow(profile))
            if (index < profiles.size - 1) {
                val divider = View(this).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1
                    )
                    setBackgroundColor(resources.getColor(R.color.text_muted, null))
                }
                binding.layoutProviderList.addView(divider)
            }
        }
    }

    private fun buildProfileRow(profile: CustomProviderProfile): View {
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(4, 8, 4, 8)
        }

        val title = TextView(this).apply {
            text = profile.name + (if (store.isActive(profile.id)) "  •  ACTIVE" else "")
            setTextColor(resources.getColor(R.color.text_primary, null))
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        container.addView(title)

        val modelId = profile.selectedModel()?.id ?: profile.selectedModelId
        val subtitle = TextView(this).apply {
            text = "${profile.protocol.label}  •  ${profile.authMethod.label}" +
                (if (modelId.isNotBlank()) "\nModel: $modelId" else "")
            setTextColor(resources.getColor(R.color.text_muted, null))
            textSize = 12f
        }
        container.addView(subtitle)

        val actions = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(0, 6, 0, 0)
        }

        val editBtn = com.google.android.material.button.MaterialButton(this).apply {
            text = "Edit"
            textSize = 12f
            isAllCaps = false
            setTextColor(resources.getColor(R.color.text_primary, null))
            backgroundTintList = null
            strokeColor = colorStateListOf(R.color.text_muted)
            strokeWidth = 1
        }
        editBtn.setOnClickListener { loadIntoForm(profile.id) }
        actions.addView(editBtn, android.widget.LinearLayout.LayoutParams(0, dp(42), 1f))

        val activateBtn = com.google.android.material.button.MaterialButton(this).apply {
            text = if (store.isActive(profile.id)) "Active" else "Activate"
            textSize = 12f
            isAllCaps = false
            setTextColor(resources.getColor(R.color.text_primary, null))
            backgroundTintList = null
            strokeColor = colorStateListOf(R.color.text_muted)
            strokeWidth = 1
        }
        activateBtn.setOnClickListener {
            if (credentials.hasKeyFor(profile.id) && profile.selectedModel() != null) {
                configManager.activateCustom(profile)
                com.jarvis.assistant.background.JarvisConversationService.clearLiveError()
                com.jarvis.assistant.background.JarvisConversationService.restartSession()
                refreshList()
                refreshActiveCard()
                Snackbar.make(binding.root, "Now using ${profile.name}.", Snackbar.LENGTH_LONG).show()
            } else {
                Toast.makeText(
                    this,
                    "Save an API key and select a model before activating.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        actions.addView(activateBtn, android.widget.LinearLayout.LayoutParams(0, dp(42), 1f))

        val deleteBtn = com.google.android.material.button.MaterialButton(this).apply {
            text = "Delete"
            textSize = 12f
            isAllCaps = false
            setTextColor(resources.getColor(R.color.text_muted, null))
            backgroundTintList = null
            strokeColor = colorStateListOf(R.color.text_muted)
            strokeWidth = 1
        }
        deleteBtn.setOnClickListener { confirmDelete(profile) }
        actions.addView(deleteBtn, android.widget.LinearLayout.LayoutParams(0, dp(42), 1f))

        container.addView(actions)
        return container
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun colorStateListOf(colorRes: Int): android.content.res.ColorStateList {
        val color = resources.getColor(colorRes, null)
        return android.content.res.ColorStateList.valueOf(color)
    }

    private fun refreshActiveCard() {
        val profile = configManager.activeCustomProfile
        if (profile == null) {
            binding.tvActiveStatus.text = "No custom provider active"
            binding.tvActiveDetail.text = "Jarvis is using Gemini or Groq."
            binding.btnDeactivate.isEnabled = false
        } else {
            val model = profile.selectedModel()?.id ?: profile.selectedModelId
            val note = if (configManager.customSupportsRealtimeVoice()) {
                "  •  Live voice supported"
            } else {
                "  •  Voice uses text-to-speech (no realtime)"
            }
            binding.tvActiveStatus.text = "Active: ${profile.name}"
            binding.tvActiveDetail.text = "Model: ${model.ifBlank { "none selected" }}$note"
            binding.btnDeactivate.isEnabled = true
        }
    }

    // ------------------------------------------------------------------ form

    private fun selectedProtocol(): ProviderProtocol =
        ProviderProtocol.entries.getOrNull(binding.spProtocol.selectedItemPosition)
            ?: ProviderProtocol.OPENAI_COMPATIBLE

    private fun selectedAuth(): AuthMethod =
        AuthMethod.entries.getOrNull(binding.spAuth.selectedItemPosition) ?: AuthMethod.BEARER

    private fun loadIntoForm(profileId: String) {
        val profile = store.profile(profileId) ?: return
        editingProfileId = profile.id
        pendingFetchedModels = profile.models
        pendingFetchSignature = currentProviderSignature(
            profile.baseUrl, profile.protocol, profile.authMethod, profile.discoveryPath
        )
        binding.tvFormTitle.text = "Edit Provider"
        binding.etName.setText(profile.name)
        binding.etBaseUrl.setText(profile.baseUrl)
        binding.etDiscoveryPath.setText(profile.discoveryPath)
        binding.spProtocol.setSelection(
            ProviderProtocol.entries.indexOf(profile.protocol).coerceAtLeast(0)
        )
        binding.spAuth.setSelection(AuthMethod.entries.indexOf(profile.authMethod).coerceAtLeast(0))
        binding.etApiKey.setText("")
        binding.tvEndpointResult.text =
            if (credentials.hasKeyFor(profile.id)) {
                "A key is saved for this provider (leave the field empty to keep it)."
            } else {
                "No key saved for this provider."
            }
        renderModels(profile.models, profile.selectedModelId)
        binding.etBaseUrl.requestFocus()
    }

    private fun renderModels(models: List<DiscoveredModel>, selectedModelId: String) {
        binding.layoutModelsList.removeAllViews()
        if (models.isEmpty()) {
            binding.tvModelHint.text = "Models appear here after Fetch Models"
            return
        }
        binding.tvModelHint.text = "${models.size} model(s) discovered. Tap to select one."
        for (model in models) {
            val rb = RadioButton(this).apply {
                text = if (model.displayName.isNotBlank() && model.displayName != model.id) {
                    "${model.id}  —  ${model.displayName}"
                } else {
                    model.id
                }
                textSize = 13f
                setTextColor(resources.getColor(R.color.text_primary, null))
                isChecked = model.id == selectedModelId
            }
            binding.layoutModelsList.addView(rb)
        }
    }

    /** Selected model id from the radio list, or the previously selected one. */
    private fun selectedModelFromUi(fallback: String): String {
        val available = ArrayList<String>(binding.layoutModelsList.childCount)
        var selected = ""
        for (i in 0 until binding.layoutModelsList.childCount) {
            val rb = binding.layoutModelsList.getChildAt(i) as? RadioButton ?: continue
            val id = rb.text.toString().substringBefore("  —  ").trim()
            if (id.isBlank()) continue
            available.add(id)
            if (rb.isChecked) selected = id
        }
        return selected.ifBlank {
            fallback.takeIf { it in available } ?: available.firstOrNull().orEmpty()
        }
    }

    // ------------------------------------------------------------------ actions

    private fun saveProfile() {
        val name = binding.etName.text?.toString()?.trim().orEmpty()
        val baseUrl = binding.etBaseUrl.text?.toString()?.trim().orEmpty()
        val discoveryPath = binding.etDiscoveryPath.text?.toString()?.trim().orEmpty()
        if (name.isBlank() || baseUrl.isBlank()) {
            Toast.makeText(this, "Provider name and base URL are required.", Toast.LENGTH_LONG).show()
            return
        }
        val protocol = selectedProtocol()
        val validation = ProviderUrlValidator.validate(baseUrl, protocol)
        if (validation !is ProviderUrlValidator.Result.Ok) {
            val validationError = validation as ProviderUrlValidator.Result.Error
            Toast.makeText(this, "Invalid URL: ${validationError.reason}", Toast.LENGTH_LONG).show()
            return
        }

        val old = editingProfileId?.let { store.profile(it) }
        val id = editingProfileId ?: UUID.randomUUID().toString()
        val signature = currentProviderSignature(baseUrl, protocol, selectedAuth(), discoveryPath)
        val models = if (pendingFetchSignature == signature && pendingFetchedModels.isNotEmpty()) {
            pendingFetchedModels
        } else {
            old?.models ?: emptyList()
        }
        val selectedModelId = selectedModelFromUi(
            old?.selectedModelId?.takeIf { it.isNotBlank() }
                ?: models.firstOrNull()?.id.orEmpty()
        )

        val profile = CustomProviderProfile(
            id = id,
            name = name,
            baseUrl = validation.normalizedBase,
            protocol = protocol,
            authMethod = selectedAuth(),
            discoveryPath = discoveryPath,
            models = models,
            selectedModelId = selectedModelId,
            providerSettings = old?.providerSettings ?: emptyMap(),
            lastRefreshStatus = old?.lastRefreshStatus ?: ""
        )

        val enteredKey = binding.etApiKey.text?.toString()?.trim().orEmpty()
        if (enteredKey.isNotBlank() && !credentials.saveKey(id, enteredKey)) {
            Toast.makeText(this, "Could not store this key securely (Keystore unavailable).", Toast.LENGTH_LONG).show()
            return
        }

        store.save(profile)
        editingProfileId = id
        pendingFetchedModels = models
        pendingFetchSignature = signature

        val canActivate = credentials.hasKeyFor(id) &&
            selectedModelId.isNotBlank() &&
            models.any { it.id == selectedModelId }
        if (canActivate) {
            configManager.activateCustom(profile)
            runCatching {
                com.jarvis.assistant.background.JarvisConversationService.clearLiveError()
                com.jarvis.assistant.background.JarvisConversationService.restartSession()
            }.onFailure {
                android.util.Log.w("CustomProviderActivity", "Provider saved; live session restart deferred.", it)
            }
            Toast.makeText(this, "Provider saved and activated.", Toast.LENGTH_LONG).show()
        } else {
            if (store.isActive(id)) {
                configManager.deactivateCustom()
                com.jarvis.assistant.background.JarvisConversationService.clearLiveError()
                com.jarvis.assistant.background.JarvisConversationService.restartSession()
            }
            Toast.makeText(
                this,
                "Provider saved. Add an API key and fetch/select a model to activate it.",
                Toast.LENGTH_LONG
            ).show()
        }
        refreshList()
        refreshActiveCard()
    }

    /**
     * Test/fetch with an isolated encrypted credential. Typing a replacement key must not
     * overwrite the saved key until the user explicitly presses Save Provider.
     */
    private suspend fun discoverForForm(
        baseUrl: String,
        protocol: ProviderProtocol,
        enteredKey: String
    ): ProviderModelDiscovery.Result {
        val key = enteredKey.ifBlank {
            editingProfileId?.let { credentials.keyFor(it) }.orEmpty()
        }
        if (key.isBlank()) {
            return ProviderModelDiscovery.Result.Error(
                ProviderModelDiscovery.ErrorKind.AUTH_FAILED,
                "Enter an API key to test or fetch models."
            )
        }

        val temporaryId = "provider-probe-${UUID.randomUUID()}"
        if (!credentials.saveKey(temporaryId, key)) {
            return ProviderModelDiscovery.Result.Error(
                ProviderModelDiscovery.ErrorKind.AUTH_FAILED,
                "Could not store the test credential securely. Check Android Keystore availability."
            )
        }
        return try {
            val profile = editedProfileOrTemp(baseUrl, protocol).copy(id = temporaryId)
            withContext(Dispatchers.IO) {
                ProviderModelDiscovery().discover(profile, credentials)
            }
        } finally {
            credentials.clearKey(temporaryId)
        }
    }

    private fun testConnection() {
        val baseUrl = binding.etBaseUrl.text?.toString()?.trim().orEmpty()
        val protocol = selectedProtocol()
        val validation = ProviderUrlValidator.validate(baseUrl, protocol)
        if (validation !is ProviderUrlValidator.Result.Ok) {
            val validationError = validation as ProviderUrlValidator.Result.Error
            binding.tvEndpointResult.text = "Invalid URL: ${validationError.reason}"
            return
        }
        val enteredKey = binding.etApiKey.text?.toString()?.trim().orEmpty()
        if (enteredKey.isBlank() && editingProfileId == null) {
            binding.tvEndpointResult.text = "Enter an API key to test the connection."
            return
        }
        binding.btnTestConnection.isEnabled = false
        binding.tvEndpointResult.text = "Testing ${validation.host} ..."

        lifecycleScope.launch {
            val result = discoverForForm(baseUrl, protocol, enteredKey)
            binding.btnTestConnection.isEnabled = true
            when (result) {
                is ProviderModelDiscovery.Result.Success -> {
                    binding.tvEndpointResult.text =
                        "Connected! ${result.models.size} real model(s) from ${validation.host}."
                    renderModels(result.models, "")
                    Snackbar.make(
                        binding.root,
                        "Test Connection OK — models fetched from the endpoint.",
                        Snackbar.LENGTH_LONG
                    ).show()
                }
                is ProviderModelDiscovery.Result.Error -> {
                    binding.tvEndpointResult.text = result.message
                    Snackbar.make(binding.root, "Connection failed: ${result.message}", Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun fetchModels() {
        val baseUrl = binding.etBaseUrl.text?.toString()?.trim().orEmpty()
        val protocol = selectedProtocol()
        val validation = ProviderUrlValidator.validate(baseUrl, protocol)
        if (validation !is ProviderUrlValidator.Result.Ok) {
            val validationError = validation as ProviderUrlValidator.Result.Error
            binding.tvEndpointResult.text = "Invalid URL: ${validationError.reason}"
            return
        }
        val enteredKey = binding.etApiKey.text?.toString()?.trim().orEmpty()
        if (enteredKey.isBlank() && editingProfileId == null) {
            binding.tvEndpointResult.text = "Enter an API key to fetch models."
            return
        }
        binding.btnFetchModels.isEnabled = false
        binding.tvEndpointResult.text = "Fetching models from ${validation.host} ..."

        lifecycleScope.launch {
            val result = discoverForForm(baseUrl, protocol, enteredKey)
            binding.btnFetchModels.isEnabled = true
            when (result) {
                is ProviderModelDiscovery.Result.Success -> {
                    val existing = store.profile(editingProfileId ?: "")?.selectedModelId ?: ""
                    pendingFetchedModels = result.models
                    pendingFetchSignature = currentProviderSignature(
                        baseUrl, protocol, selectedAuth(), binding.etDiscoveryPath.text?.toString().orEmpty()
                    )
                    renderModels(result.models, existing.ifBlank { result.models.firstOrNull()?.id.orEmpty() })
                    binding.tvEndpointResult.text = "Models refreshed (${result.models.size})."
                    val updatedProfile = editingProfileId?.let { store.profile(it) }?.copy(models = result.models)
                    if (updatedProfile != null) store.save(updatedProfile)
                }
                is ProviderModelDiscovery.Result.Error -> {
                    binding.tvEndpointResult.text = result.message
                    Snackbar.make(binding.root, "Could not fetch models: ${result.message}", Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Profile for discovery/test calls: uses the entered key, or the saved key when editing. */
    private fun editedProfileOrTemp(baseUrl: String, protocol: ProviderProtocol): CustomProviderProfile {
        val existing = editingProfileId?.let { store.profile(it) }
        return CustomProviderProfile(
            id = editingProfileId ?: "temp",
            name = binding.etName.text?.toString()?.trim()?.ifBlank { "Test" } ?: "Test",
            baseUrl = baseUrl,
            protocol = protocol,
            authMethod = selectedAuth(),
            discoveryPath = binding.etDiscoveryPath.text?.toString()?.trim().orEmpty(),
            models = existing?.models ?: emptyList(),
            selectedModelId = existing?.selectedModelId ?: "",
            providerSettings = existing?.providerSettings ?: emptyMap(),
            lastRefreshStatus = existing?.lastRefreshStatus ?: ""
        )
    }

    private fun activateEditable() {
        val profile = editingProfileId?.let { store.profile(it) }
        if (profile == null) {
            Toast.makeText(this, "Save the provider first, then activate it.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!credentials.hasKeyFor(profile.id)) {
            Toast.makeText(this, "Add an API key for this provider first.", Toast.LENGTH_LONG).show()
            return
        }
        if (profile.selectedModel() == null) {
            Toast.makeText(this, "Fetch models and select a valid model before activating.", Toast.LENGTH_LONG).show()
            return
        }
        configManager.activateCustom(profile)
        com.jarvis.assistant.background.JarvisConversationService.clearLiveError()
        com.jarvis.assistant.background.JarvisConversationService.restartSession()
        refreshActiveCard()
        refreshList()
        Snackbar.make(binding.root, "Now using ${profile.name}.", Snackbar.LENGTH_LONG).show()
    }

    private fun confirmDelete(profile: CustomProviderProfile) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete ${profile.name}?")
            .setMessage("This removes the saved endpoint and its encrypted API key.")
            .setPositiveButton("Delete") { _, _ ->
                val wasActive = store.isActive(profile.id)
                credentials.clearKey(profile.id)
                store.delete(profile.id)
                if (wasActive) {
                    com.jarvis.assistant.background.JarvisConversationService.clearLiveError()
                    com.jarvis.assistant.background.JarvisConversationService.restartSession()
                }
                if (editingProfileId == profile.id) {
                    editingProfileId = null
                    binding.tvFormTitle.text = "Add Provider"
                    binding.etName.setText("")
                    binding.etBaseUrl.setText("")
                    binding.etDiscoveryPath.setText("")
                    binding.etApiKey.setText("")
                    binding.layoutModelsList.removeAllViews()
                    binding.tvModelHint.text = "Models appear here after Fetch Models"
                }
                refreshList()
                refreshActiveCard()
                Toast.makeText(this, "Provider deleted.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}