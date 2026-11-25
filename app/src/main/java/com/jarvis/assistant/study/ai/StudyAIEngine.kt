package com.jarvis.assistant.study.ai

import android.content.Context
import android.util.Base64
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.offline.NetworkStateMonitor
import com.jarvis.assistant.provider.ApiKeySecret
import com.jarvis.assistant.provider.AuthMethod
import com.jarvis.assistant.provider.AuthSpec
import com.jarvis.assistant.provider.Capability
import com.jarvis.assistant.provider.CapabilityPolicy
import com.jarvis.assistant.provider.CapabilityResult
import com.jarvis.assistant.provider.ChatAttachment
import com.jarvis.assistant.provider.ChatClientFactory
import com.jarvis.assistant.provider.ChatConfigSource
import com.jarvis.assistant.provider.ChatEvent
import com.jarvis.assistant.provider.ChatMessage
import com.jarvis.assistant.provider.ChatRequest
import com.jarvis.assistant.provider.ChatResolution
import com.jarvis.assistant.provider.ChatRole
import com.jarvis.assistant.provider.Evidence
import com.jarvis.assistant.provider.HttpTransport
import com.jarvis.assistant.provider.KeyLease
import com.jarvis.assistant.provider.KeyRotationSource
import com.jarvis.assistant.provider.ModelInfo
import com.jarvis.assistant.provider.ProviderException
import com.jarvis.assistant.provider.ProviderProtocol
import com.jarvis.assistant.provider.ProviderRuntime
import com.jarvis.assistant.provider.ResolvedProvider
import com.jarvis.assistant.provider.Support
import com.jarvis.assistant.provider.Thinking
import com.jarvis.assistant.provider.TransportException
import com.jarvis.assistant.provider.WorkCategory
import com.jarvis.assistant.security.AiEngineStore
import com.jarvis.assistant.study.model.ExtractedQuestionCandidate
import com.jarvis.assistant.study.model.StudyDoubtEntry
import com.jarvis.assistant.study.model.StudyInputKind
import com.jarvis.assistant.study.model.StudyJsonCodec
import com.jarvis.assistant.study.model.StudySolution
import com.jarvis.assistant.study.model.StudyStep
import com.jarvis.assistant.study.model.StudyStreamEvent
import com.jarvis.assistant.study.model.StudySubject
import com.jarvis.assistant.study.model.TeacherProfile
import com.jarvis.assistant.study.model.VerificationStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.UUID

/**
 * Unified AI Teaching Engine for the Live Study Board (Sections 5, 10, 11).
 *
 * Reuses JARVIS's existing provider infrastructure ([ChatClientFactory], [ProviderConfigManager],
 * [KeyRotationSource], built-in Gemini key, and Groq config) with incremental streaming,
 * strict educational validation, and honest offline fallback via [StudyOfflineSolver].
 */
