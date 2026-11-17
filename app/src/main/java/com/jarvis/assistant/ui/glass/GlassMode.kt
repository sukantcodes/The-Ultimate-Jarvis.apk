package com.jarvis.assistant.ui.glass

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R

/**
 * The one switch behind the "Liquid Glass" setting. It is on by default. When it is off the app falls back to its original
 * simple design everywhere EXCEPT the bottom dock bar: the dock is locked and keeps its glass whatever this says.
 *
 * Every glass entry point asks [enabled] when it is about to apply itself, and the app restarts when the setting changes,
 * so each screen is rebuilt in the chosen look.
 */
object GlassMode {

    /** True while the liquid glass look is on (the default). */
    fun enabled(): Boolean = try { JarvisApp.instance.preferences.liquidGlassEnabled } catch (_: Throwable) { true }

    /** Opposite of [enabled]; reads better at the call sites that skip glass. */
    fun plain(): Boolean = !enabled()

    /** Stores the new choice and restarts the app so every screen is rebuilt in that look. */
    fun setEnabledAndRestart(activity: Activity, on: Boolean) {
        JarvisApp.instance.preferences.liquidGlassEnabled = on
        restart(activity)
    }

    /** Relaunches the app from its launcher screen with a clean task, so nothing keeps the old look. */
    fun restart(activity: Activity) {
        val launch = activity.packageManager.getLaunchIntentForPackage(activity.packageName)
        if (launch == null) { activity.recreate(); return }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        activity.startActivity(launch)
        activity.finishAffinity()
        @Suppress("DEPRECATION")
        activity.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    /** A flat themed card background, used where the glass look is off and the view has no background of its own. */
    fun plainCardBackground(context: Context, radiusPx: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(attrColor(context, R.attr.themeSurface, Color.parseColor("#1E1E2A")))
            setStroke(
                (1f * context.resources.displayMetrics.density).toInt().coerceAtLeast(1),
                attrColor(context, R.attr.themeChipBg, Color.parseColor("#33FFFFFF"))
            )
        }

    fun attrColor(context: Context, attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        return if (context.theme.resolveAttribute(attr, tv, true) && tv.type >= TypedValue.TYPE_FIRST_COLOR_INT &&
            tv.type <= TypedValue.TYPE_LAST_COLOR_INT) tv.data else fallback
    }

    /** True when [view] should keep its glass: glass is on. (Convenience for code that takes a view.) */
    @Suppress("UNUSED_PARAMETER")
    fun on(view: View?): Boolean = enabled()
}
