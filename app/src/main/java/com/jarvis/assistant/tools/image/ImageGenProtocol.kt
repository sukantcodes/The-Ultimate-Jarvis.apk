package com.jarvis.assistant.tools.image

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser

/** One picture sent to the model for editing (already base64). */
data class ImageInput(val mimeType: String, val base64: String)

/** One picture the model returned. */
class GeneratedImage(val mimeType: String, val bytes: ByteArray) {
    val extension: String get() = when (mimeType.lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/webp" -> "webp"
        else -> "png"
    }
}

/** What came back: pictures, any words the model added, and a plain reason when nothing was produced. */
class ImageResult(val images: List<GeneratedImage>, val text: String, val failure: String?)

/**
 * The wire format for Gemini image generation and editing (generateContent with an IMAGE response). Pure functions,
 * no network and no Android types except [Base64], so the format is easy to test.
 */
object ImageGenProtocol {
    const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/"

    /** Tried in this order when the model list can not be read. Newest-looking first; discovery replaces these. */
    val FALLBACK_MODELS = listOf("gemini-3-pro-image", "gemini-3.1-flash-image", "gemini-2.5-flash-image")

    val ASPECT_RATIOS = listOf("1:1", "2:3", "3:2", "3:4", "4:3", "4:5", "5:4", "9:16", "16:9", "21:9")
    val SIZES = listOf("1K", "2K", "4K")

    /** Gemini accepts at most this many reference pictures in one request. */
    const val MAX_INPUT_IMAGES = 14

    fun generateUrl(model: String): String = BASE_URL + modelPath(model) + ":generateContent"
    fun listModelsUrl(pageToken: String? = null): String =
        BASE_URL + "models?pageSize=200" + (pageToken?.let { "&pageToken=$it" } ?: "")

    private fun modelPath(model: String): String {
        val clean = model.trim().removePrefix("/")
        return if (clean.startsWith("models/")) clean else "models/$clean"
    }

    /**
     * The request body. [images] are reference pictures (editing, or style / subject references for generation);
     * with none it is plain text-to-image. [aspectRatio] and [size] are only sent when valid, so a model that does
     * not know them is not sent nonsense.
     */
    fun requestBody(prompt: String, images: List<ImageInput> = emptyList(), aspectRatio: String? = null, size: String? = null): String {
        val parts = JsonArray()
        for (img in images.take(MAX_INPUT_IMAGES)) {
            parts.add(JsonObject().apply {
                add("inlineData", JsonObject().apply { addProperty("mimeType", img.mimeType); addProperty("data", img.base64) })
            })
        }
        parts.add(JsonObject().apply { addProperty("text", prompt) })
        val image = JsonObject()
        aspectRatio?.trim()?.takeIf { it in ASPECT_RATIOS }?.let { image.addProperty("aspectRatio", it) }
        size?.trim()?.uppercase()?.takeIf { it in SIZES }?.let { image.addProperty("imageSize", it) }
        val generation = JsonObject().apply {
            add("responseModalities", JsonArray().apply { add("TEXT"); add("IMAGE") })
            if (image.size() > 0) add("imageConfig", image)
        }
        return JsonObject().apply {
            add("contents", JsonArray().apply { add(JsonObject().apply { addProperty("role", "user"); add("parts", parts) }) })
            add("generationConfig", generation)
        }.toString()
    }

    /** Reads an answer. Accepts both camelCase and snake_case field names. */
    fun parseResponse(body: String): ImageResult {
        val root = try { JsonParser.parseString(body) } catch (_: JsonParseException) { null }?.takeIf { it.isJsonObject }?.asJsonObject
            ?: return ImageResult(emptyList(), "", "The image service sent an answer that could not be read.")
        val images = ArrayList<GeneratedImage>()
        val text = StringBuilder()
        val candidate = root.getAsJsonArray("candidates")?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
        val parts = candidate?.getAsJsonObject("content")?.getAsJsonArray("parts")
        if (parts != null) for (p in parts) {
            val o = p.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val inline = (o.get("inlineData") ?: o.get("inline_data"))?.takeIf { it.isJsonObject }?.asJsonObject
            if (inline != null) {
                val data = inline.get("data")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                val mime = (inline.get("mimeType") ?: inline.get("mime_type"))?.takeIf { it.isJsonPrimitive }?.asString ?: "image/png"
                val bytes = try { java.util.Base64.getMimeDecoder().decode(data) } catch (_: IllegalArgumentException) { ByteArray(0) }
                if (bytes.isNotEmpty() && mime.startsWith("image/")) images += GeneratedImage(mime, bytes)
            } else if (o.has("text") && !(o.get("thought")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false)) {
                text.append(o.get("text").asString)
            }
        }
        if (images.isNotEmpty()) return ImageResult(images, text.toString().trim(), null)
        return ImageResult(emptyList(), text.toString().trim(), explainEmpty(root, candidate, text.toString()))
    }

    private fun explainEmpty(root: JsonObject, candidate: JsonObject?, text: String): String {
        val blocked = root.getAsJsonObject("promptFeedback")?.get("blockReason")?.takeIf { it.isJsonPrimitive }?.asString
        if (blocked != null) return "The image service refused this request ($blocked). Try describing it differently."
        val finish = candidate?.get("finishReason")?.takeIf { it.isJsonPrimitive }?.asString
        return when {
            finish == "IMAGE_SAFETY" || finish == "SAFETY" || finish == "PROHIBITED_CONTENT" ->
                "The image service declined to make this picture for safety reasons. Try describing it differently."
            text.isNotBlank() -> "The image service answered in words only and made no picture: ${text.trim().take(300)}"
            else -> "The image service returned no picture."
        }
    }

    /** Names of models from a ListModels answer that can generate pictures, newest first as the service lists them. */
    fun imageModelsFromList(body: String): List<String> {
        val root = try { JsonParser.parseString(body) } catch (_: JsonParseException) { null }?.takeIf { it.isJsonObject }?.asJsonObject
            ?: return emptyList()
        val models = root.getAsJsonArray("models") ?: return emptyList()
        val found = ArrayList<String>()
        for (m in models) {
            val o = m.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val name = o.get("name")?.takeIf { it.isJsonPrimitive }?.asString?.removePrefix("models/") ?: continue
            val methods = o.getAsJsonArray("supportedGenerationMethods")?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }.orEmpty()
            if ("generateContent" !in methods) continue
            val id = name.lowercase()
            if ("image" in id && "imagen" !in id && "embedding" !in id && "vision" !in id) found += name
        }
        return found
    }

    /** Orders discovered models: stable names before previews and experiments, higher quality tiers first. */
    fun rank(models: List<String>): List<String> = models.distinct().sortedWith(
        compareBy<String>({ if (it.contains("preview") || it.contains("exp")) 1 else 0 }, { if (it.contains("lite")) 1 else 0 }, { if (it.contains("pro")) 0 else 1 })
    )

    /** Picks a MIME type from a file name. */
    fun mimeFor(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        else -> "image/png"
    }
}