class StudyAIEngine(
    private val configSource: ChatConfigSource,
    private val transport: HttpTransport,
    private val keyRotation: KeyRotationSource?,
    private val builtInGeminiKeyProvider: () -> String,
    private val groqConfigProvider: () -> Pair<Boolean, String> = { false to "" },
    private val groqModelProvider: () -> String = { AiEngineStore.DEFAULT_CHAT_MODEL },
    private val isOnlineProvider: () -> Boolean = { true },
    private val memoryContextProvider: (String) -> String = { "" }
) {

    sealed class ProviderTarget {
        data class Ready(val provider: ResolvedProvider, val useKeyPool: Boolean) : ProviderTarget()
        data class Blocked(val reason: String) : ProviderTarget()
        data class Unavailable(val reason: String) : ProviderTarget()
    }

    sealed class DoubtResult {
        data class Answered(val entry: StudyDoubtEntry) : DoubtResult()
        data class Failed(val reason: String) : DoubtResult()
    }

    sealed class OcrResult {
        data class Success(val candidates: List<ExtractedQuestionCandidate>) : OcrResult()
        data class Failed(val reason: String) : OcrResult()
    }

    fun streamSolution(
        question: String,
        profile: TeacherProfile,
        inputKind: StudyInputKind = StudyInputKind.TEXT,
        documentContext: String = "",
        attachmentRef: String? = null,
        imageAttachment: ChatAttachment.Image? = null,
        forceOffline: Boolean = false
    ): Flow<StudyStreamEvent> = flow {
        val cleanQuestion = StudyStrictFilter.cleanQuestion(question)
        if (cleanQuestion.isBlank()) {
            emit(StudyStreamEvent.Error("Please enter, speak, or select an educational question first."))
            return@flow
        }

        if (forceOffline || !isOnlineProvider()) {
            emitOfflineResult(cleanQuestion, profile, inputKind, attachmentRef, offlineReason = true)
            return@flow
        }

        val category = when {
            imageAttachment != null -> WorkCategory.IMAGE
            documentContext.isNotBlank() -> WorkCategory.FILE_ANALYSIS
            else -> WorkCategory.CHAT
        }

        val target = resolveProvider(category, needsVision = imageAttachment != null)
        when (target) {
            is ProviderTarget.Blocked -> {
                emit(StudyStreamEvent.Error(target.reason))
                return@flow
            }
            is ProviderTarget.Unavailable -> {
                // No online API key configured; attempt deterministic offline solver honestly.
                emitOfflineResult(cleanQuestion, profile, inputKind, attachmentRef, offlineReason = false, fallbackMessage = target.reason)
                return@flow
            }
            is ProviderTarget.Ready -> {
                streamWithProvider(
                    target = target,
                    question = cleanQuestion,
                    profile = profile,
                    inputKind = inputKind,
                    documentContext = documentContext,
                    attachmentRef = attachmentRef,
                    imageAttachment = imageAttachment
                ).collect { emit(it) }
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun kotlinx.coroutines.flow.FlowCollector<StudyStreamEvent>.emitOfflineResult(
        question: String,
        profile: TeacherProfile,
        inputKind: StudyInputKind,
        attachmentRef: String?,
        offlineReason: Boolean,
        fallbackMessage: String? = null
    ) {
        when (val offline = StudyOfflineSolver.trySolve(question, profile, inputKind)) {
            is StudyOfflineSolver.OfflineResult.Solved -> {
                val sol = offline.solution.copy(attachmentRef = attachmentRef)
                emit(
                    StudyStreamEvent.Metadata(
                        subject = sol.subject,
                        topic = sol.topic,
                        chapter = sol.chapter,
                        difficulty = sol.difficulty,
                        inferredLevel = sol.educationLevel,
                        question = sol.question,
                        givenValues = sol.givenValues,
                        requiredQuantity = sol.requiredQuantity,
                        formulas = sol.formulas,
                        definitions = sol.definitions
                    )
                )
                for (step in sol.steps) {
                    emit(StudyStreamEvent.StepValidated(step))
                }
                emit(StudyStreamEvent.Completed(sol))
            }
            is StudyOfflineSolver.OfflineResult.Unsupported -> {
                val msg = if (!fallbackMessage.isNullOrBlank() && !offlineReason) {
                    "$fallbackMessage\n\nOffline limit: ${offline.reason}"
                } else {
                    offline.reason
                }
                emit(StudyStreamEvent.Error(msg, isOfflineLimitation = true))
            }
        }
    }

    private fun streamWithProvider(
        target: ProviderTarget.Ready,
        question: String,
        profile: TeacherProfile,
        inputKind: StudyInputKind,
        documentContext: String,
        attachmentRef: String?,
        imageAttachment: ChatAttachment.Image?
    ): Flow<StudyStreamEvent> = flow {
        val memoryCtx = runCatching { memoryContextProvider(question) }.getOrDefault("")
        val sysPrompt = StudyPromptBuilder.buildTeachSystemPrompt(question, profile, memoryCtx)
        val userPrompt = StudyPromptBuilder.buildTeachUserPrompt(question, documentContext)
        val attachments = if (imageAttachment != null) listOf(imageAttachment) else emptyList()
        val messages = listOf(ChatMessage(ChatRole.USER, userPrompt, attachments))

        val pool = if (target.useKeyPool) keyRotation?.takeIf { it.hasKeys(target.provider.profileId) } else null
        val tried = HashSet<String>()

        while (true) {
            var lease: KeyLease? = null
            val activeProvider = if (pool == null) {
                target.provider
            } else {
                lease = pool.lease(target.provider.profileId, tried) ?: run {
                    emit(StudyStreamEvent.Error(pool.unavailableSummary(target.provider.profileId)))
                    return@flow
                }
                target.provider.copy(
                    auth = AuthSpec(target.provider.auth.method, target.provider.auth.name, lease.secret),
                    modelId = lease.modelId.ifBlank { target.provider.modelId },
                    model = if (lease.modelId.isBlank()) target.provider.model else ModelInfo(id = lease.modelId, manual = true)
                )
            }

            val parser = StudyResponseParser()
            var meta: StudyResponseParser.ParsedEvent.Meta? = null
            val validatedSteps = ArrayList<StudyStep>()
            var finalEvent: StudyResponseParser.ParsedEvent.Final? = null

            suspend fun processEvents(events: List<StudyResponseParser.ParsedEvent>) {
                for (ev in events) {
                    when (ev) {
                        is StudyResponseParser.ParsedEvent.Meta -> {
                            when (val res = StudyResponseValidator.validateMeta(ev, question)) {
                                is StudyResponseValidator.ValidationResult.Valid -> {
                                    meta = res.value
                                    emit(
                                        StudyStreamEvent.Metadata(
                                            subject = res.value.subject,
                                            topic = res.value.topic,
                                            chapter = res.value.chapter,
                                            difficulty = res.value.difficulty,
                                            inferredLevel = res.value.educationLevel,
                                            question = res.value.question,
                                            givenValues = res.value.givenValues,
                                            requiredQuantity = res.value.requiredQuantity,
                                            formulas = res.value.formulas,
                                            definitions = res.value.definitions
                                        )
                                    )
                                }
                                is StudyResponseValidator.ValidationResult.Rejected -> Unit
                            }
                        }
                        is StudyResponseParser.ParsedEvent.Step -> {
                            if (meta == null) {
                                // Emit synthetic validated meta from question if model omitted meta line.
                                val fallbackMeta = StudyResponseParser.ParsedEvent.Meta(
                                    subject = StudySubject.GENERAL,
                                    topic = StudySubject.GENERAL.displayName,
                                    chapter = "",
                                    difficulty = "",
                                    educationLevel = profile.educationLevel,
                                    question = question,
                                    givenValues = emptyList(),
                                    requiredQuantity = "",
                                    formulas = emptyList(),
                                    definitions = emptyList()
                                )
                                meta = fallbackMeta
                                emit(
                                    StudyStreamEvent.Metadata(
                                        fallbackMeta.subject, fallbackMeta.topic, "", "",
                                        fallbackMeta.educationLevel, fallbackMeta.question,
                                        emptyList(), "", emptyList(), emptyList()
                                    )
                                )
                            }
                            val nextIdx = validatedSteps.size
                            when (val res = StudyResponseValidator.validateStep(ev.step, nextIdx)) {
                                is StudyResponseValidator.ValidationResult.Valid -> {
                                    validatedSteps += res.value
                                    emit(StudyStreamEvent.StepValidated(res.value))
                                }
                                is StudyResponseValidator.ValidationResult.Rejected -> Unit
                            }
                        }
                        is StudyResponseParser.ParsedEvent.Final -> {
                            finalEvent = ev
                        }
                    }
                }
            }

            try {
                val client = ChatClientFactory.create(activeProvider.protocol, transport)
                val req = ChatRequest(
                    provider = activeProvider,
                    system = sysPrompt,
                    messages = messages,
                    maxTokens = activeProvider.maxTokens ?: 4096,
                    thinking = Thinking.LOW
                )
                client.stream(req).collect { event ->
                    when (event) {
                        is ChatEvent.Delta -> processEvents(parser.feed(event.text))
                        is ChatEvent.Done -> processEvents(parser.finish())
                        is ChatEvent.Usage -> Unit
                    }
                }
                processEvents(parser.finish())
                lease?.let { pool?.reportSuccess(it.keyId) }

                val currentMeta = meta
                val effLang = StudyPromptBuilder.resolveEffectiveLanguage(question, profile)
                val effDepth = StudyPromptBuilder.resolveEffectiveDepth(question, profile)
                val built = StudySolution(
                    id = UUID.randomUUID().toString(),
                    subject = currentMeta?.subject ?: StudySubject.GENERAL,
                    topic = currentMeta?.topic.orEmpty().ifBlank { "Solution" },
                    chapter = currentMeta?.chapter.orEmpty(),
                    difficulty = currentMeta?.difficulty.orEmpty(),
                    educationLevel = currentMeta?.educationLevel ?: profile.educationLevel,
                    language = effLang,
                    depth = effDepth,
                    question = currentMeta?.question ?: question,
                    givenValues = currentMeta?.givenValues.orEmpty(),
                    requiredQuantity = currentMeta?.requiredQuantity.orEmpty(),
                    formulas = currentMeta?.formulas.orEmpty(),
                    definitions = currentMeta?.definitions.orEmpty(),
                    steps = validatedSteps,
                    finalAnswer = finalEvent?.finalAnswer.orEmpty(),
                    verificationStatus = finalEvent?.verificationStatus ?: VerificationStatus.PARTIAL,
                    verificationNote = finalEvent?.verificationNote.orEmpty(),
                    inputKind = inputKind,
                    attachmentRef = attachmentRef,
                    isOffline = false
                )

                when (val checked = StudyResponseValidator.finalizeAndVerify(built)) {
                    is StudyResponseValidator.ValidationResult.Valid -> {
                        emit(StudyStreamEvent.Completed(checked.value))
                    }
                    is StudyResponseValidator.ValidationResult.Rejected -> {
                        emit(StudyStreamEvent.Error("Could not validate a complete educational answer (${checked.reason}). Please retry."))
                    }
                }
                return@flow
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProviderException) {
                val used = lease
                if (used != null && pool != null) {
                    val failure = pool.reportFailure(used.keyId, e.error)
                    tried += used.keyId
                    if (failure.rotates && tried.size < MAX_KEY_ATTEMPTS) continue
                }
                if (validatedSteps.isNotEmpty() && !finalEvent?.finalAnswer.isNullOrBlank()) {
                    val partialSol = StudySolution(
                        subject = meta?.subject ?: StudySubject.GENERAL,
                        topic = meta?.topic.orEmpty(),
                        question = meta?.question ?: question,
                        steps = validatedSteps,
                        finalAnswer = finalEvent?.finalAnswer.orEmpty(),
                        verificationStatus = VerificationStatus.PARTIAL,
                        verificationNote = "Stream interrupted: ${e.error.message}"
                    )
                    emit(StudyStreamEvent.Completed(partialSol))
                } else {
                    emit(StudyStreamEvent.Error(e.error.message))
                }
                return@flow
            } catch (e: TransportException) {
                emit(StudyStreamEvent.Error("Network error while generating lesson: ${e.message ?: "connection interrupted"}"))
                return@flow
            } catch (e: Exception) {
                emit(StudyStreamEvent.Error("Unable to complete lesson: ${e.message ?: e.javaClass.simpleName}"))
                return@flow
            } finally {
                lease?.secret?.wipe()
            }
        }
    }

    suspend fun askDoubt(
        solution: StudySolution,
        targetStep: StudyStep?,
        doubtQuestion: String,
        profile: TeacherProfile
    ): DoubtResult = withContext(Dispatchers.IO) {
        val cleanDoubt = StudyStrictFilter.cleanQuestion(doubtQuestion)
        if (cleanDoubt.isBlank()) return@withContext DoubtResult.Failed("Enter or speak your doubt first.")
        if (!isOnlineProvider()) {
            return@withContext DoubtResult.Failed("Follow-up doubts require an active internet connection.")
        }
        val target = resolveProvider(WorkCategory.CHAT, needsVision = false)
        val resolved = when (target) {
            is ProviderTarget.Ready -> target.provider
            is ProviderTarget.Blocked -> return@withContext DoubtResult.Failed(target.reason)
            is ProviderTarget.Unavailable -> return@withContext DoubtResult.Failed(target.reason)
        }
        try {
            val prompt = StudyPromptBuilder.buildDoubtPrompt(solution, targetStep, cleanDoubt, profile)
            val client = ChatClientFactory.create(resolved.protocol, transport)
            val completion = client.complete(
                ChatRequest(
                    provider = resolved,
                    system = "You are JARVIS Live Study Board answering a focused student doubt cleanly in strict JSON.",
                    messages = listOf(ChatMessage(ChatRole.USER, prompt)),
                    maxTokens = 1400,
                    thinking = Thinking.LOW
                )
            )
            val obj = StudyResponseParser.parseDoubtJson(completion.text)
                ?: return@withContext DoubtResult.Failed("Received an unformatted doubt explanation. Please retry.")
            val summary = StudyStrictFilter.cleanBoardText(obj.optString("boardSummary"))
            val explanation = StudyStrictFilter.cleanExplanation(obj.optString("explanation"))
            val math = StudyJsonCodec.readStringList(obj.optJSONArray("mathExpressions"))
            val diagram = obj.optJSONObject("diagram")?.let { StudyJsonCodec.decodeDiagram(it) }
                ?.let { StudyResponseValidator.validateDiagram(it) }
            if (summary.isBlank() && explanation.isBlank()) {
                return@withContext DoubtResult.Failed("No clear clarification was generated. Please rephrase your doubt.")
            }
            DoubtResult.Answered(
                StudyDoubtEntry(
                    targetStepIndex = targetStep?.stepIndex,
                    targetFocus = targetStep?.let { "Step ${it.stepIndex + 1}" } ?: "Overall Solution",
                    question = cleanDoubt,
                    boardSummary = summary.ifBlank { explanation.take(140) },
                    explanation = explanation.ifBlank { summary },
                    mathExpressions = math,
                    diagram = diagram
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DoubtResult.Failed(e.message ?: "Could not resolve doubt.")
        } finally {
            resolved.auth.secret?.wipe()
        }
    }

    suspend fun extractQuestionsFromImage(
        imageBytes: ByteArray,
        mimeType: String,
        sourceLabel: String
    ): OcrResult = withContext(Dispatchers.IO) {
        if (!isOnlineProvider()) {
            return@withContext OcrResult.Failed("Reading printed or handwritten questions from photos requires an active internet connection.")
        }
        val target = resolveProvider(WorkCategory.IMAGE, needsVision = true)
        val resolved = when (target) {
            is ProviderTarget.Ready -> target.provider
            is ProviderTarget.Blocked -> return@withContext OcrResult.Failed(target.reason)
            is ProviderTarget.Unavailable -> return@withContext OcrResult.Failed(target.reason)
        }
        try {
            val base64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
            val attachment = ChatAttachment.Image(mimeType, base64)
            val client = ChatClientFactory.create(resolved.protocol, transport)
            val completion = client.complete(
                ChatRequest(
                    provider = resolved,
                    system = "You are an accurate educational OCR extractor. Return strict JSON only.",
                    messages = listOf(ChatMessage(ChatRole.USER, StudyPromptBuilder.buildOcrExtractionPrompt(sourceLabel), listOf(attachment))),
                    maxTokens = 2048,
                    thinking = Thinking.LOW
                )
            )
            parseCandidates(completion.text, pageNumber = null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            OcrResult.Failed("Could not extract questions from $sourceLabel: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            resolved.auth.secret?.wipe()
        }
    }

    suspend fun extractQuestionsFromPdfText(
        pdfText: String,
        pageNumber: Int?
    ): OcrResult = withContext(Dispatchers.IO) {
        if (pdfText.isBlank()) {
            return@withContext OcrResult.Failed("No readable text on this PDF page. Use Vision Scan on the page image instead.")
        }
        if (!isOnlineProvider()) {
            // Best-effort offline line/question split so user can still pick & edit locally!
            val localCandidates = splitLocalQuestionCandidates(pdfText, pageNumber)
            return@withContext if (localCandidates.isNotEmpty()) OcrResult.Success(localCandidates)
            else OcrResult.Failed("Connect online to extract structured questions from this PDF.")
        }
        val target = resolveProvider(WorkCategory.FILE_ANALYSIS, needsVision = false)
        val resolved = when (target) {
            is ProviderTarget.Ready -> target.provider
            is ProviderTarget.Blocked -> return@withContext OcrResult.Failed(target.reason)
            is ProviderTarget.Unavailable -> {
                val local = splitLocalQuestionCandidates(pdfText, pageNumber)
                return@withContext if (local.isNotEmpty()) OcrResult.Success(local) else OcrResult.Failed(target.reason)
            }
        }
        try {
            val client = ChatClientFactory.create(resolved.protocol, transport)
            val prompt = """
Extract every distinct educational question / exercise / numerical problem from this PDF excerpt.
Preserve exact math notation and numbers. Do NOT solve them.
Reply with ONLY JSON: {"questions":[{"questionText":"...","subjectHint":"Mathematics|Physics|Chemistry|Biology|Computer Science|English|General","diagramSummary":"","confidenceNote":""}]}

PDF EXCERPT:
${pdfText.take(16_000)}
            """.trimIndent()
            val completion = client.complete(
                ChatRequest(
                    provider = resolved,
                    system = "Extract educational questions accurately into JSON.",
                    messages = listOf(ChatMessage(ChatRole.USER, prompt)),
                    maxTokens = 2400,
                    thinking = Thinking.LOW
                )
            )
            parseCandidates(completion.text, pageNumber)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            val local = splitLocalQuestionCandidates(pdfText, pageNumber)
            if (local.isNotEmpty()) OcrResult.Success(local)
            else OcrResult.Failed("Could not parse questions from PDF content.")
        } finally {
            resolved.auth.secret?.wipe()
        }
    }

    private fun parseCandidates(raw: String, pageNumber: Int?): OcrResult {
        val obj = StudyResponseParser.parseQuestionCandidatesJson(raw)
            ?: return OcrResult.Failed("Could not structure the recognized text. Try retaking a clearer photo or typing the question.")
        val arr = obj.optJSONArray("questions") ?: JSONArray()
        val list = ArrayList<ExtractedQuestionCandidate>()
        for (i in 0 until arr.length()) {
            val qObj = arr.optJSONObject(i) ?: continue
            val qText = StudyStrictFilter.cleanQuestion(qObj.optString("questionText"))
            if (qText.isBlank()) continue
            list += ExtractedQuestionCandidate(
                index = list.size,
                questionText = qText,
                pageNumber = pageNumber,
                subjectHint = StudySubject.fromLabel(qObj.optString("subjectHint")),
                diagramSummary = qObj.optString("diagramSummary").trim(),
                confidenceNote = qObj.optString("confidenceNote").trim()
            )
        }
        return if (list.isEmpty()) {
            OcrResult.Failed("No clear educational question was detected. Try cropping closer or editing manually.")
        } else {
            OcrResult.Success(list)
        }
    }

    private fun splitLocalQuestionCandidates(text: String, pageNumber: Int?): List<ExtractedQuestionCandidate> {
        val normalized = text.replace("\r\n", "\n").trim()
        val split = normalized.split(Regex("(?m)(?=^(?:Q\\.?\\s*\\d+|\\d+[.)]|Question\\s+\\d+)\\b)"))
            .map { it.trim() }
            .filter { it.length >= 6 }
            .take(15)
        return split.mapIndexed { idx, chunk ->
            ExtractedQuestionCandidate(
                index = idx,
                questionText = chunk.take(600),
                pageNumber = pageNumber,
                confidenceNote = "Extracted locally from PDF text — please review before solving."
            )
        }
    }

    fun resolveProvider(category: WorkCategory, needsVision: Boolean): ProviderTarget {
        val resolution = configSource.resolveChat(category)
        when (resolution) {
            is ChatResolution.Ready -> {
                if (needsVision) {
                    val verdict = CapabilityPolicy.imageInput(resolution.provider.model)
                    if (!verdict.ok) {
                        val geminiKey = builtInGeminiKeyProvider().trim()
                        if (geminiKey.isNotBlank()) {
                            return ProviderTarget.Ready(buildGeminiRestProvider(geminiKey), useKeyPool = false)
                        }
                        return ProviderTarget.Blocked("${resolution.provider.name}: ${verdict.reason}")
                    }
                }
                return ProviderTarget.Ready(resolution.provider, useKeyPool = true)
            }
            is ChatResolution.Blocked -> {
                return ProviderTarget.Blocked("${resolution.providerName}: ${resolution.reason}")
            }
            ChatResolution.NotActive -> Unit
        }

        // Built-in engines (Gemini first; Groq if enabled and not requiring vision)
        val (useGroq, groqKey) = groqConfigProvider()
        if (useGroq && groqKey.isNotBlank() && !needsVision) {
            return ProviderTarget.Ready(buildGroqProvider(groqKey, groqModelProvider()), useKeyPool = false)
        }

        val geminiKey = builtInGeminiKeyProvider().trim()
        if (geminiKey.isNotBlank()) {
            return ProviderTarget.Ready(buildGeminiRestProvider(geminiKey), useKeyPool = false)
        }

        if (keyRotation?.hasKeys("gemini") == true) {
            val placeholder = buildGeminiRestProvider("")
            return ProviderTarget.Ready(placeholder, useKeyPool = true)
        }

        return ProviderTarget.Unavailable("No Gemini API key or custom AI provider is configured in Settings.")
    }

    private fun buildGeminiRestProvider(apiKey: String): ResolvedProvider {
        val caps = mapOf(
            Capability.TEXT to CapabilityResult(Support.YES, Evidence.METADATA),
            Capability.STREAMING to CapabilityResult(Support.YES, Evidence.METADATA),
            Capability.IMAGE_INPUT to CapabilityResult(Support.YES, Evidence.METADATA),
            Capability.FILE_INPUT to CapabilityResult(Support.YES, Evidence.METADATA)
        )
        return ResolvedProvider(
            profileId = "gemini",
            name = "Gemini",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            protocol = ProviderProtocol.GEMINI,
            auth = AuthSpec(AuthMethod.X_GOOG_API_KEY, secret = if (apiKey.isNotBlank()) ApiKeySecret.of(apiKey) else null),
            modelId = DEFAULT_GEMINI_STUDY_MODEL,
            model = ModelInfo(id = DEFAULT_GEMINI_STUDY_MODEL, displayName = "Gemini 2.5 Flash", capabilities = caps),
            realtimeUrl = "",
            sttModelId = "",
            settings = mapOf("temperature" to "0.25")
        )
    }

    private fun buildGroqProvider(apiKey: String, modelId: String): ResolvedProvider {
        val caps = mapOf(
            Capability.TEXT to CapabilityResult(Support.YES, Evidence.METADATA),
            Capability.STREAMING to CapabilityResult(Support.YES, Evidence.METADATA)
        )
        return ResolvedProvider(
            profileId = "groq",
            name = "Groq",
            baseUrl = "https://api.groq.com/openai/v1",
            protocol = ProviderProtocol.OPENAI_COMPATIBLE,
            auth = AuthSpec(AuthMethod.BEARER, secret = ApiKeySecret.of(apiKey)),
            modelId = modelId.ifBlank { AiEngineStore.DEFAULT_CHAT_MODEL },
            model = ModelInfo(id = modelId.ifBlank { AiEngineStore.DEFAULT_CHAT_MODEL }, capabilities = caps),
            realtimeUrl = "",
            sttModelId = "",
            settings = mapOf("temperature" to "0.25")
        )
    }

    companion object {
        private const val DEFAULT_GEMINI_STUDY_MODEL = "gemini-2.5-flash"
        private const val MAX_KEY_ATTEMPTS = 6

        fun create(context: Context): StudyAIEngine {
            val appCtx = context.applicationContext
            val app = appCtx as? JarvisApp
            val engineStore = AiEngineStore(appCtx)
            return StudyAIEngine(
                configSource = ProviderRuntime.manager(appCtx),
                transport = ProviderRuntime.transport(),
                keyRotation = ProviderRuntime.keyRotation(appCtx),
                builtInGeminiKeyProvider = { app?.preferences?.apiKey.orEmpty() },
                groqConfigProvider = { engineStore.useGroq to engineStore.groqKey() },
                groqModelProvider = { engineStore.groqChatModel },
                isOnlineProvider = { NetworkStateMonitor.isInternetAvailable(appCtx) },
                memoryContextProvider = { query ->
                    runCatching {
                        com.jarvis.assistant.tools.ToolRuntime.get(appCtx).memory.contextFor(query)
                    }.getOrDefault("")
                }
            )
        }
    }
}
