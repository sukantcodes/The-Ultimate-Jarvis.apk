package com.jarvis.assistant.automation.accessibility

import com.jarvis.assistant.automation.accessibility.TypingText.Box
import com.jarvis.assistant.automation.accessibility.TypingText.Candidate
import com.jarvis.assistant.automation.accessibility.TypingText.Match
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TypingTextTest {

    // ---- readback matching -------------------------------------------------------------------

    @Test fun exactEnglishMatches() {
        assertEquals(Match.EXACT, TypingText.match("hello world", "hello world"))
    }

    @Test fun caseWhitespaceAndNbspAreIgnored() {
        assertEquals(Match.EXACT, TypingText.match("Hello   World ", "hello world"))
    }

    @Test fun hindiMatchesExactly() {
        assertEquals(Match.EXACT, TypingText.match("नमस्ते दुनिया", "नमस्ते दुनिया"))
    }

    @Test fun hinglishMatchesExactly() {
        assertEquals(Match.EXACT, TypingText.match("kya haal hai bhai", "Kya haal hai bhai"))
    }

    @Test fun mixedScriptMatches() {
        assertTrue(TypingText.isVerified("WhatsApp पर message bhejo", "WhatsApp पर message bhejo"))
    }

    @Test fun decomposedAndComposedUnicodeAreTheSameText() {
        val composed = "é"          // é
        val decomposed = "é"       // e + combining acute
        assertEquals(Match.EXACT, TypingText.match(decomposed, composed))
    }

    @Test fun zeroWidthJoinersDoNotBreakDevanagariMatching() {
        val withZwj = "क्‍ष"
        val without = "क्ष".replace("क्ष", "क्ष")
        assertTrue(TypingText.isVerified(withZwj, "क्ष"))
        assertTrue(TypingText.isVerified("क्ष", withZwj))
        assertTrue(TypingText.isVerified(without, "क्ष"))
    }

    @Test fun emojiSurvives() {
        assertEquals(Match.EXACT, TypingText.match("ok 👍🏽", "ok 👍🏽"))
    }

    @Test fun emptyFieldIsNeverAMatch() {
        assertEquals(Match.NONE, TypingText.match("", "hello"))
        assertEquals(Match.NONE, TypingText.match(null, "hello"))
    }

    @Test fun differentTextIsNotAMatch() {
        assertEquals(Match.NONE, TypingText.match("hello", "hello world"))
        assertEquals(Match.NONE, TypingText.match("something else entirely", "hello world"))
    }

    @Test fun smallDecorationAroundTypedTextIsLoose() {
        assertEquals(Match.LOOSE, TypingText.match("₹ 500", "500"))
        assertEquals(Match.LOOSE, TypingText.match("hello world 11/100", "hello world"))
    }

    @Test fun duplicatedTextIsNotAccepted() {
        assertEquals(Match.NONE, TypingText.match("hello hello", "hello"))
    }

    @Test fun largeExistingContentAroundTheTextIsNotAccepted() {
        val big = "x".repeat(200) + " hello " + "y".repeat(200)
        assertEquals(Match.NONE, TypingText.match(big, "hello"))
    }

    @Test fun newlineVersusSpaceIsTolerated() {
        assertTrue(TypingText.isVerified("line one line two", "line one\nline two"))
        assertTrue(TypingText.isVerified("line oneline two", "line one\nline two"))
    }

    // ---- password masks ----------------------------------------------------------------------

    @Test fun passwordMaskOfSameLengthCounts() {
        assertTrue(TypingText.isMaskOf("••••••", "abc123"))
        assertTrue(TypingText.isMaskOf("******", "abc123"))
    }

    @Test fun passwordMaskOfWrongLengthOrNonMaskDoesNotCount() {
        assertFalse(TypingText.isMaskOf("•••", "abc123"))
        assertFalse(TypingText.isMaskOf("abc123", "abc123"))
        assertFalse(TypingText.isMaskOf("", "abc123"))
        assertFalse(TypingText.isMaskOf("•••••••", "abc123"))
    }

    // ---- candidate ranking -------------------------------------------------------------------

    private fun cand(
        index: Int, l: Int, t: Int, r: Int, b: Int,
        inputFocused: Boolean = false, focused: Boolean = false, setText: Boolean = true, leaf: Boolean = true
    ) = Candidate(index, inputFocused, focused, setText, leaf, l, t, r, b)

    @Test fun noCandidatesGivesNull() {
        assertNull(TypingText.pick(emptyList(), null))
    }

    @Test fun inputFocusedFieldWinsWithoutATarget() {
        val a = cand(0, 0, 100, 500, 200)
        val b = cand(1, 0, 300, 500, 400, inputFocused = true, focused = true)
        assertEquals(1, TypingText.pick(listOf(a, b), null)?.index)
    }

    @Test fun firstFieldWinsWhenNothingElseDistinguishesThem() {
        val a = cand(0, 0, 100, 500, 200)
        val b = cand(1, 0, 300, 500, 400)
        assertEquals(0, TypingText.pick(listOf(b, a), null)?.index)
    }

    @Test fun fieldUnderTheTargetBeatsAnUnfocusedFieldElsewhere() {
        val top = cand(0, 0, 100, 500, 200)
        val bottom = cand(1, 0, 900, 500, 1000)
        assertEquals(1, TypingText.pick(listOf(top, bottom), Box(10, 920, 490, 980))?.index)
    }

    @Test fun whenTheTargetMissesEveryFieldTheNearestWins() {
        // Target rect sits on a non-editable wrapper between two fields: the closer field is chosen.
        val far = cand(0, 0, 100, 500, 200)
        val near = cand(1, 0, 700, 500, 800)
        assertEquals(1, TypingText.pick(listOf(far, near), Box(0, 560, 500, 640))?.index)
    }

    @Test fun focusStillBeatsGeometry() {
        val focusedElsewhere = cand(0, 0, 100, 500, 200, inputFocused = true, focused = true)
        val underTarget = cand(1, 0, 900, 500, 1000)
        assertEquals(0, TypingText.pick(listOf(focusedElsewhere, underTarget), Box(10, 920, 490, 980))?.index)
    }

    @Test fun nestedEditableLeafBeatsItsEditableParent() {
        val parent = cand(0, 0, 100, 500, 300, leaf = false)
        val child = cand(1, 10, 110, 490, 290, leaf = true)
        assertEquals(1, TypingText.pick(listOf(parent, child), null)?.index)
    }

    @Test fun rankIsDeterministicAndComplete() {
        val list = listOf(cand(2, 0, 0, 10, 10), cand(0, 0, 0, 10, 10), cand(1, 0, 0, 10, 10))
        assertEquals(listOf(0, 1, 2), TypingText.rank(list, null).map { it.index })
    }
}
