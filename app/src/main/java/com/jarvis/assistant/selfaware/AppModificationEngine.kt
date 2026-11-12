package com.jarvis.assistant.selfaware

import android.content.Context
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.util.ThemeManager

object AppModificationEngine {
    
    sealed class ModificationResult {
        data class Success(val message: String) : ModificationResult()
        data class Failure(val reason: String) : ModificationResult()
    }
    
    /**
     * Process a self-modification command from Jarvis.
     * Returns a result that can be spoken back to the user.
     */
    fun processCommand(context: Context, command: String): ModificationResult {
        val cmd = command.lowercase().trim()
        return when {
            cmd.contains("enable auto approve") || cmd.contains("auto approve on") -> {
                try {
                    com.jarvis.assistant.tools.PermissionPreferences.setAutoApproveAll(context, true)
                    ModificationResult.Success("Auto-approve is now enabled. I will execute all tasks without permission prompts.")
                } catch (e: Exception) {
                    ModificationResult.Failure("Could not enable auto-approve: ${e.message}")
                }
            }
            cmd.contains("disable auto approve") || cmd.contains("auto approve off") -> {
                try {
                    com.jarvis.assistant.tools.PermissionPreferences.setAutoApproveAll(context, false)
                    ModificationResult.Success("Auto-approve disabled. I will ask before executing sensitive tasks.")
                } catch (e: Exception) {
                    ModificationResult.Failure("Could not disable auto-approve: ${e.message}")
                }
            }
            cmd.contains("theme") -> {
                ModificationResult.Success("Theme modification is available via Settings → Appearance. I can open it for you.")
            }
            cmd.contains("what are you") || cmd.contains("your blueprint") || cmd.contains("your structure") -> {
                val summary = AppBlueprintProvider.getBlueprintSummary(context)
                ModificationResult.Success(summary)
            }
            else -> ModificationResult.Failure("I don't have a direct modification for '$command'. Try: 'enable auto approve', 'disable auto approve', or 'your blueprint'.")
        }
    }
}
