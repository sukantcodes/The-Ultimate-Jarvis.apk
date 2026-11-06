package com.jarvis.assistant.ui.diagnostics

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.R
import java.util.Locale
import java.util.concurrent.Executors

/** Theme colours of the running app, so these screens follow whichever theme the user picked. */
class Palette(context: Context) {
    private val theme = context.theme
    private fun c(attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        return if (theme.resolveAttribute(attr, tv, true)) tv.data else fallback
    }

    val text = c(R.attr.themeTextPrimary, 0xFFFFFFFF.toInt())
    val muted = c(R.attr.themeTextMuted, 0xFF9AA4B2.toInt())
    val card = c(R.attr.themeCardBg, 0x33FFFFFF)
    val cardEnd = c(R.attr.themeCardGlassEnd, 0x22FFFFFF)
    val border = c(R.attr.themeCardBorder, 0x33FFFFFF)
    val accent = c(R.attr.themeAccent, 0xFF39C6FF.toInt())
    val accent2 = c(R.attr.themeAccent2, 0xFFA78BFA.toInt())
    val success = c(R.attr.themeSuccess, 0xFF34D399.toInt())
    val warning = c(R.attr.themeWarning, 0xFFFFB020.toInt())
    val error = c(R.attr.themeError, 0xFFEF4444.toInt())
}

fun Int.withAlphaF(alpha: Float): Int =
    Color.argb((alpha.coerceIn(0f, 1f) * 255f).toInt(), Color.red(this), Color.green(this), Color.blue(this))

/** Small view factory shared by the diagnostics dashboard and the cleaning flow. */
class Ui(val ctx: Context) {
    val p = Palette(ctx)
    private val density = ctx.resources.displayMetrics.density

    fun dp(v: Int): Int = (v * density).toInt()

    fun formatBytes(v: Long): String = android.text.format.Formatter.formatShortFileSize(ctx, v.coerceAtLeast(0L))

    fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(w, h)

    fun label(value: String, sp: Float, color: Int = p.text, bold: Boolean = false, light: Boolean = false): TextView =
        TextView(ctx).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color)
            typeface = when {
                bold -> Typeface.create("sans-serif-medium", Typeface.BOLD)
                light -> Typeface.create("sans-serif-light", Typeface.NORMAL)
                else -> Typeface.create("sans-serif", Typeface.NORMAL)
            }
        }

    fun glass(radiusDp: Int = 22): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR, intArrayOf(p.card, p.cardEnd)
    ).apply {
        cornerRadius = dp(radiusDp).toFloat()
        setStroke(dp(1), p.border)
    }

    fun ripple(content: Drawable, radiusDp: Int, color: Int = p.accent.withAlphaF(0.25f)): Drawable {
        val mask = GradientDrawable().apply {
            cornerRadius = dp(radiusDp).toFloat()
            setColor(Color.WHITE)
        }
        return RippleDrawable(ColorStateList.valueOf(color), content, mask)
    }

    fun card(padding: Int = 18): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padding), dp(padding - 2), dp(padding), dp(padding - 2))
        background = glass(24)
        layoutParams = lp().apply { bottomMargin = dp(14) }
    }

    fun badge(iconRes: Int, color: Int, sizeDp: Int = 42): ImageView = ImageView(ctx).apply {
        setImageResource(iconRes)
        imageTintList = ColorStateList.valueOf(color)
        val pad = dp(sizeDp / 4 + 1)
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(color.withAlphaF(0.30f), color.withAlphaF(0.10f))).apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(1), color.withAlphaF(0.45f))
        }
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    fun primaryButton(value: String): TextView = TextView(ctx).apply {
        text = value
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        letterSpacing = 0.03f
        isClickable = true
        isFocusable = true
        minHeight = dp(58)
        setPadding(dp(20), dp(14), dp(20), dp(14))
        elevation = dp(6).toFloat()
        val fill = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(p.accent, p.accent2)).apply {
            cornerRadius = dp(20).toFloat()
        }
        background = ripple(fill, 20, Color.WHITE.withAlphaF(0.35f))
    }

    fun ghostButton(value: String): TextView = TextView(ctx).apply {
        text = value
        gravity = Gravity.CENTER
        setTextColor(p.text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        isClickable = true
        isFocusable = true
        minHeight = dp(50)
        setPadding(dp(18), dp(12), dp(18), dp(12))
        val outline = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(p.card)
            setStroke(dp(1), p.accent.withAlphaF(0.55f))
        }
        background = ripple(outline, 18)
    }

    fun chip(value: String, selected: Boolean): TextView = TextView(ctx).apply {
        text = value
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (selected) Color.WHITE else p.text)
        isClickable = true
        isFocusable = true
        setPadding(dp(14), dp(8), dp(14), dp(8))
        val shape = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            if (selected) {
                setColor(p.accent.withAlphaF(0.85f))
                setStroke(dp(1), p.accent)
            } else {
                setColor(p.card)
                setStroke(dp(1), p.border)
            }
        }
        background = ripple(shape, 18)
    }

    fun divider(): View = View(ctx).apply {
        setBackgroundColor(p.border)
        layoutParams = lp(h = dp(1))
    }

    fun spacer(heightDp: Int): View = View(ctx).apply { layoutParams = lp(h = dp(heightDp)) }

    fun countUp(view: TextView, target: Long, durationMs: Long = 1000L, format: (Long) -> String) {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener { view.text = format((target * (it.animatedValue as Float)).toLong()) }
            start()
        }
    }

    fun haptic(view: View) {
        val kind = if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS
        view.performHapticFeedback(kind)
    }

    /** Lets the user choose apps that Boost must never stop. */
    fun showProtectedApps(activity: Activity, onChanged: () -> Unit) {
        val apps = CleanerEngine.stoppableApps(activity)
        if (apps.isEmpty()) {
            MaterialAlertDialogBuilder(activity)
                .setTitle("Protected apps")
                .setMessage("There are no stoppable apps to protect right now.")
                .setPositiveButton("OK", null).show()
            return
        }
        val saved = CleanerEngine.protectedApps(activity)
        val labels = apps.map { it.label }.toTypedArray()
        val checked = BooleanArray(apps.size) { apps[it].packageName in saved }
        MaterialAlertDialogBuilder(activity)
            .setTitle("Protected apps")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val listed = apps.map { it.packageName }.toSet()
                val keep = saved.filter { it !in listed }.toMutableSet()
                apps.forEachIndexed { i, app -> if (checked[i]) keep.add(app.packageName) }
                CleanerEngine.setProtectedApps(activity, keep)
                onChanged()
            }.show()
    }
}

