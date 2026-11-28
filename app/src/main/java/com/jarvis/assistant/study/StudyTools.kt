package com.jarvis.assistant.study

import android.content.Context
import android.content.Intent
import com.jarvis.assistant.ui.studyboard.StudyBoardActivity
import com.jarvis.assistant.util.ActivityLauncherHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Complete voice & AI tool surface for the JARVIS Study Board.
 *
 * - [EXPLAIN]: Opens the Study Board (or brings it to the front), visibly types the exact question into
 *   the Study Board input box, and runs the step-by-step visual + live voice explanation.
 * - [CONTROL]: Controls every Study Board function (playback, speed, language, level, depth, voice, notes,
 *   pen, memory, undo/redo/clear, zoom/fit/fullscreen, step navigation/bookmarking, save/export, pickers,
 *   history, close, and state inspection).
 */
object StudyTools {
    const val EXPLAIN = "study_board_explain"
    const val CONTROL = "study_board_control"
    val NAMES: Set<String> = setOf(EXPLAIN, CONTROL)
    const val TOOL_TIMEOUT_MS = 20_000L

    private val CONTROL_ACTIONS = listOf(
        "open", "close", "get_state",
        "play", "pause", "toggle_play", "next", "previous", "replay_step", "replay_all", "stop_generation", "retry", "resume_lesson",
        "set_speed", "speed_up", "slow_down",
        "voice_on", "voice_off", "toggle_voice", "notes_on", "notes_off", "toggle_notes",
        "pen_on", "pen_off", "status_on", "status_off", "memory_on", "memory_off", "clear_memory",
        "set_language", "set_voice_language", "set_level", "set_depth", "set_provider", "switch_mode", "new_question_mode",
        "undo", "redo", "clear_board", "fullscreen_on", "fullscreen_off", "follow_pen",
        "zoom_in", "zoom_out", "zoom_reset", "fit_board", "scroll_top", "scroll_bottom",
        "select_step", "bookmark_step", "save_lesson", "copy_board", "copy_step",
        "explain_with_diagram", "explain_why", "explain_simpler", "memory_trick",
        "start_quiz", "submit_quiz_answer", "next_quiz_question", "quiz_hint", "dismiss_quiz",
        "open_history", "open_camera", "open_image_picker", "open_pdf_picker", "open_settings",
        "export_menu", "export_pdf", "export_png"
    )

