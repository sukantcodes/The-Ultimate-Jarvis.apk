package com.jarvis.assistant.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.graphics.ColorUtils
import com.jarvis.assistant.R

/**
 * The look of the Settings screen: "Control Deck".
 *
 * Flat, hairline-bordered surfaces with a soft sheen of the accent colour in one corner, squircle icon tiles, a rail of
 * section chips, a gradient-ringed identity card and a large title that condenses as you scroll. Every colour comes from the
 * active JARVIS theme (card, border, text and accent attributes), so each theme gets the same layout in its own colours.
 *
 * This object only paints views that already exist. It never creates, moves or re-wires a setting, so every row keeps its
 * click handler, switch state and search behaviour.
 */
object SettingsDeck {

    /** One chip in the category rail. */
    class Category(val label: String, val color: Int)

    // ------------------------------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------------------------------

    private fun dp(ctx: Context, v: Float): Float = v * ctx.resources.displayMetrics.density
    private fun px(ctx: Context, v: Float): Int = (dp(ctx, v) + 0.5f).toInt().coerceAtLeast(1)

    private fun themeColor(ctx: Context, attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        return if (ctx.theme.resolveAttribute(attr, tv, true)) tv.data else fallback
    }

    private fun alpha(color: Int, a: Float): Int = ColorUtils.setAlphaComponent(color, (a * 255f).toInt().coerceIn(0, 255))

