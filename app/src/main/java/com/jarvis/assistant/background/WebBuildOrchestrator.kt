package com.jarvis.assistant.background

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.R
import android.util.Log
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.data.model.GeminiConstants
import com.jarvis.assistant.provider.ApiKeySecret
import com.jarvis.assistant.provider.AuthMethod
import com.jarvis.assistant.provider.AuthSpec
import com.jarvis.assistant.provider.ChatClientFactory
import com.jarvis.assistant.provider.ChatContext
import com.jarvis.assistant.provider.ChatEvent
import com.jarvis.assistant.provider.ChatMessage
import com.jarvis.assistant.provider.ChatRequest
import com.jarvis.assistant.provider.ChatResolution
import com.jarvis.assistant.provider.ChatRole
import com.jarvis.assistant.provider.EngineState
import com.jarvis.assistant.provider.GeminiToolLoop
import com.jarvis.assistant.provider.LiveToolStep
import com.jarvis.assistant.provider.ModelInfo
import com.jarvis.assistant.provider.ProviderChatEngine
import com.jarvis.assistant.provider.ProviderChatBridge
import com.jarvis.assistant.provider.ProviderErrorKind
import com.jarvis.assistant.provider.ProviderException
import com.jarvis.assistant.provider.ProviderProtocol
import com.jarvis.assistant.provider.ProviderRuntime
import com.jarvis.assistant.provider.ResolvedProvider
import com.jarvis.assistant.provider.SendOutcome
import com.jarvis.assistant.provider.Thinking
import com.jarvis.assistant.provider.TokenModeStore
import com.jarvis.assistant.provider.TransportException
import com.jarvis.assistant.provider.ProviderErrors
import com.jarvis.assistant.provider.WorkCategory
import com.jarvis.assistant.automation.command.CommandParser
import com.jarvis.assistant.tools.PermissionPreferences
import com.jarvis.assistant.tools.ToolRuntime
import com.jarvis.assistant.tools.server.ProjectBuilder
import com.jarvis.assistant.util.ActivityLauncherHelper
import com.jarvis.assistant.util.MultilingualCommandHelper
import com.jarvis.assistant.voice.JarvisVoiceController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale

/**
 * Website / web-game build supervisor.
 *
 * Flow: request -> (no custom model? ask Gemini-or-custom) -> (custom chosen? wait, never sleeping, until it is
 * active) -> (generic request? ask what to build) -> build with live monitoring -> auto host on the local server ->
 * open the preview -> short rhyming summary -> stay awake until the user says okay.
 *
 * The worker (custom model, or Gemini when chosen / taking over) does the writing through the shared tool layer.
 * This supervisor never writes code itself while a custom model is working; it only reads the project folder, speaks
 * real progress, and, if the worker stops with an error, resumes from the files already on disk (and lets Gemini
 * finish if the custom model keeps failing).
 */
object WebBuildOrchestrator {

    private const val TAG = "WebBuildOrchestrator"
    private const val MAX_RESUMES = 6
    private const val CUSTOM_RETRIES_BEFORE_TAKEOVER = 3
    private const val SPEAK_EVERY_MS = 30_000L
    private const val NUDGE_AFTER_MS = 60_000L
    private const val ACK_WAIT_MS = 15 * 60_000L
    private const val LOCK_TIMEOUT_MS = 6 * 60 * 60_000L

    enum class Phase { IDLE, ASK_ENGINE, WAIT_CUSTOM, ASK_SPEC, ASK_TIME, BUILDING, AWAIT_ACK }
    enum class Engine { GEMINI, CUSTOM }

    data class Status(
        val phase: Phase = Phase.IDLE,
        val percent: Int = 0,
        val stage: String = "",
        val currentFile: String = "",
        val filesWritten: Int = 0,
        val etaSec: Int = 0,
        val engineLabel: String = "",
        val previewUrl: String = ""
    )

