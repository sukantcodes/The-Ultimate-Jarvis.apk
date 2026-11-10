package com.jarvis.assistant.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.jarvis.assistant.data.model.ChatTurn
import com.jarvis.assistant.databinding.ItemNairaChatMessageBinding

class JarvisChatAdapter(
    private val onEditUserMessage: ((String) -> Unit)? = null,
    private val onSaveAssistantMessage: ((String) -> Unit)? = null,
    private val onOpenAttachment: ((String, String, String) -> Unit)? = null,
    private val onEditAssistantMessage: ((String) -> Unit)? = null
) : ListAdapter<ChatTurn, JarvisChatAdapter.MessageViewHolder>(DiffCallback) {

    private var speechEngine: TextToSpeech? = null
    private var speechReady = false
    @Volatile private var speaking = false
    @Volatile private var activeSpeech = ""
    private var pendingSpeech = ""

    fun release() {
        speechEngine?.stop()
        speechEngine?.shutdown()
        speechEngine = null
        speechReady = false
        speaking = false
        activeSpeech = ""
        pendingSpeech = ""
    }

    private fun speak(context: Context, text: String) {
        if (speaking && activeSpeech == text) {
            speechEngine?.stop()
            speaking = false
            activeSpeech = ""
            return
        }
        if (speaking) {
            speechEngine?.stop()
            speaking = false
            activeSpeech = ""
        }
        if (speechEngine == null) {
            pendingSpeech = text
            speechEngine = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    speechReady = true
                    val engine = speechEngine
                    if (engine != null) {
                        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) { speaking = true }
                            override fun onDone(utteranceId: String?) { speaking = false; activeSpeech = "" }
                            override fun onError(utteranceId: String?) { speaking = false; activeSpeech = "" }
                        })
                        val languageResult = engine.setLanguage(Locale.getDefault())
                        if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) engine.setLanguage(Locale.US)
                        val queued = pendingSpeech
                        pendingSpeech = ""
                        if (queued.isNotBlank()) speakNow(queued)
                    }
                } else Toast.makeText(context, "Text-to-speech is unavailable on this device.", Toast.LENGTH_SHORT).show()
            }
        } else if (speechReady) speakNow(text) else pendingSpeech = text
    }

    private fun speakNow(text: String) {
        activeSpeech = text
        speechEngine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis-chat-response")
        speaking = true
    }
    companion object DiffCallback : DiffUtil.ItemCallback<ChatTurn>() {
        override fun areItemsTheSame(oldItem: ChatTurn, newItem: ChatTurn): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: ChatTurn, newItem: ChatTurn): Boolean = oldItem == newItem
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val binding = ItemNairaChatMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return MessageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class MessageViewHolder(
        private val binding: ItemNairaChatMessageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(turn: ChatTurn) {
            val context = binding.root.context
            val attachmentPath = turn.attachmentPath
            val attachmentName = turn.attachmentName.orEmpty()
            val attachmentMime = turn.attachmentMimeType.orEmpty()
            binding.tvUserAttachment.visibility = if (!attachmentPath.isNullOrBlank() && java.io.File(attachmentPath).isFile) View.VISIBLE else View.GONE
            binding.tvUserAttachment.text = "▣  " + attachmentName.ifBlank { "Open attachment" } + "   ·   Preview"
            binding.tvUserAttachment.setOnClickListener {
                if (!attachmentPath.isNullOrBlank()) onOpenAttachment?.invoke(attachmentPath, attachmentName, attachmentMime)
            }

            if (turn.userTranscript.isNotBlank()) {
                binding.layoutUserContainer.visibility = View.VISIBLE
                binding.tvUserMessage.text = turn.userTranscript
                binding.btnUserCopy.setOnClickListener { copyToClipboard(context, "User message", turn.userTranscript) }
                binding.btnUserEdit.setOnClickListener { onEditUserMessage?.invoke(turn.userTranscript) }
            } else {
                binding.layoutUserContainer.visibility = View.GONE
            }

            if (turn.jarvisResponse.isNotBlank()) {
                binding.layoutAssistantContainer.visibility = View.VISIBLE
                binding.tvAssistantMessage.text = MarkdownMessageRenderer.render(context, turn.jarvisResponse)
            binding.tvAssistantMessage.movementMethod = android.text.method.LinkMovementMethod.getInstance()
            binding.tvAssistantMessage.highlightColor = android.graphics.Color.TRANSPARENT

                val reasoning = turn.codingReasoning.orEmpty()
                binding.tvCodingReasoningToggle.visibility = if (reasoning.isBlank()) View.GONE else View.VISIBLE
                binding.tvCodingReasoningDetails.visibility = View.GONE
                binding.tvCodingReasoningToggle.text = "▸ THINKING · TAP TO EXPAND"
                binding.tvCodingReasoningDetails.text = reasoning
                binding.tvCodingReasoningToggle.setOnClickListener {
                    val expand = binding.tvCodingReasoningDetails.visibility != View.VISIBLE
                    binding.tvCodingReasoningDetails.visibility = if (expand) View.VISIBLE else View.GONE
                    binding.tvCodingReasoningToggle.text = (if (expand) "▾" else "▸") + " THINKING · " + if (expand) "COLLAPSE" else "EXPAND"
                }

                val codingTools = turn.codingTools.orEmpty()
                binding.tvCodingToolsToggle.visibility = if (codingTools.isEmpty()) View.GONE else View.VISIBLE
                binding.tvCodingToolsDetails.visibility = View.GONE
                val completed = codingTools.count { !it.running && it.ok }
                val running = codingTools.count { it.running }
                binding.tvCodingToolsToggle.text = "▸ TOOL ACTIVITY · ${codingTools.size} ACTIONS · ${completed} DONE" +
                    (if (running > 0) " · ${running} RUNNING" else "") + " · TAP TO EXPAND"
                binding.tvCodingToolsDetails.text = codingTools.joinToString("\n\n") { tool ->
                    val status = if (tool.running) "RUNNING" else if (tool.ok) "DONE" else "FAILED"
                    "${status}  ·  ${tool.name}\nInput: ${tool.input.take(1800)}\nOutput:\n${tool.output.take(6000)}"
                }
                binding.tvCodingToolsToggle.setOnClickListener {
                    val expand = binding.tvCodingToolsDetails.visibility != View.VISIBLE
                    binding.tvCodingToolsDetails.visibility = if (expand) View.VISIBLE else View.GONE
                    binding.tvCodingToolsToggle.text = (if (expand) "▾" else "▸") + " TOOL ACTIVITY · " +
                        codingTools.size + " ACTIONS · " + if (expand) "COLLAPSE" else "EXPAND"
                }

                binding.btnAssistantCopy.setOnClickListener { copyToClipboard(context, "Jarvis response", turn.jarvisResponse) }
                binding.btnAssistantEdit.setOnClickListener { onEditAssistantMessage?.invoke(turn.jarvisResponse) }
                binding.btnAssistantPlay.setOnClickListener {
                    speak(context, turn.jarvisResponse)
                }
                binding.btnAssistantSave.setOnClickListener { onSaveAssistantMessage?.invoke(turn.jarvisResponse) }
            } else {
                binding.layoutAssistantContainer.visibility = View.GONE
            }
        }

        private fun copyToClipboard(context: Context, label: String, text: String) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    }
}
