package com.jarvis.assistant.provider

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What a provider says it counted (and bills) for one request. [total] is the provider's own total when it reports
 * one, otherwise input + output, so the chat counter can match the provider dashboard.
 */
data class TokenUsage(val input: Long, val output: Long, val total: Long)

/** Reads the usage block of OpenAI-compatible and Gemini answers. Returns null when the answer carries no usage. */
object TokenUsageParser {

    /** OpenAI-compatible `usage` (also Groq's `x_groq.usage` and gateways that use input_tokens/output_tokens). */
    fun openAi(root: JsonObject?): TokenUsage? {
        if (root == null) return null
        val usage = root.objectOrNull("usage") ?: root.objectOrNull("x_groq")?.objectOrNull("usage") ?: return null
        val prompt = usage.numberOrNull("prompt_tokens") ?: usage.numberOrNull("input_tokens")
        val completion = usage.numberOrNull("completion_tokens") ?: usage.numberOrNull("output_tokens")
        val reported = usage.numberOrNull("total_tokens")
        if (prompt == null && completion == null && reported == null) return null
        val input = prompt ?: 0L
        val output = completion ?: 0L
        return TokenUsage(input, output, reported?.takeIf { it > 0L } ?: (input + output))
    }

    /** Same as [openAi], for a raw JSON body. Returns null for anything that is not a JSON object. */
    fun openAiFromText(body: String): TokenUsage? = openAi(parseOrNull(body))

    /**
     * Gemini `usageMetadata`. `totalTokenCount` already includes thinking and tool-use prompt tokens, so it is used
     * as the total whenever the provider sends it. In a stream every chunk repeats the running figure; the last one wins.
     */
    fun gemini(root: JsonObject?): TokenUsage? {
        if (root == null) return null
        val meta = root.objectOrNull("usageMetadata") ?: return null
        val prompt = meta.numberOrNull("promptTokenCount")
        val candidates = meta.numberOrNull("candidatesTokenCount")
        val thoughts = meta.numberOrNull("thoughtsTokenCount")
        val toolUse = meta.numberOrNull("toolUsePromptTokenCount")
        val reported = meta.numberOrNull("totalTokenCount")
        if (prompt == null && candidates == null && thoughts == null && toolUse == null && reported == null) return null
        val input = (prompt ?: 0L) + (toolUse ?: 0L)
        val output = (candidates ?: 0L) + (thoughts ?: 0L)
        return TokenUsage(input, output, reported?.takeIf { it > 0L } ?: (input + output))
    }

    private fun parseOrNull(text: String): JsonObject? = try {
        val e = JsonParser.parseString(text)
        if (e.isJsonObject) e.asJsonObject else null
    } catch (e: Exception) {
        null
    }

    private fun JsonObject.objectOrNull(name: String): JsonObject? {
        val e = get(name) ?: return null
        return if (e.isJsonObject) e.asJsonObject else null
    }

    private fun JsonObject.numberOrNull(name: String): Long? {
        val e = get(name) ?: return null
        if (!e.isJsonPrimitive) return null
        val p = e.asJsonPrimitive
        return when {
            p.isNumber -> p.asLong
            p.isString -> p.asString.trim().toLongOrNull()
            else -> null
        }?.takeIf { it >= 0L }
    }
}

/** A rough size for text when a provider reports no usage. About four characters or three quarters of a word per token. */
object TokenEstimator {
    fun estimate(text: String?): Int {
        if (text.isNullOrBlank()) return 0
        val chars = text.length
        val words = text.trim().split(Regex("\\s+")).size
        return maxOf((words * 1.33f).toInt(), (chars / 3.8f).toInt()).coerceAtLeast(1)
    }
}

/**
 * Running token total of the current chat: the sum of what the provider reported for every request, input and output
 * together. Each request resends the history, so this is the sum of requests, not the length of the conversation.
 *
 * The total lives in a [Store] (the app plugs in its preferences) so it survives a restart and is cleared with the chat.
 * An answer from a provider that reports no usage is added as an estimate and the total is then flagged approximate.
 */
object ChatTokenLedger {

    interface Store {
        var total: Int
        var approximate: Boolean
    }

    data class Snapshot(val total: Int, val approximate: Boolean)

    private val lock = Any()
    @Volatile private var store: Store? = null
    private val _state = MutableStateFlow(Snapshot(0, false))
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    /** Connects the persistent store and loads whatever total it already holds. */
    fun attach(newStore: Store) {
        synchronized(lock) {
            store = newStore
            _state.value = Snapshot(newStore.total.coerceAtLeast(0), newStore.approximate)
        }
    }

    /** Re-reads the store, for when the chat was cleared elsewhere. */
    fun refresh() {
        synchronized(lock) {
            val s = store ?: return
            _state.value = Snapshot(s.total.coerceAtLeast(0), s.approximate)
        }
    }

    /** Adds exactly what the provider counted for one request. */
    fun record(usage: TokenUsage) = add(usage.total, estimated = false)

    /** Adds an estimate for a request whose provider reported no usage. */
    fun recordEstimate(tokens: Int) = add(tokens.toLong(), estimated = true)

    /** Starts a fresh count for a new chat. */
    fun reset() {
        synchronized(lock) {
            store?.let { it.total = 0; it.approximate = false }
            _state.value = Snapshot(0, false)
        }
    }

    private fun add(tokens: Long, estimated: Boolean) {
        if (tokens <= 0L) return
        synchronized(lock) {
            val current = store?.let { Snapshot(it.total.coerceAtLeast(0), it.approximate) } ?: _state.value
            val next = (current.total.toLong() + tokens).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val approximate = current.approximate || estimated
            store?.let { it.total = next; it.approximate = approximate }
            _state.value = Snapshot(next, approximate)
        }
    }
}
