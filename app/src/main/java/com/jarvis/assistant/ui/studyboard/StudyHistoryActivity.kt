package com.jarvis.assistant.ui.studyboard

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.jarvis.assistant.study.data.StudyHistoryManager
import com.jarvis.assistant.study.data.StudySessionRow
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Saved and interrupted Study Board lessons: search, filter by subject or saved, reopen, delete. */
class StudyHistoryActivity : AppCompatActivity() {

    private lateinit var history: StudyHistoryManager
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var chipRow: LinearLayout
    private var rows: List<StudySessionRow> = emptyList()
    private var query = ""
    private var subject = ""
    private var savedOnly = false
    private var searchJob: Job? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF06080C.toInt(); window.navigationBarColor = 0xFF06080C.toInt()
        history = StudyHistoryManager(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF06080C.toInt()); setPadding(dp(14), dp(10), dp(14), dp(10)) }
        root.addView(TextView(this).apply { text = "Study history"; textSize = 20f; setTextColor(0xFFF2F5FA.toInt()); setPadding(0, dp(4), 0, dp(10)) })
        val search = EditText(this).apply {
            hint = "Search saved questions"; setHintTextColor(0xFF6E7A8E.toInt()); setTextColor(0xFFF2F5FA.toInt()); maxLines = 1; inputType = android.text.InputType.TYPE_CLASS_TEXT
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(0xFF10141B.toInt()); setStroke(dp(1), 0xFF263040.toInt()) }
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                searchJob?.cancel()
                searchJob = lifecycleScope.launch { delay(250); reload() }
            }
        })
        root.addView(search)
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, dp(8)) }
        scroll.addView(chipRow)
        root.addView(scroll)
        list = ListView(this).apply { divider = null; selector = android.graphics.drawable.ColorDrawable(0); layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) }
        empty = TextView(this).apply { text = "No lessons yet. Ask a question on the Study Board and it will appear here."; setTextColor(0xFF7E8BA0.toInt()); gravity = Gravity.CENTER; setPadding(dp(24), dp(40), dp(24), 0) }
        root.addView(list); root.addView(empty)
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left + dp(14), b.top + dp(10), b.right + dp(14), b.bottom + dp(10)); insets
        }
        list.setOnItemClickListener { _, _, pos, _ ->
            setResult(RESULT_OK, Intent().putExtra(EXTRA_ID, rows[pos].id)); finish()
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            val r = rows[pos]
            AlertDialog.Builder(this).setTitle("Delete this lesson?").setMessage(r.question.take(120))
                .setPositiveButton("Delete") { _, _ -> lifecycleScope.launch { history.delete(r.id); reload() } }
                .setNegativeButton("Cancel", null).show()
            true
        }
        lifecycleScope.launch { reload() }
    }

    private fun chip(text: String, on: Boolean, click: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = 12f; setPadding(dp(12), dp(7), dp(12), dp(7))
        setTextColor(if (on) 0xFF0B1220.toInt() else 0xFFE8EDF5.toInt())
        background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(if (on) 0xFF19E3FF.toInt() else 0xFF10141B.toInt()); setStroke(dp(1), 0xFF263040.toInt()) }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) }
        setOnClickListener { click() }
    }

    private suspend fun reload() {
        rows = history.search(query, subject, savedOnly)
        val subjects = history.subjects()
        chipRow.removeAllViews()
        chipRow.addView(chip("All", subject.isEmpty() && !savedOnly) { subject = ""; savedOnly = false; lifecycleScope.launch { reload() } })
        chipRow.addView(chip("Saved", savedOnly) { savedOnly = !savedOnly; lifecycleScope.launch { reload() } })
        for (s in subjects) chipRow.addView(chip(s, subject == s) { subject = if (subject == s) "" else s; lifecycleScope.launch { reload() } })
        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        empty.text = if (query.isNotEmpty() || subject.isNotEmpty() || savedOnly) "No lessons match." else "No lessons yet. Ask a question on the Study Board and it will appear here."
        list.adapter = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(i: Int) = rows[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, convert: View?, parent: ViewGroup): View {
                val r = rows[i]
                val card = LinearLayout(this@StudyHistoryActivity).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12))
                    background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(0xFF10141B.toInt()); setStroke(dp(1), 0xFF263040.toInt()) }
                    layoutParams = AbsListViewParams()
                }
                card.addView(TextView(this@StudyHistoryActivity).apply { text = r.question.replace('\n', ' '); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(0xFFF2F5FA.toInt()); textSize = 14f })
                val status = if (r.status == "COMPLETE") "" else "  ·  interrupted"
                card.addView(TextView(this@StudyHistoryActivity).apply {
                    text = (if (r.saved) "★ " else "") + listOf(r.subject, r.topic).filter { it.isNotBlank() }.joinToString(" · ") + status + "  ·  " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(r.updatedAt))
                    setTextColor(0xFF7E8BA0.toInt()); textSize = 11f; setPadding(0, dp(4), 0, 0)
                })
                val wrap = LinearLayout(this@StudyHistoryActivity).apply { setPadding(0, dp(4), 0, dp(4)) }
                wrap.addView(card, LinearLayout.LayoutParams(-1, -2))
                return wrap
            }
        }
    }

    @Suppress("FunctionName")
    private fun AbsListViewParams() = LinearLayout.LayoutParams(-1, -2)

    companion object { const val EXTRA_ID = "study_session_id" }
}
