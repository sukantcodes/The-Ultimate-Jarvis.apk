package com.jarvis.assistant.ui.glass

import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import com.jarvis.assistant.JarvisApp
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executors

/**
 * The picture behind the Transparent (Crystal Mirror) theme.
 *
 * Eight dreamy "Daydream" scenes ship as 4K (2160 x 3840) WebP files in assets/crystal_bg. Only the Crystal Mirror theme uses
 * them; every other theme is untouched. The chosen scene is decoded once at a size that matches the screen (a 4K file is never
 * held in memory at full size on a phone that cannot show it), shared by every screen, and painted by ONE drawable on the
 * window, so there is no per-screen copy, no animation and no per-frame cost.
 *
 * Blur mode softens the scene (a real blur at a small size, scaled up smoothly, so it is cheap); Dim lays a calm dark veil on
 * top so text and glass stay readable on any scene. "Phone wallpaper" keeps the old behaviour: the system wallpaper shows
 * through (with the same dim, and the system's window blur where Android supports it).
 */
object CrystalBackdrop {

    const val SYSTEM = "system"
    private const val ASSET_DIR = "crystal_bg"

    class Option(val id: String, val title: String, val mood: String)

    val OPTIONS: List<Option> = listOf(
        Option("dd_lavender_dusk", "Lavender Dusk", "Stars over a violet sunset"),
        Option("dd_aurora_lake", "Aurora Lake", "Northern lights on still water"),
        Option("dd_cotton_sky", "Cotton Sky", "Pastel clouds at twilight"),
        Option("dd_misty_peaks", "Misty Peaks", "Moonlit mountains in haze"),
        Option("dd_ocean_dream", "Ocean Dream", "A glowing horizon over the sea"),
        Option("dd_neon_bokeh", "Neon Bokeh", "Soft city lights, out of focus"),
        Option("dd_golden_dunes", "Golden Dunes", "Warm dunes at sunset"),
        Option("dd_nebula", "Starlit Nebula", "Colour clouds in deep space")
    )

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "crystal-backdrop").apply { priority = Thread.NORM_PRIORITY - 1; isDaemon = true }
    }

    /** Every live window drawable (main thread only), so a change in the picker shows on all open screens at once. */
    private val live: MutableSet<CrystalBackdropDrawable> =
        Collections.newSetFromMap(WeakHashMap<CrystalBackdropDrawable, Boolean>())

    // one decoded scene and one blurred copy are kept; they are shared by every window
    private var sharpId: String? = null
    private var sharpBmp: Bitmap? = null
    private var blurKey: String? = null
    private var blurBmp: Bitmap? = null

    // ------------------------------------------------------------------ choices
    private fun prefs() = JarvisApp.instance.preferences

    fun selectedId(): String {
        val stored = try { prefs().crystalBackground } catch (_: Throwable) { OPTIONS[0].id }
        return if (stored == SYSTEM || OPTIONS.any { it.id == stored }) stored else OPTIONS[0].id
    }

    fun usesImage(): Boolean = selectedId() != SYSTEM
    fun blurEnabled(): Boolean = try { prefs().crystalBlurEnabled } catch (_: Throwable) { false }
    fun blurStrength(): Int = try { prefs().crystalBlurStrength.coerceIn(0, 100) } catch (_: Throwable) { 55 }
    fun dim(): Int = try { prefs().crystalDim.coerceIn(0, 70) } catch (_: Throwable) { 28 }

    fun title(id: String): String = if (id == SYSTEM) "Phone wallpaper" else (OPTIONS.firstOrNull { it.id == id }?.title ?: "Daydream")

    /** One line for the Settings row. */
    fun summary(): String {
        val blur = if (blurEnabled()) " • Blur ${blurStrength()}%" else ""
        return title(selectedId()) + blur
    }

    // ------------------------------------------------------------------ wiring
    /** Gives the activity's window the Crystal Mirror backdrop. Called from ThemeManager.applyTheme for the Crystal theme only. */
    fun install(activity: Activity) {
        val drawable = CrystalBackdropDrawable(activity.window)
        activity.window.setBackgroundDrawable(drawable)
        live.add(drawable)
        drawable.attach(activity.applicationContext)
    }

    /** Re-reads the choices and repaints every open window. Call on the main thread after changing a preference. */
    fun notifyChanged() {
        live.toList().forEach { it.onPrefsChanged() }
    }

    fun thumbnail(context: Context, id: String, done: (Bitmap?) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val bmp = try {
                app.assets.open("$ASSET_DIR/${id}_t.webp").use { BitmapFactory.decodeStream(it) }
            } catch (_: Throwable) { null }
            main.post { done(bmp) }
        }
    }

    // ------------------------------------------------------------------ decoding
    internal fun requestImage(context: Context, id: String, done: (Bitmap?) -> Unit) {
        val app = context.applicationContext
        val dm = app.resources.displayMetrics
        worker.execute {
            val cached = synchronized(this) { if (sharpId == id) sharpBmp else null }
            val bmp = cached ?: decode(app, id, dm.widthPixels, dm.heightPixels)?.also {
                synchronized(this) { sharpId = id; sharpBmp = it; blurKey = null; blurBmp = null }
            }
            main.post { done(bmp) }
        }
    }

    internal fun requestBlur(sharp: Bitmap, id: String, strength: Int, done: (Bitmap?) -> Unit) {
        worker.execute {
            val key = "$id@$strength"
            var out = synchronized(this) { if (blurKey == key) blurBmp else null }
            if (out == null) {
                out = try { makeBlur(sharp, strength) } catch (_: Throwable) { null }
                val made = out
                if (made != null) synchronized(this) { blurKey = key; blurBmp = made }
            }
            main.post { done(out) }
        }
    }

    private fun decode(ctx: Context, id: String, screenW: Int, screenH: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            ctx.assets.open("$ASSET_DIR/$id.webp").use { BitmapFactory.decodeStream(it, null, bounds) }
        } catch (_: Throwable) { return null }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // The scene is shown "cover" style: it only has to be as sharp as the screen needs (a little softer is invisible once it
        // is dimmed and sits behind glass), so a 1080p phone decodes the 4K file at half size instead of holding 33 MB.
        val cover = maxOf(screenW / bounds.outWidth.toFloat(), screenH / bounds.outHeight.toFloat())
        var sample = 1
        while (1f / (sample * 2) >= 0.75f * cover) sample *= 2
        val lowRam = try { (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice } catch (_: Throwable) { false }
        if (lowRam && sample < 2) sample = 2
        repeat(3) {
            try {
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bmp = ctx.assets.open("$ASSET_DIR/$id.webp").use { BitmapFactory.decodeStream(it, null, opts) }
                if (bmp != null) return bmp
            } catch (_: OutOfMemoryError) {
                sample *= 2
            } catch (_: Throwable) {
                return null
            }
        }
        return null
    }

    // ------------------------------------------------------------------ blur (small and cheap)
    private fun makeBlur(sharp: Bitmap, strength: Int): Bitmap {
        val targetW = 180
        var cur = sharp
        while (cur.width / 2 >= targetW) {
            cur = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
        }
        val h = (cur.height * (targetW / cur.width.toFloat())).toInt().coerceAtLeast(1)
        val small = if (cur.width != targetW) Bitmap.createScaledBitmap(cur, targetW, h, true) else cur
        val radius = (2f + strength.coerceIn(0, 100) * 0.20f).toInt()
        return blurBitmap(small, radius)
    }

    private fun blurBitmap(src: Bitmap, radius: Int): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val ch = Array(3) { IntArray(w * h) }
        for (i in px.indices) {
            val p = px[i]
            ch[0][i] = (p shr 16) and 255
            ch[1][i] = (p shr 8) and 255
            ch[2][i] = p and 255
        }
        val tmp = IntArray(w * h)
        val r = (radius / 2).coerceAtLeast(1)
        for (c in 0 until 3) {
            val a = ch[c]
            repeat(3) {
                boxPass(a, tmp, w, h, r, true)
                boxPass(tmp, a, w, h, r, false)
            }
        }
        for (i in px.indices) {
            px[i] = (0xFF shl 24) or (ch[0][i] shl 16) or (ch[1][i] shl 8) or ch[2][i]
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun boxPass(inp: IntArray, out: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val len = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val div = 2 * r + 1
        for (line in 0 until lines) {
            val base = if (horizontal) line * w else line
            val step = if (horizontal) 1 else w
            var sum = 0
            for (i in -r..r) sum += inp[base + i.coerceIn(0, len - 1) * step]
            for (i in 0 until len) {
                out[base + i * step] = sum / div
                val add = inp[base + (i + r + 1).coerceAtMost(len - 1) * step]
                val sub = inp[base + (i - r).coerceAtLeast(0) * step]
                sum += add - sub
            }
        }
    }
}

