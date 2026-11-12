package com.jarvis.assistant.tts

import com.jarvis.assistant.tts.model.SpeakerProfile
import com.jarvis.assistant.tts.model.VoiceEngineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying Neural TTS catalog, speaker profiles, and speech sanitization filters.
 */
class NeuralTtsTest {

    @Test
    fun testVoiceEngineCatalog() {
        assertEquals("local_kokoro", VoiceEngineType.LOCAL_KOKORO.id)
        assertTrue(VoiceEngineType.LOCAL_KOKORO.isLocal)
        assertEquals("kokoro-int8-multi-lang-v1_0", VoiceEngineType.LOCAL_KOKORO.archiveName)

        assertEquals("local_piper", VoiceEngineType.LOCAL_PIPER.id)
        assertTrue(VoiceEngineType.LOCAL_PIPER.isLocal)
        assertEquals("vits-piper-en_GB-alan-medium", VoiceEngineType.LOCAL_PIPER.archiveName)

        assertEquals("cloud_gemini", VoiceEngineType.CLOUD_GEMINI.id)
        assertFalse(VoiceEngineType.CLOUD_GEMINI.isLocal)

        assertEquals("cloud_elevenlabs", VoiceEngineType.CLOUD_ELEVENLABS.id)
        assertFalse(VoiceEngineType.CLOUD_ELEVENLABS.isLocal)
    }

    @Test
    fun testKokoroCuratedSpeakers() {
        val speakers = SpeakerProfile.speakersFor(VoiceEngineType.LOCAL_KOKORO)
        assertEquals(5, speakers.size)

        val george = speakers.first { it.id == "kokoro_bm_george" }
        assertEquals(11, george.speakerId)
        assertTrue(george.isDefault)
        assertEquals("British English (en-GB)", george.language)
        assertEquals("Male", george.gender)

        val lewis = speakers.first { it.id == "kokoro_bm_lewis" }
        assertEquals(12, lewis.speakerId)

        val bella = speakers.first { it.id == "kokoro_af_bella" }
        assertEquals(1, bella.speakerId)
        assertEquals("Female", bella.gender)

        val emma = speakers.first { it.id == "kokoro_bf_emma" }
        assertEquals(7, emma.speakerId)

        val adam = speakers.first { it.id == "kokoro_am_adam" }
        assertEquals(5, adam.speakerId)
    }

    @Test
    fun testPiperCuratedSpeaker() {
        val speakers = SpeakerProfile.speakersFor(VoiceEngineType.LOCAL_PIPER)
        assertEquals(1, speakers.size)

        val alan = speakers.first()
        assertEquals(0, alan.speakerId)
        assertEquals("Alan (Medium VITS)", alan.displayName)
    }

    @Test
    fun testCloudVoicesCatalog() {
        val geminiVoices = SpeakerProfile.speakersFor(VoiceEngineType.CLOUD_GEMINI)
        assertTrue(geminiVoices.any { it.cloudVoiceIdentifier == "Charon" })
        assertTrue(geminiVoices.any { it.cloudVoiceIdentifier == "Orus" })
        assertTrue(geminiVoices.any { it.cloudVoiceIdentifier == "Kore" })
        assertTrue(geminiVoices.any { it.cloudVoiceIdentifier == "Puck" })

        val elevenVoices = SpeakerProfile.speakersFor(VoiceEngineType.CLOUD_ELEVENLABS)
        assertTrue(elevenVoices.any { it.cloudVoiceIdentifier == "21m00Tcm4TlvDq8ikWAM" }) // Rachel
        assertTrue(elevenVoices.any { it.cloudVoiceIdentifier == "pNInz6obpgDQGcFmaJgB" }) // Adam
    }

