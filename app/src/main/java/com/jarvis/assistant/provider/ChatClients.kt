package com.jarvis.assistant.provider

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

enum class ChatRole { USER, ASSISTANT }

sealed class ChatAttachment {
    class Image(val mimeType: String, val base64: String) : ChatAttachment()
    class Pdf(val fileName: String, val base64: String) : ChatAttachment()
}

class ChatMessage(val role: ChatRole, val text: String, val attachments: List<ChatAttachment> = emptyList())

sealed class ChatEvent {
    class Delta(val text: String) : ChatEvent()
    object Done : ChatEvent()
}

class ChatRequest(
    val provider: ResolvedProvider,
    val system: String,
    val messages: List<ChatMessage>,
    val maxTokens: Int? = null,
    /** Adds a harmless dummy tool so a probe can learn whether the model accepts tool definitions. */
    val includeProbeTool: Boolean = false
)

class ChatCompletion(val text: String, val hadToolCall: Boolean)

/** One implementation per wire protocol. Neither knows about screens or services. */
interface ChatProviderClient {
    fun stream(request: ChatRequest): Flow<ChatEvent>
    suspend fun complete(request: ChatRequest): ChatCompletion
}

object ChatClientFactory {
    fun create(protocol: ProviderProtocol, transport: HttpTransport): ChatProviderClient = when (protocol) {
        ProviderProtocol.OPENAI_COMPATIBLE -> OpenAiChatClient(transport)
        ProviderProtocol.GEMINI -> GeminiChatClient(transport)
    }
}

private const val STREAM_TIMEOUT_MS = 120_000L
private const val COMPLETE_TIMEOUT_MS = 60_000L
internal const val JSON_TYPE = "application/json; charset=utf-8"

internal fun parseObject(text: String): JsonObject? = try {
    val e = JsonParser.parseString(text)
    if (e.isJsonObject) e.asJsonObject else null
} catch (e: JsonParseException) {
    null
}

internal fun secretOf(provider: ResolvedProvider): String? = provider.auth.secret?.takeIf { !it.isBlank }?.reveal()

internal fun prepare(request: HttpRequestSpec, provider: ResolvedProvider): HttpRequestSpec =
    ProviderAuth.withExtraHeaders(ProviderAuth.apply(request, provider.auth), provider.extraHeaders)

private fun interrupted(): ProviderException = ProviderException(
    ProviderError(ProviderErrorKind.STREAM_INTERRUPTED, "The connection dropped while the answer was still arriving. What arrived is kept; tap Retry to ask again.")
)

// ---------------------------------------------------------------------------------------------------------
// OpenAI-compatible: POST {base}/chat/completions
// ---------------------------------------------------------------------------------------------------------

class OpenAiChatClient(private val transport: HttpTransport) : ChatProviderClient {