/** Small background loader for photo thumbnails and app icons, with a memory cache. */
object Thumbs {
    private val executor = Executors.newFixedThreadPool(2)
    private val cache = object : LruCache<String, Bitmap>(10 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val iconCache = LruCache<String, Drawable>(120)
    private val IMAGES = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")

    fun isImage(path: String) = path.substringAfterLast('.', "").lowercase(Locale.ROOT) in IMAGES

    fun loadImage(view: ImageView, path: String, sizePx: Int) {
        view.tag = path
        val hit = cache.get(path)
        if (hit != null) {
            view.setImageBitmap(hit)
            return
        }
        view.setImageDrawable(null)
        executor.execute {
            val bitmap = decode(path, sizePx) ?: return@execute
            cache.put(path, bitmap)
            view.post { if (view.tag == path) view.setImageBitmap(bitmap) }
        }
    }

    private fun decode(path: String, sizePx: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= sizePx && bounds.outHeight / (sample * 2) >= sizePx) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (_: Throwable) {
        null
    }

    fun appIcon(context: Context, packageName: String): Drawable? {
        iconCache.get(packageName)?.let { return it }
        val icon = try { context.packageManager.getApplicationIcon(packageName) } catch (_: Exception) { null }
        if (icon != null) iconCache.put(packageName, icon)
        return icon
    }
}

/** Review list for scan results, with thumbnails for photos. */
class ItemAdapter(private val ui: Ui, private val onSelectionChanged: () -> Unit) :
    RecyclerView.Adapter<ItemAdapter.Holder>() {

    private var data: List<CleanItem> = emptyList()

    fun submit(list: List<CleanItem>) {
        data = list
        notifyDataSetChanged()
    }

    class Holder(
        val root: LinearLayout,
        val thumb: ImageView,
        val title: TextView,
        val sub: TextView,
        val size: TextView,
        val check: AppCompatCheckBox
    ) : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val root = LinearLayout(ui.ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(12), ui.dp(10), ui.dp(8), ui.dp(10))
            background = ui.ripple(ui.glass(18), 18)
            isClickable = true
            isFocusable = true
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = ui.dp(8) }
        }
        val thumb = ImageView(ui.ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            val shape = GradientDrawable().apply {
                cornerRadius = ui.dp(12).toFloat()
                setColor(ui.p.accent.withAlphaF(0.14f))
            }
            background = shape
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(ui.dp(48), ui.dp(48))
        }
        val texts = LinearLayout(ui.ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = ui.dp(12)
                marginEnd = ui.dp(8)
            }
        }
        val title = ui.label("", 14f, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE }
        val sub = ui.label("", 11f, ui.p.muted).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        texts.addView(title)
        texts.addView(sub)
        val size = ui.label("", 12f, ui.p.accent, bold = true)
        val check = AppCompatCheckBox(ui.ctx).apply {
            buttonTintList = ColorStateList.valueOf(ui.p.accent)
        }
        root.addView(thumb)
        root.addView(texts)
        root.addView(size)
        root.addView(check)
        return Holder(root, thumb, title, sub, size, check)
    }

    override fun getItemCount(): Int = data.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = data[position]
        holder.title.text = item.title
        holder.sub.text = item.subtitle
        holder.size.text = if (item.size > 0L) ui.formatBytes(item.size) else ""
        val node = item.node
        if (node is FileNode && item.mode == DeleteMode.FILE && Thumbs.isImage(node.path)) {
            holder.thumb.imageTintList = null
            holder.thumb.setPadding(0, 0, 0, 0)
            Thumbs.loadImage(holder.thumb, node.path, ui.dp(96))
        } else {
            holder.thumb.tag = null
            val icon = when {
                item.mode != DeleteMode.FILE -> R.drawable.ic_diag_folder
                else -> R.drawable.ic_diag_file
            }
            holder.thumb.setImageResource(icon)
            holder.thumb.imageTintList = ColorStateList.valueOf(ui.p.accent)
            holder.thumb.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
        }
        holder.check.setOnCheckedChangeListener(null)
        holder.check.isChecked = item.selected
        holder.check.setOnCheckedChangeListener { _, checked ->
            item.selected = checked
            onSelectionChanged()
        }
        holder.root.setOnClickListener { holder.check.toggle() }
    }
}

