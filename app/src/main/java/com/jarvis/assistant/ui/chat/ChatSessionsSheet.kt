package com.jarvis.assistant.ui.chat

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.data.sessions.ChatSessionMeta
import java.text.DateFormat
import java.util.Date

/** All chats: search, pin, rename, archive, export and delete. Tapping a chat opens it; the old one stays saved. */
class ChatSessionsSheet : BottomSheetDialogFragment() {
    private val repo get() = JarvisApp.instance.chatRepository
    private lateinit var list: LinearLayout
    private var query = ""
    private var showArchived = false
    var onChanged: (() -> Unit)? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog = BottomSheetDialog(requireContext(), com.jarvis.assistant.R.style.ThemeOverlay_Jarvis_BottomSheet)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)) }
        val head = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(ctx).apply { text = "Chats"; textSize = 20f; setTypeface(typeface, android.graphics.Typeface.BOLD) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(Button(ctx).apply { text = "＋ New chat"; isAllCaps = false; setOnClickListener {
            if (repo.newSession()) { onChanged?.invoke(); dismiss() } else Toast.makeText(ctx, "Wait for the current answer to finish first.", Toast.LENGTH_SHORT).show() } })
        head.addView(Button(ctx).apply { text = "Workspace"; isAllCaps = false; setOnClickListener { startActivity(android.content.Intent(ctx, com.jarvis.assistant.ui.settings.WorkspaceActivity::class.java)); dismiss() } })
        root.addView(head)
        root.addView(EditText(ctx).apply {
            hint = "Search chats"; setSingleLine(); addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { query = s?.toString().orEmpty(); render() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {} })
        })
        root.addView(Switch(ctx).apply { text = "Show archived"; setOnCheckedChangeListener { _, on -> showArchived = on; render() } })
        list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(ctx).apply { addView(list) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(420)))
        render()
        return root
    }

    private fun render() {
        list.removeAllViews()
        val rows: List<Pair<ChatSessionMeta, String?>> = if (query.isBlank()) repo.sessions(showArchived).map { it to null } else repo.searchSessions(query).filter { showArchived || !it.first.archived }.map { it.first to it.second }
        if (rows.isEmpty()) list.addView(TextView(requireContext()).apply { text = if (query.isBlank()) "No chats yet." else "No chat matches \"$query\"."; setPadding(0, dp(16), 0, 0); alpha = 0.7f })
        val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        for ((m, snippet) in rows) {
            val row = LinearLayout(requireContext()).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
            val col = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
            val current = m.id == repo.currentSessionId
            col.addView(TextView(requireContext()).apply { text = (if (m.pinned) "📌 " else "") + m.title + (if (current) "  ●" else ""); textSize = 16f; setTypeface(typeface, if (current) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL) })
            col.addView(TextView(requireContext()).apply { text = snippet ?: "${fmt.format(Date(m.updatedAt))} · ${m.turnCount} messages" + if (m.archived) " · archived" else ""; textSize = 12f; alpha = 0.7f })
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(requireContext(), null, android.R.attr.borderlessButtonStyle).apply { text = "⋮"; setOnClickListener { menu(it, m) } })
            row.setOnClickListener {
                if (repo.switchTo(m.id)) { onChanged?.invoke(); dismiss() } else Toast.makeText(requireContext(), "Wait for the current answer to finish first.", Toast.LENGTH_SHORT).show()
            }
            list.addView(row)
        }
    }

    private fun menu(anchor: View, m: ChatSessionMeta) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add(0, 1, 0, "Rename"); menu.add(0, 2, 1, if (m.pinned) "Unpin" else "Pin"); menu.add(0, 3, 2, if (m.archived) "Unarchive" else "Archive")
            menu.add(0, 4, 3, "Copy as Markdown"); menu.add(0, 5, 4, "Delete")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> rename(m)
                    2 -> { repo.pinSession(m.id, !m.pinned); render() }
                    3 -> { repo.archiveSession(m.id, !m.archived); render() }
                    4 -> { (requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(m.title, repo.exportSession(m.id))); Toast.makeText(requireContext(), "Copied.", Toast.LENGTH_SHORT).show() }
                    5 -> MaterialAlertDialogBuilder(requireContext(), com.jarvis.assistant.R.style.ThemeOverlay_Jarvis_MaterialDialog).setTitle("Delete chat?").setMessage("\"${m.title}\" and its attachments will be deleted. This can not be undone.")
                        .setPositiveButton("Delete") { _, _ -> if (repo.deleteSession(m.id)) { onChanged?.invoke(); render() } else Toast.makeText(requireContext(), "Wait for the current answer to finish first.", Toast.LENGTH_SHORT).show() }
                        .setNegativeButton("Cancel", null).show()
                }
                true
            }
        }.show()
    }

    private fun rename(m: ChatSessionMeta) {
        val input = EditText(requireContext()).apply { setText(m.title); setSelection(text.length) }
        MaterialAlertDialogBuilder(requireContext(), com.jarvis.assistant.R.style.ThemeOverlay_Jarvis_MaterialDialog).setTitle("Rename chat").setView(input)
            .setPositiveButton("Save") { _, _ -> repo.renameSession(m.id, input.text.toString()); render() }.setNegativeButton("Cancel", null).show()
    }

    companion object { const val TAG = "ChatSessionsSheet" }
}
