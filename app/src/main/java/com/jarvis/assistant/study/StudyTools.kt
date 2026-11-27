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
        "play", "pause", "toggle_play", "next", "previous", "replay_step", "replay_all", "stop_generation", "retry",
        "set_speed", "speed_up", "slow_down",
        "voice_on", "voice_off", "toggle_voice", "notes_on", "notes_off", "toggle_notes",
        "pen_on", "pen_off", "status_on", "status_off", "memory_on", "memory_off", "clear_memory",
        "set_language", "set_voice_language", "set_level", "set_depth", "set_provider", "switch_mode",
        "undo", "redo", "clear_board", "fullscreen_on", "fullscreen_off", "follow_pen",
        "zoom_in", "zoom_out", "zoom_reset", "fit_board",
        "select_step", "bookmark_step", "save_lesson",
        "open_history", "open_camera", "open_image_picker", "open_pdf_picker", "open_settings",
        "export_menu", "export_pdf", "export_png"
    )

    fun declarations(): List<JSONObject> = listOf(
        JSONObject().put("name", EXPLAIN).put(
            "description",
            "Opens the JARVIS Study Board (the whiteboard teacher), visibly types the user's question into the Study Board box, and solves/explains it step by step with visual diagrams and live human voice. " +
                "Use this whenever the user asks to explain a question, teach a topic, solve a problem, draw a study diagram, or says things like \"explain me ...\", \"solve this ...\", \"open study board and ...\", \"board pe samjhao\", or asks a doubt while the Study Board is open. " +
                "Pass the COMPLETE, exact question in 'question' (preserving any diagram, language, or step-by-step instructions). " +
                "After calling this tool, give only an ultra-short 2-5 word confirmation (e.g. \"Opening Study Board, Sir.\") and stay silent so the Study Board's own teacher voice explains everything without overlapping."
        ).put(
            "parameters",
            JSONObject().put("type", "OBJECT").put(
                "properties",
                JSONObject().apply {
                    put("question", JSONObject().put("type", "STRING").put("description", "The full question, equation, diagram prompt, or doubt text to type and solve on the Study Board."))
                    put("mode", JSONObject().put("type", "STRING").put("enum", JSONArray().put("new").put("doubt")).put("description", "Use 'new' for a fresh question (default), or 'doubt' for a follow-up doubt about the current lesson."))
                    put("step_number", JSONObject().put("type", "INTEGER").put("description", "Optional 1-based step number on the board that the doubt refers to."))
                    put("language", JSONObject().put("type", "STRING").put("description", "Optional language name such as English, Hindi, Hinglish, Telugu, Tamil, Bengali, Marathi, Gujarati, Kannada, Malayalam, Punjabi, Urdu, Odia, Assamese, Nepali, Spanish, French, German, Arabic, Japanese, Korean, Chinese, or Auto."))
                    put("level", JSONObject().put("type", "STRING").put("description", "Optional student level such as Auto, Class 6..Class 12, or Undergraduate."))
                    put("depth", JSONObject().put("type", "STRING").put("enum", JSONArray().put("Auto").put("Simple").put("Detailed").put("Advanced")).put("description", "Optional explanation depth."))
                }
            ).put("required", JSONArray().put("question"))
        ),

        JSONObject().put("name", CONTROL).put(
            "description",
            "Controls every function of the JARVIS Study Board: opening/closing, playback (play, pause, next, previous, replay step, replay all, stop, retry), speed, voice mute/unmute, notes/pen/status/memory toggles, language/level/depth/mode switching, undo/redo/clear, zoom/fit/fullscreen/follow_pen, step selection/bookmarking, saving, exporting PDF/PNG, opening history/camera/gallery/PDF/settings, and inspecting board state (get_state)."
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
                        JSONObject().put("type", "STRING").put("description", "Optional parameter for actions like set_speed ('0.5'..'2.0'), set_language, set_voice_language, set_level, set_depth, set_provider ('gemini'|'custom'), or switch_mode ('new'|'doubt').")
                    )
                    put(
                        "step_number",
                        JSONObject().put("type", "INTEGER").put("description", "Optional 1-based step number for select_step.")
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
        val mode = args.optString("mode", "new").trim().lowercase(Locale.ROOT).ifEmpty { "new" }
        val stepNumber = args.optInt("step_number", -1).takeIf { it > 0 }
        val language = args.optString("language").trim().takeIf { it.isNotEmpty() }
        val level = args.optString("level").trim().takeIf { it.isNotEmpty() }
        val depth = args.optString("depth").trim().takeIf { it.isNotEmpty() }

        return try {
            val app = context.applicationContext
            if (StudyBoardController.isBoardVisible.value) {
                StudyBoardController.dispatchCommand(
                    StudyBoardController.StudyBoardCommand.OpenAndExplain(
                        question = question,
                        mode = mode,
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
                    .put("yield_to_study_voice", true)
                    .put("message", "Typed and submitted \"$question\" on the active Study Board. Give only a brief confirmation and stay quiet while the board explains.")
            }

            val intent = Intent(app, StudyBoardActivity::class.java).apply {
                putExtra(StudyBoardActivity.EXTRA_QUESTION, question)
                putExtra(StudyBoardActivity.EXTRA_MODE, mode)
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
                    .put("yield_to_study_voice", true)
                    .put("message", "Study Board is opening, typing, and explaining: \"${question.take(120)}\". Confirm briefly and let the Study Board voice teach.")
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
                .put("total_steps", s.totalSteps)
                .put("playback_state", s.playbackState)
                .put("speed", s.speed.toDouble())
                .put("voice_on", s.voiceOn)
                .put("notes_on", s.notesOn)
                .put("pen_on", s.penOn)
                .put("language", s.language)
                .put("voice_language", s.voiceLanguage)
                .put("level", s.level)
                .put("depth", s.depth)
                .put("active_voice_name", s.activeVoiceName)
                .put("has_content", s.hasContent)
                .put("can_undo", s.canUndo)
                .put("can_redo", s.canRedo)
                .put("fullscreen", s.fullscreen)
                .put("bookmarked_count", s.bookmarkedCount)
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
            "select_step" -> {
                val n = stepNumber ?: value.toIntOrNull() ?: return fail("Provide a 1-based step_number.")
                StudyBoardController.StudyBoardCommand.SelectStep(n)
            }
            "bookmark_step" -> StudyBoardController.StudyBoardCommand.ToggleSetting(StudyBoardController.SettingToggleKey.BOOKMARK_CURRENT, null)
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

        val yieldAudio = action in setOf("play", "next", "previous", "replay_step", "replay_all", "select_step")
        return JSONObject()
            .put("success", true)
            .put("verified", true)
            .put("action", action)
            .put("yield_to_study_voice", yieldAudio)
            .put("message", "Executed Study Board action: $action" + if (value.isNotEmpty()) " ($value)" else ".")
    }

    private fun fail(msg: String) = JSONObject().put("success", false).put("verified", false).put("message", msg)
}
