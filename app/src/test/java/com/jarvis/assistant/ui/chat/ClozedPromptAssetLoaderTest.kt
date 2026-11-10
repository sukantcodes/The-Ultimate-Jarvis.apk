package com.jarvis.assistant.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClozedPromptAssetLoaderTest {

    @Test fun required_asset_throws_a_typed_error_when_missing() {
        val loader = ClozedPromptAssetLoader { throw java.io.FileNotFoundException(it) }
        val error = runCatching { loader.required("prompts/system.txt") }.exceptionOrNull()
        assertTrue(error is ClozedAssetMissingException)
        assertEquals("prompts/system.txt", (error as ClozedAssetMissingException).assetPath)
    }

    @Test fun required_asset_rejects_blank_content() {
        val loader = ClozedPromptAssetLoader { "  \n " }
        val error = runCatching { loader.required("prompts/system.txt") }.exceptionOrNull()
        assertTrue(error is ClozedAssetMissingException)
    }

    @Test fun optional_asset_reports_absence_without_fabricating_content() {
        val loader = ClozedPromptAssetLoader { throw java.io.FileNotFoundException(it) }
        assertNull(loader.optional("prompts/anthropic.txt"))
    }

    @Test fun required_asset_returns_the_exact_source_text() {
        val original = "Line one\n${'$'}{CLOZED_TOOL_GUIDANCE}\n"
        val loader = ClozedPromptAssetLoader { original }
        assertEquals(original, loader.required("prompts/system.txt"))
    }
}
