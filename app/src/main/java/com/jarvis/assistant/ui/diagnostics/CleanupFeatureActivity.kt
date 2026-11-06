package com.jarvis.assistant.ui.diagnostics

import android.Manifest
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.R
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One guided flow for every cleaning tool:
 * intro, scan with live counters, review with thumbnails, clean/boost with animation,
 * and a result screen that only shows numbers that were really measured.
 */
class CleanupFeatureActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FEATURE = "cleanup_feature"
        private const val MIN_ANIMATION_MS = 2400L
    }

    private enum class Phase { IDLE, SCANNING, REVIEW, WORKING, DONE, APPS }

    private lateinit var ui: Ui
    private val feature by lazy { intent.getIntExtra(EXTRA_FEATURE, 0).coerceIn(0, 7) }

    private lateinit var root: LinearLayout
    private lateinit var fxBox: FrameLayout
    private lateinit var fx: FxView
    private lateinit var bigValue: TextView
    private lateinit var headline: TextView
    private lateinit var subline: TextView
    private lateinit var content: LinearLayout

    private var phase = Phase.IDLE
    private val cancel = AtomicBoolean(false)

    private var allItems: List<CleanItem> = emptyList()
    private var filter: String? = null
    private var itemAdapter: ItemAdapter? = null
    private var cleanButton: TextView? = null
    private var selectToggle: TextView? = null
    private var chipRow: LinearLayout? = null
    private var boostToo = true
    private var boostCount = 0
    private var largeMb = 50
    private var afterAccess: ((Node?) -> Unit)? = null

    private var scanFilesView: TextView? = null
    private var scanFoundView: TextView? = null
    private var workView: TextView? = null

    // Apps
    private var appAdapter: AppAdapter? = null
    private var uninstallButton: TextView? = null
    private val uninstallQueue = ArrayList<String>()
    private var uninstalling: String? = null

    private val names = listOf(
        "Junk Clean", "RAM Boost", "Battery Saver", "Large Files", "WhatsApp Cleaner",
        "Photos Cleaner", "App Manager", "Full Phone Boost"
    )
    private val descriptions = listOf(
        "Clear leftover cache, temp files and empty folders from your phone's storage.",
        "Free up memory by stopping apps that are running in the background.",
        "Cut battery drain by stopping background apps, and jump to Android's battery controls.",
        "Find the biggest files taking up space and remove the ones you don't need.",
        "Clean WhatsApp media only. Your chats and backups are never touched.",
        "Find duplicate photos, screenshots and your largest pictures.",
        "See your installed apps by size and uninstall several at once.",
        "Clean junk, stop background apps and free memory in one guided pass."
    )

    // ---- Result launchers -------------------------------------------------------------------------

    private val storageSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { onAccessResult() }
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onAccessResult() }
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> onFolderPicked(uri) }
    private val uninstallLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { onUninstallReturned() }

    // ---- Setup ------------------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        buildScaffold()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })
        if (feature == CleanerActions.F_APPS) showApps() else showIdle()
    }

    override fun onResume() {
        super.onResume()
        if (phase == Phase.APPS) loadApps()
    }

    override fun onDestroy() {
        cancel.set(true)
        super.onDestroy()
    }

    private fun featureColor(): Int = when (feature) {
        CleanerActions.F_BOOST -> ui.p.accent2
        CleanerActions.F_BATTERY -> ui.p.success
        CleanerActions.F_LARGE -> ui.p.warning
        CleanerActions.F_WHATSAPP -> Color.rgb(37, 211, 102)
        CleanerActions.F_PHOTOS -> Color.rgb(244, 114, 182)
        CleanerActions.F_APPS -> Color.rgb(251, 146, 60)
        else -> ui.p.accent
    }

    private fun buildScaffold() {
        val p = ui.p
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(16))
        }
        setContentView(root)
        ThemeManager.applyDynamicBackground(root)

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = ui.lp().apply { bottomMargin = ui.dp(6) }
        }
        bar.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_diag_back)
            imageTintList = android.content.res.ColorStateList.valueOf(p.text)
            contentDescription = "Back"
            isClickable = true
            isFocusable = true
            val pad = ui.dp(11)
            setPadding(pad, pad, pad, pad)
            background = ui.ripple(GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(p.card)
                setStroke(ui.dp(1), p.border)
            }, 24)
            layoutParams = LinearLayout.LayoutParams(ui.dp(46), ui.dp(46))
            setOnClickListener { handleBack() }
        })
        bar.addView(ui.label(names[feature], 18f, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ui.dp(14) }
        })
        root.addView(bar)

        fx = FxView(this).apply {
            accent = featureColor()
            accent2 = if (featureColor() == p.accent2) p.accent else p.accent2
            success = p.success
        }
        fxBox = FrameLayout(this).apply {
            layoutParams = ui.lp(h = ui.dp(250))
        }
        fxBox.addView(fx, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(fxBox)

        bigValue = ui.label("", 34f, light = true).apply { gravity = Gravity.CENTER }
        headline = ui.label("", 19f, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(2), 0, 0) }
        subline = ui.label("", 13f, p.muted).apply { gravity = Gravity.CENTER; setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(12)) }
        root.addView(bigValue, ui.lp())
        root.addView(headline, ui.lp())
        root.addView(subline, ui.lp())

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(content)
    }

    private fun setFxHeight(heightDp: Int) {
        val lp = fxBox.layoutParams
        val target = ui.dp(heightDp)
        if (lp.height == target) return
        ValueAnimator.ofInt(lp.height, target).apply {
            duration = 320L
            addUpdateListener {
                lp.height = it.animatedValue as Int
                fxBox.layoutParams = lp
            }
            start()
        }
    }

    private fun setTexts(big: String, title: String, sub: String) {
        bigValue.text = big
        bigValue.visibility = if (big.isEmpty()) View.GONE else View.VISIBLE
        headline.text = title
        subline.text = sub
    }

    /** Replaces the lower part of the screen with a gentle fade-and-rise. */
    private fun swapContent(build: (LinearLayout) -> Unit) {
        content.removeAllViews()
        build(content)
        content.alpha = 0f
        content.translationY = ui.dp(16).toFloat()
        content.animate().alpha(1f).translationY(0f).setDuration(260L).start()
    }

    private fun handleBack() {
        when (phase) {
            Phase.SCANNING -> { cancel.set(true); showIdle() }
            Phase.WORKING -> {
                cancel.set(true)
                Toast.makeText(this, "Stopping...", Toast.LENGTH_SHORT).show()
            }
            Phase.REVIEW -> showIdle()
            else -> finish()
        }
    }

    // ---- IDLE -------------------------------------------------------------------------------------

    private fun showIdle() {
        phase = Phase.IDLE
        cancel.set(false)
        allItems = emptyList()
        filter = null
        fxBox.visibility = View.VISIBLE
        fx.progress = -1f
        setFxHeight(250)
        val battery = CleanerEngine.battery(this)
        if (feature == CleanerActions.F_BATTERY && battery != null && battery.percent >= 0) {
            fx.level = battery.percent / 100f
            fx.mode = FxView.Mode.BATTERY
        } else {
            fx.mode = FxView.Mode.IDLE
        }
        val big = when (feature) {
            CleanerActions.F_BOOST -> ui.formatBytes(CleanerEngine.memory(this).available) + " free"
            CleanerActions.F_BATTERY -> if (battery != null && battery.percent >= 0) "${battery.percent}%" else ""
            else -> ""
        }
        setTexts(big, names[feature], descriptions[feature])

        swapContent { host ->
            val scroll = ScrollView(this).apply {
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                addView(idleBody())
            }
            host.addView(scroll)
            val cta = ui.primaryButton(ctaLabel()).apply { setOnClickListener { beginFeature() } }
            host.addView(cta, ui.lp().apply { topMargin = ui.dp(10) })
        }
    }

    private fun ctaLabel() = when (feature) {
        CleanerActions.F_JUNK -> "Scan for junk"
        CleanerActions.F_BOOST -> "Boost now"
        CleanerActions.F_BATTERY -> "Stop background apps"
        CleanerActions.F_LARGE -> "Find large files"
        CleanerActions.F_WHATSAPP -> "Scan WhatsApp media"
        CleanerActions.F_PHOTOS -> "Find duplicates and screenshots"
        else -> "Start full boost"
    }

    private fun bulletCard(lines: List<String>): LinearLayout {
        val card = ui.card(16)
        lines.forEachIndexed { index, line ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, if (index == 0) 0 else ui.dp(9), 0, 0)
            }
            row.addView(View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(featureColor()) }
                layoutParams = LinearLayout.LayoutParams(ui.dp(7), ui.dp(7)).apply { topMargin = ui.dp(6); marginEnd = ui.dp(12) }
            })
            row.addView(ui.label(line, 13f, ui.p.text))
            card.addView(row)
        }
        return card
    }

    private fun idleBody(): View {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        when (feature) {
            CleanerActions.F_JUNK -> {
                column.addView(bulletCard(listOf(
                    "Cache and temp folders left behind by apps",
                    "Thumbnail caches, temp files and logs",
                    "Empty folders, plus JARVIS' own cache",
                    "Old installers, partial downloads and gallery trash are listed too, unticked, for you to decide"
                )))
                column.addView(ui.label("Android keeps other apps' private caches locked. To clear those, use Android's own cleaner.", 12f, ui.p.muted).apply {
                    setPadding(ui.dp(6), 0, ui.dp(6), ui.dp(10))
                })
                column.addView(ui.ghostButton("Open Android's cache cleaner").apply {
                    setOnClickListener { openAndroidCacheCleaner() }
                }, ui.lp())
            }
            CleanerActions.F_BOOST -> {
                column.addView(bulletCard(listOf(
                    "Asks Android to end background activity of apps you aren't using",
                    "Skips your keyboard, launcher, phone and messages apps",
                    "Measures memory before and after. The result is real, never an estimate",
                    "Apps you protect are never stopped"
                )))
                column.addView(ui.ghostButton("Protected apps (${CleanerEngine.protectedApps(this).size})").apply {
                    setOnClickListener { ui.showProtectedApps(this@CleanupFeatureActivity) { showIdle() } }
                }, ui.lp())
                column.addView(ui.label("Android may restart some apps on its own. That is normal and keeps notifications working.", 12f, ui.p.muted).apply {
                    setPadding(ui.dp(6), ui.dp(10), ui.dp(6), 0)
                })
            }
            CleanerActions.F_BATTERY -> {
                val battery = CleanerEngine.battery(this)
                val facts = ArrayList<String>()
                if (battery != null) {
                    facts.add("Charge: ${if (battery.percent >= 0) "${battery.percent}%" else "unavailable"}, ${if (battery.charging) "charging" else "on battery"}")
                    battery.temperatureC?.let { facts.add(String.format(java.util.Locale.getDefault(), "Temperature: %.1f °C", it)) }
                }
                facts.add("Stopping background apps reduces drain and heat")
                column.addView(bulletCard(facts))
                column.addView(ui.ghostButton("Battery Saver settings").apply {
                    setOnClickListener { openSystem(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)) }
                }, ui.lp().apply { bottomMargin = ui.dp(8) })
                column.addView(ui.ghostButton("Battery usage by app").apply {
                    setOnClickListener { openSystem(Intent(Intent.ACTION_POWER_USAGE_SUMMARY)) }
                }, ui.lp().apply { bottomMargin = ui.dp(8) })
                column.addView(ui.ghostButton("Battery optimization").apply {
                    setOnClickListener { openSystem(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                }, ui.lp())
            }
            CleanerActions.F_LARGE -> {
                column.addView(bulletCard(listOf(
                    "Lists the biggest files first, across all your storage",
                    "Nothing is selected until you tick it"
                )))
                column.addView(ui.label("MINIMUM SIZE", 11f, ui.p.muted, bold = true).apply {
                    letterSpacing = 0.14f
                    setPadding(ui.dp(4), ui.dp(4), 0, ui.dp(8))
                })
                val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                val sizes = listOf(50, 100, 250, 500)
                fun draw() {
                    chips.removeAllViews()
                    for (mb in sizes) {
                        chips.addView(ui.chip("$mb MB", mb == largeMb).apply {
                            setOnClickListener { largeMb = mb; draw() }
                        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            marginEnd = ui.dp(8)
                        })
                    }
                }
                draw()
                column.addView(chips)
            }
            CleanerActions.F_WHATSAPP -> column.addView(bulletCard(listOf(
                "Images, videos, voice notes, documents, GIFs, stickers and status cache",
                "Chat history and backups are never read or listed",
                "Nothing is selected until you choose it"
            )))
            CleanerActions.F_PHOTOS -> column.addView(bulletCard(listOf(
                "Finds identical photos by content and keeps the oldest copy",
                "Lists screenshots and your largest photos",
                "Duplicates are suggested; everything else is your call"
            )))
            else -> {
                column.addView(bulletCard(listOf(
                    "1  Scan junk, caches and empty folders",
                    "2  You review what will be removed",
                    "3  Stop background apps and free memory",
                    "4  See exactly what was freed, measured on your phone"
                )))
            }
        }
        return column
    }

    private fun beginFeature() {
        when (feature) {
            CleanerActions.F_BOOST, CleanerActions.F_BATTERY -> runBoostOnly()
            CleanerActions.F_JUNK -> ensureAccess(allowSkip = true) { runScan(it) }
            CleanerActions.F_FULL -> ensureAccess(allowSkip = true) { runScan(it) }
            CleanerActions.F_WHATSAPP -> ensureAccess(allowSkip = false) { runScan(it) }
            else -> ensureAccess(allowSkip = false) { runScan(it) }
        }
    }

    // ---- Storage access ---------------------------------------------------------------------------

    private fun ensureAccess(allowSkip: Boolean, then: (Node?) -> Unit) {
        if (CleanerEngine.hasFullStorageAccess(this)) {
            then(CleanerEngine.rootNode())
            return
        }
        afterAccess = then
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle("Allow storage access")
            .setMessage(
                "To look through your phone's storage, Android needs your permission. JARVIS only lists matching files " +
                    "and asks before deleting anything. Other apps' private data stays protected."
            )
            .setPositiveButton("Allow access") { _, _ -> requestFullAccess() }
            .setNeutralButton("Choose a folder") { _, _ -> folderPicker.launch(null) }
            .setOnCancelListener { afterAccess = null }
        if (allowSkip) {
            builder.setNegativeButton("Skip storage") { _, _ ->
                val next = afterAccess
                afterAccess = null
                next?.invoke(null)
            }
        } else {
            builder.setNegativeButton("Cancel") { _, _ -> afterAccess = null }
        }
        builder.show()
    }

    private fun requestFullAccess() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                try {
                    storageSettings.launch(
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                    )
                } catch (_: Exception) {
                    try {
                        storageSettings.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    } catch (_: Exception) {
                        Toast.makeText(this, "Open Settings > Apps > Special app access > All files access > JARVIS", Toast.LENGTH_LONG).show()
                    }
                }
            }
            Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> folderPicker.launch(null)
            else -> permissionLauncher.launch(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            )
        }
    }

    private fun onAccessResult() {
        val next = afterAccess
        if (CleanerEngine.hasFullStorageAccess(this)) {
            afterAccess = null
            next?.invoke(CleanerEngine.rootNode())
        } else {
            Toast.makeText(this, "Storage access was not enabled.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onFolderPicked(uri: Uri?) {
        if (uri == null) return
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: SecurityException) { }
        val doc = DocumentFile.fromTreeUri(this, uri)
        if (doc == null || !doc.isDirectory) {
            Toast.makeText(this, "That folder can't be opened.", Toast.LENGTH_SHORT).show()
            return
        }
        val next = afterAccess
        afterAccess = null
        next?.invoke(DocNode(doc, ""))
    }

    // ---- SCAN -------------------------------------------------------------------------------------

    private fun runScan(root: Node?) {
        phase = Phase.SCANNING
        cancel.set(false)
        fx.mode = FxView.Mode.SCAN
        fx.progress = -1f
        fxBox.visibility = View.VISIBLE
        setFxHeight(250)
        setTexts("", "Scanning...", "Looking through your storage. You can stop at any time.")
        swapContent { host ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val files = statTile("FILES CHECKED", "0", first = true)
            val found = statTile("FOUND", "0 B", first = false)
            scanFilesView = files.second
            scanFoundView = found.second
            row.addView(files.first)
            row.addView(found.first)
            host.addView(row, ui.lp())
            host.addView(ui.ghostButton("Stop").apply {
                setOnClickListener { cancel.set(true); showIdle() }
            }, ui.lp().apply { topMargin = ui.dp(14) })
        }

        val context = applicationContext
        lifecycleScope.launch {
            val result: List<CleanItem>? = try {
                withContext(Dispatchers.IO) {
                    val progress: ProgressCallback = { files, bytes, folder ->
                        runOnUiThread { if (phase == Phase.SCANNING) updateScan(files, bytes, folder) }
                    }
                    if (feature == CleanerActions.F_FULL) boostCount = CleanerEngine.boostCandidates(context).size
                    when (feature) {
                        CleanerActions.F_JUNK, CleanerActions.F_FULL -> CleanerEngine.scanJunk(context, root, cancel, progress)
                        CleanerActions.F_LARGE -> CleanerEngine.scanLarge(root ?: CleanerEngine.rootNode(), largeMb * 1024L * 1024L, cancel, progress)
                        CleanerActions.F_WHATSAPP -> CleanerEngine.scanWhatsApp(root, cancel, progress)
                        else -> CleanerEngine.scanPhotos(context, root ?: CleanerEngine.rootNode(), cancel, progress)
                    }
                }
            } catch (e: Exception) {
                null
            }
            if (cancel.get() || phase != Phase.SCANNING) return@launch
            if (result == null) {
                Toast.makeText(this@CleanupFeatureActivity, "The scan could not finish. Please try again.", Toast.LENGTH_LONG).show()
                showIdle()
                return@launch
            }
            allItems = result
            if (result.isEmpty() && feature != CleanerActions.F_FULL) {
                showDone(null, null, "Nothing to clean", "No matching files were found in the storage JARVIS can access.")
            } else {
                showReview()
            }
        }
    }

    private fun updateScan(files: Long, bytes: Long, folder: String) {
        scanFilesView?.text = NumberFormat.getInstance().format(files)
        scanFoundView?.text = ui.formatBytes(bytes)
        if (folder.isNotBlank()) subline.text = "Checking $folder"
    }

    private fun statTile(label: String, value: String, first: Boolean): Pair<LinearLayout, TextView> {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(ui.dp(12), ui.dp(16), ui.dp(12), ui.dp(16))
            background = ui.glass(20)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (first) marginEnd = ui.dp(6) else marginStart = ui.dp(6)
            }
        }
        val valueView = ui.label(value, 20f, bold = true).apply { gravity = Gravity.CENTER }
        tile.addView(valueView)
        tile.addView(ui.label(label, 10f, ui.p.muted).apply {
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
            setPadding(0, ui.dp(4), 0, 0)
        })
        return tile to valueView
    }

    // ---- REVIEW -----------------------------------------------------------------------------------

    private fun visibleItems(): List<CleanItem> = allItems.filter { filter == null || it.category == filter }

    private fun showReview() {
        phase = Phase.REVIEW
        filter = null
        fx.mode = FxView.Mode.IDLE
        fx.progress = -1f
        setFxHeight(120)
        val adapter = ItemAdapter(ui) { updateReviewSummary() }
        itemAdapter = adapter

        swapContent { host ->
            if (feature == CleanerActions.F_FULL) host.addView(boostRow(), ui.lp().apply { bottomMargin = ui.dp(10) })

            if (allItems.isEmpty()) {
                host.addView(ui.label("No junk found. Your storage is already tidy.", 14f, ui.p.muted).apply {
                    gravity = Gravity.CENTER
                    setPadding(ui.dp(12), ui.dp(24), ui.dp(12), ui.dp(24))
                }, ui.lp())
            } else {
                val scroller = HorizontalScrollView(this).apply {
                    isHorizontalScrollBarEnabled = false
                    layoutParams = ui.lp().apply { bottomMargin = ui.dp(10) }
                }
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                chipRow = row
                scroller.addView(row)
                host.addView(scroller)
                drawChips()

                val list = RecyclerView(this).apply {
                    layoutManager = LinearLayoutManager(this@CleanupFeatureActivity)
                    this.adapter = adapter
                    clipToPadding = false
                    overScrollMode = View.OVER_SCROLL_NEVER
                    setPadding(0, 0, 0, ui.dp(6))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                }
                host.addView(list)
                adapter.submit(visibleItems())
            }

            val bottom = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = ui.lp().apply { topMargin = ui.dp(8) }
            }
            if (allItems.isNotEmpty()) {
                val toggle = ui.ghostButton("Select all").apply {
                    setOnClickListener { toggleSelectAll() }
                }
                selectToggle = toggle
                bottom.addView(toggle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = ui.dp(10) })
            }
            val clean = ui.primaryButton("Clean").apply { setOnClickListener { confirmWork() } }
            cleanButton = clean
            bottom.addView(clean, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, if (allItems.isNotEmpty()) 2f else 1f))
            host.addView(bottom)
        }
        updateReviewSummary()
    }

    private fun boostRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(16), ui.dp(12), ui.dp(12), ui.dp(12))
            background = ui.glass(20)
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(ui.label("Also stop background apps", 14f, bold = true))
        texts.addView(ui.label(
            if (boostCount > 0) "$boostCount apps will be asked to stop" else "Frees memory after cleaning",
            11f, ui.p.muted
        ))
        row.addView(texts)
        row.addView(SwitchCompat(this).apply {
            isChecked = boostToo
            setOnCheckedChangeListener { _, checked ->
                boostToo = checked
                if (phase == Phase.REVIEW) updateReviewSummary()
            }
        })
        return row
    }

    private fun drawChips() {
        val row = chipRow ?: return
        row.removeAllViews()
        val categories = allItems.map { it.category }.distinct()
        fun addChip(label: String, value: String?) {
            row.addView(ui.chip(label, filter == value).apply {
                setOnClickListener {
                    filter = value
                    drawChips()
                    itemAdapter?.submit(visibleItems())
                    updateReviewSummary()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = ui.dp(8)
            })
        }
        addChip("All (${allItems.size})", null)
        for (category in categories) addChip("$category (${allItems.count { it.category == category }})", category)
    }

    private fun toggleSelectAll() {
        val visible = visibleItems()
        val allOn = visible.isNotEmpty() && visible.all { it.selected }
        visible.forEach { it.selected = !allOn }
        itemAdapter?.submit(visible)
        updateReviewSummary()
    }

    private fun updateReviewSummary() {
        val selected = allItems.filter { it.selected }
        val bytes = selected.sumOf { it.size }
        val boostPart = feature == CleanerActions.F_FULL && boostToo
        val amount = if (bytes > 0L) ui.formatBytes(bytes) else "${selected.size} items"
        setTexts(
            ui.formatBytes(bytes),
            if (allItems.isEmpty()) "Ready to boost" else "${selected.size} of ${allItems.size} selected",
            when {
                allItems.isEmpty() -> "No junk found. You can still free up memory."
                allItems.any { it.preselect } -> "Suggested items are ticked. Untick anything you want to keep."
                else -> "Nothing is ticked yet. Choose what you want removed."
            }
        )
        cleanButton?.text = when {
            selected.isNotEmpty() && boostPart -> "Clean $amount and boost"
            selected.isNotEmpty() -> "Clean $amount"
            boostPart -> "Boost memory"
            else -> "Nothing selected"
        }
        cleanButton?.alpha = if (selected.isEmpty() && !boostPart) 0.45f else 1f
        val visible = visibleItems()
        selectToggle?.text = if (visible.isNotEmpty() && visible.all { it.selected }) "Select none" else "Select all"
    }

    private fun confirmWork() {
        val selected = allItems.filter { it.selected }
        val boostPart = feature == CleanerActions.F_FULL && boostToo
        if (selected.isEmpty() && !boostPart) {
            Toast.makeText(this, "Select something first.", Toast.LENGTH_SHORT).show()
            return
        }
        val needsConfirm = selected.any { !it.preselect } || (feature != CleanerActions.F_JUNK && feature != CleanerActions.F_FULL)
        if (!needsConfirm || selected.isEmpty()) {
            runWork(selected)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete ${selected.size} item${if (selected.size == 1) "" else "s"}?")
            .setMessage("${ui.formatBytes(selected.sumOf { it.size })} will be removed from your phone. This can't be undone.")
            .setNegativeButton("Keep", null)
            .setPositiveButton("Delete") { _, _ -> runWork(selected) }
            .show()
    }

    // ---- WORK (clean + boost) ---------------------------------------------------------------------

    private fun runWork(selected: List<CleanItem>) {
        phase = Phase.WORKING
        cancel.set(false)
        fx.mode = FxView.Mode.CLEAN
        fx.progress = 0f
        fxBox.visibility = View.VISIBLE
        setFxHeight(250)
        setTexts("", "Cleaning...", "Removing selected items")
        swapContent { host ->
            val status = ui.label("", 13f, ui.p.muted).apply { gravity = Gravity.CENTER }
            workView = status
            host.addView(status, ui.lp())
        }
        val context = applicationContext
        val boostPart = feature == CleanerActions.F_FULL && boostToo
        lifecycleScope.launch {
            val started = SystemClock.uptimeMillis()
            val cleaned: CleanResult? = if (selected.isEmpty()) null else withContext(Dispatchers.IO) {
                CleanerEngine.clean(context, selected, cancel) { done, total, freed ->
                    runOnUiThread {
                        if (phase == Phase.WORKING) {
                            fx.progress = if (total > 0) done.toFloat() / total else 1f
                            setTexts(ui.formatBytes(freed), "Cleaning...", "$done of $total items")
                            workView?.text = "Freed so far: ${ui.formatBytes(freed)}"
                        }
                    }
                }
            }
            var boost: BoostResult? = null
            if (boostPart && !cancel.get()) {
                fx.mode = FxView.Mode.BOOST
                fx.progress = -1f
                setTexts("", "Boosting memory...", "Stopping background apps")
                boost = withContext(Dispatchers.IO) { CleanerEngine.runBoost(context) }
            }
            val left = MIN_ANIMATION_MS - (SystemClock.uptimeMillis() - started)
            if (left > 0L) delay(left)
            if (isFinishing || isDestroyed) return@launch
            showDone(cleaned, boost, null, null)
        }
    }

    private fun runBoostOnly() {
        phase = Phase.WORKING
        cancel.set(false)
        fx.mode = FxView.Mode.BOOST
        fx.progress = -1f
        fxBox.visibility = View.VISIBLE
        setFxHeight(250)
        setTexts("", "Boosting...", "Asking Android to end background activity")
        swapContent { host ->
            host.addView(ui.label("Measuring memory before and after.", 13f, ui.p.muted).apply { gravity = Gravity.CENTER }, ui.lp())
        }
        val context = applicationContext
        lifecycleScope.launch {
            val started = SystemClock.uptimeMillis()
            val boost = withContext(Dispatchers.IO) { CleanerEngine.runBoost(context) }
            val left = MIN_ANIMATION_MS - (SystemClock.uptimeMillis() - started)
            if (left > 0L) delay(left)
            if (isFinishing || isDestroyed) return@launch
            showDone(null, boost, null, null)
        }
    }

    // ---- DONE -------------------------------------------------------------------------------------

    private fun showDone(cleaned: CleanResult?, boost: BoostResult?, titleOverride: String?, detail: String?) {
        phase = Phase.DONE
        fxBox.visibility = View.VISIBLE
        fx.progress = -1f
        fx.mode = FxView.Mode.DONE
        setFxHeight(250)
        ui.haptic(fx)

        val storageFreed = cleaned?.freedBytes ?: 0L
        val ramFreed = boost?.freedBytes ?: 0L
        if (cleaned != null || boost != null) CleanerEngine.recordRun(this, storageFreed, ramFreed)

        val title = titleOverride ?: when {
            cleaned != null && boost != null -> "Phone boosted"
            boost != null && ramFreed > 0L -> "Boost complete"
            boost != null -> "Already running lean"
            else -> "Cleaning complete"
        }
        val sub = detail ?: when {
            boost != null && ramFreed == 0L && cleaned == null ->
                "Memory was already well managed. Android keeps the apps you use most ready."
            cleaned != null && cleaned.failed > 0 ->
                "${cleaned.failed} item${if (cleaned.failed == 1) "" else "s"} couldn't be deleted because they are in use or protected by Android."
            else -> "Everything below was measured on your phone."
        }
        setTexts("", title, sub)
        if (storageFreed > 0L || ramFreed > 0L) {
            bigValue.visibility = View.VISIBLE
            ui.countUp(bigValue, storageFreed + ramFreed) { ui.formatBytes(it) + " freed" }
        }

        val disk = CleanerEngine.disk()
        val mem = CleanerEngine.memory(this)
        swapContent { host ->
            val rows = ArrayList<Pair<String, String>>()
            if (cleaned != null) {
                rows.add("STORAGE FREED" to ui.formatBytes(storageFreed))
                rows.add("FREE STORAGE NOW" to ui.formatBytes(disk.free))
            }
            if (boost != null) {
                rows.add("MEMORY FREED" to ui.formatBytes(ramFreed))
                rows.add("APPS STOPPED" to boost.appsStopped.toString())
                rows.add("FREE MEMORY NOW" to ui.formatBytes(mem.available))
            }
            if (rows.isEmpty()) {
                rows.add("FREE STORAGE" to ui.formatBytes(disk.free))
                rows.add("FREE MEMORY" to ui.formatBytes(mem.available))
            }
            val scroll = ScrollView(this).apply {
                isVerticalScrollBarEnabled = false
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            for (start in rows.indices step 2) {
                val line = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = ui.lp().apply { bottomMargin = ui.dp(10) }
                }
                val a = statTile(rows[start].first, rows[start].second, first = true)
                line.addView(a.first)
                if (start + 1 < rows.size) {
                    val b = statTile(rows[start + 1].first, rows[start + 1].second, first = false)
                    line.addView(b.first)
                } else {
                    line.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
                }
                grid.addView(line)
            }
            scroll.addView(grid)
            host.addView(scroll)
            host.addView(ui.primaryButton("Done").apply { setOnClickListener { finish() } }, ui.lp().apply { topMargin = ui.dp(8) })
            host.addView(ui.ghostButton("Run again").apply { setOnClickListener { showIdle() } }, ui.lp().apply { topMargin = ui.dp(10) })
        }
    }

    // ---- APPS -------------------------------------------------------------------------------------

    private fun showApps() {
        phase = Phase.APPS
        fxBox.visibility = View.GONE
        setTexts("", "Installed apps", "Largest first. Tick the apps you want to remove. Android asks you to confirm each one.")
        val adapter = AppAdapter(ui) { updateUninstallButton() }
        appAdapter = adapter
        swapContent { host ->
            val list = RecyclerView(this).apply {
                layoutManager = LinearLayoutManager(this@CleanupFeatureActivity)
                this.adapter = adapter
                clipToPadding = false
                overScrollMode = View.OVER_SCROLL_NEVER
                setPadding(0, 0, 0, ui.dp(6))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            host.addView(list)
            val button = ui.primaryButton("Select apps to uninstall").apply { setOnClickListener { confirmUninstall() } }
            uninstallButton = button
            host.addView(button, ui.lp().apply { topMargin = ui.dp(8) })
        }
        updateUninstallButton()
        loadApps()
    }

    private fun loadApps() {
        val context = applicationContext
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) { CleanerEngine.userApps(context) }
            if (phase != Phase.APPS) return@launch
            appAdapter?.submit(apps)
            subline.text = "${apps.size} apps, largest first. Tick the ones you want to remove. Android asks you to confirm each."
            updateUninstallButton()
        }
    }

    private fun updateUninstallButton() {
        val count = appAdapter?.selected?.size ?: 0
        uninstallButton?.text = if (count == 0) "Select apps to uninstall" else "Uninstall $count app${if (count == 1) "" else "s"}"
        uninstallButton?.alpha = if (count == 0) 0.45f else 1f
    }

    private fun confirmUninstall() {
        val chosen = appAdapter?.selected?.toList().orEmpty()
        if (chosen.isEmpty()) {
            Toast.makeText(this, "Select at least one app.", Toast.LENGTH_SHORT).show()
            return
        }
        val pm = packageManager
        val labels = chosen.map { pkg ->
            try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
        }
        val shown = labels.take(8).joinToString("\n") { "• $it" } + if (labels.size > 8) "\n+ ${labels.size - 8} more" else ""
        MaterialAlertDialogBuilder(this)
            .setTitle("Uninstall ${chosen.size} app${if (chosen.size == 1) "" else "s"}?")
            .setMessage("$shown\n\nAndroid will ask you to confirm each one.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ ->
                uninstallQueue.clear()
                uninstallQueue.addAll(chosen)
                uninstallNext()
            }.show()
    }

    private fun uninstallNext() {
        val pkg = if (uninstallQueue.isEmpty()) null else uninstallQueue.removeAt(0)
        if (pkg == null) {
            uninstalling = null
            appAdapter?.selected?.clear()
            loadApps()
            return
        }
        if (!CleanerEngine.isInstalled(this, pkg)) {
            uninstallNext()
            return
        }
        uninstalling = pkg
        try {
            uninstallLauncher.launch(
                Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")).putExtra(Intent.EXTRA_RETURN_RESULT, true)
            )
        } catch (_: Exception) {
            Toast.makeText(this, "Android could not open the uninstall screen.", Toast.LENGTH_SHORT).show()
            uninstallQueue.clear()
            uninstalling = null
            loadApps()
        }
    }

    private fun onUninstallReturned() {
        val pkg = uninstalling
        // If the app is still installed the user said no, so the rest of the queue is dropped.
        if (pkg != null && CleanerEngine.isInstalled(this, pkg)) uninstallQueue.clear()
        uninstallNext()
    }

    // ---- System pages -----------------------------------------------------------------------------

    private fun openAndroidCacheCleaner() {
        try {
            val clear = Intent("android.os.storage.action.CLEAR_APP_CACHE")
            if (clear.resolveActivity(packageManager) != null) {
                startActivity(clear)
                return
            }
        } catch (_: Exception) { }
        openSystem(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
    }

    private fun openSystem(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, "That settings page isn't available on this phone.", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
