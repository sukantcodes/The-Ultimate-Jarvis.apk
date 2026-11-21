package com.jarvis.assistant.ui.glass

import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
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

/** Entry points for the iOS 26/27 Liquid Glass material across screens, cards, switches and dialogs. */
object LiquidGlass {

    private const val TAG_KEY = 0x4A474C53 // "JGLS"

    private fun isLight(): Boolean = try { ThemeManager.isCurrentThemeLight() } catch (_: Throwable) { false }

    private fun refreshAnimationSetting(context: Context) {
        AuroraClock.animationsEnabled = try {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        } catch (_: Throwable) { true }
    }

    /** Turns [view] into a Liquid Glass surface with clean optical rim and elevation shadow. */
    fun apply(
        view: View,
        radiusDp: Float,
        tint: Int = 0,
        soft: Boolean = false,
        topOnly: Boolean = false,
        elevationDp: Float = if (soft) 0f else 5f
    ) {
        val density = view.resources.displayMetrics.density
        if (GlassMode.plain()) {
            if (view.background == null) view.background = GlassMode.plainCardBackground(view.context, radiusDp * density)
            return
        }
        view.background = LiquidGlassDrawable(view, radiusDp * density, tint, soft, topOnly)
        view.outlineProvider = ViewOutlineProvider.BACKGROUND
        view.clipToOutline = false
        view.elevation = elevationDp * density
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && elevationDp > 0f) {
            val shadow = if (tint != 0) tint else Color.argb(120, 0, 0, 0)
            view.outlineAmbientShadowColor = shadow
            view.outlineSpotShadowColor = shadow
        }
        view.setTag(TAG_KEY, true)
    }

    /** iOS 26/27 Liquid Glass switches: translucent glass pill track + crisp white floating thumb. */
    fun styleSwitches(root: View) {
        if (GlassMode.plain()) return
        if (root is android.widget.CompoundButton && (root is androidx.appcompat.widget.SwitchCompat ||
                root is com.google.android.material.materialswitch.MaterialSwitch)) {
            val light = isLight()
            val on = try { ThemeManager.getThemeSuccessColor() } catch (_: Throwable) { Color.parseColor("#34C759") }
            val off = androidx.core.graphics.ColorUtils.setAlphaComponent(
                if (light) Color.BLACK else Color.WHITE,
                if (light) 32 else 58
            )
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf(-android.R.attr.state_checked))
            val track = android.content.res.ColorStateList(
                states,
                intArrayOf(androidx.core.graphics.ColorUtils.setAlphaComponent(on, 228), off)
            )
            val thumb = android.content.res.ColorStateList.valueOf(Color.WHITE)
            when (root) {
                is com.google.android.material.materialswitch.MaterialSwitch -> {
                    root.trackTintList = track
                    root.thumbTintList = thumb
                    root.trackDecorationTintList = android.content.res.ColorStateList.valueOf(
                        androidx.core.graphics.ColorUtils.setAlphaComponent(Color.WHITE, if (light) 90 else 55)
                    )
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

    /** Quieter optical glass pane for nested controls (inputs, chips, compact rows). */
    fun applySoft(view: View, radiusDp: Float, tint: Int = 0) = apply(view, radiusDp, tint, soft = true)

    /** Installs the shared subtle aurora field behind a screen so glass panes stay luminous. */
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

    /** Converts eligible shape-drawable cards inside [root] into Liquid Glass once laid out. */
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
                continue
            }
            glassifyChildren(child, tint)
        }
    }

    /** Dims and applies hardware window blur behind a dialog on Android 12+; smooth dim on 10–11. */
    fun blurBehind(dialog: Dialog, radiusDp: Float = 26f, dim: Float = 0.26f) {
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
            }
        }
    }

    /** Turns a dialog into a floating iOS 26/27 Liquid Glass sheet with live background blur on API 31+. */
    fun styleDialog(dialog: Dialog, radiusDp: Float = 32f) {
        if (GlassMode.plain()) return
        val window = dialog.window ?: return
        val decor = window.decorView
        val density = decor.resources.displayMetrics.density
        val glass = LiquidGlassDrawable(decor, radiusDp * density, 0, soft = false)
        val margin = (8 * density).toInt()
        window.setBackgroundDrawable(InsetDrawable(glass, margin))
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(if (isLight()) 0.16f else 0.34f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window.attributes = window.attributes.apply { blurBehindRadius = (30 * density).toInt() }
                window.setBackgroundBlurRadius((40 * density).toInt())
            } catch (_: Throwable) {
            }
        }
    }
}

/** Shared aurora backdrop behind glass screens. */
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
        AuroraField.draw(canvas, screenW, screenH, light, (if (light) 0.48f else 0.52f) * alphaValue / 255f)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) { alphaValue = alpha; base?.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { base?.colorFilter = colorFilter }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
