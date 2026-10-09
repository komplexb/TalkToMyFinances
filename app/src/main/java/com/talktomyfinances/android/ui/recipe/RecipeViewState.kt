package com.talktomyfinances.android.ui.recipe

import com.talktomyfinances.android.data.model.Recipe

sealed interface RecipeAudioState {
    object Idle : RecipeAudioState
    object LoadingAudio : RecipeAudioState
    object Playing : RecipeAudioState
    object Paused : RecipeAudioState
    data class Error(val message: String) : RecipeAudioState
}

data class RecipeViewState(
    val recipeId: String = "",
    val recipe: Recipe = Recipe(),
    val favorite: Boolean = false,
    val rating: Int = 0,
    val audioState: RecipeAudioState = RecipeAudioState.Idle
)