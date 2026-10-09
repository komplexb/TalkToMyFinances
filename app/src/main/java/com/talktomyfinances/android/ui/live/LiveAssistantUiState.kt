package com.talktomyfinances.android.ui.live

import com.talktomyfinances.android.data.model.Recipe

sealed interface LiveAssistantUiState {
    data object Loading : LiveAssistantUiState
    data class Success(val recipe: Recipe) : LiveAssistantUiState
    data class Error(val message: String) : LiveAssistantUiState
}
