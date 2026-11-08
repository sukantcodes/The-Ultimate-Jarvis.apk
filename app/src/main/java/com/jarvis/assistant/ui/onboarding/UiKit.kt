package com.jarvis.assistant.ui.onboarding

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Fixed dark palette: the tutorial is always a dark, cinematic surface regardless of the app theme. */
object Palette {
    val BG = Color.parseColor("#04060D")
    val BG_SOFT = Color.parseColor("#0A1020")
    val CYAN = Color.parseColor("#35D8FF")
    val BLUE = Color.parseColor("#3D7BFF")
    val VIOLET = Color.parseColor("#9B6BFF")
    val MINT = Color.parseColor("#2EE6A6")
    val AMBER = Color.parseColor("#FFB547")
    val ROSE = Color.parseColor("#FF5C7A")
    val TEXT = Color.parseColor("#F2F6FF")
    val TEXT_DIM = Color.parseColor("#9FB0CC")
    val GLASS = Color.parseColor("#14FFFFFF")
    val GLASS_STROKE = Color.parseColor("#26FFFFFF")
}

object Motion {
    /** False when the person (or battery saver) has turned animations off, so every effect falls back to a still frame. */
    fun enabled(@Suppress("UNUSED_PARAMETER") context: Context): Boolean = ValueAnimator.areAnimatorsEnabled()
}

fun View.dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(value: Float): Float = value * resources.displayMetrics.density

fun withAlpha(color: Int, alpha: Int): Int = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

fun glass(context: Context, cornerDp: Int = 20, accent: Int? = null): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = context.dpf(cornerDp.toFloat())
        setColor(if (accent != null) withAlpha(accent, 28) else Palette.GLASS)
        setStroke(context.dp(1), if (accent != null) withAlpha(accent, 110) else Palette.GLASS_STROKE)
    }

fun text(
    context: Context,
    value: CharSequence,
    sizeSp: Float = 15f,
    color: Int = Palette.TEXT,
    bold: Boolean = false,
    gravity: Int = Gravity.START
): TextView = TextView(context).apply {
    text = value
    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sizeSp)
    setTextColor(color)
    this.gravity = gravity
    typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
    setLineSpacing(0f, 1.15f)
    includeFontPadding = false
}

fun pill(context: Context, label: String, color: Int): TextView = TextView(context).apply {
    text = label
    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
    setTextColor(color)
    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    letterSpacing = 0.06f
    includeFontPadding = false
    setPadding(context.dp(10), context.dp(5), context.dp(10), context.dp(5))
    background = GradientDrawable().apply {
        cornerRadius = context.dpf(20f)
        setColor(withAlpha(color, 34))
        setStroke(context.dp(1), withAlpha(color, 120))
    }
}

fun restyle(pill: TextView, label: String, color: Int) {
    pill.text = label
    pill.setTextColor(color)
    pill.background = GradientDrawable().apply {
        cornerRadius = pill.context.dpf(20f)
        setColor(withAlpha(color, 34))
        setStroke(pill.context.dp(1), withAlpha(color, 120))
    }
}

fun primaryButton(context: Context, label: String, onClick: () -> Unit): Button = Button(context).apply {
    text = label
    isAllCaps = false
    stateListAnimator = null
    setTextColor(Color.parseColor("#04101C"))
    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    minHeight = context.dp(52)
    minimumHeight = context.dp(52)
    val shape = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Palette.CYAN, Palette.BLUE, Palette.VIOLET)
    ).apply { cornerRadius = context.dpf(26f) }
    background = RippleDrawable(ColorStateList.valueOf(withAlpha(Color.WHITE, 70)), shape, null)
    setOnClickListener { onClick() }
}

fun secondaryButton(context: Context, label: String, accent: Int = Palette.CYAN, onClick: () -> Unit): Button =
    Button(context).apply {
        text = label
        isAllCaps = false
        stateListAnimator = null
        setTextColor(accent)
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        minHeight = context.dp(48)
        minimumHeight = context.dp(48)
        val shape = glass(context, 24, accent)
        background = RippleDrawable(ColorStateList.valueOf(withAlpha(accent, 60)), shape, null)
        setOnClickListener { onClick() }
    }

fun textButton(context: Context, label: String, color: Int = Palette.TEXT_DIM, onClick: () -> Unit): Button =
    Button(context).apply {
        text = label
        isAllCaps = false
        stateListAnimator = null
        setTextColor(color)
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
        minHeight = context.dp(44)
        minimumHeight = context.dp(44)
        background = RippleDrawable(ColorStateList.valueOf(withAlpha(color, 50)), null, null)
        setOnClickListener { onClick() }
    }

fun verticalLayout(context: Context): LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

fun spacer(context: Context, heightDp: Int): View = View(context).apply {
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(heightDp))
}

fun lp(widthPx: Int = ViewGroup.LayoutParams.MATCH_PARENT, heightPx: Int = ViewGroup.LayoutParams.WRAP_CONTENT, topDp: Int = 0, context: Context? = null): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(widthPx, heightPx).apply {
        if (context != null) topMargin = context.dp(topDp)
    }
