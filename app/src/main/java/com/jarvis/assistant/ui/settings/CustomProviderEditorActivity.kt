package com.jarvis.assistant.ui.settings

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.databinding.ActivityCustomProviderEditorBinding
import com.jarvis.assistant.provider.ActivationResult
import com.jarvis.assistant.provider.AuthMethod
import com.jarvis.assistant.provider.CapabilityPolicy
import com.jarvis.assistant.provider.DiscoveryOutcome
import com.jarvis.assistant.provider.ModelInfo
import com.jarvis.assistant.provider.ProviderConfigManager
import com.jarvis.assistant.provider.ProviderErrorKind
import com.jarvis.assistant.provider.ProviderProfile
import com.jarvis.assistant.provider.ProviderProtocol
import com.jarvis.assistant.provider.ProviderRuntime
import com.jarvis.assistant.provider.ProviderUrl
import com.jarvis.assistant.provider.StepStatus
import com.jarvis.assistant.provider.Support
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Add / edit one custom AI provider: address, its own key, protocol, authentication, real model discovery,
 * compatibility check, connection test, save and activate. The key is held only in the field while typing and
 * in the encrypted vault after saving; it is never shown again in full.
 */
class CustomProviderEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCustomProviderEditorBinding
    private val manager: ProviderConfigManager by lazy { ProviderRuntime.manager(applicationContext) }

    private var profileId: String = ""
    private var isNew = true
    private var models: List<ModelInfo> = emptyList()
    private var selectedId: String = ""
    private var chatModelId: String = ""
    private var sttSupport: Support = Support.UNKNOWN
    private var lastRefreshAt = 0L
    private var lastRefreshStatus = ""
    private var busy = false
    private val authMethods = AuthMethod.values().toList()

    override fun onCreate(savedInstanceState: Bundle?) {
        com.jarvis.assistant.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityCustomProviderEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)

        binding.etEndpointKey.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        binding.spAuth.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, authMethods.map { it.label })
        binding.spAuth.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = refreshAuthFields()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        val existing = intent.getStringExtra(EXTRA_PROFILE_ID)?.let { manager.profile(it) }
        if (existing != null) load(existing) else {
            profileId = manager.newId()
            binding.tvEditorTitle.text = "Add AI Provider"
            binding.btnDeleteProvider.visibility = View.GONE
            binding.tvKeyHint.text = if (manager.canStoreKeys) "The key is stored encrypted on this phone and sent only to this provider."
            else "Secure storage is unavailable on this device, so a key cannot be saved."
        }
        refreshAuthFields()
        renderModels()
        refreshSummary()

        binding.btnEditorBack.setOnClickListener { finish() }
        binding.btnToggleAdvanced.setOnClickListener {
            val show = binding.groupAdvanced.visibility != View.VISIBLE
            binding.groupAdvanced.visibility = if (show) View.VISIBLE else View.GONE
            binding.btnToggleAdvanced.text = if (show) "Advanced settings ▴" else "Advanced settings ▾"
        }
        binding.rgProtocol.setOnCheckedChangeListener { _, checked ->
            if (isNew && checked == binding.rbGemini.id && currentAuth() == AuthMethod.BEARER) {
                binding.spAuth.setSelection(authMethods.indexOf(AuthMethod.X_GOOG_API_KEY))
            } else if (isNew && checked == binding.rbOpenAi.id && currentAuth() == AuthMethod.X_GOOG_API_KEY) {
                binding.spAuth.setSelection(authMethods.indexOf(AuthMethod.BEARER))
            }
        }
        binding.etModelFilter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = renderModels()
        })
        binding.btnFetchModels.setOnClickListener { fetchModels() }
        binding.btnUseManual.setOnClickListener { useManualModel() }
        binding.btnPickChatModel.setOnClickListener { pickChatModel() }
        binding.btnCheckCompat.setOnClickListener { checkCompatibility() }
        binding.btnTestProvider.setOnClickListener { testConnection() }
        binding.btnSaveProvider.setOnClickListener { save(activate = false) }
        binding.btnActivateProvider.setOnClickListener { save(activate = true) }
        binding.btnDeleteProvider.setOnClickListener { confirmDelete() }
    }

    override fun onDestroy() {
        // Do not leave the typed key sitting in a view after the screen closes.
        if (::binding.isInitialized) binding.etEndpointKey.text?.clear()
        super.onDestroy()
    }

    // ------------------------------------------------------------------------------------------------------

    private fun load(p: ProviderProfile) {
        isNew = false
        profileId = p.id
        binding.tvEditorTitle.text = "Edit AI Provider"
        binding.etProvName.setText(p.name)
        binding.etBaseUrl.setText(p.baseUrl)
        if (p.protocol == ProviderProtocol.GEMINI) binding.rbGemini.isChecked = true else binding.rbOpenAi.isChecked = true
        binding.spAuth.setSelection(authMethods.indexOf(p.authMethod).coerceAtLeast(0))
        binding.etAuthName.setText(p.authName)
        binding.etDiscoveryPath.setText(p.discoveryPath)
        binding.etRealtimeUrl.setText(p.realtimeUrl)
        binding.etSttModel.setText(p.sttModelId)
        binding.etTemperature.setText(p.settings[ProviderProfile.SETTING_TEMPERATURE].orEmpty())
        binding.etMaxTokens.setText(p.settings[ProviderProfile.SETTING_MAX_TOKENS].orEmpty())
        binding.etExtraHeaders.setText(p.settings[ProviderProfile.SETTING_EXTRA_HEADERS].orEmpty())
        models = p.models
        selectedId = p.selectedModelId
        chatModelId = p.chatModelId
        sttSupport = p.sttSupport
        lastRefreshAt = p.lastRefreshAt
        lastRefreshStatus = p.lastRefreshStatus
        val hint = manager.keyHint(p.id)
        binding.tvKeyHint.text = if (hint.isNotEmpty()) "Saved key: $hint. Leave the field empty to keep it, or type a new key to replace it."
        else "No key is saved for this provider yet."
        if (lastRefreshAt > 0) {
            binding.tvModelStatus.text = "Last refresh: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(lastRefreshAt))} · $lastRefreshStatus"
        }
        if (models.isNotEmpty() || p.advancedUsed()) binding.btnToggleAdvanced.performClick()
    }

    private fun ProviderProfile.advancedUsed() =
        discoveryPath.isNotBlank() || realtimeUrl.isNotBlank() || sttModelId.isNotBlank() || settings.isNotEmpty()

    private fun currentAuth(): AuthMethod = authMethods.getOrElse(binding.spAuth.selectedItemPosition) { AuthMethod.BEARER }

    private fun refreshAuthFields() {
        val auth = currentAuth()
        binding.tilAuthName.visibility = if (auth.needsName) View.VISIBLE else View.GONE
        binding.tilAuthName.hint = if (auth == AuthMethod.QUERY_PARAM) "Query parameter name (e.g. key)" else "Header name (e.g. X-Api-Token)"
        binding.etEndpointKey.isEnabled = auth != AuthMethod.NONE
    }

    private fun draft(): ProviderProfile {
        val rawBase = binding.etBaseUrl.text?.toString()?.trim().orEmpty()
        val base = (ProviderUrl.validateBase(rawBase) as? ProviderUrl.Check.Ok)?.url ?: rawBase
        val settings = LinkedHashMap<String, String>()
        binding.etTemperature.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { settings[ProviderProfile.SETTING_TEMPERATURE] = it }
        binding.etMaxTokens.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { settings[ProviderProfile.SETTING_MAX_TOKENS] = it }
        binding.etExtraHeaders.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { settings[ProviderProfile.SETTING_EXTRA_HEADERS] = it }
        return ProviderProfile(
            id = profileId,
            name = binding.etProvName.text?.toString()?.trim().orEmpty(),
            baseUrl = base,
            protocol = if (binding.rbGemini.isChecked) ProviderProtocol.GEMINI else ProviderProtocol.OPENAI_COMPATIBLE,
            authMethod = currentAuth(),
            authName = if (currentAuth().needsName) binding.etAuthName.text?.toString()?.trim().orEmpty() else "",
            discoveryPath = binding.etDiscoveryPath.text?.toString()?.trim().orEmpty(),
            realtimeUrl = binding.etRealtimeUrl.text?.toString()?.trim().orEmpty(),
            models = models,
            selectedModelId = selectedId,
            chatModelId = chatModelId,
            sttModelId = binding.etSttModel.text?.toString()?.trim().orEmpty(),
            sttSupport = sttSupport,
            lastRefreshAt = lastRefreshAt,
            lastRefreshStatus = lastRefreshStatus,
            settings = settings
        )
    }

    private fun typedKey(): String? = binding.etEndpointKey.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    private fun setBusy(value: Boolean) {
        busy = value
        listOf(
            binding.btnFetchModels, binding.btnCheckCompat, binding.btnTestProvider,
            binding.btnSaveProvider, binding.btnActivateProvider, binding.btnDeleteProvider
        ).forEach { it.isEnabled = !value }
    }

    private fun snack(text: String) = Snackbar.make(binding.root, text, Snackbar.LENGTH_LONG).show()

    // ------------------------------------------------------------------------------------------------------
    // Models
    // ------------------------------------------------------------------------------------------------------

    private fun fetchModels() {
        if (busy) return
        val d = draft()
        manager.validate(d)?.let { binding.tvModelStatus.text = it; return }
        setBusy(true)
        binding.tvModelStatus.text = "Contacting ${d.displayName}…"
        lifecycleScope.launch {
            try {
                when (val outcome = manager.fetchModels(d, typedKey())) {
                    is DiscoveryOutcome.Success -> {
                        models = outcome.models
                        lastRefreshAt = System.currentTimeMillis()
                        lastRefreshStatus = "${models.size} models"
                        if (selectedId.isNotBlank() && models.none { it.id == selectedId }) {
                            val old = selectedId
                            selectedId = ""
                            binding.tvModelStatus.text = "\"$old\" is not in the refreshed list. Choose a model."
                        } else {
                            binding.tvModelStatus.text = "${models.size} models found" +
                                (if (outcome.truncated) " (list was very long and was cut off; filter or type an ID)" else "") +
                                " · refreshed just now"
                        }
                        renderModels()
                        refreshSummary()
                    }
                    is DiscoveryOutcome.Failure -> {
                        val e = outcome.error
                        lastRefreshStatus = "Failed"
                        binding.tvModelStatus.text = e.message +
                            if (e.kind == ProviderErrorKind.DISCOVERY_UNSUPPORTED || e.kind == ProviderErrorKind.NO_MODELS)
                                "\nYou can type a model ID below; it will be labelled manual."
                            else ""
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                binding.tvModelStatus.text = "Something went wrong while fetching models (${e.javaClass.simpleName})."
            } finally {
                setBusy(false)
            }
        }
    }

    private fun badgeText(m: ModelInfo): String = CapabilityPolicy.badges(m).joinToString(" · ") { (cap, support) ->
        cap.label + when (support) { Support.YES -> " ✓"; Support.NO -> " ✗"; Support.UNKNOWN -> " ?" }
    }

    private fun renderModels() {
        val container = binding.llModels
        container.removeAllViews()
        val filter = binding.etModelFilter.text?.toString()?.trim().orEmpty()
        val shown = models.filter { filter.isEmpty() || it.id.contains(filter, true) || it.displayName.contains(filter, true) }
        if (models.isEmpty()) {
            container.addView(note("No models yet. Tap \"Fetch / refresh models\"."))
            return
        }
        val limit = 150
        shown.take(limit).forEach { m -> container.addView(modelRow(m)) }
        if (shown.size > limit) container.addView(note("Showing $limit of ${shown.size}. Type in the filter to narrow the list."))
        if (shown.isEmpty()) container.addView(note("No model matches \"$filter\"."))
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(themeColor(com.jarvis.assistant.R.attr.themeTextMuted))
        setPadding(0, 8, 0, 8)
    }

    private fun themeColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private fun modelRow(m: ModelInfo): View {
        val selected = m.id == selectedId
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 14, 12, 14)
            isClickable = true
            isFocusable = true
            gravity = Gravity.START
            contentDescription = "${m.id}. ${badgeText(m)}" + if (selected) ". Selected" else ""
            alpha = if (selected) 1f else 0.92f
            if (selected) setBackgroundColor(themeColor(com.jarvis.assistant.R.attr.themeAccent) and 0x00FFFFFF or 0x22000000)
            setOnClickListener {
                selectedId = m.id
                renderModels()
                refreshSummary()
            }
        }
        row.addView(TextView(this).apply {
            text = (if (selected) "● " else "○ ") + m.label + if (m.manual) "  (manual)" else ""
            textSize = 14f
            setTextColor(themeColor(com.jarvis.assistant.R.attr.themeTextPrimary))
        })
        row.addView(TextView(this).apply {
            text = badgeText(m) + (if (m.contextWindow > 0) " · ${m.contextWindow / 1000}k ctx" else "")
            textSize = 11f
            setTextColor(themeColor(com.jarvis.assistant.R.attr.themeTextMuted))
        })
        return row
    }

    private fun useManualModel() {
        val id = binding.etManualModel.text?.toString()?.trim().orEmpty()
        if (id.isEmpty() || id.any { it.isWhitespace() } || id.length > 200) {
            snack("Enter a model ID without spaces.")
            return
        }
        if (models.none { it.id == id }) models = models + ModelInfo(id = id, manual = true)
        selectedId = id
        binding.etManualModel.setText("")
        renderModels()
        refreshSummary()
    }

    private fun pickChatModel() {
        if (models.isEmpty() && selectedId.isBlank()) { snack("Fetch or enter a model first."); return }
        val ids = (listOf("") + models.map { it.id }).take(301)
        val labels = ids.map { if (it.isEmpty()) "Same model as voice" else it }.toTypedArray()
        MaterialAlertDialogBuilder(this, com.jarvis.assistant.R.style.ThemeOverlay_Jarvis_MaterialDialog)
            .setTitle("Model for ChatBox")
            .setSingleChoiceItems(labels, ids.indexOf(chatModelId).coerceAtLeast(0)) { dialog, which ->
                chatModelId = ids[which]
                dialog.dismiss()
                refreshSummary()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshSummary() {
        val d = draft()
        val model = d.selectedModel
        binding.tvSelectedSummary.text = if (model == null) "No model selected yet." else
            "Voice model: ${model.id}\n" + CapabilityPolicy.summary(d, model) + "\n" + CapabilityPolicy.voice(d, model).reason
        binding.tvChatModel.text = "ChatBox model: " + (if (chatModelId.isBlank()) "same as voice model" else chatModelId)
    }

    // ------------------------------------------------------------------------------------------------------
    // Compatibility and test
    // ------------------------------------------------------------------------------------------------------

    private fun requireModel(): Boolean {
        if (selectedId.isBlank()) { snack("Choose a model first."); return false }
        return true
    }

    private fun checkCompatibility() {
        if (busy || !requireModel()) return
        val d = draft()
        manager.validate(d)?.let { binding.tvCompat.text = it; return }
        setBusy(true)
        binding.tvCompat.text = "Checking what ${d.selectedModelId} really supports…"
        lifecycleScope.launch {
            try {
                val report = manager.checkCompatibility(d, typedKey(), d.selectedModelId, force = true)
                report.abort?.let { binding.tvCompat.text = it.message; return@launch }
                models = models.map { if (it.id == report.model.id) report.model else it }
                    .let { list -> if (list.none { it.id == report.model.id }) list + report.model.copy(manual = true) else list }
                report.sttSupport?.let { sttSupport = it }
                renderModels()
                refreshSummary()
                binding.tvCompat.text = buildString {
                    report.steps.forEach { step ->
                        val mark = when (step.result.support) { Support.YES -> "✓"; Support.NO -> "✗"; Support.UNKNOWN -> "?" }
                        append(mark).append(' ').append(step.capability.label)
                        if (step.result.note.isNotBlank()) append(" — ").append(step.result.note)
                        append('\n')
                    }
                    if (report.sttNote.isNotBlank()) append("Speech-to-text: ").append(report.sttNote).append('\n')
                    append("Results are saved with the provider when you tap Save.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                binding.tvCompat.text = "The check could not finish (${e.javaClass.simpleName})."
            } finally {
                setBusy(false)
            }
        }
    }

    private fun testConnection() {
        if (busy) return
        val d = draft()
        manager.validate(d)?.let { binding.tvTestResult.text = it; return }
        setBusy(true)
        binding.tvTestResult.text = "Testing ${d.displayName}…"
        lifecycleScope.launch {
            try {
                val report = manager.testConnection(d, typedKey(), d.selectedModelId)
                report.models?.let { found ->
                    if (models.isEmpty()) { models = found; lastRefreshAt = System.currentTimeMillis(); lastRefreshStatus = "${found.size} models"; renderModels() }
                }
                binding.tvTestResult.text = (if (report.ok) "✓ " else "✗ ") + report.summary + "\n" +
                    report.steps.joinToString("\n") {
                        val mark = when (it.status) { StepStatus.PASS -> "✓"; StepStatus.WARN -> "!"; StepStatus.FAIL -> "✗"; StepStatus.SKIPPED -> "–" }
                        "$mark ${it.title}: ${it.detail}"
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                binding.tvTestResult.text = "The test could not finish (${e.javaClass.simpleName})."
            } finally {
                setBusy(false)
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------
    // Save / activate / delete
    // ------------------------------------------------------------------------------------------------------

    private fun save(activate: Boolean) {
        if (busy) return
        val d = draft()
        if (activate && d.selectedModelId.isBlank()) { snack("Choose a model before activating."); return }
        val problem = manager.save(d, typedKey())
        if (problem != null) { snack(problem); return }
        binding.etEndpointKey.text?.clear()
        isNew = false
        val wasActive = manager.activeProfile()?.id == d.id
        if (!activate) {
            if (wasActive) JarvisConversationService.restartSession()
            snack("Saved.")
            load(manager.profile(d.id) ?: d)
            return
        }
        setBusy(true)
        lifecycleScope.launch {
            try {
                when (val result = manager.activate(d.id)) {
                    is ActivationResult.Activated -> {
                        JarvisConversationService.clearLiveError()
                        JarvisConversationService.restartSession()
                        android.widget.Toast.makeText(
                            applicationContext,
                            "${result.profile.displayName} is now active" + (result.warning?.let { ". $it" } ?: "."),
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                        finish()
                    }
                    is ActivationResult.Rejected -> snack("Saved, but not activated: ${result.reason}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snack("Activation failed (${e.javaClass.simpleName}). The previous setup is unchanged.")
            } finally {
                setBusy(false)
            }
        }
    }

    private fun confirmDelete() {
        val p = manager.profile(profileId) ?: run { finish(); return }
        MaterialAlertDialogBuilder(this, com.jarvis.assistant.R.style.ThemeOverlay_Jarvis_MaterialDialog)
            .setTitle("Delete ${p.displayName}?")
            .setMessage("This removes the provider and its saved key from this phone. Your Google Gemini and Groq settings are not touched." +
                if (manager.activeProfile()?.id == p.id) "\n\nThis provider is active; JARVIS will return to its built-in engines." else "")
            .setPositiveButton("Delete") { _, _ ->
                val wasActive = manager.delete(p.id)
                if (wasActive) JarvisConversationService.restartSession()
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        const val EXTRA_PROFILE_ID = "provider_id"
    }
}
