package com.jarvis.assistant.provider

import android.content.Context
import com.jarvis.assistant.tools.ToolBackend
import com.jarvis.assistant.tools.ToolCall
import com.jarvis.assistant.tools.ToolResult
import com.jarvis.assistant.tools.ToolSpec

/**
 * What the ChatBox model is asked to do. The mode decides which tools it may use and what it is told to produce:
 *  - ASK: answers questions only, never code, no tools.
 *  - DEBUG: finds and explains bugs in pasted code or in a file or folder from the phone, read-only unless asked to fix.
 *  - CODE: writes code into the chat only; no files, no folders, no commands.
 *  - DEVELOP: the full builder (files, folders, local server, preview). The default.
 */
enum class ChatWorkMode(
    val key: String,
    val label: String,
    val inputHint: String,
    val usesTools: Boolean,
    /** Tools the model may use in this mode, or null for all of them. Ignored when [usesTools] is false. */
    val allowedTools: Set<String>?,
    val instruction: String
) {
    ASK(
        key = "ask",
        label = "Ask",
        inputHint = "Ask me anything…",
        usesTools = false,
        allowedTools = emptySet(),
        instruction = "MODE: ASK. Give the answer itself, straight away. Your first sentence must already be the answer. Never show your thinking or planning, and never describe the request: " +
            "do not write things like 'The user asked', 'The user wants', 'You asked me', 'Let me think', 'I need to' or 'Okay,'. Do not restate the question and do not add a preamble or a closing offer. Keep simple answers short. " +
            "Make the answer easy to read with markdown: when the content is a comparison, a list of items with several attributes, steps with details, prices, specs, pros and cons, or a schedule, put it in a markdown table (header row, then rows). " +
            "Use ## and ### headings for longer answers, **bold** for key terms, ==highlighted== text for the single most important fact, {red:text}, {green:text}, {blue:text}, {orange:text} or {purple:text} to colour a few key words " +
            "(for example warnings in red and good news in green), and {big:text} or {small:text} to change the size of a word or short phrase. Use these sparingly so the answer stays clean. " +
            "You only answer questions. Do NOT write any code, code blocks, scripts or commands, and do not create or change files. " +
            "If the user asks you to write or fix code, say in one short line that Ask mode only answers questions and suggest Code, Debug or Develop mode. You have no tools in this mode."
    ),
    DEBUG(
        key = "debug",
        label = "Debug",
        inputHint = "Paste code, or a file / folder path or name…",
        usesTools = true,
        allowedTools = setOf(
            "list_files", "read_file", "search_files", "read_document", "search_document", "compare_documents", "inspect_archive",
            "edit_file", "write_file", "append_file", "terminal_run", "project_check",
            "web_search", "web_fetch", "memory_search", "github_read_file", "github_search_code"
        ),
        instruction = "MODE: DEBUG. These mode rules override the tool rules above. The user gives you code, or a file or folder path, or only a file or folder name. " +
            "If they give only a name, find it yourself: use list_files on /sdcard, /storage/emulated/0 and the workspace and search_files until you locate it; if several match, list them and ask which one. " +
            "Read the file, or walk the folder and read its source files, then find every real problem: crashes, wrong logic, unhandled edge cases, bad imports, syntax errors, security holes, resource leaks. " +
            "For each problem give the file and line, what is wrong and why, and the corrected code. Never invent file contents: only talk about what you really read. " +
            "Do NOT change any file unless the user asks you to fix or apply it. When they do, make the smallest correct change with edit_file and read the file again to confirm. " +
            "Never start a preview or a server in this mode."
    ),
    CODE(
        key = "code",
        label = "Code",
        inputHint = "Describe the code you want…",
        usesTools = false,
        allowedTools = emptySet(),
        instruction = "MODE: CODE. Write the code directly in your reply, in fenced code blocks with the language name, complete and ready to run, with one or two lines on how to run it. " +
            "You have no tools in this mode: never create files or folders, never run commands, never say you saved anything. The user copies the code from the chat. " +
            "Keep each block self-contained, with no placeholders or TODOs."
    ),
    DEVELOP(
        key = "develop",
        label = "Develop",
        inputHint = "Describe what to build…",
        usesTools = true,
        allowedTools = null,
        instruction = "MODE: DEVELOP. You are a full-stack builder. Create real files and folders in the workspace, write complete production-grade code with no placeholders, shortcuts or TODOs, " +
            "check the result (project_check, running it), and when a website or web app is ready start the local server and its preview. " +
            "Follow the user's prompt exactly, whether it is one line or a long specification, and keep working until everything is finished and working."
    );

    companion object {
        val DEFAULT = DEVELOP
        fun fromKey(key: String?): ChatWorkMode = values().firstOrNull { it.key == key } ?: DEFAULT
    }
}

object ChatWorkModeStore {
    private const val PREFS = "jarvis_chat_work_mode"
    private const val KEY = "mode"

    fun get(context: Context): ChatWorkMode =
        ChatWorkMode.fromKey(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, ChatWorkMode.DEFAULT.key))

    fun set(context: Context, mode: ChatWorkMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, mode.key).apply()
    }
}

/** Lets the model use only the tools a mode allows. Everything else is neither offered nor executed. */
class FilteredToolBackend(private val inner: ToolBackend, private val allowed: Set<String>) : ToolBackend {
    override fun specs(): List<ToolSpec> = inner.specs().filter { it.name in allowed }

    override suspend fun execute(call: ToolCall): ToolResult =
        if (call.name in allowed) inner.execute(call) else ToolResult.Error("${call.name} is not available in this mode.")
}

/** Ask mode never shows code, even if the model writes some: fenced blocks are replaced by a short note. */
object AskModeGuard {
    const val NOTE = "_(Ask mode doesn't write code. Switch to Code or Develop mode for that.)_"

    private val thinkBlock = Regex("(?is)<(think|thinking|thought|reasoning)>.*?(</\\1>|$)")
    private val narration = Regex(
        "(?i)^\\s*(okay,?\\s+|ok,?\\s+|alright,?\\s+|so,?\\s+|let me think[^\\n]*\\n\\s*)?" +
            "(the user|user)\\s+(is\\s+)?(asked|asks|asking|wants|wanted|is asking|would like|needs|said|wrote|is curious|seems)\\b[^\\n]*\\n+"
    )
    private val planLine = Regex("(?i)^\\s*(let me think|let me break|i need to (figure|understand|answer|think|explain)|i should (answer|explain|respond))\\b[^\\n]*\\n+")

    /** Removes <think> blocks and leading "The user asked me…" narration so only the answer remains. */
    fun clean(text: String): String {
        var t = thinkBlock.replace(text, "")
        // While a block is still open (no closing tag yet) it was removed through the end above.
        repeat(4) {
            val m = narration.find(t) ?: planLine.find(t) ?: return@repeat
            if (m.range.first != 0 || m.value.length >= t.length) return@repeat
            t = t.substring(m.value.length)
        }
        return t.trimStart()
    }

    fun strip(raw: String): String {
        val text = clean(raw)
        if (!text.contains("```")) return text
        val out = StringBuilder()
        var i = 0
        var noted = false
        while (i < text.length) {
            val open = text.indexOf("```", i)
            if (open < 0) { out.append(text, i, text.length); break }
            out.append(text, i, open)
            if (!noted) { out.append(NOTE); noted = true }
            val close = text.indexOf("```", open + 3)
            if (close < 0) break            // still being written: hide it until it closes
            i = close + 3
        }
        return out.toString().replace(Regex("\n{3,}"), "\n\n")
    }
}
