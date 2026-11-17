package com.jarvis.assistant.ui.jarvishome

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.home.HomeController
import com.jarvis.assistant.home.HomeRegistry
import com.jarvis.assistant.home.HomeServices
import com.jarvis.assistant.home.automation.AutomationEngine
import com.jarvis.assistant.home.automation.AutomationScheduler
import com.jarvis.assistant.home.automation.AutomationStore
import com.jarvis.assistant.ui.glass.LiquidGlass
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class HomePage(val title: String) {
    OVERVIEW("Overview"), FAVORITES("Favorites"), ALL_DEVICES("All Devices"), ROOMS("Rooms"),
    BLUETOOTH("Bluetooth Devices"), WIFI("Wi-Fi Devices"), DISCOVERY("Device Discovery"),
    AUTOMATIONS("Automations"), SCENES("Smart Scenes"), VOICE("Voice Control"),
    ACTIVITY("Device Activity"), SETTINGS("Home Settings")
}

/** What every page needs from the screen it lives on. */
interface HomeHost {
    val activity: AppCompatActivity
    val ui: HomeUi
    val registry: HomeRegistry
    val controller: HomeController
    val automationStore: AutomationStore
    val engine: AutomationEngine
    fun navigate(page: HomePage)
    fun rerender()
    fun toast(message: String)
    /** Runs [work] off the main thread, then [done] on it, only if the screen is still alive. */
    fun <T> background(work: () -> T, done: (T) -> Unit)
    fun requirePermissions(permissions: Array<String>, onResult: (Boolean) -> Unit)
}

/**
 * JARVIS Home: the smart-device control centre. One screen with twelve pages, on the app's Liquid Glass backdrop.
 * Every number and state shown comes from the registry and the devices themselves; nothing is demo data.
 */
class HomeActivity : AppCompatActivity(), HomeHost {

    override val activity: AppCompatActivity get() = this
    override val ui by lazy { HomeUi(this) }
    override val registry: HomeRegistry by lazy { HomeServices.registry(applicationContext) }
    override val controller: HomeController by lazy { HomeServices.controller(applicationContext) }
    override val automationStore: AutomationStore by lazy { HomeServices.automationStore(applicationContext) }
    override val engine: AutomationEngine by lazy { HomeServices.engine(applicationContext) }

    private lateinit var root: LinearLayout
    private lateinit var tabs: LinearLayout
    private lateinit var content: LinearLayout
    private var page: HomePage = HomePage.OVERVIEW
    private var pendingPermissionResult: ((Boolean) -> Unit)? = null

