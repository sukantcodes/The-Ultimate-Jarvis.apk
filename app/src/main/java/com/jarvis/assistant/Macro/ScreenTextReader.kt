package com.jarvis.assistant.macro

import android.view.accessibility.AccessibilityNodeInfo

/** Reads all visible text and content descriptions from the current screen, top to bottom, without repeats. */
object ScreenTextReader {
    fun read(root: AccessibilityNodeInfo?, maxChars: Int = 6_000): String {
        if (root == null) return ""
        val seen = LinkedHashSet<String>()
        val counter = IntArray(1)
        walk(root, seen, counter, 0)
        return seen.joinToString("\n").take(maxChars)
    }

    private fun walk(node: AccessibilityNodeInfo, out: LinkedHashSet<String>, counter: IntArray, depth: Int) {
        if (counter[0] > 1_500 || depth > 40) return
        counter[0]++
        val text = node.text?.toString()?.trim().orEmpty()
        val description = node.contentDescription?.toString()?.trim().orEmpty()
        if (text.isNotEmpty()) out.add(text)
        if (description.isNotEmpty() && description != text) out.add(description)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walk(child, out, counter, depth + 1)
        }
    }
}