/**
 * The window background of the Crystal Mirror theme: the chosen scene (cover-fitted), then a calm dim veil and a soft caustic
 * glow in the top corner. Static: it never animates after the short fade-in when a scene changes.
 */
class CrystalBackdropDrawable(window: Window) : Drawable() {

    private val windowRef = WeakReference(window)
    private var ctx: Context? = null

    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val veilPaint = Paint(Paint.DITHER_FLAG)
    private val src = Rect()
    private val dst = RectF()

    private var imageMode = true
    private var blurOn = false
    private var blurStrength = 0
    private var dimPercent = 28

    private var sharp: Bitmap? = null
    private var shown: Bitmap? = null
    private var previous: Bitmap? = null
    private var fade = 255
    private var fader: ValueAnimator? = null
    private var token = 0

    private var veil: Shader? = null
    private var glow: Shader? = null

    fun attach(context: Context) {
        ctx = context
        onPrefsChanged()
    }

    /** Re-reads the choices (main thread). */
    fun onPrefsChanged() {
        val c = ctx ?: return
        val id = CrystalBackdrop.selectedId()
        imageMode = id != CrystalBackdrop.SYSTEM
        blurOn = CrystalBackdrop.blurEnabled()
        blurStrength = CrystalBackdrop.blurStrength()
        dimPercent = CrystalBackdrop.dim()
        applyWindowMode()
        rebuildShaders()
        val mine = ++token
        if (!imageMode) {
            sharp = null; shown = null; previous = null
            invalidateSelf()
            return
        }
        invalidateSelf()
        CrystalBackdrop.requestImage(c, id) { bmp ->
            if (mine != token || bmp == null) return@requestImage
            sharp = bmp
            if (blurOn && blurStrength > 0) {
                CrystalBackdrop.requestBlur(bmp, id, blurStrength) { blurred ->
                    if (mine != token) return@requestBlur
                    show(blurred ?: bmp)
                }
            } else {
                show(bmp)
            }
        }
    }

