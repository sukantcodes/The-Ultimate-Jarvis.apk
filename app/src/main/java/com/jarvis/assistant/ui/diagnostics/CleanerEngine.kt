package com.jarvis.assistant.ui.diagnostics

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import androidx.documentfile.provider.DocumentFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Locale
import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicBoolean

// ------------------------------------------------------------------------------------------------
// A tiny file-tree abstraction, so the same scanners work on real folders (All files access)
// and on a folder the user picked through Android's document picker.
// ------------------------------------------------------------------------------------------------

interface Node {
    val name: String
    val path: String
    val isDirectory: Boolean
    val length: Long
    val lastModified: Long
    fun children(): List<Node>
    fun delete(): Boolean

    /** Deletes everything inside this folder (not the folder itself) and returns the bytes really freed. */
    fun deleteContents(): Long

    /** A few KB from the start, middle and end of the file, used to recognise identical photos. */
    fun sample(context: Context): ByteArray?
}

class FileNode(val file: File) : Node {
    override val name: String get() = file.name
    override val path: String get() = file.absolutePath
    override val isDirectory: Boolean get() = file.isDirectory
    override val length: Long get() = file.length()
    override val lastModified: Long get() = file.lastModified()

    override fun children(): List<Node> = try {
        file.listFiles()
            ?.filter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }
            ?.map { FileNode(it) }
            ?: emptyList()
    } catch (_: SecurityException) {
        emptyList()
    }

    override fun delete(): Boolean = try { file.delete() } catch (_: Exception) { false }

    override fun deleteContents(): Long = purge(file)

    private fun purge(folder: File): Long {
        var freed = 0L
        val kids = try { folder.listFiles() } catch (_: SecurityException) { null } ?: return 0L
        for (kid in kids) {
            if (java.nio.file.Files.isSymbolicLink(kid.toPath())) {
                kid.delete()
            } else if (kid.isDirectory) {
                freed += purge(kid)
                kid.delete()
            } else {
                val size = kid.length()
                if (kid.delete()) freed += size
            }
        }
        return freed
    }

    override fun sample(context: Context): ByteArray? = try {
        RandomAccessFile(file, "r").use { raf ->
            val total = raf.length()
            val chunk = 64 * 1024
            val buffer = ByteArray(chunk)
            val out = ByteArrayOutputStream()
            fun grab(position: Long) {
                raf.seek(position)
                val read = raf.read(buffer, 0, chunk)
                if (read > 0) out.write(buffer, 0, read)
            }
            grab(0L)
            if (total > chunk * 2L) grab(total / 2 - chunk / 2)
            if (total > chunk) grab(total - chunk)
            out.toByteArray()
        }
    } catch (_: Exception) {
        null
    }
}

class DocNode(private val doc: DocumentFile, parentPath: String) : Node {
    override val name: String = doc.name.orEmpty()
    override val path: String = "$parentPath/$name"
    override val isDirectory: Boolean get() = doc.isDirectory
    override val length: Long get() = doc.length()
    override val lastModified: Long get() = doc.lastModified()

    override fun children(): List<Node> = try {
        doc.listFiles().map { DocNode(it, path) }
    } catch (_: Exception) {
        emptyList()
    }

    override fun delete(): Boolean = try { doc.delete() } catch (_: Exception) { false }

    override fun deleteContents(): Long {
        var freed = 0L
        for (child in children()) {
            if (child.isDirectory) {
                freed += child.deleteContents()
                child.delete()
            } else {
                val size = child.length
                if (child.delete()) freed += size
            }
        }
        return freed
    }

    override fun sample(context: Context): ByteArray? = try {
        context.contentResolver.openInputStream(doc.uri)?.use { stream ->
            val buffer = ByteArray(128 * 1024)
            val read = stream.read(buffer)
            if (read > 0) buffer.copyOf(read) else null
        }
    } catch (_: Exception) {
        null
    }
}

// ------------------------------------------------------------------------------------------------
// Data
// ------------------------------------------------------------------------------------------------

enum class DeleteMode { FILE, CONTENTS, EMPTY_DIRS }

