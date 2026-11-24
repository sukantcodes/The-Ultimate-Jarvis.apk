package com.jarvis.assistant.ui.glass

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * Gives every screen the Settings page's glass: one shared backdrop behind the screen, and glass cards on top of it.
 * Screens that already ask for it ([LiquidGlass.installBackdrop], [LiquidGlass.glassify]) get the same result through
 * those calls; every other screen gets it here, automatically, once it has started.
 */
object GlassScreens {

    private const val MARK_KEY = 0x4A474C54 // "JGLT": marks an activity's content frame that already has the backdrop
    private const val CARDS_KEY = 0x4A474C55 // "JGLU": marks a screen whose cards were already turned into glass

    /** Screens with their own full-screen visuals or overlays; they keep their design. */
    private val SKIP = setOf(
        "MainActivity", "AlarmRingActivity", "CameraScanActivity", "JarvisTerminalActivity", "OnboardingActivity"
    )

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) = attach(activity)
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun attach(activity: Activity) {
        if (GlassMode.plain() || activity.javaClass.simpleName in SKIP || isTranslucent(activity)) return
        // Screens already built on the Settings glass keep their own set-up (SettingsGlassKit and the Settings screens).
        if (activity is com.jarvis.assistant.ui.settings.SettingsScreenActivity ||
            activity is com.jarvis.assistant.util.JarvisHandlesInsets ||
            SettingsGlassKit.isTarget(activity)
        ) return
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (content.childCount == 0) return
        val screen = content.getChildAt(0)
        ensureBackdrop(screen)
        if (content.getTag(CARDS_KEY) != null) return
        content.setTag(CARDS_KEY, true)
        // Cards are found after the first layout, and once more shortly after for views a screen adds late.
        LiquidGlass.glassify(screen)
        screen.postDelayed({ LiquidGlass.glassifyNow(screen) }, 700L)
    }

    private fun isTranslucent(activity: Activity): Boolean {
        val ta = activity.theme.obtainStyledAttributes(intArrayOf(android.R.attr.windowIsTranslucent))
        return try { ta.getBoolean(0, false) } finally { ta.recycle() }
    }

    internal fun activityOf(ctx: Context): Activity? {
        var c: Context? = ctx
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    private fun containsBackdrop(v: View): Boolean {
        if (v is GlassBackdropView) return true
        if (v is ViewGroup) for (i in 0 until v.childCount) if (containsBackdrop(v.getChildAt(i))) return true
        return false
    }

    /**
     * Puts the shared backdrop behind the screen of [root] (once per screen) and clears the screen's own painted
     * background so the scene shows through. The backdrop is added to the window itself, behind everything, so it
     * reaches every edge of the screen and no layout, id or constraint of the screen changes at all.
     */
    fun ensureBackdrop(root: View) {
        val activity = activityOf(root.context) ?: return
        val decor = activity.window?.decorView as? ViewGroup ?: return
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (content.getTag(MARK_KEY) == null && !containsBackdrop(decor)) {
            decor.addView(GlassBackdropView(activity), 0, FrameLayout.LayoutParams(-1, -1))
            content.setTag(MARK_KEY, true)
        }
        clearBackground(root)
        if (content.childCount > 0) clearBackground(content.getChildAt(0))
    }

    /** True when [view] belongs to a screen whose background is the shared backdrop, so nothing may paint over it. */
    fun coversBackground(view: View): Boolean {
        if (GlassMode.plain()) return false
        val activity = activityOf(view.context) ?: return false
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return false
        return content.getTag(MARK_KEY) != null
    }

    private fun clearBackground(v: View) {
        val bg = v.background ?: return
        if (bg is RippleDrawable || v is GlassBackdropView) return
        v.background = null
    }
}
