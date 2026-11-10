package com.jarvis.assistant.ui.settings

import android.os.Bundle
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import androidx.appcompat.app.AppCompatActivity
import com.jarvis.assistant.provider.ProviderRuntime
import com.jarvis.assistant.provider.Route
import com.jarvis.assistant.provider.WorkCategory
import com.jarvis.assistant.util.ThemeManager

/**
 * Work Routing: pick which provider and model handles each kind of work (chat, file analysis, images, coding,
 * voice, task planning, task execution, web research, GitHub, documents). "Follow active provider" keeps the
 * existing behaviour; Gemini is a normal choice, never a silent default.
 */
class WorkRoutingActivity : AppCompatActivity() {

    private val providers by lazy { ProviderRuntime.manager(applicationContext) }
    private val routes by lazy { ProviderRuntime.routing(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ThemeManager.applyDynamicBackground(root)
        val bar = UiKit.row(this).apply { setPadding(UiKit.dp(this@WorkRoutingActivity, 8), UiKit.dp(this@WorkRoutingActivity, 12), UiKit.dp(this@WorkRoutingActivity, 16), UiKit.dp(this@WorkRoutingActivity, 8)) }
        bar.addView(UiKit.button(this, "‹ Back") { finish() })
        bar.addView(UiKit.text(this, "  Work Routing", 20f, bold = true))
        root.addView(bar)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p = UiKit.dp(this@WorkRoutingActivity, 16); setPadding(p, 0, p, p) }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        body.addView(UiKit.text(this, "Choose which provider handles each kind of work. Nothing is forced through Gemini: \"Follow active provider\" uses the custom provider you activated, and Gemini is used only where you pick it.", 13f, muted = true).apply {
            setPadding(0, 0, 0, UiKit.dp(this@WorkRoutingActivity, 12))
        })

        val profiles = providers.profiles()
        // Option 0 = follow active, 1 = Gemini (built-in), then each custom provider.
        val labels = listOf("Follow active provider", "Gemini (built-in)") + profiles.map { it.displayName }
        WorkCategory.values().forEach { category ->
            val card = UiKit.card(this)
            card.addView(UiKit.text(this, category.label, 15f, bold = true))
            val providerSpinner = Spinner(this).apply { adapter = ArrayAdapter(this@WorkRoutingActivity, android.R.layout.simple_spinner_dropdown_item, labels) }
            val modelSpinner = Spinner(this)
            card.addView(providerSpinner); card.addView(modelSpinner)

            fun modelsFor(pos: Int): List<String> {
                val p = profiles.getOrNull(pos - 2) ?: return emptyList()
                return listOf("Provider default") + p.models.map { it.id }
            }
            fun fillModels(pos: Int, selectedModel: String) {
                val items = modelsFor(pos)
                modelSpinner.visibility = if (items.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
                modelSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
                modelSpinner.setSelection(items.indexOf(selectedModel).coerceAtLeast(0))
            }
            fun save() {
                val pos = providerSpinner.selectedItemPosition
                when (pos) {
                    0 -> routes.clear(category)
                    1 -> routes.set(category, Route(Route.GEMINI))
                    else -> {
                        val p = profiles[pos - 2]
                        val m = modelSpinner.selectedItem as? String
                        routes.set(category, Route(p.id, if (m == null || m == "Provider default") "" else m))
                    }
                }
            }

            val current = routes.get(category)
            val initial = when {
                current == null -> 0
                current.isGemini -> 1
                current.providerId.isBlank() -> 0
                else -> (profiles.indexOfFirst { it.id == current.providerId }.takeIf { it >= 0 }?.plus(2)) ?: 0
            }
            providerSpinner.setSelection(initial)
            fillModels(initial, current?.modelId.orEmpty())
            var ready = false
            providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                    if (!ready) return
                    fillModels(pos, ""); save()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) { if (ready) save() }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            card.post { ready = true }
            body.addView(card)
        }
    }
}
