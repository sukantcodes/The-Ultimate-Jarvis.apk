package com.jarvis.assistant.ui.settings

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.util.ThemeManager

/**
 * Hidden Maya companion settings. The selected mode is stored in the same persistent personality
 * preference consumed by PromptGenerator, so it affects actual AI replies rather than a demo.
 */
class MayaModeActivity : AppCompatActivity() {
    private val preferences by lazy { JarvisApp.instance.preferences }
    private var selectedMode = "maya_friendly"
    private val ink = Color.rgb(245, 242, 250)
    private val muted = Color.rgb(190, 183, 204)
    private val accent = Color.rgb(255, 113, 177)
    private val panel = Color.rgb(35, 26, 47)
    private lateinit var friendlyCard: LinearLayout
    private lateinit var romanticCard: LinearLayout
    private lateinit var intenseCard: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        selectedMode = preferences.personality.takeIf { it.startsWith("maya_") } ?: "maya_friendly"
        buildScreen()
        refreshSelection()
    }

    private fun rounded(color: Int, stroke: Int = Color.TRANSPARENT): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(color, color)).apply {
            cornerRadius = dp(20).toFloat()
            setStroke(dp(1), stroke)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.START
        }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(22))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(17, 13, 25), Color.rgb(35, 20, 43), Color.rgb(14, 17, 31))
            )
        }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = Button(this).apply {
            text = "‹"
            textSize = 28f
            setTextColor(ink)
            background = rounded(panel)
            setOnClickListener { finish() }
        }
        top.addView(back, LinearLayout.LayoutParams(dp(54), dp(48)))
        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        titleBox.addView(label("MAYA", 26f, ink, true))
        titleBox.addView(label("SECRET COMPANION SETTINGS", 10f, accent, true))
        top.addView(titleBox, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(top)
        content.addView(label("A voice with warmth, personality, and a style you choose.", 14f, muted).apply {
            setPadding(0, dp(18), 0, dp(18))
        })

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(24), dp(18), dp(24))
            background = rounded(Color.rgb(48, 29, 56), accent)
        }
        hero.addView(label("✦", 38f, accent, true).apply { gravity = Gravity.CENTER })
        hero.addView(label("Hi, I'm Maya.", 23f, ink, true).apply { gravity = Gravity.CENTER })
        hero.addView(label("Your mode changes the actual AI conversation.", 12f, muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        })
        content.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(20) })

        friendlyCard = modeCard("FRIENDLY", "Warm, close best-friend energy. Supportive, honest, and non-romantic.", "maya_friendly")
        romanticCard = modeCard("ROMANTIC", "Affectionate pet names, gentle flirting, playful nakhre, and harmless teasing.", "maya_romantic")
        intenseCard = modeCard("INTENSE", "More expressive romance, bold wholesome flirting, dramatic sweetness, and playful jealousy — always non-explicit.", "maya_intense")
        content.addView(friendlyCard, cardParams())
        content.addView(romanticCard, cardParams())
        content.addView(intenseCard, cardParams())

        status = label("", 12f, muted)
        status.setPadding(dp(4), dp(8), dp(4), dp(8))
        content.addView(status)

        val activate = Button(this).apply {
            text = "ACTIVATE MAYA"
            textSize = 15f
            setTextColor(Color.WHITE)
            background = rounded(accent)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener {
                preferences.personality = selectedMode
                preferences.voice = "Aoede"
                Toast.makeText(this@MayaModeActivity, "Maya activated • Female voice: Aoede", Toast.LENGTH_LONG).show()
                finish()
            }
        }
        root.addView(activate, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(12) })
        setContentView(root)
        ThemeManager.applyDynamicBackground(root)
    }

    private fun cardParams() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }

    private fun modeCard(title: String, description: String, mode: String): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                selectedMode = mode
                refreshSelection()
            }
        }
        card.addView(label(title, 16f, ink, true))
        card.addView(label(description, 13f, muted).apply { setPadding(0, dp(7), 0, dp(12)) })
        card.addView(label("TAP TO SELECT  •  " + if (mode == "maya_friendly") "NO ROMANCE" else "NON-EXPLICIT", 10f, accent, true))
        card.tag = mode
        return card
    }

    private fun refreshSelection() {
        if (!::friendlyCard.isInitialized) return
        listOf(friendlyCard, romanticCard, intenseCard).forEach { card ->
            val chosen = card.tag == selectedMode
            card.background = rounded(if (chosen) Color.rgb(63, 35, 62) else panel, if (chosen) accent else Color.rgb(73, 61, 84))
            card.alpha = if (chosen) 1f else 0.88f
        }
        if (::status.isInitialized) {
            status.text = when (selectedMode) {
                "maya_romantic" -> "Selected: Romantic • affectionate, playful, non-explicit"
                "maya_intense" -> "Selected: Intense • expressive, wholesome, non-explicit"
                else -> "Selected: Friendly • close friend, no romance"
            }
        }
    }
}
