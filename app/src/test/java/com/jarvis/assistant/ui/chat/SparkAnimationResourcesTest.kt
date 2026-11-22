package com.jarvis.assistant.ui.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locks the spark animation lists to the original frame order and 125 ms timing. */
class SparkAnimationResourcesTest {

    private fun res(name: String): String {
        val f = listOf("src/main/res/drawable/$name.xml", "app/src/main/res/drawable/$name.xml").map(::File).first { it.exists() }
        return f.readText()
    }

    private fun frames(name: String): List<String> =
        Regex("""@drawable/(claude_spark_\w+)" android:duration="125"""").findAll(res(name)).map { it.groupValues[1] }.toList()

    @Test fun thinkingIsEightFrames() =
        assertEquals((1..8).map { "claude_spark_thinking$it" }, frames("claude_spark_animated_thinking"))

    @Test fun writingIsEightFrames() =
        assertEquals((1..8).map { "claude_spark_writing$it" }, frames("claude_spark_animated_writing"))

    @Test fun shimmerSequenceMatchesOriginal() =
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 4, 5, 6, 7, 4, 3, 2, 1).map { "claude_spark_shimmer$it" }, frames("claude_spark_animated_shimmer"))

    @Test fun all23FramesExistAndUseThemeColour() {
        val names = (1..8).map { "claude_spark_thinking$it" } + (1..8).map { "claude_spark_writing$it" } + (1..7).map { "claude_spark_shimmer$it" }
        assertEquals(23, names.size)
        names.forEach { assertTrue(it, res(it).contains("?attr/themeAccent") && !Regex("#[0-9A-Fa-f]{6}").containsMatchIn(res(it))) }
    }
}
