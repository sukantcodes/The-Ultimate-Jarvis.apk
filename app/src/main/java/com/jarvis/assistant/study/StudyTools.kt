package com.jarvis.assistant.study

import android.content.Context
import android.content.Intent
import com.jarvis.assistant.ui.studyboard.StudyBoardActivity
import com.jarvis.assistant.util.ActivityLauncherHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * Voice access to the Study Board. When the person asks JARVIS to explain something "in Study Board" (or "on the board"),
 * the AI calls [EXPLAIN]: the board opens and the question runs by itself. The answer is then written on the board, not
 * read out in the voice chat.
 */
object StudyTools {
    const val EXPLAIN = "study_board_explain"
    val NAMES: Set<String> = setOf(EXPLAIN)
    const val TOOL_TIMEOUT_MS = 20_000L

    fun declarations(): List<JSONObject> = listOf(
        JSONObject().put("name", EXPLAIN).put(
            "description",
            "Opens the JARVIS Study Board (the whiteboard teacher) and solves/explains a question there step by step. Use it whenever the user asks to explain, solve, teach or show something " +
                "IN THE STUDY BOARD, on the board, or says things like \"study board me samjhao\", \"explain this on the board\", \"board pe solve karo\". " +
                "Put the COMPLETE question in the user's own words in 'question', including any extra request such as 'with reactions', 'with diagram', 'step by step', 'in Hindi'. " +
                "If the user points at something they can see (\"this maths problem\") and you can see it through the camera or screen, write the whole problem out in 'question'. " +
                "If you cannot tell what the question is, ask the user first instead of calling this. After calling, say only a very short confirmation (e.g. \"Opening it on the Study Board\"); do NOT read the solution aloud."
        ).put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject().put(
            "question", JSONObject().put("type", "STRING").put("description", "The full question or topic to explain, with every extra instruction the user gave.")
        )).put("required", JSONArray().put("question")))
    )

    /** Opens the board with the question. Safe to call from the voice service (uses the background-launch helper). */
    fun execute(context: Context, name: String, args: JSONObject): JSONObject {
        if (name != EXPLAIN) return fail("Unknown Study Board tool.")
        val question = args.optString("question").trim()
        if (question.isEmpty()) return fail("No question was given. Ask the user what they want explained.")
        return try {
            val app = context.applicationContext
            val intent = Intent(app, StudyBoardActivity::class.java).putExtra(StudyBoardActivity.EXTRA_QUESTION, question)
            val launched = ActivityLauncherHelper.startActivitySafely(app, intent)
            if (launched) JSONObject().put("success", true).put("verified", true)
                .put("message", "The Study Board is open and is working on: \"${question.take(120)}\". Tell the user in one short sentence that it is on the Study Board. Do not explain the answer aloud.")
            else fail("Could not open the Study Board from the background. Ask the user to allow display over other apps, or to open the Study Board themselves.")
        } catch (e: Exception) {
            fail("Could not open the Study Board: ${e.message}")
        }
    }

    private fun fail(msg: String) = JSONObject().put("success", false).put("verified", false).put("message", msg)
}
