package com.jarvis.assistant.provider

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

internal data class ClozedToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject
)

internal data class ClozedToolCall(
    val id: String,
    val name: String,
    val arguments: String
)

internal sealed class ClozedMessage {
    data class User(val text: String) : ClozedMessage()
    data class Assistant(val text: String, val calls: List<ClozedToolCall>) : ClozedMessage()
    data class ToolResult(val callId: String, val name: String, val text: String) : ClozedMessage()
}

internal data class ClozedCompletion(
    val text: String,
    val reasoning: String,
    val calls: List<ClozedToolCall>
)

/**
 * Coding-agent dialect adapter. All network I/O goes through HttpTransport, so redirects, cancellation,
 * timeout handling and secret-safe transport errors have one implementation in provider/.
 */
internal class ClozedLlm(private val transport: HttpTransport) {

    suspend fun complete(
        provider: ResolvedProvider,
        system: String,
        messages: List<ClozedMessage>,
        tools: List<ClozedToolDefinition>,
        onText: (String) -> Unit,
        onReasoning: (String) -> Unit,
        reasoningEffort: String? = null
    ): ClozedCompletion {
        val anthropic = provider.baseUrl.contains("api.anthropic.com", ignoreCase = true)
        val rawBase = (ProviderUrl.validateBase(provider.baseUrl) as? ProviderUrl.Check.Ok)?.url
            ?: throw ClozedLlmException("The provider's endpoint URL is not valid.", 0)
        val url = endpoint(rawBase, anthropic)
        val headers = linkedMapOf(
            "Content-Type" to "application/json",
            "Accept" to "text/event-stream",
            "User-Agent" to "JARVIS-Coding-Agent"
        )
        if (anthropic) headers["anthropic-version"] = "2023-06-01"
        val body = if (anthropic) anthropicBody(provider, system, messages, tools)
            else openAiBody(provider, system, messages, tools, reasoningEffort)
        val raw = HttpRequestSpec(
            "POST",
            url,
            headers,
            HttpBody.Text("application/json; charset=utf-8", body.toString()),
            300_000L
        )
        val authenticated = try {
            val withAuth = if (anthropic && provider.auth.method != AuthMethod.QUERY_PARAM) {
                ProviderAuth.apply(raw, AuthSpec(AuthMethod.X_API_KEY, secret = provider.auth.secret))
            } else {
                ProviderAuth.apply(raw, provider.auth)
            }
            ProviderAuth.withExtraHeaders(withAuth, provider.extraHeaders)
        } catch (error: ProviderException) {
            throw ClozedLlmException(error.message ?: "Provider authentication is not configured.", 0)
        }

        return try {
            if (anthropic) parseAnthropic(transport.streamLines(authenticated), onText, onReasoning)
            else parseOpenAi(transport.streamLines(authenticated), onText, onReasoning)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: HttpStatusException) {
            val secret = provider.auth.secret?.takeIf { !it.isBlank }?.reveal()
            throw ClozedLlmException(
                errorMessage(error.code, error.body, secret.orEmpty()),
                error.code,
                error.retryAfter,
                ProviderAuth.redact(error.body, secret)
            )
        } catch (error: TransportException) {
            throw ClozedLlmException(error.message ?: "Network request failed.", 0)
        } catch (error: ClozedLlmException) {
            throw error
        } catch (error: Exception) {
            throw ClozedLlmException(error.message ?: "The provider returned an invalid response.", 0)
        }
    }

    private fun endpoint(base: String, anthropic: Boolean): String {
        val trimmed = base.trimEnd('/')
        return if (anthropic) {
            when {
                trimmed.endsWith("/messages") -> trimmed
                trimmed.endsWith("/v1") -> "$trimmed/messages"
                else -> "$trimmed/v1/messages"
            }
        } else {
            if (trimmed.endsWith("/chat/completions")) trimmed else "$trimmed/chat/completions"
        }
    }

