package com.jarvis.assistant.ui.settings

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.ui.glass.CrystalBackdrop
import com.jarvis.assistant.util.ThemeManager

/**
 * Settings > Transparent Background. Pick the 4K "Daydream" scene behind the Transparent (Crystal Mirror) theme, turn Blur mode
 * on and set its strength, and set how much the picture is dimmed. Every choice shows live: this screen sits on the same
 * backdrop, so the picture behind it changes as you tap.
 */
class CrystalBackgroundActivity : AppCompatActivity() {

    private val prefs by lazy { JarvisApp.instance.preferences }
    private val cards = HashMap<String, FrameLayout>()
    private val rings = HashMap<String, View>()
    private val checks = HashMap<String, TextView>()
    private lateinit var blurStrengthRow: View
    private lateinit var blurValue: TextView
    private lateinit var blurSeek: SeekBar
    private lateinit var dimValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(28))
        }

        // ---- header
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "‹"
            textSize = 30f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = glass(dp(22).toFloat(), 0x26FFFFFF, 0x55FFFFFF)
            isClickable = true
            isFocusable = true
            contentDescription = "Back"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
        titles.addView(label("Transparent Background", 22f, bold = true))
        titles.addView(label("Dreamy 4K scenes behind the glass", 12.5f, muted = true))
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        page.addView(header, LinearLayout.LayoutParams(-1, -2))

        // ---- hint when the Transparent theme is not the active theme
        if (prefs.appTheme != "crystal") {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = glass(dp(20).toFloat(), 0x22FFFFFF, 0x55FFFFFF)
            }
            box.addView(label("These backgrounds belong to the Transparent (Crystal Mirror) theme.", 13.5f))
            box.addView(pill("Switch to Crystal Mirror theme") {
                prefs.appTheme = "crystal"
                recreate()
            }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(10) })
            page.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        }

        // ---- scenes
        page.addView(section("Scenes"))
        val gap = dp(12)
        val colW = (resources.displayMetrics.widthPixels - dp(36) - gap) / 2
        val cardH = (colW * 1.5f).toInt()
        val entries = listOf(CrystalBackdrop.SYSTEM to "Phone wallpaper") + CrystalBackdrop.OPTIONS.map { it.id to it.title }
        var rowLayout: LinearLayout? = null
        entries.forEachIndexed { index, (id, name) ->
            if (index % 2 == 0) {
                rowLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                page.addView(rowLayout, LinearLayout.LayoutParams(-1, -2).apply { topMargin = if (index == 0) dp(8) else gap })
            }
            val card = sceneCard(id, name, colW, cardH)
            rowLayout?.addView(card, LinearLayout.LayoutParams(colW, cardH).apply { if (index % 2 == 0) marginEnd = gap })
        }

        // ---- blur
        page.addView(section("Blur mode"))
        val blurBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = glass(dp(22).toFloat(), 0x22FFFFFF, 0x55FFFFFF)
        }
        val blurTop = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val blurTexts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        blurTexts.addView(label("Soften the picture", 15f, bold = true))
        blurTexts.addView(label("A frosted look that keeps text calm and easy to read", 12f, muted = true))
        blurTop.addView(blurTexts, LinearLayout.LayoutParams(0, -2, 1f))
        val blurSwitch = SwitchCompat(this).apply { isChecked = prefs.crystalBlurEnabled }
        blurTop.addView(blurSwitch)
        blurBox.addView(blurTop)

        blurStrengthRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        val blurLine = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        blurLine.addView(label("Blur strength", 13f), LinearLayout.LayoutParams(0, -2, 1f))
        blurValue = label("${prefs.crystalBlurStrength}%", 13f, bold = true)
        blurLine.addView(blurValue)
        (blurStrengthRow as LinearLayout).addView(blurLine)
        blurSeek = SeekBar(this).apply {
            max = 100
            progress = prefs.crystalBlurStrength
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {
                    blurValue.text = "$value%"
                    if (fromUser) { prefs.crystalBlurStrength = value; CrystalBackdrop.notifyChanged() }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })
        }
        (blurStrengthRow as LinearLayout).addView(blurSeek, LinearLayout.LayoutParams(-1, -2))
        blurBox.addView(blurStrengthRow, LinearLayout.LayoutParams(-1, -2))
        blurSwitch.setOnCheckedChangeListener { _, on ->
            prefs.crystalBlurEnabled = on
            setBlurControlsEnabled(on)
            CrystalBackdrop.notifyChanged()
        }
        setBlurControlsEnabled(prefs.crystalBlurEnabled)
        page.addView(blurBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        // ---- dim
        page.addView(section("Brightness"))
        val dimBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = glass(dp(22).toFloat(), 0x22FFFFFF, 0x55FFFFFF)
        }
        val dimLine = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        dimLine.addView(label("Dim for readability", 15f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        dimValue = label("${prefs.crystalDim}%", 13f, bold = true)
        dimLine.addView(dimValue)
        dimBox.addView(dimLine)
        dimBox.addView(label("More dim makes text and glass clearer; less shows the scene at full colour.", 12f, muted = true))
        dimBox.addView(SeekBar(this).apply {
            max = 70
            progress = prefs.crystalDim
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {
                    dimValue.text = "$value%"
                    if (fromUser) { prefs.crystalDim = value; CrystalBackdrop.notifyChanged() }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        page.addView(dimBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        page.addView(label("Scenes are drawn at 3840 × 2160 (4K) and fitted to your screen to keep the app smooth and light on memory.", 11.5f, muted = true).apply {
            setPadding(dp(4), dp(16), dp(4), 0)
        })

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(page)
        }
        setContentView(scroll)
        refreshSelection()
    }

    // ------------------------------------------------------------------ pieces
    private fun sceneCard(id: String, name: String, w: Int, h: Int): FrameLayout {
        val card = FrameLayout(this).apply {
            isClickable = true
            isFocusable = true
            contentDescription = name
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(20).toFloat())
                }
            }
            setOnClickListener { choose(id) }
        }
        if (id == CrystalBackdrop.SYSTEM) {
            card.background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(36, 52, 92), Color.rgb(92, 70, 140), Color.rgb(20, 28, 56))
            )
            card.addView(TextView(this).apply {
                text = "◐"
                textSize = 44f
                setTextColor(Color.argb(200, 255, 255, 255))
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(-1, -1))
        } else {
            val iv = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.rgb(20, 24, 44))
                contentDescription = null
            }
            card.addView(iv, FrameLayout.LayoutParams(-1, -1))
            CrystalBackdrop.thumbnail(this, id) { bmp: Bitmap? -> if (bmp != null && !isDestroyed) iv.setImageBitmap(bmp) }
        }
        // name plate
        val plate = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(22), dp(12), dp(11))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.TRANSPARENT, Color.argb(190, 4, 8, 20)))
        }
        plate.addView(label(name, 14f, bold = true))
        val mood = CrystalBackdrop.OPTIONS.firstOrNull { it.id == id }?.mood ?: "Your own wallpaper shows through"
        plate.addView(label(mood, 10.5f, muted = true).apply { maxLines = 2 })
        card.addView(plate, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        // selection ring + tick
        val ring = View(this).apply { visibility = View.GONE }
        ring.background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = dp(20).toFloat()
            setStroke(dp(3), Color.WHITE)
        }
        card.addView(ring, FrameLayout.LayoutParams(-1, -1))
        val tick = TextView(this).apply {
            text = "✓"
            textSize = 14f
            setTextColor(Color.rgb(10, 18, 32))
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
            visibility = View.GONE
        }
        card.addView(tick, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(10), dp(10), 0) })
        cards[id] = card
        rings[id] = ring
        checks[id] = tick
        return card
    }

    private fun choose(id: String) {
        if (prefs.crystalBackground == id) return
        prefs.crystalBackground = id
        CrystalBackdrop.notifyChanged()
        refreshSelection()
    }

    private fun refreshSelection() {
        val current = CrystalBackdrop.selectedId()
        for ((id, ring) in rings) {
            val on = id == current
            ring.visibility = if (on) View.VISIBLE else View.GONE
            checks[id]?.visibility = if (on) View.VISIBLE else View.GONE
        }
    }

    private fun setBlurControlsEnabled(on: Boolean) {
        blurStrengthRow.alpha = if (on) 1f else 0.40f
        blurSeek.isEnabled = on
    }

    // ------------------------------------------------------------------ small helpers
    private fun section(text: String): TextView = label(text.uppercase(), 11.5f, bold = true, muted = true).apply {
        letterSpacing = 0.14f
        setPadding(dp(4), dp(22), 0, 0)
    }

    private fun label(text: String, sp: Float, bold: Boolean = false, muted: Boolean = false): TextView = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(if (muted) Color.argb(205, 215, 228, 248) else Color.WHITE)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), Color.argb(110, 0, 0, 0))
    }

    private fun pill(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(11), dp(18), dp(11))
        background = glass(dp(24).toFloat(), 0x44FFFFFF, 0x99FFFFFF)
        isClickable = true
        isFocusable = true
        layoutParams = LinearLayout.LayoutParams(-2, -2)
        setOnClickListener { onClick() }
    }

    private fun glass(radius: Float, fill: Int, stroke: Int): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius
        setStroke(dp(1), stroke)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
