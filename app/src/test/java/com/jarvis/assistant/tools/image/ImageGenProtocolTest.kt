package com.jarvis.assistant.tools.image

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class ImageGenProtocolTest {
    @Test fun request_has_images_then_prompt_and_valid_config_only() {
        val body = JsonParser.parseString(ImageGenProtocol.requestBody("make it blue", listOf(ImageInput("image/png", "QUJD")), "16:9", "2k")).asJsonObject
        val parts = body.getAsJsonArray("contents")[0].asJsonObject.getAsJsonArray("parts")
        assertEquals("QUJD", parts[0].asJsonObject.getAsJsonObject("inlineData").get("data").asString)
        assertEquals("make it blue", parts[1].asJsonObject.get("text").asString)
        val img = body.getAsJsonObject("generationConfig").getAsJsonObject("imageConfig")
        assertEquals("16:9", img.get("aspectRatio").asString); assertEquals("2K", img.get("imageSize").asString)
        val none = JsonParser.parseString(ImageGenProtocol.requestBody("x", emptyList(), "7:7", "9K")).asJsonObject
        assertFalse(none.getAsJsonObject("generationConfig").has("imageConfig"))
    }

    @Test fun response_with_picture_and_text_is_read_in_both_field_styles() {
        val data = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        val camel = """{"candidates":[{"content":{"parts":[{"text":"Here"},{"inlineData":{"mimeType":"image/jpeg","data":"$data"}}]}}]}"""
        val snake = """{"candidates":[{"content":{"parts":[{"inline_data":{"mime_type":"image/png","data":"$data"}}]}}]}"""
        val a = ImageGenProtocol.parseResponse(camel); val b = ImageGenProtocol.parseResponse(snake)
        assertEquals(1, a.images.size); assertEquals("jpg", a.images[0].extension); assertEquals("Here", a.text)
        assertEquals("png", b.images[0].extension); assertArrayEquals(byteArrayOf(1, 2, 3), b.images[0].bytes)
    }

    @Test fun refusals_and_words_only_answers_give_a_plain_reason() {
        val blocked = ImageGenProtocol.parseResponse("""{"promptFeedback":{"blockReason":"SAFETY"}}""")
        assertTrue(blocked.images.isEmpty()); assertTrue(blocked.failure!!.contains("refused"))
        val words = ImageGenProtocol.parseResponse("""{"candidates":[{"content":{"parts":[{"text":"I can't"}]}}]}""")
        assertTrue(words.failure!!.contains("words only"))
        assertNotNull(ImageGenProtocol.parseResponse("not json").failure)
    }

    @Test fun model_discovery_keeps_only_image_generators() {
        val list = """{"models":[
          {"name":"models/gemini-2.5-flash-image","supportedGenerationMethods":["generateContent"]},
          {"name":"models/gemini-3-pro-image-preview","supportedGenerationMethods":["generateContent"]},
          {"name":"models/imagen-4.0-generate","supportedGenerationMethods":["predict"]},
          {"name":"models/gemini-2.5-flash","supportedGenerationMethods":["generateContent"]},
          {"name":"models/text-embedding-004","supportedGenerationMethods":["embedContent"]}]}"""
        val found = ImageGenProtocol.imageModelsFromList(list)
        assertEquals(listOf("gemini-2.5-flash-image", "gemini-3-pro-image-preview"), found)
        assertEquals("gemini-2.5-flash-image", ImageGenProtocol.rank(found).first())
    }

    @Test fun intent_separates_pictures_from_code_and_questions() {
        val k = ImageIntent.Kind.values().associateBy { it.name }
        assertEquals(ImageIntent.Kind.GENERATE, ImageIntent.classify("generate an image of a red dragon over a city", false))
        assertEquals(ImageIntent.Kind.GENERATE, ImageIntent.classify("ek cat ki tasveer banao", false))
        assertEquals(ImageIntent.Kind.NONE, ImageIntent.classify("write python code to resize an image", false))
        assertEquals(ImageIntent.Kind.NONE, ImageIntent.classify("what is the best image format?", false))
        assertEquals(ImageIntent.Kind.EDIT, ImageIntent.classify("remove the background and make the sky purple", true))
        assertEquals(ImageIntent.Kind.NONE, ImageIntent.classify("what is in this picture?", true))
        assertNotNull(k)
    }

    @Test fun chat_picture_lines_round_trip() {
        val f = java.io.File.createTempFile("pic", ".png"); f.deleteOnExit()
        val text = "Done\n\n" + com.jarvis.assistant.ui.chat.ChatImageRefs.line(f.absolutePath, "a (cat) [x]")
        val (words, refs) = com.jarvis.assistant.ui.chat.ChatImageRefs.extract(text)
        assertEquals("Done", words); assertEquals(1, refs.size); assertEquals(f.absolutePath, refs[0].path)
        assertEquals("Done", com.jarvis.assistant.ui.chat.ChatImageRefs.forSpeech(text))
    }
}
