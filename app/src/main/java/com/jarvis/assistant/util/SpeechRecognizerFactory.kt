package com.jarvis.assistant.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Picks a speech recognizer that can actually hear you.
 *
 * Jarvis registers its own RecognitionService because Android requires one from an assistant app, but that service only answers
 * "not usable". When Jarvis is the phone's default assistant, Android can choose it as the default recognizer, and then every plain
 * SpeechRecognizer.createSpeechRecognizer(context) call fails with ERROR_CLIENT. So we look at the services the phone really has,
 * leave Jarvis's own out, and put Google's first.
 */
object SpeechRecognizerFactory {

    private const val GOOGLE_APP = "com.google.android.googlequicksearchbox"

    /** Recognition services installed on the phone, best first, never including this app's own stub. */
    fun candidates(context: Context): List<ComponentName> {
        return try {
            val own = context.packageName
            context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
                .mapNotNull { it.serviceInfo }
                .filter { it.packageName != own && it.exported }
                .sortedBy {
                    when {
                        it.packageName == GOOGLE_APP -> 0
                        it.packageName.startsWith("com.google") -> 1
                        else -> 2
                    }
                }
                .map { ComponentName(it.packageName, it.name) }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** True when at least one real recognizer exists. */
    fun available(context: Context): Boolean = candidates(context).isNotEmpty() || SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * [attempt] 0 is the best recognizer; each higher number tries the next one, so a service that fails can be swapped for another.
     * With no other service installed the system default is used.
     */
    fun create(context: Context, attempt: Int = 0): SpeechRecognizer {
        val list = candidates(context)
        return if (list.isEmpty()) SpeechRecognizer.createSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context, list[attempt.coerceAtLeast(0) % list.size])
    }
}