/** Installed-app list for the uninstaller. */
class AppAdapter(private val ui: Ui, private val onSelectionChanged: () -> Unit) :
    RecyclerView.Adapter<AppAdapter.Holder>() {

    private var data: List<AppEntry> = emptyList()
    val selected = LinkedHashSet<String>()

    fun submit(list: List<AppEntry>) {
        data = list
        selected.retainAll(list.map { it.packageName }.toSet())
        notifyDataSetChanged()
    }

    class Holder(
        val root: LinearLayout,
        val icon: ImageView,
        val title: TextView,
        val sub: TextView,
        val size: TextView,
        val check: AppCompatCheckBox
    ) : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val root = LinearLayout(ui.ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(12), ui.dp(10), ui.dp(8), ui.dp(10))
            background = ui.ripple(ui.glass(18), 18)
            isClickable = true
            isFocusable = true
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = ui.dp(8) }
        }
        val icon = ImageView(ui.ctx).apply { layoutParams = LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)) }
        val texts = LinearLayout(ui.ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = ui.dp(12)
                marginEnd = ui.dp(8)
            }
        }
        val title = ui.label("", 14f, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        val sub = ui.label("", 11f, ui.p.muted).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        texts.addView(title)
        texts.addView(sub)
        val size = ui.label("", 12f, ui.p.accent, bold = true)
        val check = AppCompatCheckBox(ui.ctx).apply { buttonTintList = ColorStateList.valueOf(ui.p.accent) }
        root.addView(icon)
        root.addView(texts)
        root.addView(size)
        root.addView(check)
        return Holder(root, icon, title, sub, size, check)
    }

    override fun getItemCount(): Int = data.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val app = data[position]
        holder.title.text = app.label
        val installed = if (app.installedAt > 0L) {
            java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(app.installedAt))
        } else {
            "unknown date"
        }
        holder.sub.text = "Installed $installed"
        holder.size.text = ui.formatBytes(app.sizeBytes)
        holder.icon.setImageDrawable(Thumbs.appIcon(ui.ctx, app.packageName))
        holder.check.setOnCheckedChangeListener(null)
        holder.check.isChecked = app.packageName in selected
        holder.check.setOnCheckedChangeListener { _, checked ->
            if (checked) selected.add(app.packageName) else selected.remove(app.packageName)
            onSelectionChanged()
        }
        holder.root.setOnClickListener { holder.check.toggle() }
    }
}
