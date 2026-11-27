package com.jarvis.assistant.study.ai

import android.content.Context
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.offline.NetworkStateMonitor
import com.jarvis.assistant.provider.ApiKeySecret
import com.jarvis.assistant.provider.AuthMethod
import com.jarvis.assistant.provider.AuthSpec
import com.jarvis.assistant.provider.CapabilityPolicy
import com.jarvis.assistant.provider.ChatAttachment
import com.jarvis.assistant.provider.ChatClientFactory
import com.jarvis.assistant.provider.ChatEvent
import com.jarvis.assistant.provider.ChatMessage
import com.jarvis.assistant.provider.ChatRequest
import com.jarvis.assistant.provider.ChatRole
import com.jarvis.assistant.provider.ModelInfo
import com.jarvis.assistant.provider.ProviderError
import com.jarvis.assistant.provider.ProviderErrorKind
import com.jarvis.assistant.provider.ProviderException
import com.jarvis.assistant.provider.ProviderProfile
import com.jarvis.assistant.provider.ProviderProtocol
import com.jarvis.assistant.provider.ProviderRuntime
import com.jarvis.assistant.provider.ResolvedProvider
import com.jarvis.assistant.provider.TaskRouteResolution
import com.jarvis.assistant.provider.TaskRouter
import com.jarvis.assistant.provider.TaskType
import com.jarvis.assistant.provider.Thinking
import com.jarvis.assistant.study.ExtractedQuestion
import com.jarvis.assistant.study.StudyExtraction
import com.jarvis.assistant.study.StudyPrompt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** A failure the Study Board can show to the student as is. [retryable] decides whether a Retry button is offered. */
class StudyAiException(message: String, val retryable: Boolean = true, val offline: Boolean = false) : Exception(message)

/**
 * The Study Board's only door to AI. It uses the app's existing provider routing and key rotation (a custom provider if the
 * user activated one, otherwise the Gemini key saved in Settings), so it adds no provider, no key storage and no network
 * stack of its own. This branch ships no on-device model, so without a connection a new question cannot be answered; that is
 * reported plainly instead of guessing.
 */
object StudyAiEngine {

    private const val GEMINI_BASE = GeminiModels.BASE
    private const val GEMINI_DEFAULT_MODEL = "gemini-2.5-flash"
    private const val MAX_LEASE_ATTEMPTS = 3

    class Request(
        val system: String,
        val user: String,
        val attachments: List<ChatAttachment> = emptyList(),
        /** Routes to the model chosen for image understanding when true, otherwise the chat model. */
        val vision: Boolean = false,
        val thinking: Thinking = Thinking.MINIMAL
    )