    private fun body(request: ChatRequest, stream: Boolean, tokenParam: String): String {
        val p = request.provider
        val messages = JsonArray()
        if (request.system.isNotBlank()) {
            messages.add(JsonObject().apply { addProperty("role", "system"); addProperty("content", request.system) })
        }
        for (m in request.messages) {
            val role = if (m.role == ChatRole.USER) "user" else "assistant"
            val obj = JsonObject().apply { addProperty("role", role) }
            if (m.attachments.isEmpty()) {
                obj.addProperty("content", m.text)
            } else {
                val parts = JsonArray()
                if (m.text.isNotBlank()) parts.add(JsonObject().apply { addProperty("type", "text"); addProperty("text", m.text) })
                for (a in m.attachments) {
                    when (a) {
                        is ChatAttachment.Image -> parts.add(JsonObject().apply {
                            addProperty("type", "image_url")
                            add("image_url", JsonObject().apply { addProperty("url", "data:${a.mimeType};base64,${a.base64}") })
                        })
                        is ChatAttachment.Pdf -> parts.add(JsonObject().apply {
                            addProperty("type", "file")
                            add("file", JsonObject().apply {
                                addProperty("filename", a.fileName)
                                addProperty("file_data", "data:application/pdf;base64,${a.base64}")
                            })
                        })
                    }
                }
                obj.add("content", parts)
            }
            messages.add(obj)
        }
        val root = JsonObject()
        root.addProperty("model", p.modelId)
        root.add("messages", messages)
        if (stream) root.addProperty("stream", true)
        p.temperature?.let { root.addProperty("temperature", it) }
        (request.maxTokens ?: p.maxTokens)?.let { root.addProperty(tokenParam, it) }
        if (request.includeProbeTool) {
            root.add("tools", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "function")
                    add("function", JsonObject().apply {
                        addProperty("name", "get_time")
                        addProperty("description", "Returns the current time.")
                        add("parameters", JsonObject().apply {
                            addProperty("type", "object")
                            add("properties", JsonObject())
                        })
                    })
                })
            })
            root.addProperty("tool_choice", "auto")
        }
        return root.toString()
    }

    private fun spec(request: ChatRequest, stream: Boolean, tokenParam: String): HttpRequestSpec {
        val base = (ProviderUrl.validateBase(request.provider.baseUrl) as? ProviderUrl.Check.Ok)?.url
            ?: throw ProviderException(ProviderError(ProviderErrorKind.INVALID_ENDPOINT, "The provider's endpoint URL is not valid."))
        val headers = HashMap<String, String>()
        headers["Accept"] = if (stream) "text/event-stream" else "application/json"
        val raw = HttpRequestSpec(
            "POST", ProviderUrl.join(base, "chat/completions"), headers,
            HttpBody.Text(JSON_TYPE, body(request, stream, tokenParam)),
            if (stream) STREAM_TIMEOUT_MS else COMPLETE_TIMEOUT_MS
        )
        return prepare(raw, request.provider)
    }

    override suspend fun complete(request: ChatRequest): ChatCompletion {
        val secret = secretOf(request.provider)
        var tokenParam = "max_tokens"
        var response = send(request, tokenParam)
        // Newer OpenAI models want max_completion_tokens. Retry once if the provider says so.
        if (response.code == 400 && response.body.contains("max_completion_tokens") && (request.maxTokens ?: request.provider.maxTokens) != null) {
            tokenParam = "max_completion_tokens"
            response = send(request, tokenParam)
        }
        if (!response.isSuccessful) throw ProviderException(ProviderErrors.fromHttp(response.code, response.body, secret, ErrorContext.CHAT, response.headers.entries.firstOrNull { it.key.equals("retry-after", true) }?.value))
        return parseCompletion(response.body)
    }

    private suspend fun send(request: ChatRequest, tokenParam: String): HttpResponse = try {
        transport.execute(spec(request, false, tokenParam))
    } catch (e: TransportException) {
        throw ProviderException(ProviderErrors.fromTransport(e))
    }

    internal fun parseCompletion(bodyText: String): ChatCompletion {
        val root = parseObject(bodyText)
            ?: throw ProviderException(ProviderErrors.invalidResponse("Expected a JSON chat completion."))
        if (root.has("error") && !root.get("error").isJsonNull) {
            throw ProviderException(ProviderErrors.fromHttp(400, bodyText, null, ErrorContext.CHAT))
        }
        val message = root.getAsJsonArrayOrNull("choices")?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
            ?.getAsJsonObjectOrNull("message")
            ?: throw ProviderException(ProviderErrors.invalidResponse("The answer had no choices."))
        val text = contentText(message.get("content"))
        val hadTool = message.getAsJsonArrayOrNull("tool_calls")?.size()?.let { it > 0 } == true
        if (text.isBlank() && !hadTool) {
            throw ProviderException(ProviderErrors.invalidResponse("The model returned an empty answer."))
        }
        return ChatCompletion(text, hadTool)
    }

    override fun stream(request: ChatRequest): Flow<ChatEvent> = flow {
        val secret = secretOf(request.provider)
        var emitted = false
        val rawOther = StringBuilder()
        var tokenParam = "max_tokens"
        var attempt = 0
        while (true) {
            attempt++
            try {
                transport.streamLines(spec(request, true, tokenParam)).collect { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith(":") || trimmed.startsWith("event:") || trimmed.startsWith("id:") || trimmed.startsWith("retry:")) {
                        return@collect
                    }
                    if (!trimmed.startsWith("data:")) {
                        if (rawOther.length < 200_000) rawOther.append(trimmed)
                        return@collect
                    }
                    val payload = trimmed.removePrefix("data:").trim()
                    if (payload == "[DONE]") return@collect
                    val obj = parseObject(payload) ?: return@collect
                    if (obj.has("error") && !obj.get("error").isJsonNull) {
                        throw ProviderException(ProviderErrors.fromHttp(400, payload, secret, ErrorContext.CHAT))
                    }
                    val choice = obj.getAsJsonArrayOrNull("choices")?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
                    val delta = choice?.getAsJsonObjectOrNull("delta")
                    val piece = delta?.let { contentText(it.get("content")) }.orEmpty()
                    if (piece.isNotEmpty()) {
                        emitted = true
                        emit(ChatEvent.Delta(piece))
                    }
                }
                break
            } catch (e: HttpStatusException) {
                if (attempt == 1 && !emitted && e.code == 400 && e.body.contains("max_completion_tokens") &&
                    (request.maxTokens ?: request.provider.maxTokens) != null
                ) {
                    tokenParam = "max_completion_tokens"
                    continue
                }
                throw ProviderException(ProviderErrors.fromHttp(e.code, e.body, secret, ErrorContext.CHAT, e.retryAfter))
            } catch (e: TransportException) {
                throw if (emitted) interrupted() else ProviderException(ProviderErrors.fromTransport(e))
            }
        }
        if (!emitted) {
            // Some gateways ignore stream=true and return one JSON document.
            val whole = rawOther.toString().takeIf { it.isNotBlank() }
            if (whole != null) {
                val completion = parseCompletion(whole)
                if (completion.text.isNotEmpty()) emit(ChatEvent.Delta(completion.text))
                emit(ChatEvent.Done)
                return@flow
            }
            throw ProviderException(ProviderErrors.invalidResponse("The model returned an empty answer."))
        }
        emit(ChatEvent.Done)
    }

    private fun contentText(content: JsonElement?): String {
        if (content == null || content.isJsonNull) return ""
        if (content.isJsonPrimitive) return content.asString
        if (content.isJsonArray) {
            return content.asJsonArray.joinToString("") { part ->
                if (part.isJsonObject) part.asJsonObject.stringOrNull("text").orEmpty() else ""
            }
        }
        return ""
    }
}

