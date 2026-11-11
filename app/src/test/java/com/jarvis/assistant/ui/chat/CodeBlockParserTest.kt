package com.jarvis.assistant.ui.chat

import org.junit.Assert.*
import org.junit.Test

class CodeBlockParserTest {

    @Test
    fun parses_plain_text_without_code_fences() {
        val result = CodeBlockParser.parse("Hello world, this is a plain message.", isActivelyStreaming = false)
        assertEquals(1, result.size)
        assertTrue(result[0] is ParsedContent.Text)
        assertEquals("Hello world, this is a plain message.", (result[0] as ParsedContent.Text).text)
    }

    @Test
    fun parses_closed_code_block() {
        val markdown = "Here is the code:\n```kotlin\nfun main() {\n    println(\"Hi\")\n}\n```\nAll done!"
        val result = CodeBlockParser.parse(markdown, isActivelyStreaming = false)
        assertEquals(3, result.size)

        assertTrue(result[0] is ParsedContent.Text)
        assertEquals("Here is the code:\n", (result[0] as ParsedContent.Text).text)

        assertTrue(result[1] is ParsedContent.Code)
        val code = result[1] as ParsedContent.Code
        assertEquals("kotlin", code.language)
        assertEquals("fun main() {\n    println(\"Hi\")\n}", code.code)
        assertFalse(code.isWriting)

        assertTrue(result[2] is ParsedContent.Text)
        assertEquals("All done!", (result[2] as ParsedContent.Text).text)
    }

    @Test
    fun parses_streaming_unclosed_code_block() {
        val streamingMarkdown = "I will write the python script for you:\n```python\nimport os\nos.system('ls')"
        val result = CodeBlockParser.parse(streamingMarkdown, isActivelyStreaming = true)
        assertEquals(2, result.size)

        assertTrue(result[0] is ParsedContent.Text)
        assertTrue(result[1] is ParsedContent.Code)
        val code = result[1] as ParsedContent.Code
        assertEquals("python", code.language)
        assertEquals("import os\nos.system('ls')", code.code)
        assertTrue(code.isWriting)
    }
}
