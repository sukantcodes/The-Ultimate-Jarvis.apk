package com.jarvis.assistant.ui.studyboard

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Space
import android.widget.TextView
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.study.data.StudyHistoryManager
import com.jarvis.assistant.study.data.StudySessionRow
import com.jarvis.assistant.ui.glass.GlassBackdropView
import com.jarvis.assistant.ui.premium.PremiumDialog
import com.jarvis.assistant.util.JarvisFonts
import com.jarvis.assistant.util.JarvisHandlesInsets
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import java.text.DateFormat
import java.util.Date

/** Saved and interrupted Study Board lessons: search, filter by subject or saved, reopen, delete. */
class StudyHistoryActivity : AppCompatActivity(), JarvisHandlesInsets {

    private lateinit var history: StudyHistoryManager
    private lateinit var pal: StudyTheme.Palette
    private lateinit var list: ListView
    private lateinit var empty: LinearLayout
    private lateinit var emptyText: TextView
    private lateinit var countBadge: TextView
    private lateinit var chipRow: LinearLayout
    private lateinit var clearSearch: ImageButton
    private var rows: List<StudySessionRow> = emptyList()
    private var query = ""
    private var subject = ""
    private var savedOnly = false
    private var searchJob: Job? = null
    private var appliedThemeKey = ""
    private var appliedCustomHex = ""

    private fun dp(v: Int) = StudyTheme.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        val prefs = runCatching { JarvisApp.instance.preferences }.getOrNull()
        appliedThemeKey = prefs?.appTheme.orEmpty()
        appliedCustomHex = prefs?.customThemeSignature.orEmpty()
        pal = StudyTheme.palette(this)
        history = StudyHistoryManager(this)