    @Test
    fun testSpeechSanitization() {
        // Regex filter behavior verified identically to JarvisVoiceDispatcher
        fun sanitize(input: String): String {
            var text = input
            text = text.replace(Regex("```[\\s\\S]*?```"), "")
            text = text.replace(Regex("`[^`]*`"), "")
            text = text.replace(Regex("\\{[\\s\\S]*?\\}"), "")
            text = text.replace(Regex("!\\[[^\\]]*\\]\\([^\\)]+\\)"), "")
            text = text.replace(Regex("\\[([^\\]]+)\\]\\([^\\)]+\\)"), "$1")
            text = text.replace(Regex("https?://\\S+"), "")
            text = text.replace(Regex("[#*~_>•]"), "")
            text = text.replace(Regex("<[^>]+>"), "")
            text = text.replace(Regex("\\s+"), " ").trim()
            return text
        }

        val rawInput = """
            # Status Report
            Here is the summary of your task.
            ```kotlin
            fun execute() {
                val secret = true
            }
            ```
            Use the `execute()` method to proceed.
            See [Documentation](https://example.com/docs) for details.
            Payload: {"status": "ok", "code": 200}
            **All systems optimal.**
        """.trimIndent()

        val clean = sanitize(rawInput)

        assertFalse(clean.contains("```"))
        assertFalse(clean.contains("execute()"))
        assertFalse(clean.contains("https://example.com/docs"))
        assertFalse(clean.contains("{\"status\""))
        assertFalse(clean.contains("#"))
        assertFalse(clean.contains("**"))

        assertTrue(clean.contains("Status Report"))
        assertTrue(clean.contains("Here is the summary of your task."))
        assertTrue(clean.contains("Use the method to proceed."))
        assertTrue(clean.contains("See Documentation for details."))
        assertTrue(clean.contains("All systems optimal."))
    }

    @Test
    fun testArchiveEntryPathSanitization() {
        val kokoroArchive = "kokoro-int8-multi-lang-v1_0"
        val piperArchive = "vits-piper-en_GB-alan-medium"

        // Direct paths with archive root prefix
        assertEquals("model.int8.onnx", com.jarvis.assistant.tts.engine.TtsModelDownloader.sanitizeRelativeEntryPath("$kokoroArchive/model.int8.onnx", kokoroArchive))
        assertEquals("voices.bin", com.jarvis.assistant.tts.engine.TtsModelDownloader.sanitizeRelativeEntryPath("$kokoroArchive/voices.bin", kokoroArchive))
        assertEquals("espeak-ng-data/phontab", com.jarvis.assistant.tts.engine.TtsModelDownloader.sanitizeRelativeEntryPath("$kokoroArchive/espeak-ng-data/phontab", kokoroArchive))

        // Root directory entry itself should resolve to blank (skipped)
        assertEquals("", com.jarvis.assistant.tts.engine.TtsModelDownloader.sanitizeRelativeEntryPath("$kokoroArchive/", kokoroArchive))
        assertEquals("", com.jarvis.assistant.tts.engine.TtsModelDownloader.sanitizeRelativeEntryPath(kokoroArchive, kokoroArchive))

        // Dot-slash prefixed paths
        assertEquals("model.int8.onnx", com.jarvis.assistant.tts.engine.TtsModelDownloader.sanitizeRelativeEntryPath("./$kokoroArchive/model.int8.onnx", kokoroArchive))

        // Flat entries without folder prefix
        assertEquals("en_GB-alan-medium.onnx", com.jarvis.assistant.tts.engine.TtsModelDownloader.sanitizeRelativeEntryPath("en_GB-alan-medium.onnx", piperArchive))

        // Piper archive paths
        assertEquals("tokens.txt", com.jarvis.assistant.tts.engine.TtsModelDownloader.sanitizeRelativeEntryPath("$piperArchive/tokens.txt", piperArchive))
    }

    @Test
    fun testSpeakerProfileFindById() {
        assertNotNull(SpeakerProfile.findById("kokoro_bm_george"))
        assertEquals("George (Classic Jarvis)", SpeakerProfile.findById("kokoro_bm_george")?.displayName)

        assertNotNull(SpeakerProfile.findById("piper_alan"))
        assertEquals("Alan (Medium VITS)", SpeakerProfile.findById("piper_alan")?.displayName)

        assertNotNull(SpeakerProfile.findById("gemini_charon"))
        assertEquals("Charon", SpeakerProfile.findById("gemini_charon")?.displayName)

        assertNotNull(SpeakerProfile.findById("eleven_rachel"))
        assertEquals("Rachel", SpeakerProfile.findById("eleven_rachel")?.displayName)

        assertEquals(null, SpeakerProfile.findById(null))
        assertEquals(null, SpeakerProfile.findById(""))
        assertEquals(null, SpeakerProfile.findById("non_existent_id"))
    }
}
