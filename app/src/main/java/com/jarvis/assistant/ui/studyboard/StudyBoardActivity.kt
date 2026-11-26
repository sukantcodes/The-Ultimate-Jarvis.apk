package com.jarvis.assistant.ui.studyboard

import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jarvis.assistant.R
import com.jarvis.assistant.study.StudyPlayback
import com.jarvis.assistant.study.voice.StudyVoiceInput
import com.jarvis.assistant.util.PermissionHelper
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Study Board: a full-screen AI whiteboard teacher. Not part of ChatBox. Everything that matters lives in
 * [StudyViewModel] (it survives rotation); this class only builds the screen and forwards what the student does.
 */
class StudyBoardActivity : AppCompatActivity(), StudyBoardView.Host {

    private val vm: StudyViewModel by viewModels()
    private lateinit var board: StudyBoardView
    private lateinit var voice: StudyVoiceInput

    private lateinit var subjectLine: TextView
    private lateinit var statusPill: TextView
    private lateinit var emptyHint: LinearLayout
    private lateinit var resumeButton: TextView
    private lateinit var banner: LinearLayout
    private lateinit var bannerText: TextView
    private lateinit var bannerRetry: TextView
    private lateinit var explainCard: LinearLayout
    private lateinit var explainText: TextView
    private lateinit var verifyText: TextView
    private lateinit var playButton: ImageButton
    private lateinit var speedChip: TextView
    private lateinit var voiceButton: ImageButton
    private lateinit var notesButton: ImageButton
    private lateinit var bookmarkButton: ImageButton
    private lateinit var input: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var micButton: ImageButton
    private lateinit var chipNew: TextView
    private lateinit var chipDoubt: TextView

    private var accent = 0xFF19E3FF.toInt()
    private var listening = false
    private var pendingExportKind = ""
    private var cameraFile: File? = null

