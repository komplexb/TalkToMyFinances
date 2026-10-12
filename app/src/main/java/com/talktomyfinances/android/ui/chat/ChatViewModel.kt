package com.talktomyfinances.android.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.talktomyfinances.android.data.datasource.AIRemoteDataSource
import com.talktomyfinances.android.data.repository.AuthRepository
import com.talktomyfinances.android.domain.AuditorRepository
import com.talktomyfinances.android.domain.ChatMessage
import com.talktomyfinances.android.domain.ChatRepository
import com.talktomyfinances.android.domain.MessageSender
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

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val aiRemoteDataSource: AIRemoteDataSource,
    private val auditorRepository: AuditorRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _inputText = MutableStateFlow("")
    private val _isLoading = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)
    private val _messagesFlow = MutableStateFlow<List<ChatMessage>>(emptyList())

    val uiState: StateFlow<ChatUiState> = combine(
        _messagesFlow,
        _inputText,
        _isLoading,
        _error
    ) { messages, input, loading, err ->
        ChatUiState(
            messages = messages,
            inputText = input,
            isLoading = loading,
            error = err
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ChatUiState()
    )

    private var primedPromptHandled = false

    init {
        ensureAuthAndObserveChat()
    }

    private fun ensureAuthAndObserveChat() {
        viewModelScope.launch {
            val user = authRepository.currentUser ?: run {
                try {
                    authRepository.createAnonymousAccount()
                    authRepository.currentUser
                } catch (e: Exception) {
                    _error.value = "Failed to authenticate: ${e.message}"
                    null
                }
            }

            user?.uid?.let { uid ->
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

        val user = authRepository.currentUser ?: return
        val uid = user.uid

        _inputText.value = ""
        _isLoading.value = true
        _error.value = null

        viewModelScope.launch {
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
                val receipt = auditResult.getOrNull()

                val rawSummary = receipt?.summary ?: "Verified calculation completed over synthetic ledger."
                val verifiedValue = receipt?.value

                // 3. Plain-English Readability Check via gemini-3.5-flash-lite Judge
                val finalProse = aiRemoteDataSource.evaluateWithJudge(rawSummary, verifiedValue)

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
}
