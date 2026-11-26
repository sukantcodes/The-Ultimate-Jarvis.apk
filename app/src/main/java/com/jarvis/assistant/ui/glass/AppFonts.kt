package com.jarvis.assistant.ui.glass

import android.app.Activity
import android.app.Application
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import com.jarvis.assistant.util.AppFont
import com.jarvis.assistant.util.JarvisFonts
import java.util.WeakHashMap

/**
 * The picked app font on every piece of text.
 *
 * Two layers make it reach everything:
 *  1. The theme: [ThemeManager.applyTheme][com.jarvis.assistant.util.ThemeManager.applyTheme] lays the font's theme overlay
 *     over the screen's theme, so text without its own font (and dialogs and sheets opened from the screen) starts in it.
 *  2. This object: text that sets its own font in a layout or in code (titles, drawer rows, chips, chat replies...) is moved
 *     to the same family, keeping its weight (regular, medium, semibold, bold) and italics. It runs from the same scans as
 *     the text colours, so it reaches dialogs, sheets and list rows as they appear.
 *
 * Terminal and code (monospace) and the status strip and clock (HUD face) are left as they are.
 */
internal object AppFonts {

    private const val WEIGHT_TAG = 0x4A414631   // weight bucket (0..3) the text had when first seen
    private const val APPLIED_TAG = 0x4A414632  // the typeface this object set last
    private const val FAMILY_TAG = 0x4A414633   // the AppFont key that typeface belongs to

    private val stamps = WeakHashMap<Activity, String>()

    /** Remembers which font a screen was built with, so the screens behind Settings catch up after a change. */
    fun stamp(activity: Activity) { stamps[activity] = AppFont.current.key }

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                val built = stamps[activity] ?: return
                // A screen opened earlier keeps the theme it was built with; rebuild it once if the font changed since.
                if (built != AppFont.current.key && !activity.isFinishing) {
                    stamps[activity] = AppFont.current.key
                    activity.recreate()
                }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /** Moves one label to the picked font. Cheap to call repeatedly (a tag check when nothing changed). */
    fun apply(tv: TextView) {
        try {
            val font = AppFont.current
            val cur = tv.typeface
            val applied = tv.getTag(APPLIED_TAG) as? Typeface
            val ours = applied != null && cur === applied
            if (ours && tv.getTag(FAMILY_TAG) == font.key) return
            if (!ours && isFixedFace(cur)) return

            val weight = if (ours) (tv.getTag(WEIGHT_TAG) as? Int ?: 0) else weightOf(cur)
            var target = JarvisFonts.appFace(weight)
            if (cur != null && cur.isItalic) target = Typeface.create(target, Typeface.ITALIC)
            if (cur !== target) tv.typeface = target
            tv.setTag(WEIGHT_TAG, weight)
            tv.setTag(APPLIED_TAG, tv.typeface)
            tv.setTag(FAMILY_TAG, font.key)
        } catch (_: Throwable) {
            // Visual polish only: never let it break a screen.
        }
    }

    /** Terminal/code faces and the HUD face keep their look. */
    private fun isFixedFace(tf: Typeface?): Boolean {
        if (tf == null) return false
        return tf === JarvisFonts.mono || tf === JarvisFonts.monoMedium || tf === JarvisFonts.monoBold ||
            tf === JarvisFonts.hudMedium || tf === JarvisFonts.hudBold || tf === JarvisFonts.hudBlack ||
            tf === Typeface.MONOSPACE || tf === Typeface.create(Typeface.MONOSPACE, tf.style)
    }

    private fun weightOf(tf: Typeface?): Int {
        if (tf == null) return 0
        if (Build.VERSION.SDK_INT >= 28) {
            val w = tf.weight
            return when {
                w >= 650 -> 3
                w >= 550 -> 2
                w >= 450 -> 1
                else -> 0
            }
        }
        return if (tf.isBold) 3 else 0
    }
}