    private class WorkerResult(val ok: Boolean, val error: String? = null, val timedOut: Boolean = false, val limitHit: Boolean = false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    @Volatile private var phase = Phase.IDLE
    @Volatile private var engine = Engine.GEMINI
    @Volatile private var spec = ""
    @Volatile private var quality = false
    /** True once the owner has said (or been asked) whether they want standard or high detail. */
    @Volatile private var qualityChosen = false
    /** The owner's time allowance in seconds: -1 not asked yet, 0 no limit, otherwise the exact budget. */
    @Volatile private var budgetSec = -1
    /** Epoch millis when the budget runs out; 0 when there is no limit. Saved so a restart keeps the same deadline. */
    @Volatile private var deadlineAt = 0L
    @Volatile private var projectName = ""
    @Volatile private var previewOpened = false
    @Volatile private var lastNarration = ""
    @Volatile private var lang = "English"
    @Volatile private var appCtx: Context? = null
    @Volatile private var job: Job? = null
    @Volatile private var onComplete: ((Boolean, String) -> Unit)? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var priorAutoApprove = false
    private var autoApproveTouched = false

    /** True from the first request until the user says okay (or the run is cancelled). Keeps Jarvis awake. */
    fun isActive(): Boolean = phase != Phase.IDLE

    // ─────────────────────────────────────────────────────────────────────────────
    // Intent detection
    // ─────────────────────────────────────────────────────────────────────────────

    private val createWords = listOf(
        "create", "build", "make", "develop", "design", "code", "generate", "banao", "bana do", "banado", "banaye",
        "तैयार", "बनाओ", "बना दो", "बनाइए", "তৈরি", "বানাও", "crear", "hacer", "erstelle", "bauen", "作成", "作って"
    )
    private val wantWords = listOf("chahiye", "chaiye", "चाहिए", "চাই", "i want", "i need", "want a", "need a")
    private val siteWords = listOf(
        "website", "web site", "webpage", "web page", "landing page", "web app", "webapp", "वेबसाइट", "ওয়েবসাইট", "sitio web", "webseite"
    )
    private val gameWords = listOf("web game", "webgame", "browser game", "html game", "html5 game", "game website", "game site")

    fun isWebBuildRequest(text: String): Boolean {
        if (MultilingualCommandHelper.isProjectCreationRequest(text)) return true
        val l = text.lowercase(Locale.ROOT)
        val create = createWords.any { l.contains(it) }
        val want = wantWords.any { l.contains(it) }
        if (!create && !want) return false
        if (siteWords.any { l.contains(it) } || gameWords.any { l.contains(it) }) return true
        if (!create) return false
        // "make a snake game" / "create a game" — a game with a creation verb means a browser game here.
        return Regex("\\b(game|खेल|গেম)\\b").containsMatchIn(l)
    }

    /** True when this utterance belongs to the orchestrator and must not go to the normal assistant path. */
    fun wantsInput(text: String): Boolean = when (phase) {
        Phase.IDLE -> isWebBuildRequest(text)
        // Anything that is clearly an on-device command ("open WhatsApp", "set an alarm") still goes to the normal path.
        Phase.ASK_ENGINE -> parseEngine(text) != null || MultilingualCommandHelper.isUserChoosesJarvisDirectly(text) || MultilingualCommandHelper.isUserWantsToAddCustomProvider(text) || isCancel(text) || !isDeviceCommand(text)
        Phase.ASK_SPEC -> isCancel(text) || !isDeviceCommand(text)
        Phase.ASK_TIME -> isCancel(text) || isQuality(text) || TimeBudget.parse(text) != null || MultilingualCommandHelper.parseQualityAndTime(text) != null || !isDeviceCommand(text)
        Phase.WAIT_CUSTOM -> parseEngine(text) == Engine.GEMINI || MultilingualCommandHelper.isUserChoosesJarvisDirectly(text) || isCancel(text)
        Phase.BUILDING -> isCancel(text) || isWebBuildRequest(text)
        Phase.AWAIT_ACK -> isAck(text) || isCancel(text) || isWebBuildRequest(text)
    }

    private fun isDeviceCommand(text: String): Boolean = try {
        if (isWebBuildRequest(text)) false else CommandParser.parse(text).let { it.command != null && it.confidence >= 0.8f }
    } catch (e: Exception) { false }

    private val qualityWords = listOf(
        "high quality", "highest quality", "best quality", "premium", "professional", "stunning", "beautiful", "polished", "top notch", "top-notch",
        "high detail", "high-detail", "detailed", "full detail", "ultra detail", "ultra", "perfect", "har ek cheez",
        "achha", "accha", "acha", "badhiya", "badiya", "zabardast", "shandar", "behtareen", "best se best", "high-quality",
        "अच्छा", "बढ़िया", "बढ़िया", "शानदार", "जबरदस्त", "बेहतरीन", "প্রিমিয়াম", "ভালো", "সেরা", "দারুণ"
    )
    private fun isQuality(text: String): Boolean = text.lowercase(Locale.ROOT).let { l -> qualityWords.any { l.contains(it) } || l.contains("high detail") || l.contains("highly detailed") || l.contains("every detail") || l.contains("perfect") }

    private val standardWords = listOf("standard", "normal quality", "normal", "basic", "simple", "quick", "medium", "regular", "sadharan", "साधारण", "सामान्य", "সাধারণ")
    private fun isStandard(text: String): Boolean = text.lowercase(Locale.ROOT).let { l -> standardWords.any { l.contains(it) } }

    /** Starts the supervised build for [text] when none is running (used by the Live "build_web_project" tool). */
    fun begin(context: Context, text: String, completion: ((Boolean, String) -> Unit)?): Boolean {
        if (phase != Phase.IDLE) return false
        val ctx = context.applicationContext
        appCtx = ctx
        start(ctx, text, completion)
        return true
    }

    private fun isCancel(text: String): Boolean {
        val l = text.lowercase(Locale.ROOT)
        if (l.contains("cancel") || l.contains("रद्द") || l.contains("বাতিল")) return true
        val about = listOf("build", "task", "work", "creat", "website", "game", "काम", "কাজ")
        return (l.contains("stop") || l.contains("ruko") || l.contains("रुक") || l.contains("থামো")) && about.any { l.contains(it) }
    }

    private fun isAck(text: String): Boolean {
        val l = text.lowercase(Locale.ROOT).trim()
        if (l.split(Regex("\\s+")).size > 4) return false
        return listOf("ok", "okay", "thanks", "thank you", "fine", "great", "done", "perfect", "theek", "accha", "ठीक", "अच्छा", "ঠিক", "ধন্যবাদ")
            .any { l.contains(it) }
    }

    private fun parseEngine(text: String): Engine? {
        val l = text.lowercase(Locale.ROOT)
        val custom = listOf("custom", "कस्टम", "কাস্টম", "own model", "my model", "my own", "provider", "add custom provider", "add custom", "add provider", "custom provider")
        val gemini = listOf(
            "gemini", "jemini", "जेमिनी", "জেমিনি", "you create", "i will create", "i'll create", "you write", "you do",
            "you make", "you build", "tum", "तुम", "aap", "आप", "তুমি", "tum banao", "aap banao",
            "khud banao", "create it yourself", "jarvis create", "jarvis do it", "jarvis banao",
            "you", "do it yourself", "sirf tum", "bana do", "make it", "tu bana", "tu banao", "tu bana de", "tum hi"
        )
        return when {
            custom.any { l.contains(it) } -> Engine.CUSTOM
            gemini.any { l.contains(it) } -> Engine.GEMINI
            else -> null
        }
    }

    /** A request with nothing to build yet ("create a website"): ask what kind. */
    private fun isGeneric(text: String): Boolean {
        val filler = (createWords + wantWords + qualityWords + siteWords + gameWords + listOf("mujhe", "mera", "mereko", "hai", "ek", "achha", "i", "want", "need",
            "a", "an", "the", "me", "for", "my", "new", "please", "jarvis", "hey", "ek", "mere", "liye", "game", "site",
            "and", "run", "it", "in", "local", "server", "host", "preview", "open", "on", "to", "can", "you", "karo", "kar", "do"
        )).map { it.lowercase(Locale.ROOT) }
        var rest = text.lowercase(Locale.ROOT)
        for (f in filler.sortedByDescending { it.length }) rest = rest.replace(Regex("(?<![\\p{L}\\p{M}])" + Regex.escape(f) + "(?![\\p{L}\\p{M}])"), " ")
        return rest.split(Regex("[^\\p{L}\\p{M}\\p{N}]+")).count { it.length > 2 } < 2
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Entry point (called by AutonomousBackgroundExecutor)
    // ─────────────────────────────────────────────────────────────────────────────

    /** Returns true when the orchestrator consumed [text]. */
    fun handle(context: Context, text: String, completion: ((Boolean, String) -> Unit)?): Boolean {
        val ctx = context.applicationContext
        if (!wantsInput(text)) return false
        appCtx = ctx
        if (phase != Phase.IDLE && isCancel(text)) { cancel(ctx, announce = true); completion?.invoke(false, "Cancelled"); return true }
        when (phase) {
            Phase.IDLE -> start(ctx, text, completion)
            Phase.ASK_ENGINE -> onEngineReply(ctx, text)
            Phase.WAIT_CUSTOM -> if (parseEngine(text) == Engine.GEMINI || MultilingualCommandHelper.isUserChoosesJarvisDirectly(text)) { job?.cancel(); engine = Engine.GEMINI; afterEngineChosen(ctx) }
            Phase.ASK_SPEC -> {
                spec = text.trim(); quality = quality || isQuality(text) || !text.contains("standard", ignoreCase = true)
                if (budgetSec < 0) TimeBudget.parse(text, requireUnit = true)?.let { budgetSec = it }
                askQualityAndTimeOrBegin(ctx)
            }
            Phase.ASK_TIME -> onTimeReply(ctx, text)
            Phase.AWAIT_ACK -> if (isAck(text)) { say(ctx, tr("Alright, Sir. I'm here whenever you need me.", "ठीक है सर, मैं यहीं हूँ।", "ঠিক আছে স্যার, আমি এখানেই আছি।")); finish(ctx, true, "Acknowledged") }
                else { finish(ctx, true, "Next request"); start(ctx, text, completion) }
            Phase.BUILDING -> {
                if (isCancel(text)) {
                    say(ctx, tr("Stopping the build as requested, Sir.", "बिल्ड रोक रहा हूँ, सर।", "বিল্ড থামাচ্ছি, স্যার।"))
                    finish(ctx, false, "Cancelled by user")
                } else {
                    val file = _status.value.currentFile.ifBlank { "project files" }
                    val pct = _status.value.percent
                    val updateMsg = tr(
                        "Custom model is currently working on $file ($pct% complete). Everything is progressing smoothly, Sir.",
                        "कस्टम मॉडल अभी $file पर काम कर रहा है ($pct% पूरा)। सब कुछ ठीक से आगे बढ़ रहा है, सर।",
                        "কাস্টম মডেল বর্তমানে $file-এ কাজ করছে ($pct% সম্পন্ন)। সবকিছু ঠিকমতো এগোচ্ছে, স্যার।"
                    )
                    say(ctx, updateMsg)
                }
            }
        }
        return true
    }

    private fun start(ctx: Context, text: String, completion: ((Boolean, String) -> Unit)?) {
        onComplete = completion
        spec = text.trim()
        val parsedQualityAndTime = MultilingualCommandHelper.parseQualityAndTime(text)
        // High detail is strictly enforced by default unless standard is explicitly requested
        quality = !text.contains("standard", ignoreCase = true)
        budgetSec = parsedQualityAndTime?.second?.let { it * 60 } ?: (TimeBudget.parse(text, requireUnit = true) ?: -1)
        deadlineAt = 0L
        previewOpened = false
        lastNarration = ""
        projectName = ""
        lang = MultilingualCommandHelper.resolveLanguage(ctx, text)
        acquireLock(ctx)
        WebBuildService.start(ctx)
        if (!PermissionPreferences.isAutoApproveAll(ctx)) { priorAutoApprove = false; autoApproveTouched = true; PermissionPreferences.setAutoApproveAll(ctx, true) }
        val customAvail = customReady(ctx)
        val hasGemini = JarvisApp.instance.preferences.apiKey.isNotBlank()
        val requestedGemini = MultilingualCommandHelper.isUserChoosesJarvisDirectly(text) || parseEngine(text) == Engine.GEMINI || text.contains("gemini", ignoreCase = true)
        val requestedCustom = MultilingualCommandHelper.isUserWantsToAddCustomProvider(text) || parseEngine(text) == Engine.CUSTOM
        when {
            requestedGemini -> { engine = Engine.GEMINI; afterEngineChosen(ctx) }
            requestedCustom -> { engine = Engine.CUSTOM; afterEngineChosen(ctx) }
            customAvail -> { engine = Engine.CUSTOM; afterEngineChosen(ctx) }
            hasGemini -> { engine = Engine.GEMINI; afterEngineChosen(ctx) }
            else -> {
                say(ctx, tr("No AI model is configured. Please add a Gemini key or a custom provider in Settings.",
                    "कोई AI मॉडल सेट नहीं है। कृपया सेटिंग्स में जेमिनी की या कस्टम प्रोवाइडर जोड़ें।",
                    "কোনো AI মডেল সেট করা নেই। সেটিংসে জেমিনি কী বা কাস্টম প্রোভাইডার যোগ করুন।"))
                finish(ctx, false, "No model")
            }
        }
    }

    private fun onEngineReply(ctx: Context, text: String) {
        val choice = parseEngine(text)
        val userChoosesJarvis = MultilingualCommandHelper.isUserChoosesJarvisDirectly(text) || choice == Engine.GEMINI
        val userAddsCustom = MultilingualCommandHelper.isUserWantsToAddCustomProvider(text) || choice == Engine.CUSTOM

        when {
            userChoosesJarvis -> {
                engine = Engine.GEMINI
                afterEngineChosen(ctx)
            }
            userAddsCustom -> {
                engine = Engine.CUSTOM
                afterEngineChosen(ctx)
            }
            else -> {
                say(ctx, tr("Please say you create, or custom provider.", "कृपया बोलिए आप बनाओ, या कस्टम प्रोवाइडर।", "অনুগ্রহ করে বলুন আপনি বানান, অথবা কাস্টম প্রোভাইডার।"))
            }
        }
    }

    private fun afterEngineChosen(ctx: Context) {
        if (engine == Engine.CUSTOM && !customReady(ctx)) {
            setPhase(Phase.WAIT_CUSTOM, tr("Waiting for custom model", "कस्टम मॉडल का इंतज़ार", "কাস্টম মডেলের অপেক্ষা"))
            say(ctx, tr(
                "Understood. Please add your custom provider in Settings, or tell me 'you create' to build it directly.",
                "ठीक है। कृपया सेटिंग्स में कस्टम प्रोवाइडर जोड़ें, या बोलिए 'आप बनाओ' तो मैं खुद बना दूँगा।",
                "ঠিক আছে। সেটিংসে কাস্টম প্রোভাইডার যোগ করুন, অথবা বলুন 'আপনি বানান' আমি নিজেই তৈরি করব।"))
            try {
                val intent = Intent(ctx, com.jarvis.assistant.ui.settings.CustomProviderEditorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
            } catch (_: Exception) {}
            job = scope.launch {
                var lastRemind = System.currentTimeMillis()
                while (isActive) {
                    if (customReady(ctx)) {
                        engine = Engine.CUSTOM
                        say(ctx, tr("Custom model ${activeCustomName(ctx)} is active.", "कस्टम मॉडल ${activeCustomName(ctx)} एक्टिव है।", "কাস্টম মডেল ${activeCustomName(ctx)} चालू হয়েছে।"))
                        afterEngineChosen(ctx)
                        return@launch
                    }
                    delay(1_500)
                    if (System.currentTimeMillis() - lastRemind > 180_000) {
                        lastRemind = System.currentTimeMillis()
                        say(ctx, tr("Still waiting for the custom model, Sir.", "अभी भी कस्टम मॉडल का इंतज़ार है सर।", "এখনও কাস্টম মডেলের অপেক্ষায় আছি স্যার।"))
                    }
                }
            }
            return
        }
        askQualityAndTimeOrBegin(ctx)
    }

    /** ChatBox & Autonomous builds enforce zero time limits and proceed straight to high-detail build. */
    private fun askQualityAndTimeOrBegin(ctx: Context) {
        quality = true
        budgetSec = 0 // Zero time limit, builds run until fully complete
        beginBuild(ctx)
    }

    private fun onTimeReply(ctx: Context, text: String) {
        val parsed = MultilingualCommandHelper.parseQualityAndTime(text)
        if (parsed != null && (parsed.first != null || parsed.second != null)) {
            quality = parsed.first?.contains("high", true) == true || isQuality(text)
            budgetSec = parsed.second?.let { it * 60 } ?: (TimeBudget.parse(text) ?: if (quality) 300 else 120)
        } else {
            if (isQuality(text)) quality = true
            val sec = TimeBudget.parse(text)
            budgetSec = sec ?: if (quality) 300 else 120
        }

        val qLabel = if (quality) "High Detail" else "Standard"
        val mins = (budgetSec / 60).coerceAtLeast(1)
        val startAnnouncement = MultilingualCommandHelper.getLocalizedBuildStarting(
            topic = spec,
            quality = qLabel,
            minutes = mins,
            language = lang
        ).ifBlank {
            tr("Understood, Sir. Starting now.", "समझ गया सर। अभी शुरू कर रहा हूँ।", "বুঝেছি স্যার। এখনই শুরু করছি।")
        }
        say(ctx, startAnnouncement)
        beginBuild(ctx)
    }

    private fun langIndex(): Int = when {
        lang.contains("hindi", true) || lang.contains("hinglish", true) -> 1
        lang.contains("bengali", true) || lang.contains("bangla", true) -> 2
        else -> 0
    }

    fun cancel(context: Context? = null, announce: Boolean = false) {
        val ctx = context?.applicationContext ?: appCtx ?: return
        if (phase == Phase.IDLE) return
        job?.cancel()
        if (engine == Engine.CUSTOM) runCatching { ProviderRuntime.chatEngine(ctx).cancel() }
        if (announce) say(ctx, tr("Cancelled, Sir.", "रद्द कर दिया सर।", "বাতিল করা হয়েছে স্যার।"))
        finish(ctx, false, "Cancelled")
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Build supervision
    // ─────────────────────────────────────────────────────────────────────────────

    private class Tracker {
        @Volatile var lastTool = ""
        @Volatile var lastTarget = ""
        @Volatile var percent = 0
        @Volatile var workerDone = false
        @Volatile var chatTurns = 0
        @Volatile var polishing = false
        /** What the ChatBox shows as the owner's message for the current step: only their words, never the rules. */
        @Volatile var shown = ""
        @Volatile var polishOps = 0
        /** The refinement window (epoch millis): progress during refinement follows the real clock inside it. */
        @Volatile var windowStart = 0L
        @Volatile var windowEnd = 0L
        fun bump(p: Int) { if (p > percent) percent = p.coerceIn(0, 100) }
    }

    private fun beginBuild(ctx: Context, resuming: Boolean = false) {
        setPhase(Phase.BUILDING, tr("Starting", "शुरू कर रहा हूँ", "শুরু করছি"), 1)
        val built = ToolRuntime.get(ctx)
        val name = if (resuming && projectName.isNotBlank()) projectName else uniqueProjectName(built.workspace.resolve("projects"), spec)
        projectName = name
        val dir = built.workspace.project(name)
        if (budgetSec < 0) budgetSec = 0
        if (!resuming) deadlineAt = if (budgetSec > 0) System.currentTimeMillis() + budgetSec * 1000L else 0L
        saveState(ctx)
        val label = if (engine == Engine.CUSTOM) activeCustomName(ctx) else "Gemini"
        _status.value = _status.value.copy(engineLabel = label)
        if (resuming) {
            say(ctx, tr("I'm back. Continuing the build from the files already saved.", "मैं वापस आ गया। सेव हुई फ़ाइलों से बिल्ड आगे बढ़ा रहा हूँ।", "আমি ফিরে এসেছি। সংরক্ষিত ফাইল থেকে বিল্ড চালিয়ে যাচ্ছি।"))
        } else {
            runCatching {
                val cleanUserPrompt = spec.trim().ifBlank { "Create a complete website or game" }
                JarvisApp.instance.chatRepository.addTurn(cleanUserPrompt, "🛠 Building \"$name\" with $label" + (if (quality) " in high-quality mode" else "") + ". I'll host it on the local server and open the preview automatically.")
            }
            say(ctx, if (quality) tr(
                "High detail it is. $label will implement every single detail and I'll supervise each step.",
                "हाई डिटेल बनेगा। $label हर एक चीज़ पूरी तरह लागू करेगा और मैं हर कदम पर निगरानी रखूँगा।",
                "হাই ডিটেইল হবে। $label প্রতিটি জিনিস পুরোপুরি বানাবে আর আমি প্রতিটি ধাপে নজর রাখব।")
            else tr(
                "Starting now. $label will write the code and I'll supervise it live.",
                "अभी शुरू करता हूँ। $label कोड लिखेगा और मैं लाइव निगरानी करूँगा।",
                "এখনই শুরু করছি। $label কোড লিখবে আর আমি লাইভ নজর রাখব।"))
        }

        if (!resuming && budgetSec > 0) {
            say(ctx, tr("You gave me ${spokenDuration(budgetSec, 0)}. I'll use all of it: build first, keep improving, then open the preview right at the end.",
                "आपने ${spokenDuration(budgetSec, 1)} दिए हैं। मैं पूरा समय लगाऊँगा: पहले बनाऊँगा, फिर सुधारता रहूँगा, और आखिर में प्रीव्यू खोलूँगा।",
                "আপনি ${spokenDuration(budgetSec, 2)} দিয়েছেন। আমি পুরো সময় ব্যবহার করব: আগে বানাব, তারপর উন্নত করতে থাকব, শেষে প্রিভিউ খুলব।"))
        }

        job = scope.launch {
            val tracker = Tracker()
            val timed = deadlineAt > 0
            val totalMs = budgetSec * 1000L
            val startedAt = if (timed) deadlineAt - totalMs else System.currentTimeMillis()
            // Only the last few seconds are kept to check, host and open the preview; everything before is real working time.
            val workUntil = if (timed) deadlineAt - maxOf(8_000L, totalMs / 20) else Long.MAX_VALUE
            // The first complete version is written within about 70% of the working time; refinement rounds use the rest.
            val wrapAt = if (timed) startedAt + (workUntil - startedAt) * 70 / 100 else Long.MAX_VALUE
            /** mode 0: first pass, 1: wrap up now, 2: refinement round. */
            fun timeNote(mode: Int): String {
                if (!timed) return ""
                val target = if (mode == 0) wrapAt else workUntil
                val secs = ((target - System.currentTimeMillis()) / 1000).toInt().coerceAtLeast(10)
                return timeRules(secs, mode)
            }
            var wrapping = false
            var outOfTime = false
            val monitor = launch { monitor(ctx, dir, tracker, startedAt) }
            var attempt = 0
            var takeover = false
            var lastError: String? = null
            var prompt = (if (resuming) resumePrompt(name, spec, dir, emptyList(), "the app was restarted") else buildPrompt(name, spec)) + timeNote(0)
            var ok = false
            var firstRun = true
            try {
                while (isActive) {
                    if (System.currentTimeMillis() >= workUntil) { outOfTime = true; break }
                    val result = runWorker(ctx, prompt, tracker, useCustom = engine == Engine.CUSTOM && !takeover,
                        untilMs = if (wrapping) workUntil else minOf(wrapAt, workUntil),
                        shown = if (firstRun && !resuming) askedText() else continuedText())
                    firstRun = false
                    if (result.timedOut) {
                        if (!wrapping && System.currentTimeMillis() < workUntil - 5_000) {
                            wrapping = true
                            tracker.workerDone = false
                            sayWork(ctx, tr("Time is getting short. I'm telling $label to finish a working version now.",
                                "समय कम हो रहा है। मैं $label से अभी चलने वाला वर्ज़न पूरा करने को कह रहा हूँ।",
                                "সময় কমে আসছে। আমি $label-কে এখনই চালু থাকা সংস্করণ শেষ করতে বলছি।"))
                            prompt = resumePrompt(name, spec, dir, emptyList(), "the time for the first version is nearly used") + timeNote(1)
                            continue
                        }
                        outOfTime = true
                        break
                    }
                    if (!result.ok) {
                        lastError = result.error
                        if (attempt == 0 && !result.error.isNullOrBlank()) {
                            val why = result.error.take(160)
                            sayWork(ctx, tr("$label reported a problem: $why. I'll continue from where it stopped.", "$label में समस्या आई: $why। जहाँ रुका वहीं से आगे बढ़ा रहा हूँ।", "$label-এ সমস্যা হয়েছে: $why। যেখানে থেমেছে সেখান থেকে এগোচ্ছি।"))
                        }
                    }
                    var hasFiles = dir.walkTopDown().any { it.isFile && !it.relativeTo(dir).path.startsWith(".") }
                    var report = if (hasFiles) ProjectBuilder.check(dir, false) else null
                    if (report == null || !report.ok) {
                        // Some models answer with code blocks instead of tool calls: save those as files.
                        extractCodeBlocksFromRecentChat(dir)
                        hasFiles = dir.walkTopDown().any { it.isFile && !it.relativeTo(dir).path.startsWith(".") }
                        report = if (hasFiles) ProjectBuilder.check(dir, false) else null
                    }
                    if (report != null && report.ok) { ok = true; break }
                    val geminiAvailable = JarvisApp.instance.preferences.apiKey.isNotBlank()
                    // The model answered but wrote nothing: it is not doing the work (for example it cannot use tools).
                    if (result.ok && !hasFiles && engine == Engine.CUSTOM && !takeover) {
                        lastError = "$label answered but wrote no files, so it is not doing the build."
                        if (geminiAvailable) {
                            takeover = true
                            tracker.workerDone = false
                            _status.value = _status.value.copy(engineLabel = "Gemini")
                            sayWork(ctx, tr("$label did not write any files, so Gemini is building it instead.", "$label ने कोई फ़ाइल नहीं लिखी, इसलिए अब जेमिनी इसे बना रहा है।", "$label কোনো ফাইল লেখেনি, তাই এখন জেমিনি এটি বানাচ্ছে।"))
                            prompt = resumePrompt(name, spec, dir, emptyList(), lastError) + timeNote(if (wrapping) 1 else 0)
                            continue
                        }
                        sayWork(ctx, tr("$label did not write any files. I'll try again, and if it keeps happening please check that this model supports tools.", "$label ने कोई फ़ाइल नहीं लिखी। मैं दोबारा कोशिश करता हूँ; ऐसा बार-बार हो तो जाँचें कि यह मॉडल टूल्स सपोर्ट करता है।", "$label কোনো ফাইল লেখেনি। আমি আবার চেষ্টা করছি; বারবার হলে দেখুন এই মডেল টুল সাপোর্ট করে কি না।"))
                    }
                    // The custom provider hit its limit (every key used up): Gemini carries on at once from the saved files.
                    if (result.limitHit && engine == Engine.CUSTOM && !takeover && geminiAvailable) {
                        takeover = true
                        tracker.workerDone = false
                        _status.value = _status.value.copy(engineLabel = "Gemini")
                        sayWork(ctx, tr("The custom model's key limit is reached. Gemini is taking over from ${tracker.lastTarget.ifBlank { "the last file" }} and will finish the work.",
                            "कस्टम मॉडल की की-लिमिट पूरी हो गई। जेमिनी ${tracker.lastTarget.ifBlank { "आखिरी फ़ाइल" }} से काम संभालकर पूरा करेगा।",
                            "কাস্টম মডেলের কী-লিমিট শেষ। জেমিনি ${tracker.lastTarget.ifBlank { "শেষ ফাইল" }} থেকে কাজ নিয়ে শেষ করবে।"))
                        prompt = resumePrompt(name, spec, dir, report?.problems.orEmpty(), lastError) + timeNote(if (wrapping) 1 else 0)
                        continue
                    }
                    attempt++
                    if (attempt > MAX_RESUMES) break
                    if (engine == Engine.CUSTOM && !takeover && attempt >= CUSTOM_RETRIES_BEFORE_TAKEOVER && geminiAvailable) {
                        takeover = true
                        sayWork(ctx, tr("The custom model keeps failing. I'm taking over from ${tracker.lastTarget.ifBlank { "the last file" }}.",
                            "कस्टम मॉडल बार-बार फेल हो रहा है। मैं ${tracker.lastTarget.ifBlank { "आखिरी फ़ाइल" }} से काम संभाल रहा हूँ।",
                            "কাস্টম মডেল বারবার ব্যর্থ হচ্ছে। আমি ${tracker.lastTarget.ifBlank { "শেষ ফাইল" }} থেকে কাজ নিচ্ছি।"))
                    } else {
                        sayWork(ctx, tr("${label} stopped at ${tracker.lastTarget.ifBlank { "the planning step" }}. Restarting from exactly there.",
                            "$label ${tracker.lastTarget.ifBlank { "प्लानिंग स्टेप" }} पर रुक गया। ठीक वहीं से दोबारा शुरू कर रहा हूँ।",
                            "$label ${tracker.lastTarget.ifBlank { "প্ল্যানিং ধাপে" }} থেমে গেছে। ঠিক সেখান থেকেই আবার শুরু করছি।"))
                    }
                    tracker.workerDone = false
                    prompt = resumePrompt(name, spec, dir, report?.problems.orEmpty(), lastError) + timeNote(if (wrapping) 1 else 0)
                    delay(1_500L * attempt)
                }
                // Out of time but something runnable exists: open that rather than nothing.
                if (!ok && outOfTime && File(dir, "index.html").isFile) {
                    ok = true
                    sayWork(ctx, tr("Your time is up, so I'm opening what is ready.", "आपका समय पूरा हो गया, इसलिए जो तैयार है उसे खोल रहा हूँ।", "আপনার সময় শেষ, তাই যা তৈরি আছে তা খুলছি।"))
                }
                // Use the time that was given (and the detail that was asked for): keep improving the working project
                // round after round until the working time is nearly gone. A round that breaks the project is undone.
                if (ok && isActive && (timed || quality)) {
                    val maxRounds = if (timed) 12 else 1
                    var round = 0
                    var failedRounds = 0
                    while (isActive && round < maxRounds) {
                        val now0 = System.currentTimeMillis()
                        if (timed && workUntil - now0 < 40_000L) break
                        round++
                        tracker.workerDone = false
                        tracker.polishing = true
                        tracker.polishOps = 0
                        tracker.windowStart = now0
                        tracker.windowEnd = if (timed) workUntil else 0L
                        val snap = snapshot(ctx, dir, name)
                        sayWork(ctx, if (round == 1) tr(
                            if (timed) "The first version works. I'll keep improving it until your time is used." else "The first version works. Now polishing it to high detail.",
                            if (timed) "पहला वर्ज़न चल रहा है। आपका समय पूरा होने तक इसे और बेहतर करता रहूँगा।" else "पहला वर्ज़न चल रहा है। अब इसे हाई डिटेल तक पॉलिश कर रहा हूँ।",
                            if (timed) "প্রথম সংস্করণ কাজ করছে। আপনার সময় শেষ না হওয়া পর্যন্ত এটি আরও ভালো করব।" else "প্রথম সংস্করণ কাজ করছে। এবার হাই ডিটেইলে পালিশ করছি।")
                        else tr("Improvement round $round.", "सुधार का राउंड $round।", "উন্নতির রাউন্ড $round।"))
                        var review: String? = null
                        if (!timed || workUntil - System.currentTimeMillis() > 90_000L) {
                            val heads = dir.walkTopDown().filter { it.isFile && !it.relativeTo(dir).path.startsWith(".") }.take(6)
                                .joinToString("\n\n") { "// ${it.name}\n" + runCatching { it.readText().take(1_200) }.getOrDefault("") }
                            review = narrate(ctx, "You are a senior reviewer. List the 8 most valuable concrete improvements still missing in this project (missing features or details, visual design, responsiveness, animations, game feel or UX, bugs, accessibility). Be specific and short, one per line, plain text. Request: $spec\n\n$heads", maxTokens = 500)
                        }
                        val polish = runWorker(ctx, refinePrompt(name, spec, review, round) + timeNote(2), tracker, useCustom = engine == Engine.CUSTOM && !takeover, untilMs = workUntil, shown = polishText(round))
                        tracker.polishing = false
                        if (!polish.ok && !polish.timedOut) lastError = polish.error
                        var after = ProjectBuilder.check(dir, false)
                        if (!after.ok && (!timed || workUntil - System.currentTimeMillis() > 15_000L)) {
                            runWorker(ctx, resumePrompt(name, spec, dir, after.problems, lastError) + timeNote(if (timed) 1 else 0), tracker, useCustom = engine == Engine.CUSTOM && !takeover, untilMs = workUntil, shown = continuedText())
                            after = ProjectBuilder.check(dir, false)
                        }
                        if (!after.ok) {
                            restoreSnapshot(snap, dir)
                            sayWork(ctx, tr("That change broke the project, so I restored the last working version.", "उस बदलाव से प्रोजेक्ट टूट गया, इसलिए पिछला चलने वाला वर्ज़न वापस लगा दिया।", "ওই পরিবর্তনে প্রজেক্ট ভেঙে গেছে, তাই আগের চালু সংস্করণ ফিরিয়ে দিয়েছি।"))
                            failedRounds++
                        } else if (!polish.ok && !polish.timedOut) failedRounds++ else failedRounds = 0
                        if (polish.timedOut || failedRounds >= 2) break
                        if (polish.limitHit && engine == Engine.CUSTOM && !takeover) {
                            if (JarvisApp.instance.preferences.apiKey.isNotBlank()) {
                                takeover = true
                                _status.value = _status.value.copy(engineLabel = "Gemini")
                                sayWork(ctx, tr("The custom model's key limit is reached. Gemini continues the improvements.", "कस्टम मॉडल की की-लिमिट पूरी हो गई। जेमिनी सुधार जारी रखेगा।", "কাস্টম মডেলের কী-লিমিট শেষ। জেমিনি উন্নতির কাজ চালিয়ে যাবে।"))
                            } else break
                        }
                    }
                    tracker.polishing = false
                }
                tracker.workerDone = true
                if (timed && quality && (workUntil - System.currentTimeMillis() > 6_000L)) {
                    val remainingSec = ((workUntil - System.currentTimeMillis()) / 1000).toInt()
                    if (remainingSec > 5) {
                        delay((remainingSec - 4) * 1000L)
                    }
                }
                if (!ok) {
                    monitor.cancel()
                    sayWork(ctx, if (outOfTime) tr("Your time ran out before anything runnable was ready. Your files are saved in the project $name.",
                        "आपका समय खत्म हो गया और चलने लायक कुछ तैयार नहीं हो सका। आपकी फ़ाइलें प्रोजेक्ट $name में सुरक्षित हैं।",
                        "আপনার সময় শেষ হয়ে গেছে, চালানোর মতো কিছু তৈরি হয়নি। আপনার ফাইল $name প্রজেক্টে সংরক্ষিত আছে।") else tr("I couldn't finish the build after several restarts. Your files are saved in the project $name.",
                        "कई बार दोबारा कोशिश के बाद भी बिल्ड पूरा नहीं हो सका। आपकी फ़ाइलें प्रोजेक्ट $name में सुरक्षित हैं।",
                        "বহুবার চেষ্টার পরও বিল্ড শেষ হয়নি। আপনার ফাইল $name প্রজেক্টে সংরক্ষিত আছে।"))
                    setPhase(Phase.AWAIT_ACK, tr("Build incomplete", "बिल्ड अधूरा", "বিল্ড অসম্পূর্ণ"), tracker.percent)
                    holdUntilAck(ctx)
                    return@launch
                }
                monitor.cancel()
                if (timed) {
                    val used = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
                    sayWork(ctx, tr("Done in ${spokenDuration(used, 0)} of your ${spokenDuration(budgetSec, 0)}.",
                        "आपके ${spokenDuration(budgetSec, 1)} में से ${spokenDuration(used, 1)} में पूरा हो गया।",
                        "আপনার ${spokenDuration(budgetSec, 2)} এর মধ্যে ${spokenDuration(used, 2)}-এ শেষ হয়েছে।"))
                }
                val checkingNotice = MultilingualCommandHelper.getLocalizedFilesChecking(lang)
                say(ctx, checkingNotice, logChat = false)
                hostAndPreview(ctx, dir, name)
            } catch (e: CancellationException) {
                monitor.cancel(); throw e
            } catch (e: Exception) {
                Log.e(TAG, "Build supervision failed", e)
                monitor.cancel()
                sayWork(ctx, tr("The build hit an unexpected problem, but your files are preserved.", "बिल्ड में अनपेक्षित समस्या आई, पर आपकी फ़ाइलें सुरक्षित हैं।", "বিল্ডে অপ্রত্যাশিত সমস্যা হয়েছে, তবে ফাইল সুরক্ষিত আছে।"))
                finish(ctx, false, e.message ?: "Error")
            }
        }
    }

    private fun sayWork(ctx: Context, text: String) = say(ctx, text, logChat = true)

    private fun snapshot(ctx: Context, dir: File, name: String): File? = runCatching {
        val snap = File(ctx.cacheDir, "jarvis-snapshot/$name")
        snap.deleteRecursively()
        dir.copyRecursively(snap, overwrite = true)
        snap
    }.getOrNull()

    private fun restoreSnapshot(snap: File?, dir: File) {
        if (snap == null || !snap.isDirectory) return
        runCatching {
            dir.walkTopDown().filter { it.isFile && !it.relativeTo(dir).path.startsWith(".") && !File(snap, it.relativeTo(dir).path).exists() }.toList().forEach { it.delete() }
            snap.copyRecursively(dir, overwrite = true)
        }
    }

    private suspend fun runWorker(ctx: Context, prompt: String, tracker: Tracker, useCustom: Boolean, untilMs: Long = Long.MAX_VALUE, shown: String = continuedText()): WorkerResult {
        tracker.shown = shown
        suspend fun once() = if (useCustom) runCustomWorker(ctx, prompt, tracker) else runGeminiWorker(ctx, prompt, tracker)
        if (untilMs == Long.MAX_VALUE) return once()
        val remaining = untilMs - System.currentTimeMillis()
        if (remaining <= 0) return WorkerResult(false, "The time budget is used up.", timedOut = true)
        // A worker that is still going when its slice ends is stopped; what it already wrote stays on disk.
        return withTimeoutOrNull(remaining) { once() } ?: run {
            if (useCustom) runCatching { ProviderRuntime.chatEngine(ctx).cancel() }
            WorkerResult(false, "The time budget was reached.", timedOut = true)
        }
    }

    /** The owner's own request as the ChatBox should show it: their words, plus the time if they gave one. High quality is already in their words. */
    private fun askedText(): String = spec.trim() + if (budgetSec > 0) "  ⏱ ${spokenDuration(budgetSec, langIndex())}" else ""

    private fun continuedText(): String = "↻ " + tr("Continuing from where it stopped", "जहाँ रुका था वहीं से जारी", "যেখানে থেমেছিল সেখান থেকে চালিয়ে যাচ্ছি")

    private fun polishText(round: Int): String = "✨ " + tr("Improving it, round $round", "इसे बेहतर कर रहा हूँ, राउंड $round", "আরও ভালো করছি, রাউন্ড $round")

    private fun timeRules(secs: Int, mode: Int): String =
        "\nTIME: the owner set a time budget and Jarvis uses all of it. About ${spokenDuration(secs, 0)} remain for this step. " +
            when (mode) {
                0 -> "First write a complete, working version of the whole request within that time (every feature, no placeholders, one tool call per file in the order index.html, style.css, script.js, with no long planning). Improvement rounds follow, so do not cut features and do not stop early."
                1 -> "This is the final stretch: finish everything that is missing, make sure it all works, run project_check, and stop. Add nothing new and risky."
                else -> "Use the whole time to make the project better in the ways asked; keep everything that already works and finish cleanly before the time is gone."
            }

    private fun refinePrompt(name: String, request: String, review: String?, round: Int): String =
        "The project \"$name\" (request: \"$request\") already works. This is improvement round $round: raise it further by editing the existing files in place. Never recreate the project and never remove a feature that works.\n" +
            (if (!review.isNullOrBlank()) "Reviewer notes to apply:\n$review\n" else "") +
            (if (quality) qualityRules else "- Fix bugs, complete anything that feels unfinished or placeholder-like, and polish the visual design, responsiveness and interactions.\n") +
            "Then run project_check on \"$name\" and fix any problem. Do not start the preview."

    private suspend fun runCustomWorker(ctx: Context, prompt: String, tracker: Tracker): WorkerResult {
        val eng = ProviderRuntime.chatEngine(ctx)
        var waited = 0
        while (eng.isBusy && waited < 600) { delay(300); waited++ }
        val feeder = scopeFeed(eng, tracker)
        try {
            // The ChatBox shows only the owner's words; the full instructions go to the model but never into the chat.
            return when (val out = eng.send(tracker.shown.ifBlank { spec }, prompt, categoryOverride = WorkCategory.CODING)) {
                is SendOutcome.Started -> {
                    while (eng.isBusy) delay(300)
                    val st = eng.state.value
                    if (st is EngineState.Failed) WorkerResult(false, st.error.message, limitHit = isLimit(st.error.kind, st.error.message)) else WorkerResult(true)
                }
                // Every key of the custom provider is resting or refused: that is a limit, and Gemini carries on.
                is SendOutcome.Blocked -> WorkerResult(false, out.reason, limitHit = true)
                SendOutcome.NotActive -> WorkerResult(false, "The custom model is no longer active.", limitHit = true)
                SendOutcome.Busy -> WorkerResult(false, "The custom model is busy.")
            }
        } finally {
            feeder.cancel()
        }
    }

    private fun isLimit(kind: ProviderErrorKind, message: String): Boolean {
        if (kind == ProviderErrorKind.RATE_LIMITED || kind == ProviderErrorKind.AUTH_FAILED || kind == ProviderErrorKind.KEY_REJECTED) return true
        val l = message.lowercase(Locale.ROOT)
        return listOf("quota", "limit", "exhaust", "billing", "credit", "resting", "no usable", "insufficient").any { l.contains(it) }
    }

    private fun scopeFeed(eng: ProviderChatEngine, tracker: Tracker): Job = scope.launch {
        eng.workProgress.collect { p ->
            if (p.isWorking) {
                if (p.actionText.isNotBlank()) tracker.lastTool = p.actionText
                if (p.target.isNotBlank()) tracker.lastTarget = p.target.substringAfterLast('/')
            }
        }
    }

    private suspend fun runGeminiWorker(ctx: Context, prompt: String, tracker: Tracker): WorkerResult {
        val backend = ToolRuntime.backend(ctx) ?: return WorkerResult(false, "Workspace tools are unavailable.")
        val system = ChatContext.systemPrompt(JarvisApp.instance.preferences.userName, TokenModeStore.get(ctx)) + ProviderChatEngine.TOOL_PROMPT
        // Show the work in ChatBox exactly like a custom model's: code and terminal blocks that appear live.
        val store = ProviderChatBridge(JarvisApp.instance.chatRepository)
        val turnId = store.begin(tracker.shown.ifBlank { spec }, null)
        val blocks = LinkedHashMap<String, String>()

        var lastErrorMsg = "Gemini build failed"
        // Try up to 3 times strictly with Google AI Studio authorized API keys
        for (attempt in 1..3) {
            val provider = geminiProvider(ctx, forBuild = true) ?: return WorkerResult(false, "No Gemini API key is saved.")
            try {
                val loop = GeminiToolLoop(ProviderRuntime.transport(), backend)
                val res = loop.run(
                    ChatRequest(provider, system, listOf(ChatMessage(ChatRole.USER, prompt)), maxTokens = TokenModeStore.get(ctx).maxTokens, thinking = if (quality) Thinking.DEFAULT else Thinking.LOW),
                    "web-build",
                    onActivity = { },
                    onStep = { s: LiveToolStep ->
                        tracker.lastTool = s.toolName
                        if (s.target.isNotBlank()) tracker.lastTarget = s.target.substringAfterLast('/')
                        if (s.codeOrOutput.isNotBlank() && s.toolName in setOf("write_file", "edit_file", "terminal_run", "read_file")) {
                            blocks[s.target] = "```${s.language}:${s.target}\n${s.codeOrOutput}\n```"
                            store.updatePartial(turnId, blocks.values.joinToString("\n\n"))
                        }
                    }
                )
                store.finish(turnId, (blocks.values.joinToString("\n\n") + "\n\n" + res.text.trim()).trim().ifBlank { "Done." })
                return WorkerResult(true)
            } catch (e: CancellationException) {
                store.finish(turnId, (blocks.values.joinToString("\n\n") + "\n\n⏱ Stopped to stay within the time limit; Jarvis is wrapping up.").trim())
                throw e
            } catch (e: Exception) {
                lastErrorMsg = if (e is ProviderException) e.error.message else (e.message ?: "Google AI Studio API error")
                Log.w(TAG, "runGeminiWorker Google AI Studio attempt $attempt failed: $lastErrorMsg", e)
                rotateToNextGoogleAiStudioKey(ctx)
                if (attempt < 3) {
                    delay(1_000L * attempt)
                }
            }
        }

        // All 3 attempts failed: mark failure in ChatBox and announce exact error out loud
        store.fail(turnId, blocks.values.joinToString("\n\n"), ProviderChatEngine.ERROR_PREFIX + lastErrorMsg)
        val spokenError = tr(
            "Gemini build attempt failed after 3 tries. Error: $lastErrorMsg",
            "जेमिनी बिल्ड 3 बार कोशिश के बाद भी असफल रहा। एरर: $lastErrorMsg",
            "৩ বার চেষ্টার পরও জেমিনি বিল্ড ব্যর্থ হয়েছে। এরর: $lastErrorMsg"
        )
        say(ctx, spokenError, logChat = true)
        return WorkerResult(false, lastErrorMsg)
    }

    /**
     * The real percentage, from what exists on disk. The model first writes a .plan (one "path|lines" line per file),
     * so progress is the share of planned work whose files are written. Scale: 1-4 starting, 5-80 writing files
     * (5-60 then 60-80 polishing in high-quality mode), 88 checking, 92 hosting, 97 opening the preview, 100 done.
     */
    private fun workPercent(dir: File, files: List<File>, tracker: Tracker): Int {
        if (tracker.workerDone) return 88
        val writeEnd = if (quality || deadlineAt > 0) 60 else 80
        if (tracker.polishing) {
            val byOps = writeEnd + minOf(80 - writeEnd - 1, tracker.polishOps * 3)
            if (tracker.windowEnd > tracker.windowStart) {
                val frac = ((System.currentTimeMillis() - tracker.windowStart).toDouble() / (tracker.windowEnd - tracker.windowStart)).coerceIn(0.0, 1.0)
                return minOf(86, maxOf(byOps, writeEnd + (26 * frac).toInt()))
            }
            return byOps
        }
        val plan = File(dir, ".plan")
        val planned = if (plan.isFile) parsePlan(plan) else emptyList()
        val rel = files.map { it.relativeTo(dir).path.replace('\\', '/') to it }
        val fraction: Double
        if (planned.isNotEmpty()) {
            val since = plan.lastModified()
            var done = 0.0
            var total = 0.0
            planned.forEach { (path, est) ->
                total += est
                val p = path.removePrefix("./").trimStart('/')
                val f = rel.firstOrNull { (r, _) -> r == p || p.endsWith("/$r") || r.endsWith("/$p") }?.second
                if (f != null && f.length() > 0 && f.lastModified() >= since) done += est
            }
            fraction = if (total > 0) done / total else 0.0
        } else if (files.isEmpty()) {
            return if (tracker.lastTool.isBlank()) 1 else 3
        } else {
            // No plan was written: a typical site is three files, and the bar never claims to be finished before the checks.
            fraction = minOf(0.9, files.size.toDouble() / maxOf(3, files.size + 1))
        }
        return if (planned.isNotEmpty()) 5 + ((writeEnd - 5) * fraction).toInt() else 4 + ((writeEnd - 5) * fraction).toInt()
    }

    private fun parsePlan(plan: File): List<Pair<String, Int>> = runCatching {
        plan.readLines().mapNotNull { line ->
            val parts = line.trim().split('|', ':', ',').map { it.trim() }
            val path = parts.firstOrNull()?.takeIf { it.isNotBlank() && !it.startsWith("#") } ?: return@mapNotNull null
            path to (parts.getOrNull(1)?.filter { it.isDigit() }?.toIntOrNull() ?: 80).coerceIn(20, 2_000)
        }.take(12)
    }.getOrDefault(emptyList())

    /** Reads the project folder every two seconds so progress, file and size are real, not guessed. */
    private suspend fun monitor(ctx: Context, dir: File, tracker: Tracker, startedAt: Long) {
        val narrator = BuildNarrator(
            request = { spec },
            language = { lang },
            ask = { system, prompt -> narrate(ctx, prompt, system) },
            fallback = { file, role, symbols, isNew ->
                val names = symbols.take(3).joinToString(", ")
                val roleHi = when (role) { "page structure" -> "पेज का ढांचा"; "styling" -> "स्टाइलिंग"; "logic" -> "लॉजिक"; "data" -> "डेटा"; else -> "कोड" }
                val roleBn = when (role) { "page structure" -> "পেজের কাঠামো"; "styling" -> "স্টাইলিং"; "logic" -> "লজিক"; "data" -> "ডেটা"; else -> "কোড" }
                if (isNew) tr("Started the $role in $file" + (if (names.isNotBlank()) ": $names." else "."),
                    "$file में $roleHi शुरू किया" + (if (names.isNotBlank()) ": $names।" else "।"),
                    "$file-এ $roleBn শুরু করেছি" + (if (names.isNotBlank()) ": $names।" else "।"))
                else tr("Extending the $role in $file" + (if (names.isNotBlank()) ": $names." else "."),
                    "$file में $roleHi आगे बढ़ा रहा हूँ" + (if (names.isNotBlank()) ": $names।" else "।"),
                    "$file-এ $roleBn এগিয়ে নিচ্ছি" + (if (names.isNotBlank()) ": $names।" else "।"))
            },
            smartPurpose = { file, content, isNew ->
                ProjectCodeAnalyzer.generateFilePurpose(file, content, isNew, lang)
            }
        )
        var narration: Job? = null
        var lastKey = ""
        try {
        while (true) {
            val files = dir.walkTopDown().filter { it.isFile && !it.relativeTo(dir).path.startsWith(".") }.toList()
            val latest = files.maxByOrNull { it.lastModified() }
            val key = tracker.lastTool + "|" + tracker.lastTarget
            if (key != lastKey) { lastKey = key; if (tracker.polishing && tracker.lastTarget.isNotBlank()) tracker.polishOps++ }
            tracker.bump(workPercent(dir, files, tracker))
            val timed = deadlineAt > 0
            val now = System.currentTimeMillis()
            val current = latest?.name ?: tracker.lastTarget
            val lines = latest?.let { runCatching { it.readLines().size }.getOrDefault(0) } ?: 0
            val elapsed = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
            val p = tracker.percent
            // With a time limit the countdown is the real time left to the deadline, not a guess.
            val eta = if (timed) ((deadlineAt - now) / 1000).toInt().coerceAtLeast(0) else if (p in 10..99) maxOf(5, elapsed * (100 - p) / p) else 0
            val stage = when {
                files.isEmpty() -> tr("Planning the project", "प्रोजेक्ट की प्लानिंग", "প্রজেক্ট প্ল্যান করছি")
                tracker.workerDone -> tr("Checking the files", "फ़ाइलें जाँच रहा हूँ", "ফাইল যাচাই করছি")
                else -> tr("Writing $current ($lines lines)", "$current लिख रहा है ($lines लाइनें)", "$current লিখছে ($lines লাইন)")
            }
            publish(ctx, p, stage, current, files.size, eta)

            // What to say is decided from the real code, off the progress loop so a slow model never freezes the status.
            if (narration?.isActive != true) {
                val snapshot = files
                val pct = p
                val etaNow = eta
                val done = tracker.workerDone
                narration = scope.launch {
                    val line = narrator.tick(dir, snapshot, pct, etaNow, done, SPEAK_EVERY_MS, NUDGE_AFTER_MS) ?: return@launch
                    say(ctx, line, logChat = false)
                    lastNarration = line.take(140)
                }
            }
            delay(2_000)
        }
        } finally {
            narration?.cancel()
        }
    }

    private suspend fun hostAndPreview(ctx: Context, dir: File, name: String) {
        publish(ctx, 92, tr("Starting local server", "लोकल सर्वर चालू कर रहा हूँ", "লোকাল সার্ভার চালু করছি"), "", _status.value.filesWritten, 10)
        val built = ToolRuntime.get(ctx)
        val serveDir = listOf("dist", "build").map { File(dir, it) }.firstOrNull { File(it, "index.html").isFile } ?: dir
        val url = try { built.servers.servers.start(name, serveDir, false).url } catch (e: Exception) {
            say(ctx, tr("I couldn't start the local server: ${e.message}", "लोकल सर्वर चालू नहीं हो सका: ${e.message}", "লোকাল সার্ভার চালু করা যায়নি: ${e.message}"))
            finish(ctx, false, "Server failed"); return
        }
        val serverNotice = MultilingualCommandHelper.getLocalizedServerCreatedAndPreview(url, lang)
        publish(ctx, 97, tr("Opening preview", "प्रीव्यू खोल रहा हूँ", "প্রিভিউ খুলছি"), "", _status.value.filesWritten, 3)
        val uri = Uri.parse(url)
        val base = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = if (previewOpened) true else (ActivityLauncherHelper.startActivitySafely(ctx, Intent(base).setPackage("com.android.chrome")) ||
            ActivityLauncherHelper.startActivitySafely(ctx, base))
        previewOpened = true
        // Wherever the user is (another app, locked screen), this also offers the preview as a tap-to-open / full-screen alert.
        postPreviewNotification(ctx, url, name)
        _status.value = _status.value.copy(previewUrl = url)
        publish(ctx, 100, tr("Done", "पूरा हुआ", "সম্পন্ন"), "", _status.value.filesWritten, 0)
        runCatching { JarvisApp.instance.chatRepository.addTurn("", "✅ \"$name\" is live at $url (local to this phone)." + if (!opened) " Open it yourself: the browser couldn't be launched from the background." else " I've opened the preview.") }
        TaskProgressNotifier.postComplete(ctx, name, "Live at $url")
        val isGame = spec.contains("game", true) || dir.walkTopDown().any { it.name.endsWith(".html") && runCatching { it.readText().contains("canvas", true) }.getOrDefault(false) }
        val projectTypeStr = if (isGame) tr("game", "गेम", "গেম") else tr("website", "वेबसाइट", "ওয়েবসাইট")
        val explanation = ProjectCodeAnalyzer.generateExplanation(
            dir = dir,
            topic = name,
            type = projectTypeStr,
            url = url,
            language = lang,
            askAi = { system, prompt -> narrate(ctx, prompt, system, maxTokens = 350) }
        )
        val ackNotice = tr(
            "Say okay when you're done, and I'll stay ready until then.",
            "जब हो जाए तो ओके बोलिए, तब तक मैं तैयार रहूँगा।",
            "হয়ে গেলে ওকে বলুন, ততক্ষণ আমি তৈরি থাকব।"
        )
        val fullAnnouncement = "$serverNotice $explanation $ackNotice"
        setPhase(Phase.AWAIT_ACK, tr("Say okay when you're done", "हो जाए तो ओके बोलिए", "হয়ে গেলে ওকে বলুন"), 100)
        saySuspending(ctx, fullAnnouncement, logChat = false)
        holdUntilAck(ctx)
    }

    private suspend fun holdUntilAck(ctx: Context) {
        val until = System.currentTimeMillis() + ACK_WAIT_MS
        while (phase == Phase.AWAIT_ACK && System.currentTimeMillis() < until) delay(1_000)
        if (phase == Phase.AWAIT_ACK) finish(ctx, true, "Completed")
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Prompts
    // ─────────────────────────────────────────────────────────────────────────────

    private val qualityRules =
        "QUALITY MODE & HIGH DETAIL: the owner wants the best possible result, not a quick draft. First write a short plan, then build to a premium standard:\n" +
            "- HIGH DETAIL REQUIREMENT: Every single element must be fully and perfectly implemented. Zero placeholders, zero TODO comments, zero dummy stubs. Full production-ready semantic HTML5, modern CSS3 styling, and JavaScript interactivity.\n" +
            "- A cohesive, modern visual design: a defined colour palette, typography scale, spacing system, gradients, shadows, smooth transitions and tasteful animation. Fully responsive from 320px phones to desktops, with touch-friendly controls.\n" +
            "- Split the code cleanly (index.html, css/style.css, js/*.js) with clear names; rich, realistic content, no lorem ipsum.\n" +
            "- Accessibility: semantic HTML, labels, keyboard support, good contrast.\n" +
            "- For a game: polished game feel (particles or screen shake, sound effects via the Web Audio API with a mute button), scoring with a saved high score (localStorage), start, pause and game-over screens, increasing difficulty, and both keyboard and touch controls.\n" +
            "- For a website: a hero section, several well-designed sections, a working navigation, a contact or call-to-action form with validation, and subtle scroll animations.\n" +
            "- Handle edge cases and errors; no console errors.\n"

    private fun polishPrompt(name: String, request: String, review: String?): String =
        "The project \"$name\" (request: \"$request\") works. Now raise it to premium quality by editing the existing files in place; do not recreate the project.\n" +
            (if (!review.isNullOrBlank()) "Reviewer notes to apply:\n$review\n" else "") + qualityRules +
            "Then run project_check on \"$name\" and fix any problem. Do not start the preview."

    private fun buildPrompt(name: String, request: String): String =
        "Build a complete, polished, fully working website or browser game for this request: \"$request\".\n" +
            (if (quality) qualityRules else "") +
            "Rules:\n" +
            "- Use the project name \"$name\". Create it with project_create (template static), then write EVERY file with the file tools (index.html, style.css, script.js, and any others needed). Write each file in full, no placeholders or TODOs.\n" +
            "- It must run offline in a plain browser: no build step, no external CDN dependencies.\n" +
            "- Right after project_create, write a file named .plan inside the project folder (same path style as the other files). It has one line per file you will create, written as path|estimated number of lines, and nothing else. Jarvis uses it to show exact progress, so list every file and then write exactly those files.\n" +
            "- Put the .plan and ALL the write_file calls in ONE response (parallel tool calls, .plan first) instead of one file per turn" + (if (quality) ", but think carefully so that every detail is right.\n" else ", and do not deliberate at length.\n") +
            "- When all files are written, run project_check on \"$name\" and fix every problem it reports.\n" +
            "- Do NOT start the preview or open a browser: Jarvis hosts and opens it automatically.\n" +
            "- Finish with a two-line summary of what you built."

    private fun resumePrompt(name: String, request: String, dir: File, problems: List<String>, error: String?): String {
        val files = dir.walkTopDown().filter { it.isFile && !it.relativeTo(dir).path.startsWith(".") }
            .joinToString("\n") { "- ${it.relativeTo(dir).path} (${it.length()} bytes, ${runCatching { it.readLines().size }.getOrDefault(0)} lines)" }
            .ifBlank { "(no files yet)" }
        return "Continue the unfinished project \"$name\" for the request: \"$request\". The previous run stopped" +
            (error?.let { " ($it)" } ?: "") + ". Do NOT start over and do NOT rewrite files that are complete.\n" +
            "Files already on disk:\n$files\n" +
            (if (problems.isNotEmpty()) "Problems to fix:\n" + problems.joinToString("\n") { "- $it" } + "\n" else "") +
            "Read the existing files, finish whatever is missing or truncated (including any file that ends mid-way), then run project_check on \"$name\" and fix problems. Do not start the preview."
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────────

    private fun customReady(ctx: Context): Boolean {
        // Exactly the check the ChatBox makes before it sends: a provider is "set" only when it has a model and a
        // usable key. A half-configured or placeholder provider must not be treated as ready.
        val m = ProviderRuntime.manager(ctx)
        val coding = m.resolveChat(WorkCategory.CODING)
        val resolved = if (coding is ChatResolution.NotActive) m.resolveChat() else coding
        return resolved is ChatResolution.Ready
    }

    private fun extractCodeBlocksFromRecentChat(dir: File) {
        val lastTurns = JarvisApp.instance.chatRepository.turns.value.takeLast(3)
        for (turn in lastTurns.reversed()) {
            val text = turn.jarvisResponse
            if (text.isBlank()) continue
            extractAndWriteFiles(dir, text)
            if (File(dir, "index.html").isFile) break
        }
    }

    fun extractAndWriteFiles(dir: File, text: String): Boolean {
        var written = false
        val namedRegex = Regex("```(?:[a-zA-Z0-9_-]+:)?([a-zA-Z0-9_./-]+\\.[a-zA-Z0-9]+)\\s*\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)
        for (match in namedRegex.findAll(text)) {
            val rawPath = match.groupValues[1].trim().trimStart('/')
            val content = match.groupValues[2].trim()
            if (rawPath.isNotEmpty() && content.isNotEmpty()) {
                val targetFile = File(dir, rawPath)
                targetFile.parentFile?.mkdirs()
                targetFile.writeText(content)
                written = true
            }
        }
        if (File(dir, "index.html").isFile) return true

        val htmlRegex = Regex("```(?:html)?\\s*\\n(<!DOCTYPE html.*?)```", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val htmlMatch = htmlRegex.find(text)
        if (htmlMatch != null) {
            val htmlContent = htmlMatch.groupValues[1].trim()
            File(dir, "index.html").writeText(htmlContent)
            written = true
        }

        val cssRegex = Regex("```css\\s*\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)
        val cssMatch = cssRegex.find(text)
        if (cssMatch != null) {
            val cssContent = cssMatch.groupValues[1].trim()
            File(dir, "style.css").writeText(cssContent)
            written = true
        }

        val jsRegex = Regex("```(?:javascript|js)\\s*\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)
        val jsMatch = jsRegex.find(text)
        if (jsMatch != null) {
            val jsContent = jsMatch.groupValues[1].trim()
            File(dir, "script.js").writeText(jsContent)
            written = true
        }

        return written
    }

    private fun activeCustomName(ctx: Context): String = ProviderRuntime.manager(ctx).activeProfile()?.displayName ?: "Custom model"

    private fun geminiProvider(ctx: Context, forBuild: Boolean): ResolvedProvider? {
        val key = JarvisApp.instance.preferences.apiKey.trim()
        if (key.isBlank()) return null
        // Strictly use official Google AI Studio Gemini API endpoint and model
        val modelId = GeminiConstants.MODEL_3_1_FLASH_AGENT
        return ResolvedProvider(
            profileId = "gemini-builtin", name = "Gemini", baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            protocol = ProviderProtocol.GEMINI, auth = AuthSpec(AuthMethod.X_GOOG_API_KEY, "", ApiKeySecret(key.toCharArray())),
            modelId = modelId, model = ModelInfo(id = modelId, manual = true), realtimeUrl = "", sttModelId = "", settings = emptyMap()
        )
    }

    private fun rotateToNextGoogleAiStudioKey(ctx: Context): Boolean {
        return try {
            val rotation = ProviderRuntime.keyRotation(ctx)
            if (!rotation.hasKeys("gemini")) return false
            val current = JarvisApp.instance.preferences.apiKey.trim()
            val tried = HashSet<String>()
            var next: String? = null
            var nextLabel = ""
            for (i in 0 until 8) {
                val lease = rotation.lease("gemini", tried) ?: break
                val text = lease.secret.reveal().trim()
                lease.secret.wipe()
                if (text == current) {
                    rotation.reportFailure(lease.keyId, ProviderError(ProviderErrorKind.RATE_LIMITED, "Google AI Studio quota or rate limit reached during build"))
                    tried += lease.keyId
                } else {
                    next = text
                    nextLabel = lease.label
                    break
                }
            }
            val key = next ?: return false
            JarvisApp.instance.preferences.apiKey = key
            Log.i(TAG, "Rotated Google AI Studio key to '$nextLabel'")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Google AI Studio key rotation failed: ${e.message}")
            false
        }
    }

    /** Short side-calls for the live review and the rhyme. Gemini when a key exists, otherwise the active custom model. */
    private suspend fun narrate(ctx: Context, prompt: String, system: String = "You are JARVIS. Reply with plain spoken text only.", maxTokens: Int = 160): String? {
        val provider = geminiProvider(ctx, false)
            ?: ((ProviderRuntime.manager(ctx).resolveChat(WorkCategory.CHAT)) as? ChatResolution.Ready)?.provider
            ?: return null
        return try {
            val sb = StringBuilder()
            withTimeoutOrNull(25_000L) {
                ChatClientFactory.create(provider.protocol, ProviderRuntime.transport())
                    .stream(ChatRequest(provider, system, listOf(ChatMessage(ChatRole.USER, prompt)), maxTokens = maxTokens, thinking = Thinking.MINIMAL))
                    .collect { ev -> if (ev is ChatEvent.Delta) sb.append(ev.text) }
            }
            sb.toString().trim().ifBlank { null }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.w(TAG, "narrate failed", e); null }
    }

    private fun uniqueProjectName(root: File, request: String): String {
        val words = request.lowercase(Locale.ROOT).split(Regex("[^a-z0-9]+")).filter { it.length > 2 && it !in setOf("create", "build", "make", "website", "web", "game", "the", "for", "with", "and", "jarvis", "hey", "banao") }
        val base = words.take(3).joinToString("-").ifBlank { "jarvis-site" }
        var name = base; var i = 2
        while (File(root, name).exists() && File(root, name).list()?.isNotEmpty() == true) name = "$base-${i++}"
        return name
    }

    private fun tr(en: String, hi: String, bn: String): String = when {
        lang.contains("hindi", true) || lang.contains("hinglish", true) -> hi
        lang.contains("bengali", true) || lang.contains("bangla", true) -> bn
        else -> en
    }

    private fun spokenDuration(sec: Int, l: Int): String {
        if (sec < 60) return when (l) { 1 -> "$sec सेकंड"; 2 -> "$sec সেকেন্ড"; else -> "$sec seconds" }
        val m = (sec + 30) / 60
        return when (l) { 1 -> "$m मिनट"; 2 -> "$m মিনিট"; else -> if (m == 1) "1 minute" else "$m minutes" }
    }

    private const val PREFS = "jarvis_web_build_state"

    private fun saveState(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("phase", phase.name).putString("engine", engine.name).putString("spec", spec)
            .putString("lang", lang).putString("project", projectName).putBoolean("quality", quality)
            .putInt("budget", budgetSec).putLong("deadline", deadlineAt).apply()
    }

    private fun clearState(ctx: Context) { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply() }

    /**
     * Called when the build service restarts (the app was closed or the process was killed mid-build):
     * restores the request and carries on from the files already saved, or goes back to waiting for the custom model.
     */
    fun resumeIfPending(context: Context) {
        val ctx = context.applicationContext
        if (phase != Phase.IDLE) return
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = p.getString("phase", "") ?: ""
        if (saved != Phase.BUILDING.name && saved != Phase.WAIT_CUSTOM.name) return
        appCtx = ctx
        spec = p.getString("spec", "") ?: ""
        lang = p.getString("lang", "English") ?: "English"
        engine = if (p.getString("engine", "") == Engine.CUSTOM.name) Engine.CUSTOM else Engine.GEMINI
        quality = p.getBoolean("quality", false)
        budgetSec = p.getInt("budget", 0)
        deadlineAt = p.getLong("deadline", 0L)
        projectName = p.getString("project", "") ?: ""
        previewOpened = false
        if (spec.isBlank()) { clearState(ctx); return }
        acquireLock(ctx)
        if (!PermissionPreferences.isAutoApproveAll(ctx)) { priorAutoApprove = false; autoApproveTouched = true; PermissionPreferences.setAutoApproveAll(ctx, true) }
        if (saved == Phase.WAIT_CUSTOM.name) afterEngineChosen(ctx) else beginBuild(ctx, resuming = true)
    }

    private fun postPreviewNotification(ctx: Context, url: String, name: String) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel("jarvis_web_preview") == null) {
                nm.createNotificationChannel(NotificationChannel("jarvis_web_preview", "JARVIS live preview", NotificationManager.IMPORTANCE_HIGH))
            }
            val open = PendingIntent.getActivity(
                ctx, 7791, Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            nm.notify(7791, NotificationCompat.Builder(ctx, "jarvis_web_preview")
                .setSmallIcon(R.drawable.ic_sparkle).setContentTitle("\"$name\" is live").setContentText("Tap to open the preview · $url")
                .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_STATUS)
                .setContentIntent(open).setFullScreenIntent(open, true).setAutoCancel(true).build())
        } catch (e: Exception) { Log.w(TAG, "preview notification failed", e) }
    }

    private fun setPhase(p: Phase, stage: String, percent: Int = 0) {
        phase = p
        appCtx?.let { saveState(it) }
        _status.value = _status.value.copy(phase = p, stage = stage, percent = percent)
        appCtx?.let { TaskProgressNotifier.postProgress(it, "Jarvis · web build", stage, percent.coerceIn(1, 100)) }
    }

    private fun publish(ctx: Context, percent: Int, stage: String, file: String, files: Int, eta: Int) {
        _status.value = _status.value.copy(phase = phase, percent = percent, stage = stage, currentFile = file, filesWritten = files, etaSec = eta)
        val etaText = if (eta > 0) " · ~${spokenDuration(eta, 0)} left" else ""
        // The notification carries the latest thing Jarvis said (what the code is for); the header keeps the exact file and numbers.
        val text = if (lastNarration.isNotBlank() && phase == Phase.BUILDING) lastNarration else "$stage$etaText"
        TaskProgressNotifier.postProgress(ctx, "Jarvis · web build", text, percent.coerceIn(1, 100))
    }

    /**
     * Everything the supervisor says is spoken in Jarvis's own voice (the live Gemini voice, or the installed offline
     * Jarvis voice). The phone's plain text-to-speech is used only when Jarvis's voice cannot be reached at all.
     */
    private fun say(ctx: Context, text: String, logChat: Boolean = false) {
        if (text.isBlank()) return
        if (logChat) runCatching { JarvisApp.instance.chatRepository.addTurn("🛠 JARVIS build", text) }
        val spokenInFull = text.length > 140
        if (!JarvisConversationService.announceMessage(text, spokenInFull)) {
            com.jarvis.assistant.tts.engine.JarvisVoiceDispatcher.getInstance(ctx).speak(text)
        }
    }

    private suspend fun saySuspending(ctx: Context, text: String, logChat: Boolean = false) {
        if (text.isBlank()) return
        say(ctx, text, logChat)
        // The live voice reports no "finished" callback, so give it roughly the time the sentence takes to say.
        delay((2_000L + text.length * 70L).coerceAtMost(45_000L))
    }

    private fun acquireLock(ctx: Context) {
        if (wakeLock?.isHeld == true) return
        wakeLock = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${ctx.packageName}:JarvisWebBuild")
            .also { it.acquire(LOCK_TIMEOUT_MS) }
    }

    private fun finish(ctx: Context, success: Boolean, message: String) {
        val cb = onComplete
        onComplete = null
        phase = Phase.IDLE
        job = null
        _status.value = Status()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        if (autoApproveTouched) { PermissionPreferences.setAutoApproveAll(ctx, priorAutoApprove); autoApproveTouched = false }
        if (!success) TaskProgressNotifier.cancel(ctx)
        clearState(ctx)
        WebBuildService.stop(ctx)
        cb?.invoke(success, message)
    }
}