    private fun show(bmp: Bitmap) {
        if (shown === bmp) return
        fader?.cancel() // its end-listener clears `previous`, so it must run before the new pair is set up
        previous = shown
        shown = bmp
        fade = 0
        fader = ValueAnimator.ofInt(0, 255).apply {
            duration = 320L
            interpolator = DecelerateInterpolator()
            addUpdateListener { fade = it.animatedValue as Int; invalidateSelf() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { previous = null; fade = 255; invalidateSelf() }
            })
            start()
        }
        invalidateSelf()
    }

    /** The picture is opaque, so the phone's own wallpaper only needs compositing when "Phone wallpaper" is chosen. */
    private fun applyWindowMode() {
        val w = windowRef.get() ?: return
        try {
            if (imageMode) w.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            else w.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        } catch (_: Throwable) { }
        if (Build.VERSION.SDK_INT >= 31) {
            val radius = when {
                imageMode -> 0
                blurOn -> (blurStrength * 0.9f).toInt()
                else -> 14 // the previous, very slight system blur
            }
            try { w.setBackgroundBlurRadius(radius) } catch (_: Throwable) { }
        }
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        rebuildShaders()
    }

    private fun rebuildShaders() {
        val b = bounds
        if (b.isEmpty) return
        val d = dimPercent / 100f + (if (blurOn) 0.04f else 0f)
        fun a(f: Float) = (f.coerceIn(0f, 1f) * 255f).toInt()
        val ink = intArrayOf(10, 14, 30)
        fun shade(f: Float) = Color.argb(a(f), ink[0], ink[1], ink[2])
        veil = LinearGradient(
            0f, b.top.toFloat(), 0f, b.bottom.toFloat(),
            intArrayOf(shade(d * 0.90f), shade(d * 0.55f), shade(d * 1.25f + 0.06f)),
            floatArrayOf(0f, 0.50f, 1f), Shader.TileMode.CLAMP
        )
        val big = maxOf(b.width(), b.height()).toFloat()
        glow = RadialGradient(
            b.left + b.width() * 0.12f, b.top.toFloat(), big * 0.70f,
            intArrayOf(Color.argb(0x2A, 0xE6, 0xF4, 0xFF), Color.argb(0x0E, 0xE6, 0xF4, 0xFF), Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
        )
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        if (imageMode) {
            canvas.drawColor(BASE)
            previous?.let { drawCover(canvas, it, 255, b) }
            shown?.let { drawCover(canvas, it, fade, b) }
        }
        veil?.let { veilPaint.shader = it; canvas.drawRect(b, veilPaint) }
        glow?.let { veilPaint.shader = it; canvas.drawRect(b, veilPaint) }
        veilPaint.shader = null
    }

    private fun drawCover(canvas: Canvas, bmp: Bitmap, alpha: Int, b: Rect) {
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        val dw = b.width().toFloat()
        val dh = b.height().toFloat()
        val scale = maxOf(dw / bw, dh / bh)
        val sw = dw / scale
        val sh = dh / scale
        val sx = (bw - sw) / 2f
        val sy = (bh - sh) / 2f
        src.set(sx.toInt(), sy.toInt(), (sx + sw).toInt().coerceAtMost(bmp.width), (sy + sh).toInt().coerceAtMost(bmp.height))
        dst.set(b)
        imagePaint.alpha = alpha
        canvas.drawBitmap(bmp, src, dst, imagePaint)
    }

    override fun setAlpha(alpha: Int) { imagePaint.alpha = alpha; veilPaint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { imagePaint.colorFilter = colorFilter; veilPaint.colorFilter = colorFilter }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        val BASE: Int = Color.rgb(10, 18, 32)
    }
}
