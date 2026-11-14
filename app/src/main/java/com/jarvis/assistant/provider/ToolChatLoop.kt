package com.jarvis.assistant.provider

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.jarvis.assistant.tools.ToolBackend
import com.jarvis.assistant.tools.ToolCall
import com.jarvis.assistant.tools.ToolResult
import com.jarvis.assistant.tools.ToolSchemas
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class ToolChatResult(val text: String, val toolCalls: Int, val rounds: Int, val stoppedEarly: String?)

data class LiveToolStep(
    val toolName: String,
    val target: String,
    val language: String,
    val codeOrOutput: String,
    val isWriting: Boolean,
    val round: Int,
    val maxRounds: Int
)

/**
 * Lets a model use the phone-workspace tools: ask → run the tools it requested (through the permission-checked
 * [ToolBackend]) → give it the results → repeat until it answers in words. Bounded in rounds, in repeated identical
 * calls, and in result size, so a confused model can never loop forever.
 */
abstract class ToolChatLoop(protected val backend: ToolBackend, private val maxRounds: Int = 50, private val maxResultChars: Int = 32_000) {
    protected abstract suspend fun round(request: ChatRequest, state: Any?, results: List<Pair<ToolCall, String>>): Round
    protected abstract fun newState(request: ChatRequest): Any?
    protected class Round(val text: String, val calls: List<ToolCall>, val state: Any?)

    /** Receives the provider's own token count for every answer it sends back, including answers that are then retried. */
    var onUsage: ((TokenUsage) -> Unit)? = null

    protected fun report(usage: TokenUsage?) {
        if (usage != null) onUsage?.invoke(usage)
    }

    suspend fun run(
        request: ChatRequest,
        source: String,
        onActivity: (String) -> Unit = {}
    ): ToolChatResult = run(request, source, onActivity, onStep = {})

    suspend fun run(
        request: ChatRequest,
        source: String,
        onActivity: (String) -> Unit,
        onStep: (LiveToolStep) -> Unit
    ): ToolChatResult {
        var state = newState(request)
        var results: List<Pair<ToolCall, String>> = emptyList()
        var total = 0; val seen = HashMap<String, Int>(); val collected = StringBuilder()
        for (n in 1..maxRounds) {
            currentCoroutineContext().ensureActive()
            var r: Round? = null
            var roundAttempts = 0
            while (r == null) {
                try {
                    r = round(request, state, results)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    if (++roundAttempts >= 4) throw e
                    kotlinx.coroutines.delay(1000L * roundAttempts)
                }
            }
            state = r.state
            if (r.text.isNotBlank()) { if (collected.isNotEmpty()) collected.append("\n\n"); collected.append(r.text.trim()) }
            if (r.calls.isEmpty()) return ToolChatResult(collected.toString(), total, n, null)
            val out = ArrayList<Pair<ToolCall, String>>()
            for (c in r.calls.map { it.copy(source = source) }) {
                currentCoroutineContext().ensureActive()
                val key = c.name + c.args.toString()
                val times = (seen[key] ?: 0) + 1; seen[key] = times
                total++
                if (times > 2) { out += c to "Error: this exact call was already made $times times with the same arguments. Do not repeat it; answer with what you have or try something different."; continue }

                val target = when (c.name) {
                    "write_file", "read_file", "edit_file", "delete_path" -> c.args.get("path")?.takeIf { it.isJsonPrimitive }?.asString ?: "file"
                    "terminal_run" -> c.args.get("command")?.takeIf { it.isJsonPrimitive }?.asString ?: "terminal"
                    "make_directory" -> c.args.get("path")?.takeIf { it.isJsonPrimitive }?.asString ?: "folder"
                    "search_web", "web_search" -> c.args.get("query")?.takeIf { it.isJsonPrimitive }?.asString ?: "web search"
                    else -> c.name
                }
                val content = when (c.name) {
                    "write_file" -> c.args.get("content")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                    "edit_file" -> c.args.get("replacement")?.takeIf { it.isJsonPrimitive }?.asString ?: c.args.get("content")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                    "terminal_run" -> "$ $target"
                    "search_web", "web_search" -> "Searching: $target…"
                    "read_file" -> "Reading $target…"
                    else -> ""
                }
                val lang = when (c.name) {
                    "terminal_run" -> "terminal"
                    "search_web", "web_search" -> "search"
                    "read_file" -> "read"
                    "edit_file" -> "edit"
                    else -> target.substringAfterLast('.', "code")
                }

                onStep(LiveToolStep(c.name, target, lang, content, isWriting = true, n, maxRounds))
                val activityStart = when (c.name) {
                    "write_file" -> "Creating $target…"
                    "edit_file" -> "Editing $target…"
                    "terminal_run" -> "Terminal: $target…"
                    "search_web", "web_search" -> "Searching web: $target…"
                    "read_file" -> "Reading $target…"
                    else -> "Using ${c.name}…"
                }
                val activityLabel = if (c.name == "write_file") "Using write_file: Creating $target…" else activityStart
                onActivity(activityLabel)

                val res = try { backend.execute(c) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { ToolResult.Error("${e.javaClass.simpleName}: ${e.message?.take(120)}") }

                val finalOutput = when (c.name) {
                    "terminal_run" -> "$ $target\n${res.text}"
                    "search_web", "web_search" -> "Query: $target\n\n${res.text}"
                    "read_file" -> res.text
                    "edit_file" -> if (content.isNotBlank()) content else res.text
                    else -> content
                }
                onStep(LiveToolStep(c.name, target, lang, finalOutput, isWriting = false, n, maxRounds))
                val activityDone = when (c.name) {
                    "write_file" -> "Created $target"
                    "edit_file" -> "Updated $target"
                    "terminal_run" -> "Finished: $target"
                    "search_web", "web_search" -> "Retrieved: $target"
                    "read_file" -> "Read: $target"
                    else -> "${c.name} finished"
                }
                onActivity(activityDone)

                out += c to clip(res.text)
            }
            results = out
        }
        return ToolChatResult(collected.toString(), total, maxRounds, "Completed up to round limit ($maxRounds steps). All generated code and files are saved in the workspace.")
    }

    private fun clip(s: String) = if (s.length <= maxResultChars) s else s.take(maxResultChars) + "\n… (${s.length - maxResultChars} more characters were not sent to the model)"

    private fun ToolCall.copy(source: String) = ToolCall(id, name, args, source)
}

/** OpenAI-compatible tool calling: `tools` + `tool_calls` + `role: tool` messages, one non-streaming request per round. */
class OpenAiToolLoop(private val transport: HttpTransport, backend: ToolBackend, maxRounds: Int = 50, maxResultChars: Int = 32_000) : ToolChatLoop(backend, maxRounds, maxResultChars) {
    private class State(val messages: JsonArray)

