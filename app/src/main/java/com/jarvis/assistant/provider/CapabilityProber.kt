package com.jarvis.assistant.provider

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.firstOrNull

class ProbeStep(val capability: Capability, val result: CapabilityResult, val millis: Long)

class ProbeReport(
    /** The model with every probed capability filled in. */
    val model: ModelInfo,
    val steps: List<ProbeStep>,
    /** Set when probing stopped because the provider rejected the credentials. Nothing was learned about the model. */
    val abort: ProviderError?,
    /** Result of the speech-to-text endpoint check, when one was requested. */
    val sttSupport: Support?,
    val sttNote: String = ""
)

/**
 * Settles what a model can really do by sending small real requests, comparing each feature request with a
 * plain text request that already worked. A model's name is never used as evidence.
 *
 * Costs a handful of tiny requests (a few tokens each); it only runs when the user asks.
 */
class CapabilityProber(private val transport: HttpTransport) {

    suspend fun probe(
        provider: ResolvedProvider,
        known: ModelInfo,
        sttModelId: String = "",
        force: Boolean = false
    ): ProbeReport {
        val client = ChatClientFactory.create(provider.protocol, transport)
        val caps = LinkedHashMap(known.capabilities)
        val steps = ArrayList<ProbeStep>()

        fun settled(c: Capability): Boolean {
            val r = caps[c] ?: return false
            return !force && r.evidence == Evidence.METADATA && r.support != Support.UNKNOWN
        }

        fun record(c: Capability, r: CapabilityResult, ms: Long) {
            caps[c] = r
            steps += ProbeStep(c, r, ms)
        }

        fun finish(abort: ProviderError? = null, stt: Support? = null, sttNote: String = "") =
            ProbeReport(known.copy(capabilities = caps), steps, abort, stt, sttNote)

        // ---- Text -------------------------------------------------------------------------------------
        val textBlockedByMetadata = caps[Capability.TEXT]?.let { it.evidence == Evidence.METADATA && it.support == Support.NO } == true
        var textWorks = false
        if (!textBlockedByMetadata) {
            val started = System.currentTimeMillis()
            val outcome = attempt { client.complete(textRequest(provider, "Reply with the single word OK.")) }
            val ms = System.currentTimeMillis() - started
            when (outcome) {
                is Attempt.Ok -> { record(Capability.TEXT, CapabilityResult(Support.YES, Evidence.PROBE, "Answered in $ms ms"), ms); textWorks = true }
                is Attempt.Failed -> {
                    if (outcome.error.isAuth) return finish(abort = outcome.error)
                    record(Capability.TEXT, verdict(outcome.error), ms)
                }
            }
        } else {
            textWorks = false
        }

        // ---- Features that build on working text ---------------------------------------------------------
        if (textWorks) {
            if (!settled(Capability.STREAMING)) {
                val started = System.currentTimeMillis()
                val outcome = attempt {
                    client.stream(textRequest(provider, "Count from one to three.")).firstOrNull { it is ChatEvent.Delta }
                        ?: throw ProviderException(ProviderErrors.invalidResponse("No streamed text arrived."))
                }
                val ms = System.currentTimeMillis() - started
                record(
                    Capability.STREAMING,
                    when (outcome) {
                        is Attempt.Ok -> CapabilityResult(Support.YES, Evidence.PROBE, "Streamed text arrived")
                        is Attempt.Failed -> if (outcome.error.isAuth) return finish(abort = outcome.error) else verdict(outcome.error)
                    },
                    ms
                )
            }
            if (!settled(Capability.TOOLS)) {
                val started = System.currentTimeMillis()
                val outcome = attempt {
                    client.complete(ChatRequest(provider, "", listOf(ChatMessage(ChatRole.USER, "Reply with the single word OK.")), 32, includeProbeTool = true))
                }
                val ms = System.currentTimeMillis() - started
                record(
                    Capability.TOOLS,
                    when (outcome) {
                        is Attempt.Ok -> CapabilityResult(Support.YES, Evidence.PROBE, "Accepts tool definitions")
                        is Attempt.Failed -> if (outcome.error.isAuth) return finish(abort = outcome.error) else verdict(outcome.error)
                    },
                    ms
                )
            }
            if (!settled(Capability.IMAGE_INPUT)) {
                val started = System.currentTimeMillis()
                val image = ChatAttachment.Image("image/png", ProbeAssets.base64(ProbeAssets.png()))
                val outcome = attempt {
                    client.complete(ChatRequest(provider, "", listOf(ChatMessage(ChatRole.USER, "Describe this picture in five words.", listOf(image))), 48))
                }
                val ms = System.currentTimeMillis() - started
                record(
                    Capability.IMAGE_INPUT,
                    when (outcome) {
                        is Attempt.Ok -> CapabilityResult(Support.YES, Evidence.PROBE, "Accepted an image and answered")
                        is Attempt.Failed -> if (outcome.error.isAuth) return finish(abort = outcome.error) else verdict(outcome.error)
                    },
                    ms
                )
            }
            if (!settled(Capability.FILE_INPUT)) {
                val started = System.currentTimeMillis()
                val pdf = ChatAttachment.Pdf("probe.pdf", ProbeAssets.base64(ProbeAssets.pdf()))
                val outcome = attempt {
                    client.complete(ChatRequest(provider, "", listOf(ChatMessage(ChatRole.USER, "What does this document say?", listOf(pdf))), 48))
                }
                val ms = System.currentTimeMillis() - started
                record(
                    Capability.FILE_INPUT,
                    when (outcome) {
                        is Attempt.Ok -> CapabilityResult(Support.YES, Evidence.PROBE, "Accepted a PDF and answered")
                        is Attempt.Failed -> if (outcome.error.isAuth) return finish(abort = outcome.error) else verdict(outcome.error)
                    },
                    ms
                )
            }
        }

        // ---- Real-time voice (Gemini Live protocol only) ---------------------------------------------------
        if (provider.protocol == ProviderProtocol.GEMINI) {
            val liveListed = known.supportedMethods.isEmpty() || "bidiGenerateContent" in known.supportedMethods
            if (liveListed && (force || !settled(Capability.REALTIME_VOICE) || caps[Capability.REALTIME_VOICE]?.support == Support.YES)) {
                val started = System.currentTimeMillis()
                val result = probeLive(provider)
                record(Capability.REALTIME_VOICE, result, System.currentTimeMillis() - started)
                if (result.support == Support.YES) {
                    caps[Capability.AUDIO_INPUT] = CapabilityResult(Support.YES, Evidence.PROBE, "Live session accepted an audio configuration")
                    caps[Capability.AUDIO_OUTPUT] = CapabilityResult(Support.YES, Evidence.PROBE, "Live session accepted an audio configuration")
                }
            }
        }

        // ---- Speech-to-text endpoint (OpenAI-compatible turn-based voice) ---------------------------------------
        var stt: Support? = null
        var sttNote = ""
        if (provider.protocol == ProviderProtocol.OPENAI_COMPATIBLE && sttModelId.isNotBlank()) {
            val (support, note, auth) = probeTranscription(provider, sttModelId)
            if (auth != null) return finish(abort = auth)
            stt = support
            sttNote = note
        }
        return finish(stt = stt, sttNote = sttNote)
    }

