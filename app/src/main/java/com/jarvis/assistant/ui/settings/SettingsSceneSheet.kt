package com.jarvis.assistant.ui.settings

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.ui.glass.AuroraClock
import com.jarvis.assistant.ui.glass.AuroraField
import com.jarvis.assistant.ui.glass.GlassBackdropView
import com.jarvis.assistant.ui.glass.GlassMode
import com.jarvis.assistant.ui.glass.LiquidGlass
import com.jarvis.assistant.ui.glass.SceneRenderer
import com.jarvis.assistant.ui.glass.ScreenSnapshot
import com.jarvis.assistant.ui.glass.SettingsScene
import com.jarvis.assistant.ui.glass.ViewSprings
import com.jarvis.assistant.util.JarvisFonts
import com.jarvis.assistant.util.ThemeManager

/** The picker for the Settings background. Every tile is a live 4K-crisp preview of its scene, drawn at the tile's own size. */
object SettingsSceneSheet {

    /** Label for the scene that is currently shown, used as the Appearance row's subtitle. */
    fun currentLabel(): String {
        val key = try { JarvisApp.instance.preferences.settingsScene } catch (_: Throwable) { "auto" }
        val scene = SettingsScene.fromKey(key)
        return if (scene == null) "Auto • " + SettingsScene.effective().label else scene.label
    }

