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
import com.jarvis.assistant.databinding.LayoutCodeArtifactCardBinding

class JarvisChatAdapter(
    private val onEditUserMessage: ((String) -> Unit)? = null,
    private val onSaveAssistantMessage: ((String) -> Unit)? = null,
    private val onOpenAttachment: ((String, String, String) -> Unit)? = null
) : ListAdapter<ChatTurn, JarvisChatAdapter.MessageViewHolder>(DiffCallback) {

    private var speechEngine: TextToSpeech? = null
    private var speechReady = false
    @Volatile private var speaking = false
    private var pendingSpeech = ""

    fun release() {
        speechEngine?.stop()
        speechEngine?.shutdown()
        speechEngine = null
        speechReady = false
        speaking = false
        pendingSpeech = ""
    }

    private fun speak(context: Context, text: String) {
        if (speaking) { speechEngine?.stop(); speaking = false; return }
        if (speechEngine == null) {
            pendingSpeech = text
            speechEngine = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    speechReady = true
                    val engine = speechEngine
                    if (engine != null) {
                        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) { speaking = true }
                            override fun onDone(utteranceId: String?) { speaking = false }
                            override fun onError(utteranceId: String?) { speaking = false }
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
        speechEngine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis-chat-response")
        speaking = true
    }

    companion object DiffCallback : DiffUtil.ItemCallback<ChatTurn>() {
        const val PAYLOAD_RESPONSE_UPDATE = "PAYLOAD_RESPONSE_UPDATE"

        override fun areItemsTheSame(oldItem: ChatTurn, newItem: ChatTurn): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: ChatTurn, newItem: ChatTurn): Boolean = oldItem == newItem

        override fun getChangePayload(oldItem: ChatTurn, newItem: ChatTurn): Any? {
            if (oldItem.id == newItem.id &&
                oldItem.jarvisResponse != newItem.jarvisResponse &&
                oldItem.userTranscript == newItem.userTranscript &&
                oldItem.attachmentPath == newItem.attachmentPath) {
                return PAYLOAD_RESPONSE_UPDATE
            }
            return null
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val binding = ItemNairaChatMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return MessageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_RESPONSE_UPDATE)) {
            holder.bindResponseUpdate(getItem(position))
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    inner class MessageViewHolder(
        private val binding: ItemNairaChatMessageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        /**
         * Fast partial rebind during streaming. Updates only the assistant text and hides the thinking
         * indicator without touching user bubble, attachments, or triggering full view re-layouts.
         */
        fun bindResponseUpdate(turn: ChatTurn) {
            binding.layoutThinking.visibility = View.GONE
            renderAssistantContent(turn, isStreaming = true)
        }

        private fun renderAssistantContent(turn: ChatTurn, isStreaming: Boolean) {
            val context = binding.root.context
            if (turn.jarvisResponse.isBlank()) {
                binding.layoutAssistantContainer.visibility = View.GONE
                return
            }
            binding.layoutAssistantContainer.visibility = View.VISIBLE
            // Keep markdown links tappable (Termux ChatBox markdown feature).
            binding.tvAssistantMessage.movementMethod = android.text.method.LinkMovementMethod.getInstance()

            val rawResponse = turn.jarvisResponse
            if (rawResponse.contains("```")) {
                val parsed = CodeBlockParser.parse(rawResponse, isActivelyStreaming = isStreaming)
                val textBuilder = StringBuilder()
                val codeBlocks = ArrayList<ParsedContent.Code>()

                for (segment in parsed) {
                    when (segment) {
                        is ParsedContent.Text -> textBuilder.append(segment.text)
                        is ParsedContent.Code -> codeBlocks.add(segment)
                    }
                }

                val rendered: CharSequence = try {
                    val text = textBuilder.toString().trim()
                    if (text.isNotEmpty()) MarkdownRenderer.render(text, context.resources.displayMetrics.density) else ""
                } catch (_: Throwable) {
                    textBuilder.toString()
                }

                if (rendered.isNotBlank()) {
                    binding.tvAssistantMessage.text = rendered
                    binding.tvAssistantMessage.visibility = View.VISIBLE
                } else {
                    binding.tvAssistantMessage.visibility = View.GONE
                }

                binding.layoutAssistantArtifacts.visibility = View.VISIBLE
                val container = binding.layoutAssistantArtifacts
                while (container.childCount > codeBlocks.size) {
                    container.removeViewAt(container.childCount - 1)
                }
                for (i in codeBlocks.indices) {
                    val block = codeBlocks[i]
                    val cardBinding = if (i < container.childCount) {
                        LayoutCodeArtifactCardBinding.bind(container.getChildAt(i))
                    } else {
                        val card = LayoutCodeArtifactCardBinding.inflate(LayoutInflater.from(context), container, false)
                        container.addView(card.root)
                        card
                    }
                    CodeArtifactCardBinder.bind(cardBinding, block.language, block.code, block.isWriting)
                }
            } else {
                binding.layoutAssistantArtifacts.visibility = View.GONE
                binding.layoutAssistantArtifacts.removeAllViews()
                binding.tvAssistantMessage.visibility = View.VISIBLE
                val rendered: CharSequence = try {
                    MarkdownRenderer.render(rawResponse, context.resources.displayMetrics.density)
                } catch (_: Throwable) {
                    rawResponse
                }
                binding.tvAssistantMessage.text = rendered
            }

            binding.btnAssistantCopy.setOnClickListener { copyToClipboard(context, "Jarvis response", rawResponse) }
            binding.btnAssistantPlay.setOnClickListener {
                try { speak(context, rawResponse) } catch (_: Exception) {
                    Toast.makeText(context, "Couldn't read this aloud", Toast.LENGTH_SHORT).show()
                }
            }
            binding.btnAssistantSave.setOnClickListener { onSaveAssistantMessage?.invoke(rawResponse) }
        }

        fun bind(turn: ChatTurn) {
            val context = binding.root.context
            val isLast = bindingAdapterPosition == itemCount - 1
            val pending = turn.jarvisResponse.isBlank() && turn.userTranscript.isNotBlank() &&
                isLast && System.currentTimeMillis() - turn.timestamp < 180_000L
            binding.layoutThinking.visibility = if (pending) View.VISIBLE else View.GONE
            if (pending) {
                binding.thinkingIndicator.startTimeMs = turn.timestamp
            }
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

            renderAssistantContent(turn, isStreaming = false)
        }

        private fun copyToClipboard(context: Context, label: String, text: String) {
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(context, "Couldn't copy this message", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