    private fun textRequest(provider: ResolvedProvider, prompt: String) =
        ChatRequest(provider, "", listOf(ChatMessage(ChatRole.USER, prompt)), 16)

    /** Opens the real Live WebSocket and waits for the server to accept an audio-response setup. */
    private suspend fun probeLive(provider: ResolvedProvider): CapabilityResult {
        val url = (ProviderUrl.geminiLiveUrl(provider.baseUrl, provider.realtimeUrl) as? ProviderUrl.Check.Ok)?.url
            ?: return CapabilityResult(Support.UNKNOWN, Evidence.UNVERIFIED, "The real-time address could not be worked out.")
        val request = try {
            ProviderAuth.withExtraHeaders(ProviderAuth.apply(HttpRequestSpec("GET", url, emptyMap()), provider.auth), provider.extraHeaders)
        } catch (e: ProviderException) {
            return CapabilityResult(Support.UNKNOWN, Evidence.UNVERIFIED, e.error.message)
        }
        val setup = JsonObject().apply {
            add("setup", JsonObject().apply {
                addProperty("model", ProviderUrl.geminiModelName(provider.modelId))
                add("generationConfig", JsonObject().apply {
                    add("responseModalities", JsonArray().apply { add("AUDIO") })
                })
            })
        }.toString()
        val probe = try {
            transport.probeWebSocket(request, setup, 12_000L)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TransportException) {
            return CapabilityResult(Support.UNKNOWN, Evidence.UNVERIFIED, ProviderErrors.fromTransport(e).message)
        }
        val secret = provider.auth.secret?.takeIf { !it.isBlank }?.reveal()
        val detail = ProviderAuth.redact(probe.detail, secret).take(160)
        return when {
            probe.ok -> CapabilityResult(Support.YES, Evidence.PROBE, "The Live connection opened and accepted an audio session")
            probe.httpCode == 401 || probe.httpCode == 403 -> CapabilityResult(Support.UNKNOWN, Evidence.UNVERIFIED, "Authentication failed on the real-time connection. $detail")
            probe.httpCode == null && detail.contains("timed out", ignoreCase = true) -> CapabilityResult(Support.UNKNOWN, Evidence.UNVERIFIED, "The Live connection timed out.")
            else -> CapabilityResult(Support.NO, Evidence.PROBE, "The Live connection was refused. $detail".trim())
        }
    }

