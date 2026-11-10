package com.jarvis.assistant.ui.chat

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.jarvis.assistant.R

/**
 * Floating ChatBox command surface. Each tile has a visible icon, label and
 * a direct action that opens the owning feature or executes it in ChatBox.
 */
object ChatComposerMenu {
    data class Action(
        val title: String,
        val subtitle: String,
        val glyph: String,
        val run: () -> Unit
    )

    fun show(context: Context, actions: List<Action>) {
        if (actions.isEmpty()) return
        val dialog = BottomSheetDialog(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 18), dp(context, 12), dp(context, 18), dp(context, 24))
            background = context.getDrawable(R.drawable.bg_chat_composer)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        root.addView(View(context).apply {
            background = context.getDrawable(R.drawable.bg_chat_tool_chip)
            layoutParams = LinearLayout.LayoutParams(dp(context, 38), dp(context, 4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(context, 16)
            }
        })
        root.addView(TextView(context).apply {
            text = "ChatBox tools"
            textSize = 19f
            setTextColor(resolveColor(context, R.attr.themeTextPrimary))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(context, 2), 0, 0, dp(context, 3))
        })
        root.addView(TextView(context).apply {
            text = "Open a tool or jump into its full workspace."
            textSize = 12f
            setTextColor(resolveColor(context, R.attr.themeTextSecondary))
            setPadding(dp(context, 2), 0, 0, dp(context, 14))
        })

        val scroll = ScrollView(context).apply {
            isFillViewport = false
            clipToPadding = false
            setPadding(0, 0, 0, dp(context, 4))
        }
        val grid = GridLayout(context).apply {
            columnCount = 2
            useDefaultMargins = false
            alignmentMode = GridLayout.ALIGN_BOUNDS
        }
        actions.forEachIndexed { index, action ->
            val tile = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                isClickable = true
                isFocusable = true
                contentDescription = "${action.title}. ${action.subtitle}"
                background = context.getDrawable(R.drawable.bg_chat_suggestion)
                setPadding(dp(context, 10), dp(context, 13), dp(context, 10), dp(context, 13))
                val icon = TextView(context).apply {
                    text = action.glyph
                    textSize = 23f
                    gravity = Gravity.CENTER
                    setTextColor(resolveColor(context, R.attr.themeAccent))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(resolveColor(context, R.attr.themeCardBg))
                        setStroke(dp(context, 1), resolveColor(context, R.attr.themeCardBorder))
                    }
                    layoutParams = LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)).apply {
                        bottomMargin = dp(context, 9)
                    }
                }
                addView(icon)
                addView(TextView(context).apply {
                    text = action.title
                    textSize = 13f
                    gravity = Gravity.CENTER
                    maxLines = 2
                    setTextColor(resolveColor(context, R.attr.themeTextPrimary))
                    setTypeface(typeface, Typeface.BOLD)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(TextView(context).apply {
                    text = action.subtitle
                    textSize = 10.5f
                    gravity = Gravity.CENTER
                    maxLines = 3
                    setTextColor(resolveColor(context, R.attr.themeTextSecondary))
                    setPadding(0, dp(context, 4), 0, 0)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                setOnClickListener {
                    dialog.dismiss()
                    action.run()
                }
            }
            val params = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED, 1f),
                GridLayout.spec(GridLayout.UNDEFINED)
            ).apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                setMargins(
                    if (index % 2 == 0) 0 else dp(context, 5),
                    0,
                    if (index % 2 == 0) dp(context, 5) else 0,
                    dp(context, 9)
                )
            }
            grid.addView(tile, params)
        }
        scroll.addView(grid, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.setContentView(root)
        dialog.setOnShowListener {
            dialog.behavior.peekHeight = (context.resources.displayMetrics.heightPixels * 0.76f).toInt()
            dialog.behavior.isFitToContents = true
            dialog.behavior.maxHeight = (context.resources.displayMetrics.heightPixels * 0.88f).toInt()
        }
        dialog.show()
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun resolveColor(context: Context, attribute: Int): Int {
        val typed = android.util.TypedValue()
        context.theme.resolveAttribute(attribute, typed, true)
        return if (typed.resourceId != 0) context.getColor(typed.resourceId) else typed.data
    }
}
