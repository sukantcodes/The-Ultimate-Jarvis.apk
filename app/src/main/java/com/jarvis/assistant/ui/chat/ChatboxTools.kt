package com.jarvis.assistant.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.jarvis.assistant.data.model.ChatTurn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Local-first tools for editing drafts and working with the visible conversation. */
object ChatboxTools {
    private data class Action(val title: String, val run: () -> Unit)

    fun show(context: Context, input: EditText, turns: List<ChatTurn>, onExport: (String) -> Unit) {
        val prefs = context.getSharedPreferences("jarvis_chatbox_tools", Context.MODE_PRIVATE)
        fun toast(s: String) = Toast.makeText(context, s, Toast.LENGTH_SHORT).show()
        fun text() = input.text?.toString().orEmpty()
        fun set(s: String) { input.setText(s); input.setSelection(s.length); input.requestFocus() }
        fun prompt(s: String) { val old=text().trim(); set(if(old.isEmpty()) s else "$s\n\n$old"); toast("Prompt added") }
        fun copy(label: String, s: String) {
            if(s.isBlank()) { toast("Nothing to copy"); return }
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText(label,s))
            toast("$label copied")
        }
        fun share(label: String,s: String) {
            if(s.isBlank()) { toast("Nothing to share"); return }
            val i=Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT,label).putExtra(Intent.EXTRA_TEXT,s)
            runCatching { context.startActivity(Intent.createChooser(i,"Share $label")) }.onFailure { toast("Sharing unavailable") }
        }
        fun transcript()=turns.joinToString("\n\n") { "You · ${it.formattedTime}\n${it.userTranscript}\n\nJARVIS\n${it.jarvisResponse}" }
        fun latest()=turns.lastOrNull()?.jarvisResponse.orEmpty()
        fun lines(s:String)=s.lines().map { it.trim() }.filter { it.isNotEmpty() }
        fun titleCase(s:String)=s.lowercase(Locale.getDefault()).split(Regex("\\s+")).joinToString(" ") { w ->
            w.replaceFirstChar { if(it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        }
        fun sentenceCase(s:String):String {
            var start=true
            return s.lowercase(Locale.getDefault()).map { ch ->
                if(start && ch.isLetter()) { start=false; ch.titlecase(Locale.getDefault()) }
                else { if(ch in ".!?\n") start=true; ch.toString() }
            }.joinToString("")
        }

        val writing=listOf(
            Action("Improve clarity and flow"){prompt("Rewrite for clarity, flow, and readability while preserving meaning:")},
            Action("Fix grammar and spelling"){prompt("Correct grammar, spelling, and punctuation. Briefly explain important corrections:")},
            Action("Summarize concisely"){prompt("Summarize this in clear, concise points:")},
            Action("Explain in simple language"){prompt("Explain this in beginner-friendly language with an example:")},
            Action("Expand with useful detail"){prompt("Expand this with relevant context and examples without inventing facts:")},
            Action("Professional tone"){prompt("Rewrite this in a polished, professional tone:")},
            Action("Friendly tone"){prompt("Rewrite this in a natural, friendly tone:")},
            Action("Translate to Hindi"){prompt("Translate this into natural Hindi, preserving the meaning:")},
            Action("Translate to English"){prompt("Translate this into clear, natural English:")},
            Action("Create a checklist"){prompt("Turn this into an actionable checklist:")},
            Action("Convert to bullet points"){prompt("Organize this into concise bullet points:")},
            Action("Convert to a table"){prompt("Organize this into a readable Markdown table where appropriate:")},
            Action("Give practical examples"){prompt("Give practical examples that make this easy to understand:")},
            Action("Brainstorm alternatives"){prompt("Brainstorm useful alternatives and explain their trade-offs:")},
            Action("Create a study quiz"){prompt("Create a quiz from this material and provide a separate answer key:")},
            Action("Make flashcards"){prompt("Turn this material into question-and-answer flashcards:")},
            Action("Step-by-step explanation"){prompt("Explain step by step, defining unfamiliar terms:")},
            Action("Compare options"){prompt("Compare the options in a balanced table and explain when each fits:")},
            Action("Pros and cons"){prompt("List the meaningful advantages, disadvantages, and considerations:")},
            Action("Key takeaways"){prompt("Extract the most important takeaways and why they matter:")},
            Action("Action items"){prompt("Extract concrete action items and group them by priority:")},
            Action("Draft an email"){prompt("Turn this into an email with subject, greeting, body, and closing:")},
            Action("Create revision notes"){prompt("Convert this into organized revision notes with headings and definitions:")},
            Action("Review code"){prompt("Review this code for correctness, readability, performance, and maintainability:")},
            Action("Debug code or error"){prompt("Diagnose this issue, explain the likely cause, and propose a minimal fix:")},
            Action("Build a practical plan"){prompt("Create a realistic step-by-step plan with milestones:")},
            Action("Continue this draft"){prompt("Continue this draft in the same style and preserve its intent:")},
            Action("Simplify without losing meaning"){prompt("Simplify this while preserving all important meaning:")},
            Action("Constructive critique"){prompt("Give constructive feedback: strengths, unclear parts, and improvements:")},
            Action("Add headings and structure"){prompt("Organize this with useful headings and a logical order:")},
        )
        val formatting=listOf(
            Action("UPPERCASE"){set(text().uppercase(Locale.getDefault()))},
            Action("lowercase"){set(text().lowercase(Locale.getDefault()))},
            Action("Title Case"){set(titleCase(text()))},
            Action("Sentence case"){set(sentenceCase(text()))},
            Action("Trim extra whitespace"){set(text().trim().replace(Regex("[ \\t]+")," "))},
            Action("Remove blank lines"){set(lines(text()).joinToString("\n"))},
            Action("Make bullet list"){set(lines(text()).joinToString("\n"){"• $it"})},
            Action("Number each line"){set(lines(text()).mapIndexed{i,s->"${i+1}. $s"}.joinToString("\n"))},
            Action("Quote each line"){set(lines(text()).joinToString("\n"){"> $it"})},
            Action("Wrap in code block"){set("~~~\n${text().trim()}\n~~~")},
            Action("Reverse line order"){set(text().lines().reversed().joinToString("\n"))},
            Action("Sort lines A–Z"){set(text().lines().sortedBy{it.lowercase(Locale.getDefault())}.joinToString("\n"))},
            Action("Remove duplicate lines"){set(text().lines().distinct().joinToString("\n"))},
            Action("Add checkbox markers"){set(lines(text()).joinToString("\n"){"☐ $it"})},
            Action("Prefix lines with dash"){set(lines(text()).joinToString("\n"){"- $it"})}
        )
        val composer=listOf(
            Action("Clear composer"){set("");toast("Composer cleared")},
            Action("Copy composer text"){copy("Draft",text())},
            Action("Paste clipboard text"){
                val clip=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val value=clip.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                if(value.isBlank()) toast("Clipboard is empty") else set(if(text().isBlank()) value else text()+"\n"+value)
            },
            Action("Save draft locally"){prefs.edit().putString("draft",text()).apply();toast("Draft saved on device")},
            Action("Restore saved draft"){set(prefs.getString("draft","").orEmpty());toast("Draft restored")},
            Action("Delete saved draft"){prefs.edit().remove("draft").apply();toast("Saved draft deleted")},
            Action("Insert current date"){set(text()+if(text().isBlank()) "" else "\n"+SimpleDateFormat("EEE, dd MMM yyyy",Locale.getDefault()).format(Date()))},
            Action("Insert current time"){set(text()+if(text().isBlank()) "" else "\n"+SimpleDateFormat("hh:mm a",Locale.getDefault()).format(Date()))},
            Action("Word and character count"){
                val s=text(); val w=s.trim().split(Regex("\\s+")).count{it.isNotBlank()}
                AlertDialog.Builder(context).setTitle("Draft statistics").setMessage("Words: $w\nCharacters: ${s.length}\nWithout spaces: ${s.count{!it.isWhitespace()}}\nLines: ${if(s.isEmpty()) 0 else s.lines().size}").setPositiveButton("OK",null).show()
            },
            Action("Estimated reading time"){
                val w=text().trim().split(Regex("\\s+")).count{it.isNotBlank()}
                toast("About ${maxOf(1,kotlin.math.ceil(w/200.0).toInt())} min ($w words)")
            },
            Action("Save reusable prompt"){
                val s=text().trim()
                if(s.isBlank()) toast("Write a prompt first") else {prefs.edit().putString("template",s).apply();toast("Reusable prompt saved")}
            },
            Action("Insert reusable prompt"){
                val s=prefs.getString("template","").orEmpty()
                if(s.isBlank()) toast("No reusable prompt saved") else set(s)
            }
        )
        val conversation=listOf(
            Action("Copy full conversation"){copy("Conversation",transcript())},
            Action("Share full conversation"){share("JARVIS conversation",transcript())},
            Action("Export full conversation"){val s=transcript();if(s.isBlank())toast("No conversation")else onExport(s)},
            Action("Copy latest JARVIS answer"){copy("Latest answer",latest())},
            Action("Share latest JARVIS answer"){share("JARVIS latest answer",latest())},
            Action("Export latest JARVIS answer"){val s=latest();if(s.isBlank())toast("No answer")else onExport(s)},
            Action("Copy all my messages"){copy("Your messages",turns.joinToString("\n\n"){it.userTranscript})},
            Action("Copy all JARVIS replies"){copy("JARVIS replies",turns.joinToString("\n\n"){it.jarvisResponse})},
            Action("Conversation statistics"){
                val s=transcript();val w=s.trim().split(Regex("\\s+")).count{it.isNotBlank()}
                AlertDialog.Builder(context).setTitle("Conversation statistics").setMessage("Turns: ${turns.size}\nMessages: ${turns.size*2}\nWords: $w\nCharacters: ${s.length}").setPositiveButton("OK",null).show()
            },
            Action("Find text in conversation"){
                val field=EditText(context).apply{hint="Enter a word or phrase"}
                AlertDialog.Builder(context).setTitle("Search conversation").setView(field).setPositiveButton("Search"){_,_->
                    val q=field.text.toString().trim()
                    if(q.isBlank()) toast("Enter a search phrase") else {
                        val found=turns.filter{it.userTranscript.contains(q,true)||it.jarvisResponse.contains(q,true)}
                        val result=found.joinToString("\n\n"){"You: ${it.userTranscript.take(180)}\nJARVIS: ${it.jarvisResponse.take(220)}"}
                        AlertDialog.Builder(context).setTitle("${found.size} matching turn(s)").setMessage(result.ifBlank{"No matches found."}).setPositiveButton("OK",null).show()
                    }
                }.setNegativeButton("Cancel",null).show()
            }
        )
        val settings=listOf(
            Action("⚡ Chatbox Settings & Auto-Approve Permissions"){
                TokenSettingsBottomSheet.show(context)
            },
            Action("🛡️ Toggle Auto-Approve Permissions (Instant)"){
                val current = com.jarvis.assistant.tools.PermissionPreferences.isAutoApproveAll(context)
                com.jarvis.assistant.tools.PermissionPreferences.setAutoApproveAll(context, !current)
                toast(if (!current) "⚡ Auto-Approve Enabled: All permissions auto-approved" else "Auto-Approve Disabled: Safety prompts enabled")
            }
        )
        val groups=linkedMapOf("Agent & Permissions (2)" to settings,"Writing & AI prompts (30)" to writing,"Text formatting (15)" to formatting,"Composer & drafts (12)" to composer,"Conversation tools (10)" to conversation)
        AlertDialog.Builder(context).setTitle("JARVIS Chatbox · 69 tools").setItems(groups.keys.toTypedArray()){_,index->
            val title=groups.keys.elementAt(index);val actions=groups.values.elementAt(index)
            AlertDialog.Builder(context).setTitle(title).setItems(actions.map{it.title}.toTypedArray()){_,i->
                runCatching{actions[i].run()}.onFailure{toast("Tool failed: ${it.message ?: "unknown error"}")}
            }.setNegativeButton("Close",null).show()
        }.setNegativeButton("Close",null).show()
    }
}