    override fun newState(request: ChatRequest): Any? {
        val m = JsonArray()
        if (request.system.isNotBlank()) m.add(JsonObject().apply { addProperty("role", "system"); addProperty("content", request.system) })
        for (x in request.messages) m.add(JsonObject().apply { addProperty("role", if (x.role == ChatRole.USER) "user" else "assistant"); addProperty("content", x.text) })
        return State(m)
    }

    override suspend fun round(request: ChatRequest, state: Any?, results: List<Pair<ToolCall, String>>): Round {
        val st = state as State
        for ((c, text) in results) st.messages.add(JsonObject().apply {
            addProperty("role", "tool")
            addProperty("tool_call_id", c.id)
            addProperty("name", c.name)
            addProperty("content", text)
        })
        val p = request.provider
        val base = (ProviderUrl.validateBase(p.baseUrl) as? ProviderUrl.Check.Ok)?.url ?: throw ProviderException(ProviderError(ProviderErrorKind.INVALID_ENDPOINT, "The provider's endpoint URL is not valid."))
        val body = JsonObject().apply {
            addProperty("model", p.modelId); add("messages", st.messages)
            add("tools", ToolSchemas.toOpenAi(backend.specs())); addProperty("tool_choice", "auto")
            p.temperature?.let { addProperty("temperature", it) }
        }
        val spec = prepare(HttpRequestSpec("POST", ProviderUrl.join(base, "chat/completions"), mapOf("Accept" to "application/json"), HttpBody.Text(JSON_TYPE, body.toString()), 120_000L), p)
        val resp = try { transport.execute(spec) } catch (e: TransportException) { throw ProviderException(ProviderErrors.fromTransport(e)) }
        if (!resp.isSuccessful) throw ProviderException(ProviderErrors.fromHttp(resp.code, resp.body, secretOf(p), ErrorContext.CHAT, resp.headers.entries.firstOrNull { it.key.equals("retry-after", true) }?.value))
        val root = parseObject(resp.body) ?: throw ProviderException(ProviderErrors.invalidResponse("Expected a JSON chat completion."))
        report(TokenUsageParser.openAi(root))
        val msg = root.getAsJsonArrayOrNull("choices")?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject?.getAsJsonObjectOrNull("message")
            ?: throw ProviderException(ProviderErrors.invalidResponse("The answer had no choices."))
        val content = msg.get("content")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val calls = ToolSchemas.parseOpenAiCalls(msg, "custom-api")
        // Echo the assistant turn (with its tool calls) back so the provider can match the results to it.
        st.messages.add(JsonObject().apply {
            addProperty("role", "assistant")
            if (content.isNotEmpty()) addProperty("content", content) else add("content", com.google.gson.JsonNull.INSTANCE)
            msg.get("tool_calls")?.let { add("tool_calls", it) }
            msg.get("function_call")?.let { add("function_call", it) }
        })
        if (content.isBlank() && calls.isEmpty()) throw ProviderException(ProviderErrors.invalidResponse("The model returned an empty answer."))
        return Round(content, calls, st)
    }
}

/** Gemini function calling over generateContent: `functionDeclarations`, `functionCall` parts, `functionResponse` parts. */
class GeminiToolLoop(private val transport: HttpTransport, backend: ToolBackend, maxRounds: Int = 50, maxResultChars: Int = 32_000) : ToolChatLoop(backend, maxRounds, maxResultChars) {
    private class State(val contents: JsonArray)

