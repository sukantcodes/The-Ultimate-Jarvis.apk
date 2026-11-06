package com.jarvis.assistant.automation.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneNumberInputValidatorTest {
    @Test fun acceptsCommonInternationalAndFormattedNumbers() {
        assertEquals("+91 98765-43210", PhoneNumberInputValidator.normalize("+91 98765-43210"))
        assertEquals("(080) 1234 5678", PhoneNumberInputValidator.normalize("(080) 1234 5678"))
        assertEquals("1234567890", PhoneNumberInputValidator.normalize("1234567890"))
    }
    @Test fun rejectsMalformedNumbers() {
        listOf("", "  ", "12", "123456789012345678901", "++919876543210", "91+9876543210", "123-ABC-7890")
            .forEach { assertNull("Should reject '$it'", PhoneNumberInputValidator.normalize(it)) }
    }
    @Test fun trimsOuterWhitespace() {
        assertEquals("+1 (415) 555-0132", PhoneNumberInputValidator.normalize("  +1 (415) 555-0132  "))
    }
}
