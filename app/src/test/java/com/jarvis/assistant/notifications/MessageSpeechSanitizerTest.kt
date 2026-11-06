package com.jarvis.assistant.notifications

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageSpeechSanitizerTest {
    @Test fun convertsCommonSocialHandleSeparatorsToNaturalSpaces() {
        assertEquals("rahul kumar", MessageSpeechSanitizer.forAnnouncement("rahul_kumar"))
        assertEquals("rahul kumar", MessageSpeechSanitizer.forAnnouncement("rahul-kumar"))
        assertEquals("rahul kumar", MessageSpeechSanitizer.forAnnouncement("@rahul.kumar"))
    }

    @Test fun preservesUnicodeNamesAndDigits() {
        assertEquals("राहुल 123", MessageSpeechSanitizer.forAnnouncement("राहुल_123"))
    }

    @Test fun removesDecorativeSymbolsAndCollapsesWhitespace() {
        assertEquals("A B", MessageSpeechSanitizer.forAnnouncement("  ✨A__--B✨  "))
    }

    @Test fun blankOrSymbolOnlyNamesRemainSafe() {
        assertEquals("", MessageSpeechSanitizer.forAnnouncement("   "))
        assertEquals("", MessageSpeechSanitizer.forAnnouncement("___---"))
    }
}
