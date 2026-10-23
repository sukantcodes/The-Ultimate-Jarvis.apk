package com.jarvis.assistant.ui.settings

import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.jarvis.assistant.network.AiGatewayClient
import com.jarvis.assistant.security.AiGatewayStore
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** API key/model controls for OpenAI, Anthropic Claude and DeepSeek. */
class ApiGatewayActivity : AppCompatActivity() {
    private lateinit var providerSpinner: Spinner
    private lateinit var keyInput: TextInputEditText
    private lateinit var modelInput: TextInputEditText
    private lateinit var status: TextView
    private lateinit var autoCheck: CheckBox
    private lateinit var testButton: MaterialButton
    private val store by lazy { AiGatewayStore(this) }
    private val providerIds = listOf(AiGatewayStore.AUTO, AiGatewayStore.OPENAI, AiGatewayStore.ANTHROPIC, AiGatewayStore.DEEPSEEK)
    private var syncing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        val bg = Color.rgb(9, 13, 24)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(18), dp(20), dp(24)); setBackgroundColor(bg) }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        content.addView(TextView(this).apply {
            text = "AI API Gateway"; textSize = 25f; setTextColor(Color.WHITE); typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        content.addView(TextView(this).apply {
            text = "Choose a provider, securely save its key, and let Jarvis route supported text tasks to the fastest configured model."
            textSize = 13f; setTextColor(Color.LTGRAY); setPadding(0, dp(8), 0, dp(18))
        })
        card(content) {
            addView(label("Provider"))
            providerSpinner = Spinner(this@ApiGatewayActivity)
            providerSpinner.adapter = ArrayAdapter(this@ApiGatewayActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Auto • Fastest available", "OpenAI", "Anthropic / Claude", "DeepSeek"))
            addView(providerSpinner, LinearLayout.LayoutParams(-1, dp(48)))
            autoCheck = CheckBox(this@ApiGatewayActivity).apply {
                text = "Automatically choose fastest configured provider"; setTextColor(Color.WHITE)
                isChecked = store.autoFastest
            }
            addView(autoCheck)
            addView(label("API key"))
            keyInput = TextInputEditText(this@ApiGatewayActivity).apply {
                hint = "Paste provider API key"; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                maxLines = 1
            }
            addView(keyInput, LinearLayout.LayoutParams(-1, dp(54)))
            addView(label("Model ID • refresh to discover available models"))
            modelInput = TextInputEditText(this@ApiGatewayActivity).apply {
                setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); singleLine = true
            }
            addView(modelInput, LinearLayout.LayoutParams(-1, dp(54)))
            status = TextView(this@ApiGatewayActivity).apply { textSize = 12f; setTextColor(Color.LTGRAY); setPadding(0, dp(8), 0, dp(8)) }
            addView(status)
            testButton = button("Test key & discover latest available models")
            addView(testButton, LinearLayout.LayoutParams(-1, dp(48)))
            val save = button("Save provider settings")
            addView(save, LinearLayout.LayoutParams(-1, dp(48)))
            save.setOnClickListener { saveCurrent(showToast = true) }
            testButton.setOnClickListener { testAndDiscover() }
            providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (!syncing) loadProvider()
                }
            }
        }
        val gemini = button("Open existing Gemini Live settings")
        root.addView(gemini, LinearLayout.LayoutParams(-1, dp(48)))
        gemini.setOnClickListener { startActivity(android.content.Intent(this, ApiConnectionActivity::class.java)) }
        val back = button("Close")
        root.addView(back, LinearLayout.LayoutParams(-1, dp(44)))
        back.setOnClickListener { finish() }
        providerSpinner.setSelection(providerIds.indexOf(store.provider).coerceAtLeast(0))
        loadProvider()
    }

    private fun testAndDiscover() {
        val provider = selectedProvider()
        if (provider == AiGatewayStore.AUTO) {
            status.text = "Select OpenAI, Claude or DeepSeek to test a provider key."
            return
        }
        val key = keyInput.text?.toString()?.trim().orEmpty().ifBlank { store.key(provider) }
        if (key.isBlank()) { status.text = "Enter an API key first."; return }
        testButton.isEnabled = false
        status.text = "Checking API key and listing available models…"
        lifecycleScope.launch {
            val started = System.currentTimeMillis()
            try {
                val models = withContext(Dispatchers.IO) { AiGatewayClient.listModels(this@ApiGatewayActivity, provider, key) }
                if (models.isEmpty()) error("Provider returned no available models for this key.")
                val elapsed = System.currentTimeMillis() - started
                store.setLatency(provider, elapsed)
                modelInput.setText(AiGatewayClient.fastestSuggestedModel(provider, models))
                status.text = "Key accepted • ${models.size} models available • model-list latency ${elapsed} ms. Save to keep settings."
            } catch (e: Exception) {
                status.text = "Connection failed: ${e.message ?: "Check key/network"}"
            } finally { testButton.isEnabled = true }
        }
    }

    private fun saveCurrent(showToast: Boolean) {
        val provider = selectedProvider()
        store.autoFastest = autoCheck.isChecked
        store.provider = provider
        if (provider != AiGatewayStore.AUTO) {
            val typed = keyInput.text?.toString()?.trim().orEmpty()
            if (typed.isNotEmpty() && !store.setKey(provider, typed)) {
                status.text = "Secure key storage is unavailable; key was not saved."
                return
            }
            val model = modelInput.text?.toString()?.trim().orEmpty()
            if (model.isNotBlank()) store.setModel(provider, model)
        }
        status.text = "Saved securely. Auto routing will use configured providers and recorded response latency."
        if (showToast) Toast.makeText(this, "AI Gateway settings saved", Toast.LENGTH_SHORT).show()
    }

    private fun loadProvider() {
        if (!::keyInput.isInitialized) return
        val provider = selectedProvider()
        syncing = true
        keyInput.setText(if (provider == AiGatewayStore.AUTO) "" else store.key(provider))
        modelInput.setText(if (provider == AiGatewayStore.AUTO) "" else store.model(provider))
        keyInput.hint = if (provider == AiGatewayStore.AUTO) "Choose a provider to enter its key" else "Paste ${AiGatewayStore.label(provider)} API key"
        status.text = if (provider == AiGatewayStore.AUTO) {
            "Configured keys are stored encrypted on this device. Auto mode orders providers by measured latency and falls back on errors."
        } else if (store.hasKey(provider)) "Key saved • ${AiGatewayStore.label(provider)} ready to test" else "No saved key for ${AiGatewayStore.label(provider)}"
        syncing = false
    }

    private fun selectedProvider(): String = providerIds.getOrElse(providerSpinner.selectedItemPosition) { AiGatewayStore.AUTO }
    private fun label(text: String) = TextView(this).apply { this.text = text; textSize = 13f; setTextColor(Color.LTGRAY); setPadding(0, dp(12), 0, dp(4)) }
    private fun button(text: String) = MaterialButton(this).apply { this.text = text; isAllCaps = false }
    private fun card(parent: LinearLayout, build: LinearLayout.() -> Unit) {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(16)); setBackgroundColor(Color.rgb(22, 29, 45)); build() }
        parent.addView(panel, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
