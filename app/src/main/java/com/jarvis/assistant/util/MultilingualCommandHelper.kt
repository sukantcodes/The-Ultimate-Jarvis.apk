package com.jarvis.assistant.util

import android.content.Context
import com.jarvis.assistant.JarvisApp
import java.util.Locale

/**
 * Enterprise Multilingual Command & Localization Helper.
 * Enables Jarvis to seamlessly understand voice & text commands across ALL languages
 * (English, Hindi, Hinglish, Spanish, French, German, Japanese, Telugu, Tamil, Bengali, etc.),
 * and articulate vocal milestone announcements and telemetry status in the user's selected language.
 */
object MultilingualCommandHelper {

    // ─────────────────────────────────────────────────────────────────────────
    // Language Resolution
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Resolves the active language preference. If user setting is "Auto",
     * inspects the script and vocabulary of the input command; otherwise falls back to system locale.
     */
    fun resolveLanguage(context: Context, input: String): String {
        val prefLang = try {
            JarvisApp.instance.preferences.language
        } catch (_: Throwable) {
            "Auto"
        }
        if (prefLang.isNotBlank() && !prefLang.equals("Auto", ignoreCase = true)) {
            return prefLang
        }
        return detectLanguage(input)
    }

    /**
     * Inspects text characters and linguistic markers to detect language.
     */
    fun detectLanguage(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return fallbackLocaleLanguage()
        }

        // Script-based detection
        for (ch in trimmed) {
            when (ch.code) {
                in 0x0900..0x097F -> return "Hindi" // Devanagari (Hindi, Marathi, Bhojpuri)
                in 0x0C00..0x0C7F -> return "Telugu"
                in 0x0B80..0x0BFF -> return "Tamil"
                in 0x0980..0x09FF -> return "Bengali"
                in 0x0A80..0x0AFF -> return "Gujarati"
                in 0x0A00..0x0A7F -> return "Punjabi"
                in 0x0C80..0x0CFF -> return "Kannada"
                in 0x0D00..0x0D7F -> return "Malayalam"
                in 0x0B00..0x0B7F -> return "Odia"
                in 0x0600..0x06FF -> return "Urdu"
                in 0x3040..0x30FF, in 0x4E00..0x9FFF -> return "Japanese"
                in 0x0400..0x04FF -> return "Russian"
            }
        }

        val lower = trimmed.lowercase(Locale.ROOT)

        fun matchesToken(tokens: List<String>): Boolean {
            return tokens.any { token ->
                Regex("(?i)\\b" + Regex.escape(token) + "\\b").containsMatchIn(lower)
            }
        }

        // Hinglish markers
        val hinglishTokens = listOf(
            "banao", "karo", "batao", "chal raha", "kitna", "kitni", "kaise", "bhejo",
            "jawab", "haal", "kaisa", "garam", "tapman", "acha", "theek", "shuru", "bolo"
        )
        if (matchesToken(hinglishTokens)) return "Hinglish"

        // German markers
        val germanTokens = listOf(
            "erstelle", "bauen", "starten", "batterie", "akku",
            "bericht", "status", "temperatur", "antworten", "speicher", "bitte"
        )
        if (matchesToken(germanTokens)) return "German"

        // Spanish markers
        val spanishTokens = listOf(
            "crear", "sitio", "página", "construir", "iniciar", "servidor", "batería",
            "cómo", "cuál", "informe", "estado", "temperatura", "responder", "memoria"
        )
        if (matchesToken(spanishTokens)) return "Spanish"

        // French markers
        val frenchTokens = listOf(
            "créer", "construire", "démarrer", "serveur", "batterie",
            "rapport", "état", "température", "répondre", "mémoire", "processeur"
        )
        if (matchesToken(frenchTokens)) return "French"