class CleanItem(
    val node: Node,
    val title: String,
    val subtitle: String,
    val size: Long,
    val category: String,
    val mode: DeleteMode,
    val preselect: Boolean,
    val modified: Long = 0L,
    val nodes: List<Node> = emptyList()
) {
    var selected: Boolean = preselect
}

class CleanResult(val deleted: Int, val failed: Int, val freedBytes: Long)

class BoostCandidate(val packageName: String, val label: String)

class BoostResult(
    val appsStopped: Int,
    val freedBytes: Long,
    val availableBefore: Long,
    val availableAfter: Long,
    val total: Long
)

class MemStats(val total: Long, val available: Long, val low: Boolean) {
    val used: Long get() = (total - available).coerceAtLeast(0L)
    val usedFraction: Float get() = if (total > 0L) (used.toFloat() / total).coerceIn(0f, 1f) else 0f
}

class DiskStats(val total: Long, val free: Long) {
    val used: Long get() = (total - free).coerceAtLeast(0L)
    val usedFraction: Float get() = if (total > 0L) (used.toFloat() / total).coerceIn(0f, 1f) else 0f
}

class BatteryStats(val percent: Int, val charging: Boolean, val plugged: Boolean, val temperatureC: Float?, val voltageMv: Int)

class AppEntry(val packageName: String, val label: String, val sizeBytes: Long, val installedAt: Long)

class LastRun(val time: Long, val storageFreed: Long, val ramFreed: Long)

typealias ProgressCallback = (scannedFiles: Long, foundBytes: Long, folder: String) -> Unit

private class Tally {
    var files = 0L
    var bytes = 0L
}

// ------------------------------------------------------------------------------------------------
// Engine
// ------------------------------------------------------------------------------------------------

object CleanerEngine {

    const val CAT_APP_CACHE = "JARVIS cache"
    const val CAT_JUNK_FOLDERS = "Cache & temp folders"
    const val CAT_THUMBS = "Thumbnail caches"
    const val CAT_TEMP_FILES = "Temp & log files"
    const val CAT_EMPTY = "Empty folders"
    const val CAT_PARTIAL = "Partial downloads"
    const val CAT_APK = "Installer files (APK)"
    const val CAT_TRASH = "Gallery trash"
    const val CAT_DUPLICATES = "Duplicates"
    const val CAT_SCREENSHOTS = "Screenshots"
    const val CAT_PHOTOS = "Largest photos"

    private val JUNK_ORDER = listOf(
        CAT_APP_CACHE, CAT_JUNK_FOLDERS, CAT_THUMBS, CAT_TEMP_FILES, CAT_EMPTY, CAT_PARTIAL, CAT_APK, CAT_TRASH
    )