    /** Streams the model's text as it arrives. Cancelling the collector closes the connection. */
    fun stream(context: Context, request: Request): Flow<String> = flow {
        val app = context.applicationContext
        if (!NetworkStateMonitor.isInternetAvailable(app)) {
            throw StudyAiException(
                "You are offline. This version of JARVIS has no on-device model, so a new question needs an internet connection. Saved lessons can still be opened.",
                retryable = true, offline = true
            )
        }
        if (com.jarvis.assistant.ui.studyboard.StudyPrefs(app).provider == com.jarvis.assistant.ui.studyboard.StudyPrefs.PROVIDER_GEMINI) {
            emitAll(streamGemini(request))
            return@flow
        }
        var base = resolve(app, request)
        val transport = ProviderRuntime.transport()
        val client = ChatClientFactory.create(base.protocol, transport)
        val pooled = base.auth.secret?.isBlank != false
        val rotation = if (pooled) ProviderRuntime.keyRotation(app).takeIf { it.hasKeys(base.profileId) } else null
        if (pooled && rotation == null && base.auth.method != AuthMethod.NONE) {
            throw StudyAiException("No API key is saved for ${base.name}. Add one in Settings, then try again.", retryable = false)
        }
        val tried = HashSet<String>()
        var emitted = false
        var attempt = 0
        var thinking = request.thinking
        while (true) {
            attempt++
            val lease = rotation?.let {
                it.lease(base.profileId, tried) ?: throw StudyAiException(it.unavailableSummary(base.profileId), retryable = true)
            }
            val active = if (lease == null) base else base.copy(
                auth = AuthSpec(base.auth.method, base.auth.name, lease.secret),
                modelId = lease.modelId.ifBlank { base.modelId },
                model = if (lease.modelId.isBlank()) base.model else (base.model.takeIf { it.id == lease.modelId } ?: ModelInfo(id = lease.modelId, manual = true))
            )
            try {
                val chat = ChatRequest(
                    provider = active,
                    system = request.system,
                    messages = listOf(ChatMessage(ChatRole.USER, request.user, request.attachments)),
                    thinking = thinking
                )
                client.stream(chat).collect { event ->
                    if (event is ChatEvent.Delta) { emitted = true; emit(event.text) }
                }
                if (lease != null) rotation?.reportSuccess(lease.keyId)
                return@flow
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProviderException) {
                val err = e.error
                if (lease != null && rotation != null) {
                    val failure = rotation.reportFailure(lease.keyId, err)
                    tried.add(lease.keyId)
                    // Another key can only help before any text was shown; after that, retrying would repeat steps.
                    if (!emitted && failure.rotates && attempt < MAX_LEASE_ATTEMPTS) continue
                }
                // Some models reject the thinking setting: send the same question again without it, once.
                if (err.kind == ProviderErrorKind.BAD_REQUEST && !emitted && thinking != Thinking.DEFAULT &&
                    err.message.contains("thinking", ignoreCase = true)) {
                    thinking = Thinking.DEFAULT
                    continue
                }
                throw mapError(err)
            } finally {
                lease?.secret?.wipe()
            }
        }
    }

    /** Reads photographs or page images and returns the questions on them, for the student to check before solving. */
    suspend fun extractQuestions(context: Context, images: List<ChatAttachment.Image>): List<ExtractedQuestion> {
        val sb = StringBuilder()
        stream(context, Request(StudyPrompt.EXTRACT_SYSTEM, StudyPrompt.EXTRACT_USER, images, vision = true, thinking = Thinking.MINIMAL))
            .collect { sb.append(it) }
        val questions = StudyExtraction.parse(sb.toString())
        if (questions.isEmpty()) {
            throw StudyAiException("I could not read a question from this image. Try a clearer photo, or type the question.", retryable = false)
        }
        return questions
    }

    /**
     * The Gemini-key path: tries the models this key can use, best first, and moves on at once when one is overloaded,
     * rate-limited or gone, so a busy or retired model never stops the board. After a full round of busy answers it waits
     * a moment and goes round once more. A rejected key stops immediately with Google's own message.
     */
    private fun streamGemini(request: Request): Flow<String> = flow {
        val key = JarvisApp.instance.preferences.apiKey.trim()
        if (key.isBlank()) throw StudyAiException("Add your Gemini API key in Settings to use the Study Board.", retryable = false)
        val transport = ProviderRuntime.transport()
        val client = ChatClientFactory.create(ProviderProtocol.GEMINI, transport)
        var thinking = request.thinking
        var emitted = false
        var lastError: ProviderError? = null
        var sawBusy = false
        val startedAt = System.currentTimeMillis()

        for (round in 1..2) {
            var busyThisRound = false
            for (model in GeminiModels.candidates(transport, key)) {
                if (lastError != null && System.currentTimeMillis() - startedAt > 75_000L) break
                var again = true
                while (again) {
                    again = false
                    try {
                        val chat = ChatRequest(
                            provider = geminiProvider(key, model),
                            system = request.system,
                            messages = listOf(ChatMessage(ChatRole.USER, request.user, request.attachments)),
                            thinking = thinking
                        )
                        client.stream(chat).collect { event -> if (event is ChatEvent.Delta) { emitted = true; emit(event.text) } }
                        GeminiModels.markGood(model)
                        return@flow
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ProviderException) {
                        val err = e.error
                        lastError = err
                        when {
                            err.isAuth || emitted -> throw mapError(err)
                            err.kind == ProviderErrorKind.BAD_REQUEST && thinking != Thinking.DEFAULT &&
                                err.message.contains("thinking", ignoreCase = true) -> { thinking = Thinking.DEFAULT; again = true }
                            err.kind == ProviderErrorKind.MODEL_NOT_FOUND -> GeminiModels.markDead(model)
                            err.isTransient -> { busyThisRound = true; sawBusy = true }
                            else -> throw mapError(err)
                        }
                    }
                }
            }
            if (!busyThisRound) break
            if (round == 1) delay(800L)
        }
        val err = lastError ?: throw StudyAiException("Gemini did not answer. Try again.")
        if (sawBusy) {
            throw StudyAiException(
                "Google's Gemini servers are busy right now: every model this key can use answered with a temporary error (last: ${err.httpCode?.let { "HTTP $it" } ?: err.kind.name.lowercase()}). Tap Retry in a minute.",
                retryable = true
            )
        }
        throw mapError(err)
    }

