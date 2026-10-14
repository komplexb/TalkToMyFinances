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
import com.talktomyfinances.android.domain.LiveVoiceManager
import com.talktomyfinances.android.domain.MessageSender
import com.talktomyfinances.android.domain.TranscriptEvent
import com.talktomyfinances.android.domain.VisualCard
import com.talktomyfinances.android.domain.VisualCardPayload
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
    private val remoteConfig: FirebaseRemoteConfig
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

    override suspend fun startSession(): Result<Unit> = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _voiceState.value = VoiceState(
                status = VoiceSessionStatus.ERROR,
                errorMessage = "Microphone permission is required for voice mode."
            )
            return@withContext Result.failure(SecurityException("Microphone permission denied"))
        }

        _voiceState.value = VoiceState(status = VoiceSessionStatus.CONNECTING)

        try {
            val modelName = remoteConfig.getString(LIVE_MODEL_NAME_KEY).ifBlank { DEFAULT_LIVE_MODEL }
            val systemPrompt = remoteConfig.getString(LIVE_MODEL_PROMPT_KEY)
            val voiceName = remoteConfig.getString(LIVE_MODEL_VOICE_KEY).ifBlank { DEFAULT_VOICE }

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
                systemInstruction = content { text(systemPrompt) },
                tools = listOf(renderVisualCardTool)
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
        }
    }

    override suspend fun stopSession(): Unit = withContext(Dispatchers.IO) {
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
        const val RENDER_VISUAL_CARD_TOOL_NAME = "render_visual_card"

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
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