    // ----- result launchers -----
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startListening() else toast("Microphone permission is needed for voice questions.")
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) launchCamera() else toast("Camera permission is needed to photograph a question.")
    }
    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val f = cameraFile
        if (saved && f != null && f.exists()) vm.onImage(Uri.fromFile(f), "photo") else if (!saved) toast("No photo was taken.")
    }
    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) vm.onImage(uri, "image") }
    private val pickPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.onPdf(uri) }
    private val historyLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringExtra(StudyHistoryActivity.EXTRA_ID)?.let { vm.openSession(it) }
    }
    private val createPdf = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri -> if (uri != null) export(uri, true) }
    private val createPng = registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri -> if (uri != null) export(uri, false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        accent = themeAccent()
        voice = StudyVoiceInput(this)
        setContentView(buildUi())
        board.bind(vm.playback, vm.blocks.value, vm.eraseLimit.value)
        observe()
        refresh()
    }

    // ======================================================================================================
    // UI construction
    // ======================================================================================================

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun themeAccent(): Int {
        val tv = TypedValue()
        return if (theme.resolveAttribute(R.attr.themeAccent, tv, true) && tv.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) tv.data else 0xFF19E3FF.toInt()
    }

    private fun glass(radiusDp: Int, fill: Int = PANEL, stroke: Int = STROKE): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat(); setColor(fill); setStroke(dp(1), stroke)
    }

    private fun icon(res: Int, desc: String, sizeDp: Int = 44, onClick: () -> Unit): ImageButton = ImageButton(this).apply {
        setImageResource(res)
        setColorFilter(0xFFE8EDF5.toInt())
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x00000000) }
        contentDescription = desc
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        val p = dp(10); setPadding(p, p, p, p)
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        setOnClickListener { onClick() }
        isFocusable = true
    }

    private fun label(text: String, sp: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        this.text = text; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color)
        if (bold) typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
    }

    private fun chip(text: String, onClick: () -> Unit): TextView = label(text, 12f, 0xFFE8EDF5.toInt(), true).apply {
        setPadding(dp(12), dp(7), dp(12), dp(7)); background = glass(16); gravity = Gravity.CENTER
        setOnClickListener { onClick() }; isClickable = true; isFocusable = true
    }

    private fun buildUi(): View {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }

        // header
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(4), dp(6), dp(4)) }
        header.addView(icon(R.drawable.ic_arrow_back, "Back") { finish() })
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); setPadding(dp(6), 0, dp(6), 0) }
        titles.addView(label("STUDY BOARD", 13f, accent, true).apply { letterSpacing = 0.14f })
        subjectLine = label("Ask a question", 12f, 0xFF9AA6B8.toInt()).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        titles.addView(subjectLine)
        header.addView(titles)
        header.addView(icon(R.drawable.ic_study_history, "Saved lessons") { historyLauncher.launch(Intent(this, StudyHistoryActivity::class.java)) })
        header.addView(icon(R.drawable.ic_study_tune, "Teaching settings") { StudyDialogs.settings(this, vm.prefs) { vm.setVoice(vm.prefs.voiceOn) } })
        root.addView(header)

        // board area with overlays
        val boardFrame = FrameLayout(this)
        board = StudyBoardView(this).apply { host = this@StudyBoardActivity }
        boardFrame.addView(board, FrameLayout.LayoutParams(-1, -1))
        statusPill = label("", 11f, 0xFF0B1220.toInt(), true).apply { setPadding(dp(10), dp(4), dp(10), dp(4)); background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(accent) }; visibility = View.GONE }
        boardFrame.addView(statusPill, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(12), dp(12), 0, 0) })
        val zoom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = glass(18, 0xCC10141B.toInt()) }
        zoom.addView(icon(R.drawable.ic_study_zoom_in, "Zoom in", 38) { board.zoomBy(1.4f) })
        zoom.addView(icon(R.drawable.ic_study_zoom_out, "Zoom out", 38) { board.zoomBy(1f / 1.4f) })
        boardFrame.addView(zoom, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(12), dp(12)) })
        emptyHint = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(28), 0, dp(28), 0) }
        emptyHint.addView(label("Ask a question by voice, text, photo or PDF.", 15f, 0xFF3A4352.toInt()).apply { gravity = Gravity.CENTER })
        resumeButton = chip("") { vm.interrupted.value?.let { vm.openSession(it.first); vm.dismissInterrupted() } }.apply { visibility = View.GONE; setTextColor(0xFFE8EDF5.toInt()) }
        emptyHint.addView(resumeButton, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
        boardFrame.addView(emptyHint, FrameLayout.LayoutParams(-1, -1))
        val boardHolder = FrameLayout(this).apply { setPadding(dp(10), dp(4), dp(10), dp(4)); addView(boardFrame, FrameLayout.LayoutParams(-1, -1)) }

        // banner, explanation, controls, input
        banner = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = glass(14, 0xFF2A1416.toInt(), 0xFF6B2A2E.toInt()); setPadding(dp(12), dp(8), dp(8), dp(8)); visibility = View.GONE }
        bannerText = label("", 12.5f, 0xFFFFD9DB.toInt()).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        bannerRetry = chip("Retry") { vm.retry() }
        banner.addView(bannerText); banner.addView(bannerRetry)
        banner.addView(icon(R.drawable.ic_close, "Dismiss", 34) { vm.dismissMessage() })

        explainCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = glass(16); setPadding(dp(14), dp(10), dp(14), dp(10)) }
        explainText = label("", 14f, 0xFFE8EDF5.toInt()).apply { maxLines = if (landscape) 6 else 4; ellipsize = android.text.TextUtils.TruncateAt.END }
        verifyText = label("", 11f, 0xFF7E8BA0.toInt())
        explainCard.addView(explainText); explainCard.addView(verifyText)

        val controls = buildControls()
        val inputBar = buildInput()

        val side = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        side.addView(banner, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(10), dp(4), dp(10), dp(2)) })
        side.addView(explainCard, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(10), dp(4), dp(10), dp(2)) })
        side.addView(controls, LinearLayout.LayoutParams(-1, -2))
        side.addView(inputBar, LinearLayout.LayoutParams(-1, -2))

        if (landscape) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(boardHolder, LinearLayout.LayoutParams(0, -1, 1.7f))
            val scroll = android.widget.ScrollView(this).apply { isFillViewport = true; addView(side) }
            row.addView(scroll, LinearLayout.LayoutParams(0, -1, 1f))
            root.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            root.addView(boardHolder, LinearLayout.LayoutParams(-1, 0, 1f))
            root.addView(side, LinearLayout.LayoutParams(-1, -2))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        return root
    }

    private fun buildControls(): View {
        val scroller = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; setPadding(dp(8), dp(2), dp(8), dp(2)) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun sep() = View(this).apply { setBackgroundColor(STROKE); layoutParams = LinearLayout.LayoutParams(dp(1), dp(24)).apply { setMargins(dp(6), 0, dp(6), 0) } }
        row.addView(icon(R.drawable.ic_study_prev, "Previous step") { vm.previous(); board.followPen = true })
        playButton = icon(R.drawable.ic_study_pause, "Pause or resume writing", 50) { vm.togglePlay(); board.followPen = true }
        playButton.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accent) }
        playButton.setColorFilter(0xFF0B1220.toInt())
        row.addView(playButton)
        row.addView(icon(R.drawable.ic_study_next, "Next step") { vm.next(); board.followPen = true })
        row.addView(icon(R.drawable.ic_study_replay, "Replay this step") { vm.replayStep(); board.followPen = true })
        row.addView(icon(R.drawable.ic_study_replay_all, "Replay the whole solution") { vm.replayAll(); board.followPen = true })
        speedChip = chip("1x") { cycleSpeed() }
        row.addView(speedChip)
        row.addView(sep())
        voiceButton = icon(R.drawable.ic_study_volume_off, "Narration on or off") { vm.setVoice(!vm.prefs.voiceOn) }
        row.addView(voiceButton)
        notesButton = icon(R.drawable.ic_study_notes, "Show or hide explanations") { vm.setNotes(!vm.prefs.notesOn) }
        row.addView(notesButton)
        row.addView(icon(R.drawable.ic_study_undo, "Undo") { vm.undo() })
        row.addView(icon(R.drawable.ic_study_redo, "Redo") { vm.redo() })
        row.addView(icon(R.drawable.ic_study_clear, "Clear board") { confirmClear() })
        row.addView(sep())
        bookmarkButton = icon(R.drawable.ic_study_bookmark, "Bookmark the selected step") { vm.toggleBookmark() }
        row.addView(bookmarkButton)
        row.addView(icon(R.drawable.ic_study_star, "Save this lesson") { vm.saveSession() })
        lateinit var exportButton: ImageButton
        exportButton = icon(R.drawable.ic_study_export, "Export") { showExportMenu(exportButton) }
        row.addView(exportButton)
        scroller.addView(row)
        return scroller
    }

    private fun buildInput(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(2), dp(10), dp(8)) }
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chipNew = chip("New question") { vm.newQuestionMode() }
        chipDoubt = chip("Doubt") { if (vm.lesson?.steps?.isNotEmpty() == true) vm.askMode.value = StudyViewModel.AskMode.DOUBT else toast("Solve a question first, then ask doubts about it.") }
        chips.addView(chipNew); chips.addView(chipDoubt, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        col.addView(chips, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(6) })

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = glass(26); setPadding(dp(4), dp(2), dp(4), dp(2)) }
        bar.addView(icon(R.drawable.ic_study_camera, "Photograph a question", 40) { requestCamera() })
        bar.addView(icon(R.drawable.ic_study_image, "Choose an image", 40) { pickImage.launch("image/*") })
        bar.addView(icon(R.drawable.ic_study_pdf, "Import a PDF", 40) { pickPdf.launch(arrayOf("application/pdf")) })
        input = EditText(this).apply {
            hint = "Type a question or equation"; setHintTextColor(0xFF6E7A8E.toInt()); setTextColor(0xFFF2F5FA.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = null; maxLines = 4; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        bar.addView(input)
        micButton = icon(R.drawable.ic_mic_jarvis, "Ask by voice", 40) { toggleMic() }
        bar.addView(micButton)
        sendButton = icon(R.drawable.ic_send_up, "Send", 40) { sendOrStop() }
        bar.addView(sendButton)
        col.addView(bar, LinearLayout.LayoutParams(-1, -2))
        return col
    }

    // ======================================================================================================
    // State -> screen
    // ======================================================================================================

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.blocks.collect { board.setBlocks(it); refresh() } }
                launch { vm.version.collect { board.kick(); refresh() } }
                launch { vm.ui.collect { refresh() } }
                launch { vm.eraseLimit.collect { board.setEraseLimit(it) } }
                launch { vm.selectedBlock.collect { board.selectedBlock = it; refresh() } }
                launch { vm.bookmarks.collect { board.bookmarkedBlocks = it } }
                launch { vm.askMode.collect { refresh() } }
                launch { vm.interrupted.collect { refresh() } }
                launch { vm.events.collect { handle(it) } }
            }
        }
    }

    private fun refresh() {
        if (!::board.isInitialized) return
        val ui = vm.ui.value
        val pb = vm.playback
        board.showNotes = vm.prefs.notesOn
        subjectLine.text = ui.subjectLine.ifEmpty { vm.lesson?.question?.take(60) ?: "Ask a question" }
        emptyHint.visibility = if (!vm.hasContent && ui.phase != StudyViewModel.Phase.EXTRACTING) View.VISIBLE else View.GONE
        val pending = vm.interrupted.value
        resumeButton.visibility = if (pending != null && !vm.hasContent) View.VISIBLE else View.GONE
        if (pending != null) resumeButton.text = "Resume: " + pending.second.take(40)

        val status = when {
            ui.phase == StudyViewModel.Phase.EXTRACTING -> "Reading…"
            pb.state == StudyPlayback.State.PLAYING -> "Writing"
            pb.state == StudyPlayback.State.PAUSED -> "Paused"
            pb.state == StudyPlayback.State.WAITING_FOR_CONTENT && ui.phase == StudyViewModel.Phase.GENERATING -> "Thinking…"
            else -> ""
        }
        statusPill.visibility = if (status.isEmpty()) View.GONE else View.VISIBLE
        statusPill.text = status

        playButton.setImageResource(if (pb.state == StudyPlayback.State.PLAYING) R.drawable.ic_study_pause else R.drawable.ic_study_play)
        speedChip.text = speedLabel(pb.speed)
        voiceButton.setImageResource(if (vm.prefs.voiceOn) R.drawable.ic_study_volume_on else R.drawable.ic_study_volume_off)
        voiceButton.alpha = if (vm.prefs.voiceOn) 1f else 0.6f
        notesButton.alpha = if (vm.prefs.notesOn) 1f else 0.5f
        bookmarkButton.alpha = if (vm.selectedStepNumber() != null) 1f else 0.5f

        val showBanner = ui.message.isNotEmpty() && ui.phase != StudyViewModel.Phase.EXTRACTING
        banner.visibility = if (showBanner) View.VISIBLE else View.GONE
        bannerText.text = ui.message
        bannerRetry.visibility = if (ui.phase == StudyViewModel.Phase.ERROR && ui.retryable) View.VISIBLE else View.GONE

        val ex = vm.currentExplanation
        explainCard.visibility = if (vm.prefs.notesOn && (ex.isNotEmpty() || ui.verification.isNotEmpty()) && vm.hasContent) View.VISIBLE else View.GONE
        explainText.text = ex
        explainText.visibility = if (ex.isEmpty()) View.GONE else View.VISIBLE
        verifyText.text = if (pb.state == StudyPlayback.State.FINISHED || ui.phase == StudyViewModel.Phase.READY) ui.verification else ""

        val doubtMode = vm.askMode.value == StudyViewModel.AskMode.DOUBT
        val step = vm.selectedStepNumber()
        chipNew.background = glass(16, if (!doubtMode) accent else PANEL)
        chipNew.setTextColor(if (!doubtMode) 0xFF0B1220.toInt() else 0xFFE8EDF5.toInt())
        chipDoubt.text = if (doubtMode && step != null) "Doubt · step $step" else "Doubt"
        chipDoubt.background = glass(16, if (doubtMode) accent else PANEL)
        chipDoubt.setTextColor(if (doubtMode) 0xFF0B1220.toInt() else 0xFFE8EDF5.toInt())
        chipDoubt.alpha = if (vm.lesson?.steps?.isNotEmpty() == true) 1f else 0.5f
        input.hint = if (doubtMode) (if (step != null) "Ask about step $step" else "Ask about the solution") else "Type a question or equation"

        val busy = vm.isBusy
        sendButton.setImageResource(if (busy) R.drawable.ic_study_stop else R.drawable.ic_send_up)
        sendButton.contentDescription = if (busy) "Stop" else "Send"
        micButton.setColorFilter(if (listening) accent else 0xFFE8EDF5.toInt())
    }

    private fun speedLabel(s: Float) = (if (s == s.toInt().toFloat()) s.toInt().toString() else s.toString()) + "x"

    private fun cycleSpeed() {
        val steps = floatArrayOf(0.5f, 0.75f, 1f, 1.5f, 2f)
        val cur = vm.playback.speed
        val next = steps.firstOrNull { it > cur + 0.01f } ?: steps.first()
        vm.setSpeed(next)
    }

    private fun handle(e: StudyViewModel.Event) {
        when (e) {
            is StudyViewModel.Event.Toast -> toast(e.text)
            is StudyViewModel.Event.ConfirmQuestions -> StudyDialogs.confirmQuestions(this, e.questions, e.source) { text, keep -> vm.solveConfirmed(text, keep) }
            is StudyViewModel.Event.PdfReady -> StudyDialogs.pdfPicker(this, lifecycleScope, e.path, e.pages,
                onPage = { page -> vm.extractFromPdfPage(e.path, page) }, onWhole = { q -> vm.askAboutPdf(e.path, q) })
            is StudyViewModel.Event.Reveal -> board.reveal(e.block)
        }
    }

    // ======================================================================================================
    // Actions
    // ======================================================================================================

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun sendOrStop() {
        if (vm.isBusy) { vm.cancel(); return }
        val t = input.text.toString().trim()
        if (t.isEmpty()) { toast("Type or say a question first."); return }
        hideKeyboard()
        input.setText("")
        board.followPen = true
        vm.submit(t)
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun toggleMic() {
        if (listening) { voice.stopAndUse(); return }
        if (vm.isBusy) { toast("Stop the current answer before asking another question."); return }
        if (!PermissionHelper.hasRecordAudioPermission(this)) micPermission.launch(Manifest.permission.RECORD_AUDIO) else startListening()
    }

    private fun startListening() {
        vm.interruptForMic() // narration stops so the microphone does not hear it
        val tag = when (vm.prefs.voiceLanguage.lowercase()) {
            "hindi" -> "hi-IN"
            "english" -> "en-IN"
            else -> if (vm.prefs.language.equals("Hindi", true)) "hi-IN" else "en-IN"
        }
        listening = true
        input.hint = "Listening…"
        refresh()
        voice.start(tag, object : StudyVoiceInput.Listener {
            override fun onListening() {}
            override fun onPartial(text: String) { input.setText(text); input.setSelection(input.text.length) }
            override fun onFinal(text: String) {
                listening = false
                input.setText("")
                refresh()
                board.followPen = true
                vm.submit(text)
            }
            override fun onError(message: String) { listening = false; refresh(); toast(message) }
        })
    }

    private fun requestCamera() {
        if (PermissionHelper.hasCameraPermission(this)) launchCamera() else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun launchCamera() {
        try {
            val f = StudyMedia.newCaptureFile(this)
            cameraFile = f
            takePicture.launch(FileProvider.getUriForFile(this, "$packageName.studyfiles", f))
        } catch (e: Exception) {
            toast("The camera could not be opened: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun confirmClear() {
        if (!vm.hasContent) return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Clear the board?")
            .setMessage("The board is wiped. The lesson stays saved in your history, and Redo brings it back.")
            .setPositiveButton("Clear") { _, _ -> vm.clearBoard() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showExportMenu(anchor: View) {
        if (!vm.hasContent) { toast("Nothing to export yet."); return }
        val m = android.widget.PopupMenu(this, anchor)
        m.menu.add("Export notes as PDF"); m.menu.add("Export board as image")
        m.setOnMenuItemClickListener { item ->
            val base = (vm.lesson?.topic?.takeIf { it.isNotBlank() } ?: "study-notes").replace(Regex("[^A-Za-z0-9_-]+"), "-").trim('-').ifEmpty { "study-notes" }.take(40)
            if (item.title.toString().contains("PDF")) createPdf.launch("$base.pdf") else createPng.launch("$base.png")
            true
        }
        m.show()
    }

    private fun export(uri: Uri, pdf: Boolean) {
        val blocks = vm.blocks.value
        val lesson = vm.lesson
        val notes = vm.prefs.notesOn
        val marks = vm.bookmarks.value
        toast("Exporting…")
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        if (pdf) StudyExport.writePdf(this@StudyBoardActivity, lesson?.topic?.ifBlank { null } ?: "Study notes",
                            listOfNotNull(lesson?.subject?.label, lesson?.level?.ifBlank { null }).joinToString(" · "), blocks, true, marks, out)
                        else StudyExport.writeImage(this@StudyBoardActivity, blocks, notes, marks, out)
                    } ?: throw StudyExport.ExportException("Could not open the destination file.")
                }
                toast(if (pdf) "PDF saved." else "Image saved.")
            } catch (e: StudyExport.ExportException) { toast(e.message ?: "Export failed.") }
            catch (e: Exception) { toast("Export failed: ${e.message ?: e.javaClass.simpleName}") }
        }
    }

    // ----- board host -----
    override fun onBlockTapped(index: Int) { vm.selectBlock(index) }
    override fun onUserPanned() {}

    // ----- lifecycle -----
    override fun onStart() {
        super.onStart()
        if (vm.resumeAfterBackground) { vm.resumeAfterBackground = false; vm.playback.resume() }
        board.kick()
    }

    override fun onStop() {
        if (listening) { voice.cancel(); listening = false }
        if (vm.playback.state == StudyPlayback.State.PLAYING) { vm.resumeAfterBackground = true; vm.playback.pause() }
        vm.stopSpeech()
        super.onStop()
    }

    override fun onDestroy() {
        voice.cancel()
        super.onDestroy()
    }

    companion object {
        private const val BG = 0xFF06080C.toInt()
        private const val PANEL = 0xFF10141B.toInt()
        private const val STROKE = 0xFF263040.toInt()
    }
}