    /** Set when the model refused a thinking setting, so later rounds are sent without it. */
    @Volatile private var thinkingRejected = false

    override fun newState(request: ChatRequest): Any? {
        val c = JsonArray()
        for (x in request.messages) c.add(JsonObject().apply {
            addProperty("role", if (x.role == ChatRole.USER) "user" else "model")
            add("parts", JsonArray().apply { add(JsonObject().apply { addProperty("text", x.text.ifBlank { " " }) }) })
        })
        return State(c)
    }

    override suspend fun round(request: ChatRequest, state: Any?, results: List<Pair<ToolCall, String>>): Round {
        val st = state as State
        if (results.isNotEmpty()) st.contents.add(JsonObject().apply {
            addProperty("role", "user")
            add("parts", JsonArray().apply { results.forEach { (c, text) -> add(JsonObject().apply { add("functionResponse", JsonObject().apply { addProperty("name", c.name); add("response", JsonObject().apply { addProperty("result", text) }) }) }) } })
        })
        val p = request.provider
        val base = (ProviderUrl.validateBase(p.baseUrl) as? ProviderUrl.Check.Ok)?.url ?: throw ProviderException(ProviderError(ProviderErrorKind.INVALID_ENDPOINT, "The provider's endpoint URL is not valid."))
        val tools = JsonArray().apply { add(JsonObject().apply { add("functionDeclarations", ToolSchemas.toGemini(backend.specs())) }) }
        fun bodyJson(withThinking: Boolean) = JsonObject().apply {
            if (request.system.isNotBlank()) add("systemInstruction", JsonObject().apply { add("parts", JsonArray().apply { add(JsonObject().apply { addProperty("text", request.system) }) }) })
            add("contents", st.contents)
            add("tools", tools)
            if (withThinking) GeminiThinking.config(p.modelId, request.thinking)?.let { add("generationConfig", JsonObject().apply { add("thinkingConfig", it) }) }
        }
        val url = ProviderUrl.join(base, "${ProviderUrl.geminiModelName(p.modelId)}:generateContent")
        suspend fun send(withThinking: Boolean): HttpResponse {
            val spec = prepare(HttpRequestSpec("POST", url, mapOf("Accept" to "application/json"), HttpBody.Text(JSON_TYPE, bodyJson(withThinking).toString()), 120_000L), p)
            return try { transport.execute(spec) } catch (e: TransportException) { throw ProviderException(ProviderErrors.fromTransport(e)) }
        }
        var resp = send(!thinkingRejected)
        if (!thinkingRejected && !resp.isSuccessful && GeminiThinking.isRejection(resp.code, resp.body)) {
            thinkingRejected = true
            resp = send(false)
        }
        if (!resp.isSuccessful) throw ProviderException(ProviderErrors.fromHttp(resp.code, resp.body, secretOf(p), ErrorContext.CHAT, resp.headers.entries.firstOrNull { it.key.equals("retry-after", true) }?.value))
        val root = parseObject(resp.body) ?: throw ProviderException(ProviderErrors.invalidResponse("Expected a JSON answer."))
        report(TokenUsageParser.gemini(root))
        val content = root.getAsJsonArrayOrNull("candidates")?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject?.getAsJsonObjectOrNull("content")
        val parts = content?.getAsJsonArrayOrNull("parts") ?: JsonArray()
        val text = StringBuilder(); val fcs = JsonArray()
        for (part in parts) { val o = part.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            if (o.has("text") && !(o.get("thought")?.asBoolean ?: false)) text.append(o.get("text").asString)
            o.getAsJsonObjectOrNull("functionCall")?.let { fcs.add(it) } }
        val calls = ToolSchemas.parseGeminiCalls(fcs, "gemini-chat")
        if (text.isBlank() && calls.isEmpty()) throw ProviderException(ProviderErrors.invalidResponse("The model returned no answer (it may have been blocked)."))
        // Keep the model's own turn (including its function calls) in the conversation.
        content?.let { st.contents.add(it) }
        return Round(text.toString(), calls, st)
    }
}
