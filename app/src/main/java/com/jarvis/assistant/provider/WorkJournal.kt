package com.jarvis.assistant.provider

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.Locale

/** One finished tool step of a ChatBox job, as far as a later resume needs to know it. */
data class JournalStep(
    val tool: String,
    val target: String,
    val failed: Boolean,
    /** For file writes: how many lines the file had after this step. */
    val lines: Int = 0,
    /** For file writes: the last lines of what was written, so a resume can pick up at the exact line. */
    val tail: List<String> = emptyList()
)

/** Everything needed to carry on a ChatBox job exactly where it stopped, instead of starting again. */
data class WorkJournal(
    val turnId: String,
    val prompt: String,
    val steps: MutableList<JournalStep> = ArrayList(),
    /** The code and output blocks already shown in the chat for this job, keyed by file or command. */
    val blocks: LinkedHashMap<String, String> = LinkedHashMap(),
    /** Null while the job is running or finished; otherwise why it stopped (an error, a stop, the step limit). */
    var interruptedBy: String? = null,
    var finished: Boolean = false
) {
    val canResume: Boolean get() = !finished && steps.any { !it.failed }
}

/**
 * Remembers the progress of ChatBox jobs. Saved to disk after every step so a job survives an API error, a stop
 * or the app being closed, and "Retry", a typed "continue" or a spoken "continue" all pick it up from here.
 */
class WorkJournalStore(private val file: File? = null) {
    private val gson = Gson()
    private val journals = LinkedHashMap<String, WorkJournal>()
    private var order = ArrayList<String>()

    init { load() }

    @Synchronized fun begin(turnId: String, prompt: String): WorkJournal {
        val j = WorkJournal(turnId, prompt)
        journals[turnId] = j
        touch(turnId)
        save()
        return j
    }

    /** Continues an earlier job under a new chat turn (a typed or spoken "continue"). */
    @Synchronized fun adopt(fromId: String, toId: String): WorkJournal? {
        val old = journals[fromId] ?: return null
        val j = WorkJournal(toId, old.prompt, ArrayList(old.steps), LinkedHashMap(old.blocks))
        journals.remove(fromId)
        order.remove(fromId)
        journals[toId] = j
        touch(toId)
        save()
        return j
    }

    @Synchronized fun get(turnId: String): WorkJournal? = journals[turnId]

    /** The most recent job that stopped before it was finished, if any step of it succeeded. */
    @Synchronized fun latestResumable(): WorkJournal? =
        order.asReversed().mapNotNull { journals[it] }.firstOrNull { it.canResume && it.interruptedBy != null }

    @Synchronized fun resumable(turnId: String): WorkJournal? = journals[turnId]?.takeIf { it.canResume && it.interruptedBy != null }

    @Synchronized fun running(turnId: String) {
        journals[turnId]?.apply { interruptedBy = null; finished = false }
        save()
    }

    @Synchronized fun recordStep(turnId: String, step: LiveToolStep) {
        val j = journals[turnId] ?: return
        val isWrite = step.toolName == "write_file"
        val isAppend = step.toolName == "append_file"
        var lines = 0
        var tail: List<String> = emptyList()
        if (!step.failed && (isWrite || isAppend) && step.codeOrOutput.isNotEmpty()) {
            val added = step.codeOrOutput.trimEnd('\n').lines()
            if (isAppend) {
                val before = j.steps.lastOrNull { !it.failed && it.target == step.target && it.lines > 0 }
                lines = (before?.lines ?: 0) + added.size
                tail = ((before?.tail ?: emptyList()) + added.map { it.take(240) }).takeLast(TAIL_LINES)
            } else {
                lines = added.size
                tail = added.takeLast(TAIL_LINES).map { it.take(240) }
            }
        }
        j.steps += JournalStep(step.toolName, step.target, step.failed, lines, tail)
        if (j.steps.size > MAX_STEPS) j.steps.removeAt(0)
        save()
    }

    @Synchronized fun recordBlocks(turnId: String, blocks: Map<String, String>) {
        val j = journals[turnId] ?: return
        j.blocks.clear()
        var total = 0
        for ((k, v) in blocks.entries.reversed()) {
            total += v.length
            if (total > MAX_BLOCK_CHARS) break
            j.blocks[k] = v
        }
        val ordered = LinkedHashMap<String, String>()
        j.blocks.entries.reversed().forEach { ordered[it.key] = it.value }
        j.blocks.clear(); j.blocks.putAll(ordered)
        save()
    }

    @Synchronized fun interrupted(turnId: String, reason: String) {
        journals[turnId]?.apply { interruptedBy = reason.take(300); finished = false }
        save()
    }

    @Synchronized fun finished(turnId: String) {
        journals[turnId]?.apply { finished = true; interruptedBy = null }
        save()
    }

    private fun touch(id: String) { order.remove(id); order.add(id); while (order.size > MAX_JOBS) journals.remove(order.removeAt(0)) }

