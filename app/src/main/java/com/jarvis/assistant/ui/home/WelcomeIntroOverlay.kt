package com.jarvis.assistant.ui.home

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.jarvis.assistant.R

/** First-run, one-time welcome sequence shown above the normal JARVIS home screen. */
object WelcomeIntroOverlay {
    private data class Slide(val text: String, val color: Int, val sizeSp: Float)

    fun show(activity: Activity, parent: ViewGroup, onFinished: () -> Unit) {
        val accents = listOf(
            themeColor(activity, R.attr.themeAccent1, Color.rgb(64, 145, 255)),
            themeColor(activity, R.attr.themeAccent2, Color.rgb(167, 112, 255)),
            themeColor(activity, R.attr.themeAccent3, Color.rgb(28, 196, 190)),
            themeColor(activity, R.attr.themeSuccess, Color.rgb(40, 205, 135))
        )
        val slides = listOf(
            Slide("WELCOME TO THE ALL NEW JARVIS ASSISTANT", accents[0], 27f),
            Slide("CREATED AND DESIGNED BY SUKANTA BHATTACHARYA", accents[1], 19f),
            Slide("TO HANDLE ALL YOUR NECESSARY WORKS", accents[2], 22f),
            Slide("WELCOME TO JARVIS", accents[3], 32f)
        )
        val backgroundColor = themeColor(activity, R.attr.themeBg, Color.rgb(7, 12, 25))
        val overlay = FrameLayout(activity).apply {
            isClickable = true
            isFocusable = true
            contentDescription = "JARVIS welcome introduction"
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    backgroundColor,
                    blend(backgroundColor, accents[0], 0.16f),
                    blend(backgroundColor, accents[1], 0.12f),
                    backgroundColor
                )
            )
            alpha = 0f
        }
        val messageView = TextView(activity).apply {
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            includeFontPadding = false
            setPadding(dp(activity, 28), dp(activity, 20), dp(activity, 28), dp(activity, 20))
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            letterSpacing = 0.035f
            maxLines = 3
            setLineSpacing(dp(activity, 8).toFloat(), 1f)
            alpha = 0f
            scaleX = 0.92f
            scaleY = 0.92f
        }
        val footer = TextView(activity).apply {
            text = "J A R V I S   •   PERSONAL ASSISTANT"
            setTextColor(Color.argb(170, 220, 230, 245))
            textSize = 10f
            letterSpacing = 0.22f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
        }
        overlay.addView(
            messageView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
        overlay.addView(
            footer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(activity, 44),
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            )
        )
        parent.addView(
            overlay,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        overlay.bringToFront()

        var finished = false
        fun finish() {
            if (finished) return
            finished = true
            messageView.animate().cancel()
            overlay.animate().cancel()
            overlay.animate()
                .alpha(0f)
                .setDuration(260L)
                .withEndAction {
                    (overlay.parent as? ViewGroup)?.removeView(overlay)
                    onFinished()
                }
                .start()
        }
        overlay.setOnClickListener { finish() }

        fun showSlide(index: Int) {
            if (finished) return
            if (index >= slides.size) {
                finish()
                return
            }
            val slide = slides[index]
            messageView.text = slide.text
            messageView.textSize = slide.sizeSp
            messageView.setTextColor(slide.color)
            messageView.setShadowLayer(dp(activity, 22).toFloat(), 0f, 0f, slide.color)
            messageView.alpha = 0f
            messageView.translationY = dp(activity, 18).toFloat()
            messageView.scaleX = 0.92f
            messageView.scaleY = 0.92f
            messageView.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(430L)
                .withEndAction {
                    messageView.postDelayed({
                        if (finished) return@postDelayed
                        messageView.animate()
                            .alpha(0f)
                            .translationY(-dp(activity, 12).toFloat())
                            .scaleX(0.97f)
                            .scaleY(0.97f)
                            .setDuration(300L)
                            .withEndAction { showSlide(index + 1) }
                            .start()
                    }, 1050L)
                }
                .start()
        }

        overlay.post {
            overlay.animate().alpha(1f).setDuration(260L).start()
            showSlide(0)
        }
    }

    private fun themeColor(activity: Activity, attribute: Int, fallback: Int): Int {
        val value = TypedValue()
        if (!activity.theme.resolveAttribute(attribute, value, true)) return fallback
        return if (value.resourceId != 0) {
            try { activity.getColor(value.resourceId) } catch (_: Exception) { value.data }
        } else value.data
    }

    private fun blend(from: Int, to: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(from) * (1f - t) + Color.red(to) * t).toInt(),
            (Color.green(from) * (1f - t) + Color.green(to) * t).toInt(),
            (Color.blue(from) * (1f - t) + Color.blue(to) * t).toInt()
        )
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
