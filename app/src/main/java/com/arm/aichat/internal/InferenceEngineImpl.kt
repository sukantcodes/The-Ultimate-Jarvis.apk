package com.arm.aichat.internal

import android.content.Context
import com.jarvis.assistant.offline.OfflineModelDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Serializes access to llama.cpp's process-wide native model/context. */
internal class InferenceEngineImpl private constructor(context: Context) {
    private val app = context.applicationContext
    private val mutex = Mutex()
    private var initialized = false
    private var loadedPath: String? = null

    private external fun init(nativeLibDir: String)
    private external fun load(modelPath: String): Int
    private external fun takeLastNativeError(): String
    private external fun prepare(): Int
    private external fun systemInfo(): String
    private external fun benchModel(pp: Int, tg: Int, pl: Int, nr: Int): String
    private external fun processSystemPrompt(systemPrompt: String): Int
    private external fun processUserPrompt(userPrompt: String, predictLength: Int): Int
    private external fun generateNextToken(): String?
    private external fun unload()
    private external fun shutdown()

    suspend fun generate(
        context: Context,
        prompt: String,
        onProgress: (partialResponse: String, generatedTokens: Int, totalTokens: Int) -> Unit = { _, _, _ -> }
    ): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val model = OfflineModelDownloader.modelFile(context)
            check(OfflineModelDownloader.isModelValid(model)) {
                "Offline model missing or incomplete. Download it from Offline JARVIS."
            }

            currentCoroutineContext().ensureActive()
            // Report native model initialization separately; otherwise the UI looks frozen
            // while llama.cpp maps the multi-gigabyte model into memory.
            onProgress("", -1, MAX_GENERATION_TOKENS)
            ensureLoaded(model)
            currentCoroutineContext().ensureActive()
            onProgress("", 0, MAX_GENERATION_TOKENS)
            check(processUserPrompt(prompt, MAX_GENERATION_TOKENS) == 0) {
                "Local model could not process the prompt."
            }
            onProgress("", 0, MAX_GENERATION_TOKENS)

            val output = StringBuilder()
            var generated = 0
            while (generated < MAX_GENERATION_TOKENS) {
                currentCoroutineContext().ensureActive()
                val token = generateNextToken() ?: break
                if (token.isNotEmpty()) output.append(token)
                generated++
                onProgress(cleanOutput(output.toString()), generated, MAX_GENERATION_TOKENS)
            }

            cleanOutput(output.toString()).ifBlank {
                "I couldn't generate a response. Please try a shorter message."
            }
        }
    }

    private fun cleanOutput(raw: String): String {
        val withoutThinking = raw
            .replace(Regex("<think>[\\s\\S]*?</think>"), "")
            .substringBefore("<think>")
        return withoutThinking
            .replace("<|im_end|>", "")
            .replace("<|endoftext|>", "")
            .trim()
    }

    private fun ensureLoaded(model: File) {
        val path = model.absolutePath
        if (loadedPath == path) return

        if (!initialized) {
            System.loadLibrary("ai-chat")
            init(app.applicationInfo.nativeLibraryDir)
            initialized = true
        }
        if (loadedPath != null) {
            unload()
            loadedPath = null
        }

        // Fail fast on low-memory devices instead of leaving the voice assistant apparently
        // stuck while the native loader struggles to map a 1.28 GB model.
        val memoryInfo = android.app.ActivityManager.MemoryInfo()
        (app.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(memoryInfo)
        val minimumAvailableBytes = 900L * 1024L * 1024L
        check(memoryInfo.availMem >= minimumAvailableBytes) {
            "Not enough free RAM to load the offline model (${memoryInfo.availMem / (1024L * 1024L)} MB available). Close other apps and retry; the 1.28 GB model needs additional working memory."
        }

        val loadResult = load(path)
        if (loadResult != 0) {
            val detail = takeLastNativeError().trim().take(900)
            throw IllegalStateException(
                if (detail.isNotBlank()) {
                    "llama.cpp could not load Qwen3. Native diagnostic: $detail"
                } else {
                    "llama.cpp could not load Qwen3. The native loader returned no detailed error; check model integrity and available memory."
                }
            )
        }

        check(prepare() == 0) {
            "Not enough available memory to initialize the model. Close other apps and retry."
        }
        check(
            processSystemPrompt(
                "You are JARVIS, a helpful offline Android assistant. Reply naturally in the user's language, including Hinglish and English. Be concise and honest. You have no internet access; do not claim to know live news, weather, or current online information. Never claim to perform a phone action unless the app actually performed it."
            ) == 0
        ) { "Could not initialize the offline assistant prompt." }
        loadedPath = path
    }

    companion object {
        private const val MAX_GENERATION_TOKENS = 48

        @Volatile
        private var instance: InferenceEngineImpl? = null

        fun getInstance(context: Context): InferenceEngineImpl =
            instance ?: synchronized(this) {
                instance ?: InferenceEngineImpl(context.applicationContext).also { instance = it }
            }
    }
}