// ---------------------------------------------------------------------------------------------------------
// Gemini-compatible: POST {base}/models/{id}:streamGenerateContent?alt=sse
// ---------------------------------------------------------------------------------------------------------

class GeminiChatClient(private val transport: HttpTransport) : ChatProviderClient {

    private fun body(request: ChatRequest): String {
        val p = request.provider
        val contents = JsonArray()
        for (m in request.messages) {
            val parts = JsonArray()
            if (m.text.isNotBlank()) parts.add(JsonObject().apply { addProperty("text", m.text) })
            for (a in m.attachments) {
                when (a) {
                    is ChatAttachment.Image -> parts.add(inline(a.mimeType, a.base64))
                    is ChatAttachment.Pdf -> parts.add(inline("application/pdf", a.base64))
                }
            }
            if (parts.size() == 0) parts.add(JsonObject().apply { addProperty("text", " ") })
            contents.add(JsonObject().apply {
                addProperty("role", if (m.role == ChatRole.USER) "user" else "model")
                add("parts", parts)
            })
        }
        val root = JsonObject()
        if (request.system.isNotBlank()) {
            root.add("systemInstruction", JsonObject().apply {
                add("parts", JsonArray().apply { add(JsonObject().apply { addProperty("text", request.system) }) })
            })
        }
        root.add("contents", contents)
        val generation = JsonObject()
        p.temperature?.let { generation.addProperty("temperature", it) }
        (request.maxTokens ?: p.maxTokens)?.let { generation.addProperty("maxOutputTokens", it) }
        if (generation.size() > 0) root.add("generationConfig", generation)
        if (request.includeProbeTool) {
            root.add("tools", JsonArray().apply {
                add(JsonObject().apply {
                    add("functionDeclarations", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("name", "get_time")
                            addProperty("description", "Returns the current time.")
                        })
                    })
                })
            })
        }
        return root.toString()
    }

    private fun inline(mime: String, base64: String) = JsonObject().apply {
        add("inlineData", JsonObject().apply { addProperty("mimeType", mime); addProperty("data", base64) })
    }

    private fun spec(request: ChatRequest, stream: Boolean): HttpRequestSpec {
        val base = (ProviderUrl.validateBase(request.provider.baseUrl) as? ProviderUrl.Check.Ok)?.url
            ?: throw ProviderException(ProviderError(ProviderErrorKind.INVALID_ENDPOINT, "The provider's endpoint URL is not valid."))
        val method = if (stream) "streamGenerateContent" else "generateContent"
        val url = ProviderUrl.join(base, "${ProviderUrl.geminiModelName(request.provider.modelId)}:$method") + if (stream) "?alt=sse" else ""
        val headers = mapOf("Accept" to if (stream) "text/event-stream" else "application/json")
        return prepare(
            HttpRequestSpec("POST", url, headers, HttpBody.Text(JSON_TYPE, body(request)), if (stream) STREAM_TIMEOUT_MS else COMPLETE_TIMEOUT_MS),
            request.provider
        )
    }

    override suspend fun complete(request: ChatRequest): ChatCompletion {
        val secret = secretOf(request.provider)
        val response = try {
            transport.execute(spec(request, false))
        } catch (e: TransportException) {
            throw ProviderException(ProviderErrors.fromTransport(e))
        }
        if (!response.isSuccessful) throw ProviderException(ProviderErrors.fromHttp(response.code, response.body, secret, ErrorContext.CHAT, response.headers.entries.firstOrNull { it.key.equals("retry-after", true) }?.value))
        val root = parseObject(response.body) ?: throw ProviderException(ProviderErrors.invalidResponse("Expected a JSON answer."))
        val (text, tool) = extract(root)
        if (text.isBlank() && !tool) throw ProviderException(blockedOrEmpty(root))
        return ChatCompletion(text, tool)
    }

    override fun stream(request: ChatRequest): Flow<ChatEvent> = flow {
        val secret = secretOf(request.provider)
        var emitted = false
        var lastBlock: ProviderError? = null
        try {
            transport.streamLines(spec(request, true)).collect { line ->
                val trimmed = line.trim()
                if (!trimmed.startsWith("data:")) return@collect
                val payload = trimmed.removePrefix("data:").trim()
                if (payload.isEmpty() || payload == "[DONE]") return@collect
                val obj = parseObject(payload) ?: return@collect
                if (obj.has("error") && !obj.get("error").isJsonNull) {
                    throw ProviderException(ProviderErrors.fromHttp(400, payload, secret, ErrorContext.CHAT))
                }
                val (text, _) = extract(obj)
                if (text.isNotEmpty()) {
                    emitted = true
                    emit(ChatEvent.Delta(text))
                } else {
                    lastBlock = blockedOrEmpty(obj).takeIf { it.kind == ProviderErrorKind.BAD_REQUEST } ?: lastBlock
                }
            }
        } catch (e: HttpStatusException) {
            throw ProviderException(ProviderErrors.fromHttp(e.code, e.body, secret, ErrorContext.CHAT, e.retryAfter))
        } catch (e: TransportException) {
            throw if (emitted) interrupted() else ProviderException(ProviderErrors.fromTransport(e))
        }
        if (!emitted) throw ProviderException(lastBlock ?: ProviderErrors.invalidResponse("The model returned an empty answer."))
        emit(ChatEvent.Done)
    }

    /** Returns the visible text (skipping "thought" parts) and whether the model asked to call a function. */
    private fun extract(root: JsonObject): Pair<String, Boolean> {
        val candidate = root.getAsJsonArrayOrNull("candidates")?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
            ?: return "" to false
        val parts = candidate.getAsJsonObjectOrNull("content")?.getAsJsonArrayOrNull("parts") ?: return "" to false
        val sb = StringBuilder()
        var tool = false
        for (part in parts) {
            if (!part.isJsonObject) continue
            val o = part.asJsonObject
            if (o.boolOrNull("thought") == true) continue
            o.stringOrNull("text")?.let { sb.append(it) }
            if (o.has("functionCall")) tool = true
        }
        return sb.toString() to tool
    }

    private fun blockedOrEmpty(root: JsonObject): ProviderError {
        val block = root.getAsJsonObjectOrNull("promptFeedback")?.stringOrNull("blockReason")
        if (block != null) {
            return ProviderError(ProviderErrorKind.BAD_REQUEST, "The provider blocked this request ($block).")
        }
        val finish = root.getAsJsonArrayOrNull("candidates")?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject?.stringOrNull("finishReason")
        if (finish != null && finish != "STOP" && finish != "MAX_TOKENS") {
            return ProviderError(ProviderErrorKind.BAD_REQUEST, "The provider stopped without an answer ($finish).")
        }
        return ProviderErrors.invalidResponse("The model returned an empty answer.")
    }
}
