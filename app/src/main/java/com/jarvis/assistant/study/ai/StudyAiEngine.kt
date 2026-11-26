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

    private const val GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta"
    private const val GEMINI_DEFAULT_MODEL = "gemini-3.8-flash"
    /** Tried in order when Google reports a model as retired or unavailable, so one retired id never blocks the board. */
    private val GEMINI_FALLBACK_MODELS = listOf("gemini-3.8-flash", "gemini-3.6-flash", "gemini-3.5-flash", "gemini-2.5-flash")
    private const val MAX_LEASE_ATTEMPTS = 3
    /** Models that cannot write a text answer (voice, speech, image, video, agents, embeddings): never used for the board. */
    private val NON_TEXT_MARKERS = listOf("live", "audio", "agent", "tts", "image", "imagen", "veo", "omni", "transcribe", "embedding", "robotics", "translate", "banana", "lyria", "deep-research")

    class Request(
        val system: String,
        val user: String,
        val attachments: List<ChatAttachment> = emptyList(),
        /** Routes to the model chosen for image understanding when true, otherwise the chat model. */
        val vision: Boolean = false,
        val thinking: Thinking = Thinking.LOW
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
        var base = resolve(app, request)
        val triedModels = HashSet<String>().apply { add(base.modelId) }
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
                // A model that is missing, or one that answers with a server error (overloaded, 503...), should not block the
                // board: try the next model before giving up. (A plain network failure has no HTTP code and is not retried here.)
                // Some models reject the thinking setting: send the same question again without it, once.
                if (err.kind == ProviderErrorKind.BAD_REQUEST && !emitted && thinking != Thinking.DEFAULT &&
                    err.message.contains("thinking", ignoreCase = true)) {
                    thinking = Thinking.DEFAULT
                    continue
                }
                val modelProblem = err.kind == ProviderErrorKind.MODEL_NOT_FOUND ||
                    (err.kind == ProviderErrorKind.UNAVAILABLE && err.httpCode != null)
                if (modelProblem && !emitted && base.profileId == "builtin-gemini") {
                    val next = GEMINI_FALLBACK_MODELS.firstOrNull { it !in triedModels }
                    if (next != null) {
                        triedModels.add(next)
                        base = base.copy(modelId = next, model = ModelInfo(id = next, manual = true))
                        tried.clear()
                        continue
                    }
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
        stream(context, Request(StudyPrompt.EXTRACT_SYSTEM, StudyPrompt.EXTRACT_USER, images, vision = true, thinking = Thinking.LOW))
            .collect { sb.append(it) }
        val questions = StudyExtraction.parse(sb.toString())
        if (questions.isEmpty()) {
            throw StudyAiException("I could not read a question from this image. Try a clearer photo, or type the question.", retryable = false)
        }
        return questions
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
            is TaskRouteResolution.Gemini -> geminiProvider(JarvisApp.instance.preferences.apiKey.trim(), route.modelId)
            is TaskRouteResolution.Blocked -> throw StudyAiException(route.message, retryable = false)
        }
    }

    /** The built-in Gemini route. The key is read from the encrypted settings and never logged or put in a URL. */
    private fun geminiProvider(key: String, routedModel: String): ResolvedProvider {
        if (key.isBlank()) throw StudyAiException("Add your Gemini API key in Settings to use the Study Board.", retryable = false)
        // The model saved for the voice assistant is a realtime model and cannot write text; use a text model instead.
        val model = routedModel.removePrefix("models/").takeIf { id ->
            id.isNotBlank() && NON_TEXT_MARKERS.none { id.contains(it, true) }
        } ?: GEMINI_DEFAULT_MODEL
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
