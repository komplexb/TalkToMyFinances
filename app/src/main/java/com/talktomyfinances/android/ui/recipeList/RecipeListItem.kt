package com.talktomyfinances.android.ui.recipeList

data class RecipeListItem(
    val id: String = "",
    val title: String = "",
    val averageRating: Double = 0.0,
    val imageUri: String? = null
)