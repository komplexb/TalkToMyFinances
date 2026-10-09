package com.talktomyfinances.android.ui.generate

data class GenerateViewState(
    val ingredients: String = "",
    val notes: String = "",
    val ingredientsLoading: Boolean = false,
    val recipeLoading: Boolean = false
)