package com.jarvis.assistant.ui.home

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.jarvis.assistant.R

/** First-run cinematic welcome sequence followed by creator links and timed entry to JARVIS. */
object WelcomeIntroOverlay {
    private data class Slide(val text: String, val color: Int, val sizeSp: Float, val font: String)

    fun show(activity: Activity, parent: ViewGroup, onFinished: () -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        val accents = listOf(
            themeColor(activity, R.attr.themeAccent1, Color.rgb(64, 145, 255)),
            themeColor(activity, R.attr.themeAccent2, Color.rgb(167, 112, 255)),
            themeColor(activity, R.attr.themeAccent3, Color.rgb(28, 196, 190))
        )
        val slides = listOf(
            Slide("WELCOME TO THE\nALL NEW JARVIS ASSISTANT", accents[0], 31f, "sans-serif-black"),
            Slide("CREATED AND DESIGNED BY\nSUKANTA BHATTACHARYA", accents[1], 27f, "sans-serif-condensed"),
            Slide("TO HANDLE ALL YOUR\nNECESSARY WORKS", accents[2], 32f, "sans-serif")
        )
        val backgroundColor = themeColor(activity, R.attr.themeBg, Color.rgb(7, 12, 25))
        val overlay = FrameLayout(activity).apply {
            isClickable = true
            isFocusable = true
            contentDescription = "JARVIS welcome and creator support"
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    backgroundColor,
                    blend(backgroundColor, accents[0], 0.17f),
                    blend(backgroundColor, accents[1], 0.13f),
                    backgroundColor
                )
            )
            alpha = 0f
        }
        val messageView = TextView(activity).apply {
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            includeFontPadding = false
            setPadding(dp(activity, 26), dp(activity, 24), dp(activity, 26), dp(activity, 24))
            letterSpacing = 0.045f
            maxLines = 3
            setLineSpacing(dp(activity, 12).toFloat(), 1.02f)
            alpha = 0f
            scaleX = 0.88f
            scaleY = 0.88f
        }
        overlay.addView(
            messageView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
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
        var countdown = 10
        lateinit var countdownView: TextView
        lateinit var countdownTick: Runnable

        fun finishIntro() {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(null)
            messageView.animate().cancel()
            overlay.animate().cancel()
            overlay.animate()
                .alpha(0f)
                .setDuration(240L)
                .withEndAction {
                    (overlay.parent as? ViewGroup)?.removeView(overlay)
                    onFinished()
                }
                .start()
        }

        countdownTick = object : Runnable {
            override fun run() {
                if (finished) return
                countdownView.text = "CONTINUE  ·  $countdown"
                if (countdown <= 0) {
                    finishIntro()
                } else {
                    countdown--
                    handler.postDelayed(this, 1000L)
                }
            }
        }

        fun openSocial(url: String) {
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(activity, "No app or browser is available to open this link.", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(activity, "Could not open this link right now.", Toast.LENGTH_SHORT).show()
            }
        }

        fun makeSocialButton(
            title: String,
            subtitle: String,
            iconRes: Int,
            accent: Int,
            font: Typeface,
            url: String,
            radius: Float
        ): View {
            val button = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(activity, 20), 0, dp(activity, 18), 0)
                background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(
                        blend(backgroundColor, accent, 0.24f),
                        blend(backgroundColor, accent, 0.10f)
                    )
                ).apply {
                    cornerRadius = dp(activity, radius.toInt()).toFloat()
                    setStroke(dp(activity, 1), Color.argb(175, Color.red(accent), Color.green(accent), Color.blue(accent)))
                }
                elevation = dp(activity, 7).toFloat()
                isClickable = true
                isFocusable = true
                contentDescription = "$title. $subtitle"
                setOnClickListener { openSocial(url) }
            }
            val logo = ImageView(activity).apply {
                setImageResource(iconRes)
                imageTintList = ColorStateList.valueOf(accent)
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = "$title logo"
            }
            button.addView(logo, LinearLayout.LayoutParams(dp(activity, 30), dp(activity, 30)))
            val labels = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(activity, 16), 0, 0, 0)
            }
            val titleView = TextView(activity).apply {
                text = title
                setTextColor(accent)
                textSize = 18f
                typeface = font
                letterSpacing = if (title == "INSTAGRAM") 0.025f else 0.045f
                setShadowLayer(dp(activity, 13).toFloat(), 0f, 0f, Color.argb(145, Color.red(accent), Color.green(accent), Color.blue(accent)))
            }
            val subtitleView = TextView(activity).apply {
                text = subtitle
                setTextColor(Color.argb(190, 225, 232, 245))
                textSize = 10f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                letterSpacing = 0.12f
            }
            labels.addView(titleView)
            labels.addView(subtitleView)
            button.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val arrow = TextView(activity).apply {
                text = "↗"
                textSize = 23f
                setTextColor(accent)
                typeface = Typeface.DEFAULT_BOLD
            }
            button.addView(arrow)
            return button
        }

        fun revealCreatorPanel() {
            if (finished) return
            messageView.animate().cancel()
            messageView.visibility = View.GONE

            val panel = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(activity, 24), dp(activity, 22), dp(activity, 24), dp(activity, 10))
                alpha = 0f
                translationY = dp(activity, 16).toFloat()
            }
            val scroll = ScrollView(activity).apply {
                isFillViewport = true
                overScrollMode = View.OVER_SCROLL_NEVER
                clipToPadding = false
                addView(panel, ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ))
            }
            overlay.addView(scroll, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))

            val heading = TextView(activity).apply {
                text = "SUPPORT THE CREATOR"
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                setTextColor(Color.rgb(235, 241, 255))
                textSize = 21f
                typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                letterSpacing = 0.12f
                setShadowLayer(dp(activity, 18).toFloat(), 0f, 0f, accents[0])
                setPadding(dp(activity, 4), dp(activity, 10), dp(activity, 4), dp(activity, 10))
            }
            panel.addView(heading, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(activity, 20) })

            val socials = listOf(
                makeSocialButton(
                    "INSTAGRAM", "FOLLOW · CREATOR UPDATES", R.drawable.ic_social_instagram,
                    Color.rgb(255, 105, 155), Typeface.create("sans-serif-black", Typeface.BOLD_ITALIC),
                    "https://www.instagram.com/___itz__sukanta__?vrfl=bzg3djZ5MDh3NmY2", 22f
                ),
                makeSocialButton(
                    "YOUTUBE", "WATCH · VIDEOS & PROJECTS", R.drawable.ic_social_youtube,
                    Color.rgb(255, 65, 65), Typeface.create("sans-serif-condensed", Typeface.BOLD),
                    "https://www.youtube.com/@Sukant_Codes", 15f
                ),
                makeSocialButton(
                    "TELEGRAM", "JOIN · OFFICIAL COMMUNITY", R.drawable.ic_social_telegram,
                    Color.rgb(55, 173, 255), Typeface.create("sans-serif-medium", Typeface.BOLD),
                    "https://t.me/sukantcodesbycreator", 28f
                )
            )
            socials.forEachIndexed { index, button ->
                panel.addView(button, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 68)
                ).apply {
                    bottomMargin = if (index == socials.lastIndex) dp(activity, 8) else dp(activity, 14)
                })
            }

            val bottom = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(activity, 24), dp(activity, 8), dp(activity, 24), dp(activity, 18))
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.TRANSPARENT, blend(backgroundColor, accents[0], 0.16f), backgroundColor)
                )
            }
            countdownView = TextView(activity).apply {
                text = "CONTINUE  ·  10"
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                setTextColor(Color.WHITE)
                textSize = 13f
                typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                letterSpacing = 0.10f
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(activity, 24).toFloat()
                    setColor(Color.rgb(30, 112, 238))
                    setStroke(dp(activity, 1), Color.rgb(108, 177, 255))
                }
                elevation = dp(activity, 8).toFloat()
                isClickable = true
                isFocusable = true
                setPadding(dp(activity, 28), dp(activity, 12), dp(activity, 28), dp(activity, 12))
                setOnClickListener { finishIntro() }
                setShadowLayer(dp(activity, 10).toFloat(), 0f, 0f, Color.rgb(38, 125, 255))
                contentDescription = "Continue to JARVIS. Automatic entry in ten seconds."
            }
            bottom.addView(countdownView, LinearLayout.LayoutParams(
                dp(activity, 158), dp(activity, 46)
            ))
            val hint = TextView(activity).apply {
                text = "JARVIS WILL OPEN AUTOMATICALLY"
                gravity = Gravity.CENTER
                setTextColor(Color.argb(155, 205, 218, 240))
                textSize = 9f
                letterSpacing = 0.16f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(0, dp(activity, 9), 0, 0)
            }
            bottom.addView(hint)
            overlay.addView(bottom, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ))
            scroll.setPadding(0, 0, 0, dp(activity, 112))
            panel.animate().alpha(1f).translationY(0f).setDuration(420L)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            bottom.alpha = 0f
            bottom.animate().alpha(1f).setDuration(380L).start()
            countdown = 10
            handler.post(countdownTick)
        }

        fun showSlide(index: Int) {
            if (finished) return
            if (index >= slides.size) {
                revealCreatorPanel()
                return
            }
            val slide = slides[index]
            messageView.text = slide.text
            messageView.textSize = slide.sizeSp
            messageView.typeface = Typeface.create(slide.font, Typeface.BOLD)
            messageView.setTextColor(slide.color)
            messageView.setShadowLayer(dp(activity, 25).toFloat(), 0f, 0f, slide.color)
            messageView.alpha = 0f
            messageView.translationY = dp(activity, 24).toFloat()
            messageView.scaleX = 0.88f
            messageView.scaleY = 0.88f
            messageView.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(560L)
                .withEndAction {
                    handler.postDelayed({
                        if (finished) return@postDelayed
                        messageView.animate()
                            .alpha(0f)
                            .translationY(-dp(activity, 18).toFloat())
                            .scaleX(0.96f)
                            .scaleY(0.96f)
                            .setDuration(360L)
                            .withEndAction {
                                handler.postDelayed({ showSlide(index + 1) }, 1000L)
                            }
                            .start()
                    }, 850L)
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
