package com.jarvis.assistant.ui.studyboard

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.jarvis.assistant.study.ExtractedQuestion
import com.jarvis.assistant.study.StudyLevels
import com.jarvis.assistant.study.StudyProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/** The pop-ups of the Study Board: teaching settings, checking a question read from an image, and choosing a PDF page. */
object StudyDialogs {

    private fun dp(a: Activity, v: Int) = (v * a.resources.displayMetrics.density + 0.5f).toInt()

    private fun column(a: Activity): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(a, 20), dp(a, 12), dp(a, 20), dp(a, 4))
    }

    private fun caption(a: Activity, text: String) = TextView(a).apply { this.text = text; textSize = 12f; setPadding(0, dp(a, 10), 0, dp(a, 2)); alpha = 0.7f }

    private fun spinner(a: Activity, items: List<String>, selected: String): Spinner = Spinner(a).apply {
        adapter = ArrayAdapter(a, android.R.layout.simple_spinner_dropdown_item, items)
        setSelection(items.indexOfFirst { it.equals(selected, true) }.coerceAtLeast(0))
    }

    /** Level, explanation language, depth and narration language. Saved as soon as the student confirms. */
    fun settings(a: Activity, prefs: StudyPrefs, onSaved: () -> Unit) {
        val col = column(a)
        col.addView(caption(a, "Your level"))
        val level = spinner(a, StudyLevels.CHOICES, prefs.level); col.addView(level)
        col.addView(caption(a, "Explanation language"))
        val lang = spinner(a, StudyLevels.LANGUAGES, prefs.language); col.addView(lang)
        col.addView(caption(a, "Explanation depth"))
        val depthNames = StudyProfile.Depth.values().map { it.label }
        val depth = spinner(a, depthNames, prefs.depth.label); col.addView(depth)
        col.addView(caption(a, "Narration and microphone language"))
        val voice = spinner(a, listOf("Auto", "English", "Hindi"), prefs.voiceLanguage); col.addView(voice)
        col.addView(TextView(a).apply {
            text = "Auto level uses the class named in your question, then your age from your profile. Hinglish is narrated with an English (India) voice."
            textSize = 11f; alpha = 0.6f; setPadding(0, dp(a, 10), 0, 0)
        })
        AlertDialog.Builder(a).setTitle("Teaching settings").setView(col)
            .setPositiveButton("Save") { _, _ ->
                prefs.level = level.selectedItem.toString()
                prefs.language = lang.selectedItem.toString()
                prefs.depth = StudyProfile.Depth.values()[depth.selectedItemPosition]
                prefs.voiceLanguage = voice.selectedItem.toString()
                onSaved()
            }
            .setNegativeButton("Cancel", null).show()
    }

    /**
     * Shows what was read from the image and lets the student fix it. Nothing is solved until "Solve" is pressed, so a
     * misread question can never be solved silently.
     */
    fun confirmQuestions(a: Activity, questions: List<ExtractedQuestion>, source: String, onSolve: (String, Boolean) -> Unit) {
        val col = column(a)
        val edit = EditText(a).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3; maxLines = 8; gravity = Gravity.TOP or Gravity.START
            setText(questions.first().text)
        }
        val warn = TextView(a).apply { textSize = 12f; setTextColor(Color.rgb(198, 40, 40)); setPadding(0, dp(a, 6), 0, 0) }
        val figure = CheckBox(a).apply { text = "Send the picture too (the question has a figure)"; textSize = 12f }
        var index = 0
        fun show(i: Int) {
            index = i
            val q = questions[i]
            edit.setText(q.text)
            warn.text = when {
                q.text.contains("[unclear]") -> "Part of this was hard to read. Please check it and fix the marked part."
                q.confidence != "high" -> "I am not fully sure I read this correctly. Please check it."
                else -> ""
            }
            figure.visibility = if (q.hasDiagram) android.view.View.VISIBLE else android.view.View.GONE
            figure.isChecked = q.hasDiagram
        }
        col.addView(TextView(a).apply { text = "Read from $source. Check it before solving."; textSize = 12f; alpha = 0.7f })
        if (questions.size > 1) {
            val names = questions.mapIndexed { i, q -> "Q${i + 1}: " + q.text.replace('\n', ' ').take(48) }
            val pick = Spinner(a).apply {
                adapter = ArrayAdapter(a, android.R.layout.simple_spinner_dropdown_item, names)
                onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) { if (pos != index) show(pos) }
                    override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
                }
            }
            col.addView(pick)
        }
        col.addView(edit); col.addView(warn); col.addView(figure)
        show(0)
        AlertDialog.Builder(a).setTitle("Is this your question?").setView(col)
            .setPositiveButton("Solve") { _, _ ->
                val t = edit.text.toString().trim()
                if (t.isEmpty() || t.contains("[unclear]")) {
                    android.widget.Toast.makeText(a, "Please fix the unclear part before solving.", android.widget.Toast.LENGTH_LONG).show()
                    confirmQuestions(a, questions.mapIndexed { i, q -> if (i == index) q.copy(text = t.ifEmpty { q.text }) else q }, source, onSolve)
                } else onSolve(t, figure.isChecked && figure.visibility == android.view.View.VISIBLE)
            }
            .setNegativeButton("Cancel", null).show()
    }

    /** Browse the PDF page by page, then read the questions on a page or ask about the whole document. */
    fun pdfPicker(a: Activity, scope: CoroutineScope, path: String, pages: Int, onPage: (Int) -> Unit, onWhole: (String) -> Unit) {
        if (pages <= 0) { android.widget.Toast.makeText(a, "This PDF has no pages.", android.widget.Toast.LENGTH_SHORT).show(); return }
        val col = column(a)
        val title = TextView(a).apply { textSize = 13f }
        val preview = ImageView(a).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER; background = GradientDrawable().apply { setColor(Color.WHITE); setStroke(2, Color.GRAY) } }
        val nav = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val prev = TextView(a).apply { text = "‹  Previous"; setPadding(dp(a, 16), dp(a, 10), dp(a, 16), dp(a, 10)); isClickable = true }
        val next = TextView(a).apply { text = "Next  ›"; setPadding(dp(a, 16), dp(a, 10), dp(a, 16), dp(a, 10)); isClickable = true }
        nav.addView(prev); nav.addView(next)
        col.addView(title)
        col.addView(preview, LinearLayout.LayoutParams(-1, dp(a, 280)))
        col.addView(nav)
        var page = 0
        var job: Job? = null
        fun load() {
            title.text = "Page ${page + 1} of $pages"
            job?.cancel()
            preview.setImageDrawable(null)
            job = scope.launch {
                try { preview.setImageBitmap(StudyMedia.renderPage(File(path), page, 700)) }
                catch (e: StudyMedia.MediaException) { title.text = e.message ?: "Could not show this page." }
            }
        }
        prev.setOnClickListener { if (page > 0) { page--; load() } }
        next.setOnClickListener { if (page < pages - 1) { page++; load() } }
        load()
        val dialog = AlertDialog.Builder(a).setTitle("Choose from the PDF").setView(col)
            .setPositiveButton("Read this page's questions") { _, _ -> onPage(page) }
            .setNeutralButton("Ask about the whole PDF") { _, _ -> askWhole(a, onWhole) }
            .setNegativeButton("Cancel", null).create()
        dialog.setOnDismissListener { job?.cancel(); (preview.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap?.let { preview.setImageDrawable(null); if (!it.isRecycled) it.recycle() } }
        dialog.show()
    }

    private fun askWhole(a: Activity, onWhole: (String) -> Unit) {
        val edit = EditText(a).apply { hint = "What do you want to know about this document?"; minLines = 2; maxLines = 6; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE }
        val col = column(a); col.addView(edit)
        AlertDialog.Builder(a).setTitle("Ask about the whole PDF").setView(col)
            .setPositiveButton("Ask") { _, _ -> val t = edit.text.toString().trim(); if (t.isNotEmpty()) onWhole(t) }
            .setNegativeButton("Cancel", null).show()
    }
}
