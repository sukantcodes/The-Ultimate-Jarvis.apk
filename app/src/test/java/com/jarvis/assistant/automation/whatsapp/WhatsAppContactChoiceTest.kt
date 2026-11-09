package com.jarvis.assistant.automation.whatsapp

import com.jarvis.assistant.automation.contacts.ContactInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WhatsAppContactChoiceTest {
    private val contacts = listOf(
        ContactInfo("1", "John Smith", "+1 202 555 1111"),
        ContactInfo("2", "John Smith", "+1 202 555 2222"),
        ContactInfo("3", "Johnny Doe", "+1 202 555 3333")
    )

    @Test
    fun unknownChoiceNeverFallsBackToFirstContact() {
        assertNull(resolveWhatsAppContactChoice(contacts, "Nobody"))
    }

    @Test
    fun ambiguousPartialNameRequiresMoreInformation() {
        assertNull(resolveWhatsAppContactChoice(contacts, "John"))
    }

    @Test
    fun exactPhoneSuffixSelectsOnlyTheMatchingContact() {
        assertEquals("2", resolveWhatsAppContactChoice(contacts, "2222")?.id)
    }

    @Test
    fun exactFullNameStillRequiresUniqueness() {
        assertNull(resolveWhatsAppContactChoice(contacts, "John Smith"))
        assertEquals("3", resolveWhatsAppContactChoice(contacts, "Johnny Doe")?.id)
    }
}