    fun show(activity: Activity, onChanged: () -> Unit = {}) {
        val dialog = BottomSheetDialog(activity, R.style.ThemeOverlay_Jarvis_BottomSheet)
        val dp = { v: Int -> UiKit.dp(activity, v) }
        val prefs = JarvisApp.instance.preferences
        val light = ThemeManager.isCurrentThemeLight()
        val accent = ThemeManager.getThemeAccentColor()
        val baseBg = ThemeManager.getThemeBgColor(prefs.appTheme, prefs.customThemeColor)
        val surface = GlassMode.attrColor(activity, R.attr.themeSurface, baseBg)
        val border = GlassMode.attrColor(activity, R.attr.themeCardBorder, ColorUtils.setAlphaComponent(accent, 90))
        val sheetBg = ColorUtils.compositeColors(
            ColorUtils.setAlphaComponent(surface, if (light) 238 else 224),
            baseBg
        )

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(24))
            background = GradientDrawable().apply {
                setColor(sheetBg)
                val r = dp(28).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                setStroke(dp(1), border)
            }
        }

        // Top drag handle
        val handle = View(activity).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setColor(ColorUtils.setAlphaComponent(UiKit.color(activity, R.attr.themeTextMuted, accent), 120))
            }
        }
        root.addView(handle, LinearLayout.LayoutParams(dp(42), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(14)
        })

        // Header row with title + live badge
        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleView = UiKit.text(activity, activity.getString(R.string.set_scene_sheet_title), 20f, bold = true).apply {
            typeface = JarvisFonts.displayBold
        }
        headerRow.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val badge = TextView(activity).apply {
            text = "4K LIVE"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = JarvisFonts.hudBold
            letterSpacing = 0.08f
            setTextColor(accent)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(ColorUtils.setAlphaComponent(accent, 34))
                setStroke(dp(1), ColorUtils.setAlphaComponent(accent, 115))
            }
        }
        headerRow.addView(badge)
        root.addView(headerRow)

        root.addView(UiKit.text(activity, activity.getString(R.string.set_scene_sheet_hint), 13f, muted = true).apply {
            setPadding(0, dp(4), 0, dp(14))
        })

        val selectedKey = prefs.settingsScene
        val tiles: List<SettingsScene?> = listOf(null) + SettingsScene.values().toList()
        val scroll = android.widget.ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val grid = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(grid)
        val maxScrollH = (activity.resources.displayMetrics.heightPixels * 0.58f).toInt()
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            // Keep sheet comfortable on large screens while remaining scrollable for all scenes.
            if (tiles.size > 8) height = maxScrollH
        })

        var rowLayout: LinearLayout? = null
        tiles.forEachIndexed { index, scene ->
            if (index % 2 == 0) {
                rowLayout = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                grid.addView(rowLayout, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            val selected = (scene == null && SettingsScene.fromKey(selectedKey) == null) || (scene != null && scene.key == selectedKey)
            val tile = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(5), dp(5), dp(5), dp(10))
                isClickable = true
                isFocusable = true
                minimumHeight = dp(48)
            }
            val thumbWrap = FrameLayout(activity)
            thumbWrap.addView(SceneThumbView(activity, scene, selected), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(96)))
            if (selected) {
                val tick = TextView(activity).apply {
                    text = "✓"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    typeface = JarvisFonts.bold
                    gravity = Gravity.CENTER
                    val onAccent = if (ColorUtils.calculateLuminance(accent) > 0.45) Color.BLACK else Color.WHITE
                    setTextColor(onAccent)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(accent)
                    }
                }
                thumbWrap.addView(tick, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.END).apply {
                    setMargins(0, dp(7), dp(7), 0)
                })
            }
            tile.addView(thumbWrap, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(96)))
            tile.addView(UiKit.text(activity, scene?.label ?: "Auto", 14f, bold = true).apply {
                typeface = JarvisFonts.semibold
                setPadding(dp(4), dp(6), dp(4), 0)
            })
            tile.addView(UiKit.text(activity, scene?.blurb ?: "Matches your theme", 11f, muted = true).apply {
                setPadding(dp(4), dp(1), dp(4), 0)
                maxLines = 2
            })
            tile.setOnClickListener { v ->
                ViewSprings.pop(v, 0.94f, 1f)
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
        dialog.setOnShowListener {
            LiquidGlass.blurBehind(dialog, radiusDp = 22f, dim = if (light) 0.18f else 0.34f)
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
                sheet.background = ColorDrawable(Color.TRANSPARENT)
                BottomSheetBehavior.from(sheet).apply {
                    state = BottomSheetBehavior.STATE_EXPANDED
                    skipCollapsed = true
                }
            }
        }
        dialog.show()
    }

    /** A rounded live preview of one scene. Animates smoothly while visible on screen. */
    private class SceneThumbView(context: Context, private val scene: SettingsScene?, private val selected: Boolean) : View(context) {
        private val renderer = SceneRenderer(resources.displayMetrics.density)
        private val clip = Path()
        private val rect = RectF()
        private val visibleRect = Rect()
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.6f * resources.displayMetrics.density }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * resources.displayMetrics.density }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = JarvisFonts.hudBold }
        private var registered = false

        private val heartbeat = Runnable {
            if (isShown && getLocalVisibleRect(visibleRect)) invalidate()
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            val animated = !GlassMode.plain() && scene != null && scene != SettingsScene.SEE_THROUGH
            if (animated && !registered) {
                AuroraClock.register(heartbeat)
                registered = true
            }
        }

        override fun onDetachedFromWindow() {
            if (registered) {
                AuroraClock.unregister(heartbeat)
                registered = false
            }
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val r = 16f * resources.displayMetrics.density
            rect.set(0f, 0f, w, h)
            clip.reset(); clip.addRoundRect(rect, r, r, Path.Direction.CW)
            val light = try { ThemeManager.isCurrentThemeLight() } catch (_: Throwable) { false }
            val accent = try { ThemeManager.getThemeAccentColor() } catch (_: Throwable) { Color.CYAN }
            val secondary = try { ThemeManager.getThemeSecondaryColor() } catch (_: Throwable) { Color.MAGENTA }
            val bg = try {
                val p = JarvisApp.instance.preferences
                ThemeManager.getThemeBgColor(p.appTheme, p.customThemeColor)
            } catch (_: Throwable) {
                GlassMode.attrColor(context, R.attr.themeBg, if (light) Color.WHITE else Color.BLACK)
            }
            val ink = GlassMode.attrColor(context, R.attr.themeTextPrimary, if (light) Color.BLACK else Color.WHITE)
            canvas.save()
            canvas.clipPath(clip)
            canvas.drawColor(bg)
            when (scene) {
                null -> {
                    fill.shader = LinearGradient(0f, 0f, w, h, accent, secondary, Shader.TileMode.CLAMP)
                    fill.alpha = 165
                    canvas.drawRect(rect, fill)
                    fill.shader = null; fill.alpha = 255
                    label.textSize = 13f * resources.displayMetrics.density
                    label.color = if (ColorUtils.calculateLuminance(ColorUtils.blendARGB(accent, secondary, 0.5f)) > 0.55) Color.BLACK else Color.WHITE
                    canvas.drawText("AUTO", w / 2f, h / 2f + label.textSize / 3f, label)
                }
                SettingsScene.AURORA -> AuroraField.draw(canvas, w, h, light, 0.95f)
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
                else -> renderer.draw(scene, canvas, w, h, 18f + AuroraField.phase, light, accent, secondary, ink)
            }
            canvas.restore()
            if (selected) {
                ring.color = accent
                val inset = ring.strokeWidth / 2f
                rect.set(inset, inset, w - inset, h - inset)
                canvas.drawRoundRect(rect, r, r, ring)
            } else {
                borderPaint.color = ColorUtils.setAlphaComponent(ink, if (light) 34 else 48)
                val inset = borderPaint.strokeWidth / 2f
                rect.set(inset, inset, w - inset, h - inset)
                canvas.drawRoundRect(rect, r, r, borderPaint)
            }
        }
    }
}
