package com.jarvis.assistant.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.graphics.Typeface
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.jarvis.assistant.util.ThemeManager
import java.io.File
import java.util.concurrent.Executors

/**
 * Draws the pictures of an answer straight inside the chat, with Download, Share and Edit under each one.
 * Tapping the picture opens it full screen. Colours come from the current theme.
 */
object ImageCardBinder {
    private val loader = Executors.newFixedThreadPool(2) { r -> Thread(r, "chat-image-loader").apply { isDaemon = true } }
    private val cache = object : LruCache<String, Bitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    /** Shows [refs] in [container]. Does nothing when the same pictures are already shown. */
    fun bind(container: LinearLayout, refs: List<ChatImageRef>, onOpen: (ChatImageRef) -> Unit, onEdit: (ChatImageRef) -> Unit) {
        if (refs.isEmpty()) {
            container.visibility = View.GONE
            container.removeAllViews()
            container.tag = null
            return
        }
        container.visibility = View.VISIBLE
        val signature = refs.joinToString("|") { it.path }
        if (container.tag == signature) return
        container.tag = signature
        container.removeAllViews()
        for (ref in refs) container.addView(card(container.context, ref, onOpen, onEdit))
    }

    private fun accent(): Int = try { ThemeManager.getThemeAccentColor() } catch (_: Throwable) { 0xFF00E5FF.toInt() }

    private fun card(context: Context, ref: ChatImageRef, onOpen: (ChatImageRef) -> Unit, onEdit: (ChatImageRef) -> Unit): View {
        val d = context.resources.displayMetrics.density
        fun dp(v: Float) = (v * d).toInt()
        val file = File(ref.path)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10f) }
        }
        val image = ImageView(context).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            maxHeight = dp(420f)
            minimumHeight = dp(160f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            contentDescription = ref.caption.ifBlank { "Picture made by Jarvis" }
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, 16f * d) }
            }
            tag = ref.path
            setOnClickListener { onOpen(ref) }
        }
        root.addView(image)
        load(image, ref.path, (context.resources.displayMetrics.widthPixels).coerceAtMost(1400))

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) }
        }
        fun button(label: String, onClick: (View) -> Unit) = TextView(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(accent())
            gravity = Gravity.CENTER
            minHeight = dp(44f)
            minimumWidth = dp(44f)
            background = Glass.inset(context, 14f)
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6f) }
            setOnClickListener(onClick)
        }
        row.addView(button("⬇  Download") {
            if (ImageExporter.saveToGallery(context, file) != null) Toast.makeText(context, "Saved to Pictures/JARVIS", Toast.LENGTH_SHORT).show()
            else Toast.makeText(context, "Couldn't save this picture", Toast.LENGTH_SHORT).show()
        })
        row.addView(button("↗  Share") {
            if (!ImageExporter.share(context, file)) Toast.makeText(context, "Couldn't share this picture", Toast.LENGTH_SHORT).show()
        })
        row.addView(button("✎  Edit") { onEdit(ref) }.also { (it.layoutParams as LinearLayout.LayoutParams).marginEnd = 0 })
        root.addView(row)
        return root
    }

    private fun load(view: ImageView, path: String, targetWidth: Int) {
        cache.get(path)?.let { view.setImageBitmap(it); return }
        loader.execute {
            val bitmap = try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            } catch (_: Throwable) { null }
            if (bitmap != null) {
                cache.put(path, bitmap)
                view.post { if (view.tag == path) view.setImageBitmap(bitmap) }
            }
        }
    }
}
