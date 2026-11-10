package com.jarvis.assistant.ui.settings

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.jarvis.assistant.R

/** Small builders for programmatic screens that follow the active JARVIS theme (card, text and accent colours). */
object UiKit {
    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun color(ctx: Context, attr: Int, fallback: Int = 0xFFFFFFFF.toInt()): Int {
        val tv = TypedValue()
        return if (ctx.theme.resolveAttribute(attr, tv, true)) tv.data else fallback
    }

    fun text(ctx: Context, value: String, sizeSp: Float = 14f, muted: Boolean = false, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            text = value
            textSize = sizeSp
            setTextColor(color(ctx, if (muted) R.attr.themeTextMuted else R.attr.themeTextPrimary))
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    fun card(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_jarvis_card)
        val p = dp(ctx, 16); setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(ctx, 12) }
    }

    fun button(ctx: Context, label: String, outlined: Boolean = true, onClick: () -> Unit): MaterialButton =
        MaterialButton(ctx, null, if (outlined) com.google.android.material.R.attr.materialButtonOutlinedStyle else com.google.android.material.R.attr.materialButtonStyle).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    fun row(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun input(ctx: Context, hint: String, value: String = "", number: Boolean = false, secret: Boolean = false): EditText =
        EditText(ctx).apply {
            this.hint = hint
            setText(value)
            setTextColor(color(ctx, R.attr.themeTextPrimary))
            setHintTextColor(color(ctx, R.attr.themeTextMuted))
            setSingleLine(true)
            inputType = when {
                number -> android.text.InputType.TYPE_CLASS_NUMBER
                secret -> android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                else -> android.text.InputType.TYPE_CLASS_TEXT
            }
        }

    /** A vertical form container for dialogs. */
    fun form(ctx: Context, vararg views: View): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(ctx, 20); setPadding(p, dp(ctx, 8), p, 0)
        views.forEach { addView(it) }
    }

    fun gap(ctx: Context, dp: Int = 8): View = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(1, dp(ctx, dp)) }
}