    private fun mapError(err: ProviderError): StudyAiException {
        val retryable = err.kind != ProviderErrorKind.AUTH_FAILED && err.kind != ProviderErrorKind.KEY_REJECTED &&
            err.kind != ProviderErrorKind.INVALID_CONFIG && err.kind != ProviderErrorKind.INVALID_ENDPOINT
        val text = when (err.kind) {
            ProviderErrorKind.RATE_LIMITED -> "The AI service is busy or the quota is used up. Wait a moment and retry."
            // With an HTTP code the phone did reach Google, so it is not an internet problem: show what Google said.
            ProviderErrorKind.UNAVAILABLE -> if (err.httpCode != null) err.message else "The connection to the AI service failed. Check your internet and retry."
            ProviderErrorKind.TIMEOUT -> "The AI service took too long to answer. Check your internet and retry."
            ProviderErrorKind.MODEL_NOT_FOUND -> "${err.message} Open Settings and pick a different Gemini model, then retry."
            ProviderErrorKind.STREAM_INTERRUPTED -> "The connection dropped while the solution was being written."
            else -> err.message
        }
        return StudyAiException(text, retryable = retryable)
    }

    private fun resolve(app: Context, request: Request): ResolvedProvider {
        val task = if (request.vision) TaskType.IMAGE_UNDERSTANDING else TaskType.CHAT_ANALYSIS
        return when (val route = TaskRouter.getRouteForTask(app, task)) {
            is TaskRouteResolution.Custom -> {
                val p = route.provider
                for (a in request.attachments) {
                    val verdict = when (a) {
                        is ChatAttachment.Image -> CapabilityPolicy.imageInput(p.model)
                        is ChatAttachment.Pdf -> CapabilityPolicy.pdfInput(p.model)
                    }
                    if (!verdict.ok) throw StudyAiException(verdict.reason, retryable = false)
                }
                p
            }
            is TaskRouteResolution.Gemini -> throw StudyAiException(
                "Study Board is set to use a custom provider, but none is active. Activate one in Settings, or switch Study Board back to your Gemini key (Study settings → AI provider).",
                retryable = false
            )
            is TaskRouteResolution.Blocked -> throw StudyAiException(route.message, retryable = false)
        }
    }

    /** The built-in Gemini route. The key is read from the encrypted settings and never logged or put in a URL. */
    private fun geminiProvider(key: String, routedModel: String): ResolvedProvider {
        if (key.isBlank()) throw StudyAiException("Add your Gemini API key in Settings to use the Study Board.", retryable = false)
        // The model saved for the voice assistant is a realtime model and cannot write text; use a text model instead.
        val model = routedModel.removePrefix("models/").takeIf { GeminiModels.isTextModel(it) } ?: GEMINI_DEFAULT_MODEL
        return ResolvedProvider(
            profileId = "builtin-gemini",
            name = "Gemini",
            baseUrl = GEMINI_BASE,
            protocol = ProviderProtocol.GEMINI,
            auth = AuthSpec(AuthMethod.X_GOOG_API_KEY, "", ApiKeySecret(key.toCharArray())),
            modelId = model,
            model = ModelInfo(id = model, manual = true),
            realtimeUrl = "",
            sttModelId = "",
            settings = mapOf(ProviderProfile.SETTING_TEMPERATURE to "0.2")
        )
    }
}