    private val changeListener: () -> Unit = { if (::content.isInitialized) content.post { if (!isFinishing && !isDestroyed) renderContent() } }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val cb = pendingPermissionResult
        pendingPermissionResult = null
        cb?.invoke(result.values.all { it })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        page = savedInstanceState?.getString(STATE_PAGE)?.let { runCatching { HomePage.valueOf(it) }.getOrNull() } ?: HomePage.OVERVIEW
        HomeServices.warm(applicationContext)

        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(32)) }
        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(scroll)
        LiquidGlass.installBackdrop(scroll, withBase = true)
        buildChrome()
        renderContent()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PAGE, page.name)
    }

    override fun onStart() {
        super.onStart()
        registry.addListener(changeListener)
        engine.addListener(changeListener)
        renderContent()
    }

    override fun onStop() {
        registry.removeListener(changeListener)
        engine.removeListener(changeListener)
        super.onStop()
    }

    private fun buildChrome() {
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "‹"; gravity = Gravity.CENTER
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 30f)
            setTextColor(ui.primary)
            contentDescription = "Back"
            layoutParams = LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)).apply { marginEnd = ui.dp(8) }
            isClickable = true; isFocusable = true
            setOnClickListener { finish() }
        })
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(ui.text("JARVIS Home", 25f, bold = true))
            addView(ui.text("Your connected world", 12f, ui.muted))
        })
        header.addView(ui.button("＋ Add", filled = true) { navigate(HomePage.DISCOVERY) })
        root.addView(header)
        root.addView(ui.space(12))

        tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(tabs) })
        root.addView(ui.space(12))
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content)
    }

    private fun renderTabs() {
        tabs.removeAllViews()
        HomePage.values().forEach { p -> tabs.addView(ui.chip(p.title, p == page) { navigate(p) }) }
        tabs.post { (tabs.parent as? HorizontalScrollView)?.let { sv -> tabs.getChildAt(page.ordinal)?.let { sv.smoothScrollTo((it.left - ui.dp(24)).coerceAtLeast(0), 0) } } }
    }

    private fun renderContent() {
        renderTabs()
        content.removeAllViews()
        when (page) {
            HomePage.OVERVIEW -> DevicePages.overview(this, content)
            HomePage.FAVORITES -> DevicePages.favorites(this, content)
            HomePage.ALL_DEVICES -> DevicePages.all(this, content)
            HomePage.ROOMS -> DevicePages.rooms(this, content)
            HomePage.BLUETOOTH -> DevicePages.byConnection(this, content, bluetooth = true)
            HomePage.WIFI -> DevicePages.byConnection(this, content, bluetooth = false)
            HomePage.DISCOVERY -> DiscoveryPage.render(this, content)
            HomePage.AUTOMATIONS -> AutomationPages.render(this, content, scenes = false)
            HomePage.SCENES -> AutomationPages.render(this, content, scenes = true)
            HomePage.VOICE -> MiscPages.voice(this, content)
            HomePage.ACTIVITY -> MiscPages.activity(this, content)
            HomePage.SETTINGS -> MiscPages.settings(this, content)
        }
    }

    // ---- HomeHost ----------------------------------------------------------------------------------

    override fun navigate(page: HomePage) {
        if (this.page != page) DiscoveryPage.stopScans(this)
        this.page = page
        renderContent()
    }

    override fun rerender() = renderContent()

    override fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }

    override fun <T> background(work: () -> T, done: (T) -> Unit) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { work() }
            if (!isFinishing && !isDestroyed) done(result)
        }
    }

    override fun requirePermissions(permissions: Array<String>, onResult: (Boolean) -> Unit) {
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) { onResult(true); return }
        pendingPermissionResult = onResult
        permissionLauncher.launch(missing.toTypedArray())
    }

    override fun onPause() {
        DiscoveryPage.stopScans(this)
        super.onPause()
    }

    override fun onDestroy() {
        DiscoveryPage.release()
        super.onDestroy()
    }

    /** Re-plans time routines after the user changes them. */
    fun reschedule() { AutomationScheduler.rescheduleAll(applicationContext) }

    companion object { private const val STATE_PAGE = "home_page" }
}

internal fun HomeHost.promptText(title: String, initial: String, hint: String, onOk: (String) -> Unit) {
    val input = android.widget.EditText(activity).apply { setText(initial); this.hint = hint; setSingleLine(true); setTextColor(ui.primary); setHintTextColor(ui.muted) }
    val holder = LinearLayout(activity).apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0); addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
    MaterialAlertDialogBuilder(activity, com.jarvis.assistant.R.style.ThemeOverlay_Jarvis_MaterialDialog)
        .setTitle(title).setView(holder)
        .setPositiveButton("OK") { _, _ -> onOk(input.text.toString().trim()) }
        .setNegativeButton("Cancel", null).show()
}

internal fun HomeHost.chooseOne(title: String, labels: List<String>, current: Int = -1, onPick: (Int) -> Unit) {
    MaterialAlertDialogBuilder(activity, com.jarvis.assistant.R.style.ThemeOverlay_Jarvis_MaterialDialog)
        .setTitle(title)
        .setSingleChoiceItems(labels.toTypedArray(), current) { d, which -> d.dismiss(); onPick(which) }
        .setNegativeButton("Cancel", null).show()
}

internal fun HomeHost.confirm(title: String, message: String, positive: String, onYes: () -> Unit) {
    MaterialAlertDialogBuilder(activity, com.jarvis.assistant.R.style.ThemeOverlay_Jarvis_MaterialDialog)
        .setTitle(title).setMessage(message)
        .setPositiveButton(positive) { _, _ -> onYes() }
        .setNegativeButton("Cancel", null).show()
}
