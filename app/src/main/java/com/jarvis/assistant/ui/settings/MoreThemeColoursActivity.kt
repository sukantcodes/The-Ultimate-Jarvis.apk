package com.jarvis.assistant.ui.settings

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.jarvis.assistant.JarvisApp

/**
 * Palette of 50 curated two-colour blends. Each swatch stores the blended midpoint
 * as the custom theme colour, so it works with the existing theme preference system.
 */
class MoreThemeColoursActivity : Activity() {

    private data class Blend(val name: String, val first: String, val second: String, val result: String)

    private val blends = listOf(
        Blend("Ocean × Violet", "#00C6FF", "#7F00FF", "#4083FF"),
        Blend("Cyan × Indigo", "#00F5D4", "#3A0CA3", "#1D80A0"),
        Blend("Arctic × Blue", "#B8F2FF", "#2563EB", "#6CABC7"),
        Blend("Mint × Teal", "#A7F3D0", "#0F766E", "#5EB2A0"),
        Blend("Emerald × Aqua", "#10B981", "#06B6D4", "#0BBEAA"),
        Blend("Lime × Forest", "#BEF264", "#166534", "#6AA34C"),
        Blend("Sage × Seafoam", "#A3B18A", "#84DCC6", "#94CFB8"),
        Blend("Blue × Silver", "#2563EB", "#CBD5E1", "#78A2D0"),
        Blend("Royal × Sky", "#4338CA", "#38BDF8", "#3D7BC4"),
        Blend("Navy × Ice", "#0F172A", "#BAE6FD", "#657FA6"),
        Blend("Purple × Pink", "#7C3AED", "#F472B6", "#B358A2"),
        Blend("Lavender × Blue", "#C4B5FD", "#60A5FA", "#92A0FE"),
        Blend("Lilac × Rose", "#D8B4FE", "#FDA4AF", "#EBAAC7"),
        Blend("Magenta × Indigo", "#D946EF", "#4338CA", "#8E3FE4"),
        Blend("Plum × Orchid", "#701A75", "#E879F9", "#AC49BA"),
        Blend("Berry × Ice", "#9D174D", "#BFDBFE", "#AE7EA6"),
        Blend("Rose × Coral", "#FB7185", "#FB923C", "#FB8090"),
        Blend("Peach × Pink", "#FDBA8C", "#F9A8D4", "#FBB1B0"),
        Blend("Sunset × Purple", "#FB7185", "#8B5CF6", "#C367BA"),
        Blend("Coral × Gold", "#FF6B6B", "#FBBF24", "#FE9536"),
        Blend("Amber × Crimson", "#F59E0B", "#BE123C", "#D85F29"),
        Blend("Flame × Rose", "#EF4444", "#F472B6", "#F15B8A"),
        Blend("Ruby × Violet", "#E11D48", "#7C3AED", "#AD2D9B"),
        Blend("Cherry × Peach", "#BE123C", "#FDBA74", "#DD965E"),
        Blend("Red × Gold", "#DC2626", "#FDE047", "#ED8424"),
        Blend("Honey × Cream", "#F59E0B", "#FEF3C7", "#FABD6B"),
        Blend("Butter × Peach", "#FDE68A", "#FDBA8C", "#FDD07D"),
        Blend("Sunshine × Mint", "#FACC15", "#86EFAC", "#B0DE5D"),
        Blend("Olive × Sand", "#737A2D", "#E7D8B1", "#B2A66F"),
        Blend("Copper × Rose", "#B45309", "#FCA5A5", "#D4AC57"),
        Blend("Chocolate × Gold", "#451A03", "#FBBF24", "#A06D14"),
        Blend("Mocha × Cream", "#78350F", "#F5E6CC", "#BCA892"),
        Blend("Cocoa × Mauve", "#6B3E3E", "#C08497", "#96616A"),
        Blend("Slate × Lavender", "#475569", "#C4B5FD", "#8695A9"),
        Blend("Graphite × Cyan", "#1F2937", "#67E8F9", "#437A9B"),
        Blend("Charcoal × Violet", "#111827", "#A78BFA", "#5C6A94"),
        Blend("Midnight × Rose", "#020617", "#FDA4AF", "#80717B"),
        Blend("Black × Electric Blue", "#030712", "#2563EB", "#142F80"),
        Blend("Smoke × Aqua", "#64748B", "#99F6E4", "#7EB2A9"),
        Blend("Steel × Periwinkle", "#64748B", "#A5B4FC", "#8592C5"),
        Blend("Cloud × Ocean", "#F1F5F9", "#0EA5E9", "#80D2D4"),
        Blend("Pearl × Lilac", "#F8FAFC", "#C4B5FD", "#DEE5DD"),
        Blend("Ivory × Coral", "#FFFBEB", "#FB7185", "#FDBD6A"),
        Blend("Blush × Plum", "#FBCFE8", "#6B21A8", "#B66C78"),
        Blend("Aqua × Peach", "#67E8F9", "#FDBA8C", "#B2D19C"),
        Blend("Tropical × Sunset", "#2DD4BF", "#FB7185", "#94A3A0"),
        Blend("Lagoon × Orchid", "#0891B2", "#C026D3", "#658BB3"),
        Blend("Dragonfruit × Lime", "#DB2777", "#A3E635", "#BF826E"),
        Blend("Aurora × Ice", "#34D399", "#818CF8", "#5AA9C4"),
        Blend("Cosmic × Gold", "#6D28D9", "#FBBF24", "#B6789C")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(24))
            setBackgroundColor(Color.rgb(10, 13, 31))
        }
        val title = TextView(this).apply {
            text = "50 Blended Colours"
            setTextColor(Color.WHITE)
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        }
        root.addView(title)
        root.addView(TextView(this).apply {
            text = "Choose a two-colour blend for your JARVIS theme"
            setTextColor(Color.rgb(170, 181, 205))
            textSize = 14f
            setPadding(0, dp(5), 0, dp(16))
        })
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        blends.forEach { blend ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = rounded(Color.rgb(24, 29, 54), dp(14).toFloat())
                isClickable = true
                isFocusable = true
                val lp = LinearLayout.LayoutParams(-1, -2)
                lp.bottomMargin = dp(9)
                layoutParams = lp
            }
            val swatch = View(this).apply {
                background = gradient(Color.parseColor(blend.first), Color.parseColor(blend.second))
            }
            row.addView(swatch, LinearLayout.LayoutParams(dp(54), dp(46)))
            val labels = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
            }
            labels.addView(TextView(this).apply {
                text = blend.name
                setTextColor(Color.WHITE)
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
            })
            labels.addView(TextView(this).apply {
                text = "${blend.first} + ${blend.second}  →  ${blend.result}"
                setTextColor(Color.rgb(166, 179, 204))
                textSize = 11f
            })
            row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(View(this).apply {
                background = rounded(Color.parseColor(blend.result), dp(24).toFloat())
            }, LinearLayout.LayoutParams(dp(24), dp(24)))
            row.setOnClickListener { applyBlend(blend) }
            list.addView(row)
        }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun applyBlend(blend: Blend) {
        JarvisApp.instance.preferences.customThemeColor = blend.result
        JarvisApp.instance.preferences.appTheme = "custom"
        setResult(RESULT_OK)
        finish()
        // Recreate the settings host so all theme-aware views refresh.
        (parent ?: this).runOnUiThread {
            if (!isFinishing) recreate()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Float): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            setStroke(dp(1), Color.rgb(54, 68, 115))
        }

    private fun gradient(start: Int, end: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(start, end)
        ).apply { cornerRadius = dp(12).toFloat() }
}