    private const val MAX_RESULTS = 400
    private const val PREFS = "jarvis_cleaner"
    private const val KEY_PROTECTED = "protected_apps"

    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp")
    private val VIDEO_EXT = setOf("mp4", "mkv", "avi", "mov", "3gp", "webm", "m4v", "ts")
    private val AUDIO_EXT = setOf("mp3", "m4a", "wav", "flac", "aac", "ogg", "opus", "amr")
    private val ARCHIVE_EXT = setOf("zip", "rar", "7z", "tar", "gz", "iso")
    private val DOC_EXT = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "epub")

    private val JUNK_DIR_NAMES = setOf(
        ".thumbnails", "thumbnails", ".thumbs", ".cache", "cache", ".tmp", "tmp", ".temp", "temp", ".trash"
    )
    private val AMBIGUOUS_DIR_NAMES = setOf("cache", "tmp", "temp")
    private val USER_FOLDERS = listOf("/documents/", "/download/", "/downloads/", "/music/", "/movies/")

    // Prefixes of system packages that must never be stopped by Boost.
    private val NEVER_STOP_PREFIXES = listOf(
        "com.android.", "android.", "com.google.android.gms", "com.google.android.gsf",
        "com.google.android.webview", "com.google.android.inputmethod", "com.google.android.permissioncontroller",
        "com.google.android.packageinstaller", "com.samsung.android.", "com.sec.android.", "com.miui.",
        "com.xiaomi.", "com.coloros.", "com.oppo.", "com.vivo.", "com.huawei.android.", "com.oneplus."
    )

    // ---- Access -----------------------------------------------------------------------------------

    /** True when the whole shared storage can be scanned directly (not through the folder picker). */
    fun hasFullStorageAccess(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
        Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> false
        else -> context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    fun rootNode(): Node = FileNode(Environment.getExternalStorageDirectory())

    // ---- Live numbers -----------------------------------------------------------------------------

    fun memory(context: Context): MemStats {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return MemStats(info.totalMem, info.availMem, info.lowMemory)
    }

    fun disk(): DiskStats = try {
        val stats = StatFs(Environment.getDataDirectory().absolutePath)
        val total = stats.totalBytes.coerceAtLeast(0L)
        DiskStats(total, stats.availableBytes.coerceIn(0L, total))
    } catch (_: Exception) {
        DiskStats(0L, 0L)
    }

    fun battery(context: Context): BatteryStats? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val percent = if (level >= 0 && scale > 0) (level * 100f / scale).toInt().coerceIn(0, 100) else -1
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
        val volt = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
        return BatteryStats(
            percent = percent,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            plugged = plugged,
            temperatureC = if (temp >= 0) temp / 10f else null,
            voltageMv = volt
        )
    }

    // ---- Last run ---------------------------------------------------------------------------------

    fun recordRun(context: Context, storageFreed: Long, ramFreed: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("last_time", System.currentTimeMillis())
            .putLong("last_storage", storageFreed)
            .putLong("last_ram", ramFreed)
            .apply()
    }

    fun lastRun(context: Context): LastRun? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val time = prefs.getLong("last_time", 0L)
        if (time <= 0L) return null
        return LastRun(time, prefs.getLong("last_storage", 0L), prefs.getLong("last_ram", 0L))
    }

    // ---- Boost (stop background apps, free RAM) ---------------------------------------------------

    fun protectedApps(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_PROTECTED, emptySet()) ?: emptySet()

    fun setProtectedApps(context: Context, packages: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_PROTECTED, HashSet(packages)).apply()
    }

    /** Every app Boost is allowed to stop, before the user's protected list is applied. */
    fun stoppableApps(context: Context): List<BoostCandidate> {
        val pm = context.packageManager
        val launcher = pm.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo?.packageName
        val keyboard = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')
        val dialer = try { (context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)?.defaultDialerPackage } catch (_: Exception) { null }
        val sms = try { Telephony.Sms.getDefaultSmsPackage(context) } catch (_: Exception) { null }
        val never = setOfNotNull(context.packageName, launcher, keyboard, dialer, sms)

        return pm.getInstalledApplications(0)
            .asSequence()
            .filter { it.enabled && it.packageName !in never }
            .filter { (it.flags and ApplicationInfo.FLAG_STOPPED) == 0 }
            .filter { info ->
                val system = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val updatedSystem = (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                when {
                    !system -> true
                    !updatedSystem -> false
                    else -> NEVER_STOP_PREFIXES.none { info.packageName.startsWith(it) }
                }
            }
            .map { BoostCandidate(it.packageName, pm.getApplicationLabel(it).toString()) }
            .sortedBy { it.label.lowercase(Locale.ROOT) }
            .toList()
    }

    fun boostCandidates(context: Context): List<BoostCandidate> {
        val protectedSet = protectedApps(context)
        return stoppableApps(context).filter { it.packageName !in protectedSet }
    }

    /**
     * Asks Android to end the background processes of every candidate app, then measures how much memory
     * really came back. The number reported is the measured difference, never an estimate. Blocking (about 1.5 s).
     */
    fun runBoost(context: Context, onProgress: ((done: Int, total: Int) -> Unit)? = null): BoostResult {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val before = memory(context)
        val targets = boostCandidates(context)
        targets.forEachIndexed { index, app ->
            try { manager.killBackgroundProcesses(app.packageName) } catch (_: Exception) { }
            onProgress?.invoke(index + 1, targets.size)
        }
        try { Thread.sleep(1500L) } catch (_: InterruptedException) { }
        val after = memory(context)
        return BoostResult(
            appsStopped = targets.size,
            freedBytes = (after.available - before.available).coerceAtLeast(0L),
            availableBefore = before.available,
            availableAfter = after.available,
            total = after.total
        )
    }

    // ---- Scanners ---------------------------------------------------------------------------------

    private class Walker(
        private val cancel: AtomicBoolean,
        private val tally: Tally,
        private val onProgress: ProgressCallback,
        private val maxDepth: Int = 22
    ) {
        var skipDir: (Node, String) -> Boolean = { _, _ -> false }
        var enterDir: (Node, String, Int) -> Boolean = { _, _, _ -> true }
        var onFile: (Node, String) -> Unit = { _, _ -> }
        var leaveDir: (Node, String, Int, Int) -> Unit = { _, _, _, _ -> }

        fun walk(node: Node, depth: Int = 0) {
            if (cancel.get() || depth > maxDepth) return
            val lower = node.path.lowercase(Locale.ROOT) + "/"
            if (depth > 0 && skipDir(node, lower)) return
            if (depth > 0 && !enterDir(node, lower, depth)) return
            val kids = node.children()
            for (kid in kids) {
                if (cancel.get()) return
                if (kid.isDirectory) {
                    walk(kid, depth + 1)
                } else {
                    tally.files++
                    onFile(kid, kid.path.lowercase(Locale.ROOT))
                    if (tally.files % 150L == 0L) onProgress(tally.files, tally.bytes, node.name)
                }
            }
            leaveDir(node, lower, depth, kids.size)
        }
    }

    private fun androidPrivate(lower: String) = lower.contains("/android/data/") || lower.contains("/android/obb/")

    private fun treeSize(node: Node, cancel: AtomicBoolean, depth: Int = 0): Long {
        if (cancel.get() || depth > 24) return 0L
        var total = 0L
        for (child in node.children()) {
            total += if (child.isDirectory) treeSize(child, cancel, depth + 1) else child.length
        }
        return total
    }

    private fun parentLabel(path: String, root: String): String {
        val parent = path.substringBeforeLast('/', "")
        val relative = parent.removePrefix(root).trim('/')
        return if (relative.isEmpty()) "Internal storage" else relative
    }

    private fun isJunkDir(name: String, lowerPath: String): Boolean {
        val lowerName = name.lowercase(Locale.ROOT)
        if (lowerName !in JUNK_DIR_NAMES) return false
        if (lowerName in AMBIGUOUS_DIR_NAMES && USER_FOLDERS.any { lowerPath.contains(it) }) return false
        return true
    }

    private fun ownCacheItem(context: Context): CleanItem? {
        val folders = ArrayList<File>()
        folders.add(context.cacheDir)
        context.externalCacheDirs?.filterNotNull()?.forEach { folders.add(it) }
        var total = 0L
        val nodes = ArrayList<Node>()
        for (folder in folders) {
            if (!folder.isDirectory) continue
            val node = FileNode(folder)
            total += treeSize(node, AtomicBoolean(false))
            nodes.add(node)
        }
        if (total <= 0L || nodes.isEmpty()) return null
        return CleanItem(
            node = nodes.first(), title = "JARVIS app cache", subtitle = "Temporary files JARVIS can rebuild",
            size = total, category = CAT_APP_CACHE, mode = DeleteMode.CONTENTS, preselect = true,
            nodes = nodes
        )
    }

    /** Junk: cache/temp folders, thumbnail caches, temp and log files, empty folders, JARVIS' own cache. */
    fun scanJunk(context: Context, root: Node?, cancel: AtomicBoolean, onProgress: ProgressCallback): List<CleanItem> {
        val items = ArrayList<CleanItem>()
        val tally = Tally()
        ownCacheItem(context)?.let { items.add(it); tally.bytes += it.size }

        if (root != null) {
            val storageRoot = Environment.getExternalStorageDirectory().absolutePath
            val emptyDirs = ArrayList<Node>()
            val walker = Walker(cancel, tally, onProgress)
            walker.skipDir = { _, lower -> lower.contains("/android/") }
            walker.enterDir = { node, lower, _ ->
                if (isJunkDir(node.name, lower)) {
                    val size = treeSize(node, cancel)
                    if (size > 0L) {
                        items.add(
                            CleanItem(
                                node, node.name, parentLabel(node.path, storageRoot), size,
                                CAT_JUNK_FOLDERS, DeleteMode.CONTENTS, true, node.lastModified
                            )
                        )
                        tally.bytes += size
                    }
                    false
                } else {
                    true
                }
            }
            walker.onFile = { node, lower ->
                classifyJunkFile(node, lower, storageRoot)?.let { items.add(it); tally.bytes += it.size }
            }
            walker.leaveDir = { node, _, depth, childCount ->
                if (childCount == 0 && depth >= 2 && emptyDirs.size < 600) emptyDirs.add(node)
            }
            walker.walk(root)
            if (emptyDirs.isNotEmpty() && !cancel.get()) {
                items.add(
                    CleanItem(
                        node = emptyDirs.first(), title = "${emptyDirs.size} empty folders",
                        subtitle = "Folders that contain nothing", size = 0L, category = CAT_EMPTY,
                        mode = DeleteMode.EMPTY_DIRS, preselect = true,
                        nodes = emptyDirs.sortedByDescending { it.path.length }
                    )
                )
            }
        }
        onProgress(tally.files, tally.bytes, "")
        return items.sortedWith(compareBy<CleanItem> { JUNK_ORDER.indexOf(it.category).let { i -> if (i < 0) 99 else i } }
            .thenByDescending { it.size })
    }

    private fun classifyJunkFile(node: Node, lower: String, root: String): CleanItem? {
        val name = node.name.lowercase(Locale.ROOT)
        val ext = name.substringAfterLast('.', "")
        val category: String
        val preselect: Boolean
        when {
            name.startsWith(".thumbdata") -> { category = CAT_THUMBS; preselect = true }
            ext == "tmp" || ext == "temp" || ext == "log" || ext == "dmp" -> { category = CAT_TEMP_FILES; preselect = true }
            ext == "crdownload" || ext == "part" || ext == "partial" -> { category = CAT_PARTIAL; preselect = false }
            ext == "apk" -> { category = CAT_APK; preselect = false }
            name.startsWith(".trashed-") -> { category = CAT_TRASH; preselect = false }
            else -> return null
        }
        val size = node.length
        if (size <= 0L) return null
        return CleanItem(node, node.name, parentLabel(node.path, root), size, category, DeleteMode.FILE, preselect, node.lastModified)
    }

    private fun largeCategory(ext: String) = when (ext) {
        in VIDEO_EXT -> "Videos"
        in AUDIO_EXT -> "Audio"
        in IMAGE_EXT -> "Photos"
        in ARCHIVE_EXT -> "Archives"
        "apk", "xapk", "apks" -> "Apps & installers"
        in DOC_EXT -> "Documents"
        else -> "Other"
    }

    /** The biggest files first, kept in a bounded heap so the list is truly the largest, not the first found. */
    fun scanLarge(root: Node, minBytes: Long, cancel: AtomicBoolean, onProgress: ProgressCallback): List<CleanItem> {
        val tally = Tally()
        val storageRoot = Environment.getExternalStorageDirectory().absolutePath
        val heap = PriorityQueue<CleanItem>(16, compareBy<CleanItem> { it.size })
        val walker = Walker(cancel, tally, onProgress)
        walker.skipDir = { _, lower -> androidPrivate(lower) }
        walker.onFile = { node, lower ->
            val size = node.length
            if (size >= minBytes) {
                val ext = lower.substringAfterLast('.', "")
                heap.add(
                    CleanItem(node, node.name, parentLabel(node.path, storageRoot), size, largeCategory(ext), DeleteMode.FILE, false, node.lastModified)
                )
                tally.bytes += size
                if (heap.size > MAX_RESULTS) heap.poll()?.let { tally.bytes -= it.size }
            }
        }
        walker.walk(root)
        onProgress(tally.files, tally.bytes, "")
        return heap.sortedByDescending { it.size }
    }

    private fun whatsappCategory(lower: String): String? {
        val marker = listOf("/whatsapp/media/", "/whatsapp business/media/").firstOrNull { lower.contains(it) } ?: return null
        val rest = lower.substringAfter(marker)
        if (rest.indexOf('/') < 0) return null
        val folder = rest.substringBefore('/')
        return when {
            folder.contains("profile") -> null
            folder.startsWith(".statuses") -> "Status cache"
            folder.contains("image") -> "Images"
            folder.contains("video") -> "Videos"
            folder.contains("audio") || folder.contains("voice") -> "Audio & voice notes"
            folder.contains("document") -> "Documents"
            folder.contains("gif") -> "GIFs"
            folder.contains("sticker") -> "Stickers"
            else -> "Other media"
        }
    }

    private fun whatsappRoots(): List<Node> {
        val external = Environment.getExternalStorageDirectory()
        return listOf(
            File(external, "WhatsApp/Media"),
            File(external, "Android/media/com.whatsapp/WhatsApp/Media"),
            File(external, "WhatsApp Business/Media"),
            File(external, "Android/media/com.whatsapp.w4b/WhatsApp Business/Media")
        ).filter { it.isDirectory }.map { FileNode(it) }
    }

    /**
     * WhatsApp and WhatsApp Business media only. The Backups and Databases folders (your chat history)
     * are never read, listed or touched.
     */
    fun scanWhatsApp(root: Node?, cancel: AtomicBoolean, onProgress: ProgressCallback): List<CleanItem> {
        val tally = Tally()
        val storageRoot = Environment.getExternalStorageDirectory().absolutePath
        val heap = PriorityQueue<CleanItem>(16, compareBy<CleanItem> { it.size })
        val walker = Walker(cancel, tally, onProgress)
        walker.skipDir = { _, lower ->
            androidPrivate(lower) || lower.contains("/backups/") || lower.contains("/databases/")
        }
        walker.onFile = { node, lower ->
            val category = whatsappCategory(lower)
            val size = node.length
            if (category != null && size > 0L) {
                heap.add(CleanItem(node, node.name, parentLabel(node.path, storageRoot), size, category, DeleteMode.FILE, false, node.lastModified))
                tally.bytes += size
                if (heap.size > MAX_RESULTS * 2) heap.poll()?.let { tally.bytes -= it.size }
            }
        }
        if (root == null) {
            whatsappRoots().forEach { walker.walk(it) }
        } else {
            walker.walk(root)
        }
        onProgress(tally.files, tally.bytes, "")
        return heap.sortedByDescending { it.size }
    }

    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format(Locale.ROOT, "%02x", b))
        return sb.toString()
    }

    /** Duplicate photos (same content, found by sampling), screenshots and the largest photos. */
    fun scanPhotos(context: Context, root: Node, cancel: AtomicBoolean, onProgress: ProgressCallback): List<CleanItem> {
        val tally = Tally()
        val storageRoot = Environment.getExternalStorageDirectory().absolutePath
        val bySize = HashMap<Long, MutableList<Node>>()
        val shots = ArrayList<CleanItem>()
        val big = PriorityQueue<CleanItem>(16, compareBy<CleanItem> { it.size })

        val walker = Walker(cancel, tally, onProgress)
        walker.skipDir = { _, lower -> androidPrivate(lower) }
        walker.onFile = { node, lower ->
            val ext = lower.substringAfterLast('.', "")
            if (ext in IMAGE_EXT) {
                val size = node.length
                if (size > 0L) {
                    bySize.getOrPut(size) { ArrayList() }.add(node)
                    val isShot = lower.contains("/screenshots/") || node.name.lowercase(Locale.ROOT).startsWith("screenshot")
                    val item = CleanItem(
                        node, node.name, parentLabel(node.path, storageRoot), size,
                        if (isShot) CAT_SCREENSHOTS else CAT_PHOTOS, DeleteMode.FILE, false, node.lastModified
                    )
                    if (isShot) {
                        if (shots.size < MAX_RESULTS) shots.add(item)
                    } else {
                        big.add(item)
                        if (big.size > 200) big.poll()
                    }
                }
            }
        }
        walker.walk(root)

        // Duplicates: only photos with identical length are compared, by a hash of sampled content.
        val groups = HashMap<String, MutableList<Node>>()
        var compared = 0
        for ((size, nodes) in bySize) {
            if (cancel.get()) break
            if (nodes.size < 2) continue
            for (node in nodes) {
                if (cancel.get()) break
                val sample = node.sample(context) ?: continue
                val digest = MessageDigest.getInstance("SHA-1")
                digest.update(sample)
                digest.update(size.toString().toByteArray())
                groups.getOrPut(hex(digest.digest())) { ArrayList() }.add(node)
                compared++
                if (compared % 25 == 0) onProgress(tally.files, tally.bytes, "Comparing photos")
            }
        }
        val duplicates = ArrayList<CleanItem>()
        val duplicatePaths = HashSet<String>()
        for (group in groups.values) {
            if (group.size < 2) continue
            val ordered = group.sortedBy { it.lastModified }
            val original = ordered.first()
            for (copy in ordered.drop(1)) {
                duplicatePaths.add(copy.path)
                duplicates.add(
                    CleanItem(
                        copy, copy.name, "Same as ${original.name}", copy.length, CAT_DUPLICATES,
                        DeleteMode.FILE, true, copy.lastModified
                    )
                )
            }
        }
        val result = ArrayList<CleanItem>()
        result.addAll(duplicates.sortedByDescending { it.size })
        result.addAll(shots.filter { it.node.path !in duplicatePaths }.sortedByDescending { it.size })
        result.addAll(big.filter { it.node.path !in duplicatePaths }.sortedByDescending { it.size })
        onProgress(tally.files, result.sumOf { it.size }, "")
        return result
    }

    // ---- Deleting ---------------------------------------------------------------------------------

    /** Removes the items and asks Android to refresh its gallery index so nothing is left as a broken thumbnail. */
    fun clean(
        context: Context,
        items: List<CleanItem>,
        cancel: AtomicBoolean,
        onProgress: (done: Int, total: Int, freed: Long) -> Unit
    ): CleanResult {
        var deleted = 0
        var failed = 0
        var freed = 0L
        val scanPaths = ArrayList<String>()
        for ((index, item) in items.withIndex()) {
            if (cancel.get()) break
            when (item.mode) {
                DeleteMode.FILE -> {
                    val size = item.node.length
                    if (item.node.delete()) {
                        deleted++
                        freed += size
                        if (scanPaths.size < 800) (item.node as? FileNode)?.let { scanPaths.add(it.path) }
                    } else {
                        failed++
                    }
                }
                DeleteMode.CONTENTS -> {
                    val targets = if (item.nodes.isNotEmpty()) item.nodes else listOf(item.node)
                    for (target in targets) freed += target.deleteContents()
                    deleted++
                }
                DeleteMode.EMPTY_DIRS -> {
                    var removed = 0
                    for (dir in item.nodes) if (dir.delete()) removed++
                    deleted += removed
                    failed += item.nodes.size - removed
                }
            }
            onProgress(index + 1, items.size, freed)
        }
        if (scanPaths.isNotEmpty()) {
            try { MediaScannerConnection.scanFile(context, scanPaths.toTypedArray(), null, null) } catch (_: Exception) { }
        }
        return CleanResult(deleted, failed, freed)
    }

    // ---- Installed apps ---------------------------------------------------------------------------

    fun userApps(context: Context): List<AppEntry> {
        val pm = context.packageManager
        return pm.getInstalledApplications(0)
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && it.packageName != context.packageName }
            .map { info ->
                var size = try { File(info.sourceDir).length() } catch (_: Exception) { 0L }
                info.splitSourceDirs?.forEach { size += try { File(it).length() } catch (_: Exception) { 0L } }
                val installed = try { pm.getPackageInfo(info.packageName, 0).firstInstallTime } catch (_: Exception) { 0L }
                AppEntry(info.packageName, pm.getApplicationLabel(info).toString(), size, installed)
            }
            .sortedByDescending { it.sizeBytes }
    }

    fun isInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: Exception) {
        false
    }
}
