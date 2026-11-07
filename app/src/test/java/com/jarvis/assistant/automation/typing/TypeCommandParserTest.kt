package com.jarvis.assistant.automation.typing

import com.jarvis.assistant.automation.command.CommandParser
import com.jarvis.assistant.automation.command.JarvisCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TypeCommandParserTest {

    // ---- plain typing ------------------------------------------------------------------------------

    @Test fun plainTypeKeepsExactTextAndPunctuation() {
        val i = TypeCommandParser.parse("Jarvis, type Hello, how are you?")!!
        assertEquals("Hello, how are you?", i.text)
        assertNull(i.app)
        assertEquals(TypeMode.APPEND, i.mode)
        assertFalse(i.needsText)
    }

    @Test fun originalCaseIsPreserved() {
        assertEquals("Android Development With KOTLIN", TypeCommandParser.parse("type Android Development With KOTLIN")!!.text)
    }

    @Test fun unicodeAndEmojiSurvive() {
        assertEquals("नमस्ते दुनिया 😊", TypeCommandParser.parse("type नमस्ते दुनिया 😊")!!.text)
    }

    @Test fun multilineTextSurvives() {
        assertEquals("line one\nline two", TypeCommandParser.parse("type line one\nline two")!!.text)
    }

    @Test fun wrappingQuotesAreRemovedButInnerTextIsNot() {
        assertEquals("say \"hi\" now", TypeCommandParser.parse("type \"say \"hi\" now\"")!!.text)
    }

    @Test fun textContainingInIsNotSplitUnlessAKnownApp() {
        val i = TypeCommandParser.parse("type I live in Delhi")!!
        assertEquals("I live in Delhi", i.text)
        assertNull(i.app)
    }

    // ---- open + type (the reported bug) --------------------------------------------------------------

    @Test fun openChromeAndType() {
        val i = TypeCommandParser.parse("Open Chrome and type Android development")!!
        assertEquals("Chrome", i.app)
        assertEquals("Android development", i.text)
        assertTrue(i.opensApp)
    }

    @Test fun openWhatsAppAndTypeKeepsPunctuation() {
        val i = TypeCommandParser.parse("open whatsapp and type Hello, how are you?")!!
        assertEquals("whatsapp", i.app)
        assertEquals("Hello, how are you?", i.text)
    }

    @Test fun openThenTypeWithThenAndWrite() {
        val i = TypeCommandParser.parse("launch telegram then write Meeting at 5 PM")!!
        assertEquals("telegram", i.app)
        assertEquals("Meeting at 5 PM", i.text)
    }

    // ---- Hinglish --------------------------------------------------------------------------------------

    @Test fun hinglishOpenAndTypeKaro() {
        val i = TypeCommandParser.parse("chrome kholo aur Android development type karo")!!
        assertEquals("chrome", i.app)
        assertEquals("Android development", i.text)
    }

    @Test fun hinglishTypeKaroBeforeText() {
        val i = TypeCommandParser.parse("whatsapp kholo aur type karo Kaise ho bhai")!!
        assertEquals("whatsapp", i.app)
        assertEquals("Kaise ho bhai", i.text)
    }

    @Test fun hinglishTrailingTypeKaro() {
        val i = TypeCommandParser.parse("Hello bhai type karo")!!
        assertEquals("Hello bhai", i.text)
        assertNull(i.app)
    }

    @Test fun hinglishLikho() {
        assertEquals("Aaj ka plan", TypeCommandParser.parse("likho Aaj ka plan")!!.text)
    }

    // ---- type in a named app -----------------------------------------------------------------------------

    @Test fun typeTextInKnownApp() {
        val i = TypeCommandParser.parse("type hello in whatsapp")!!
        assertEquals("hello", i.text)
        assertEquals("whatsapp", i.app)
    }

    @Test fun thisInAppNeedsTheTextFromTheUser() {
        val i = TypeCommandParser.parse("Type this in WhatsApp")!!
        assertTrue(i.needsText)
        assertEquals("", i.text)
        assertEquals("WhatsApp", i.app)
        assertTrue(TypeCommandParser.parse("Write this in Telegram")!!.needsText)
    }

    @Test fun searchBoxEntry() {
        val i = TypeCommandParser.parse("enter android tutorials in the search box")!!
        assertEquals("android tutorials", i.text)
        assertNull(i.app)
    }

    // ---- replace / clear / continue ------------------------------------------------------------------------

    @Test fun replaceExistingText() {
        val i = TypeCommandParser.parse("replace the existing text with New value")!!
        assertEquals(TypeMode.REPLACE, i.mode)
        assertEquals("New value", i.text)
    }

    @Test fun selectAllAndType() {
        val i = TypeCommandParser.parse("select all and type Fresh text")!!
        assertEquals(TypeMode.REPLACE, i.mode)
        assertEquals("Fresh text", i.text)
    }

    @Test fun fillFieldWith() {
        val i = TypeCommandParser.parse("fill this field with abc@example.com")!!
        assertEquals(TypeMode.REPLACE, i.mode)
        assertEquals("abc@example.com", i.text)
    }

    @Test fun clearField() {
        assertEquals(TypeMode.CLEAR, TypeCommandParser.parse("clear this field")!!.mode)
        assertEquals(TypeMode.CLEAR, TypeCommandParser.parse("Please clear the text")!!.mode)
        assertEquals(TypeMode.CLEAR, TypeCommandParser.parse("field clear karo")!!.mode)
    }

    @Test fun continueTyping() {
        assertEquals(TypeMode.CONTINUE, TypeCommandParser.parse("continue typing from where you stopped")!!.mode)
        assertEquals(TypeMode.CONTINUE, TypeCommandParser.parse("Type here")!!.mode)
        assertEquals(TypeMode.CONTINUE, TypeCommandParser.parse("typing continue karo")!!.mode)
    }

    // ---- things that are NOT typing requests ----------------------------------------------------------------

    @Test fun nonTypingUtterancesAreLeftAlone() {
        listOf(
            "what type of phone is best", "type of music", "open chrome", "search youtube for android tutorials",
            "write a poem about rain", "enter the room", "open youtube and search for lofi",
            "send message to mom saying hello", "turn on flashlight"
        ).forEach { assertNull("should not parse: $it", TypeCommandParser.parse(it)) }
    }

    // ---- ROOT CAUSE regression, through the real CommandParser ----------------------------------------------

    @Test fun openAndTypeIsNoLongerSwallowedAsAnAppName() {
        val r = CommandParser.parse("open chrome and type android development")
        val cmd = r.command
        assertTrue("expected TypeText but was $cmd", cmd is JarvisCommand.TypeText)
        cmd as JarvisCommand.TypeText
        assertEquals("chrome", cmd.intent.app)
        assertEquals("android development", cmd.intent.text)
    }

    @Test fun commandParserPreservesOriginalCaseOfTheText() {
        val cmd = CommandParser.parse("Open WhatsApp and type Hello, How Are You?").command as JarvisCommand.TypeText
        assertEquals("Hello, How Are You?", cmd.intent.text)   // the old lower-cased pipeline would give "hello, how are you"
        assertEquals("WhatsApp", cmd.intent.app)
    }

    @Test fun incompleteOpenAndTypeIsNeverTurnedIntoABogusAppName() {
        val cmd = CommandParser.parse("open chrome and type").command
        assertFalse("must not become OpenApp(\"chrome and type\")", cmd is JarvisCommand.OpenApp)
    }

    @Test fun existingCommandsStillParse() {
        assertEquals(JarvisCommand.Flash(true), CommandParser.parse("turn on flashlight").command)
        val open = CommandParser.parse("open youtube").command
        assertNotNull(open)
        assertFalse("plain open must not become a typing command", open is JarvisCommand.TypeText)
    }
}
