package com.jarvis.assistant.ui.settings

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.data.model.GeminiConstants
import com.jarvis.assistant.databinding.ActivityApiConnectionBinding
import com.jarvis.assistant.provider.ActivationResult
import com.jarvis.assistant.provider.ProviderRuntime
import com.jarvis.assistant.provider.StepStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class ApiConnectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityApiConnectionBinding
    private val preferences by lazy { JarvisApp.instance.preferences }
    private val providerManager by lazy { ProviderRuntime.manager(applicationContext) }
    private val testHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        com.jarvis.assistant.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityApiConnectionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)

        setupUI()
        loadCurrentPreferences()
        setupListeners()
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener {
            finish()
        }
    }

    private fun loadCurrentPreferences() {
        binding.etApiKey.setText(preferences.apiKey)
        binding.etCustomEndpoint.setText(preferences.customEndpoint)

        if (!preferences.canStoreApiKeySecurely) {
            binding.tvLiveStatus.text = "SECURE STORAGE UNAVAILABLE • API KEY DISABLED"
            binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))
        }

        when (preferences.aiModel) {
            GeminiConstants.MODEL_3_8_LIVE -> binding.rbModelFlash20.isChecked = true
            GeminiConstants.MODEL_3_1_FLASH_AGENT -> binding.rbModelFlash15.isChecked = true
            else -> binding.rbModelFlashThinking.isChecked = true
        }

        if (!preferences.canStoreApiKeySecurely) {
            // Keep the secure-storage warning set above.
        } else if (preferences.apiKey.isNotBlank()) {
            binding.tvLiveStatus.text = "KEY CONFIGURED • READY"
            binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#22C55E"))
        } else {
            binding.tvLiveStatus.text = "NO API KEY • ENTER KEY BELOW"
            binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EAB308"))
        }

        if (preferences.customEndpoint.isNotBlank()) {
            binding.tvEndpointSummary.text = "Active Endpoint: ${preferences.customEndpoint}"
        }

        val autoApprove = com.jarvis.assistant.tools.PermissionPreferences.isAutoApproveAll(this)
        updateAutoApproveUI(autoApprove)
    }

    private fun updateAutoApproveUI(enabled: Boolean) {
        binding.switchApiAutoApproveAll.isChecked = enabled
        if (enabled) {
            binding.cardApiAutoApproveAll.background = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_auto_approve_card_active)
            binding.tvApiAutoApproveBadge.background = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_auto_approve_badge_active)
            binding.tvApiAutoApproveBadge.text = "★ UNRESTRICTED"
            binding.tvApiAutoApproveBadge.setTextColor(com.jarvis.assistant.util.ThemeManager.getThemeOnAccentColor())
            binding.tvApiAutoApproveSubtitle.text = "Zero-prompt autonomy · Complete bypass"
            binding.tvApiAutoApproveSubtitle.setTextColor(com.jarvis.assistant.util.ThemeManager.getThemeAccentColor())
            binding.viewApiAutoApproveDot.backgroundTintList = ColorStateList.valueOf(com.jarvis.assistant.util.ThemeManager.getThemeSuccessColor())
            binding.tvApiAutoApproveStatusText.text = "ACTIVE · All actions auto-approved without prompts"
            binding.tvApiAutoApproveStatusText.setTextColor(com.jarvis.assistant.util.ThemeManager.getThemeSuccessColor())
        } else {
            binding.cardApiAutoApproveAll.background = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_auto_approve_card_inactive)
            binding.tvApiAutoApproveBadge.background = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_auto_approve_badge_inactive)
            binding.tvApiAutoApproveBadge.text = "STANDARD PROMPTS"
            binding.tvApiAutoApproveBadge.setTextColor(com.jarvis.assistant.util.ThemeManager.getThemeTextSecondaryColor())
            binding.tvApiAutoApproveSubtitle.text = "Safety prompts active for write & execute tools"
            binding.tvApiAutoApproveSubtitle.setTextColor(com.jarvis.assistant.util.ThemeManager.getThemeTextMutedColor())
            binding.viewApiAutoApproveDot.backgroundTintList = ColorStateList.valueOf(com.jarvis.assistant.util.ThemeManager.getThemeTextMutedColor())
            binding.tvApiAutoApproveStatusText.text = "INACTIVE · Manual confirmation required"
            binding.tvApiAutoApproveStatusText.setTextColor(com.jarvis.assistant.util.ThemeManager.getThemeTextMutedColor())
        }
    }

    private fun setupListeners() {
        binding.tvGetApiKey.setOnClickListener {
            openGeminiApiKeyPage()
        }

        binding.btnPasteKey.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val item = clipboard.primaryClip?.getItemAt(0)
            val pasted = item?.text?.toString()?.trim() ?: ""
            if (pasted.isNotBlank()) {
                binding.etApiKey.setText(pasted)
                Toast.makeText(this, "API Key pasted from clipboard", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnClearKey.setOnClickListener {
            binding.etApiKey.setText("")
        }

        binding.btnManageKeys.setOnClickListener { startActivity(Intent(this, KeyManagerActivity::class.java)) }
        binding.btnWorkRouting.setOnClickListener { startActivity(Intent(this, WorkRoutingActivity::class.java)) }
        binding.btnWorkspaceTools.setOnClickListener { startActivity(Intent(this, ToolSettingsActivity::class.java)) }

        binding.cardApiAutoApproveAll.setOnClickListener {
            val next = !binding.switchApiAutoApproveAll.isChecked
            com.jarvis.assistant.tools.PermissionPreferences.setAutoApproveAll(this, next)
            updateAutoApproveUI(next)
            Toast.makeText(this, if (next) "⚡ Auto-Approve Enabled: All permissions auto-approved" else "Auto-Approve Disabled: Safety prompts enabled", Toast.LENGTH_SHORT).show()
        }

        binding.switchApiAutoApproveAll.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked != com.jarvis.assistant.tools.PermissionPreferences.isAutoApproveAll(this)) {
                com.jarvis.assistant.tools.PermissionPreferences.setAutoApproveAll(this, isChecked)
                updateAutoApproveUI(isChecked)
                Toast.makeText(this, if (isChecked) "⚡ Auto-Approve Enabled: All permissions auto-approved" else "Auto-Approve Disabled: Safety prompts enabled", Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnAddProvider.setOnClickListener {
            startActivity(Intent(this, CustomProviderEditorActivity::class.java))
        }
        binding.btnUseBuiltIn.setOnClickListener {
            providerManager.deactivate()
            com.jarvis.assistant.background.JarvisConversationService.clearLiveError()
            com.jarvis.assistant.background.JarvisConversationService.restartSession()
            refreshProviderCard()
            Snackbar.make(binding.root, "Using the built-in engines again.", Snackbar.LENGTH_LONG).show()
        }
        binding.btnTestGoogleKey.setOnClickListener {
            val enteredKey = binding.etApiKey.text?.toString()?.trim() ?: ""
            if (enteredKey.isBlank()) {
                Snackbar.make(binding.root, "Please enter a Google Gemini API key first", Snackbar.LENGTH_LONG).show()
            } else testConnection(enteredKey)
        }

        binding.btnTestConnection.setOnClickListener {
            // With a custom provider active, this button tests that provider (its endpoint, key and model), not Google.
            if (providerManager.activeProfile() != null) {
                testActiveProvider()
                return@setOnClickListener
            }
            val enteredKey = binding.etApiKey.text?.toString()?.trim() ?: ""
            if (enteredKey.isBlank()) {
                Snackbar.make(binding.root, "Please enter an API key first", Snackbar.LENGTH_LONG).show()
                return@setOnClickListener
            }
            testConnection(enteredKey)
        }

        binding.btnSaveConnection.setOnClickListener {
            saveAndApply()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshProviderCard()
    }

    private fun refreshProviderCard() {
        val active = providerManager.activeProfile()
        val list = binding.llProviderList
        list.removeAllViews()
        if (active == null) {
            binding.tvProviderActive.text = "Using built-in engines (Gemini / Groq)"
            binding.btnUseBuiltIn.visibility = android.view.View.GONE
            binding.btnTestGoogleKey.visibility = android.view.View.GONE
            binding.btnTestConnection.text = "Test Connection & Latency"
        } else {
            binding.tvProviderActive.text = "Active: ${active.displayName} · ${active.selectedModelId.ifBlank { "no model" }}"
            binding.btnUseBuiltIn.visibility = android.view.View.VISIBLE
            binding.btnTestGoogleKey.visibility = android.view.View.VISIBLE
            binding.btnTestConnection.text = "Test active provider"
            val model = active.selectedModel
            if (model != null) {
                binding.tvProviderDetail.text = com.jarvis.assistant.provider.CapabilityPolicy.summary(active, model) +
                    (if (active.lastRefreshAt > 0) " · models: ${active.lastRefreshStatus}" else "")
            }
        }
        providerManager.profiles().forEach { p ->
            val isActive = p.id == active?.id
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(0, 10, 0, 10)
            }
            row.addView(android.widget.TextView(this).apply {
                text = (if (isActive) "● " else "○ ") + p.displayName
                textSize = 14f
                setTextColor(themeColor(R.attr.themeTextPrimary))
            })
            row.addView(android.widget.TextView(this).apply {
                text = "${p.protocol.label} · ${p.selectedModelId.ifBlank { "no model chosen" }}"
                textSize = 11f
                setTextColor(themeColor(R.attr.themeTextMuted))
            })
            val actions = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
            actions.addView(com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "Edit"
                isAllCaps = false
                setOnClickListener {
                    startActivity(Intent(this@ApiConnectionActivity, CustomProviderEditorActivity::class.java)
                        .putExtra(CustomProviderEditorActivity.EXTRA_PROFILE_ID, p.id))
                }
            })
            if (!isActive) {
                actions.addView(com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "Set active"
                    isAllCaps = false
                    setOnClickListener { activateProvider(p.id) }
                })
            }
            row.addView(actions)
            list.addView(row)
        }
    }

    private fun themeColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private fun activateProvider(id: String) {
        lifecycleScope.launch {
            when (val result = providerManager.activate(id)) {
                is ActivationResult.Activated -> {
                    com.jarvis.assistant.background.JarvisConversationService.clearLiveError()
                    com.jarvis.assistant.background.JarvisConversationService.restartSession()
                    Snackbar.make(binding.root, "${result.profile.displayName} is now active" + (result.warning?.let { ". $it" } ?: "."), Snackbar.LENGTH_LONG).show()
                }
                is ActivationResult.Rejected -> Snackbar.make(binding.root, result.reason, Snackbar.LENGTH_LONG).show()
            }
            refreshProviderCard()
        }
    }

    /** Tests the active provider with its own endpoint, key and model. Google is never contacted. */
    private fun testActiveProvider() {
        val active = providerManager.activeProfile() ?: return
        binding.btnTestConnection.isEnabled = false
        binding.tvPingResult.text = "Testing ${active.displayName}…"
        binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#3B82F6"))
        lifecycleScope.launch {
            try {
                val report = providerManager.testConnection(active, null, active.selectedModelId)
                binding.tvPingResult.text = report.summary
                binding.tvLiveStatus.text = (if (report.ok) "PROVIDER OK • " else "PROVIDER FAILED • ") + active.displayName.uppercase()
                binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor(if (report.ok) "#22C55E" else "#EF4444"))
                Snackbar.make(binding.root, report.steps.joinToString("\n") {
                    val mark = when (it.status) { StepStatus.PASS -> "✓"; StepStatus.WARN -> "!"; StepStatus.FAIL -> "✗"; StepStatus.SKIPPED -> "–" }
                    "$mark ${it.title}: ${it.detail}"
                }, Snackbar.LENGTH_INDEFINITE).setAction("OK") {}.show()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                binding.tvPingResult.text = "Test could not finish (${e.javaClass.simpleName})."
            } finally {
                binding.btnTestConnection.isEnabled = true
            }
        }
    }

    private fun openGeminiApiKeyPage() {
        val intent = Intent(Intent.ACTION_VIEW, GEMINI_API_KEY_URI)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Snackbar.make(
                binding.root,
                "No browser is available to open Google AI Studio.",
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    private fun testConnection(apiKey: String) {
        binding.btnTestConnection.isEnabled = false
        binding.tvPingResult.text = "Testing ping..."
        binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#3B82F6"))

        lifecycleScope.launch {
            // Shared with the first-launch tutorial: one validator, one place that touches the key.
            val check = com.jarvis.assistant.network.ApiKeyValidator.validate(apiKey)
            val result: Pair<Boolean, Long> = when (check) {
                is com.jarvis.assistant.network.ApiKeyCheck.Valid -> Pair(true, check.latencyMs)
                is com.jarvis.assistant.network.ApiKeyCheck.Rejected -> Pair(false, check.httpCode.toLong())
                else -> Pair(false, -1L)
            }

            binding.btnTestConnection.isEnabled = true
            if (result.first) {
                val ping = result.second
                binding.tvPingResult.text = "Ping: ${ping}ms • Valid"
                binding.tvLiveStatus.text = "API KEY ACCEPTED • LIVE SESSION NOT TESTED"
                binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#22C55E"))
                Snackbar.make(binding.root, "Connection verified! Ping: ${ping}ms", Snackbar.LENGTH_LONG).show()
            } else {
                val code = result.second
                binding.tvPingResult.text = if (code == -1L) "Network Timeout" else "HTTP $code Error"
                binding.tvLiveStatus.text = "FAILED • INVALID KEY OR NETWORK"
                binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))
                Snackbar.make(binding.root, "Connection failed. Please verify API key.", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun saveAndApply() {
        val key = binding.etApiKey.text?.toString()?.trim() ?: ""
        val customEp = binding.etCustomEndpoint.text?.toString()?.trim() ?: ""

        val selectedModel = when (binding.rgModelGroup.checkedRadioButtonId) {
            binding.rbModelFlash20.id -> GeminiConstants.MODEL_3_8_LIVE
            binding.rbModelFlash15.id -> GeminiConstants.MODEL_3_1_FLASH_AGENT
            else -> GeminiConstants.MODEL_3_1_FLASH
        }

        try {
            preferences.apiKey = key
        } catch (e: IllegalStateException) {
            Snackbar.make(binding.root, e.message ?: "Secure key storage is unavailable.", Snackbar.LENGTH_LONG).show()
            return
        }
        preferences.aiModel = selectedModel
        preferences.customEndpoint = customEp
        com.jarvis.assistant.tools.PermissionPreferences.setAutoApproveAll(this, binding.switchApiAutoApproveAll.isChecked)
        com.jarvis.assistant.background.JarvisConversationService.clearLiveError()
        com.jarvis.assistant.background.JarvisConversationService.restartSession()

        Toast.makeText(this, "API & Connection settings saved!", Toast.LENGTH_SHORT).show()
        finish()
    }

    private companion object {
        val GEMINI_API_KEY_URI: Uri = Uri.parse("https://aistudio.google.com/apikey")
    }
}
