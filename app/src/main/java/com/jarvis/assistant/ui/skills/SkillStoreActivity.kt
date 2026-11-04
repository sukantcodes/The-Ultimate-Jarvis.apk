package com.jarvis.assistant.ui.skills

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.R
import com.jarvis.assistant.skills.CatalogEntry
import com.jarvis.assistant.skills.Skill
import com.jarvis.assistant.skills.SkillParser
import com.jarvis.assistant.skills.SkillStore
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings > Skill Store. Two cards:
 *  - Installed skills: every skill with an on/off switch. Enabled ones are offered to the AI.
 *  - Skill store: the downloadable skills of a GitHub store, with Install / Update buttons and a Refresh button.
 */
class SkillStoreActivity : AppCompatActivity() {

    private val store by lazy { SkillStore.get(applicationContext) }
    private lateinit var root: LinearLayout
    private var catalog: List<CatalogEntry> = emptyList()
    private var catalogStatus: String = "Tap Refresh to see what is available."
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this).apply { isFillViewport = true }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(40))
        }
        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(scroll)
        ThemeManager.applyDynamicBackground(scroll)

        render()
        refreshCatalog()
    }

    // ---- Drawing -----------------------------------------------------------------------------

    private fun render() {
        root.removeAllViews()

        root.addView(text("Skill Store", 24f, primary = true, bold = true))
        root.addView(text("Skills are playbooks that teach Jarvis how to do a job properly. Enabled skills are offered to the AI.", 13f, primary = false).apply {
            setPadding(0, dp(4), 0, dp(16))
        })

        val skills = store.installed()
        val enabled = skills.count { store.isEnabled(it.name) }

        val installedCard = card()
        installedCard.addView(text("Installed skills", 18f, primary = true, bold = true))
        installedCard.addView(text("$enabled/${skills.size} enabled. Changes apply from the next conversation.", 12f, primary = false).apply {
            setPadding(0, dp(2), 0, dp(8))
        })
        if (skills.isEmpty()) installedCard.addView(text("No skills installed yet.", 14f, primary = false))
        skills.forEachIndexed { index, skill ->
            if (index > 0) installedCard.addView(divider())
            installedCard.addView(installedRow(skill))
        }
        root.addView(installedCard)

        val storeCard = card()
        storeCard.addView(text("Skill store", 18f, primary = true, bold = true))
        storeCard.addView(text("github.com/${store.repo}", 12f, primary = false).apply { setPadding(0, dp(2), 0, dp(8)) })

        val installedByName = skills.associateBy { it.name }
        if (catalog.any { installedByName[it.name]?.builtIn == true }) {
            storeCard.addView(text("Skills marked Built in already ship with Jarvis, adapted to Jarvis's own tools, so they work offline and are not replaced by the store copy.", 12f, primary = false).apply {
                setPadding(0, 0, 0, dp(6))
            })
        }
        catalog.forEachIndexed { index, entry ->
            if (index > 0) storeCard.addView(divider())
            storeCard.addView(storeRow(entry, installedByName[entry.name]))
        }

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        footer.addView(text(if (loading) "Loading..." else catalogStatus, 13f, primary = false).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        footer.addView(button("Source") { askForSource() })
        footer.addView(button(if (loading) "..." else "Refresh") { if (!loading) refreshCatalog() })
        storeCard.addView(footer)
        root.addView(storeCard)
    }

    private fun installedRow(skill: Skill): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val title = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        title.addView(text(skill.name, 16f, primary = true, bold = true))
        title.addView(badge(if (skill.builtIn) "built-in" else "store"))
        info.addView(title)
        info.addView(text(skill.description, 13f, primary = false).apply { setPadding(0, dp(2), dp(8), 0) })
        if (!skill.builtIn) {
            info.addView(text("Remove", 12f, primary = true, bold = true).apply {
                setPadding(0, dp(6), dp(8), dp(2))
                setOnClickListener {
                    MaterialAlertDialogBuilder(this@SkillStoreActivity)
                        .setTitle("Remove ${skill.name}?")
                        .setMessage("You can install it again from the store.")
                        .setPositiveButton("Remove") { _, _ -> store.uninstall(skill.name); render() }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            })
        }
        row.addView(info)

        val toggle = SwitchCompat(this).apply {
            isChecked = store.isEnabled(skill.name)
            contentDescription = "Enable ${skill.name}"
            setOnCheckedChangeListener { _, checked ->
                store.setEnabled(skill.name, checked)
                render()
            }
        }
        row.addView(toggle)
        return row
    }

    private fun storeRow(entry: CatalogEntry, have: Skill?): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        info.addView(text(entry.name, 16f, primary = true, bold = true))
        info.addView(text(entry.description, 13f, primary = false).apply { setPadding(0, dp(2), dp(8), 0) })
        row.addView(info)

        val label = when {
            have?.builtIn == true -> "Built in"
            have == null -> "Install"
            have.updated != entry.updated && entry.updated.isNotBlank() -> "Update"
            else -> "Installed"
        }
        val action = button(label) {
            if (label == "Installed" || label == "Built in") return@button
            install(entry)
        }
        action.isEnabled = label != "Installed" && label != "Built in" && !loading
        action.alpha = if (action.isEnabled) 1f else 0.55f
        row.addView(action)
        return row
    }

    // ---- Actions -----------------------------------------------------------------------------

    private fun refreshCatalog() {
        loading = true
        render()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { store.fetchCatalog() } }
            loading = false
            result.onSuccess {
                catalog = it
                catalogStatus = "${it.size} skills available"
            }.onFailure {
                catalogStatus = "Could not reach the store. Check your internet and tap Refresh."
            }
            render()
        }
    }

    private fun install(entry: CatalogEntry) {
        loading = true
        render()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { store.install(entry) }
            loading = false
            result.onSuccess {
                Toast.makeText(this@SkillStoreActivity, "${it.name} installed", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this@SkillStoreActivity, it.message ?: "Install failed", Toast.LENGTH_LONG).show()
            }
            render()
        }
    }

    private fun askForSource() {
        val input = EditText(this).apply {
            setText(store.repo)
            hint = "owner/repository"
            setSingleLine()
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Skill store source")
            .setMessage("A GitHub repository that has index.json and a skills/ folder. Only use stores you trust: installed skills give instructions to the AI that controls your phone.")
            .setView(input)
            .setPositiveButton("Use this") { _, _ ->
                val repo = input.text.toString().trim()
                if (SkillParser.isValidRepo(repo)) {
                    store.repo = repo
                    catalog = emptyList()
                    catalogStatus = "Tap Refresh to see what is available."
                    refreshCatalog()
                } else {
                    Toast.makeText(this, "Use the form owner/repository", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("Default") { _, _ ->
                store.repo = SkillStore.DEFAULT_REPO
                catalog = emptyList()
                refreshCatalog()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- View helpers ------------------------------------------------------------------------

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun themeColor(attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        return if (theme.resolveAttribute(attr, tv, true)) tv.data else fallback
    }

    private fun text(value: String, sizeSp: Float, primary: Boolean, bold: Boolean = false) = TextView(this).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(themeColor(if (primary) R.attr.themeTextPrimary else R.attr.themeTextMuted, if (primary) 0xFFFFFFFF.toInt() else 0xFF9AA4B2.toInt()))
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        background = GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            setColor(themeColor(R.attr.themeCardBg, 0x33FFFFFF))
            setStroke(dp(1), themeColor(R.attr.themeCardBorder, 0x33FFFFFF))
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(16)
        }
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(themeColor(R.attr.themeCardBorder, 0x22FFFFFF))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    }

    private fun badge(label: String) = TextView(this).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        setTextColor(themeColor(R.attr.themeTextMuted, 0xFF9AA4B2.toInt()))
        setPadding(dp(8), dp(2), dp(8), dp(2))
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(themeColor(R.attr.themeChipBg, 0x22FFFFFF))
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(8)
        }
    }

    private fun button(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setTextColor(themeColor(R.attr.themeTextPrimary, 0xFFFFFFFF.toInt()))
        setPadding(dp(16), dp(8), dp(16), dp(8))
        background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(themeColor(R.attr.themeChipBg, 0x22FFFFFF))
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(8)
        }
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }
}