        return fallbackLocaleLanguage()
    }

    private fun fallbackLocaleLanguage(): String {
        return when (Locale.getDefault().language.lowercase(Locale.ROOT)) {
            "hi" -> "Hindi"
            "es" -> "Spanish"
            "fr" -> "French"
            "de" -> "German"
            "ja" -> "Japanese"
            "te" -> "Telugu"
            "ta" -> "Tamil"
            "bn" -> "Bengali"
            "mr" -> "Marathi"
            "gu" -> "Gujarati"
            "pa" -> "Punjabi"
            "ru" -> "Russian"
            else -> "English"
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Intent Matching: Actionable Tasks (Websites, Apps, Servers)
    // ─────────────────────────────────────────────────────────────────────────

    fun isActionableTask(prompt: String): Boolean {
        val lower = prompt.trim().lowercase(Locale.ROOT)

        val hasWebsite = lower.contains("website") || lower.contains("web site") || lower.contains("site web") ||
                lower.contains("sitio web") || lower.contains("webseite") || lower.contains("वेबसाइट") ||
                lower.contains("వెబ్‌సైట్") || lower.contains("வலைத்தளம்") || lower.contains("ওয়েবসাইট") ||
                lower.contains("ウェブサイト")

        val hasCreationAction = lower.contains("create") || lower.contains("build") || lower.contains("make") ||
                lower.contains("banao") || lower.contains("bana do") || lower.contains("banado") || lower.contains("banaye") ||
                lower.contains("design") || lower.contains("develop") ||
                lower.contains("crear") || lower.contains("hacer") || lower.contains("construir") ||
                lower.contains("erstelle") || lower.contains("bauen") || lower.contains("作成") ||
                lower.contains("తయారు") || lower.contains("உருவாக்கு") || lower.contains("তৈরি")

        if (hasWebsite && hasCreationAction) return true

        val actionKeywords = listOf(
            // English
            "create a website", "create website", "build a website", "build website",
            "make a website", "make website", "start server", "run server", "local server",
            "create file", "write code", "build an app", "clone repo", "run script",
            "terminal", "execute", "download", "install", "deploy", "setup", "set up",
            // Hindi / Hinglish
            "website banao", "website bana do", "ek website banao", "website create karo",
            "website build karo", "server chalu karo", "server start karo", "server chalao",
            "code likho", "app banao", "file banao", "script chalao", "deploy karo",
            // Spanish
            "crear un sitio web", "crear sitio web", "construir sitio web", "hacer una pagina web",
            "iniciar servidor", "ejecutar servidor", "crear archivo", "escribir codigo", "crear aplicacion",
            // French
            "créer un site web", "créer site web", "faire un site web", "démarrer le serveur",
            "créer un fichier", "écrire du code", "construire une application",
            // German
            "erstelle eine website", "website bauen", "server starten", "datei erstellen", "code schreiben",
            // Japanese
            "ウェブサイトを作成", "ウェブサイトを作って", "サイトを作って", "サーバーを起動", "コードを書いて", "ファイルを作成",
            // Telugu
            "వెబ్‌సైట్ తయారు చేయి", "వెబ్‌సైట్ నిర్మించు", "సర్వర్‌ను ప్రారంభించు",
            // Tamil
            "வலைத்தளத்தை உருவாக்கு", "வலைத்தளம் செய்", "சர்வரை தொடங்கு",
            // Bengali
            "ওয়েবসাইট তৈরি করো", "একটি ওয়েবসাইট বানাও", "সার্ভার চালু করো"
        )
        return actionKeywords.any { lower.contains(it) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Intent Matching: Telemetry & Hardware Queries
    // ─────────────────────────────────────────────────────────────────────────

    fun isSystemReport(text: String): Boolean {
        val lower = text.trim().lowercase(Locale.ROOT)
        val keywords = listOf(
            // English
            "system report", "system status", "hardware report", "hardware status",
            "hardware telemetry", "diagnostics report", "diagnostics status", "phone status",
            // Hindi / Hinglish
            "system report", "system status", "system ka haal", "system report batao",
            "phone ka status", "hardware kaisa hai", "system check karo", "diagnostics batao",
            "phone kaisa chal raha hai", "system ki report",
            // Spanish
            "informe del sistema", "estado del sistema", "diagnóstico del sistema",
            "reporte del sistema", "estado del hardware",
            // French
            "rapport du système", "état du système", "diagnostic du système",
            // German
            "systembericht", "systemstatus", "hardware-status", "diagnosebericht",
            // Japanese
            "システムレポート", "システム状態", "診断レポート", "ハードウェア状態",
            // Telugu
            "సిస్టమ్ రిపోర్ట్", "సిస్టమ్ స్థితి",
            // Tamil
            "கணினி அறிக்கை", "கணினி நிலை",
            // Bengali
            "সিস্টেম রিপোর্ট", "সিস্টেমের অবস্থা"
        )
        return keywords.any { lower.contains(it) }
    }

    fun isBatteryQuery(text: String): Boolean {
        val lower = text.trim().lowercase(Locale.ROOT)
        val keywords = listOf(
            // English
            "battery status", "battery temp", "battery temperature", "phone temperature",
            "battery level", "charge level", "thermal status", "how hot is the phone",
            // Hindi / Hinglish
            "battery kitni hai", "battery status", "battery temperature", "battery kitna charge hai",
            "phone kitna garam hai", "battery ka tapman", "phone ka tapman", "battery kitna bacha hai",
            // Spanish
            "estado de la batería", "temperatura de la batería", "temperatura del teléfono",
            "nivel de batería", "cuánta batería queda",
            // French
            "état de la batterie", "température de la batterie", "température du téléphone",
            "niveau de batterie",
            // German
            "batteriestatus", "akkustand", "akkutemperatur", "temperatur des telefons",
            // Japanese
            "バッテリー残量", "バッテリー温度", "バッテリー状態", "電池残量",
            // Telugu
            "బ్యాటరీ స్థితి", "బ్యాటరీ ఉష్ణోగ్రత",
            // Tamil
            "பேட்டரி நிலை", "பேட்டரி வெப்பநிலை",
            // Bengali
            "ব্যাটারির অবস্থা", "ব্যাটারির তাপমাত্রা"
        )
        return keywords.any { lower.contains(it) }
    }

    fun isMemoryQuery(text: String): Boolean {
        val lower = text.trim().lowercase(Locale.ROOT)
        val keywords = listOf(
            // English
            "ram status", "ram usage", "memory status", "memory usage", "free ram",
            // Hindi / Hinglish
            "ram kitna bacha hai", "ram status", "memory kitni hai", "ram kitna use ho raha hai", "ram usage batao",
            // Spanish
            "uso de ram", "estado de la memoria", "memoria ram libre", "cuánta ram libre",
            // French
            "utilisation de la ram", "état de la mémoire", "ram libre",
            // German
            "ram-auslastung", "speicherstatus", "freier ram",
            // Japanese
            "ram使用率", "メモリ使用量", "メモリ状態", "空きメモリ",
            // Telugu
            "ర్యామ్ వినియోగం", "మెమరీ స్థితి",
            // Tamil
            "ராம் பயன்பாடு", "நினைவக நிலை"
        )
        return keywords.any { lower.contains(it) }
    }

    fun isCpuQuery(text: String): Boolean {
        val lower = text.trim().lowercase(Locale.ROOT)
        val keywords = listOf(
            // English
            "cpu status", "cpu load", "cpu usage", "processor load", "processor usage",
            // Hindi / Hinglish
            "cpu load", "cpu kitna use ho raha hai", "processor status", "cpu load kitna hai",
            // Spanish
            "carga de la cpu", "uso de la cpu", "estado del procesador",
            // French
            "charge du processeur", "utilisation du cpu", "état du processeur",
            // German
            "cpu-auslastung", "prozessorlast",
            // Japanese
            "cpu負荷", "cpu使用率", "プロセッサ使用率"
        )
        return keywords.any { lower.contains(it) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Intent Matching: Notification Voice Reply
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Extracts reply intent and cleans the message across any language.
     * Returns Pair(isReplyIntent: Boolean, extractedReplyText: String).
     */
    fun parseVoiceReply(command: String): Pair<Boolean, String> {
        val trimmed = command.trim()
        val lower = trimmed.lowercase(Locale.ROOT)

        val prefixes = listOf(
            // English
            "send reply", "reply to", "reply", "tell him", "tell her", "tell them", "tell",
            // Hindi / Hinglish
            "reply bhej do", "reply kardo", "reply karo", "jawab do", "unhe bolo", "usko bolo", "bol do", "bhejo", "bolo",
            // Spanish
            "enviar respuesta", "responder", "responde", "dile a", "dile", "envía",
            // French
            "envoyer réponse", "répondre", "réponds", "dis-lui", "dis à",
            // German
            "antwort senden", "antworte", "sag ihm", "sag ihr",
            // Japanese
            "返信して", "返信", "伝えて"
        ).sortedByDescending { it.length }

        for (prefix in prefixes) {
            if (lower.startsWith(prefix)) {
                val raw = trimmed.substring(prefix.length).trim().removePrefix(":").removePrefix(",").trim()
                val cleaned = when {
                    raw.startsWith("that ", ignoreCase = true) -> raw.substring(5).trim()
                    raw.startsWith("ki ", ignoreCase = true) -> raw.substring(3).trim()
                    raw.startsWith("que ", ignoreCase = true) -> raw.substring(4).trim()
                    raw.startsWith("dass ", ignoreCase = true) -> raw.substring(5).trim()
                    else -> raw
                }
                if (cleaned.isNotBlank()) {
                    return true to cleaned
                }
            }
        }

        return false to ""
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Localized Acknowledgements & Milestone Vocalizations
    // ─────────────────────────────────────────────────────────────────────────

    fun getLocalizedAck(language: String): String {
        return when (language.lowercase(Locale.ROOT)) {
            "hindi", "hinglish" -> "Ji sir, aapka request shuru kar raha hoon background mein."
            "spanish" -> "A su servicio, Señor. Iniciando su solicitud en segundo plano."
            "french" -> "À votre service, Monsieur. Lancement de votre requête en arrière-plan."
            "german" -> "Zu Ihren Diensten, Sir. Starte Ihre Anfrage im Hintergrund."
            "japanese" -> "承知いたしました、サー。バックグラウンドでリクエストを開始します。"
            "telugu" -> "మీ సేవలో సర్. బ్యాక్‌గ్రౌండ్‌లో మీ అభ్యర్థనను ప్రారంభిస్తున్నాను."
            "tamil" -> "உங்கள் சேவையில் சார். பின்னணியில் உங்கள் கோரிக்கையைத் தொடங்குகிறேன்."
            "bengali" -> "আপনার সেবায় প্রস্তুত স্যার। ব্যাকগ্রাউন্ডে কাজ শুরু করছি।"
            else -> "At your service, Sir. Starting your request now in the background."
        }
    }

    fun getLocalizedMilestone(milestone: Int, percent: Int, remainingSec: Int, language: String): String {
        val lang = language.lowercase(Locale.ROOT)
        return when (milestone) {
            35 -> when (lang) {
                "hindi", "hinglish" -> "Base UI layout aur files design ho gaye hain. 35% kaam complete, lagbhag $remainingSec seconds bache hain."
                "spanish" -> "He creado los archivos base del proyecto. 35% completado, quedan unos $remainingSec segundos."
                "french" -> "J'ai créé les fichiers du projet. 35% terminé, environ $remainingSec secondes restantes."
                "german" -> "Basis-Projektdateien wurden erstellt. 35% abgeschlossen, noch etwa $remainingSec Sekunden."
                "japanese" -> "プロジェクトファイルを作成しました。35％完了、残り約 $remainingSec 秒です。"
                "telugu" -> "ప్రాజెక్ట్ ఫైల్‌లు రూపొందించబడ్డాయి. 35% పూర్తయింది, సుమారు $remainingSec సెకన్లు మిగిలి ఉన్నాయి."
                else -> "I've created the project files. 35% complete, about $remainingSec seconds remaining."
            }
            70 -> when (lang) {
                "hindi", "hinglish" -> "Core logic, styles aur server configure ho rahe hain. 70% complete, bas khatam hone wala hai."
                "spanish" -> "Configurando servidor y lógica principal. 70% completado, casi terminado."
                "french" -> "Configuration du serveur et des styles. 70% terminé, presque achevé."
                "german" -> "Server und Logik werden konfiguriert. 70% abgeschlossen, fast fertig."
                "japanese" -> "サーバーとスタイルを設定中。70％完了、もうすぐ終了します。"
                "telugu" -> "సర్వర్ మరియు లాజిక్ కాన్ఫిగర్ అవుతున్నాయి. 70% పూర్తయింది."
                else -> "Configuring server and assets. 70% complete, almost finished."
            }
            100 -> when (lang) {
                "hindi", "hinglish" -> "Kaam poora ho gaya hai, Sir. Aapka website local server http://localhost:8080 par live chal raha hai."
                "spanish" -> "Tarea completada con éxito, Señor. Su sitio web se está ejecutando en el servidor local http://localhost:8080."
                "french" -> "Tâche terminée avec succès, Monsieur. Votre site web est actif sur le serveur local http://localhost:8080."
                "german" -> "Aufgabe erfolgreich abgeschlossen, Sir. Ihre Website läuft auf dem lokalen Server http://localhost:8080."
                "japanese" -> "作業が完了しました、サー。ウェブサイトはローカルサーバー http://localhost:8080 で実行中です。"
                "telugu" -> "పని విజయవంతంగా పూర్తయింది, సర్. వెబ్‌సైట్ లోకల్ సర్వర్‌లో రన్ అవుతోంది."
                else -> "Task completed successfully, Sir. Your website is running on the local server http://localhost:8080."
            }
            else -> ""
        }
    }

    fun getLocalizedNotificationAnnouncement(
        sender: String,
        appName: String,
        body: String,
        hasReplyAction: Boolean,
        language: String
    ): String {
        val lang = language.lowercase(Locale.ROOT)
        return if (body.isNotBlank()) {
            when (lang) {
                "hindi", "hinglish" -> {
                    val replyPrompt = if (hasReplyAction) " Jawab dene ke liye reply bolein." else ""
                    "Sir, $sender ne $appName par message bheja hai: \"$body\".$replyPrompt"
                }
                "spanish" -> {
                    val replyPrompt = if (hasReplyAction) " Diga responder para responder." else ""
                    "Señor, $sender envió un mensaje en $appName: \"$body\".$replyPrompt"
                }
                "french" -> {
                    val replyPrompt = if (hasReplyAction) " Dites répondre pour répondre." else ""
                    "Monsieur, $sender a envoyé un message sur $appName : \"$body\".$replyPrompt"
                }
                "german" -> {
                    val replyPrompt = if (hasReplyAction) " Sagen Sie antworten, um zu antworten." else ""
                    "Sir, $sender hat eine Nachricht auf $appName gesendet: \"$body\".$replyPrompt"
                }
                "japanese" -> {
                    val replyPrompt = if (hasReplyAction) " 返信するなら「返信」と言ってください。" else ""
                    "サー、$sender から $appName でメッセージが届きました:「$body」。$replyPrompt"
                }
                else -> {
                    val replyPrompt = if (hasReplyAction) " Say reply to respond." else ""
                    "Sir, $sender sent a message on $appName: \"$body\".$replyPrompt"
                }
            }
        } else {
            when (lang) {
                "hindi", "hinglish" -> "Sir, $sender se $appName par message aaya hai."
                "spanish" -> "Señor, tiene un mensaje de $sender en $appName."
                "french" -> "Monsieur, vous avez un message de $sender sur $appName."
                "german" -> "Sir, Sie haben eine Nachricht von $sender auf $appName."
                "japanese" -> "サー、$appName で $sender からメッセージが届きました。"
                else -> "Sir, you have a message from $sender on $appName."
            }
        }
    }

    fun getLocalizedVoiceReplyConfirm(
        sender: String,
        message: String,
        success: Boolean,
        language: String
    ): String {
        val lang = language.lowercase(Locale.ROOT)
        return if (success) {
            when (lang) {
                "hindi", "hinglish" -> "$sender ko reply bhej diya gaya hai: \"$message\", Sir."
                "spanish" -> "Respuesta enviada a $sender: \"$message\", Señor."
                "french" -> "Réponse envoyée à $sender : \"$message\", Monsieur."
                "german" -> "Antwort an $sender gesendet: \"$message\", Sir."
                "japanese" -> "$sender に返信を送信しました:「$message」、サー。"
                else -> "Reply dispatched to $sender: \"$message\", Sir."
            }
        } else {
            when (lang) {
                "hindi", "hinglish" -> "$sender ko reply nahi bhej paya. Notification session expire ho gaya hai, Sir."
                "spanish" -> "No se pudo enviar la respuesta a $sender. La notificación ha expirado, Señor."
                "french" -> "Impossible d'envoyer la réponse à $sender. La session a expiré, Monsieur."
                "german" -> "Antwort an $sender konnte nicht gesendet werden. Benachrichtigung abgelaufen, Sir."
                "japanese" -> "$sender への返信を送信できませんでした。セッションが期限切れです。"
                else -> "Could not dispatch reply to $sender. The notification session may have expired."
            }
        }
    }

    fun getLocalizedVoiceAuthRequired(language: String): String {
        return when (language.lowercase(Locale.ROOT)) {
            "hindi", "hinglish" -> "Sir, message bhejne ke liye owner voice verification zaroori hai."
            "spanish" -> "Se requiere verificación de voz del propietario para enviar el mensaje, Señor."
            "french" -> "Vérification vocale requise pour l'envoi du message, Monsieur."
            "german" -> "Besitzer-Sprachverifizierung für Nachrichtenversand erforderlich, Sir."
            "japanese" -> "メッセージを送信するには所有者の音声認証が必要です、サー。"
            else -> "Voice verification required for message dispatch, Sir."
        }
    }
}
