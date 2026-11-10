package com.jarvis.assistant.ui.chat

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.jarvis.assistant.R

/**
 * Single, compact entry point for ChatBox actions.
 * The composer stays uncluttered; advanced capabilities live in this contextual sheet.
 */
object ChatComposerMenu {
    data class Action(
        val title: String,
        val subtitle: String,
        val glyph: String,
        val run: () -> Unit
    )

    fun show(context: Context, actions: List<Action>) {
        val dialog = BottomSheetDialog(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 18), dp(context, 12), dp(context, 18), dp(context, 28))
            background = context.getDrawable(R.drawable.bg_chat_composer)
        }
        root.addView(View(context).apply {
            background = context.getDrawable(R.drawable.bg_chat_tool_chip)
            layoutParams = LinearLayout.LayoutParams(dp(context, 38), dp(context, 4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(context, 16)
            }
        })
        root.addView(TextView(context).apply {
            text = "Add to your chat"
            textSize = 19f
            setTextColor(resolveColor(context, android.R.attr.textColorPrimary))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(context, 2), 0, 0, dp(context, 3))
        })
        root.addView(TextView(context).apply {
            text = "Everything you need, without crowding the composer."
            textSize = 12f
            setTextColor(resolveColor(context, android.R.attr.textColorSecondary))
            setPadding(dp(context, 2), 0, 0, dp(context, 12))
        })
        val scroll = ScrollView(context).apply { isFillViewport = false }
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        actions.forEach { action ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                background = context.getDrawable(R.drawable.bg_chat_suggestion)
                setPadding(dp(context, 12), dp(context, 11), dp(context, 12), dp(context, 11))
                addView(TextView(context).apply {
                    text = action.glyph
                    textSize = 20f
                    gravity = Gravity.CENTER
                    setTextColor(resolveColor(context, android.R.attr.textColorPrimary))
                    layoutParams = LinearLayout.LayoutParams(dp(context, 38), dp(context, 38))
                })
                val labels = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(context, 10), 0, 0, 0)
                    addView(TextView(context).apply {
                        text = action.title
                        textSize = 14f
                        setTextColor(resolveColor(context, android.R.attr.textColorPrimary))
                        setTypeface(typeface, Typeface.BOLD)
                    })
                    addView(TextView(context).apply {
                        text = action.subtitle
                        textSize = 11f
                        setTextColor(resolveColor(context, android.R.attr.textColorSecondary))
                        setPadding(0, dp(context, 2), 0, 0)
                    })
                }
                addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                setOnClickListener {
                    dialog.dismiss()
                    action.run()
                }
            }
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(context, 7)
            })
        }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.setContentView(root)
        dialog.setOnShowListener {
            dialog.behavior.peekHeight = (context.resources.displayMetrics.heightPixels * 0.62f).toInt()
            dialog.behavior.isFitToContents = true
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