    internal fun openAiBody(
        provider: ResolvedProvider,
        system: String,
        messages: List<ClozedMessage>,
        tools: List<ClozedToolDefinition>,
        reasoningEffort: String?
    ): JsonObject {
        val body = JsonObject()
        body.addProperty("model", provider.modelId)
        reasoningEffort?.takeIf { it in setOf("low", "medium", "high") }?.let { body.addProperty("reasoning_effort", it) }
        body.addProperty("stream", true)
        body.addProperty("max_tokens", provider.maxTokens ?: 8192)
        val all = JsonArray()
        all.add(JsonObject().apply { addProperty("role", "system"); addProperty("content", system) })
        messages.forEach { message ->
            when (message) {
                is ClozedMessage.User -> all.add(JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", message.text)
                })
                is ClozedMessage.Assistant -> {
                    val item = JsonObject().apply {
                        addProperty("role", "assistant")
                        if (message.text.isBlank() && message.calls.isNotEmpty()) add("content", com.google.gson.JsonNull.INSTANCE)
                        else addProperty("content", message.text)
                    }
                    if (message.calls.isNotEmpty()) {
                        val calls = JsonArray()
                        message.calls.forEach { call ->
                            calls.add(JsonObject().apply {
                                addProperty("id", call.id)
                                addProperty("type", "function")
                                add("function", JsonObject().apply {
                                    addProperty("name", call.name)
                                    addProperty("arguments", call.arguments)
                                })
                            })
                        }
                        item.add("tool_calls", calls)
                    }
                    all.add(item)
                }
                is ClozedMessage.ToolResult -> all.add(JsonObject().apply {
                    addProperty("role", "tool")
                    addProperty("tool_call_id", message.callId)
                    addProperty("content", message.text)
                })
            }
        }
        body.add("messages", all)
        if (tools.isNotEmpty()) {
            val definitions = JsonArray()
            tools.forEach { tool ->
                definitions.add(JsonObject().apply {
                    addProperty("type", "function")
                    add("function", JsonObject().apply {
                        addProperty("name", tool.name)
                        addProperty("description", tool.description)
                        add("parameters", tool.parameters)
                    })
                })
            }
            body.add("tools", definitions)
        }
        return body
    }

    internal fun anthropicBody(
        provider: ResolvedProvider,
        system: String,
        messages: List<ClozedMessage>,
        tools: List<ClozedToolDefinition>
    ): JsonObject {
        val body = JsonObject()
        body.addProperty("model", provider.modelId)
        body.addProperty("max_tokens", provider.maxTokens ?: 8192)
        body.addProperty("stream", true)
        body.addProperty("system", system)
        val out = JsonArray()
        var pendingResults = JsonArray()
        fun flushResults() {
            if (pendingResults.size() > 0) {
                out.add(JsonObject().apply { addProperty("role", "user"); add("content", pendingResults) })
                pendingResults = JsonArray()
            }
        }
        messages.forEach { message ->
            when (message) {
                is ClozedMessage.User -> {
                    flushResults()
                    out.add(JsonObject().apply { addProperty("role", "user"); addProperty("content", message.text) })
                }
                is ClozedMessage.Assistant -> {
                    flushResults()
                    val blocks = JsonArray()
                    if (message.text.isNotBlank()) blocks.add(JsonObject().apply {
                        addProperty("type", "text")
                        addProperty("text", message.text)
                    })
                    message.calls.forEach { call ->
                        val input = try {
                            JsonParser.parseString(call.arguments).takeIf { it.isJsonObject }?.asJsonObject
                                ?: throw IllegalArgumentException("Tool arguments must be a JSON object.")
                        } catch (error: Exception) {
                            throw ClozedLlmException("Tool arguments for ${call.name} are not valid JSON.", 0)
                        }
                        blocks.add(JsonObject().apply {
                            addProperty("type", "tool_use")
                            addProperty("id", call.id)
                            addProperty("name", call.name)
                            add("input", input)
                        })
                    }
                    out.add(JsonObject().apply { addProperty("role", "assistant"); add("content", blocks) })
                }
                is ClozedMessage.ToolResult -> pendingResults.add(JsonObject().apply {
                    addProperty("type", "tool_result")
                    addProperty("tool_use_id", message.callId)
                    addProperty("content", message.text)
                })
            }
        }
        flushResults()
        body.add("messages", out)
        if (tools.isNotEmpty()) {
            val definitions = JsonArray()
            tools.forEach { tool ->
                definitions.add(JsonObject().apply {
                    addProperty("name", tool.name)
                    addProperty("description", tool.description)
                    add("input_schema", tool.parameters)
                })
            }
            body.add("tools", definitions)
        }
        return body
    }

    internal suspend fun parseOpenAi(
        lines: Flow<String>,
        onText: (String) -> Unit,
        onReasoning: (String) -> Unit
    ): ClozedCompletion {
        val text = StringBuilder()
        val reasoning = StringBuilder()
        data class Acc(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder())
        val calls = linkedMapOf<Int, Acc>()
        lines.collect { raw ->
            val line = raw.trim()
            if (!line.startsWith("data:")) return@collect
            val data = line.removePrefix("data:").trim()
            if (data.isBlank() || data == "[DONE]") return@collect
            val root = try { JsonParser.parseString(data).asJsonObject } catch (_: Exception) { return@collect }
            val delta = root.getAsJsonArray("choices")?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
                ?.getAsJsonObject("delta") ?: return@collect
            delta.get("content")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotEmpty() }?.let {
                text.append(it); onText(it)
            }
            delta.get("reasoning_content")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotEmpty() }?.let {
                reasoning.append(it); onReasoning(it)
            }
            delta.getAsJsonArray("tool_calls")?.forEach { item ->
                if (!item.isJsonObject) return@forEach
                val call = item.asJsonObject
                val index = call.get("index")?.asInt ?: 0
                val acc = calls.getOrPut(index) { Acc() }
                call.get("id")?.takeIf { it.isJsonPrimitive }?.asString?.let { if (it.isNotBlank()) acc.id = it }
                call.getAsJsonObject("function")?.let { fn ->
                    fn.get("name")?.takeIf { it.isJsonPrimitive }?.asString?.let { acc.name += it }
                    fn.get("arguments")?.takeIf { it.isJsonPrimitive }?.asString?.let { acc.args.append(it) }
                }
            }
        }
        val finished = calls.values.mapIndexedNotNull { index, call ->
            if (call.name.isBlank()) null else ClozedToolCall(call.id.ifBlank { "call_$index" }, call.name, call.args.toString())
        }
        return ClozedCompletion(text.toString(), reasoning.toString(), finished)
    }

    internal suspend fun parseAnthropic(
        lines: Flow<String>,
        onText: (String) -> Unit,
        onReasoning: (String) -> Unit
    ): ClozedCompletion {
        val text = StringBuilder()
        val reasoning = StringBuilder()
        val calls = mutableListOf<ClozedToolCall>()
        var activeIndex = -1
        lines.collect { raw ->
            val line = raw.trim()
            if (!line.startsWith("data:")) return@collect
            val data = line.removePrefix("data:").trim()
            if (data.isBlank() || data == "[DONE]") return@collect
            val root = try { JsonParser.parseString(data).asJsonObject } catch (_: Exception) { return@collect }
            when (root.get("type")?.asString.orEmpty()) {
                "content_block_start" -> {
                    val block = root.getAsJsonObject("content_block") ?: return@collect
                    if (block.get("type")?.asString == "tool_use") {
                        activeIndex = calls.size
                        calls += ClozedToolCall(
                            block.get("id")?.asString.orEmpty(),
                            block.get("name")?.asString.orEmpty(),
                            block.get("input")?.takeIf { it.isJsonObject }?.asJsonObject?.toString()?.takeIf { it != "{}" }.orEmpty()
                        )
                    }
                }
                "content_block_delta" -> {
                    val delta = root.getAsJsonObject("delta") ?: return@collect
                    when (delta.get("type")?.asString.orEmpty()) {
                        "text_delta" -> delta.get("text")?.asString?.let { text.append(it); onText(it) }
                        "thinking_delta" -> delta.get("thinking")?.asString?.let { reasoning.append(it); onReasoning(it) }
                        "input_json_delta" -> {
                            if (activeIndex in calls.indices) {
                                val old = calls[activeIndex]
                                calls[activeIndex] = old.copy(arguments = old.arguments + delta.get("partial_json")?.asString.orEmpty())
                            }
                        }
                    }
                }
            }
        }
        return ClozedCompletion(text.toString(), reasoning.toString(), calls)
    }

    private fun errorMessage(code: Int, body: String, secret: String): String = when (code) {
        401 -> "HTTP 401: invalid or missing API key"
        402 -> "HTTP 402: payment required"
        403 -> "HTTP 403: the provider refused this request"
        404 -> "HTTP 404: model or endpoint not found"
        429 -> "HTTP 429: rate limited"
        else -> "HTTP $code: ${ProviderAuth.redact(body.take(300), secret)}"
    }
}

internal class ClozedLlmException(
    override val message: String,
    val statusCode: Int,
    val retryAfterHeader: String? = null,
    val responseBody: String = ""
) : Exception(message) {
    val retryable: Boolean get() = statusCode == 429 || statusCode in 500..599 || statusCode == 0
}
