package com.jarvis.assistant.automation.rapido

import com.jarvis.assistant.skills.SkillParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RapidoGuardsAndLocationTest {
    @Test fun genericClicksCannotReachBookCancelOrPayButtons() {
        listOf("Book Auto", "Book now", "Confirm booking", "Cancel ride", "Yes, cancel", "Pay now", "Add money", "book ride").forEach {
            assertNotNull(it, RapidoGuards.genericClickBlock(true, RapidoScreen.RIDE_OPTIONS, it, "", false))
        }
    }

    @Test fun harmlessClicksAndOtherAppsAreUntouched() {
        assertNull(RapidoGuards.genericClickBlock(true, RapidoScreen.HOME, "Where to?", "", false))
        assertNull(RapidoGuards.genericClickBlock(true, RapidoScreen.RIDE_OPTIONS, "Auto", "", false))
        assertNull(RapidoGuards.genericClickBlock(true, RapidoScreen.HOME, "Booking history", "", false))
        assertNull(RapidoGuards.genericClickBlock(false, null, "Book now", "", false))
    }

    @Test fun positionOrIdTapsAreBlockedOnTheFareScreenAndCancelDialog() {
        assertNotNull(RapidoGuards.genericClickBlock(true, RapidoScreen.RIDE_OPTIONS, "", "", true))
        assertNotNull(RapidoGuards.genericClickBlock(true, RapidoScreen.CANCEL_FLOW, "", "", true))
        assertNull(RapidoGuards.genericClickBlock(true, RapidoScreen.HOME, "", "", true))
    }

    @Test fun typingIsBlockedOnSignInAndSecurityScreensOnly() {
        assertNotNull(RapidoGuards.genericTypeBlock(true, RapidoScreen.SIGN_IN))
        assertNotNull(RapidoGuards.genericTypeBlock(true, RapidoScreen.CHALLENGE))
        assertNull(RapidoGuards.genericTypeBlock(true, RapidoScreen.LOCATION_SEARCH))
        assertNull(RapidoGuards.genericTypeBlock(false, RapidoScreen.CHALLENGE))
    }

    @Test fun rapidoPackageIsRecognised() {
        assertTrue(RapidoApp.isRapidoPackage("com.rapido.passenger"))
        assertFalse(RapidoApp.isRapidoPackage("com.rapido.captain"))
        assertFalse(RapidoApp.isRapidoPackage(null))
    }

    @Test fun locationStatesAreDistinguished() {
        fun c(fine: Boolean, coarse: Boolean, on: Boolean, acc: Float?, age: Long? = null) = LocationClassifier.classify(fine, coarse, on, acc, age)
        assertEquals(LocationAccess.PERMISSION_DENIED, c(false, false, true, 10f).access)
        assertEquals(LocationAccess.SERVICES_OFF, c(true, true, false, 10f).access)
        assertEquals(LocationAccess.PRECISE, c(true, true, true, 25f).access)
        assertEquals(LocationAccess.APPROXIMATE, c(true, true, true, 800f).access)
        assertEquals(LocationAccess.APPROXIMATE, c(false, true, true, 20f).access)
        assertEquals(LocationAccess.UNAVAILABLE, c(true, true, true, null).access)
        assertEquals(LocationAccess.UNAVAILABLE, c(true, true, true, 9000f).access)
        assertEquals(LocationAccess.UNAVAILABLE, c(true, true, true, 10f, age = 40 * 60_000L).access)
        assertTrue(c(true, true, true, 25f).usable)
        assertFalse(c(true, true, false, 25f).usable)
    }

    @Test fun everyLocationStateHasAnHonestExplanation() {
        LocationAccess.values().forEach {
            val text = LocationClassifier.explain(LocationState(it, 50))
            assertTrue(it.name, text.length > 20)
            assertFalse(text.contains("lat"))
        }
    }

    @Test fun builtInSkillIsValidAndTeachesTheSafeFlow() {
        val file = listOf("src/main/assets/skills/rapido-rides.md", "app/src/main/assets/skills/rapido-rides.md")
            .map { File(it) }.first { it.exists() }
        val skill = SkillParser.parse(file.readText())
        assertNotNull(skill)
        assertEquals("rapido-rides", skill!!.name)
        assertTrue(skill.description.length <= SkillParser.MAX_DESCRIPTION)
        assertTrue(skill.body.length <= SkillParser.MAX_BODY)
        assertTrue(skill.body.contains("rapido_book"))
        assertTrue(skill.body.contains("BOOKED"))
    }
}
