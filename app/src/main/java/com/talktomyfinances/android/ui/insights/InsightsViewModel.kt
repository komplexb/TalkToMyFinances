package com.talktomyfinances.android.ui.insights

import androidx.lifecycle.viewModelScope
import com.talktomyfinances.android.MainViewModel
import com.talktomyfinances.android.data.repository.AuthRepository
import com.talktomyfinances.android.domain.ChatRepository
import com.talktomyfinances.android.domain.InsightChip
import com.talktomyfinances.android.domain.InsightsRepository
import com.talktomyfinances.android.domain.ProfileStore
import com.talktomyfinances.android.domain.ReadabilityGuard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface InsightsUiState {
    data object Loading : InsightsUiState
    data object NeedsOnboarding : InsightsUiState
    data object Error : InsightsUiState
    data class Loaded(val chips: List<InsightChip>) : InsightsUiState
}

@HiltViewModel
class InsightsViewModel @Inject constructor(
    private val profileStore: ProfileStore,
    private val insights: InsightsRepository,
    private val readability: ReadabilityGuard,
    private val authRepository: AuthRepository,
    private val chatRepository: ChatRepository
) : MainViewModel() {
    private val _state = MutableStateFlow<InsightsUiState>(InsightsUiState.Loading)
    val state: StateFlow<InsightsUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.value = InsightsUiState.Loading
        viewModelScope.launch {
            try {
                if (authRepository.currentUser == null) authRepository.createAnonymousAccount()
                val profile = profileStore.load()
                if (profile == null) {
                    _state.value = InsightsUiState.NeedsOnboarding
                    return@launch
                }
                val result = insights.loadInsights(profile)
                // Hybrid Inference: rewrite any chip that fails the Grade 6-8 check.
                val chips = result.chips.map { it.copy(text = readability.ensureReadable(it.text)) }
                _state.value = InsightsUiState.Loaded(chips)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("Insights", "Error loading chips", e)
                _state.value = InsightsUiState.Error
            }
        }
    }

    fun clearChatHistory() {
        viewModelScope.launch {
            try {
                val uid = authRepository.currentUserId
                    ?: authRepository.createAnonymousAccount().authId.takeIf { it.isNotBlank() }
                    ?: return@launch
                chatRepository.clearMessages(uid)
            } catch (e: Exception) {
                android.util.Log.e("Insights", "Error clearing chat history", e)
            }
        }
    }
}


