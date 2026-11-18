package com.jarvis.assistant.ui.glass

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.CompoundButton
import android.widget.ImageButton
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.textfield.TextInputLayout
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.util.ThemeManager

/**
 * App-wide liquid touch: installs, once, on every activity
 *  1. a transparent ripple layer (see [LiquidRippleOverlay]) so every touch spreads as liquid and presses squish like jelly,
 *  2. a 3D glass-bubble sheen ([Bubble3DSheen]) on every button, applied through the view overlay so no
 *     background, theme colour or click behaviour of the button is changed.
 * Both can be switched off from preferences, and both are skipped when the system has animations turned off.
 */
object LiquidTouch {

    /** Views carrying this tag as `true` keep their own press animation / sheen and are left alone here. */
    const val SKIP_TAG_KEY = 0x4A4C5453 // "JLTS"
    private const val SHEEN_TAG_KEY = 0x4A4C5348 // "JLSH"
    private const val SCAN_DELAY_MS = 600L
    private const val FLAG_CACHE_MS = 1500L

    private var installed = false

    fun install(app: Application) {
        if (installed) return
        installed = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { attach(activity) }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                // Dialogs and bottom sheets live in their own window, so each gets the same liquid layer when it opens.
                (activity as? androidx.fragment.app.FragmentActivity)?.supportFragmentManager?.registerFragmentLifecycleCallbacks(
                    object : androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
                        override fun onFragmentStarted(fm: androidx.fragment.app.FragmentManager, f: androidx.fragment.app.Fragment) {
                            val dialog = (f as? androidx.fragment.app.DialogFragment)?.dialog ?: return
                            dialog.window?.let { attachWindow(it, dialog.context) }
                        }
                    }, true
                )
            }
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun touchEnabled(): Boolean = try { JarvisApp.instance.preferences.liquidTouchEnabled } catch (_: Throwable) { true }
    private fun bubbleEnabled(): Boolean = try { JarvisApp.instance.preferences.bubble3dEnabled } catch (_: Throwable) { true }

    private var flagsAt = 0L
    private var gestureOn = true

    /** Preference + system animation setting, read at most every 1.5 s instead of on every touch event. */
    private fun gestureAllowed(context: Context): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - flagsAt > FLAG_CACHE_MS) {
            flagsAt = now
            gestureOn = touchEnabled() && animationsOn(context)
        }
        return gestureOn
    }

    private fun animationsOn(context: Context): Boolean = try {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    } catch (_: Throwable) { true }

    private fun attach(activity: Activity) = attachWindow(activity.window, activity)

    private fun attachWindow(window: Window, context: Context) {
        // Liquid glass off: no ripple layer and no bubble sheen anywhere (the dock bar has its own touch effects).
        if (GlassMode.plain()) return
        try {
            val decor = window.decorView as? ViewGroup ?: return
            if (window.callback is TouchCallback) return
            val overlay = LiquidRippleOverlay(context)
            decor.addView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            window.callback = TouchCallback(window.callback, overlay, context)

            var scheduled = false
            var nextDelay = SCAN_DELAY_MS
            val scan = object : Runnable {
                override fun run() {
                    // Never scan the view tree while a finger is down (scrolling, dragging): it waits for the touch to end.
                    if (overlay.isBusy()) { decor.postDelayed(this, 250L); return }
                    scheduled = false
                    val started = System.nanoTime()
                    if (bubbleEnabled() && animationsOn(context)) scanForButtons(decor)
                    // thick, readable, 3D text and buttery scrolling on every glass screen
                    try { Text3D.styleTree(decor) } catch (_: Throwable) {}
                    // A heavy screen (a long, streaming chat) is scanned less often, in proportion to what a scan costs.
                    val costMs = (System.nanoTime() - started) / 1_000_000L
                    nextDelay = (costMs * 14L).coerceIn(SCAN_DELAY_MS, 2500L)
                }
            }
            decor.viewTreeObserver.addOnGlobalLayoutListener {
                if (!scheduled) { scheduled = true; decor.postDelayed(scan, nextDelay) }
            }
            scheduled = true
            decor.postDelayed(scan, SCAN_DELAY_MS)
        } catch (_: Throwable) {
            // Visual polish only: never let it break a screen.
        }
    }

    /** Watches touches for the ripple layer and hands every event on, unchanged. */
    private class TouchCallback(
        private val base: Window.Callback,
        private val overlay: LiquidRippleOverlay,
        private val context: Context
    ) : Window.Callback by base {
        override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
            if (event != null) {
                val ending = event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL
                // A gesture that started must always be able to finish, even if the setting flipped meanwhile.
                if (ending || gestureAllowed(context)) {
                    try { overlay.onTouch(event, true) } catch (_: Throwable) {}
                }
            }
            return base.dispatchTouchEvent(event)
        }
    }

    // ---- 3D bubble sheen on buttons ----

    private fun scanForButtons(view: View, insideGlass: Boolean = false) {
        if (view is LiquidRippleOverlay) return
        var glassHere = insideGlass || view is LiquidGlassLayout || view.background is LiquidGlassDrawable
        if (view.getTag(SHEEN_TAG_KEY) == null) {
            if (qualifiesAsIcon(view)) applyPod(view)
            else if (qualifies(view)) applySheen(view)
            else if (surfaceLook(view)?.let { (corner, k) -> applySheen(view, corner, k); true } == true) Unit
            else if (!glassHere && qualifiesAsGlassCard(view)) {
                // A plain shape-drawable card anywhere in the app becomes the same liquid glass as the Settings cards.
                val d = view.resources.displayMetrics.density
                LiquidGlass.apply(view, 24f, 0, soft = view.height < 96f * d)
                glassHere = true
            }
        }
        ContrastGuard.check(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) scanForButtons(view.getChildAt(i), glassHere)
        }
    }

    private fun qualifiesAsGlassCard(v: View): Boolean {
        if (v !is ViewGroup || v is MaterialCardView || v is TextInputLayout || v.visibility != View.VISIBLE) return false
        if (v.getTag(SKIP_TAG_KEY) == true || v.getTag(SHEEN_TAG_KEY) != null) return false
        val bg = v.background
        val shape = bg is android.graphics.drawable.GradientDrawable ||
            (bg is LayerDrawable && bg !is RippleDrawable && !v.isClickable)
        if (!shape) return false
        val d = v.resources.displayMetrics.density
        val screenW = v.resources.displayMetrics.widthPixels
        return v.width >= 120f * d && v.height >= 56f * d && v.height <= 420f * d && v.width <= screenW
    }

    /** A plain, clickable icon (no background of its own) of a normal icon-button size: it gets its own glass pod. */
    private fun qualifiesAsIcon(v: View): Boolean {
        if (v.getTag(SKIP_TAG_KEY) == true || DockPods.autoPod(v) != null) return false
        val iconLike = v is ImageButton || (v is android.widget.ImageView && v.imageTintList != null)
        if (!iconLike || !v.isClickable || !v.isEnabled || v.visibility != View.VISIBLE) return false
        val d = v.resources.displayMetrics.density
        val w = v.width / d
        val h = v.height / d
        if (w < 30f || h < 30f || w > 60f || h > 60f || maxOf(w, h) / minOf(w, h) > 1.35f) return false
        val bg = v.background
        return bg == null || (bg is RippleDrawable && bg.numberOfLayers == 0)
    }

    /** Cards and text fields get the same 3D bubble look, quieter because they are big. Returns (corner radius px, intensity). */
    private fun surfaceLook(v: View): Pair<Float, Float>? {
        if (v.visibility != View.VISIBLE || v.getTag(SKIP_TAG_KEY) == true) return null
        val d = v.resources.displayMetrics.density
        val w = v.width / d
        val h = v.height / d
        if (v is MaterialCardView) {
            if (w < 64f || h < 40f) return null
            if (android.graphics.Color.alpha(v.cardBackgroundColor.defaultColor) < 40) return null // see-through cards stay as designed
            return v.radius to (if (h > 220f) 0.38f else 0.55f)
        }
        if (v is TextInputLayout) {
            if (w < 80f || h < 40f || h > 96f || !v.helperText.isNullOrEmpty() || v.isErrorEnabled) return null
            return v.boxCornerRadiusTopStart to 0.36f
        }
        return null
    }

    private fun applyPod(v: View) {
        val d = v.resources.displayMetrics.density
        DockPods.installAuto(v, (minOf(v.width, v.height) / d - 4f).coerceIn(28f, 48f))
    }

    private fun qualifies(v: View): Boolean {
        if (DockPods.autoPod(v) != null) return false
        if (v.visibility != View.VISIBLE || v.width == 0 || v.height == 0) return false
        val isButton = (v is Button && v !is CompoundButton) || v is ImageButton || v is Chip
        if (!isButton || v.getTag(SKIP_TAG_KEY) == true) return false
        val d = v.resources.displayMetrics.density
        if (v.height < 28f * d || v.height > 120f * d || v.background == null) return false
        if (v is MaterialButton) {
            val tint = v.backgroundTintList
            if (tint != null && android.graphics.Color.alpha(tint.defaultColor) < 40) return false // text / outlined buttons
        }
        val bg = v.background
        if (bg is RippleDrawable && bg.numberOfLayers > 0 && try { bg.getDrawable(0) is LayerDrawable } catch (_: Throwable) { false }) return false // already a glass chip
        return true
    }

    /**
     * Gives a view built in code (for example the Updates cards) the same 3D bubble look. [accent] tints the pooled light, so each
     * card can glow in its own colour. Safe to call before the view is laid out.
     */
    fun decorate(v: View, cornerPx: Float, intensity: Float = 0.55f, accent: Int? = null) {
        if (GlassMode.plain() || v.getTag(SHEEN_TAG_KEY) != null || !bubbleEnabled()) return
        try { applySheen(v, cornerPx, intensity, accent) } catch (_: Throwable) {}
    }

    /** Removes the bubble sheen from [v] (used before re-decorating with a new shape). */
    fun undecorate(v: View) {
        (v.getTag(SHEEN_TAG_KEY) as? android.graphics.drawable.Drawable)?.let { v.overlay.remove(it) }
        v.setTag(SHEEN_TAG_KEY, null)
    }

    private fun applySheen(v: View, cornerOverride: Float? = null, intensity: Float = 1f, accentOverride: Int? = null) {
        val d = v.resources.displayMetrics.density
        val light = try { ThemeManager.isCurrentThemeLight() } catch (_: Throwable) { false }
        val accent = accentOverride ?: try { ThemeManager.getThemeAccentColor() } catch (_: Throwable) { android.graphics.Color.WHITE }
        val corner = cornerOverride ?: ((v as? MaterialButton)?.cornerRadius?.toFloat() ?: 0f)
        val sheen = Bubble3DSheen(d, light, corner, intensity, accent)
        sheen.setBounds(0, 0, v.width, v.height)
        v.overlay.add(sheen)
        v.setTag(SHEEN_TAG_KEY, sheen)
        v.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val w = r - l
            val h = b - t
            if (w > 0 && h > 0 && (sheen.bounds.width() != w || sheen.bounds.height() != h)) sheen.setBounds(0, 0, w, h)
        }
    }
}
