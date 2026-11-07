package com.jarvis.assistant.ui.home

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
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
import androidx.core.widget.TextViewCompat
import com.jarvis.assistant.R

/** First-run cinematic welcome sequence followed by creator links and timed entry to JARVIS. */
object WelcomeIntroOverlay {
    private data class Slide(
        val eyebrow: String,
        val title: String,
        val subtitle: String,
        val color: Int,
        val accent: Int,
        val sizeSp: Float,
        val font: String
    )

    fun show(activity: Activity, parent: ViewGroup, onFinished: () -> Unit) {
        // The first-run intro includes this same logo ident, so do not replay it again
        // when authentication returns to MainActivity in the same app session.
        com.jarvis.assistant.JarvisApp.instance.preferences.openingAnimationPending = false
        val handler = Handler(Looper.getMainLooper())
        val accents = listOf(
            themeColor(activity, R.attr.themeAccent1, Color.rgb(64, 145, 255)),
            themeColor(activity, R.attr.themeAccent2, Color.rgb(167, 112, 255)),
            themeColor(activity, R.attr.themeAccent3, Color.rgb(28, 196, 190))
        )
        val slides = listOf(
            Slide(
                "INTRODUCING", "J.A.R.V.I.S.", "Your intelligent voice assistant",
                Color.rgb(15, 38, 78), Color.rgb(30, 112, 238), 44f, "sans-serif-black"
            ),
            Slide(
                "CRAFTED WITH PASSION BY", "SUKANTA\nBHATTACHARYA", "Designed and engineered by one creator",
                Color.rgb(0, 83, 155), Color.rgb(124, 77, 255), 36f, "sans-serif-condensed"
            ),
            Slide(
                "BUILT TO SERVE YOU", "SPEAK.\nJARVIS ACTS.", "Hands-free help in Hindi, Bengali and English",
                Color.rgb(0, 105, 94), Color.rgb(0, 150, 136), 40f, "sans-serif-black"
            )
        )
        val backgroundColor = themeColor(activity, R.attr.themeBg, Color.rgb(7, 12, 25))
        val creatorBackground = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.WHITE, Color.rgb(246, 250, 255), Color.WHITE)
        )
        val overlay = FrameLayout(activity).apply {
            isClickable = true
            isFocusable = true
            contentDescription = "JARVIS welcome and creator support"
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.WHITE, Color.rgb(248, 251, 255), Color.WHITE)
            )
            // The dock and its curved accent are elevated siblings of this root overlay.
            // Raise the overlay above them so no home controls show during the intro.
            elevation = dp(activity, 32).toFloat()
            alpha = 0f
        }
        val messageView = TextView(activity).apply {
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            includeFontPadding = true
            setPadding(dp(activity, 20), dp(activity, 22), dp(activity, 20), dp(activity, 22))
            letterSpacing = 0.02f
            maxLines = 6
            ellipsize = null
            setLineSpacing(dp(activity, 8).toFloat(), 1.0f)
            breakStrategy = android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY
            hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                this, 22, 36, 1, TypedValue.COMPLEX_UNIT_SP
            )
            alpha = 0f
            scaleX = 0.88f
            scaleY = 0.88f
        }
        val accentBar = View(activity).apply {
            alpha = 0f
            scaleX = 0.2f
        }
        val stage = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        stage.addView(
            messageView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        stage.addView(accentBar, LinearLayout.LayoutParams(dp(activity, 72), dp(activity, 4)).apply {
            topMargin = dp(activity, 6)
        })
        overlay.addView(
            stage,
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
            accentBar.animate().cancel()
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
                        blend(Color.WHITE, accent, 0.10f),
                        blend(Color.WHITE, accent, 0.035f)
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
                setTextColor(Color.rgb(75, 91, 112))
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
            overlay.background = creatorBackground
            messageView.animate().cancel()
            accentBar.animate().cancel()
            messageView.visibility = View.GONE
            accentBar.visibility = View.GONE

            val panel = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(activity, 24), dp(activity, 18), dp(activity, 24), dp(activity, 18))
                alpha = 0f
                translationY = dp(activity, 16).toFloat()
            }
            val scroll = ScrollView(activity).apply {
                isFillViewport = true
                overScrollMode = View.OVER_SCROLL_NEVER
                clipToPadding = false
                addView(panel, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
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
                setTextColor(Color.rgb(18, 43, 79))
                textSize = 21f
                typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                letterSpacing = 0.12f
                setShadowLayer(dp(activity, 10).toFloat(), 0f, dp(activity, 2).toFloat(), Color.argb(45, 35, 80, 140))
                setPadding(dp(activity, 4), dp(activity, 10), dp(activity, 4), dp(activity, 10))
            }
            panel.addView(heading, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(activity, 34) })

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
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 76)
                ).apply {
                    topMargin = if (index == 0) dp(activity, 4) else dp(activity, 26)
                    bottomMargin = if (index == socials.lastIndex) dp(activity, 8) else 0
                })
            }

            val bottom = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(activity, 24), dp(activity, 8), dp(activity, 24), dp(activity, 18))
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.TRANSPARENT, Color.rgb(249, 252, 255), Color.WHITE)
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
                setTextColor(Color.rgb(91, 108, 130))
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
            messageView.text = composeSlide(activity, slide)
            accentBar.background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.TRANSPARENT, slide.accent, Color.TRANSPARENT)
            ).apply { cornerRadius = dp(activity, 2).toFloat() }
            accentBar.alpha = 0f
            accentBar.scaleX = 0.2f
            accentBar.animate().alpha(1f).scaleX(1f).setStartDelay(260L).setDuration(620L).start()
            messageView.textSize = slide.sizeSp
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                messageView, 22, slide.sizeSp.toInt().coerceAtLeast(22), 1, TypedValue.COMPLEX_UNIT_SP
            )
            messageView.typeface = Typeface.create(slide.font, Typeface.BOLD)
            messageView.setTextColor(slide.color)
            messageView.setShadowLayer(dp(activity, 8).toFloat(), 0f, dp(activity, 2).toFloat(), Color.argb(35, 20, 45, 80))
            messageView.alpha = 0f
            messageView.rotation = 0f
            messageView.translationX = 0f
            messageView.translationY = 0f
            messageView.scaleX = 1f
            messageView.scaleY = 1f
            val entrance = messageView.animate().alpha(1f).setDuration(760L)
            when (index) {
                0 -> entrance.scaleX(1f).scaleY(1f).withStartAction {
                    messageView.scaleX = 0.68f
                    messageView.scaleY = 0.68f
                }
                1 -> {
                    messageView.translationX = dp(activity, 90).toFloat()
                    entrance.translationX(0f).setInterpolator(android.view.animation.OvershootInterpolator(0.8f))
                }
                else -> {
                    messageView.scaleX = 1.28f
                    messageView.scaleY = 1.28f
                    messageView.rotation = -7f
                    entrance.scaleX(1f).scaleY(1f).rotation(0f)
                        .setInterpolator(android.view.animation.DecelerateInterpolator(1.8f))
                }
            }
            entrance.withEndAction {
                    handler.postDelayed({
                        if (finished) return@postDelayed
                        accentBar.animate().alpha(0f).setStartDelay(0L).setDuration(300L).start()
                        val exit = messageView.animate().alpha(0f).setDuration(420L)
                        when (index) {
                            0 -> exit.scaleX(1.22f).scaleY(1.22f)
                            1 -> exit.translationX(-dp(activity, 90).toFloat())
                            else -> exit.rotation(7f).scaleX(0.82f).scaleY(0.82f)
                        }
                        exit
                            .withEndAction {
                                handler.postDelayed({ showSlide(index + 1) }, 1000L)
                            }
                            .start()
                    }, 850L)
                }
                .start()
        }

        val cinematicReveal = CinematicJarvisIntroView(activity)
        overlay.addView(cinematicReveal, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        stage.visibility = View.INVISIBLE
        cinematicReveal.setOnClickListener { cinematicReveal.skip() }
        overlay.setOnClickListener { cinematicReveal.skip() }
        overlay.post {
            overlay.animate().alpha(1f).setDuration(420L).start()
            cinematicReveal.start {
                if (finished) return@start
                overlay.setOnClickListener(null)
                (cinematicReveal.parent as? ViewGroup)?.removeView(cinematicReveal)
                stage.visibility = View.VISIBLE
                showSlide(0)
            }
        }
    }


    /** Plays only the brand ident on a fresh launch; first-run text slides remain separate. */
    fun showCinematicOnly(activity: Activity, parent: ViewGroup, onFinished: () -> Unit) {
        val overlay = FrameLayout(activity).apply {
            isClickable = true
            isFocusable = true
            elevation = dp(activity, 40).toFloat()
            setBackgroundColor(Color.rgb(2, 5, 14))
            contentDescription = "JARVIS opening animation"
        }
        val reveal = CinematicJarvisIntroView(activity)
        overlay.addView(
            reveal,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        reveal.setOnClickListener { reveal.skip() }
        overlay.setOnClickListener { reveal.skip() }
        parent.addView(
            overlay,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        overlay.bringToFront()
        reveal.start {
            overlay.setOnClickListener(null)
            (reveal.parent as? ViewGroup)?.removeView(reveal)
            (overlay.parent as? ViewGroup)?.removeView(overlay)
            onFinished()
        }
    }

    /**
     * Premium post-authentication greeting: a cinematic HUD sequence (see [PremiumWelcomeView]) of about four
     * seconds. It completes before the Home screen is handed over, and a tap skips straight to Home.
     */
    fun showPostLoginWelcome(activity: Activity, parent: ViewGroup, onFinished: () -> Unit) {
        val overlay = FrameLayout(activity).apply {
            isClickable = true
            isFocusable = true
            elevation = dp(activity, 36).toFloat()
            alpha = 0f
            setBackgroundColor(Color.rgb(3, 6, 14))
        }
        val sequence = PremiumWelcomeView(activity).apply {
            eyebrow = "SYSTEMS ONLINE"
            title = "WELCOME TO JARVIS"
            subtitle = "Created by Sukant"
        }
        overlay.addView(
            sequence,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        parent.addView(
            overlay,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        overlay.bringToFront()

        var done = false
        fun finish() {
            if (done) return
            done = true
            overlay.animate().alpha(0f).setDuration(320L).withEndAction {
                (overlay.parent as? ViewGroup)?.removeView(overlay)
                onFinished()
            }.start()
        }
        overlay.setOnClickListener { sequence.skip() }
        overlay.post {
            overlay.animate().alpha(1f).setDuration(260L).start()
            sequence.start { finish() }
        }
    }

    /** Eyebrow + title + subtitle, with the small lines kept at a fixed readable size. */
    private fun composeSlide(activity: Activity, slide: Slide): CharSequence {
        fun px(sp: Float) = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, sp, activity.resources.displayMetrics
        ).toInt()
        // Hair spaces give the small caps line a tracked, premium look.
        val eyebrow = slide.eyebrow.map { it.toString() }.joinToString("\u200A")
        val builder = SpannableStringBuilder()
        val eyebrowStart = builder.length
        builder.append(eyebrow).append('\n')
        builder.setSpan(AbsoluteSizeSpan(px(12f)), eyebrowStart, builder.length - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(ForegroundColorSpan(slide.accent), eyebrowStart, builder.length - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(TypefaceSpan("sans-serif-medium"), eyebrowStart, builder.length - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.append(slide.title).append('\n')
        val subStart = builder.length
        builder.append(slide.subtitle)
        builder.setSpan(AbsoluteSizeSpan(px(14f)), subStart, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(ForegroundColorSpan(Color.rgb(75, 91, 112)), subStart, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(TypefaceSpan("sans-serif-light"), subStart, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return builder
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
