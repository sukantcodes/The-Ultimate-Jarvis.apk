package com.jarvis.assistant.ui.chat

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.jarvis.assistant.provider.AuthMethod
import com.jarvis.assistant.provider.ResolvedProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

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

internal class ClozedLlm {
    suspend fun complete(
        provider: ResolvedProvider,
        system: String,
        messages: List<ClozedMessage>,
        tools: List<ClozedToolDefinition>,
        onText: (String) -> Unit,
        onReasoning: (String) -> Unit,
        reasoningEffort: String? = null
    ): ClozedCompletion = withContext(Dispatchers.IO) {
        try {
        val anthropic = provider.baseUrl.contains("api.anthropic.com", ignoreCase = true)
        val url = authenticatedEndpoint(provider, endpoint(provider.baseUrl, anthropic))
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 300_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("User-Agent", "ClozedCode-User/1.0; +https://clozedcode.app")
            if (anthropic) {
                setRequestProperty("anthropic-version", "2023-06-01")
                if (provider.auth.method != AuthMethod.QUERY_PARAM) provider.auth.secret?.reveal()?.takeIf { it.isNotBlank() }?.let { setRequestProperty("x-api-key", it) }
            } else {
                applyOpenAiAuth(provider)
            }
            provider.extraHeaders.forEach { (key, value) ->
                if (key.isNotBlank() && value.isNotBlank() &&
                    !key.equals("Host", true) && !key.equals("Content-Length", true) &&
                    !key.equals("Connection", true)
                ) setRequestProperty(key, value)
            }
        }
        try {
            val body = if (anthropic) anthropicBody(provider, system, messages, tools) else openAiBody(provider, system, messages, tools, reasoningEffort)
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            if (code !in 200..299) {
                val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                throw ClozedLlmException(
                    errorMessage(code, responseText, provider.auth.secret?.reveal().orEmpty()),
                    code,
                    connection.getHeaderField("Retry-After"),
                    responseText
                )
            }
            stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                val lines = generateSequence { reader.readLine() }
                if (anthropic) parseAnthropic(lines, onText, onReasoning)
                else parseOpenAi(lines, onText, onReasoning)
            } ?: ClozedCompletion("", "", emptyList())
        } finally {
            connection.disconnect()
        }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: ClozedLlmException) {
            throw error
        } catch (error: Exception) {
            throw ClozedLlmException(error.message ?: "Network request failed.", 0)
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

    private fun authenticatedEndpoint(provider: ResolvedProvider, endpoint: String): String {
        if (provider.auth.method != AuthMethod.QUERY_PARAM) return endpoint
        val name = provider.auth.name.trim()
        val key = provider.auth.secret?.reveal().orEmpty()
        if (name.isBlank() || key.isBlank()) return endpoint
        val separator = if (endpoint.contains("?")) "&" else "?"
        return endpoint + separator +
            java.net.URLEncoder.encode(name, "UTF-8") + "=" +
            java.net.URLEncoder.encode(key, "UTF-8")
    }

    private fun HttpURLConnection.applyOpenAiAuth(provider: ResolvedProvider) {
        val key = provider.auth.secret?.reveal().orEmpty()
        when (provider.auth.method) {
            AuthMethod.NONE -> Unit
            AuthMethod.BEARER -> if (key.isNotBlank()) setRequestProperty("Authorization", "Bearer $key")
            AuthMethod.X_API_KEY -> if (key.isNotBlank()) setRequestProperty("x-api-key", key)
            AuthMethod.X_GOOG_API_KEY -> if (key.isNotBlank()) setRequestProperty("x-goog-api-key", key)
            AuthMethod.CUSTOM_HEADER -> if (key.isNotBlank() && provider.auth.name.isNotBlank()) setRequestProperty(provider.auth.name, key)
            AuthMethod.QUERY_PARAM -> Unit // Added to the endpoint URL before opening the connection.
        }
    }

    private fun openAiBody(
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
                        if (message.text.isBlank() && message.calls.isNotEmpty()) add("content", null)
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

    private fun anthropicBody(
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
                        val input = runCatching { JsonParser.parseString(call.arguments).asJsonObject }.getOrDefault(JsonObject())
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

    private fun parseOpenAi(
        lines: Sequence<String>,
        onText: (String) -> Unit,
        onReasoning: (String) -> Unit
    ): ClozedCompletion {
        val text = StringBuilder()
        val reasoning = StringBuilder()
        data class Acc(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder())
        val calls = linkedMapOf<Int, Acc>()
        lines.forEach { raw ->
            val line = raw.trim()
            if (!line.startsWith("data:")) return@forEach
            val data = line.removePrefix("data:").trim()
            if (data.isBlank() || data == "[DONE]") return@forEach
            val root = runCatching { JsonParser.parseString(data).asJsonObject }.getOrNull() ?: return@forEach
            val delta = root.getAsJsonArray("choices")?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
                ?.getAsJsonObject("delta") ?: return@forEach
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

    private fun parseAnthropic(
        lines: Sequence<String>,
        onText: (String) -> Unit,
        onReasoning: (String) -> Unit
    ): ClozedCompletion {
        val text = StringBuilder()
        val reasoning = StringBuilder()
        val calls = mutableListOf<ClozedToolCall>()
        var activeIndex = -1
        lines.forEach { raw ->
            val line = raw.trim()
            if (!line.startsWith("data:")) return@forEach
            val data = line.removePrefix("data:").trim()
            if (data.isBlank() || data == "[DONE]") return@forEach
            val root = runCatching { JsonParser.parseString(data).asJsonObject }.getOrNull() ?: return@forEach
            when (root.get("type")?.asString.orEmpty()) {
                "content_block_start" -> {
                    val block = root.getAsJsonObject("content_block") ?: return@forEach
                    if (block.get("type")?.asString == "tool_use") {
                        activeIndex = calls.size
                        calls += ClozedToolCall(
                            block.get("id")?.asString.orEmpty(),
                            block.get("name")?.asString.orEmpty(),
                            block.get("input")?.toString()?.takeIf { it != "{}" }.orEmpty()
                        )
                    }
                }
                "content_block_delta" -> {
                    val delta = root.getAsJsonObject("delta") ?: return@forEach
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
        else -> "HTTP $code: ${body.take(300).let { if (secret.isBlank()) it else it.replace(secret, "[REDACTED]") }}"
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
