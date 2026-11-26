package com.jarvis.assistant.ui.studyboard

import android.app.Activity
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.jarvis.assistant.R
import com.jarvis.assistant.study.ExtractedQuestion
import com.jarvis.assistant.study.StudyLevels
import com.jarvis.assistant.study.StudyProfile
import com.jarvis.assistant.ui.glass.LiquidGlass
import com.jarvis.assistant.ui.premium.DialogInk
import com.jarvis.assistant.ui.premium.PremiumDialog
import com.jarvis.assistant.ui.premium.PremiumPalette
import com.jarvis.assistant.util.JarvisFonts
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/** The pop-ups of the Study Board: teaching settings, checking a question read from an image, choosing a PDF page, and export. */
object StudyDialogs {

    private fun dp(a: Activity, v: Int) = StudyTheme.dp(a, v)
    private fun dpf(a: Activity, v: Float) = StudyTheme.dpf(a, v)

    /** Level, explanation language, depth and narration language. Saved as soon as the student confirms. */
    fun settings(a: Activity, prefs: StudyPrefs, onForgetMemory: () -> Unit, onSaved: () -> Unit) {
        val pal = StudyTheme.palette(a)
        val dialog = BottomSheetDialog(a, R.style.ThemeOverlay_Jarvis_BottomSheet)

        var selectedProvider = prefs.provider
        var selectedLevel = prefs.level
        var selectedLanguage = prefs.language
        var selectedDepth = prefs.depth
        var selectedVoiceLang = prefs.voiceLanguage

        val sheetBg = ColorUtils.compositeColors(
            StudyTheme.alpha(pal.surface, if (pal.light) 0.94f else 0.88f),
            pal.baseBg
        )
        val root = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(a, 18), dp(a, 12), dp(a, 18), dp(a, 22))
            background = GradientDrawable().apply {
                setColor(sheetBg)
                val r = dpf(a, 28f)
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                setStroke(dp(a, 1), pal.cardBorder)
            }
        }

        // Drag handle
        root.addView(
            View(a).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dpf(a, 4f)
                    setColor(StudyTheme.alpha(pal.textMuted, 0.45f))
                }
            },
            LinearLayout.LayoutParams(dp(a, 42), dp(a, 4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(a, 12)
            }
        )

        // Header
        val header = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleCol = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(StudyTheme.label(a, "TEACHING SETTINGS", 11f, pal.accent, hud = true).apply { letterSpacing = 0.14f })
        titleCol.addView(
            StudyTheme.label(a, "Study Board Preferences", 20f, pal.textPrimary, bold = true).apply {
                typeface = JarvisFonts.displayBold
                setPadding(0, dp(a, 2), 0, 0)
            }
        )
        header.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(StudyTheme.iconButton(a, R.drawable.ic_close, "Close", pal, sizeDp = 44, padDp = 12) { dialog.dismiss() })
        root.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(a, 14) })

        val maxScrollH = (a.resources.displayMetrics.heightPixels * 0.62f).toInt()
        val scroll = object : ScrollView(a) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(maxScrollH, View.MeasureSpec.AT_MOST))
            }
        }.apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, -2))

        fun cardBox(): LinearLayout = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(a, 14), dp(a, 12), dp(a, 14), dp(a, 12))
            background = StudyTheme.pillDrawable(a, 22f, StudyTheme.alpha(pal.cardBg, 0.72f), pal.cardBorder)
        }

        fun sectionTitle(text: String) = StudyTheme.label(a, text, 11f, pal.accent, hud = true).apply {
            letterSpacing = 0.12f
            setPadding(dp(a, 4), dp(a, 4), 0, dp(a, 8))
        }

        fun divider() = View(a).apply {
            setBackgroundColor(StudyTheme.alpha(pal.cardBorder, 0.6f))
            layoutParams = LinearLayout.LayoutParams(-1, dp(a, 1)).apply { setMargins(0, dp(a, 8), 0, dp(a, 8)) }
        }

        fun selectorRow(title: String, initialValue: String, onClick: (TextView) -> Unit): LinearLayout {
            val valueBadge = StudyTheme.label(a, initialValue, 12.5f, pal.accent, bold = true).apply {
                setPadding(dp(a, 10), dp(a, 5), dp(a, 10), dp(a, 5))
                background = StudyTheme.pillDrawable(a, 12f, StudyTheme.alpha(pal.accent, 0.14f), StudyTheme.alpha(pal.accent, 0.35f))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            return LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(a, 48)
                setPadding(dp(a, 2), dp(a, 6), dp(a, 2), dp(a, 6))
                isClickable = true
                isFocusable = true
                StudyTheme.applyPressSpring(this, 0.97f)
                addView(
                    StudyTheme.label(a, title, 14f, pal.textPrimary, bold = true),
                    LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(a, 8) }
                )
                addView(valueBadge)
                setOnClickListener { onClick(valueBadge) }
            }
        }

        // ---- Card 1: AI & Teaching ----
        body.addView(sectionTitle("AI & TEACHING"))
        val aiCard = cardBox()

        aiCard.addView(StudyTheme.label(a, "AI provider", 13.5f, pal.textPrimary, bold = true).apply { setPadding(0, 0, 0, dp(a, 6)) })
        val providerContainer = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(a, 3), dp(a, 3), dp(a, 3), dp(a, 3))
            background = StudyTheme.pillDrawable(a, 16f, StudyTheme.alpha(pal.baseBg, 0.45f), pal.cardBorder)
        }
        val providerOptions = listOf(StudyPrefs.PROVIDER_GEMINI to "Gemini (default)", StudyPrefs.PROVIDER_CUSTOM to "Custom provider")
        val providerButtons = ArrayList<TextView>()
        fun paintProviders() {
            providerOptions.forEachIndexed { idx, (key, _) ->
                val active = key == selectedProvider
                val btn = providerButtons[idx]
                btn.background = if (active) StudyTheme.pillDrawable(a, 13f, pal.accent, pal.accent) else GradientDrawable()
                btn.setTextColor(if (active) pal.onAccent else pal.textSecondary)
            }
        }
        providerOptions.forEach { (key, title) ->
            val btn = StudyTheme.label(a, title, 12.5f, pal.textPrimary, bold = true).apply {
                minHeight = dp(a, 44)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(a, 10), dp(a, 8), dp(a, 10), dp(a, 8))
                isClickable = true
                isFocusable = true
                StudyTheme.applyPressSpring(this, 0.96f)
                setOnClickListener { selectedProvider = key; paintProviders() }
            }
            providerButtons.add(btn)
            providerContainer.addView(btn, LinearLayout.LayoutParams(0, dp(a, 44), 1f))
        }
        paintProviders()
        aiCard.addView(providerContainer, LinearLayout.LayoutParams(-1, -2))
        aiCard.addView(
            StudyTheme.label(
                a,
                "Gemini uses the key saved in Settings and switches model by itself if one is busy. Custom uses the provider you activated in Settings.",
                11.5f,
                pal.textMuted
            ).apply {
                setLineSpacing(dpf(a, 2f), 1f)
                setPadding(dp(a, 2), dp(a, 6), dp(a, 2), dp(a, 2))
            }
        )

        aiCard.addView(divider())
        aiCard.addView(selectorRow("Your level", selectedLevel) { badge ->
            val idx = StudyLevels.CHOICES.indexOfFirst { it.equals(selectedLevel, true) }.coerceAtLeast(0)
            PremiumDialog.choice(a, "LEVEL", "Your level", "Adjusts how concepts and derivations are explained.", StudyLevels.CHOICES, idx, instant = true) { pick ->
                selectedLevel = StudyLevels.CHOICES[pick]
                badge.text = selectedLevel
            }
        })

        aiCard.addView(divider())
        aiCard.addView(selectorRow("Explanation language", selectedLanguage) { badge ->
            val idx = StudyLevels.LANGUAGES.indexOfFirst { it.equals(selectedLanguage, true) }.coerceAtLeast(0)
            PremiumDialog.choice(a, "LANGUAGE", "Explanation language", "Language for board text and explanations.", StudyLevels.LANGUAGES, idx, instant = true) { pick ->
                selectedLanguage = StudyLevels.LANGUAGES[pick]
                badge.text = selectedLanguage
            }
        })

        aiCard.addView(divider())
        aiCard.addView(StudyTheme.label(a, "Explanation depth", 13.5f, pal.textPrimary, bold = true).apply { setPadding(0, 0, 0, dp(a, 6)) })
        val depthContainer = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(a, 3), dp(a, 3), dp(a, 3), dp(a, 3))
            background = StudyTheme.pillDrawable(a, 16f, StudyTheme.alpha(pal.baseBg, 0.45f), pal.cardBorder)
        }
        val depths = StudyProfile.Depth.values().toList()
        val depthButtons = ArrayList<TextView>()
        fun paintDepths() {
            depths.forEachIndexed { idx, d ->
                val active = d == selectedDepth
                val btn = depthButtons[idx]
                btn.background = if (active) StudyTheme.pillDrawable(a, 13f, pal.accent, pal.accent) else GradientDrawable()
                btn.setTextColor(if (active) pal.onAccent else pal.textSecondary)
            }
        }
        depths.forEach { d ->
            val btn = StudyTheme.label(a, d.label, 12.5f, pal.textPrimary, bold = true).apply {
                minHeight = dp(a, 44)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(a, 8), dp(a, 8), dp(a, 8), dp(a, 8))
                isClickable = true
                isFocusable = true
                StudyTheme.applyPressSpring(this, 0.96f)
                setOnClickListener { selectedDepth = d; paintDepths() }
            }
            depthButtons.add(btn)
            depthContainer.addView(btn, LinearLayout.LayoutParams(0, dp(a, 44), 1f))
        }
        paintDepths()
        aiCard.addView(depthContainer, LinearLayout.LayoutParams(-1, -2))

        aiCard.addView(divider())
        aiCard.addView(selectorRow("Narration & mic language", selectedVoiceLang) { badge ->
            val idx = StudyLevels.LANGUAGES.indexOfFirst { it.equals(selectedVoiceLang, true) }.coerceAtLeast(0)
            PremiumDialog.choice(a, "VOICE", "Narration & mic language", "Language for spoken lessons and voice questions.", StudyLevels.LANGUAGES, idx, instant = true) { pick ->
                selectedVoiceLang = StudyLevels.LANGUAGES[pick]
                badge.text = selectedVoiceLang
            }
        })

        body.addView(aiCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(a, 14) })

        // ---- Card 2: Board & Memory ----
        body.addView(sectionTitle("BOARD & MEMORY"))
        val boardCard = cardBox()

        fun switchRow(title: String, checked: Boolean): Pair<LinearLayout, SwitchCompat> {
            val sw = SwitchCompat(a).apply {
                isChecked = checked
                ThemeManager.tintSwitch(this, pal.accent)
            }
            val row = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(a, 48)
                setPadding(dp(a, 2), dp(a, 4), dp(a, 2), dp(a, 4))
                isClickable = true
                isFocusable = true
                addView(
                    StudyTheme.label(a, title, 13.5f, pal.textPrimary, bold = true),
                    LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(a, 10) }
                )
                addView(sw)
                setOnClickListener { sw.toggle() }
            }
            return row to sw
        }

        val (statusRow, statusSwitch) = switchRow("Show Thinking / Writing status (top bar)", prefs.statusOn)
        val (penRow, penSwitch) = switchRow("Show the pen on the board while it writes", prefs.penOn)
        val (memoryRow, memorySwitch) = switchRow("Teacher remembers my earlier questions", prefs.memoryOn)
        boardCard.addView(statusRow)
        boardCard.addView(divider())
        boardCard.addView(penRow)
        boardCard.addView(divider())
        boardCard.addView(memoryRow)
        boardCard.addView(divider())

        val forgetBtn = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            minimumHeight = dp(a, 46)
            setPadding(dp(a, 14), dp(a, 10), dp(a, 14), dp(a, 10))
            background = StudyTheme.pillDrawable(a, 14f, pal.errorContainer, StudyTheme.alpha(pal.error, 0.45f))
            isClickable = true
            isFocusable = true
            StudyTheme.applyPressSpring(this, 0.96f)
            addView(StudyTheme.label(a, "Forget earlier questions", 13.5f, pal.error, bold = true))
            setOnClickListener { onForgetMemory() }
        }
        boardCard.addView(forgetBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(a, 4) })
        body.addView(boardCard, LinearLayout.LayoutParams(-1, -2))

        body.addView(
            StudyTheme.label(
                a,
                "Auto level uses the class named in your question, then your age from your profile. Any language works: Auto follows the language you ask in. Narration uses the JARVIS voice when it is available, and your phone's voice otherwise.",
                11.5f,
                pal.textMuted
            ).apply {
                setLineSpacing(dpf(a, 2f), 1f)
                setPadding(dp(a, 4), dp(a, 12), dp(a, 4), dp(a, 4))
            }
        )

        // Bottom actions
        val buttons = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(a, 14), 0, 0)
        }
        val cancelBtn = StudyTheme.label(a, "Cancel", 14f, pal.textPrimary, bold = true).apply {
            minHeight = dp(a, 48)
            gravity = Gravity.CENTER
            background = StudyTheme.pillDrawable(a, 18f, StudyTheme.alpha(pal.cardBg, 0.7f), pal.cardBorder)
            isClickable = true
            isFocusable = true
            StudyTheme.applyPressSpring(this, 0.96f)
            setOnClickListener { dialog.dismiss() }
        }
        val saveBtn = StudyTheme.label(a, "Save settings", 14f, pal.onAccent, bold = true).apply {
            minHeight = dp(a, 48)
            gravity = Gravity.CENTER
            background = StudyTheme.gradientPillDrawable(a, 18f, pal.accent, pal.secondary)
            isClickable = true
            isFocusable = true
            StudyTheme.applyPressSpring(this, 0.96f)
            setOnClickListener {
                prefs.provider = selectedProvider
                prefs.level = selectedLevel
                prefs.language = selectedLanguage
                prefs.depth = selectedDepth
                prefs.voiceLanguage = selectedVoiceLang
                prefs.statusOn = statusSwitch.isChecked
                prefs.penOn = penSwitch.isChecked
                prefs.memoryOn = memorySwitch.isChecked
                onSaved()
                dialog.dismiss()
            }
        }
        buttons.addView(cancelBtn, LinearLayout.LayoutParams(0, dp(a, 48), 1f).apply { marginEnd = dp(a, 8) })
        buttons.addView(saveBtn, LinearLayout.LayoutParams(0, dp(a, 48), 1.35f))
        root.addView(buttons)

        dialog.setContentView(root)
        dialog.setOnShowListener {
            LiquidGlass.blurBehind(dialog, radiusDp = 22f, dim = if (pal.light) 0.18f else 0.34f)
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
                sheet.background = ColorDrawable(Color.TRANSPARENT)
                BottomSheetBehavior.from(sheet).apply {
                    state = BottomSheetBehavior.STATE_EXPANDED
                    skipCollapsed = true
                }
            }
        }
        dialog.show()
    }

    /**
     * Shows what was read from the image and lets the student fix it. Nothing is solved until "Solve" is pressed, so a
     * misread question can never be solved silently.
     */
    fun confirmQuestions(a: Activity, questions: List<ExtractedQuestion>, source: String, onSolve: (String, Boolean) -> Unit) {
        val d = PremiumDialog.create(a)
            .eyebrow("QUESTION CHECK")
            .title("Is this your question?")
            .message("Read from $source. Check it before solving.")

        val p = d.palette
        val edit = EditText(a).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3
            maxLines = 8
            gravity = Gravity.TOP or Gravity.START
            setTextColor(DialogInk.primary)
            setHintTextColor(DialogInk.hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = JarvisFonts.regular
            setPadding(dp(a, 14), dp(a, 12), dp(a, 14), dp(a, 12))
            background = GradientDrawable().apply {
                cornerRadius = dpf(a, 16f)
                setColor(StudyTheme.alpha(DialogInk.primary, 0.06f))
                setStroke(dp(a, 1), StudyTheme.alpha(p.accent1, 0.45f))
            }
            setText(questions.first().text)
        }

        val warn = TextView(a).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = JarvisFonts.medium
            setTextColor(PremiumPalette.DANGER_1)
            setPadding(dp(a, 2), dp(a, 8), dp(a, 2), 0)
            visibility = View.GONE
        }

        val figureHolder = FrameLayout(a)
        var includeFigure = questions.first().hasDiagram
        var figureVisible = questions.first().hasDiagram
        var index = 0

        fun rebuildFigureRow(q: ExtractedQuestion) {
            figureHolder.removeAllViews()
            figureVisible = q.hasDiagram
            includeFigure = q.hasDiagram
            if (q.hasDiagram) {
                figureHolder.visibility = View.VISIBLE
                val row = PremiumDialog.checkRow(a, p, "Send the picture too (the question has a figure)", true) { checked ->
                    includeFigure = checked
                }
                figureHolder.addView(row, FrameLayout.LayoutParams(-1, -2))
            } else {
                figureHolder.visibility = View.GONE
            }
        }

        val qTabs = ArrayList<TextView>()
        fun paintTabs() {
            qTabs.forEachIndexed { idx, tv ->
                val active = idx == index
                tv.background = GradientDrawable().apply {
                    cornerRadius = dpf(a, 14f)
                    setColor(if (active) p.accent1 else StudyTheme.alpha(DialogInk.primary, 0.08f))
                    setStroke(dp(a, 1), if (active) p.accent1 else StudyTheme.alpha(DialogInk.primary, 0.18f))
                }
                val on = if (ColorUtils.calculateLuminance(p.accent1) > 0.45) Color.BLACK else Color.WHITE
                tv.setTextColor(if (active) on else DialogInk.body)
            }
        }

        fun show(i: Int) {
            index = i
            val q = questions[i]
            edit.setText(q.text)
            edit.setSelection(edit.text.length)
            val msg = when {
                q.text.contains("[unclear]") -> "Part of this was hard to read. Please check it and fix the marked part."
                q.confidence != "high" -> "I am not fully sure I read this correctly. Please check it."
                else -> ""
            }
            warn.text = msg
            warn.visibility = if (msg.isEmpty()) View.GONE else View.VISIBLE
            rebuildFigureRow(q)
            paintTabs()
        }

        if (questions.size > 1) {
            val scroller = HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false }
            val tabRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            questions.forEachIndexed { i, q ->
                val tab = TextView(a).apply {
                    text = "Q${i + 1}: " + q.text.replace('\n', ' ').take(22)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    typeface = JarvisFonts.semibold
                    minHeight = dp(a, 44)
                    gravity = Gravity.CENTER
                    setPadding(dp(a, 12), dp(a, 8), dp(a, 12), dp(a, 8))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { if (i != index) show(i) }
                }
                qTabs.add(tab)
                tabRow.addView(tab, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(a, 8) })
            }
            scroller.addView(tabRow)
            d.add(scroller)
            d.add(PremiumDialog.spacer(a, 10f))
        }

        show(0)
        d.add(edit)
            .add(warn)
            .add(PremiumDialog.spacer(a, 10f))
            .add(figureHolder)
            .negative("Cancel")
            .positive("Solve") {
                val t = edit.text.toString().trim()
                if (t.isEmpty() || t.contains("[unclear]")) {
                    warn.text = "Please fix the unclear part before solving."
                    warn.visibility = View.VISIBLE
                    Toast.makeText(a, "Please fix the unclear part before solving.", Toast.LENGTH_LONG).show()
                    false
                } else {
                    onSolve(t, includeFigure && figureVisible)
                    true
                }
            }
            .show()
    }

    /** Browse the PDF page by page, then read the questions on a page or ask about the whole document. */
    fun pdfPicker(a: Activity, scope: CoroutineScope, path: String, pages: Int, onPage: (Int) -> Unit, onWhole: (String) -> Unit) {
        if (pages <= 0) {
            Toast.makeText(a, "This PDF has no pages.", Toast.LENGTH_SHORT).show()
            return
        }
        val d = PremiumDialog.create(a)
            .eyebrow("PDF DOCUMENT")
            .title("Choose from the PDF")

        val p = d.palette
        val pageBadge = TextView(a).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = JarvisFonts.hudBold
            setTextColor(DialogInk.accent(p.accent1, 0.2f))
            gravity = Gravity.CENTER
            setPadding(dp(a, 12), dp(a, 5), dp(a, 12), dp(a, 5))
            background = GradientDrawable().apply {
                cornerRadius = dpf(a, 12f)
                setColor(StudyTheme.alpha(p.accent1, 0.16f))
            }
        }

        val previewFrame = FrameLayout(a).apply {
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, dpf(a, 16f))
                }
            }
            background = GradientDrawable().apply {
                cornerRadius = dpf(a, 16f)
                setColor(StudyPalette.PAPER)
                setStroke(dp(a, 1), StudyTheme.alpha(p.accent1, 0.35f))
            }
        }
        val preview = ImageView(a).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        previewFrame.addView(preview, FrameLayout.LayoutParams(-1, -1))

        val nav = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun navPill(text: String) = TextView(a).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = JarvisFonts.semibold
            setTextColor(DialogInk.primary)
            minHeight = dp(a, 44)
            gravity = Gravity.CENTER
            setPadding(dp(a, 16), dp(a, 10), dp(a, 16), dp(a, 10))
            background = GradientDrawable().apply {
                cornerRadius = dpf(a, 14f)
                setColor(StudyTheme.alpha(DialogInk.primary, 0.08f))
                setStroke(dp(a, 1), StudyTheme.alpha(DialogInk.primary, 0.18f))
            }
            isClickable = true
            isFocusable = true
        }
        val prev = navPill("‹  Previous")
        val next = navPill("Next  ›")
        nav.addView(prev, LinearLayout.LayoutParams(0, dp(a, 44), 1f).apply { marginEnd = dp(a, 8) })
        nav.addView(next, LinearLayout.LayoutParams(0, dp(a, 44), 1f))

        val wholeBtn = TextView(a).apply {
            text = "Ask about the whole PDF"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = JarvisFonts.semibold
            setTextColor(DialogInk.accent(p.accent2, 0.25f))
            minHeight = dp(a, 46)
            gravity = Gravity.CENTER
            setPadding(dp(a, 14), dp(a, 10), dp(a, 14), dp(a, 10))
            background = GradientDrawable().apply {
                cornerRadius = dpf(a, 16f)
                setColor(StudyTheme.alpha(p.accent2, 0.14f))
                setStroke(dp(a, 1), StudyTheme.alpha(p.accent2, 0.45f))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                d.dismiss()
                askWhole(a, onWhole)
            }
        }

        var page = 0
        var job: Job? = null
        fun load() {
            pageBadge.text = "Page ${page + 1} of $pages"
            prev.alpha = if (page > 0) 1f else 0.4f
            prev.isEnabled = page > 0
            next.alpha = if (page < pages - 1) 1f else 0.4f
            next.isEnabled = page < pages - 1
            job?.cancel()
            preview.setImageDrawable(null)
            job = scope.launch {
                try { preview.setImageBitmap(StudyMedia.renderPage(File(path), page, 700)) }
                catch (e: StudyMedia.MediaException) { pageBadge.text = e.message ?: "Could not show this page." }
            }
        }
        prev.setOnClickListener { if (page > 0) { page--; load() } }
        next.setOnClickListener { if (page < pages - 1) { page++; load() } }
        load()

        d.add(pageBadge)
            .add(PremiumDialog.spacer(a, 8f))
            .add(previewFrame.apply { layoutParams = LinearLayout.LayoutParams(-1, dp(a, 240)) })
            .add(PremiumDialog.spacer(a, 10f))
            .add(nav)
            .add(PremiumDialog.spacer(a, 10f))
            .add(wholeBtn)
            .negative("Cancel")
            .positive("Read this page's questions") { onPage(page); true }
            .onDismissed {
                job?.cancel()
                (preview.drawable as? BitmapDrawable)?.bitmap?.let {
                    preview.setImageDrawable(null)
                    if (!it.isRecycled) it.recycle()
                }
            }
            .show()
    }

    /** Sleek export chooser replacing the plain stock popup menu. */
    fun exportSheet(a: Activity, onPdf: () -> Unit, onImage: () -> Unit) {
        val d = PremiumDialog.create(a)
            .eyebrow("EXPORT")
            .title("Save Study Notes")
            .message("Export the whiteboard solution to share or print.")

        val p = d.palette
        fun optionCard(iconRes: Int, title: String, subtitle: String, tone: Int, onClick: () -> Unit): LinearLayout =
            LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(a, 64)
                setPadding(dp(a, 14), dp(a, 12), dp(a, 14), dp(a, 12))
                background = GradientDrawable().apply {
                    cornerRadius = dpf(a, 18f)
                    setColor(StudyTheme.alpha(tone, 0.14f))
                    setStroke(dp(a, 1), StudyTheme.alpha(tone, 0.45f))
                }
                isClickable = true
                isFocusable = true
                StudyTheme.applyPressSpring(this, 0.97f)
                val badge = FrameLayout(a).apply {
                    background = GradientDrawable().apply {
                        cornerRadius = dpf(a, 12f)
                        setColor(StudyTheme.alpha(tone, 0.22f))
                    }
                    addView(
                        ImageView(a).apply {
                            setImageResource(iconRes)
                            setColorFilter(DialogInk.accent(tone, 0.2f))
                            contentDescription = null
                        },
                        FrameLayout.LayoutParams(dp(a, 22), dp(a, 22), Gravity.CENTER)
                    )
                }
                addView(badge, LinearLayout.LayoutParams(dp(a, 42), dp(a, 42)).apply { marginEnd = dp(a, 12) })
                val txtCol = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
                txtCol.addView(TextView(a).apply {
                    text = title
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    typeface = JarvisFonts.semibold
                    setTextColor(DialogInk.primary)
                })
                txtCol.addView(TextView(a).apply {
                    text = subtitle
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    typeface = JarvisFonts.regular
                    setTextColor(DialogInk.muted)
                })
                addView(txtCol, LinearLayout.LayoutParams(0, -2, 1f))
                setOnClickListener {
                    d.dismiss()
                    onClick()
                }
            }

        d.add(optionCard(R.drawable.ic_study_pdf, "Export notes as PDF", "Paginated printable document with bookmarks", p.accent1, onPdf))
            .add(PremiumDialog.spacer(a, 10f))
            .add(optionCard(R.drawable.ic_study_image, "Export board as image", "High-resolution PNG of the full whiteboard", p.accent2, onImage))
            .negative("Cancel")
            .show()
    }

    private fun askWhole(a: Activity, onWhole: (String) -> Unit) {
        val d = PremiumDialog.create(a)
            .eyebrow("DOCUMENT")
            .title("Ask about the whole PDF")
            .message("Ask a question that spans multiple pages of this PDF.")
        val p = d.palette
        val edit = EditText(a).apply {
            hint = "What do you want to know about this document?"
            minLines = 3
            maxLines = 6
            gravity = Gravity.TOP or Gravity.START
            setTextColor(DialogInk.primary)
            setHintTextColor(DialogInk.hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = JarvisFonts.regular
            setPadding(dp(a, 14), dp(a, 12), dp(a, 14), dp(a, 12))
            background = GradientDrawable().apply {
                cornerRadius = dpf(a, 16f)
                setColor(StudyTheme.alpha(DialogInk.primary, 0.06f))
                setStroke(dp(a, 1), StudyTheme.alpha(p.accent1, 0.45f))
            }
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        d.add(edit)
            .focus(edit)
            .negative("Cancel")
            .positive("Ask") {
                val t = edit.text.toString().trim()
                if (t.isNotEmpty()) { onWhole(t); true } else false
            }
            .show()
    }
}
