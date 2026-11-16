package com.jarvis.assistant.tools.image

import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.jarvis.assistant.provider.HttpBody
import com.jarvis.assistant.provider.HttpRequestSpec
import com.jarvis.assistant.provider.HttpTransport
import com.jarvis.assistant.provider.TransportException
import com.jarvis.assistant.provider.TransportFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** What an image request produced: saved files, or a plain reason for the person. */
sealed class ImageOutcome {
    class Done(val files: List<File>, val note: String, val model: String) : ImageOutcome()
    class Failed(val message: String) : ImageOutcome()
}

/**
 * Makes and edits pictures with Gemini. Finds the right image model by asking the service which models can make
 * pictures (so it keeps working when model names change), tries them best-first, and saves every picture to
 * [outDir]. The API key never appears in a message or a log.
 */
class ImageService(
    private val transport: HttpTransport,
    private val apiKey: () -> String,
    private val outDir: File,
    private val preferredModel: () -> String = { "" },
    private val clock: () -> Long = System::currentTimeMillis
) {
    @Volatile private var discovered: List<String> = emptyList()
    @Volatile private var discoveredAt = 0L

    suspend fun run(prompt: String, inputs: List<ImageInput>, aspectRatio: String?, size: String?): ImageOutcome {
        val key = apiKey().trim()
        if (key.isEmpty()) return ImageOutcome.Failed("No Gemini API key is set. Add your Google AI Studio key in Settings, then try again.")
        if (prompt.isBlank()) return ImageOutcome.Failed("Describe the picture you want.")
        val body = ImageGenProtocol.requestBody(prompt.trim(), inputs, aspectRatio, size)
        var lastProblem = "No image model answered."
        for (model in candidates(key)) {
            val response = try {
                transport.execute(HttpRequestSpec("POST", ImageGenProtocol.generateUrl(model), headers(key), HttpBody.Text("application/json", body), REQUEST_TIMEOUT_MS))
            } catch (e: TransportException) {
                return ImageOutcome.Failed(when (e.kind) {
                    TransportFailure.TIMEOUT -> "The image service took too long. Try again in a moment."
                    TransportFailure.UNREACHABLE -> "Could not reach the image service. Check the internet connection."
                    else -> "The connection to the image service failed."
                })
            }
            when {
                response.isSuccessful -> {
                    val parsed = ImageGenProtocol.parseResponse(response.body)
                    if (parsed.images.isNotEmpty()) {
                        val files = withContext(Dispatchers.IO) { save(parsed.images) }
                        if (files.isEmpty()) return ImageOutcome.Failed("The picture was made but could not be saved on this device.")
                        return ImageOutcome.Done(files, parsed.text, model)
                    }
                    // A model that answers without a picture: a refusal is final, anything else tries the next model.
                    lastProblem = parsed.failure ?: lastProblem
                    if (parsed.failure?.contains("refused") == true || parsed.failure?.contains("safety") == true) return ImageOutcome.Failed(lastProblem)
                }
                response.code == 401 || response.code == 403 -> return ImageOutcome.Failed("The Gemini API key was not accepted for image generation (${response.code}). Check the key and that image generation is enabled for it.")
                response.code == 429 -> return ImageOutcome.Failed("The image quota or rate limit was reached. Wait a little, or use another key.")
                response.code == 404 || response.code == 400 -> lastProblem = apiMessage(response.body) ?: "Model $model is not available for this key."
                else -> lastProblem = apiMessage(response.body) ?: "The image service answered with an error (${response.code})."
            }
        }
        discovered = emptyList() // the list may be stale; look again next time
        return ImageOutcome.Failed(lastProblem)
    }

    private fun headers(key: String) = mapOf("x-goog-api-key" to key, "Accept" to "application/json")

    /** Preferred model first, then discovered models, then the built-in fallbacks. */
    private suspend fun candidates(key: String): List<String> {
        val list = LinkedHashSet<String>()
        preferredModel().trim().takeIf { it.isNotEmpty() }?.let { list += it }
        list += discover(key)
        list += ImageGenProtocol.FALLBACK_MODELS
        return list.toList()
    }

    private suspend fun discover(key: String): List<String> {
        val now = clock()
        if (discovered.isNotEmpty() && now - discoveredAt < DISCOVERY_TTL_MS) return discovered
        val found = ArrayList<String>()
        var token: String? = null
        try {
            var pages = 0
            do {
                val r = transport.execute(HttpRequestSpec("GET", ImageGenProtocol.listModelsUrl(token), headers(key), null, 20_000L))
                if (!r.isSuccessful) break
                found += ImageGenProtocol.imageModelsFromList(r.body)
                token = try { JsonParser.parseString(r.body).asJsonObject.get("nextPageToken")?.asString?.takeIf { it.isNotEmpty() } } catch (_: Exception) { null }
                pages++
            } while (token != null && pages < 3)
        } catch (_: TransportException) { /* fall back to the built-in list */ }
        val ranked = ImageGenProtocol.rank(found)
        if (ranked.isNotEmpty()) { discovered = ranked; discoveredAt = now }
        return ranked
    }

    private fun save(images: List<GeneratedImage>): List<File> {
        outDir.mkdirs()
        val stamp = clock()
        val saved = ArrayList<File>()
        images.forEachIndexed { i, img ->
            val file = File(outDir, "jarvis_image_${stamp}_${i + 1}.${img.extension}")
            try { file.writeBytes(img.bytes); saved += file } catch (_: java.io.IOException) { file.delete() }
        }
        return saved
    }

    private fun apiMessage(body: String): String? = try {
        JsonParser.parseString(body).asJsonObject.getAsJsonObject("error")?.get("message")?.asString?.take(300)
    } catch (_: JsonParseException) { null } catch (_: IllegalStateException) { null }

    companion object {
        const val REQUEST_TIMEOUT_MS = 180_000L
        const val DISCOVERY_TTL_MS = 6 * 60 * 60 * 1000L
    }
}
