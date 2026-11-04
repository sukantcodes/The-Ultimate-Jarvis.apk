package com.jarvis.assistant.offline

import android.content.Context
import com.arm.aichat.internal.InferenceEngineImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object OfflineLlm {
    suspend fun generate(
        context: Context,
        prompt: String,
        onProgress: (partialResponse: String, generatedTokens: Int, totalTokens: Int) -> Unit = { _, _, _ -> }
    ): String {
        require(prompt.isNotBlank()) { "Enter a message first." }
        // Native model initialization and token generation can take seconds and must never
        // execute on the Android main thread. Keep UI responsive while the local engine works.
        return withContext(Dispatchers.IO) {
            InferenceEngineImpl.getInstance(context.applicationContext)
                .generate(context.applicationContext, prompt.trim(), onProgress)
        }
    }

    /** Loads the Qwen3 model in the background so the first offline answer is fast. */
    suspend fun warmUp(context: Context): Boolean =
        InferenceEngineImpl.getInstance(context.applicationContext).warmUp(context.applicationContext)
}
