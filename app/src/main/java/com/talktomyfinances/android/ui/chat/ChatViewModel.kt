package com.talktomyfinances.android.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.talktomyfinances.android.data.datasource.AIRemoteDataSource
import com.talktomyfinances.android.data.repository.AuthRepository
import com.talktomyfinances.android.domain.AuditorRepository
import com.talktomyfinances.android.domain.ChatMessage
import com.talktomyfinances.android.domain.ChatRepository
import com.talktomyfinances.android.domain.MessageSender
import com.talktomyfinances.android.domain.LiveVoiceManager
import com.talktomyfinances.android.domain.VoiceState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ChatInputMode {
    TEXT,
    VOICE
}

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val inputMode: ChatInputMode = ChatInputMode.TEXT,
    val voiceState: VoiceState = VoiceState()
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val aiRemoteDataSource: AIRemoteDataSource,
    private val auditorRepository: AuditorRepository,
    private val authRepository: AuthRepository,
    private val liveVoiceManager: LiveVoiceManager
) : ViewModel() {

    private val _inputText = MutableStateFlow("")
    private val _isLoading = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)
    private val _messagesFlow = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val _inputMode = MutableStateFlow(ChatInputMode.TEXT)

    val uiState: StateFlow<ChatUiState> = combine(
        _messagesFlow,
        _inputText,
        _isLoading,
        combine(_error, _inputMode, liveVoiceManager.voiceState) { err, mode, vState ->
            Triple(err, mode, vState)
        }
    ) { messages, input, loading, (err, mode, vState) ->
        ChatUiState(
            messages = messages,
            inputText = input,
            isLoading = loading,
            error = err,
            inputMode = mode,
            voiceState = vState
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ChatUiState()
    )

    private var primedPromptHandled = false

    init {
        ensureAuthAndObserveChat()
        observeVoiceTranscripts()
    }

    private suspend fun getOrEnsureUserId(): String? {
        val current = authRepository.currentUser
        if (current != null) return current.uid

        return try {
            authRepository.createAnonymousAccount()
            authRepository.currentUser?.uid
        } catch (e: Exception) {
            _error.value = "Failed to authenticate: ${e.message}"
            null
        }
    }

    private fun ensureAuthAndObserveChat() {
        viewModelScope.launch {
            val uid = getOrEnsureUserId()
            if (uid != null) {
                chatRepository.getMessages(uid)
                    .catch { e -> _error.value = "Failed to load chat history: ${e.message}" }
                    .collect { list -> _messagesFlow.value = list }
            }
        }
    }

    fun handlePrimedPrompt(prompt: String?) {
        if (!prompt.isNullOrBlank() && !primedPromptHandled) {
            primedPromptHandled = true
            sendMessage(prompt)
        }
    }

    fun onInputTextChanged(text: String) {
        _inputText.value = text
    }

    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        _inputText.value = ""
        _isLoading.value = true
        _error.value = null

        viewModelScope.launch {
            val uid = getOrEnsureUserId()
            if (uid == null) {
                _isLoading.value = false
                return@launch
            }

            val userMsg = ChatMessage(
                sender = MessageSender.USER,
                text = trimmed
            )

            try {
                chatRepository.saveMessage(uid, userMsg)
            } catch (e: Exception) {
                _error.value = "Could not send message: ${e.message}"
                _isLoading.value = false
                return@launch
            }

            try {
                // 1. Intent Parsing via Firebase AI Logic (gemini-3.8-flash)
                val spec = aiRemoteDataSource.parseFinancialIntent(trimmed)

                // 2. Deterministic Math via Cloud Run Auditor (/compute)
                val auditResult = auditorRepository.compute(spec)
                val receipt = auditResult.getOrThrow()

                // 3. Plain-English Readability Check via gemini-3.5-flash-lite Judge
                val finalProse = aiRemoteDataSource.evaluateWithJudge(receipt.summary, receipt.value)

                // 4. Save Assistant turn with Audit Receipt to Firestore
                val assistantMsg = ChatMessage(
                    sender = MessageSender.ASSISTANT,
                    text = finalProse,
                    auditReceipt = receipt
                )
                chatRepository.saveMessage(uid, assistantMsg)
            } catch (e: Exception) {
                val errorMsg = ChatMessage(
                    sender = MessageSender.ASSISTANT,
                    text = "I ran into an issue calculating that. Let's try rephrasing your question."
                )
                chatRepository.saveMessage(uid, errorMsg)
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun observeVoiceTranscripts() {
        viewModelScope.launch {
            liveVoiceManager.transcriptEvents.collect { event ->
                val user = authRepository.currentUser ?: return@collect
                val uid = user.uid

                val chatMsg = ChatMessage(
                    sender = event.sender,
                    text = event.text
                )
                try {
                    chatRepository.saveMessage(uid, chatMsg)
                } catch (e: Exception) {
                    _error.value = "Failed to sync voice transcript: ${e.message}"
                }
            }
        }
    }

    fun switchToVoice() {
        _inputMode.value = ChatInputMode.VOICE
        _error.value = null
        viewModelScope.launch {
            val result = liveVoiceManager.startSession()
            if (result.isFailure) {
                _error.value = result.exceptionOrNull()?.message ?: "Failed to start voice session"
            }
        }
    }

    fun switchToText() {
        _inputMode.value = ChatInputMode.TEXT
        viewModelScope.launch {
            liveVoiceManager.stopSession()
        }
    }

    fun toggleMute() {
        val currentMute = uiState.value.voiceState.isMuted
        liveVoiceManager.setMuted(!currentMute)
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            liveVoiceManager.stopSession()
        }
    }
}
