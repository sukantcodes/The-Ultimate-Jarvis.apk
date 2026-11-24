package com.jarvis.assistant.ui.settings

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.ui.glass.AuroraField
import com.jarvis.assistant.ui.glass.GlassBackdropView
import com.jarvis.assistant.ui.glass.GlassMode
import com.jarvis.assistant.ui.glass.ScreenSnapshot
import com.jarvis.assistant.ui.glass.SceneRenderer
import com.jarvis.assistant.ui.glass.SettingsScene
import com.jarvis.assistant.util.ThemeManager

/** The picker for the Settings background. Every tile is a live preview of its scene, drawn at the tile's own size. */
object SettingsSceneSheet {

    /** Label for the scene that is currently shown, used as the Appearance row's subtitle. */
    fun currentLabel(): String {
        val key = try { JarvisApp.instance.preferences.settingsScene } catch (_: Throwable) { "auto" }
        val scene = SettingsScene.fromKey(key)
        return if (scene == null) "Auto • " + SettingsScene.effective().label else scene.label
    }

    fun show(activity: Activity, onChanged: () -> Unit = {}) {
        val dialog = BottomSheetDialog(activity)
        val dp = { v: Int -> UiKit.dp(activity, v) }
        val surface = GlassMode.attrColor(activity, R.attr.themeSurface, Color.parseColor("#1E1E2A"))

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(26))
            setBackgroundColor(surface)
        }
        root.addView(UiKit.text(activity, activity.getString(R.string.set_scene_sheet_title), 20f, bold = true))
        root.addView(UiKit.text(activity, activity.getString(R.string.set_scene_sheet_hint), 13f, muted = true).apply {
            setPadding(0, dp(4), 0, dp(14))
        })

        val prefs = JarvisApp.instance.preferences
        val selectedKey = prefs.settingsScene
        val tiles: List<SettingsScene?> = listOf(null) + SettingsScene.values().toList()
        val scroll = android.widget.ScrollView(activity).apply { isVerticalScrollBarEnabled = false }
        val grid = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(grid)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        var rowLayout: LinearLayout? = null
        tiles.forEachIndexed { index, scene ->
            if (index % 2 == 0) {
                rowLayout = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                grid.addView(rowLayout, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            val selected = (scene == null && SettingsScene.fromKey(selectedKey) == null) || (scene != null && scene.key == selectedKey)
            val tile = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(5), dp(5), dp(5), dp(9))
            }
            tile.addView(SceneThumbView(activity, scene, selected), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(92)))
            tile.addView(UiKit.text(activity, scene?.label ?: "Auto", 14f, bold = true).apply { setPadding(dp(4), dp(6), 0, 0) })
            tile.addView(UiKit.text(activity, scene?.blurb ?: "Matches your theme", 11f, muted = true).apply { setPadding(dp(4), 0, dp(4), 0) })
            tile.setOnClickListener {
                prefs.settingsScene = scene?.key ?: "auto"
                dialog.dismiss()
                GlassBackdropView.refreshAll()
                if (SettingsScene.effective() == SettingsScene.SEE_THROUGH) {
                    Toast.makeText(activity, "See-through shows the screen underneath as you move between screens.", Toast.LENGTH_LONG).show()
                }
                onChanged()
            }
            rowLayout!!.addView(tile, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        if (tiles.size % 2 == 1) rowLayout?.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))

        dialog.setContentView(root)
        dialog.show()
    }

    /** A rounded live preview of one scene. */
    private class SceneThumbView(context: Context, private val scene: SettingsScene?, private val selected: Boolean) : View(context) {
        private val renderer = SceneRenderer(resources.displayMetrics.density)
        private val clip = Path()
        private val rect = RectF()
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.6f * resources.displayMetrics.density }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val r = 16f * resources.displayMetrics.density
            rect.set(0f, 0f, w, h)
            clip.reset(); clip.addRoundRect(rect, r, r, Path.Direction.CW)
            val light = try { ThemeManager.isCurrentThemeLight() } catch (_: Throwable) { false }
            val accent = try { ThemeManager.getThemeAccentColor() } catch (_: Throwable) { Color.CYAN }
            val secondary = try { ThemeManager.getThemeSecondaryColor() } catch (_: Throwable) { Color.MAGENTA }
            val bg = GlassMode.attrColor(context, R.attr.themeBg, if (light) Color.WHITE else Color.BLACK)
            val ink = GlassMode.attrColor(context, R.attr.themeTextPrimary, if (light) Color.BLACK else Color.WHITE)
            canvas.save()
            canvas.clipPath(clip)
            canvas.drawColor(bg)
            when (scene) {
                null -> {
                    fill.shader = LinearGradient(0f, 0f, w, h, accent, secondary, Shader.TileMode.CLAMP)
                    fill.alpha = 150
                    canvas.drawRect(rect, fill)
                    fill.shader = null; fill.alpha = 255
                    label.textSize = 13f * resources.displayMetrics.density
                    canvas.drawText("AUTO", w / 2f, h / 2f + label.textSize / 3f, label)
                }
                SettingsScene.AURORA -> AuroraField.draw(canvas, w, h, light, 0.9f)
                SettingsScene.SEE_THROUGH -> {
                    val bmp = ScreenSnapshot.bitmap
                    if (bmp != null) {
                        canvas.drawBitmap(bmp, null, rect, null)
                    } else {
                        fill.shader = LinearGradient(0f, 0f, w, h, intArrayOf(accent, bg, secondary), null, Shader.TileMode.CLAMP)
                        fill.alpha = 120
                        canvas.drawRect(rect, fill)
                        fill.shader = null; fill.alpha = 255
                    }
                    fill.color = Color.argb(70, 255, 255, 255)
                    canvas.drawCircle(w / 2f, h / 2f, h * 0.3f, fill)
                }
                else -> renderer.draw(scene, canvas, w, h, 24f, light, accent, secondary, ink)
            }
            canvas.restore()
            if (selected) {
                ring.color = accent
                canvas.drawRoundRect(rect.apply { inset(ring.strokeWidth / 2f, ring.strokeWidth / 2f) }, r, r, ring)
            }
        }
    }
}
