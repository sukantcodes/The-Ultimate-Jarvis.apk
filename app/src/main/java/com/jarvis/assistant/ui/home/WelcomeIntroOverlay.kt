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
        val introSlides = listOf(
            IntroTextSequenceView.Slide(
                "INTRODUCING", listOf("J.A.R.V.I.S."),
                "Your intelligent voice assistant", accents[0]
            ),
            IntroTextSequenceView.Slide(
                "CRAFTED WITH PASSION BY", listOf("SUKANTA", "BHATTACHARYA"),
                "Designed and engineered by one creator", accents[1]
            ),
            IntroTextSequenceView.Slide(
                "BUILT TO SERVE YOU", listOf("SPEAK.", "JARVIS ACTS."),
                "Hands-free help in Hindi, Bengali and English", accents[2]
            )
        )
        val backgroundColor = themeColor(activity, R.attr.themeBg, Color.rgb(7, 12, 25))
        val panelText = themeColor(activity, R.attr.themeTextPrimary, Color.rgb(18, 43, 79))
        val panelTextSecondary = themeColor(activity, R.attr.themeTextSecondary, Color.rgb(75, 91, 112))
        val panelTextMuted = themeColor(activity, R.attr.themeTextMuted, Color.rgb(91, 108, 130))
        val panelCard = themeColor(activity, R.attr.themeCardBg, Color.WHITE)
        val creatorBackground = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(backgroundColor, blend(backgroundColor, accents[0], 0.06f), backgroundColor)
        )
        val overlay = FrameLayout(activity).apply {
            isClickable = true
            isFocusable = true
            contentDescription = "JARVIS welcome and creator support"
            // Midnight base matches the ident and text sequence, so skipping never flashes white.
            setBackgroundColor(Color.rgb(3, 6, 14))
            // The dock and its curved accent are elevated siblings of this root overlay.
            // Raise the overlay above them so no home controls show during the intro.
            elevation = dp(activity, 32).toFloat()
            alpha = 0f
        }
        parent.addView(
            overlay,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        overlay.bringToFront()

        var finished = false
        var sequence: IntroTextSequenceView? = null
        var countdown = 10
        lateinit var countdownView: TextView
        lateinit var countdownTick: Runnable

        fun finishIntro() {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(null)
            sequence?.stop()
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
                        blend(panelCard, accent, 0.10f),
                        blend(panelCard, accent, 0.035f)
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
                setTextColor(panelTextSecondary)
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
                setTextColor(panelText)
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
                    intArrayOf(backgroundColor and 0x00FFFFFF, backgroundColor, backgroundColor)
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
                    setColor(accents[0])
                    setStroke(dp(activity, 1), blend(accents[0], Color.WHITE, 0.35f))
                }
                elevation = dp(activity, 8).toFloat()
                isClickable = true
                isFocusable = true
                setPadding(dp(activity, 28), dp(activity, 12), dp(activity, 28), dp(activity, 12))
                setOnClickListener { finishIntro() }
                setShadowLayer(dp(activity, 10).toFloat(), 0f, 0f, accents[0])
                contentDescription = "Continue to JARVIS. Automatic entry in ten seconds."
            }
            bottom.addView(countdownView, LinearLayout.LayoutParams(
                dp(activity, 158), dp(activity, 46)
            ))
            val hint = TextView(activity).apply {
                text = "JARVIS WILL OPEN AUTOMATICALLY"
                gravity = Gravity.CENTER
                setTextColor(panelTextMuted)
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

        fun playTextSequence() {
            if (finished) return
            val seq = IntroTextSequenceView(activity).apply { this.slides = introSlides }
            sequence = seq
            overlay.addView(seq, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            overlay.setOnClickListener { seq.next() }
            seq.start {
                if (finished) return@start
                overlay.setOnClickListener(null)
                // Light creator panel sits underneath; the dark sequence dissolves into it.
                overlay.background = creatorBackground
                seq.animate().alpha(0f).setDuration(320L).withEndAction {
                    (seq.parent as? ViewGroup)?.removeView(seq)
                    sequence = null
                    revealCreatorPanel()
                }.start()
            }
        }

        val cinematicReveal = CinematicJarvisIntroView(activity).apply {
            // Hold the dark ident until the text sequence (same midnight backdrop) takes over: no white flash.
            dissolveOnFinish = false
        }
        overlay.addView(cinematicReveal, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        cinematicReveal.setOnClickListener { cinematicReveal.skip() }
        overlay.setOnClickListener { cinematicReveal.skip() }
        overlay.post {
            overlay.animate().alpha(1f).setDuration(420L).start()
            cinematicReveal.start {
                if (finished) return@start
                (cinematicReveal.parent as? ViewGroup)?.removeView(cinematicReveal)
                playTextSequence()
            }
        }
    }


    /** Plays only the brand ident on a fresh launch; first-run text slides remain separate. */
    fun showCinematicOnly(activity: Activity, parent: ViewGroup, onFinished: () -> Unit) =
        showCinematicOnly(activity, parent, true, onFinished)

    /**
     * @param dissolveIntoContent true when [parent] already holds the screen the user is about to see
     * (Home): the ident dissolves away to reveal it. Pass false when the host will immediately switch
     * to another screen, so the overlay stays opaque and nothing flashes through.
     */
    fun showCinematicOnly(
        activity: Activity,
        parent: ViewGroup,
        dissolveIntoContent: Boolean,
        onFinished: () -> Unit
    ) {
        val overlay = FrameLayout(activity).apply {
            isClickable = true
            isFocusable = true
            elevation = dp(activity, 40).toFloat()
            // The ident paints its own opaque backdrop; only keep a solid base when it must not dissolve.
            if (!dissolveIntoContent) setBackgroundColor(Color.rgb(2, 5, 14))
            contentDescription = "JARVIS opening animation"
        }
        val reveal = CinematicJarvisIntroView(activity).apply {
            dissolveOnFinish = dissolveIntoContent
        }
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
