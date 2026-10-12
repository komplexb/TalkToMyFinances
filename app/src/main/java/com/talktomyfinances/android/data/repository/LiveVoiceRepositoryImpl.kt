package com.talktomyfinances.android.data.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.firebase.ai.FirebaseAI
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
import com.talktomyfinances.android.domain.AuditReceipt
import com.talktomyfinances.android.domain.FinancialQuerySpec
import com.talktomyfinances.android.domain.LiveVoiceManager
import com.talktomyfinances.android.domain.MessageSender
import com.talktomyfinances.android.domain.TranscriptEvent
import com.talktomyfinances.android.domain.VisualCardItemData
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
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

    private val _visualCardEvents = MutableSharedFlow<VisualCardPayload>(extraBufferCapacity = 64)
    override val visualCardEvents: SharedFlow<VisualCardPayload> = _visualCardEvents.asSharedFlow()

    private var liveSession: LiveSession? = null

    val renderVisualCardDeclaration = FunctionDeclaration(
        name = "render_visual_card",
        description = "Renders an interactive visual card showing category spending breakdowns, trends, or dense figures on screen instead of speaking dense tables aloud.",
        parameters = mapOf(
            "card_type" to Schema.string("The type of visual card (e.g. 'category_breakdown', 'spending_trend', 'monthly_average')"),
            "payload" to Schema.obj(
                properties = mapOf(
                    "title" to Schema.string("Title of the visual breakdown card"),
                    "total_amount" to Schema.double("Total dollar amount of the breakdown", nullable = true),
                    "items" to Schema.array(
                        Schema.obj(
                            properties = mapOf(
                                "category" to Schema.string("Category or merchant name"),
                                "amount" to Schema.double("Dollar amount spent"),
                                "percentage" to Schema.double("Percentage of total spend (0 to 100)", nullable = true)
                            )
                        ),
                        description = "List of category items and their amounts"
                    ),
                    "audit_receipt" to Schema.obj(
                        properties = mapOf(
                            "summary" to Schema.string("Plain-English explanation of the calculation"),
                            "value" to Schema.double("Verified metric value", nullable = true),
                            "timeframe" to Schema.string("Recency timeframe", nullable = true)
                        ),
                        nullable = true
                    ),
                    "timeframe" to Schema.string("Recency timeframe or period", nullable = true)
                )
            )
        )
    )

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

            val liveConfig = liveGenerationConfig {
                speechConfig = SpeechConfig(voice = Voice(DEFAULT_VOICE))
                responseModality = ResponseModality.AUDIO
            }

            val liveModel = aiModel.liveModel(
                modelName = modelName,
                generationConfig = liveConfig,
                tools = listOf(Tool.functionDeclarations(listOf(renderVisualCardDeclaration))),
                systemInstruction = content { text(systemPrompt) }
            )

            val session = liveModel.connect()
            liveSession = session

            _voiceState.value = VoiceState(status = VoiceSessionStatus.CONNECTED)

            scope.launch {
                try {
                    session.startAudioConversation(
                        functionCallHandler = { call ->
                            handleFunctionCall(call)
                        },
                        transcriptHandler = { inputTranscription, outputTranscription ->
                            inputTranscription?.text?.let { text ->
                                if (text.isNotBlank()) {
                                    _voiceState.value = _voiceState.value.copy(
                                        status = VoiceSessionStatus.LISTENING,
                                        activeTranscript = text
                                    )
                                    _transcriptEvents.tryEmit(
                                        TranscriptEvent(
                                            sender = MessageSender.USER,
                                            text = text,
                                            isFinal = true
                                        )
                                    )
                                }
                            }

                            outputTranscription?.text?.let { text ->
                                if (text.isNotBlank()) {
                                    _voiceState.value = _voiceState.value.copy(
                                        status = VoiceSessionStatus.SPEAKING,
                                        activeTranscript = text
                                    )
                                    _transcriptEvents.tryEmit(
                                        TranscriptEvent(
                                            sender = MessageSender.ASSISTANT,
                                            text = text,
                                            isFinal = true
                                        )
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

    fun handleFunctionCall(call: FunctionCallPart): FunctionResponsePart {
        if (call.name == "render_visual_card") {
            try {
                val visualCard = parseVisualCardCall(call)
                _visualCardEvents.tryEmit(visualCard)
                return FunctionResponsePart(
                    name = call.name,
                    response = JsonObject(
                        mapOf(
                            "status" to JsonPrimitive("rendered"),
                            "message" to JsonPrimitive("Visual card mounted successfully on client screen")
                        )
                    ),
                    id = call.id
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing render_visual_card payload: ${e.message}", e)
                return FunctionResponsePart(
                    name = call.name,
                    response = JsonObject(
                        mapOf(
                            "status" to JsonPrimitive("error"),
                            "message" to JsonPrimitive("Failed to render visual card: ${e.message}")
                        )
                    ),
                    id = call.id
                )
            }
        }
        return FunctionResponsePart(
            name = call.name,
            response = JsonObject(mapOf("status" to JsonPrimitive("unknown_function"))),
            id = call.id
        )
    }

    override suspend fun stopSession(): Unit = withContext(Dispatchers.IO) {
        try {
            audioManager?.isMicrophoneMute = false
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
        audioManager?.isMicrophoneMute = muted
        _voiceState.value = _voiceState.value.copy(
            isMuted = muted,
            status = if (muted) VoiceSessionStatus.MUTED else VoiceSessionStatus.LISTENING
        )
    }

    companion object {
        private const val TAG = "LiveVoiceRepo"
        private const val LIVE_MODEL_NAME_KEY = "live_model_name"
        private const val LIVE_MODEL_PROMPT_KEY = "live_model_prompt"
        private const val DEFAULT_LIVE_MODEL = "gemini-3.1-flash-live-preview"
        private const val DEFAULT_VOICE = "Puck"

        fun parseVisualCardCall(call: FunctionCallPart): VisualCardPayload {
            val cardType = (call.args["card_type"] as? JsonPrimitive)?.content ?: "category_breakdown"
            val payloadObj = (call.args["payload"] as? JsonObject) ?: JsonObject(call.args)
            return parseVisualCardFromJson(cardType, payloadObj)
        }

        fun parseVisualCardFromJson(cardType: String, obj: JsonObject): VisualCardPayload {
            val title = (obj["title"] as? JsonPrimitive)?.content ?: "Financial Breakdown"
            val totalAmount = (obj["total_amount"] as? JsonPrimitive)?.doubleOrNull
                ?: (obj["total_amount"] as? JsonPrimitive)?.content?.toDoubleOrNull()
            val timeframe = (obj["timeframe"] as? JsonPrimitive)?.content

            val itemsList = mutableListOf<VisualCardItemData>()
            val itemsArray = obj["items"] as? JsonArray
            itemsArray?.forEach { element ->
                if (element is JsonObject) {
                    val category = (element["category"] as? JsonPrimitive)?.content
                        ?: (element["name"] as? JsonPrimitive)?.content
                        ?: "Other"
                    val amount = (element["amount"] as? JsonPrimitive)?.doubleOrNull
                        ?: (element["amount"] as? JsonPrimitive)?.content?.toDoubleOrNull()
                        ?: 0.0
                    val percentage = (element["percentage"] as? JsonPrimitive)?.doubleOrNull
                        ?: (element["percentage"] as? JsonPrimitive)?.content?.toDoubleOrNull()
                        ?: if (totalAmount != null && totalAmount > 0) (amount / totalAmount * 100.0) else null
                    itemsList.add(
                        VisualCardItemData(
                            category = category,
                            amount = amount,
                            percentage = percentage
                        )
                    )
                }
            }

            val receiptObj = obj["audit_receipt"] as? JsonObject
            val auditReceipt = if (receiptObj != null) {
                val summary = (receiptObj["summary"] as? JsonPrimitive)?.content
                    ?: "Verified calculation completed over synthetic ledger."
                val value = (receiptObj["value"] as? JsonPrimitive)?.doubleOrNull
                    ?: (receiptObj["value"] as? JsonPrimitive)?.content?.toDoubleOrNull()
                val receiptTimeframe = (receiptObj["timeframe"] as? JsonPrimitive)?.content
                AuditReceipt(
                    summary = summary,
                    value = value,
                    spec = FinancialQuerySpec(
                        targetMetric = if (cardType == "monthly_average") "MONTHLY_AVERAGE" else "TOTAL_SUM",
                        temporalGrain = receiptTimeframe ?: "MONTH"
                    )
                )
            } else null

            return VisualCardPayload(
                cardType = cardType,
                title = title,
                totalAmount = totalAmount,
                items = itemsList,
                auditReceipt = auditReceipt,
                timeframe = timeframe
            )
        }
    }
}
