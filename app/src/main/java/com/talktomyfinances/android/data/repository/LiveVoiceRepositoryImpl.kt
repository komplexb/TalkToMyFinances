package com.talktomyfinances.android.data.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.firebase.ai.FirebaseAI
import com.google.firebase.ai.type.AudioTranscriptionConfig
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.LiveSession
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.SpeechConfig
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.Voice
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.liveGenerationConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.talktomyfinances.android.data.datasource.AIRemoteDataSource
import com.talktomyfinances.android.domain.AuditReceipt
import com.talktomyfinances.android.domain.AuditorRepository
import com.talktomyfinances.android.domain.ChatMessage
import com.talktomyfinances.android.domain.CurrencySanitizer
import com.talktomyfinances.android.domain.FinancialQuerySpec
import com.talktomyfinances.android.domain.LiveVoiceManager
import com.talktomyfinances.android.domain.MessageSender
import com.talktomyfinances.android.domain.OnboardingProfile
import com.talktomyfinances.android.domain.ProfileStore
import com.talktomyfinances.android.domain.TargetMetric
import com.talktomyfinances.android.domain.TranscriptEvent
import com.talktomyfinances.android.domain.VisualCard
import com.talktomyfinances.android.domain.VisualCardItem
import com.talktomyfinances.android.domain.VisualCardPayload
import com.talktomyfinances.android.domain.VisualCardType
import com.talktomyfinances.android.domain.VoiceSessionStatus
import com.talktomyfinances.android.domain.VoiceState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@OptIn(PublicPreviewAPI::class)
class LiveVoiceRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val aiModel: FirebaseAI,
    private val remoteConfig: FirebaseRemoteConfig,
    private val auditorRepository: AuditorRepository,
    private val profileStore: ProfileStore
) : LiveVoiceManager {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val _voiceState = MutableStateFlow(VoiceState())
    override val voiceState: StateFlow<VoiceState> = _voiceState.asStateFlow()

    private val _transcriptEvents = MutableSharedFlow<TranscriptEvent>(extraBufferCapacity = 64)
    override val transcriptEvents: SharedFlow<TranscriptEvent> = _transcriptEvents.asSharedFlow()

    private val _visualCardEvents = MutableSharedFlow<VisualCard>(extraBufferCapacity = 64)
    override val visualCardEvents: SharedFlow<VisualCard> = _visualCardEvents.asSharedFlow()

    private var liveSession: LiveSession? = null
    private val sessionMutex = kotlinx.coroutines.sync.Mutex()
    @Volatile
    private var activeUserProfile: OnboardingProfile? = null
    @Volatile
    private var latestReceipt: AuditReceipt? = null

    override suspend fun startSession(): Result<Unit> =
        startSession(emptyList(), null, null)

    override suspend fun startSession(
        recentMessages: List<ChatMessage>,
        previousReceipt: AuditReceipt?,
        userProfile: OnboardingProfile?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        sessionMutex.lock()
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                _voiceState.value = VoiceState(
                    status = VoiceSessionStatus.ERROR,
                    errorMessage = "Microphone permission is required for voice mode."
                )
                return@withContext Result.failure(SecurityException("Microphone permission denied"))
            }

            // Ensure any previous session is cleanly stopped before opening a new WebSocket
            liveSession?.let { existing ->
                Log.d(TAG, "Closing existing LiveSession before starting a new one")
                runCatching { existing.stopAudioConversation() }
                runCatching { existing.close() }
                liveSession = null
            }

            _voiceState.value = VoiceState(status = VoiceSessionStatus.CONNECTING)

            val resolvedProfile = userProfile ?: runCatching { profileStore.load() }.getOrNull()
            activeUserProfile = resolvedProfile
            latestReceipt = previousReceipt

            val modelName = remoteConfig.getString(LIVE_MODEL_NAME_KEY).ifBlank { DEFAULT_LIVE_MODEL }
            val basePrompt = remoteConfig.getString(LIVE_MODEL_PROMPT_KEY)
            val voiceName = remoteConfig.getString(LIVE_MODEL_VOICE_KEY).ifBlank { DEFAULT_VOICE }
            val fullSystemPrompt = buildLiveSystemInstruction(
                basePrompt = basePrompt,
                userProfile = resolvedProfile,
                previousReceipt = previousReceipt,
                recentMessages = recentMessages
            )

            val liveConfig = liveGenerationConfig {
                speechConfig = SpeechConfig(voice = Voice(voiceName))
                responseModality = ResponseModality.AUDIO
                inputAudioTranscription = AudioTranscriptionConfig()
                outputAudioTranscription = AudioTranscriptionConfig()
            }

            Log.d(TAG, "Connecting liveModel with model=$modelName")
            val liveModel = aiModel.liveModel(
                modelName = modelName,
                generationConfig = liveConfig,
                systemInstruction = content { text(fullSystemPrompt) },
                tools = listOf(queryLedgerTool)
            )

            val session = liveModel.connect()
            liveSession = session
            _voiceState.value = VoiceState(status = VoiceSessionStatus.LISTENING)
            Log.d(TAG, "LiveSession connected and primed to LISTENING")

            var currentUserTranscript = ""
            var currentAssistantTranscript = ""

            fun flushPendingTurn(pendingSender: MessageSender, pendingText: String) {
                if (pendingText.isNotBlank()) {
                    _transcriptEvents.tryEmit(
                        TranscriptEvent(
                            sender = pendingSender,
                            text = pendingText,
                            isFinal = true
                        )
                    )
                }
            }

            scope.launch {
                try {
                    Log.d(TAG, "Starting audio conversation...")
                    session.startAudioConversation(
                        functionCallHandler = { call ->
                            handleFunctionCall(call)
                        },
                        transcriptHandler = { inputTranscription, outputTranscription ->
                            Log.d(TAG, "transcriptHandler called: input='${inputTranscription?.text}', output='${outputTranscription?.text}'")
                            inputTranscription?.text?.let { text ->
                                if (text.isNotBlank()) {
                                    flushPendingTurn(MessageSender.ASSISTANT, currentAssistantTranscript)
                                    currentAssistantTranscript = ""
                                    currentUserTranscript = text
                                    _voiceState.value = _voiceState.value.copy(
                                        status = VoiceSessionStatus.LISTENING,
                                        activeTranscript = text
                                    )
                                }
                            }

                            outputTranscription?.text?.let { text ->
                                if (text.isNotBlank()) {
                                    flushPendingTurn(MessageSender.USER, currentUserTranscript)
                                    currentUserTranscript = ""
                                    currentAssistantTranscript = text
                                    _voiceState.value = _voiceState.value.copy(
                                        status = VoiceSessionStatus.SPEAKING,
                                        activeTranscript = text
                                    )
                                }
                            }
                        },
                        enableInterruptions = true
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Audio conversation error: ${e.message}", e)
                    _voiceState.value = VoiceState(
                        status = VoiceSessionStatus.ERROR,
                        errorMessage = "Audio streaming interrupted: ${e.message}"
                    )
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to Gemini Live: ${e.message}", e)
            _voiceState.value = VoiceState(
                status = VoiceSessionStatus.ERROR,
                errorMessage = "Could not connect to voice service: ${e.message}"
            )
            Result.failure(e)
        } finally {
            sessionMutex.unlock()
        }
    }

    override suspend fun stopSession(): Unit = withContext(Dispatchers.IO) {
        sessionMutex.lock()
        try {
            val active = _voiceState.value.activeTranscript
            val status = _voiceState.value.status
            if (active.isNotBlank()) {
                val sender = if (status == VoiceSessionStatus.SPEAKING) MessageSender.ASSISTANT else MessageSender.USER
                _transcriptEvents.tryEmit(
                    TranscriptEvent(
                        sender = sender,
                        text = active,
                        isFinal = true
                    )
                )
            }
            liveSession?.stopAudioConversation()
            liveSession?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing live session: ${e.message}")
        } finally {
            liveSession = null
            _voiceState.value = VoiceState(status = VoiceSessionStatus.DISCONNECTED)
            sessionMutex.unlock()
        }
    }

    override fun setMuted(muted: Boolean) {
        val current = _voiceState.value
        val newStatus = when {
            muted -> VoiceSessionStatus.MUTED
            current.status == VoiceSessionStatus.MUTED -> {
                if (liveSession != null) VoiceSessionStatus.LISTENING else VoiceSessionStatus.CONNECTING
            }
            else -> current.status
        }
        _voiceState.value = current.copy(
            isMuted = muted,
            status = newStatus
        )
    }

    internal fun handleFunctionCall(call: FunctionCallPart): FunctionResponsePart {
        Log.d(TAG, "Tool call received: ${call.name} (id=${call.id}) args=${call.args}")
        if (call.name == QUERY_LEDGER_TOOL_NAME) {
            return try {
                val normalizedArgs = call.args.toMutableMap()
                val dw = normalizedArgs["date_window"]
                if (dw is JsonPrimitive && dw.isString) {
                    val cleanType = dw.content.trim().uppercase().replace(" ", "_")
                    normalizedArgs["date_window"] = buildJsonObject { put("type", cleanType) }
                }
                val argsJsonObj = JsonObject(normalizedArgs)
                val chatIntro = (call.args["chat_intro"] as? JsonPrimitive)?.content
                    ?.trim()
                    ?.takeIf { it.isNotBlank() && it != "null" }
                var spec = json.decodeFromJsonElement(FinancialQuerySpec.serializer(), argsJsonObj)
                val profile = activeUserProfile ?: runCatching { runBlocking { profileStore.load() } }.getOrNull()
                spec = AIRemoteDataSource.resolvePayCycleWindow(spec, profile)

                Log.d(TAG, "LiveVoice evaluating FinancialQuerySpec via AuditorRepository: $spec")
                val rawReceipt = runBlocking {
                    auditorRepository.compute(spec).getOrThrow()
                }
                val finalReceipt = rawReceipt.copy(summary = CurrencySanitizer.sanitize(rawReceipt.summary))
                latestReceipt = finalReceipt

                val title = AIRemoteDataSource.resolveCardTitle(finalReceipt)
                val cardToEmit = when {
                    finalReceipt.value != null && spec.targetMetric != TargetMetric.TRANSACTION_LIST && finalReceipt.breakdown.size > 1 -> {
                        VisualCard(
                            cardType = VisualCardType.CATEGORY_BREAKDOWN.wireName,
                            title = title,
                            totalAmount = finalReceipt.value,
                            items = finalReceipt.breakdown.map { item ->
                                VisualCardItem(
                                    category = item.category,
                                    amount = item.amount,
                                    percentage = item.percentage
                                )
                            },
                            auditReceipt = finalReceipt,
                            timeframe = finalReceipt.spec.dateWindow.type.name,
                            caption = chatIntro
                        )
                    }
                    spec.targetMetric == TargetMetric.TRANSACTION_LIST || (finalReceipt.breakdown.size <= 1 && finalReceipt.transactions.isNotEmpty()) -> {
                        VisualCard(
                            cardType = TRANSACTION_LIST_CARD_TYPE,
                            title = title,
                            totalAmount = finalReceipt.value,
                            items = emptyList(),
                            auditReceipt = finalReceipt,
                            timeframe = finalReceipt.spec.dateWindow.type.name,
                            caption = chatIntro
                        )
                    }
                    else -> {
                        VisualCard(
                            cardType = AUDIT_ONLY_CARD_TYPE,
                            title = title,
                            totalAmount = finalReceipt.value,
                            items = emptyList(),
                            auditReceipt = finalReceipt,
                            timeframe = finalReceipt.spec.dateWindow.type.name,
                            caption = chatIntro
                        )
                    }
                }

                val emitted = _visualCardEvents.tryEmit(cardToEmit)
                Log.d(TAG, "Emitted query_ledger visual event: type='${cardToEmit.cardType}', title='${cardToEmit.title}', caption='${cardToEmit.caption}', emitted=$emitted")

                val receiptJson = AIRemoteDataSource.buildReceiptJsonForToolResponse(finalReceipt)
                val responseMap = receiptJson.toMutableMap()
                responseMap["voice_instruction"] = JsonPrimitive(
                    "The detailed card or bulleted transaction breakdown is now displayed on the user's screen. " +
                    "Directly answer the user's question in 2 to 3 natural spoken sentences using the headline numbers/top items from this tool output, and direct them to check the full breakdown on their screen. " +
                    "Do not read out individual transaction dates or long bullet lists."
                )
                FunctionResponsePart(
                    name = call.name,
                    response = JsonObject(responseMap),
                    id = call.id
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to execute query_ledger in LiveVoice: ${e.message}", e)
                createErrorResponse(call, e.message ?: "Failed to execute query_ledger")
            }
        }
        if (call.name == RENDER_VISUAL_CARD_TOOL_NAME) {
            return try {
                val card = parseVisualCardCall(call)
                val emitted = _visualCardEvents.tryEmit(card)
                Log.d(TAG, "Emitted visual card event: title='${card.title}', emitted=$emitted")
                createSuccessConfirmation(call)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse visual card payload: ${e.message}", e)
                createErrorResponse(call, e.message ?: "Failed to parse visual card payload")
            }
        }
        return createUnsupportedResponse(call)
    }

    companion object {
        private const val TAG = "LiveVoiceRepo"
        private const val LIVE_MODEL_NAME_KEY = "live_model_name"
        private const val LIVE_MODEL_PROMPT_KEY = "live_model_prompt"
        private const val LIVE_MODEL_VOICE_KEY = "live_model_voice"
        private const val DEFAULT_LIVE_MODEL = "gemini-3.1-flash-live-preview"
        private const val DEFAULT_VOICE = "Puck"
        const val QUERY_LEDGER_TOOL_NAME = "query_ledger"
        const val RENDER_VISUAL_CARD_TOOL_NAME = "render_visual_card"
        const val TRANSACTION_LIST_CARD_TYPE = "transaction_list"
        const val AUDIT_ONLY_CARD_TYPE = "audit_only"

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

        val LIVE_QUERY_LEDGER_PROPERTIES = AIRemoteDataSource.FINANCIAL_QUERY_SPEC_PROPERTIES + mapOf(
            "chat_intro" to Schema.string(
                "A brief, natural 1-sentence contextual chat message introducing the card or breakdown you are pulling up for the user, varying your phrasing naturally across turns."
            )
        )

        val LIVE_QUERY_LEDGER_OPTIONAL_PROPERTIES =
            AIRemoteDataSource.FINANCIAL_QUERY_SPEC_OPTIONAL_PROPERTIES + listOf("chat_intro")

        val queryLedgerTool = Tool.functionDeclarations(
            listOf(
                FunctionDeclaration(
                    name = QUERY_LEDGER_TOOL_NAME,
                    description = "Executes a deterministic calculation or returns a verified transaction listing over the user's ledger and automatically renders the detailed card or bulleted breakdown on screen.",
                    parameters = LIVE_QUERY_LEDGER_PROPERTIES,
                    optionalParameters = LIVE_QUERY_LEDGER_OPTIONAL_PROPERTIES
                )
            )
        )

        internal fun sanitizeBasePromptForQueryLedger(basePrompt: String): String {
            return basePrompt.lines()
                .filterNot { line ->
                    line.contains("render_visual_card", ignoreCase = true) ||
                        line.contains("Tool Schema Contract", ignoreCase = true)
                }
                .joinToString("\n")
                .trim()
        }

        fun buildLiveSystemInstruction(
            basePrompt: String,
            userProfile: OnboardingProfile?,
            previousReceipt: AuditReceipt?,
            recentMessages: List<ChatMessage>
        ): String {
            val goalName = userProfile?.goal?.name ?: "MINDFUL_SPENDING"
            val payCycleText = userProfile?.payCycle?.let { pc ->
                "The user's pay frequency is ${pc.frequency.label} (${pc.frequency.name}) with next pay date on ${pc.nextPayDate}."
            } ?: "The user's pay frequency is Every 2 weeks (FORTNIGHTLY) with next pay date on 2026-10-13."
            val cleanedBasePrompt = sanitizeBasePromptForQueryLedger(basePrompt)

            return buildString {
                append(cleanedBasePrompt)
                append("\n\n")
                append("User Goal & Pay Cycle:\n")
                append("- The user's primary goal is: $goalName. $payCycleText\n\n")
                append("Tool Calling Instructions for 'query_ledger' (Full Parity with Text Chat):\n")
                append("- Whenever the user asks any factual calculation, ranking (e.g. 'which cafe got the most money'), category breakdown, merchant breakdown, or transaction listing question, you MUST invoke the query_ledger tool FIRST before answering.\n")
                append("- Populate 'chat_intro' with a natural, varied 1-sentence contextual message for the chat bubble that acknowledges what the user just asked for without being repetitive.\n")
                append("- Invoking query_ledger automatically renders the detailed visual card or bulleted transaction list and Audit Receipt on the user's screen.\n")
                append("- Only refer to the card or breakdown on the screen AFTER query_ledger has returned a result. In your spoken voice response, directly answer the user's question (e.g. naming the top merchant/category and its verified dollar amount), do NOT read out the full list of card items or bulleted transaction lines, and NEVER say meta-phrases like \"I can't read long lists\" or \"I won't read all of those out\".\n")
                append("- Merchant-Level Subcategory Clusters vs Broad Categories:\n")
                append(AIRemoteDataSource.formatMerchantClustersPrompt()).append("\n")
                append("  * Broad queries for 'dining' or 'eating out' -> map to included_categories: [\"Dining\"] and empty included_merchants.\n")
                append("- Parameter Rules:\n")
                append(AIRemoteDataSource.formatParameterRulesPrompt()).append("\n")
                append("- If the tool output shows a meaningful change or savings compared to a prior period, briefly name what drove the shift and offer an encouraging, natural idea for how that win supports their goal ($goalName).\n")
                if (previousReceipt != null) {
                    append("\n")
                    append(AIRemoteDataSource.buildPreviousReceiptContext(previousReceipt))
                }
                if (recentMessages.isNotEmpty()) {
                    append("\nRecent conversation turns:\n")
                    recentMessages.takeLast(4).forEach { msg ->
                        append("${msg.sender.name}: ${msg.text}\n")
                    }
                }
            }
        }

        val renderVisualCardTool = Tool.functionDeclarations(
            listOf(
                FunctionDeclaration(
                    name = RENDER_VISUAL_CARD_TOOL_NAME,
                    description = "Renders an interactive inline visual card on screen with detailed financial category breakdowns, spending trends, and deterministic audit receipt.",
                    parameters = mapOf(
                        "card_type" to Schema.string("The type of visual card to render, such as category_breakdown, spending_trend, or monthly_average."),
                        "payload" to Schema.obj(
                            properties = mapOf(
                                "card_type" to Schema.string("The card type identifier."),
                                "title" to Schema.string("The title of the visual card."),
                                "total_amount" to Schema.double("The total calculated dollar amount."),
                                "timeframe" to Schema.string("The timeframe description, e.g. This Month."),
                                "items" to Schema.array(
                                    items = Schema.obj(
                                        properties = mapOf(
                                            "category" to Schema.string("The category name."),
                                            "amount" to Schema.double("The dollar amount."),
                                            "percentage" to Schema.double("The percentage of total.")
                                        ),
                                        optionalProperties = listOf("percentage")
                                    ),
                                    description = "List of category breakdown items."
                                ),
                                "audit_receipt" to Schema.obj(
                                    properties = mapOf(
                                        "summary" to Schema.string("Deterministic calculation summary text."),
                                        "value" to Schema.double("Verified total dollar metric.")
                                    ),
                                    optionalProperties = listOf("value"),
                                    description = "Deterministic audit receipt containing summary and value object."
                                )
                            ),
                            optionalProperties = listOf("card_type", "total_amount", "timeframe", "items", "audit_receipt"),
                            description = "The structured visual card payload object containing title, total_amount, items, timeframe, and audit_receipt."
                        )
                    )
                )
            )
        )

        fun sanitizePayloadObject(obj: JsonObject): JsonObject {
            val auditReceiptElement = obj["audit_receipt"] ?: obj["auditReceipt"]
            if (auditReceiptElement is JsonPrimitive && auditReceiptElement.isString) {
                val receiptSummary = auditReceiptElement.content
                val totalVal = (obj["total_amount"] as? JsonPrimitive)?.content?.toDoubleOrNull()
                    ?: (obj["totalAmount"] as? JsonPrimitive)?.content?.toDoubleOrNull()

                val receiptObj = buildJsonObject {
                    put("summary", receiptSummary)
                    totalVal?.let { put("value", it) }
                }
                val mutableMap = obj.toMutableMap()
                mutableMap["audit_receipt"] = receiptObj
                return JsonObject(mutableMap)
            }
            return obj
        }

        fun parseVisualCardCall(call: FunctionCallPart): VisualCard {
            val cardTypeArg = (call.args["card_type"] as? JsonPrimitive)?.content
                ?: (call.args["cardType"] as? JsonPrimitive)?.content
                ?: "category_breakdown"

            val payloadElement = call.args["payload"]
            val parsed = when (payloadElement) {
                is JsonObject -> {
                    val sanitized = sanitizePayloadObject(payloadElement)
                    json.decodeFromJsonElement(VisualCard.serializer(), sanitized)
                }
                is JsonPrimitive -> {
                    val raw = payloadElement.content.trim()
                    val clean = if (raw.startsWith("```")) {
                        raw.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                    } else raw

                    val jsonElement = runCatching { json.parseToJsonElement(clean) }.getOrNull()
                    if (jsonElement is JsonObject) {
                        val sanitized = sanitizePayloadObject(jsonElement)
                        json.decodeFromJsonElement(VisualCard.serializer(), sanitized)
                    } else {
                        json.decodeFromString(VisualCard.serializer(), clean)
                    }
                }
                else -> {
                    // Fallback: check if args itself is the payload
                    val jsonObj = JsonObject(call.args)
                    val sanitized = sanitizePayloadObject(jsonObj)
                    json.decodeFromJsonElement(VisualCard.serializer(), sanitized)
                }
            }

            return if (parsed.cardType.isBlank() || parsed.cardType == "category_breakdown") {
                if (cardTypeArg.isNotBlank() && cardTypeArg != "category_breakdown") {
                    parsed.copy(cardType = cardTypeArg)
                } else {
                    parsed
                }
            } else {
                parsed
            }
        }

        fun createSuccessConfirmation(call: FunctionCallPart): FunctionResponsePart {
            return FunctionResponsePart(
                name = call.name,
                response = buildJsonObject {
                    put("status", "success")
                    put("message", "Visual card successfully rendered on screen.")
                },
                id = call.id
            )
        }

        fun createErrorResponse(call: FunctionCallPart, errorMessage: String): FunctionResponsePart {
            return FunctionResponsePart(
                name = call.name,
                response = buildJsonObject {
                    put("status", "error")
                    put("message", errorMessage)
                },
                id = call.id
            )
        }

        fun createUnsupportedResponse(call: FunctionCallPart): FunctionResponsePart {
            return FunctionResponsePart(
                name = call.name,
                response = buildJsonObject {
                    put("status", "unsupported")
                    put("message", "Tool ${call.name} is not supported.")
                },
                id = call.id
            )
        }
    }
}
