package com.jarvis.assistant.tools.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.gson.JsonObject
import com.jarvis.assistant.tools.Risk
import com.jarvis.assistant.tools.Tool
import com.jarvis.assistant.tools.ToolArtifact
import com.jarvis.assistant.tools.ToolContext
import com.jarvis.assistant.tools.ToolParam
import com.jarvis.assistant.tools.ToolResult
import com.jarvis.assistant.tools.ToolSpec
import com.jarvis.assistant.tools.Workspace
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Remembers the two pictures a person is most likely to mean by "this one": the last image they attached to the
 * chat, and the last image Jarvis made. Edits default to these, so "make the sky purple" just works.
 */
object ImageSession {
    @Volatile var lastAttachment: String? = null
    @Volatile var lastGenerated: String? = null

    fun noteAttachment(path: String?, mime: String?) {
        if (path.isNullOrBlank()) return
        val isImage = mime?.startsWith("image/") == true || path.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
        if (isImage) lastAttachment = path
    }

    val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "heic", "heif")
}

private fun JsonObject.str(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }

/** The two picture tools a model can call from the chat: make a picture, and change a picture. */
object ImageTools {
    fun all(service: ImageService, workspace: () -> Workspace): List<Tool> = listOf(GenerateImage(service), EditImage(service, workspace))

    private const val SHOWN_AUTOMATICALLY =
        "The finished picture is shown to the person automatically inside the chat with Download, Share and Edit buttons. " +
            "Do not print the file path or a link. Reply with one short, friendly sentence about what you made, in the person's language."

    class GenerateImage(private val service: ImageService) : Tool {
        override val spec = ToolSpec(
            "generate_image",
            "Create a new picture from a text description. Use it whenever the person asks to draw, make, create or generate an image, photo, logo, poster, wallpaper or illustration. " +
                "Write the prompt in English with subject, style, setting, lighting, colours and mood, keeping everything the person asked for. Call it once per picture.",
            listOf(
                ToolParam("prompt", required = true, description = "Detailed description of the picture."),
                ToolParam("aspect_ratio", description = "One of ${ImageGenProtocol.ASPECT_RATIOS.joinToString()}. Default: let the model choose."),
                ToolParam("size", description = "Resolution: 1K, 2K or 4K (only when the person asks for higher quality).")
            ), Risk.NETWORK, "image"
        )

        override fun describe(args: JsonObject) = "Create a picture: " + (args.str("prompt") ?: "").take(160)

        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val prompt = args.str("prompt") ?: return ToolResult.Error("The prompt is missing. Describe the picture.")
            ctx.progress("Creating your picture…")
            return when (val o = service.run(prompt, emptyList(), args.str("aspect_ratio"), args.str("size"))) {
                is ImageOutcome.Failed -> ToolResult.Error(o.message)
                is ImageOutcome.Done -> finish(o)
            }
        }
    }

    class EditImage(private val service: ImageService, private val workspace: () -> Workspace) : Tool {
        override val spec = ToolSpec(
            "edit_image",
            "Change a picture the person uploaded or that you made earlier: remove or add things, change colours, background, style, lighting, text, clothing, expression, or combine pictures. " +
                "Describe only the change; the rest of the picture is kept. By default it edits the image the person attached most recently.",
            listOf(
                ToolParam("instruction", required = true, description = "Exactly what to change, in English, with every detail the person gave."),
                ToolParam("source", description = "Which picture: \"attachment\" (the person's latest upload, default), \"last_image\" (the one you made last), or a file path."),
                ToolParam("extra_source", description = "Optional second picture to use as a reference (same options as source)."),
                ToolParam("aspect_ratio", description = "Optional new shape, one of ${ImageGenProtocol.ASPECT_RATIOS.joinToString()}."),
                ToolParam("size", description = "Optional resolution: 1K, 2K or 4K.")
            ), Risk.NETWORK, "image"
        )

        override fun describe(args: JsonObject) = "Edit a picture: " + (args.str("instruction") ?: "").take(160)

        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val instruction = args.str("instruction") ?: return ToolResult.Error("Say what to change in the picture.")
            val inputs = ArrayList<ImageInput>()
            for (which in listOfNotNull(args.str("source") ?: "attachment", args.str("extra_source"))) {
                val file = resolve(which) ?: return ToolResult.Error(
                    if (which.equals("attachment", true) || which.equals("last_image", true))
                        "There is no picture to edit yet. Ask the person to attach the picture they want changed."
                    else "That picture could not be found: $which"
                )
                inputs += encode(file) ?: return ToolResult.Error("The picture ${file.name} could not be read. Is it a normal image file?")
            }
            ctx.progress("Editing your picture…")
            val prompt = editPrompt(instruction)
            return when (val o = service.run(prompt, inputs, args.str("aspect_ratio"), args.str("size"))) {
                is ImageOutcome.Failed -> ToolResult.Error(o.message)
                is ImageOutcome.Done -> finish(o)
            }
        }

        private fun resolve(which: String): File? {
            val path = when (which.lowercase()) {
                "attachment", "last_attachment", "upload", "uploaded" -> ImageSession.lastAttachment
                "last_image", "last", "previous", "generated" -> ImageSession.lastGenerated
                else -> try { workspace().resolve(which).path } catch (_: Exception) { null }
            } ?: return null
            return File(path).takeIf { it.isFile && it.length() > 0 }
        }
    }

    /** The instruction sent with a picture to be edited: change only what was asked. */
    fun editPrompt(instruction: String): String =
        "Edit the provided picture as follows, and keep everything else exactly as it is (same subject, identity, composition and quality): $instruction"

    private fun finish(o: ImageOutcome.Done): ToolResult {
        ImageSession.lastGenerated = o.files.first().absolutePath
        val artifacts = o.files.map { ToolArtifact(it.absolutePath, it.name, ImageGenProtocol.mimeFor(it.name)) }
        val extra = if (o.note.isNotBlank()) "\nThe image model added: ${o.note.take(300)}" else ""
        return ToolResult.Ok("Done: ${o.files.size} picture(s) made with ${o.model}. $SHOWN_AUTOMATICALLY$extra", artifacts)
    }

    /** Reads a picture for upload. Big photos are scaled down first so the request stays small and fast. */
    internal fun encode(file: File): ImageInput? = try {
        val mime = ImageGenProtocol.mimeFor(file.name)
        val bytes = if (file.length() > BIG_BYTES) shrink(file) else file.readBytes()
        if (bytes == null || bytes.isEmpty()) null
        else ImageInput(if (file.length() > BIG_BYTES) "image/jpeg" else mime, java.util.Base64.getEncoder().encodeToString(bytes))
    } catch (_: Exception) { null }

    private const val BIG_BYTES = 6L * 1024 * 1024
    private const val MAX_SIDE = 3072

    private fun shrink(file: File): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return try {
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
        } finally { bitmap.recycle() }
    }
}
