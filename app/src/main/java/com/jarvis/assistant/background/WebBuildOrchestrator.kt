package com.jarvis.assistant.background

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
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
import com.jarvis.assistant.provider.ProviderException
import com.jarvis.assistant.provider.ProviderProtocol
import com.jarvis.assistant.provider.ProviderRuntime
import com.jarvis.assistant.provider.ResolvedProvider
import com.jarvis.assistant.provider.SendOutcome
import com.jarvis.assistant.provider.TokenModeStore
import com.jarvis.assistant.provider.TransportException
import com.jarvis.assistant.provider.ProviderErrors
import com.jarvis.assistant.provider.WorkCategory
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
    private const val SPEAK_EVERY_MS = 35_000L
    private const val REVIEW_EVERY_MS = 120_000L
    private const val MAX_REVIEWS = 5
    private const val ACK_WAIT_MS = 15 * 60_000L
    private const val LOCK_TIMEOUT_MS = 6 * 60 * 60_000L

    enum class Phase { IDLE, ASK_ENGINE, WAIT_CUSTOM, ASK_SPEC, BUILDING, AWAIT_ACK }
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

    private class WorkerResult(val ok: Boolean, val error: String? = null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    @Volatile private var phase = Phase.IDLE
    @Volatile private var engine = Engine.GEMINI
    @Volatile private var spec = ""
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
    private val siteWords = listOf(
        "website", "web site", "webpage", "web page", "landing page", "web app", "webapp", "वेबसाइट", "ওয়েবসাইট", "sitio web", "webseite"
    )
    private val gameWords = listOf("web game", "webgame", "browser game", "html game", "html5 game", "game website", "game site")

    fun isWebBuildRequest(text: String): Boolean {
        val l = text.lowercase(Locale.ROOT)
        val create = createWords.any { l.contains(it) }
        if (!create) return false
        if (siteWords.any { l.contains(it) } || gameWords.any { l.contains(it) }) return true
        // "make a snake game" / "create a game" — a game with a creation verb means a browser game here.
        return Regex("\\b(game|खेल|গেম)\\b").containsMatchIn(l)
    }

    /** True when this utterance belongs to the orchestrator and must not go to the normal assistant path. */
    fun wantsInput(text: String): Boolean = when (phase) {
        Phase.IDLE -> isWebBuildRequest(text)
        Phase.ASK_ENGINE, Phase.ASK_SPEC -> true
        Phase.WAIT_CUSTOM -> parseEngine(text) == Engine.GEMINI || isCancel(text)
        Phase.BUILDING -> isCancel(text)
        Phase.AWAIT_ACK -> isAck(text) || isCancel(text) || isWebBuildRequest(text)
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
        val custom = listOf("custom", "कस्टम", "কাস্টম", "own model", "my model", "my own", "provider")
        val gemini = listOf("gemini", "jemini", "जेमिनी", "জেমিনি", "you create", "you write", "you do", "tum", "तुम", "aap", "आप", "তুমি")
        return when {
            custom.any { l.contains(it) } -> Engine.CUSTOM
            gemini.any { l.contains(it) } -> Engine.GEMINI
            else -> null
        }
    }

    /** A request with nothing to build yet ("create a website"): ask what kind. */
    private fun isGeneric(text: String): Boolean {
        val filler = (createWords + siteWords + gameWords + listOf(
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
            Phase.WAIT_CUSTOM -> if (parseEngine(text) == Engine.GEMINI) { job?.cancel(); engine = Engine.GEMINI; afterEngineChosen(ctx) }
            Phase.ASK_SPEC -> { spec = text.trim(); beginBuild(ctx) }
            Phase.AWAIT_ACK -> if (isAck(text)) { say(ctx, tr("Alright, Sir. I'm here whenever you need me.", "ठीक है सर, मैं यहीं हूँ।", "ঠিক আছে স্যার, আমি এখানেই আছি।")); finish(ctx, true, "Acknowledged") }
                else { finish(ctx, true, "Next request"); start(ctx, text, completion) }
            Phase.BUILDING -> Unit
        }
        return true
    }

    private fun start(ctx: Context, text: String, completion: ((Boolean, String) -> Unit)?) {
        onComplete = completion
        spec = text.trim()
        lang = MultilingualCommandHelper.resolveLanguage(ctx, text)
        acquireLock(ctx)
        if (!PermissionPreferences.isAutoApproveAll(ctx)) { priorAutoApprove = false; autoApproveTouched = true; PermissionPreferences.setAutoApproveAll(ctx, true) }
        val hasGemini = JarvisApp.instance.preferences.apiKey.isNotBlank()
        when {
            customReady(ctx) -> { engine = Engine.CUSTOM; afterEngineChosen(ctx) }
            hasGemini -> {
                setPhase(Phase.ASK_ENGINE, tr("Waiting for your choice", "आपके जवाब का इंतज़ार", "আপনার উত্তরের অপেক্ষা"))
                say(ctx, tr(
                    "No custom model is active. Should Gemini write the code, or will you set up a custom model for coding?",
                    "कोई कस्टम मॉडल एक्टिव नहीं है। क्या जेमिनी कोड लिखे, या आप कोडिंग के लिए कस्टम मॉडल सेट करेंगे?",
                    "কোনো কাস্টম মডেল চালু নেই। জেমিনি কি কোড লিখবে, নাকি আপনি কোডিংয়ের জন্য কাস্টম মডেল সেট করবেন?"))
            }
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
        if (choice == null) {
            say(ctx, tr("Please say Gemini, or custom model.", "कृपया बोलिए जेमिनी, या कस्टम मॉडल।", "অনুগ্রহ করে বলুন জেমিনি, অথবা কাস্টম মডেল।"))
            return
        }
        engine = choice
        afterEngineChosen(ctx)
    }

    private fun afterEngineChosen(ctx: Context) {
        if (engine == Engine.CUSTOM && !customReady(ctx)) {
            setPhase(Phase.WAIT_CUSTOM, tr("Waiting for custom model", "कस्टम मॉडल का इंतज़ार", "কাস্টম মডেলের অপেক্ষা"))
            say(ctx, tr(
                "Understood. I'll wait for your custom model and I won't go to sleep. I'll notice the moment it's active.",
                "ठीक है। मैं आपके कस्टम मॉडल का इंतज़ार करूँगा और सोऊँगा नहीं। एक्टिव होते ही मुझे पता चल जाएगा।",
                "ঠিক আছে। আমি আপনার কাস্টম মডেলের অপেক্ষা করব, ঘুমাব না। চালু হলেই আমি বুঝতে পারব।"))
            job = scope.launch {
                var lastRemind = System.currentTimeMillis()
                while (isActive) {
                    if (customReady(ctx)) {
                        engine = Engine.CUSTOM
                        say(ctx, tr("Custom model ${activeCustomName(ctx)} is active.", "कस्टम मॉडल ${activeCustomName(ctx)} एक्टिव है।", "কাস্টম মডেল ${activeCustomName(ctx)} চালু হয়েছে।"))
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
        if (isGeneric(spec)) {
            setPhase(Phase.ASK_SPEC, tr("Waiting for your idea", "आपके आइडिया का इंतज़ार", "আপনার আইডিয়ার অপেক্ষা"))
            say(ctx, tr("What kind of website or game should I build?", "कैसी वेबसाइट या गेम बनाऊँ?", "কেমন ওয়েবসাইট বা গেম বানাব?"))
        } else {
            beginBuild(ctx)
        }
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
        fun bump(p: Int) { if (p > percent) percent = p.coerceIn(0, 100) }
    }

    private fun beginBuild(ctx: Context) {
        setPhase(Phase.BUILDING, tr("Starting", "शुरू कर रहा हूँ", "শুরু করছি"), 3)
        val built = ToolRuntime.get(ctx)
        val name = uniqueProjectName(built.workspace.resolve("projects"), spec)
        val dir = built.workspace.project(name)
        val label = if (engine == Engine.CUSTOM) activeCustomName(ctx) else "Gemini"
        _status.value = _status.value.copy(engineLabel = label)
        runCatching { JarvisApp.instance.chatRepository.addTurn(spec, "🛠 Building \"$name\" with $label. I'll host it on the local server and open the preview automatically.") }
        say(ctx, tr(
            "Starting now. $label will write the code and I'll supervise it live.",
            "अभी शुरू करता हूँ। $label कोड लिखेगा और मैं लाइव निगरानी करूँगा।",
            "এখনই শুরু করছি। $label কোড লিখবে আর আমি লাইভ নজর রাখব।"))

        job = scope.launch {
            val tracker = Tracker()
            val startedAt = System.currentTimeMillis()
            val monitor = launch { monitor(ctx, dir, tracker, startedAt) }
            var attempt = 0
            var takeover = false
            var lastError: String? = null
            var prompt = buildPrompt(name, spec)
            var ok = false
            try {
                while (isActive) {
                    val result = runWorker(ctx, prompt, tracker, useCustom = engine == Engine.CUSTOM && !takeover)
                    if (!result.ok) lastError = result.error
                    val report = if (File(dir, "index.html").isFile || dir.walkTopDown().any { it.isFile }) ProjectBuilder.check(dir, false) else null
                    if (report != null && report.ok) { ok = true; break }
                    attempt++
                    if (attempt > MAX_RESUMES) break
                    val geminiAvailable = JarvisApp.instance.preferences.apiKey.isNotBlank()
                    if (engine == Engine.CUSTOM && !takeover && attempt >= CUSTOM_RETRIES_BEFORE_TAKEOVER && geminiAvailable) {
                        takeover = true
                        say(ctx, tr("The custom model keeps failing. I'm taking over from ${tracker.lastTarget.ifBlank { "the last file" }}.",
                            "कस्टम मॉडल बार-बार फेल हो रहा है। मैं ${tracker.lastTarget.ifBlank { "आखिरी फ़ाइल" }} से काम संभाल रहा हूँ।",
                            "কাস্টম মডেল বারবার ব্যর্থ হচ্ছে। আমি ${tracker.lastTarget.ifBlank { "শেষ ফাইল" }} থেকে কাজ নিচ্ছি।"))
                    } else {
                        say(ctx, tr("${label} stopped at ${tracker.lastTarget.ifBlank { "the planning step" }}. Restarting from exactly there.",
                            "$label ${tracker.lastTarget.ifBlank { "प्लानिंग स्टेप" }} पर रुक गया। ठीक वहीं से दोबारा शुरू कर रहा हूँ।",
                            "$label ${tracker.lastTarget.ifBlank { "প্ল্যানিং ধাপে" }} থেমে গেছে। ঠিক সেখান থেকেই আবার শুরু করছি।"))
                    }
                    tracker.workerDone = false
                    prompt = resumePrompt(name, spec, dir, report?.problems.orEmpty(), lastError)
                    delay(1_500L * attempt)
                }
                tracker.workerDone = true
                if (!ok) {
                    monitor.cancel()
                    say(ctx, tr("I couldn't finish the build after several restarts. Your files are saved in the project $name.",
                        "कई बार दोबारा कोशिश के बाद भी बिल्ड पूरा नहीं हो सका। आपकी फ़ाइलें प्रोजेक्ट $name में सुरक्षित हैं।",
                        "বহুবার চেষ্টার পরও বিল্ড শেষ হয়নি। আপনার ফাইল $name প্রজেক্টে সংরক্ষিত আছে।"))
                    setPhase(Phase.AWAIT_ACK, tr("Build incomplete", "बिल्ड अधूरा", "বিল্ড অসম্পূর্ণ"), tracker.percent)
                    holdUntilAck(ctx)
                    return@launch
                }
                monitor.cancel()
                hostAndPreview(ctx, dir, name)
            } catch (e: CancellationException) {
                monitor.cancel(); throw e
            } catch (e: Exception) {
                Log.e(TAG, "Build supervision failed", e)
                monitor.cancel()
                say(ctx, tr("The build hit an unexpected problem, but your files are preserved.", "बिल्ड में अनपेक्षित समस्या आई, पर आपकी फ़ाइलें सुरक्षित हैं।", "বিল্ডে অপ্রত্যাশিত সমস্যা হয়েছে, তবে ফাইল সুরক্ষিত আছে।"))
                finish(ctx, false, e.message ?: "Error")
            }
        }
    }

    private suspend fun runWorker(ctx: Context, prompt: String, tracker: Tracker, useCustom: Boolean): WorkerResult {
        return if (useCustom) runCustomWorker(ctx, prompt, tracker) else runGeminiWorker(ctx, prompt, tracker)
    }

    private suspend fun runCustomWorker(ctx: Context, prompt: String, tracker: Tracker): WorkerResult {
        val eng = ProviderRuntime.chatEngine(ctx)
        var waited = 0
        while (eng.isBusy && waited < 600) { delay(300); waited++ }
        val feeder = scopeFeed(eng, tracker)
        try {
            return when (val out = eng.send(prompt)) {
                is SendOutcome.Started -> {
                    while (eng.isBusy) delay(300)
                    val st = eng.state.value
                    if (st is EngineState.Failed) WorkerResult(false, st.error.message) else WorkerResult(true)
                }
                is SendOutcome.Blocked -> WorkerResult(false, out.reason)
                SendOutcome.NotActive -> WorkerResult(false, "The custom model is no longer active.")
                SendOutcome.Busy -> WorkerResult(false, "The custom model is busy.")
            }
        } finally {
            feeder.cancel()
        }
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
        val provider = geminiProvider(ctx, forBuild = true) ?: return WorkerResult(false, "No Gemini API key is saved.")
        val system = ChatContext.systemPrompt(JarvisApp.instance.preferences.userName, TokenModeStore.get(ctx)) + ProviderChatEngine.TOOL_PROMPT
        return try {
            val loop = GeminiToolLoop(ProviderRuntime.transport(), backend)
            loop.run(
                ChatRequest(provider, system, listOf(ChatMessage(ChatRole.USER, prompt)), maxTokens = TokenModeStore.get(ctx).maxTokens),
                "web-build",
                onActivity = { },
                onStep = { s: LiveToolStep ->
                    tracker.lastTool = s.toolName
                    if (s.target.isNotBlank()) tracker.lastTarget = s.target.substringAfterLast('/')
                }
            )
            WorkerResult(true)
        } catch (e: ProviderException) {
            WorkerResult(false, e.error.message)
        } catch (e: TransportException) {
            WorkerResult(false, ProviderErrors.fromTransport(e).message)
        }
    }

    /** Reads the project folder every two seconds so progress, file and size are real, not guessed. */
    private suspend fun monitor(ctx: Context, dir: File, tracker: Tracker, startedAt: Long) {
        var lastSpoken = 0L
        var lastSig = ""
        var reviews = 0
        var lastReview = System.currentTimeMillis()
        val reviewed = HashSet<String>()
        while (true) {
            val files = dir.walkTopDown().filter { it.isFile && !it.relativeTo(dir).path.startsWith(".") }.toList()
            val latest = files.maxByOrNull { it.lastModified() }
            val expected = maxOf(4, files.size + (if (tracker.workerDone) 0 else 1))
            val base = if (files.isEmpty()) 6 else 15 + (55 * files.size / expected)
            tracker.bump(if (tracker.workerDone) maxOf(base, 72) else minOf(base, 70))
            val current = latest?.name ?: tracker.lastTarget
            val lines = latest?.let { runCatching { it.readLines().size }.getOrDefault(0) } ?: 0
            val elapsed = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
            val p = tracker.percent
            val eta = if (p in 10..99) maxOf(5, elapsed * (100 - p) / p) else 0
            val stage = when {
                files.isEmpty() -> tr("Planning the project", "प्रोजेक्ट की प्लानिंग", "প্রজেক্ট প্ল্যান করছি")
                tracker.workerDone -> tr("Checking the files", "फ़ाइलें जाँच रहा हूँ", "ফাইল যাচাই করছি")
                else -> tr("Writing $current ($lines lines)", "$current लिख रहा है ($lines लाइनें)", "$current লিখছে ($lines লাইন)")
            }
            publish(ctx, p, stage, current, files.size, eta)

            val sig = "$current|$lines|${files.size}"
            val now = System.currentTimeMillis()
            if (sig != lastSig && now - lastSpoken >= SPEAK_EVERY_MS) {
                lastSig = sig; lastSpoken = now
                say(ctx, tr(
                    "$p percent done. $stage. ${files.size} files so far. About ${spokenDuration(eta, 0)} left.",
                    "$p प्रतिशत पूरा। $stage। अब तक ${files.size} फ़ाइलें। लगभग ${spokenDuration(eta, 1)} बाकी।",
                    "$p শতাংশ সম্পূর্ণ। $stage। এখন পর্যন্ত ${files.size}টি ফাইল। প্রায় ${spokenDuration(eta, 2)} বাকি।"), logChat = true)
            }
            // Gemini reads the newest finished file and says in one sentence what it does (observer only).
            if (latest != null && reviews < MAX_REVIEWS && now - lastReview >= REVIEW_EVERY_MS && latest.name !in reviewed && now - latest.lastModified() > 4_000) {
                lastReview = now; reviewed += latest.name; reviews++
                val head = runCatching { latest.readText().take(1_500) }.getOrDefault("")
                narrate(ctx, "In one short sentence in $lang, say what this file does and mention any obvious bug. File ${latest.name}:\n$head")?.let { say(ctx, it.take(220)) }
            }
            delay(2_000)
        }
    }

    private suspend fun hostAndPreview(ctx: Context, dir: File, name: String) {
        publish(ctx, 85, tr("Starting local server", "लोकल सर्वर चालू कर रहा हूँ", "লোকাল সার্ভার চালু করছি"), "", _status.value.filesWritten, 10)
        val built = ToolRuntime.get(ctx)
        val serveDir = listOf("dist", "build").map { File(dir, it) }.firstOrNull { File(it, "index.html").isFile } ?: dir
        val url = try { built.servers.servers.start(name, serveDir, false).url } catch (e: Exception) {
            say(ctx, tr("I couldn't start the local server: ${e.message}", "लोकल सर्वर चालू नहीं हो सका: ${e.message}", "লোকাল সার্ভার চালু করা যায়নি: ${e.message}"))
            finish(ctx, false, "Server failed"); return
        }
        publish(ctx, 95, tr("Opening preview", "प्रीव्यू खोल रहा हूँ", "প্রিভিউ খুলছি"), "", _status.value.filesWritten, 3)
        val uri = Uri.parse(url)
        val base = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = ActivityLauncherHelper.startActivitySafely(ctx, Intent(base).setPackage("com.android.chrome")) ||
            ActivityLauncherHelper.startActivitySafely(ctx, base)
        _status.value = _status.value.copy(previewUrl = url)
        publish(ctx, 100, tr("Done", "पूरा हुआ", "সম্পন্ন"), "", _status.value.filesWritten, 0)
        runCatching { JarvisApp.instance.chatRepository.addTurn("", "✅ \"$name\" is live at $url (local to this phone)." + if (!opened) " Open it yourself: the browser couldn't be launched from the background." else " I've opened the preview.") }
        TaskProgressNotifier.postComplete(ctx, name, "Live at $url")
        say(ctx, if (opened) tr("Your project is live on the local server and I've opened the preview.", "आपका प्रोजेक्ट लोकल सर्वर पर लाइव है और मैंने प्रीव्यू खोल दिया है।", "আপনার প্রজেক্ট লোকাল সার্ভারে লাইভ এবং আমি প্রিভিউ খুলে দিয়েছি।")
            else tr("Your project is live at $url. Please open it in the browser.", "आपका प्रोजेक्ट $url पर लाइव है। कृपया ब्राउज़र में खोलें।", "আপনার প্রজেক্ট $url এ লাইভ। ব্রাউজারে খুলুন।"))

        val titles = dir.walkTopDown().filter { it.isFile && it.name.endsWith(".html") }.take(1).map { runCatching { it.readText().take(1_200) }.getOrDefault("") }.joinToString()
        val rhyme = narrate(ctx, "Write a 2 to 4 line RHYMING phrase in $lang that briefly explains this website or game to its owner. No emojis, no markdown. Request: $spec\nPage start:\n$titles")
        if (!rhyme.isNullOrBlank()) say(ctx, rhyme.take(400), logChat = true)

        setPhase(Phase.AWAIT_ACK, tr("Say okay when you're done", "हो जाए तो ओके बोलिए", "হয়ে গেলে ওকে বলুন"), 100)
        say(ctx, tr("Say okay when you're done, and I'll stay ready until then.", "जब हो जाए तो ओके बोलिए, तब तक मैं तैयार रहूँगा।", "হয়ে গেলে ওকে বলুন, ততক্ষণ আমি তৈরি থাকব।"))
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

    private fun buildPrompt(name: String, request: String): String =
        "Build a complete, polished, fully working website or browser game for this request: \"$request\".\n" +
            "Rules:\n" +
            "- Use the project name \"$name\". Create it with project_create (template static), then write EVERY file with the file tools (index.html, style.css, script.js, and any others needed). Write each file in full, no placeholders or TODOs.\n" +
            "- It must run offline in a plain browser: no build step, no external CDN dependencies.\n" +
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
        val m = ProviderRuntime.manager(ctx)
        val p = m.activeProfile() ?: return false
        return m.hasKey(p.id) || p.authMethod == AuthMethod.NONE
    }

    private fun activeCustomName(ctx: Context): String = ProviderRuntime.manager(ctx).activeProfile()?.displayName ?: "Custom model"

    private fun geminiProvider(ctx: Context, forBuild: Boolean): ResolvedProvider? {
        val key = JarvisApp.instance.preferences.apiKey.trim()
        if (key.isBlank()) return null
        val saved = ProviderRuntime.routing(ctx).get(WorkCategory.CODING)
        val modelId = if (saved?.isGemini == true && saved.modelId.isNotBlank()) saved.modelId else GeminiConstants.MODEL_3_1_FLASH_AGENT
        return ResolvedProvider(
            profileId = "gemini-builtin", name = "Gemini", baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            protocol = ProviderProtocol.GEMINI, auth = AuthSpec(AuthMethod.X_GOOG_API_KEY, "", ApiKeySecret(key.toCharArray())),
            modelId = modelId, model = ModelInfo(id = modelId, manual = true), realtimeUrl = "", sttModelId = "", settings = emptyMap()
        )
    }

    /** Short side-calls for the live review and the rhyme. Gemini when a key exists, otherwise the active custom model. */
    private suspend fun narrate(ctx: Context, prompt: String): String? {
        val provider = geminiProvider(ctx, false)
            ?: ((ProviderRuntime.manager(ctx).resolveChat(WorkCategory.CHAT)) as? ChatResolution.Ready)?.provider
            ?: return null
        return try {
            val sb = StringBuilder()
            withTimeoutOrNull(25_000L) {
                ChatClientFactory.create(provider.protocol, ProviderRuntime.transport())
                    .stream(ChatRequest(provider, "You are JARVIS. Reply with plain spoken text only.", listOf(ChatMessage(ChatRole.USER, prompt)), maxTokens = 300))
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

    private fun setPhase(p: Phase, stage: String, percent: Int = 0) {
        phase = p
        _status.value = _status.value.copy(phase = p, stage = stage, percent = percent)
        appCtx?.let { TaskProgressNotifier.postProgress(it, "Jarvis · web build", stage, percent.coerceIn(1, 100)) }
    }

    private fun publish(ctx: Context, percent: Int, stage: String, file: String, files: Int, eta: Int) {
        _status.value = _status.value.copy(phase = phase, percent = percent, stage = stage, currentFile = file, filesWritten = files, etaSec = eta)
        val etaText = if (eta > 0) " · ~${spokenDuration(eta, 0)} left" else ""
        TaskProgressNotifier.postProgress(ctx, "Jarvis · web build", "$stage$etaText", percent.coerceIn(1, 100))
    }

    private fun say(ctx: Context, text: String, logChat: Boolean = false) {
        if (logChat) runCatching { JarvisApp.instance.chatRepository.addTurn("", "🛠 $text") }
        if (!JarvisConversationService.announceMessage(text)) {
            JarvisVoiceController.speakTurnResponse(ctx, "webbuild-${System.nanoTime()}", null, text, forceTts = true)
        }
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
        cb?.invoke(success, message)
    }
}
