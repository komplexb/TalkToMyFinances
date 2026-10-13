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

    private val _visualCardEvents = MutableSharedFlow<VisualCardPayload>(extraBufferCapacity = 64)
    override val visualCardEvents: SharedFlow<VisualCardPayload> = _visualCardEvents.asSharedFlow()

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

            val liveConfig = liveGenerationConfig {
                speechConfig = SpeechConfig(voice = Voice(DEFAULT_VOICE))
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
            try {
                val payload = parseVisualCardCall(call)
                val emitted = _visualCardEvents.tryEmit(payload)
                Log.d(TAG, "Emitted visual card event: title='${payload.title}', emitted=$emitted")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse visual card payload: ${e.message}", e)
            }
            return createSuccessConfirmation(call)
        }
        return createUnsupportedResponse(call)
    }

    companion object {
        private const val TAG = "LiveVoiceRepo"
        private const val LIVE_MODEL_NAME_KEY = "live_model_name"
        private const val LIVE_MODEL_PROMPT_KEY = "live_model_prompt"
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
                        "payload" to Schema.string("The complete JSON payload containing title, total_amount, items (list of category, amount, percentage), timeframe, and audit_receipt.")
                    )
                )
            )
        )

        fun parseVisualCardCall(call: FunctionCallPart): VisualCardPayload {
            val cardTypeArg = (call.args["card_type"] as? JsonPrimitive)?.content
                ?: (call.args["cardType"] as? JsonPrimitive)?.content
                ?: "category_breakdown"

            val payloadElement = call.args["payload"]
            val parsed = when (payloadElement) {
                is JsonObject -> {
                    json.decodeFromJsonElement(VisualCardPayload.serializer(), payloadElement)
                }
                is JsonPrimitive -> {
                    val raw = payloadElement.content.trim()
                    val clean = if (raw.startsWith("```")) {
                        raw.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                    } else raw
                    json.decodeFromString(VisualCardPayload.serializer(), clean)
                }
                else -> {
                    // Fallback: check if args itself is the payload
                    json.decodeFromJsonElement(VisualCardPayload.serializer(), JsonObject(call.args))
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
