package com.jarvis.assistant.gallery

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.CheckBox
import android.widget.ScrollView
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.R
import com.jarvis.assistant.util.ThemeManager
import java.text.DateFormat
import java.util.Date

/** A read-only MediaStore gallery starter. Permission is requested only after the user opts in. */
class GalleryActivity : AppCompatActivity() {
    private lateinit var content: LinearLayout
    private var resultsStartIndex = 5
    private var activeSearch = ""
    private var selectedType: GalleryMediaType? = null
    private var selectedAlbum: String? = null
    private var dateWindowDays: Int? = null
    private var sortOrder = GallerySortOrder.NEWEST
    private var favoritesOnly = false
    private var sizeFilter = GallerySizeFilter.ALL
    private var formatFilter = GalleryFormatFilter.ALL
    private var sizeFilterButton: MaterialButton? = null
    private var formatFilterButton: MaterialButton? = null
    private var summaryView: TextView? = null
    private var selectionMode = false
    private val selectedItems = linkedMapOf<String, GalleryMediaItem>()
    private var displayedItems: List<GalleryMediaItem> = emptyList()
    private var selectAllButton: MaterialButton? = null
    private var selectionButton: MaterialButton? = null
    private var shareButton: MaterialButton? = null
    private lateinit var thumbnailLoader: GalleryThumbnailLoader
    private var mediaObserver: GalleryMediaObserver? = null
    private val backgroundRefreshEnabled: Boolean
        get() = prefs.getBoolean(GalleryRefreshScheduler.KEY_BACKGROUND_REFRESH, false)
    private val prefs by lazy { getSharedPreferences("jarvis_gallery", MODE_PRIVATE) }
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val enabled = GalleryPermissionManager.hasAnyMediaAccess(this)
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        root.addView(TextView(this).apply {
            text = "JARVIS Gallery"; textSize = 24f
            setTextColor(resolveColor(android.R.attr.textColorPrimary))
        })
        root.addView(TextView(this).apply {
            text = "Read-only media browser using Android MediaStore and scoped storage."
            textSize = 13f; setPadding(0, dp(6), 0, dp(14))
            setTextColor(resolveColor(android.R.attr.textColorSecondary))
        })
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            addView(content, ViewGroup.LayoutParams(-1, -2))
        }
        root.addView(scrollView, LinearLayout.LayoutParams(-1, 0, 1f))
        ThemeManager.applyDynamicBackground(root)
        setContentView(root)
        thumbnailLoader = GalleryThumbnailLoader(contentResolver)
        render()
    }

    override fun onResume() {
        super.onResume()
        if (backgroundRefreshEnabled && GalleryPermissionManager.hasAnyMediaAccess(this)) {
            startMediaObserver()
        }
    }

    override fun onDestroy() {
        if (::thumbnailLoader.isInitialized) thumbnailLoader.shutdown()
        super.onDestroy()
    }

    override fun onPause() {
        mediaObserver?.unregister()
        mediaObserver = null
        super.onPause()
    }

    private fun startMediaObserver() {
        if (mediaObserver != null) return
        mediaObserver = GalleryMediaObserver(contentResolver) {
            GalleryRefreshScheduler.enqueueChangeRefresh(this)
        }.also { it.register() }
    }

    private fun render() {
        content.removeAllViews()
        val enabled = prefs.getBoolean(KEY_ENABLED, false)
        val hasAccess = GalleryPermissionManager.hasAnyMediaAccess(this)
        if (!enabled || !hasAccess) {
            content.addView(TextView(this).apply {
                text = "Gallery access is off. Turn it on only if you want JARVIS to browse photos and videos on this device. Android will show its own permission prompt."
                textSize = 14f; setPadding(0, dp(12), 0, dp(12))
                setTextColor(resolveColor(android.R.attr.textColorPrimary))
            })
            content.addView(MaterialButton(this).apply {
                text = if (enabled && !hasAccess) "Grant gallery access" else "Enable gallery access"
                setOnClickListener {
                    if (enabled && !hasAccess) {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    } else {
                        permissionRequest.launch(GalleryPermissionManager.requiredPermissions())
                    }
                }
            })
            content.addView(TextView(this).apply {
                text = "You can choose limited photo/video access on supported Android versions. No files are uploaded. This starter does not delete, move or edit media."
                textSize = 12f; setPadding(0, dp(12), 0, 0)
                setTextColor(resolveColor(android.R.attr.textColorSecondary))
            })
            return
        }

        val search = EditText(this).apply {
            hint = "Search file names"
            isSingleLine = true
        }
        content.addView(search)
        content.addView(MaterialButton(this).apply {
            text = "Search"
            setOnClickListener { showItems(search.text.toString()) }
        })
        content.addView(MaterialButton(this).apply {
            text = "Refresh"
            setOnClickListener { showItems("") }
        })
        content.addView(MaterialButton(this).apply {
            text = if (backgroundRefreshEnabled) "Disable background index refresh" else "Enable background index refresh"
            setOnClickListener {
                GalleryRefreshScheduler.setEnabled(this@GalleryActivity, !backgroundRefreshEnabled)
                if (backgroundRefreshEnabled) startMediaObserver()
                else {
                    mediaObserver?.unregister()
                    mediaObserver = null
                }
                render()
            }
        })
        content.addView(TextView(this).apply {
            text = if (backgroundRefreshEnabled) {
                "Local metadata refresh is enabled. WorkManager runs periodically when battery and storage conditions allow. Change detection runs only while this screen is open."
            } else {
                "Background indexing is off. Enable it only if you want JARVIS to keep a local metadata index refreshed."
            }
            textSize = 12f
            setPadding(0, dp(8), 0, dp(8))
            setTextColor(resolveColor(android.R.attr.textColorSecondary))
        })
        content.addView(MaterialButton(this).apply {
            text = "Media type: " + (selectedType?.name ?: "All")
            setOnClickListener {
                val options = arrayOf("All media", "Photos", "Videos")
                MaterialAlertDialogBuilder(this@GalleryActivity)
                    .setTitle("Filter media type")
                    .setItems(options) { _, which ->
                        selectedType = when (which) {
                            1 -> GalleryMediaType.IMAGE
                            2 -> GalleryMediaType.VIDEO
                            else -> null
                        }
                        showItems(activeSearch)
                        renderFilterLabels()
                    }.show()
            }
        })
        content.addView(MaterialButton(this).apply {
            text = "Date: " + (dateWindowDays?.let { "Last $it days" } ?: "Any time")
            setOnClickListener {
                val options = arrayOf("Any time", "Last 7 days", "Last 30 days", "Last 90 days")
                MaterialAlertDialogBuilder(this@GalleryActivity)
                    .setTitle("Filter by date")
                    .setItems(options) { _, which ->
                        dateWindowDays = when (which) {
                            1 -> 7
                            2 -> 30
                            3 -> 90
                            else -> null
                        }
                        showItems(activeSearch)
                        renderFilterLabels()
                    }.show()
            }
        })
        content.addView(MaterialButton(this).apply {
            text = "Album: " + (selectedAlbum ?: "All albums")
            setOnClickListener { chooseAlbum() }
        })
        content.addView(MaterialButton(this).apply {
            text = "Sort: " + sortOrder.name.lowercase().replaceFirstChar { it.uppercase() }
            setOnClickListener { chooseSortOrder() }
        })
        content.addView(MaterialButton(this).apply {
            text = if (favoritesOnly) "Showing favorites (tap to show all)" else "Show favorites"
            setOnClickListener {
                favoritesOnly = !favoritesOnly
                showItems(activeSearch)
                renderFilterLabels()
            }
        })
        sizeFilterButton = MaterialButton(this).apply {
            text = "File size: ${sizeFilter.label}"
            setOnClickListener { chooseSizeFilter() }
        }
        content.addView(sizeFilterButton)
        formatFilterButton = MaterialButton(this).apply {
            text = "Format: ${formatFilter.label}"
            setOnClickListener { chooseFormatFilter() }
        }
        content.addView(formatFilterButton)
        summaryView = TextView(this).apply {
            textSize = 12f
            setPadding(dp(4), dp(8), dp(4), dp(8))
            setTextColor(resolveColor(android.R.attr.textColorSecondary))
        }
        content.addView(summaryView)
        selectionButton = MaterialButton(this).apply {
            text = if (selectionMode) "Selection mode: ON (${selectedItems.size})" else "Select multiple"
            setOnClickListener {
                selectionMode = !selectionMode
                if (!selectionMode) selectedItems.clear()
                showItems(activeSearch)
                renderFilterLabels()
                updateSelectionControls()
            }
        }
        content.addView(selectionButton)
        shareButton = MaterialButton(this).apply {
            text = "Share selected (${selectedItems.size})"
            isEnabled = selectedItems.isNotEmpty()
            setOnClickListener {
                runCatching { GalleryShareHelper.share(this@GalleryActivity, selectedItems.values.toList()) }
                    .onFailure {
                        MaterialAlertDialogBuilder(this@GalleryActivity)
                            .setMessage("Unable to open the Android share sheet.")
                            .setPositiveButton("OK", null).show()
                    }
            }
        }
        content.addView(shareButton)
        selectAllButton = MaterialButton(this).apply {
            text = "Select all visible (0)"
            setOnClickListener {
                selectionMode = true
                displayedItems.forEach { selectedItems[it.uri.toString()] = it }
                showItems(activeSearch)
                renderFilterLabels()
            }
        }
        content.addView(selectAllButton)
        resultsStartIndex = content.childCount
        showItems("")
    }

    private fun renderFilterLabels() {
        val controls = (5 until resultsStartIndex).mapNotNull { content.getChildAt(it) as? MaterialButton }
        if (controls.size >= 3) {
            controls[0].text = "Media type: " + (selectedType?.name ?: "All")
            controls[1].text = "Date: " + (dateWindowDays?.let { "Last $it days" } ?: "Any time")
            controls[2].text = "Album: " + (selectedAlbum ?: "All albums")
            if (controls.size >= 5) {
                controls[3].text = "Sort: " + sortOrder.name.lowercase().replaceFirstChar { it.uppercase() }
                controls[4].text = if (favoritesOnly) "Showing favorites (tap to show all)" else "Show favorites"
            }
        }
        sizeFilterButton?.text = "File size: ${sizeFilter.label}"
        formatFilterButton?.text = "Format: ${formatFilter.label}"
        updateSelectionControls()
    }

    private fun chooseSizeFilter() {
        val options = GallerySizeFilter.values()
        MaterialAlertDialogBuilder(this)
            .setTitle("Filter by file size")
            .setItems(options.map { it.label }.toTypedArray()) { _, which ->
                sizeFilter = options[which]
                showItems(activeSearch)
                renderFilterLabels()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun chooseFormatFilter() {
        val options = GalleryFormatFilter.values()
        MaterialAlertDialogBuilder(this)
            .setTitle("Filter by format")
            .setItems(options.map { it.label }.toTypedArray()) { _, which ->
                formatFilter = options[which]
                showItems(activeSearch)
                renderFilterLabels()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateSelectionControls() {
        selectionButton?.text = if (selectionMode) "Selection mode: ON (${selectedItems.size})" else "Select multiple"
        shareButton?.text = "Share selected (${selectedItems.size})"
        shareButton?.isEnabled = selectedItems.isNotEmpty()
        selectAllButton?.text = "Select all visible (${displayedItems.size})"
        selectAllButton?.isEnabled = displayedItems.isNotEmpty()
    }

    private fun updateSelectedItem(item: GalleryMediaItem, selected: Boolean) {
        if (selected) selectedItems[item.uri.toString()] = item
        else selectedItems.remove(item.uri.toString())
        updateSelectionControls()
    }

    private fun chooseSortOrder() {
        val options = arrayOf("Newest first", "Oldest first", "Name A–Z", "Largest first")
        MaterialAlertDialogBuilder(this)
            .setTitle("Sort gallery")
            .setItems(options) { _, which ->
                sortOrder = when (which) {
                    1 -> GallerySortOrder.OLDEST
                    2 -> GallerySortOrder.NAME
                    3 -> GallerySortOrder.LARGEST
                    else -> GallerySortOrder.NEWEST
                }
                showItems(activeSearch)
                renderFilterLabels()
            }.show()
    }

    private fun favoriteUris(): MutableSet<String> =
        prefs.getStringSet(KEY_FAVORITES, emptySet())?.toMutableSet() ?: mutableSetOf()

    private fun toggleFavorite(item: GalleryMediaItem) {
        val favorites = favoriteUris()
        if (!favorites.add(item.uri.toString())) favorites.remove(item.uri.toString())
        prefs.edit().putStringSet(KEY_FAVORITES, favorites).apply()
    }

    private fun chooseAlbum() {
        val names = runCatching {
            val repo = MediaStoreGalleryRepository(contentResolver)
            (repo.loadRecent(500).mapNotNull { it.albumName }.distinct().sorted())
        }.getOrDefault(emptyList())
        val options = listOf("All albums") + names
        MaterialAlertDialogBuilder(this)
            .setTitle("Browse albums")
            .setItems(options.toTypedArray()) { _, which ->
                selectedAlbum = if (which == 0) null else names[which - 1]
                showItems(activeSearch)
                renderFilterLabels()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showItems(query: String) {
        activeSearch = query
        while (content.childCount > resultsStartIndex) content.removeViewAt(resultsStartIndex)
        val items = runCatching {
            val repository = MediaStoreGalleryRepository(contentResolver)
            if (query.isBlank()) repository.loadRecent(500) else repository.searchByName(query, 500)
        }.getOrElse { error ->
            content.addView(TextView(this).apply { text = "Unable to read media: ${error.message}" })
            return
        }
        val cutoff = dateWindowDays?.let { System.currentTimeMillis() / 1000L - it * 24L * 60L * 60L }
        val favoriteSet = favoriteUris()
        val filtered = items.filter { item ->
            (selectedType == null || item.type == selectedType) &&
                (selectedAlbum == null || item.albumName == selectedAlbum) &&
                (cutoff == null || item.dateAddedSeconds >= cutoff) &&
                (!favoritesOnly || favoriteSet.contains(item.uri.toString())) &&
                sizeFilter.matches(item.sizeBytes) &&
                formatFilter.matches(item.displayName, item.mimeType)
        }.let { list ->
            when (sortOrder) {
                GallerySortOrder.NEWEST -> list.sortedByDescending { it.dateAddedSeconds }
                GallerySortOrder.OLDEST -> list.sortedBy { it.dateAddedSeconds }
                GallerySortOrder.NAME -> list.sortedBy { it.displayName.lowercase() }
                GallerySortOrder.LARGEST -> list.sortedByDescending { it.sizeBytes }
            }
        }
        displayedItems = filtered
        val visibleBytes = filtered.sumOf { it.sizeBytes.coerceAtLeast(0L) }
        summaryView?.text = "${filtered.size} items · ${formatBytes(visibleBytes)} total in current view"
        updateSelectionControls()
        if (filtered.isEmpty()) {
            content.addView(TextView(this).apply { text = "No media found for these filters."; setPadding(0, dp(14), 0, 0) })
            return
        }
        filtered.forEach { item ->
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(8), dp(4), dp(8))
            }
            val preview = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(76), dp(76))
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = "Preview of ${item.displayName}"
            }
            thumbnailLoader.load(item, preview)
            val row = TextView(this).apply {
                val star = if (favoriteSet.contains(item.uri.toString())) "★ " else ""
                text = "${star}${if (item.type == GalleryMediaType.VIDEO) "▶" else "▣"}  ${item.displayName}\n${DateFormat.getDateTimeInstance().format(Date(item.dateAddedSeconds * 1000))} · ${formatBytes(item.sizeBytes)}"
                textSize = 13f
                setPadding(dp(10), dp(8), dp(8), dp(8))
                setTextColor(resolveColor(android.R.attr.textColorPrimary))
            }
            if (selectionMode) {
                val checkBox = CheckBox(this).apply {
                    isChecked = selectedItems.containsKey(item.uri.toString())
                    contentDescription = "Select ${item.displayName}"
                    setOnCheckedChangeListener { _, checked -> updateSelectedItem(item, checked) }
                }
                row.setOnClickListener { checkBox.isChecked = !checkBox.isChecked }
                rowLayout.addView(checkBox)
            } else {
                row.setOnClickListener { showMediaDetails(item) }
            }
            rowLayout.addView(preview)
            rowLayout.addView(row, LinearLayout.LayoutParams(0, -2, 1f))
            content.addView(rowLayout)
        }
    }

    private fun showMediaDetails(item: GalleryMediaItem) {
        val date = DateFormat.getDateTimeInstance().format(Date(item.dateAddedSeconds * 1000))
        val details = buildString {
            append("Name: ").append(item.displayName)
            append("\nType: ").append(item.type.name.lowercase().replaceFirstChar { it.uppercase() })
            append("\nAlbum: ").append(item.albumName ?: "Unknown")
            append("\nDate: ").append(date)
            append("\nSize: ").append(formatBytes(item.sizeBytes))
            item.durationMs?.let { append("\nDuration: ").append(it / 1000).append(" seconds") }
            item.mimeType?.let { append("\nFormat: ").append(it) }
        }
        val isFavorite = favoriteUris().contains(item.uri.toString())
        MaterialAlertDialogBuilder(this)
            .setTitle("Media details")
            .setMessage(details)
            .setNeutralButton(if (isFavorite) "Remove favorite" else "Add favorite") { _, _ ->
                toggleFavorite(item)
                showItems(activeSearch)
            }
            .setPositiveButton("Open") { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(item.uri, item.mimeType ?: if (item.type == GalleryMediaType.VIDEO) "video/*" else "image/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching { startActivity(intent) }.onFailure {
                    MaterialAlertDialogBuilder(this@GalleryActivity)
                        .setMessage("No app can open this media item.")
                        .setPositiveButton("OK", null).show()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> "${bytes / (1024L * 1024L)} MB"
        bytes >= 1024L -> "${bytes / 1024L} KB"
        else -> "$bytes B"
    }
    private fun resolveColor(attr: Int): Int {
        val value = android.util.TypedValue()
        theme.resolveAttribute(attr, value, true)
        return if (value.resourceId != 0) getColor(value.resourceId) else value.data
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object {
        private const val KEY_ENABLED = "gallery_access_enabled"
        private const val KEY_FAVORITES = "gallery_favorite_uris"
    }
}