    private data class SttOutcome(val support: Support, val note: String, val authError: ProviderError?)

    private suspend fun probeTranscription(provider: ResolvedProvider, sttModel: String): SttOutcome {
        val base = (ProviderUrl.validateBase(provider.baseUrl) as? ProviderUrl.Check.Ok)?.url
            ?: return SttOutcome(Support.UNKNOWN, "Endpoint URL is not valid.", null)
        val body = HttpBody.Multipart(
            listOf(
                MultipartPart("model", value = sttModel),
                MultipartPart("response_format", value = "json"),
                MultipartPart("file", filename = "probe.wav", contentType = "audio/wav", bytes = ProbeAssets.silentWav())
            )
        )
        val request = try {
            ProviderAuth.withExtraHeaders(
                ProviderAuth.apply(HttpRequestSpec("POST", ProviderUrl.join(base, "audio/transcriptions"), emptyMap(), body, 30_000L), provider.auth),
                provider.extraHeaders
            )
        } catch (e: ProviderException) {
            return SttOutcome(Support.UNKNOWN, e.error.message, null)
        }
        val secret = provider.auth.secret?.takeIf { !it.isBlank }?.reveal()
        val response = try {
            transport.execute(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TransportException) {
            return SttOutcome(Support.UNKNOWN, ProviderErrors.fromTransport(e).message, null)
        }
        val detail = ProviderErrors.providerMessage(response.body, secret)
        return when {
            response.isSuccessful -> SttOutcome(Support.YES, "The transcription endpoint accepted audio.", null)
            response.code == 401 || response.code == 403 ->
                SttOutcome(Support.UNKNOWN, "", ProviderErrors.fromHttp(response.code, response.body, secret, ErrorContext.CHAT))
            response.code == 404 || response.code == 405 || response.code == 501 ->
                SttOutcome(Support.NO, "This provider has no /audio/transcriptions endpoint, so it can't turn your speech into text.", null)
            response.code == 429 || response.code >= 500 ->
                SttOutcome(Support.UNKNOWN, "The provider was busy (HTTP ${response.code}). Try again.", null)
            (response.code == 400 || response.code == 422) && detail.lowercase().contains("model") ->
                SttOutcome(Support.NO, "The provider doesn't offer \"$sttModel\" for transcription. $detail".trim(), null)
            else -> SttOutcome(Support.UNKNOWN, "The transcription endpoint answered HTTP ${response.code}. $detail".trim(), null)
        }
    }

    /**
     * Maps a failed feature request to a verdict. The plain text request already worked, so a refusal of the
     * feature request is evidence the feature is unsupported. A busy or unreachable provider proves nothing.
     */
    private fun verdict(error: ProviderError): CapabilityResult =
        if (error.isTransient) CapabilityResult(Support.UNKNOWN, Evidence.UNVERIFIED, "Could not check: ${error.message}")
        else CapabilityResult(Support.NO, Evidence.PROBE, error.message.take(200))

    private sealed class Attempt<out T> {
        class Ok<T>(val value: T) : Attempt<T>()
        class Failed(val error: ProviderError) : Attempt<Nothing>()
    }

    private suspend fun <T> attempt(block: suspend () -> T): Attempt<T> = try {
        Attempt.Ok(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: ProviderException) {
        Attempt.Failed(e.error)
    } catch (e: TransportException) {
        Attempt.Failed(ProviderErrors.fromTransport(e))
    }
}