    fun declarations(): List<JSONObject> = listOf(
        JSONObject().put("name", EXPLAIN).put(
            "description",
            "Opens the JARVIS Study Board (the whiteboard teacher), types the user's question/doubt into the Study Board, and solves/explains it step by step with printed clarity, diagrams, and live voice. " +
                "Pass mode='doubt' if asking anything about the explanation currently on the board (even mid-explanation or after explanation), mode='new' for a clearly new standalone question/topic, or 'auto' to route automatically. " +
                "Always pass the COMPLETE, exact user utterance/question in 'question'. After calling this tool, give only an ultra-short 2-5 word confirmation and stay quiet so Study Board speaks clearly."
        ).put(
            "parameters",
            JSONObject().put("type", "OBJECT").put(
                "properties",
                JSONObject().apply {
                    put("question", JSONObject().put("type", "STRING").put("description", "The full question, equation, diagram prompt, follow-up doubt, or quiz answer to send to the Study Board."))
                    put("mode", JSONObject().put("type", "STRING").put("enum", JSONArray().put("auto").put("doubt").put("new")).put("description", "Use 'doubt' for any follow-up doubt about the current explanation (mid-lesson or post-lesson), 'new' for a fresh standalone problem, or 'auto' (default) to classify automatically."))
                    put("step_number", JSONObject().put("type", "INTEGER").put("description", "Optional 1-based step number on the board that the doubt refers to."))
                    put("language", JSONObject().put("type", "STRING").put("description", "Optional language name such as English, Hindi, Hinglish, Telugu, Tamil, Bengali, Marathi, Gujarati, Kannada, Malayalam, Punjabi, Urdu, Odia, Assamese, Nepali, Spanish, French, German, Arabic, Japanese, Korean, Chinese, or Auto."))
                    put("level", JSONObject().put("type", "STRING").put("description", "Optional student level such as Auto, Class 6..Class 12, or Undergraduate."))
                    put("depth", JSONObject().put("type", "STRING").put("enum", JSONArray().put("Auto").put("Simple").put("Detailed").put("Advanced")).put("description", "Optional explanation depth."))
                }
            ).put("required", JSONArray().put("question"))
        ),

        JSONObject().put("name", CONTROL).put(
            "description",
            "Controls EVERY Study Board action and reads complete live knowledge: get_state (returns everything written on the board, recent events, active quiz, and settings), playback, speed, voice/notes/pen/status/memory toggles, language/level/depth/mode switching, undo/redo/clear/resume, zoom/fit/scroll/fullscreen/follow_pen, step selection/bookmarking/copying, quick doubt helpers (explain_with_diagram/why/simpler/memory_trick), post-lesson quiz controls (start_quiz/submit_quiz_answer/next_quiz_question/quiz_hint/dismiss_quiz), save/export PDF/PNG, and opening pickers/history/settings."
        ).put(
            "parameters",
            JSONObject().put("type", "OBJECT").put(
                "properties",
                JSONObject().apply {
                    put(
                        "action",
                        JSONObject().put("type", "STRING").put(
                            "enum",
                            JSONArray().apply { CONTROL_ACTIONS.forEach { put(it) } }
                        ).put("description", "Exact Study Board control action to perform.")
                    )
                    put(
                        "value",
                        JSONObject().put("type", "STRING").put("description", "Optional value for set_speed ('0.5'..'2.0'), set_language, set_voice_language, set_level, set_depth, set_provider ('gemini'|'custom'), switch_mode ('new'|'doubt'), or submit_quiz_answer (the user's answer).")
                    )
                    put(
                        "step_number",
                        JSONObject().put("type", "INTEGER").put("description", "Optional 1-based step number for select_step, bookmark_step, or quick doubt actions.")
                    )
                }
            ).put("required", JSONArray().put("action"))
        )
    )

    /** Executes Study Board AI tools or local voice commands safely from background or UI callers. */
    fun execute(context: Context, name: String, args: JSONObject): JSONObject = when (name) {
        EXPLAIN -> executeExplain(context, args)
        CONTROL -> executeControl(context, args)
        else -> fail("Unknown Study Board tool: $name")
    }

