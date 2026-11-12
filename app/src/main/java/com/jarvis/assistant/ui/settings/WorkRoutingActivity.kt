package com.jarvis.assistant.ui.settings

import android.os.Bundle
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jarvis.assistant.provider.ProviderRuntime
import com.jarvis.assistant.provider.Route
import com.jarvis.assistant.provider.WorkCategory
import com.jarvis.assistant.tts.engine.TtsModelDownloader
import com.jarvis.assistant.tts.model.VoiceEngineType
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.launch

/**
 * Work Routing: pick which provider and model handles each kind of work (chat, file analysis, images, coding,
 * voice, task planning, task execution, web research, GitHub, documents).
 * Immediate reactive updates when offline neural TTS models are installed or custom providers are edited.
 */
class WorkRoutingActivity : AppCompatActivity() {

    private val providers by lazy { ProviderRuntime.manager(applicationContext) }
    private val routes by lazy { ProviderRuntime.routing(applicationContext) }
    private lateinit var body: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ThemeManager.applyDynamicBackground(root)
        val bar = UiKit.row(this).apply { setPadding(UiKit.dp(this@WorkRoutingActivity, 8), UiKit.dp(this@WorkRoutingActivity, 12), UiKit.dp(this@WorkRoutingActivity, 16), UiKit.dp(this@WorkRoutingActivity, 8)) }
        bar.addView(UiKit.button(this, "‹ Back") { finish() })
        bar.addView(UiKit.text(this, "  Work Routing", 20f, bold = true))
        root.addView(bar)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p = UiKit.dp(this@WorkRoutingActivity, 16); setPadding(p, 0, p, p) }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        // Initial sync of installed offline TTS engines
        TtsModelDownloader.notifyEnginesChanged(this)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                TtsModelDownloader.installedEnginesFlow.collect {
                    rebuildRoutingUi()
                }
            }
        }
    }

    private fun rebuildRoutingUi() {
        body.removeAllViews()
        body.addView(UiKit.text(this, "Choose which provider handles each kind of work. Nothing is forced through Gemini: \"Follow active provider\" uses the custom provider you activated, and Gemini or Offline Voice is used only where you pick it.", 13f, muted = true).apply {
            setPadding(0, 0, 0, UiKit.dp(this@WorkRoutingActivity, 12))
        })

        val profiles = providers.profiles()
        val installedLocalEngines = VoiceEngineType.entries.filter { it.isLocal && TtsModelDownloader.isEngineInstalled(this, it) }

        WorkCategory.values().forEach { category ->
            val card = UiKit.card(this)
            card.addView(UiKit.text(this, category.label, 15f, bold = true))

            // Build labels specific to this category
            val isVoice = category == WorkCategory.VOICE
            val localEngineLabels = if (isVoice) installedLocalEngines.map { "Offline Voice (${it.displayName.substringBefore(" ")})" } else emptyList()
            val labels = listOf("Follow active provider", "Gemini (built-in)") + localEngineLabels + profiles.map { it.displayName }

            val providerSpinner = Spinner(this).apply {
                minimumHeight = UiKit.dp(this@WorkRoutingActivity, 48)
                adapter = ArrayAdapter(this@WorkRoutingActivity, android.R.layout.simple_spinner_dropdown_item, labels)
            }
            val modelSpinner = Spinner(this).apply {
                minimumHeight = UiKit.dp(this@WorkRoutingActivity, 48)
            }
            card.addView(providerSpinner)
            card.addView(modelSpinner)

            val baseOffset = 2 + localEngineLabels.size

            fun modelsFor(pos: Int): List<String> {
                if (pos < 2) return emptyList()
                if (isVoice && pos < baseOffset) {
                    val engine = installedLocalEngines[pos - 2]
                    return listOf(engine.defaultModelFileName)
                }
                val p = profiles.getOrNull(pos - baseOffset) ?: return emptyList()
                return listOf("Provider default") + p.models.map { it.id }
            }

            fun fillModels(pos: Int, selectedModel: String) {
                val items = modelsFor(pos)
                modelSpinner.visibility = if (items.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
                modelSpinner.adapter = ArrayAdapter(this@WorkRoutingActivity, android.R.layout.simple_spinner_dropdown_item, items)
                modelSpinner.setSelection(items.indexOf(selectedModel).coerceAtLeast(0))
            }

            fun save() {
                val pos = providerSpinner.selectedItemPosition
                when {
                    pos == 0 -> routes.clear(category)
                    pos == 1 -> routes.set(category, Route(Route.GEMINI))
                    isVoice && pos < baseOffset -> {
                        val engine = installedLocalEngines[pos - 2]
                        routes.set(category, Route(Route.OFFLINE_TTS, engine.id))
                    }
                    else -> {
                        val p = profiles.getOrNull(pos - baseOffset) ?: return
                        val m = modelSpinner.selectedItem as? String
                        routes.set(category, Route(p.id, if (m == null || m == "Provider default") "" else m))
                    }
                }
            }

            val current = routes.get(category)
            val initial = when {
                current == null -> 0
                current.isGemini -> 1
                isVoice && current.isOfflineTts -> {
                    val idx = installedLocalEngines.indexOfFirst { it.id == current.modelId }
                    if (idx >= 0) 2 + idx else 0
                }
                current.providerId.isBlank() -> 0
                else -> {
                    val idx = profiles.indexOfFirst { it.id == current.providerId }
                    if (idx >= 0) baseOffset + idx else 0
                }
            }
            providerSpinner.setSelection(initial)
            fillModels(initial, current?.modelId.orEmpty())

            var ready = false
            providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                    if (!ready) return
                    fillModels(pos, "")
                    save()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                    if (ready) save()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            card.post { ready = true }
            body.addView(card)
        }
    }
}
