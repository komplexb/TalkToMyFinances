package com.talktomyfinances.android.data.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.firebase.ai.FirebaseAI
import com.google.firebase.ai.type.LiveSession
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.SpeechConfig
import com.google.firebase.ai.type.Voice
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.liveGenerationConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.talktomyfinances.android.domain.LiveVoiceManager
import com.talktomyfinances.android.domain.MessageSender
import com.talktomyfinances.android.domain.TranscriptEvent
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
            }

            val liveModel = aiModel.liveModel(
                modelName = modelName,
                generationConfig = liveConfig,
                systemInstruction = content { text(systemPrompt) }
            )

            val session = liveModel.connect()
            liveSession = session

            var currentUserTranscript = ""
            var currentAssistantTranscript = ""

            scope.launch {
                try {
                    session.startAudioConversation(
                        transcriptHandler = { inputTranscription, outputTranscription ->
                            inputTranscription?.text?.let { text ->
                                if (text.isNotBlank()) {
                                    if (currentAssistantTranscript.isNotBlank()) {
                                        _transcriptEvents.tryEmit(
                                            TranscriptEvent(
                                                sender = MessageSender.ASSISTANT,
                                                text = currentAssistantTranscript,
                                                isFinal = true
                                            )
                                        )
                                        currentAssistantTranscript = ""
                                    }
                                    currentUserTranscript = text
                                    _voiceState.value = _voiceState.value.copy(
                                        status = VoiceSessionStatus.LISTENING,
                                        activeTranscript = text
                                    )
                                }
                            }

                            outputTranscription?.text?.let { text ->
                                if (text.isNotBlank()) {
                                    if (currentUserTranscript.isNotBlank()) {
                                        _transcriptEvents.tryEmit(
                                            TranscriptEvent(
                                                sender = MessageSender.USER,
                                                text = currentUserTranscript,
                                                isFinal = true
                                            )
                                        )
                                        currentUserTranscript = ""
                                    }
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
    }
}
