package com.jarvis.assistant.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.animation.LinearInterpolator
import android.widget.TextView
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
    private val onOpenAttachment: ((String, String, String) -> Unit)? = null
) : ListAdapter<ChatTurn, JarvisChatAdapter.MessageViewHolder>(DiffCallback) {

    private var speechEngine: TextToSpeech? = null
    private var speechReady = false
    @Volatile private var speaking = false
    private var pendingSpeech = ""

    /** Turns whose reveal already played, and turns seen waiting for an answer (so a slow reply still animates). */
    private val revealed = HashSet<String>()
    private val awaiting = HashSet<String>()

    private fun animationsEnabled(context: Context): Boolean = try {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    } catch (_: Exception) { true }

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

    override fun onViewRecycled(holder: MessageViewHolder) {
        holder.cancelReveal()
        super.onViewRecycled(holder)
    }

    inner class MessageViewHolder(
        private val binding: ItemNairaChatMessageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var revealAnimator: ValueAnimator? = null

        fun cancelReveal() {
            revealAnimator?.cancel()
            revealAnimator = null
        }

        /** Letters appear word by word. The full text is laid out up front, so nothing jumps while it plays. */
        private fun playReveal(text: CharSequence) {
            val tv: TextView = binding.tvAssistantMessage
            tv.setText(SpannableString(text), TextView.BufferType.SPANNABLE)
            val spannable = tv.text as Spannable
            val hide = ForegroundColorSpan(Color.TRANSPARENT)
            val length = text.length
            spannable.setSpan(hide, 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            fun showAll() { spannable.removeSpan(hide) }
            revealAnimator = ValueAnimator.ofInt(0, length).apply {
                duration = (length * 14L).coerceIn(350L, 2400L)
                interpolator = LinearInterpolator()
                addUpdateListener {
                    var start = it.animatedValue as Int
                    while (start < length && !text[start].isWhitespace()) start++
                    if (start >= length) showAll() else spannable.setSpan(hide, start, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) { showAll() }
                    override fun onAnimationCancel(animation: Animator) { showAll() }
                })
                start()
            }
        }

        fun bind(turn: ChatTurn) {
            val context = binding.root.context
            cancelReveal()
            val isLast = bindingAdapterPosition == itemCount - 1
            val recent = System.currentTimeMillis() - turn.timestamp < 15_000L
            val pending = turn.jarvisResponse.isBlank() && turn.userTranscript.isNotBlank() &&
                isLast && System.currentTimeMillis() - turn.timestamp < 180_000L
            binding.layoutThinking.visibility = if (pending) View.VISIBLE else View.GONE
            if (pending) {
                binding.thinkingIndicator.startTimeMs = turn.timestamp
                awaiting.add(turn.id)
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

            if (turn.jarvisResponse.isNotBlank()) {
                binding.layoutAssistantContainer.visibility = View.VISIBLE
                val shouldReveal = isLast && turn.id !in revealed && (turn.id in awaiting || recent) &&
                    turn.jarvisResponse.length > 1 && animationsEnabled(context)
                revealed.add(turn.id)
                awaiting.remove(turn.id)
                val rendered: CharSequence = try {
                    MarkdownRenderer.render(turn.jarvisResponse, context.resources.displayMetrics.density)
                } catch (_: Throwable) {
                    turn.jarvisResponse // never let odd markdown take the chat down
                }
                if (shouldReveal) playReveal(rendered) else binding.tvAssistantMessage.text = rendered
                binding.btnAssistantCopy.setOnClickListener { copyToClipboard(context, "Jarvis response", turn.jarvisResponse) }
                binding.btnAssistantPlay.setOnClickListener {
                    try { speak(context, turn.jarvisResponse) } catch (_: Exception) {
                        Toast.makeText(context, "Couldn't read this aloud", Toast.LENGTH_SHORT).show()
                    }
                }
                binding.btnAssistantSave.setOnClickListener { onSaveAssistantMessage?.invoke(turn.jarvisResponse) }
            } else {
                binding.layoutAssistantContainer.visibility = View.GONE
            }
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
