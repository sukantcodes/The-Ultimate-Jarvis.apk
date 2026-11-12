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
        val cmd = command.lowercase(java.util.Locale.ROOT).trim()
        val lang = com.jarvis.assistant.util.MultilingualCommandHelper.resolveLanguage(context, command)

        val isEnableAutoApprove = cmd.contains("enable auto approve") ||
                cmd.contains("auto approve on") ||
                cmd.contains("auto approve chalu karo") ||
                cmd.contains("auto approve on karo") ||
                cmd.contains("auto approve enable karo") ||
                cmd.contains("activar auto aprobación") ||
                cmd.contains("activer approbation automatique") ||
                cmd.contains("auto genehmigung aktivieren") ||
                cmd.contains("自動承認を有効にする")

        val isDisableAutoApprove = cmd.contains("disable auto approve") ||
                cmd.contains("auto approve off") ||
                cmd.contains("auto approve band karo") ||
                cmd.contains("auto approve off karo") ||
                cmd.contains("auto approve disable karo") ||
                cmd.contains("desactivar auto aprobación") ||
                cmd.contains("désactiver approbation automatique") ||
                cmd.contains("auto genehmigung deaktivieren") ||
                cmd.contains("自動承認を無効にする")

        val isBlueprint = cmd.contains("what are you") ||
                cmd.contains("your blueprint") ||
                cmd.contains("your structure") ||
                cmd.contains("blueprint dikhao") ||
                cmd.contains("tumhara blueprint") ||
                cmd.contains("tumhara structure") ||
                cmd.contains("tu plano") ||
                cmd.contains("tu estructura") ||
                cmd.contains("votre plan") ||
                cmd.contains("dein bauplan") ||
                cmd.contains("設計図") ||
                cmd.contains("ブループリント")

        return when {
            isEnableAutoApprove -> {
                try {
                    com.jarvis.assistant.tools.PermissionPreferences.setAutoApproveAll(context, true)
                    val msg = when (lang.lowercase(java.util.Locale.ROOT)) {
                        "hindi", "hinglish" -> "Auto-approve ab active hai. Main bina kisi permission prompt ke saare tasks execute karunga, Sir."
                        "spanish" -> "La auto-aprobación está habilitada. Ejecutaré todas las tareas sin avisos de permisos, Señor."
                        "french" -> "L'approbation automatique est activée. J'exécuterai toutes les tâches sans invite, Monsieur."
                        "german" -> "Automatische Genehmigung ist aktiviert. Ich führe Aufgaben ohne Nachfragen aus, Sir."
                        "japanese" -> "自動承認が有効になりました。許可プロンプトなしですべてのタスクを実行します、サー。"
                        else -> "Auto-approve is now enabled. I will execute all tasks without permission prompts."
                    }
                    ModificationResult.Success(msg)
                } catch (e: Exception) {
                    ModificationResult.Failure("Could not enable auto-approve: ${e.message}")
                }
            }
            isDisableAutoApprove -> {
                try {
                    com.jarvis.assistant.tools.PermissionPreferences.setAutoApproveAll(context, false)
                    val msg = when (lang.lowercase(java.util.Locale.ROOT)) {
                        "hindi", "hinglish" -> "Auto-approve band kar diya gaya hai. Ab main sensitive tasks ke liye aapse permission maangunga, Sir."
                        "spanish" -> "Auto-aprobación deshabilitada. Pediré confirmación antes de tareas confidenciales, Señor."
                        "french" -> "Approbation automatique désactivée. Je demanderai confirmation avant chaque tâche sensible, Monsieur."
                        "german" -> "Automatische Genehmigung deaktiviert. Ich frage vor sensiblen Aufgaben nach, Sir."
                        "japanese" -> "自動承認が無効になりました。重要なタスクを実行する前に確認します、サー。"
                        else -> "Auto-approve disabled. I will ask before executing sensitive tasks."
                    }
                    ModificationResult.Success(msg)
                } catch (e: Exception) {
                    ModificationResult.Failure("Could not disable auto-approve: ${e.message}")
                }
            }
            cmd.contains("theme") || cmd.contains("rang") || cmd.contains("tema") || cmd.contains("thème") -> {
                val msg = when (lang.lowercase(java.util.Locale.ROOT)) {
                    "hindi", "hinglish" -> "Theme badalne ke liye Settings → Appearance mein ja sakte hain, Sir."
                    "spanish" -> "La modificación de tema está disponible en Ajustes → Apariencia, Señor."
                    "french" -> "La personnalisation du thème est disponible dans Paramètres → Apparence, Monsieur."
                    "german" -> "Design-Einstellungen sind unter Einstellungen → Erscheinungsbild verfügbar, Sir."
                    "japanese" -> "テーマの変更は [設定] → [外観] から行えます、サー。"
                    else -> "Theme modification is available via Settings → Appearance. I can open it for you."
                }
                ModificationResult.Success(msg)
            }
            isBlueprint -> {
                val summary = AppBlueprintProvider.getBlueprintSummary(context)
                ModificationResult.Success(summary)
            }
            else -> ModificationResult.Failure("I don't have a direct modification for '$command'.")
        }
    }
}
