package com.jarvis.assistant.ui.glass

import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import com.jarvis.assistant.util.ThemeManager

/** Entry points for the Settings "liquid glass" material. */
object LiquidGlass {

    private const val TAG_KEY = 0x4A474C53 // "JGLS": marks views that already carry a glass surface

    private fun isLight(): Boolean = try { ThemeManager.isCurrentThemeLight() } catch (_: Throwable) { false }

    private fun refreshAnimationSetting(context: Context) {
        AuroraClock.animationsEnabled = try {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        } catch (_: Throwable) { true }
    }

    /**
     * Turns [view] into a glass surface. The aurora behind it keeps drifting while the view is on screen, so the
     * refraction and the sliding highlight stay alive; the clock sleeps as soon as nothing glass is attached.
     */
    fun apply(
        view: View,
        radiusDp: Float,
        tint: Int = 0,
        soft: Boolean = false,
        topOnly: Boolean = false,
        elevationDp: Float = if (soft) 0f else 4f
    ) {
        val density = view.resources.displayMetrics.density
        if (GlassMode.plain()) {
            // Glass is switched off: keep the view's own design; a view built in code with no background gets a flat themed card.
            if (view.background == null) view.background = GlassMode.plainCardBackground(view.context, radiusDp * density)
            return
        }
        view.background = LiquidGlassDrawable(view, radiusDp * density, tint, soft, topOnly)
        view.outlineProvider = ViewOutlineProvider.BACKGROUND
        view.clipToOutline = false
        view.elevation = elevationDp * density
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && elevationDp > 0f) {
            val shadow = if (tint != 0) tint else try { ThemeManager.getThemeAccentColor() } catch (_: Throwable) { 0xFF000000.toInt() }
            view.outlineAmbientShadowColor = shadow
            view.outlineSpotShadowColor = shadow
        }
        // Glass cards are static; only the screen backdrop animates, and the cards simply show it through.
        view.setTag(TAG_KEY, true)
    }

    /**
     * iOS-style switches: a white thumb on a glassy track that turns the theme's success colour when on.
     * Walks the whole view tree, so every toggle on a screen matches.
     */
    fun styleSwitches(root: View) {
        if (GlassMode.plain()) return
        if (root is android.widget.CompoundButton && (root is androidx.appcompat.widget.SwitchCompat ||
                root is com.google.android.material.materialswitch.MaterialSwitch)) {
            val light = isLight()
            val on = try { ThemeManager.getThemeSuccessColor() } catch (_: Throwable) { root.context.getColor(android.R.color.holo_green_light) }
            val off = androidx.core.graphics.ColorUtils.setAlphaComponent(if (light) android.graphics.Color.BLACK else android.graphics.Color.WHITE, if (light) 36 else 70)
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf(-android.R.attr.state_checked))
            val track = android.content.res.ColorStateList(states, intArrayOf(androidx.core.graphics.ColorUtils.setAlphaComponent(on, 235), off))
            val thumb = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
            when (root) {
                is com.google.android.material.materialswitch.MaterialSwitch -> {
                    root.trackTintList = track
                    root.thumbTintList = thumb
                    root.trackDecorationTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
                    root.thumbIconDrawable = null
                }
                is androidx.appcompat.widget.SwitchCompat -> {
                    root.trackTintList = track
                    root.thumbTintList = thumb
                }
            }
        }
        if (root is ViewGroup) for (i in 0 until root.childCount) styleSwitches(root.getChildAt(i))
    }

    /** A quieter glass pane for something that sits inside a card (inputs, chips, small buttons). */
    fun applySoft(view: View, radiusDp: Float, tint: Int = 0) = apply(view, radiusDp, tint, soft = true)

    /**
     * Gives a screen root the Settings backdrop: the app's own themed background with the drifting aurora on top.
     * Glass surfaces on the screen refract this same field.
     */
    fun installBackdrop(root: View, withBase: Boolean = false) {
        if (GlassMode.plain()) return
        val density = root.resources.displayMetrics
        val existing = root.background
        if (existing is AuroraBackdropDrawable) {
            existing.invalidateSelf()
            return
        }
        val base = if (withBase && existing != null && existing !is AuroraBackdropDrawable) existing else null
        val aurora = AuroraBackdropDrawable(density.widthPixels.toFloat(), density.heightPixels.toFloat(), root, base)
        root.background = aurora
        if (root.getTag(TAG_KEY) == null) {
            val tick = Runnable { if (root.isShown) root.invalidate() }
            val listener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    refreshAnimationSetting(v.context)
                    AuroraClock.register(tick)
                }

                override fun onViewDetachedFromWindow(v: View) {
                    AuroraClock.unregister(tick)
                }
            }
            root.addOnAttachStateChangeListener(listener)
            root.setTag(TAG_KEY, true)
            if (root.isAttachedToWindow) listener.onViewAttachedToWindow(root)
        }
    }

    /**
     * Turns the existing shape-drawable cards of a screen into glass once it has been laid out. Only larger
     * container views with a shape background are touched; buttons, chips, badges and ripples keep their look.
     */
    fun glassify(root: View, tint: Int = 0) {
        if (GlassMode.plain()) return
        root.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (root.width == 0) return
                if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnGlobalLayoutListener(this)
                glassifyChildren(root, tint)
            }
        })
    }

    private fun glassifyChildren(group: View, tint: Int) {
        if (group !is ViewGroup) return
        val density = group.resources.displayMetrics.density
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            val bg = child.background
            val isShapeCard = child is ViewGroup && !child.isClickable &&
                (bg is android.graphics.drawable.GradientDrawable || bg is android.graphics.drawable.LayerDrawable) &&
                child.width >= 120 * density && child.height >= 56 * density && child.getTag(TAG_KEY) == null
            if (isShapeCard) {
                apply(child, 24f, tint, soft = child.height < 96 * density)
                continue // keep the contents of a glass card as they were; no glass-on-glass
            }
            glassifyChildren(child, tint)
        }
    }

    /** Dims and blurs the screen behind a window (blur needs Android 12+; older versions only get the dim). */
    fun blurBehind(dialog: Dialog, radiusDp: Float = 24f, dim: Float = 0.30f) {
        if (GlassMode.plain()) return
        val window = dialog.window ?: return
        val density = window.decorView.resources.displayMetrics.density
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(dim)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window.attributes = window.attributes.apply { blurBehindRadius = (radiusDp * density).toInt() }
            } catch (_: Throwable) {
                // Window blur can be disabled by the vendor; the dim alone still reads well.
            }
        }
    }

    /**
     * Makes a dialog a floating glass pane: rounded glass window, a soft blur of the screen behind it on Android 12+,
     * and a gentle dim so the text stays readable.
     */
    fun styleDialog(dialog: Dialog, radiusDp: Float = 30f) {
        if (GlassMode.plain()) return
        val window = dialog.window ?: return
        val decor = window.decorView
        val density = decor.resources.displayMetrics.density
        val glass = LiquidGlassDrawable(decor, radiusDp * density, 0, soft = false)
        val margin = (6 * density).toInt()
        window.setBackgroundDrawable(InsetDrawable(glass, margin))
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(if (isLight()) 0.18f else 0.38f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window.attributes = window.attributes.apply { blurBehindRadius = (28 * density).toInt() }
                window.setBackgroundBlurRadius((36 * density).toInt())
            } catch (_: Throwable) {
                // Some vendor builds disable window blur; the glass pane still looks right without it.
            }
        }
    }
}

/** The calm aurora a Settings screen sits on. It is transparent, so the app's own themed background shows through. */
class AuroraBackdropDrawable(
    private val screenW: Float,
    private val screenH: Float,
    private val host: View,
    private val base: Drawable? = null
) : Drawable() {

    private val loc = IntArray(2)
    private var alphaValue = 255

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        super.onBoundsChange(bounds)
        base?.bounds = bounds
    }

    override fun draw(canvas: Canvas) {
        base?.draw(canvas)
        host.getLocationOnScreen(loc)
        val light = try { ThemeManager.isCurrentThemeLight() } catch (_: Throwable) { false }
        canvas.save()
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
        // On a Transparent-theme picture the drifting aurora would muddy the scene, so it is only a faint accent there.
        val onPicture = try { CrystalTheme.active() && CrystalBackdrop.usesImage() } catch (_: Throwable) { false }
        AuroraField.draw(canvas, screenW, screenH, light, (if (light) 0.50f else 0.55f) * (if (onPicture) 0.30f else 1f) * alphaValue / 255f)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) { alphaValue = alpha; base?.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { base?.colorFilter = colorFilter }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
