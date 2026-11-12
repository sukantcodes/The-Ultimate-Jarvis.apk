package com.jarvis.assistant.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultilingualCommandHelperTest {

    @Test
    fun testLanguageDetectionFromScriptAndKeywords() {
        assertEquals("Hindi", MultilingualCommandHelper.detectLanguage("एक पोर्टफोलियो वेबसाइट बनाओ"))
        assertEquals("Telugu", MultilingualCommandHelper.detectLanguage("వెబ్‌సైట్ తయారు చేయి"))
        assertEquals("Tamil", MultilingualCommandHelper.detectLanguage("வலைத்தளத்தை உருவாக்கு"))
        assertEquals("Bengali", MultilingualCommandHelper.detectLanguage("ওয়েবসাইট তৈরি করো"))
        assertEquals("Japanese", MultilingualCommandHelper.detectLanguage("システムレポートを教えて"))
        assertEquals("Hinglish", MultilingualCommandHelper.detectLanguage("Jarvis ek badhiya website banao"))
        assertEquals("Hinglish", MultilingualCommandHelper.detectLanguage("battery kitni bachi hai"))
        assertEquals("Spanish", MultilingualCommandHelper.detectLanguage("crear un sitio web para mi proyecto"))
        assertEquals("French", MultilingualCommandHelper.detectLanguage("quel est le rapport du système"))
        assertEquals("German", MultilingualCommandHelper.detectLanguage("erstelle eine website bitte"))
    }

    @Test
    fun testMultilingualActionableTaskDetection() {
        // English
        assertTrue(MultilingualCommandHelper.isActionableTask("build a modern website"))
        assertTrue(MultilingualCommandHelper.isActionableTask("start local server"))
        // Hindi / Hinglish
        assertTrue(MultilingualCommandHelper.isActionableTask("ek portfolio website bana do"))
        assertTrue(MultilingualCommandHelper.isActionableTask("local server chalu karo"))
        assertTrue(MultilingualCommandHelper.isActionableTask("naya app banao"))
        // Spanish
        assertTrue(MultilingualCommandHelper.isActionableTask("crear un sitio web"))
        assertTrue(MultilingualCommandHelper.isActionableTask("iniciar servidor"))
        // French
        assertTrue(MultilingualCommandHelper.isActionableTask("créer un site web"))
        assertTrue(MultilingualCommandHelper.isActionableTask("démarrer le serveur"))
        // German
        assertTrue(MultilingualCommandHelper.isActionableTask("erstelle eine website"))
        // Japanese
        assertTrue(MultilingualCommandHelper.isActionableTask("ウェブサイトを作成して"))
        // Non-actionable
        assertFalse(MultilingualCommandHelper.isActionableTask("who is the president of france"))
        assertFalse(MultilingualCommandHelper.isActionableTask("what is the weather like today"))
    }

    @Test
    fun testMultilingualSystemReportQueries() {
        // English
        assertTrue(MultilingualCommandHelper.isSystemReport("system report"))
        assertTrue(MultilingualCommandHelper.isSystemReport("give me the hardware status"))
        // Hindi / Hinglish
        assertTrue(MultilingualCommandHelper.isSystemReport("system report batao"))
        assertTrue(MultilingualCommandHelper.isSystemReport("phone ka status kaisa hai"))
        assertTrue(MultilingualCommandHelper.isSystemReport("system ka haal batao"))
        // Spanish
        assertTrue(MultilingualCommandHelper.isSystemReport("informe del sistema"))
        assertTrue(MultilingualCommandHelper.isSystemReport("estado del sistema"))
        // French
        assertTrue(MultilingualCommandHelper.isSystemReport("rapport du système"))
        // German
        assertTrue(MultilingualCommandHelper.isSystemReport("systembericht bitte"))
        // Japanese
        assertTrue(MultilingualCommandHelper.isSystemReport("システムレポート"))
    }

    @Test
    fun testMultilingualBatteryAndThermalQueries() {
        assertTrue(MultilingualCommandHelper.isBatteryQuery("battery temperature"))
        assertTrue(MultilingualCommandHelper.isBatteryQuery("battery kitni hai"))
        assertTrue(MultilingualCommandHelper.isBatteryQuery("phone kitna garam hai"))
        assertTrue(MultilingualCommandHelper.isBatteryQuery("temperatura de la batería"))
        assertTrue(MultilingualCommandHelper.isBatteryQuery("température du téléphone"))
        assertTrue(MultilingualCommandHelper.isBatteryQuery("akkustand"))
        assertTrue(MultilingualCommandHelper.isBatteryQuery("バッテリー残量"))
    }

    @Test
    fun testMultilingualMemoryAndCpuQueries() {
        assertTrue(MultilingualCommandHelper.isMemoryQuery("how much free ram"))
        assertTrue(MultilingualCommandHelper.isMemoryQuery("ram kitna bacha hai"))
        assertTrue(MultilingualCommandHelper.isMemoryQuery("cuánta ram libre"))
        assertTrue(MultilingualCommandHelper.isMemoryQuery("メモリ使用量"))

        assertTrue(MultilingualCommandHelper.isCpuQuery("cpu load"))
        assertTrue(MultilingualCommandHelper.isCpuQuery("cpu kitna use ho raha hai"))
        assertTrue(MultilingualCommandHelper.isCpuQuery("charge du processeur"))
    }

    @Test
    fun testMultilingualVoiceReplyParsing() {
        // English
        val (enReply, enText) = MultilingualCommandHelper.parseVoiceReply("reply: I will be there in 10 minutes")
        assertTrue(enReply)
        assertEquals("I will be there in 10 minutes", enText)

        val (tellReply, tellText) = MultilingualCommandHelper.parseVoiceReply("tell Alex that I'm running late")
        assertTrue(tellReply)
        assertEquals("Alex that I'm running late", tellText)

        // Hindi / Hinglish
        val (hiReply, hiText) = MultilingualCommandHelper.parseVoiceReply("reply karo: main raste mein hoon")
        assertTrue(hiReply)
        assertEquals("main raste mein hoon", hiText)

        val (hiReply2, hiText2) = MultilingualCommandHelper.parseVoiceReply("usko bolo ki 5 minute mein pahunchta hoon")
        assertTrue(hiReply2)
        assertEquals("5 minute mein pahunchta hoon", hiText2)

        // Spanish
        val (esReply, esText) = MultilingualCommandHelper.parseVoiceReply("responder: llego en breve")
        assertTrue(esReply)
        assertEquals("llego en breve", esText)

        // French
        val (frReply, frText) = MultilingualCommandHelper.parseVoiceReply("répondre: je suis en route")
        assertTrue(frReply)
        assertEquals("je suis en route", frText)

        // German
        val (deReply, deText) = MultilingualCommandHelper.parseVoiceReply("antworte: ich bin gleich da")
        assertTrue(deReply)
        assertEquals("ich bin gleich da", deText)

        // Japanese
        val (jaReply, jaText) = MultilingualCommandHelper.parseVoiceReply("返信: すぐに行きます")
        assertTrue(jaReply)
        assertEquals("すぐに行きます", jaText)
    }

    @Test
    fun testLocalizedMilestoneAnnouncements() {
        val hi35 = MultilingualCommandHelper.getLocalizedMilestone(35, 35, 12, "Hindi")
        assertTrue(hi35.contains("Base UI layout"))
        assertTrue(hi35.contains("35% kaam complete"))

        val hi100 = MultilingualCommandHelper.getLocalizedMilestone(100, 100, 0, "Hindi")
        assertTrue(hi100.contains("http://localhost:8080"))
        assertTrue(hi100.contains("Kaam poora ho gaya"))

        val es100 = MultilingualCommandHelper.getLocalizedMilestone(100, 100, 0, "Spanish")
        assertTrue(es100.contains("http://localhost:8080"))
        assertTrue(es100.contains("Tarea completada"))
    }

    @Test
    fun testLocalizedVoiceReplyConfirm() {
        val hiConfirm = MultilingualCommandHelper.getLocalizedVoiceReplyConfirm("Rahul", "Aata hoon", true, "Hindi")
        assertEquals("Rahul ko reply bhej diya gaya hai: \"Aata hoon\", Sir.", hiConfirm)

        val esConfirm = MultilingualCommandHelper.getLocalizedVoiceReplyConfirm("Carlos", "Voy", true, "Spanish")
        assertEquals("Respuesta enviada a Carlos: \"Voy\", Señor.", esConfirm)
    }
}
