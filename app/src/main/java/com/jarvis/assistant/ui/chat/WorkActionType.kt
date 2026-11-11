package com.jarvis.assistant.ui.chat

import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import com.jarvis.assistant.R

enum class WorkActionType(
    val idName: String,
    @DrawableRes val iconRes: Int,
    @ColorInt val activeColor: Int,
    val activeBadgeText: (String) -> String,
    val doneBadgeText: (String) -> String
) {
    FILE_CREATE(
        idName = "create",
        iconRes = R.drawable.ic_work_file_create,
        activeColor = Color.parseColor("#00E5FF"),
        activeBadgeText = { target -> if (target.isNotBlank()) "✦ Creating $target…" else "✦ Creating file…" },
        doneBadgeText = { target -> if (target.isNotBlank()) "✓ Created $target" else "✓ Created file" }
    ),
    FILE_EDIT(
        idName = "edit",
        iconRes = R.drawable.ic_work_file_edit,
        activeColor = Color.parseColor("#FF9100"),
        activeBadgeText = { target -> if (target.isNotBlank()) "✦ Editing $target…" else "✦ Modifying file…" },
        doneBadgeText = { target -> if (target.isNotBlank()) "✓ Updated $target" else "✓ File updated" }
    ),
    TERMINAL(
        idName = "terminal",
        iconRes = R.drawable.ic_work_terminal,
        activeColor = Color.parseColor("#B388FF"),
        activeBadgeText = { target -> if (target.isNotBlank()) "✦ Executing: $target…" else "✦ Running terminal…" },
        doneBadgeText = { target -> if (target.isNotBlank()) "✓ Executed: $target" else "✓ Command finished" }
    ),
    WEB_SEARCH(
        idName = "search",
        iconRes = R.drawable.ic_work_web_search,
        activeColor = Color.parseColor("#00E676"),
        activeBadgeText = { query -> if (query.isNotBlank()) "✦ Searching web: $query…" else "✦ Searching web…" },
        doneBadgeText = { query -> if (query.isNotBlank()) "✓ Web: $query" else "✓ Web search complete" }
    ),
    FILE_READ(
        idName = "read",
        iconRes = R.drawable.ic_work_file_read,
        activeColor = Color.parseColor("#40C4FF"),
        activeBadgeText = { target -> if (target.isNotBlank()) "✦ Inspecting $target…" else "✦ Reading file…" },
        doneBadgeText = { target -> if (target.isNotBlank()) "✓ Inspected $target" else "✓ Read file" }
    ),
    CODE_SYNTHESIS(
        idName = "code",
        iconRes = R.drawable.ic_sparkle,
        activeColor = Color.parseColor("#64B5F6"),
        activeBadgeText = { target -> if (target.isNotBlank()) "✦ Synthesizing $target…" else "✦ Writing code…" },
        doneBadgeText = { target -> if (target.isNotBlank()) "✓ Completed $target" else "✓ Code ready" }
    );

    fun getDynamicColor(): Int {
        return try {
            when (this) {
                FILE_CREATE, CODE_SYNTHESIS, FILE_READ -> com.jarvis.assistant.util.ThemeManager.getThemeAccentColor()
                TERMINAL -> com.jarvis.assistant.util.ThemeManager.getThemeSecondaryColor()
                FILE_EDIT -> com.jarvis.assistant.util.ThemeManager.getThemeWarningColor()
                WEB_SEARCH -> com.jarvis.assistant.util.ThemeManager.getThemeSuccessColor()
            }
        } catch (_: Exception) {
            activeColor
        }
    }

    companion object {
        fun resolve(language: String, targetName: String = "", toolName: String = ""): WorkActionType {
            val tool = toolName.lowercase().trim()
            val lang = language.lowercase().trim()
            val target = targetName.lowercase().trim()

            return when {
                tool in listOf("terminal_run", "terminal_exec", "shell", "bash", "exec") ||
                    lang in listOf("terminal", "bash", "sh", "zsh", "shell") -> TERMINAL

                tool in listOf("search_web", "web_search", "fetch_web_page", "browse", "scrape") ||
                    lang in listOf("search", "web", "http") -> WEB_SEARCH

                tool in listOf("edit_file", "patch_file", "replace_file") ||
                    lang.startsWith("edit") || lang.startsWith("patch") || target.contains("diff") -> FILE_EDIT

                tool in listOf("read_file", "list_files", "inspect_directory") ||
                    lang in listOf("read", "inspect") -> FILE_READ

                tool in listOf("write_file", "create_file", "make_directory") ||
                    target.isNotBlank() && (target.contains('.') || target.contains('/')) -> FILE_CREATE

                else -> CODE_SYNTHESIS
            }
        }
    }
}
