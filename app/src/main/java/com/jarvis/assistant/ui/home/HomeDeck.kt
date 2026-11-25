package com.jarvis.assistant.ui.home

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.graphics.ColorUtils
import com.jarvis.assistant.R
import com.jarvis.assistant.databinding.FragmentHomeBinding
import com.jarvis.assistant.util.ThemeManager

/**
 * The look of the Home screen. Every shortcut becomes a hairline-bordered glass tile with its own colour: the colours are the
 * active theme's accent rotated around the colour wheel, so each theme gets a colourful but coherent Home in its own palette.
 * Surfaces are painted once (static gradients), so nothing flickers, and tiles only react to touch with a short press scale.
 * This object restyles views that already exist; it never changes what a tile does.
 */
object HomeDeck {

    private fun dp(ctx: Context, v: Float): Float = v * ctx.resources.displayMetrics.density
    private fun px(ctx: Context, v: Float): Int = (dp(ctx, v) + 0.5f).toInt().coerceAtLeast(1)

    private fun themeColor(ctx: Context, attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        return if (ctx.theme.resolveAttribute(attr, tv, true)) tv.data else fallback
    }

    private fun alpha(color: Int, a: Float): Int = ColorUtils.setAlphaComponent(color, (a * 255f).toInt().coerceIn(0, 255))

    private fun rotate(color: Int, degrees: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[0] = (hsv[0] + degrees) % 360f
        hsv[1] = hsv[1].coerceIn(0.5f, 0.9f)
        hsv[2] = hsv[2].coerceIn(0.75f, if (ThemeManager.isCurrentThemeLight()) 0.85f else 1f)
        return Color.HSVToColor(hsv)
    }

    /** A glass tile: theme card colour, a diagonal wash of [tone], a hairline border and a faint highlight along the top edge. */
    private fun tile(ctx: Context, radiusDp: Float, tone: Int, wash: Float): Drawable {
        val r = dp(ctx, radiusDp)
        val fill = themeColor(ctx, R.attr.themeCardBg, alpha(Color.WHITE, 0.06f))
        val border = themeColor(ctx, R.attr.themeCardBorder, alpha(Color.WHITE, 0.14f))
        val text = themeColor(ctx, R.attr.themeTextPrimary, Color.WHITE)
        val base = LayerDrawable(arrayOf(
            GradientDrawable().apply { setColor(fill); cornerRadius = r },
            GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(alpha(tone, wash), alpha(tone, 0f))).apply { cornerRadius = r },
            GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = r; setStroke(px(ctx, 1f), border) },
            GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.TRANSPARENT, alpha(text, 0.22f), Color.TRANSPARENT))
        ))
        val i = 3
        base.setLayerInset(i, px(ctx, radiusDp * 0.7f), px(ctx, 1f), px(ctx, radiusDp * 0.7f), 0)
        base.setLayerHeight(i, px(ctx, 1f))
        base.setLayerGravity(i, Gravity.TOP)
        val mask = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = r }
        return RippleDrawable(ColorStateList.valueOf(alpha(tone, 0.28f)), base, mask)
    }

    /** The squircle behind a tile's icon: a soft wash of the tile colour with a matching hairline. */
    private fun badge(ctx: Context, tone: Int, radiusDp: Float): Drawable = GradientDrawable().apply {
        cornerRadius = dp(ctx, radiusDp)
        colors = intArrayOf(alpha(tone, 0.34f), alpha(tone, 0.12f))
        orientation = GradientDrawable.Orientation.TL_BR
        setStroke(px(ctx, 1f), alpha(tone, 0.45f))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun press(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.965f).scaleY(0.965f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
            }
            false
        }
    }

    private fun styleTile(v: View, tone: Int) {
        val ctx = v.context
        v.background = tile(ctx, 22f, tone, 0.16f)
        (v as? ViewGroup)?.getChildAt(0)?.let { icon ->
            if (icon is ImageView) {
                icon.background = badge(ctx, tone, 12f)
                icon.imageTintList = ColorStateList.valueOf(tone)
            }
        }
        press(v)
    }

    fun apply(b: FragmentHomeBinding) {
        val ctx = b.root.context
        val accent = ThemeManager.getThemeAccentColor()
        val tones = intArrayOf(accent, rotate(accent, 50f), rotate(accent, 110f), rotate(accent, 170f), rotate(accent, 230f), rotate(accent, 290f))

        // Weather: a wide glass bar with a coloured badge.
        b.cardWeather.background = tile(ctx, 26f, tones[1], 0.14f)
        b.ivWeatherIcon.background = badge(ctx, tones[1], 15f)
        b.ivWeatherIcon.imageTintList = ColorStateList.valueOf(tones[1])
        press(b.cardWeather)

        // Shortcuts and quick actions: one colour each.
        styleTile(b.btnHomeStudyBoard, rotate(accent, 325f))
        styleTile(b.btnHomeAutomation, tones[0])
        styleTile(b.btnHomeNetwork, tones[2])
        styleTile(b.btnHomeMaya, tones[3])
        styleTile(b.btnScreenShareQuick, tones[4])
        styleTile(b.btnSavedMemories, tones[1])
        styleTile(b.btnConversationLanguageQuick, tones[5])

        // Header buttons.
        for (btn in listOf(b.btnTopChat, b.btnTopSettings)) {
            btn.background = tile(ctx, 17f, accent, 0.12f)
            press(btn)
        }
    }
}