        val contentColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // ---- Header ----
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(12))
        }
        header.addView(
            StudyTheme.iconButton(this, R.drawable.ic_back, "Back", pal, sizeDp = 48, padDp = 12) { finish() }.apply {
                StudyTheme.applyGlass(this, 16f, pal, soft = true)
            }
        )
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        titleCol.addView(StudyTheme.label(this, "STUDY BOARD", 11f, pal.accent, hud = true).apply { letterSpacing = 0.14f })
        titleCol.addView(
            StudyTheme.label(this, "Study history", 22f, pal.textPrimary, bold = true).apply {
                typeface = JarvisFonts.displayBold
                setPadding(0, dp(2), 0, 0)
            }
        )
        header.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        countBadge = StudyTheme.label(this, "0", 12f, pal.accent, hud = true).apply {
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = StudyTheme.pillDrawable(this@StudyHistoryActivity, 12f, StudyTheme.alpha(pal.accent, 0.14f), StudyTheme.alpha(pal.accent, 0.35f))
            visibility = View.GONE
        }
        header.addView(countBadge)
        contentColumn.addView(header)

        // ---- Glass Search Bar ----
        val searchBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(dp(14), dp(2), dp(6), dp(2))
        }
        StudyTheme.applyGlass(searchBox, 22f, pal, soft = true)
        val search = EditText(this).apply {
            hint = "Search saved questions"
            setHintTextColor(pal.textMuted)
            setTextColor(pal.textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            typeface = JarvisFonts.regular
            maxLines = 1
            background = null
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(0, dp(10), dp(6), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        clearSearch = StudyTheme.iconButton(this, R.drawable.ic_close, "Clear search", pal, sizeDp = 44, padDp = 12, tint = pal.textMuted) {
            search.setText("")
        }.apply { visibility = View.GONE }
        searchBox.addView(search)
        searchBox.addView(clearSearch)

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                clearSearch.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
                searchJob?.cancel()
                searchJob = lifecycleScope.launch { delay(250); reload() }
            }
        })
        contentColumn.addView(searchBox, LinearLayout.LayoutParams(-1, -2))

        // ---- Filter Chips ----
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(10))
        }
        scroll.addView(chipRow)
        contentColumn.addView(scroll)

        // ---- Lessons List & Empty State ----
        list = ListView(this).apply {
            divider = null
            selector = ColorDrawable(0)
            clipToPadding = false
            setPadding(0, dp(2), 0, dp(16))
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        empty = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(40), dp(28), dp(40))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        val emptyIcon = FrameLayout(this).apply {
            background = StudyTheme.pillDrawable(this@StudyHistoryActivity, 20f, StudyTheme.alpha(pal.accent, 0.14f), StudyTheme.alpha(pal.accent, 0.35f))
            addView(
                ImageView(this@StudyHistoryActivity).apply {
                    setImageResource(R.drawable.ic_study_history)
                    setColorFilter(pal.accent)
                    contentDescription = null
                },
                FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER)
            )
        }
        empty.addView(emptyIcon, LinearLayout.LayoutParams(dp(60), dp(60)).apply { bottomMargin = dp(14) })
        emptyText = StudyTheme.label(this, "No lessons yet. Ask a question on the Study Board and it will appear here.", 14f, pal.textSecondary).apply {
            gravity = Gravity.CENTER
            setLineSpacing(StudyTheme.dpf(this@StudyHistoryActivity, 3f), 1f)
        }
        empty.addView(emptyText)

        contentColumn.addView(list)
        contentColumn.addView(empty)

        setContentView(StudyTheme.wrapWithBackdrop(this, contentColumn, extraPadDp = 14))

        list.setOnItemClickListener { _, _, pos, _ ->
            setResult(RESULT_OK, Intent().putExtra(EXTRA_ID, rows[pos].id))
            finish()
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            val r = rows[pos]
            PremiumDialog.confirm(
                this,
                eyebrow = "HISTORY",
                title = "Delete this lesson?",
                message = r.question.take(120),
                confirmLabel = "Delete",
                destructive = true
            ) {
                lifecycleScope.launch { history.delete(r.id); reload() }
            }
            true
        }
        lifecycleScope.launch { reload() }
    }

    override fun onResume() {
        super.onResume()
        GlassBackdropView.refreshAll()
        val prefs = runCatching { JarvisApp.instance.preferences }.getOrNull()
        if (prefs != null && (appliedThemeKey != prefs.appTheme || appliedCustomHex != prefs.customThemeSignature)) {
            appliedThemeKey = prefs.appTheme
            appliedCustomHex = prefs.customThemeSignature
            recreate()
        }
    }

    private fun chip(text: String, on: Boolean, click: () -> Unit) = StudyTheme.chip(this, text, pal, minHeightDp = 44) { click() }.apply {
        setPadding(dp(14), dp(8), dp(14), dp(8))
        setTextColor(if (on) pal.onAccent else pal.textPrimary)
        background = if (on) {
            StudyTheme.pillDrawable(this@StudyHistoryActivity, 18f, pal.accent, pal.accent)
        } else {
            StudyTheme.pillDrawable(this@StudyHistoryActivity, 18f, StudyTheme.alpha(pal.cardBg, 0.72f), pal.cardBorder)
        }
        layoutParams = LinearLayout.LayoutParams(-2, dp(44)).apply { marginEnd = dp(8) }
    }

    private suspend fun reload() {
        rows = history.search(query, subject, savedOnly)
        val subjects = history.subjects()
        chipRow.removeAllViews()
        chipRow.addView(chip("All", subject.isEmpty() && !savedOnly) { subject = ""; savedOnly = false; lifecycleScope.launch { reload() } })
        chipRow.addView(chip("Saved", savedOnly) { savedOnly = !savedOnly; lifecycleScope.launch { reload() } })
        for (s in subjects) chipRow.addView(chip(s, subject == s) { subject = if (subject == s) "" else s; lifecycleScope.launch { reload() } })

        countBadge.visibility = if (rows.isNotEmpty()) View.VISIBLE else View.GONE
        countBadge.text = rows.size.toString()

        val isEmpty = rows.isEmpty()
        list.visibility = if (isEmpty) View.GONE else View.VISIBLE
        empty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        emptyText.text = if (query.isNotEmpty() || subject.isNotEmpty() || savedOnly) "No lessons match." else "No lessons yet. Ask a question on the Study Board and it will appear here."

        list.adapter = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(i: Int) = rows[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, convert: View?, parent: ViewGroup): View {
                val holder: HistoryRowHolder
                val wrap: View
                if (convert != null && convert.tag is HistoryRowHolder) {
                    wrap = convert
                    holder = convert.tag as HistoryRowHolder
                } else {
                    holder = createRowHolder()
                    wrap = holder.root
                    wrap.tag = holder
                }
                bindRowHolder(holder, rows[i])
                return wrap
            }
        }
    }

    private class HistoryRowHolder(
        val root: View,
        val subjectBadge: TextView,
        val interruptedBadge: TextView,
        val starIcon: ImageView,
        val questionText: TextView,
        val metaText: TextView
    )

    private fun createRowHolder(): HistoryRowHolder {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        StudyTheme.applyGlass(card, 20f, pal)

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val subjectBadge = StudyTheme.label(this, "", 10f, pal.accent, hud = true).apply {
            letterSpacing = 0.08f
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = StudyTheme.pillDrawable(
                this@StudyHistoryActivity, 8f,
                StudyTheme.alpha(pal.accent, 0.14f),
                StudyTheme.alpha(pal.accent, 0.30f)
            )
        }
        val interruptedBadge = StudyTheme.label(this, "INTERRUPTED", 10f, pal.warning, hud = true).apply {
            letterSpacing = 0.06f
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = StudyTheme.pillDrawable(
                this@StudyHistoryActivity, 8f,
                StudyTheme.alpha(pal.warning, 0.14f),
                StudyTheme.alpha(pal.warning, 0.35f)
            )
            visibility = View.GONE
        }
        val starIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_study_star)
            setColorFilter(pal.warning)
            contentDescription = "Saved"
            visibility = View.GONE
        }
        topRow.addView(subjectBadge)
        topRow.addView(interruptedBadge, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
        topRow.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f))
        topRow.addView(starIcon, LinearLayout.LayoutParams(dp(16), dp(16)))
        card.addView(topRow)

        val questionText = StudyTheme.label(this, "", 15f, pal.textPrimary, bold = true).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(StudyTheme.dpf(this@StudyHistoryActivity, 2f), 1f)
            setPadding(0, dp(8), 0, dp(6))
        }
        card.addView(questionText)

        val metaText = StudyTheme.label(this, "", 11.5f, pal.textMuted).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        card.addView(metaText)

        val wrap = LinearLayout(this).apply { setPadding(0, dp(5), 0, dp(5)) }
        wrap.addView(card, LinearLayout.LayoutParams(-1, -2))
        return HistoryRowHolder(wrap, subjectBadge, interruptedBadge, starIcon, questionText, metaText)
    }

    private fun bindRowHolder(h: HistoryRowHolder, r: StudySessionRow) {
        h.subjectBadge.text = r.subject.ifBlank { "General" }.uppercase()
        h.interruptedBadge.visibility = if (r.status != "COMPLETE") View.VISIBLE else View.GONE
        h.starIcon.visibility = if (r.saved) View.VISIBLE else View.GONE
        h.questionText.text = r.question.replace('\n', ' ')
        h.metaText.text = buildList {
            if (r.topic.isNotBlank()) add(r.topic)
            add(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(r.updatedAt)))
        }.joinToString(" · ")
    }

    companion object { const val EXTRA_ID = "study_session_id" }
}
