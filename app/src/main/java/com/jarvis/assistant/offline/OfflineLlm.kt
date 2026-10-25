package com.jarvis.assistant.offline

import android.content.Context
import com.arm.aichat.internal.InferenceEngineImpl

object OfflineLlm {
    suspend fun generate(
        context: Context,
        prompt: String,
        onProgress: (partialResponse: String, generatedTokens: Int, totalTokens: Int) -> Unit = { _, _, _ -> }
    ): String {
        require(prompt.isNotBlank()) { "Enter a message first." }
        return InferenceEngineImpl.getInstance(context.applicationContext)
            .generate(context.applicationContext, prompt.trim(), onProgress)
    }
}