    private fun pill(ctx: Context, fill: Int, stroke: Int, radiusDp: Float, strokeDp: Float = 1f): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(ctx, radiusDp)
            setStroke(px(ctx, strokeDp), stroke)
        }

    /** Clips a view (and so its press ripples) to a rounded rectangle. */
    private fun round(view: View, radiusDp: Float) {
        val r = dp(view.context, radiusDp)
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, r)
            }
        }
        view.clipToOutline = true
    }

    /**
     * The deck surface: the theme's card colour, an optional diagonal sheen and corner glow in [accent], and a hairline border.
     */
    private fun surface(ctx: Context, radiusDp: Float, accent: Int, sheen: Float, glow: Float = 0f): Drawable {
        val r = dp(ctx, radiusDp)
        val fill = themeColor(ctx, R.attr.themeCardBg, alpha(Color.WHITE, 0.06f))
        val border = themeColor(ctx, R.attr.themeCardBorder, alpha(Color.WHITE, 0.14f))
        val layers = ArrayList<Drawable>()
        layers += GradientDrawable().apply { setColor(alpha(fill, 0.55f)); cornerRadius = r }
        if (sheen > 0f) {
            layers += GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(alpha(accent, sheen), alpha(accent, 0f))
            ).apply { cornerRadius = r }
        }
        if (glow > 0f) {
            layers += GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = dp(ctx, 240f)
                setGradientCenter(0.92f, 0f)
                setColors(intArrayOf(alpha(accent, glow), alpha(accent, 0f)))
                cornerRadius = r
            }
        }
        layers += GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = r
            setStroke(px(ctx, 1f), border)
        }
        return LayerDrawable(layers.toTypedArray())
    }

    // ------------------------------------------------------------------------------------------
    // Surfaces
    // ------------------------------------------------------------------------------------------

    /** A section card: flat, hairline-bordered, with a faint sheen of the section colour. */
    fun styleCard(card: ViewGroup, accent: Int) {
        card.background = surface(card.context, 24f, accent, sheen = 0.07f)
        round(card, 24f)
        card.elevation = 0f
    }

    /** The identity card at the top: a stronger sheen plus a glow in the corner. */
    fun styleHero(card: View, accent: Int) {
        card.background = surface(card.context, 30f, accent, sheen = 0.20f, glow = 0.34f)
        round(card, 30f)
        card.elevation = dp(card.context, 2f)
    }

    /** The search pill: a hairline at rest, the accent colour while it has focus. */
    fun styleSearch(field: EditText, accent: Int) {
        val ctx = field.context
        val fill = themeColor(ctx, R.attr.themeCardBg, alpha(Color.WHITE, 0.06f))
        val border = themeColor(ctx, R.attr.themeCardBorder, alpha(Color.WHITE, 0.14f))
        val muted = themeColor(ctx, R.attr.themeTextMuted, Color.GRAY)
        field.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), pill(ctx, fill, alpha(accent, 0.85f), 25f, 1.5f))
            addState(intArrayOf(), pill(ctx, fill, border, 25f))
        }
        field.compoundDrawableTintList = ColorStateList.valueOf(muted)
    }

    /** The round badge beside the title. */
    fun styleOrb(frame: View, accent: Int) {
        val ctx = frame.context
        frame.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(alpha(accent, 0.30f), alpha(accent, 0.06f))
        ).apply {
            shape = GradientDrawable.OVAL
            setStroke(px(ctx, 1f), alpha(accent, 0.45f))
        }
        val icon = (frame as? ViewGroup)?.getChildAt(0) as? ImageView
        icon?.imageTintList = ColorStateList.valueOf(accent)
    }

    /** A thin gradient ring around the profile photo (the photo sits on top with the frame's padding as ring width). */
    fun styleAvatarRing(frame: View, accent: Int, secondary: Int) {
        frame.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            gradientType = GradientDrawable.SWEEP_GRADIENT
            setColors(intArrayOf(accent, secondary, accent))
        }
    }

    /** The green "online" dot on the photo's edge, ringed in the card colour so it reads cleanly over the photo. */
    fun styleOnlineDot(dot: View, success: Int) {
        val ctx = dot.context
        val ring = themeColor(ctx, R.attr.themeBg, Color.BLACK)
        dot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(success)
            setStroke(px(ctx, 2.5f), ring)
        }
    }

    /** A small tinted pill (status chips and the edit tile). */
    fun styleChip(view: View, tone: Int, radiusDp: Float = 100f) {
        val ctx = view.context
        view.background = pill(ctx, alpha(tone, 0.14f), alpha(tone, 0.42f), radiusDp)
    }

    /** Section heading: a short vertical bar in the section colour before the label. */
    fun styleSectionHeader(header: TextView, accent: Int) {
        val ctx = header.context
        val bar = GradientDrawable().apply {
            setColor(accent)
            cornerRadius = dp(ctx, 2f)
            setSize(px(ctx, 3f), px(ctx, 14f))
        }
        header.setCompoundDrawablesRelativeWithIntrinsicBounds(bar, null, null, null)
        header.compoundDrawablePadding = px(ctx, 9f)
        header.gravity = Gravity.CENTER_VERTICAL
    }

    /**
     * A section title that folds its card: the accent bar on the left, a chevron on the right (up while open, down while folded),
     * a roomier touch area and a soft press ripple. The title text is left exactly as it is, so search still finds it.
     */
    fun styleCollapsibleHeader(header: TextView, accent: Int, expanded: Boolean) {
        val ctx = header.context
        val bar = GradientDrawable().apply {
            setColor(accent)
            cornerRadius = dp(ctx, 2f)
            setSize(px(ctx, 3f), px(ctx, 14f))
        }
        val chevron = androidx.core.content.ContextCompat.getDrawable(ctx, if (expanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down)?.mutate()
        chevron?.let {
            androidx.core.graphics.drawable.DrawableCompat.setTint(it, alpha(themeColor(ctx, R.attr.themeTextMuted, Color.GRAY), 0.9f))
        }
        header.setCompoundDrawablesRelativeWithIntrinsicBounds(bar, null, chevron, null)
        header.compoundDrawablePadding = px(ctx, 9f)
        header.gravity = Gravity.CENTER_VERTICAL
        header.minHeight = px(ctx, 40f)
        header.setPaddingRelative(px(ctx, 2f), px(ctx, 6f), px(ctx, 6f), px(ctx, 6f))
        header.isClickable = true
        header.isFocusable = true
        if (header.background == null) {
            val tv = TypedValue()
            if (ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
                header.setBackgroundResource(tv.resourceId)
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Icons and switches
    // ------------------------------------------------------------------------------------------

    /**
     * Gives every settings icon its tile. [shape] is the person's "Settings icon shape" choice:
     * 0 = the Control Deck default (squircle), 1 = rectangular, 2 = pillowy, 3 = circular.
     */
    fun styleIcons(root: View, accent: Int, shape: Int) {
        fun visit(view: View) {
            if (view !is ViewGroup) return
            val first = if (view.childCount > 0) view.getChildAt(0) else null
            if (first is FrameLayout) {
                val ctx = view.context
                val w = first.layoutParams?.width ?: 0
                val h = first.layoutParams?.height ?: 0
                val lo = px(ctx, 36f)
                val hi = px(ctx, 44f)
                val icon = if (first.childCount > 0) first.getChildAt(0) else null
                if (w in lo..hi && h in lo..hi && icon is ImageView) tile(first, icon, accent, shape)
            }
            for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        visit(root)
    }

    private fun tile(box: FrameLayout, icon: ImageView, accent: Int, shape: Int) {
        val ctx = box.context
        val d = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(alpha(accent, 0.26f), alpha(accent, 0.07f))
        )
        when (shape) {
            1 -> d.cornerRadius = dp(ctx, 5f)
            2 -> d.cornerRadius = dp(ctx, 16f)
            3 -> d.shape = GradientDrawable.OVAL
            else -> d.cornerRadius = dp(ctx, 12f)
        }
        box.backgroundTintList = null
        box.background = d
        box.elevation = 0f
        box.translationZ = 0f
        box.clipToOutline = false
        icon.imageTintList = ColorStateList.valueOf(accent)
    }

    /** Switches use the accent colour for the track and a white thumb when on. */
    fun styleSwitches(root: View, accent: Int) {
        val ctx = root.context
        val on = intArrayOf(android.R.attr.state_checked)
        val off = intArrayOf(-android.R.attr.state_checked)
        val muted = themeColor(ctx, R.attr.themeTextMuted, Color.GRAY)
        val track = ColorStateList(arrayOf(on, off), intArrayOf(alpha(accent, 0.95f), alpha(muted, 0.30f)))
        val thumb = ColorStateList(arrayOf(on, off), intArrayOf(Color.WHITE, alpha(muted, 0.95f)))
        fun visit(v: View) {
            if (v is SwitchCompat) {
                v.setTrackTintList(track)
                v.setThumbTintList(thumb)
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
        }
        visit(root)
    }

    /** The icon tile dips slightly while a row is pressed and springs back on release. */
    @SuppressLint("ClickableViewAccessibility")
    fun pressFeedback(card: ViewGroup) {
        for (i in 0 until card.childCount) {
            val row = card.getChildAt(i)
            if (!row.isClickable || row !is ViewGroup || row.childCount == 0) continue
            val icon = row.getChildAt(0)
            // The trailing chevron (if the row has one) leans forward while the row is held.
            val chevron = row.getChildAt(row.childCount - 1).takeIf { it is ImageView && !it.isClickable && it !== icon }
            val nudge = dp(row.context, 4f)
            row.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        icon.animate().scaleX(0.88f).scaleY(0.88f).setDuration(90L).start()
                        chevron?.animate()?.translationX(nudge)?.setDuration(120L)?.start()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        icon.animate().scaleX(1f).scaleY(1f).setDuration(240L)
                            .setInterpolator(OvershootInterpolator(2.2f)).start()
                        chevron?.animate()?.translationX(0f)?.setDuration(220L)
                            ?.setInterpolator(OvershootInterpolator(2.4f))?.start()
                    }
                }
                false // never consume: the row's own click handling and ripple carry on as before
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Category rail
    // ------------------------------------------------------------------------------------------

    /**
     * Fills [row] with one chip per category and returns a function that repaints the chips for a selected index.
     * Chip 0 is "All".
     */
    fun buildRail(row: LinearLayout, categories: List<Category>, accent: Int, onPick: (Int) -> Unit): (Int) -> Unit {
        val ctx = row.context
        row.removeAllViews()
        val chips = categories.mapIndexed { index, category ->
            TextView(ctx).apply {
                text = category.label
                textSize = 13f
                isAllCaps = false
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER_VERTICAL
                includeFontPadding = false
                setPadding(px(ctx, 14f), 0, px(ctx, 16f), 0)
                compoundDrawablePadding = px(ctx, 8f)
                isClickable = true
                isFocusable = true
                contentDescription = category.label
                setOnClickListener {
                    animate().cancel()
                    scaleX = 0.9f; scaleY = 0.9f
                    animate().scaleX(1f).scaleY(1f).setDuration(260L).setInterpolator(OvershootInterpolator(2.4f)).start()
                    onPick(index)
                }
                row.addView(this, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, px(ctx, 36f)
                ).apply { marginEnd = px(ctx, 8f) })
            }
        }
        fun paint(selected: Int) {
            val border = themeColor(ctx, R.attr.themeCardBorder, alpha(Color.WHITE, 0.14f))
            val fill = themeColor(ctx, R.attr.themeCardBg, alpha(Color.WHITE, 0.06f))
            val primary = themeColor(ctx, R.attr.themeTextPrimary, Color.WHITE)
            val muted = themeColor(ctx, R.attr.themeTextMuted, Color.GRAY)
            chips.forEachIndexed { index, chip ->
                val color = categories[index].color.takeIf { index != 0 } ?: accent
                val on = index == selected
                chip.isSelected = on
                chip.setTextColor(if (on) primary else muted)
                chip.background = if (on) pill(ctx, alpha(color, 0.20f), alpha(color, 0.80f), 18f)
                else pill(ctx, fill, border, 18f)
                val dot = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (on) color else alpha(color, 0.55f))
                    setSize(px(ctx, 7f), px(ctx, 7f))
                }
                chip.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null)
            }
        }
        paint(0)
        return { selected -> paint(selected) }
    }

    // ------------------------------------------------------------------------------------------
    // Motion
    // ------------------------------------------------------------------------------------------

    private fun animationsOn(ctx: Context): Boolean =
        Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

    /** Blocks rise into place one after another. Skipped when the system has animations switched off. */
    fun enter(views: List<View>, startDelay: Long = 40L) {
        val ctx = views.firstOrNull()?.context ?: return
        if (!animationsOn(ctx)) return
        val lift = dp(ctx, 16f)
        views.forEachIndexed { index, view ->
            if (view.visibility != View.VISIBLE) return@forEachIndexed
            view.alpha = 0f
            view.translationY = lift
            view.animate().alpha(1f).translationY(0f)
                .setStartDelay(startDelay + index * 55L)
                .setDuration(380L)
                .setInterpolator(DecelerateInterpolator(1.8f))
                .start()
        }
    }

    /** A quick fade-and-lift when the category changes, so the swap does not feel like a hard cut. */
    fun swap(content: View) {
        val ctx = content.context
        content.animate().cancel()
        content.alpha = 0f
        content.translationY = dp(ctx, 10f)
        content.animate().alpha(1f).translationY(0f).setDuration(240L)
            .setInterpolator(DecelerateInterpolator(1.6f)).start()
    }

    /**
     * The large title condenses as the page scrolls: it shrinks, the overline, subtitle and badge fade out and a hairline
     * appears under the header.
     */
    fun collapseHeader(
        scrollY: Int,
        title: TextView,
        subtitle: View,
        overline: View,
        badge: View,
        divider: View
    ) {
        val ctx = title.context
        val progress = (scrollY / dp(ctx, 72f)).coerceIn(0f, 1f)
        title.pivotX = 0f
        title.pivotY = title.height / 2f
        val s = 1f - 0.24f * progress
        title.scaleX = s
        title.scaleY = s
        val fade = 1f - progress
        subtitle.alpha = fade
        overline.alpha = fade
        badge.alpha = fade
        divider.alpha = (scrollY / dp(ctx, 24f)).coerceIn(0f, 1f)
    }
}