    private fun save() {
        val f = file ?: return
        try {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(gson.toJson(order.mapNotNull { journals[it] }))
            if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
        } catch (_: Exception) {
            // Losing the saved copy only costs a resume after the app was closed; the running job is unaffected.
        }
    }

    private fun load() {
        val f = file ?: return
        try {
            if (!f.exists()) return
            val type = object : TypeToken<List<WorkJournal>>() {}.type
            val list: List<WorkJournal> = gson.fromJson(f.readText(), type) ?: return
            for (j in list) {
                // A job that was still running when the app closed counts as interrupted.
                if (!j.finished && j.interruptedBy == null) j.interruptedBy = "The app was closed while the work was running."
                journals[j.turnId] = j
                order.add(j.turnId)
            }
        } catch (_: Exception) {
            journals.clear(); order.clear()
        }
    }

    companion object {
        const val TAIL_LINES = 25
        const val MAX_STEPS = 300
        const val MAX_JOBS = 12
        const val MAX_BLOCK_CHARS = 600_000

        /** The instruction a resumed job sends to the model in place of the original prompt. */
        fun brief(j: WorkJournal, extra: String = ""): String = buildString {
            append("CONTINUE AN UNFINISHED TASK. The work was interrupted. Do NOT start over, do NOT recreate files that are already complete, and do NOT rewrite a file from its first line.\n\n")
            append("Original request:\n").append(j.prompt.trim()).append("\n\n")
            j.interruptedBy?.let { append("It was interrupted by: ").append(it).append("\n\n") }
            val done = j.steps.filter { !it.failed }
            append("Steps already finished, in order:\n")
            done.takeLast(60).forEachIndexed { i, s ->
                append(i + 1 + (done.size - minOf(done.size, 60))).append(". ").append(s.tool).append(' ').append(s.target.take(120))
                if (s.lines > 0) append(" (").append(s.lines).append(" lines)")
                append('\n')
            }
            val last = done.lastOrNull { it.tail.isNotEmpty() }
            if (last != null) {
                append("\nThe last file being written was ").append(last.target).append(". It has ").append(last.lines).append(" lines so far. Its last lines are:\n")
                val first = last.lines - last.tail.size + 1
                last.tail.forEachIndexed { i, line -> append(String.format(Locale.ROOT, "%5d| %s\n", first + i, line)) }
            }
            append("\nHow to continue:\n")
            append("1. Use list_files and read_file to see the real current contents of the files above before you change anything.\n")
            append("2. Carry on from exactly the next missing piece. If a file is unfinished, finish it with append_file or edit_file starting right after its last line. Never rewrite finished parts.\n")
            append("3. Do not repeat steps that already succeeded.\n")
            append("4. Do not start a preview and do not say the work is finished until every part of the original request is really complete.")
            if (extra.isNotBlank()) append("\n\nAdditional instruction from the user: ").append(extra.trim())
        }
    }
}

/** Recognises "continue" in English, Hindi, Bengali and their Roman spellings, so it can resume instead of starting a new job. */
object ContinueIntent {
    private val words = listOf(
        "continue", "resume", "carry on", "keep going", "go on", "proceed", "finish it", "finish the work", "complete it", "complete the work",
        "jari rakho", "jaari rakho", "jari rakhiye", "aage badho", "aage badhao", "aage chalo", "age badho", "kaam jari", "kaam jaari", "phir se shuru nahi", "continue karo", "continue kro",
        "জারি রাখো", "চালিয়ে যাও", "আবার শুরু না", "এগিয়ে যাও", "কন্টিনিউ", "agey cholo", "agiye jao", "cholte thako",
        "जारी रखो", "जारी रखें", "आगे बढ़ो", "आगे बढ़ें", "आगे चलो", "कंटिन्यू", "काम जारी", "पूरा करो"
    )
    private val regex = Regex("^\\s*(?:please\\s+|pls\\s+|ok(?:ay)?[,.]?\\s+|jarvis[,.]?\\s+|haan\\s+|ha\\s+)?(?:" + words.joinToString("|") { Regex.escape(it) } + ")(?![\\p{L}\\p{M}\\p{N}])", RegexOption.IGNORE_CASE)

    /** The extra instruction after the word "continue" (possibly empty), or null when the text is not a continue request. */
    fun match(text: String): String? {
        val t = text.trim()
        if (t.isEmpty() || t.length > 400) return null
        val m = regex.find(t.lowercase(Locale.ROOT)) ?: return null
        val rest = t.substring(minOf(m.range.last + 1, t.length)).trim().trimStart(',', '.', '-', ':', '!', ' ')
        val filler = rest.lowercase(Locale.ROOT).trim()
        val onlyFiller = filler.isEmpty() || filler in setOf("it", "please", "pls", "the work", "work", "from where you stopped", "from where it stopped", "karo", "kro", "do", "ji", "na", "প্লিজ", "करो", "कीजिए", "please continue")
        return if (onlyFiller) "" else rest
    }
}