    private fun executeExplain(context: Context, args: JSONObject): JSONObject {
        val question = args.optString("question").trim()
        if (question.isEmpty()) return fail("No question was given. Ask the user what they want explained.")
        val rawMode = args.optString("mode", "auto").trim().lowercase(Locale.ROOT).ifEmpty { "auto" }
        val stepNumber = args.optInt("step_number", -1).takeIf { it > 0 }
        val language = args.optString("language").trim().takeIf { it.isNotEmpty() }
        val level = args.optString("level").trim().takeIf { it.isNotEmpty() }
        val depth = args.optString("depth").trim().takeIf { it.isNotEmpty() }

        val snap = StudyBoardController.stateSnapshot.value
        val hasLesson = snap.hasContent && snap.totalSteps > 0
        val routed = StudyIntentRouter.classify(
            query = question,
            hasLesson = hasLesson,
            activeQuestion = snap.question,
            activeTopic = snap.subject,
            explicitMode = if (stepNumber != null && rawMode == "auto") "doubt" else rawMode,
            quizActive = snap.quizActive
        )

        return try {
            val app = context.applicationContext
            if (routed == StudyIntentRouter.Intent.QUIZ_ANSWER && snap.quizActive && StudyBoardController.isBoardVisible.value) {
                StudyBoardController.dispatchCommand(
                    StudyBoardController.StudyBoardCommand.QuizCommand(StudyBoardController.QuizAction.SUBMIT_ANSWER, question)
                )
                return JSONObject()
                    .put("success", true)
                    .put("verified", true)
                    .put("routed_mode", "quiz_answer")
                    .put("yield_to_study_voice", true)
                    .put("message", "Submitted your answer \"$question\" to the Study Board practice check.")
            }

            val resolvedMode = if (routed == StudyIntentRouter.Intent.DOUBT) "doubt" else "new"
            if (StudyBoardController.isBoardVisible.value) {
                StudyBoardController.dispatchCommand(
                    StudyBoardController.StudyBoardCommand.OpenAndExplain(
                        question = question,
                        mode = resolvedMode,
                        language = language,
                        level = level,
                        depth = depth,
                        stepNumber = stepNumber,
                        animateTyping = true
                    )
                )
                ActivityLauncherHelper.startActivitySafely(
                    app,
                    Intent(app, StudyBoardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                )
                return JSONObject()
                    .put("success", true)
                    .put("verified", true)
                    .put("routed_mode", resolvedMode)
                    .put("yield_to_study_voice", true)
                    .put("message", "Submitted \"$question\" as a $resolvedMode on the active Study Board. Give only a brief confirmation and stay quiet while the board explains.")
            }

            val intent = Intent(app, StudyBoardActivity::class.java).apply {
                putExtra(StudyBoardActivity.EXTRA_QUESTION, question)
                putExtra(StudyBoardActivity.EXTRA_MODE, resolvedMode)
                language?.let { putExtra(StudyBoardActivity.EXTRA_LANGUAGE, it) }
                level?.let { putExtra(StudyBoardActivity.EXTRA_LEVEL, it) }
                depth?.let { putExtra(StudyBoardActivity.EXTRA_DEPTH, it) }
                stepNumber?.let { putExtra(StudyBoardActivity.EXTRA_STEP, it) }
            }
            val launched = ActivityLauncherHelper.startActivitySafely(app, intent)
            if (launched) {
                JSONObject()
                    .put("success", true)
                    .put("verified", true)
                    .put("routed_mode", resolvedMode)
                    .put("yield_to_study_voice", true)
                    .put("message", "Study Board is opening, typing, and explaining ($resolvedMode): \"${question.take(120)}\". Confirm briefly and let the Study Board voice teach.")
            } else {
                fail("Could not open the Study Board from the background. Ask the user to allow Display Over Other Apps or tap Study Board.")
            }
        } catch (e: Exception) {
            fail("Could not open the Study Board: ${e.message}")
        }
    }

    private fun executeControl(context: Context, args: JSONObject): JSONObject {
        val action = args.optString("action").trim().lowercase(Locale.ROOT)
        val value = args.optString("value").trim()
        val stepNumber = args.optInt("step_number", -1).takeIf { it > 0 }
        val app = context.applicationContext

        if (action.isEmpty()) return fail("No Study Board control action was provided.")

        if (action == "get_state") {
            val s = StudyBoardController.stateSnapshot.value
            return JSONObject()
                .put("success", true)
                .put("visible", s.visible)
                .put("phase", s.phase)
                .put("question", s.question)
                .put("subject", s.subject)
                .put("current_step", s.currentStepNumber)
                .put("selected_step", s.selectedStepNumber)
                .put("total_steps", s.totalSteps)
                .put("playback_state", s.playbackState)
                .put("ask_mode", s.askMode)
                .put("speed", s.speed.toDouble())
                .put("voice_on", s.voiceOn)
                .put("notes_on", s.notesOn)
                .put("pen_on", s.penOn)
                .put("status_on", s.statusOn)
                .put("memory_on", s.memoryOn)
                .put("language", s.language)
                .put("voice_language", s.voiceLanguage)
                .put("level", s.level)
                .put("depth", s.depth)
                .put("active_voice_name", s.activeVoiceName)
                .put("has_content", s.hasContent)
                .put("can_undo", s.canUndo)
                .put("can_redo", s.canRedo)
                .put("fullscreen", s.fullscreen)
                .put("follow_pen", s.followPen)
                .put("bookmarked_count", s.bookmarkedCount)
                .put("last_error", s.lastError)
                .put("board_content", s.boardDigest)
                .put("recent_events", JSONArray().apply { s.recentEvents.forEach { put(it) } })
                .put("quiz_active", s.quizActive)
                .put("quiz_question", s.quizQuestion)
                .put("quiz_options", JSONArray().apply { s.quizOptions.forEach { put(it) } })
                .put("quiz_progress", s.quizProgress)
                .put("quiz_feedback", s.quizFeedback)
        }

        if (action == "open") {
            val launched = ActivityLauncherHelper.startActivitySafely(app, Intent(app, StudyBoardActivity::class.java))
            return if (launched) {
                JSONObject().put("success", true).put("verified", true).put("message", "Study Board opened.")
            } else {
                fail("Could not open Study Board.")
            }
        }

        val cmd: StudyBoardController.StudyBoardCommand = when (action) {
            "close" -> StudyBoardController.StudyBoardCommand.CloseBoard
            "play" -> StudyBoardController.StudyBoardCommand.Playback(StudyBoardController.PlaybackAction.PLAY)
            "pause" -> StudyBoardController.StudyBoardCommand.Playback(StudyBoardController.PlaybackAction.PAUSE)
            "toggle_play" -> StudyBoardController.StudyBoardCommand.Playback(StudyBoardController.PlaybackAction.TOGGLE)
            "next" -> StudyBoardController.StudyBoardCommand.Playback(StudyBoardController.PlaybackAction.NEXT)
            "previous" -> StudyBoardController.StudyBoardCommand.Playback(StudyBoardController.PlaybackAction.PREVIOUS)
            "replay_step" -> StudyBoardController.StudyBoardCommand.Playback(StudyBoardController.PlaybackAction.REPLAY_STEP)
            "replay_all" -> StudyBoardController.StudyBoardCommand.Playback(StudyBoardController.PlaybackAction.REPLAY_ALL)
            "stop_generation" -> StudyBoardController.StudyBoardCommand.Playback(StudyBoardController.PlaybackAction.STOP)
            "retry" -> StudyBoardController.StudyBoardCommand.Playback(StudyBoardController.PlaybackAction.RETRY)
            "resume_lesson" -> StudyBoardController.StudyBoardCommand.ResumeInterrupted
            "set_speed" -> {
                val spd = value.replace("x", "", true).replace("×", "").trim().toFloatOrNull()
                    ?: return fail("Please pass a valid speed such as 0.75, 1.0, 1.5, or 2.0.")
                StudyBoardController.StudyBoardCommand.SetSpeed(speed = spd)
            }
            "speed_up" -> StudyBoardController.StudyBoardCommand.SetSpeed(delta = 0.25f)
            "slow_down" -> StudyBoardController.StudyBoardCommand.SetSpeed(delta = -0.25f)
            "voice_on" -> StudyBoardController.StudyBoardCommand.ToggleSetting(StudyBoardController.SettingToggleKey.VOICE, true)
            "voice_off" -> StudyBoardController.StudyBoardCommand.ToggleSetting(StudyBoardController.SettingToggleKey.VOICE, false)
            "toggle_voice" -> StudyBoardController.StudyBoardCommand.ToggleSetting(StudyBoardController.SettingToggleKey.VOICE, null)
            "notes_on" -> StudyBoardController.StudyBoardCommand.ToggleSetting(StudyBoardController.SettingToggleKey.NOTES, true)
            "notes_off" -> StudyBoardController.StudyBoardCommand.ToggleSetting(StudyBoardController.SettingToggleKey.NOTES, false)
            "toggle_notes" -> StudyBoardController.StudyBoardCommand.ToggleSetting(StudyBoardController.SettingToggleKey.NOTES, null)
            "pen_on" -> StudyBoardController.StudyBoardCommand.ToggleSetting(StudyBoardController.SettingToggleKey.PEN, true)
            "pen_off" -> StudyBoardController.StudyBoardCommand.ToggleSetting(StudyBoardController.SettingToggleKey.PEN, false)
            "status_on" -> StudyBoardController.StudyBoardCommand.ToggleSetting(StudyBoardController.SettingToggleKey.STATUS, true)
            "status_off" -> StudyBoardController.StudyBoardCommand.ToggleSetting(StudyBoardController.SettingToggleKey.STATUS, false)
            "memory_on" -> StudyBoardController.StudyBoardCommand.ToggleSetting(StudyBoardController.SettingToggleKey.MEMORY, true)
            "memory_off" -> StudyBoardController.StudyBoardCommand.ToggleSetting(StudyBoardController.SettingToggleKey.MEMORY, false)
            "clear_memory" -> StudyBoardController.StudyBoardCommand.CanvasAction(StudyBoardController.BoardCanvasAction.CLEAR_MEMORY)
            "set_language" -> {
                if (value.isBlank()) return fail("Specify a language name.")
                StudyBoardController.StudyBoardCommand.SetOption(StudyBoardController.SettingOptionKey.LANGUAGE, value)
            }
            "set_voice_language" -> {
                if (value.isBlank()) return fail("Specify a voice language name.")
                StudyBoardController.StudyBoardCommand.SetOption(StudyBoardController.SettingOptionKey.VOICE_LANGUAGE, value)
            }
            "set_level" -> {
                if (value.isBlank()) return fail("Specify a class or level.")
                StudyBoardController.StudyBoardCommand.SetOption(StudyBoardController.SettingOptionKey.LEVEL, value)
            }
            "set_depth" -> {
                if (value.isBlank()) return fail("Specify Auto, Simple, Detailed, or Advanced.")
                StudyBoardController.StudyBoardCommand.SetOption(StudyBoardController.SettingOptionKey.DEPTH, value)
            }
            "set_provider" -> {
                if (value.isBlank()) return fail("Specify gemini or custom.")
                StudyBoardController.StudyBoardCommand.SetOption(StudyBoardController.SettingOptionKey.PROVIDER, value)
            }
            "switch_mode" -> {
                val targetMode = if (value.equals("doubt", true)) "doubt" else "new"
                StudyBoardController.StudyBoardCommand.SetOption(StudyBoardController.SettingOptionKey.ASK_MODE, targetMode)
            }
            "new_question_mode" -> StudyBoardController.StudyBoardCommand.SetOption(StudyBoardController.SettingOptionKey.ASK_MODE, "new")
            "undo" -> StudyBoardController.StudyBoardCommand.CanvasAction(StudyBoardController.BoardCanvasAction.UNDO)
            "redo" -> StudyBoardController.StudyBoardCommand.CanvasAction(StudyBoardController.BoardCanvasAction.REDO)
            "clear_board" -> StudyBoardController.StudyBoardCommand.CanvasAction(StudyBoardController.BoardCanvasAction.CLEAR_BOARD)
            "fullscreen_on" -> StudyBoardController.StudyBoardCommand.ToggleSetting(StudyBoardController.SettingToggleKey.FULLSCREEN, true)
            "fullscreen_off" -> StudyBoardController.StudyBoardCommand.ToggleSetting(StudyBoardController.SettingToggleKey.FULLSCREEN, false)
            "follow_pen" -> StudyBoardController.StudyBoardCommand.ToggleSetting(StudyBoardController.SettingToggleKey.FOLLOW_PEN, true)
            "zoom_in" -> StudyBoardController.StudyBoardCommand.CanvasAction(StudyBoardController.BoardCanvasAction.ZOOM_IN)
            "zoom_out" -> StudyBoardController.StudyBoardCommand.CanvasAction(StudyBoardController.BoardCanvasAction.ZOOM_OUT)
            "zoom_reset" -> StudyBoardController.StudyBoardCommand.CanvasAction(StudyBoardController.BoardCanvasAction.ZOOM_RESET)
            "fit_board" -> StudyBoardController.StudyBoardCommand.CanvasAction(StudyBoardController.BoardCanvasAction.FIT_BOARD)
            "scroll_top" -> StudyBoardController.StudyBoardCommand.CanvasAction(StudyBoardController.BoardCanvasAction.SCROLL_TOP)
            "scroll_bottom" -> StudyBoardController.StudyBoardCommand.CanvasAction(StudyBoardController.BoardCanvasAction.SCROLL_BOTTOM)
            "copy_board" -> StudyBoardController.StudyBoardCommand.CanvasAction(StudyBoardController.BoardCanvasAction.COPY_BOARD)
            "copy_step" -> StudyBoardController.StudyBoardCommand.CanvasAction(StudyBoardController.BoardCanvasAction.COPY_STEP)
            "select_step" -> {
                val n = stepNumber ?: value.toIntOrNull() ?: return fail("Provide a 1-based step_number.")
                StudyBoardController.StudyBoardCommand.SelectStep(n)
            }
            "bookmark_step" -> {
                val n = stepNumber ?: value.toIntOrNull()
                StudyBoardController.StudyBoardCommand.BookmarkStep(n)
            }
            "explain_with_diagram" -> StudyBoardController.StudyBoardCommand.QuickDoubt("diagram", stepNumber)
            "explain_why" -> StudyBoardController.StudyBoardCommand.QuickDoubt("why", stepNumber)
            "explain_simpler" -> StudyBoardController.StudyBoardCommand.QuickDoubt("simpler", stepNumber)
            "memory_trick" -> StudyBoardController.StudyBoardCommand.QuickDoubt("trick", stepNumber)
            "start_quiz" -> StudyBoardController.StudyBoardCommand.QuizCommand(StudyBoardController.QuizAction.START)
            "submit_quiz_answer" -> {
                if (value.isBlank()) return fail("Provide the student's answer in 'value'.")
                StudyBoardController.StudyBoardCommand.QuizCommand(StudyBoardController.QuizAction.SUBMIT_ANSWER, value)
            }
            "next_quiz_question" -> StudyBoardController.StudyBoardCommand.QuizCommand(StudyBoardController.QuizAction.NEXT)
            "quiz_hint" -> StudyBoardController.StudyBoardCommand.QuizCommand(StudyBoardController.QuizAction.HINT)
            "dismiss_quiz" -> StudyBoardController.StudyBoardCommand.QuizCommand(StudyBoardController.QuizAction.DISMISS)
            "save_lesson" -> StudyBoardController.StudyBoardCommand.CanvasAction(StudyBoardController.BoardCanvasAction.SAVE_LESSON)
            "open_history" -> StudyBoardController.StudyBoardCommand.OpenUi(StudyBoardController.UiTarget.HISTORY)
            "open_camera" -> StudyBoardController.StudyBoardCommand.OpenUi(StudyBoardController.UiTarget.CAMERA)
            "open_image_picker" -> StudyBoardController.StudyBoardCommand.OpenUi(StudyBoardController.UiTarget.IMAGE_PICKER)
            "open_pdf_picker" -> StudyBoardController.StudyBoardCommand.OpenUi(StudyBoardController.UiTarget.PDF_PICKER)
            "open_settings" -> StudyBoardController.StudyBoardCommand.OpenUi(StudyBoardController.UiTarget.TEACHING_SETTINGS)
            "export_menu" -> StudyBoardController.StudyBoardCommand.OpenUi(StudyBoardController.UiTarget.EXPORT_SHEET)
            "export_pdf" -> StudyBoardController.StudyBoardCommand.OpenUi(StudyBoardController.UiTarget.EXPORT_PDF)
            "export_png" -> StudyBoardController.StudyBoardCommand.OpenUi(StudyBoardController.UiTarget.EXPORT_PNG)
            else -> return fail("Unknown Study Board action: $action")
        }

        if (!StudyBoardController.isBoardVisible.value && action != "close") {
            ActivityLauncherHelper.startActivitySafely(app, Intent(app, StudyBoardActivity::class.java))
        }
        StudyBoardController.dispatchCommand(cmd)

        val s = StudyBoardController.stateSnapshot.value
        val yieldAudio = action in setOf(
            "play", "next", "previous", "replay_step", "replay_all", "select_step",
            "explain_with_diagram", "explain_why", "explain_simpler", "memory_trick",
            "start_quiz", "submit_quiz_answer", "next_quiz_question"
        )
        return JSONObject()
            .put("success", true)
            .put("verified", true)
            .put("action", action)
            .put("yield_to_study_voice", yieldAudio)
            .put("state_summary", "phase=${s.phase}, step=${s.currentStepNumber}/${s.totalSteps}, mode=${s.askMode}, quizActive=${s.quizActive}")
            .put("message", "Executed Study Board action: $action" + if (value.isNotEmpty()) " ($value)" else ".")
    }

    private fun fail(msg: String) = JSONObject().put("success", false).put("verified", false).put("message", msg)
}
