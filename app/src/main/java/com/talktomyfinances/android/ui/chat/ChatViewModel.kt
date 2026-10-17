package com.talktomyfinances.android.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.talktomyfinances.android.data.datasource.AIChatDataSource
import com.talktomyfinances.android.data.repository.AuthRepository
import com.talktomyfinances.android.domain.AuditorRepository
import com.talktomyfinances.android.domain.ChatMessage
import com.talktomyfinances.android.domain.ChatRepository
import com.talktomyfinances.android.domain.CurrencySanitizer
import com.talktomyfinances.android.domain.LiveVoiceManager
import com.talktomyfinances.android.domain.MessageSender
import com.talktomyfinances.android.domain.ProfileStore
import com.talktomyfinances.android.domain.VisualCard
import com.talktomyfinances.android.domain.VisualCardItem
import com.talktomyfinances.android.domain.VisualCardType
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
    private val aiChatDataSource: AIChatDataSource,
    private val authRepository: AuthRepository,
    private val liveVoiceManager: LiveVoiceManager,
    private val profileStore: ProfileStore
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
        observeVisualCardEvents()
    }

    private suspend fun getOrEnsureUserId(): String? {
        val current = authRepository.currentUserId
        if (current != null) return current

        return try {
            val user = authRepository.createAnonymousAccount()
            authRepository.currentUserId ?: user.authId.takeIf { it.isNotBlank() }
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
                val previousReceipt = _messagesFlow.value.lastOrNull { it.auditReceipt != null }?.auditReceipt
                val recentTurns = _messagesFlow.value.takeLast(4)
                val userGoal = try { profileStore.load()?.goal } catch (_: Exception) { null }

                val response = aiChatDataSource.sendMessage(
                    prompt = trimmed,
                    recentMessages = recentTurns,
                    previousReceipt = previousReceipt,
                    userGoal = userGoal
                )

                val assistantMsg = ChatMessage(
                    sender = MessageSender.ASSISTANT,
                    text = response.prose,
                    auditReceipt = response.auditReceipt,
                    visualCard = response.visualCard
                )
                chatRepository.saveMessage(uid, assistantMsg)
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "Error calculating response for query: $trimmed", e)
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
        // Voice dialogue transcribing to chat bubbles is disabled per design.
        // Supporting visual cards / charts will be rendered inline during tool calls in Issue #33.
    }

    private fun observeVisualCardEvents() {
        viewModelScope.launch {
            liveVoiceManager.visualCardEvents.collect { payload ->
                val uid = getOrEnsureUserId() ?: return@collect

                val title = payload.title.ifBlank { "Spending breakdown" }
                val timeframeDesc = when (payload.timeframe?.trim()?.uppercase()) {
                    "THIS_WEEK" -> "this week"
                    "LAST_WEEK" -> "last week"
                    "THIS_PAY_CYCLE" -> "this pay cycle"
                    "LAST_30_DAYS" -> "over the last 30 days"
                    "THIS_QUARTER" -> "this quarter"
                    "SIX_MONTHS" -> "over the past 6 months"
                    "YTD" -> "so far this year"
                    null, "" -> null
                    else -> payload.timeframe
                }

                val summaryText = when {
                    timeframeDesc != null && !title.contains(timeframeDesc, ignoreCase = true) ->
                        "Here is your $title $timeframeDesc."
                    else ->
                        "Here is your $title."
                }

                val chatMsg = ChatMessage(
                    sender = MessageSender.ASSISTANT,
                    text = summaryText,
                    visualCard = payload,
                    auditReceipt = payload.auditReceipt
                )
                try {
                    chatRepository.saveMessage(uid, chatMsg)
                } catch (e: Exception) {
                    _error.value = "Failed to sync visual card: ${e.message}"
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
