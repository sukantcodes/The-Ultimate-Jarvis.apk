package com.jarvis.assistant.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillParserTest {

    private val good = "---\nname: my-skill\ndescription: Use when the user says hello\n---\n# Title\n\nStep one.\n"

    @Test
    fun parsesNameDescriptionAndBody() {
        val skill = SkillParser.parse(good)
        assertNotNull(skill)
        assertEquals("my-skill", skill!!.name)
        assertEquals("Use when the user says hello", skill.description)
        assertTrue(skill.body.startsWith("# Title"))
        assertTrue(skill.body.contains("Step one."))
    }

    @Test
    fun acceptsWindowsLineEndings() {
        assertNotNull(SkillParser.parse(good.replace("\n", "\r\n")))
    }

    @Test
    fun rejectsMissingFrontMatterBadNamesAndEmptyBodies() {
        assertNull(SkillParser.parse("# no front matter\nSteps"))
        assertNull(SkillParser.parse("---\nname: Bad Name\ndescription: x\n---\nbody"))
        assertNull(SkillParser.parse("---\nname: ok-name\n---\nbody"))
        assertNull(SkillParser.parse("---\nname: ok-name\ndescription: x\n---\n"))
        assertNull(SkillParser.parse("---\nname: ok-name\ndescription: x\nbody without closing"))
    }

    @Test
    fun capsDescriptionAndBodyLength() {
        val longDescription = "d".repeat(500)
        val longBody = "b".repeat(20000)
        val skill = SkillParser.parse("---\nname: big-one\ndescription: $longDescription\n---\n$longBody")
        assertNotNull(skill)
        assertEquals(SkillParser.MAX_DESCRIPTION, skill!!.description.length)
        assertEquals(SkillParser.MAX_BODY, skill.body.length)
    }

    @Test
    fun validatesRepoAndPaths() {
        assertTrue(SkillParser.isValidRepo("HunterisLive-1/mobile-maya-skills"))
        assertFalse(SkillParser.isValidRepo("not a repo"))
        assertFalse(SkillParser.isValidRepo("a/b/c"))
        assertTrue(SkillParser.isSafeSkillPath("skills/whatsapp-pro.md"))
        assertFalse(SkillParser.isSafeSkillPath("../secrets.md"))
        assertFalse(SkillParser.isSafeSkillPath("skills/../x.md"))
        assertFalse(SkillParser.isSafeSkillPath("other/x.md"))
        assertFalse(SkillParser.isSafeSkillPath("skills/x.sh"))
    }
}
