package com.talktomyfinances.android.data.schema

import kotlinx.serialization.Serializable

@Serializable
data class MealSchema(
    val protein: String = "0g",
    val fat: String = "0g",
    val carbs: String = "0g",
    val sugar: String = "0g",
    val ingredients: List<String> = emptyList()
)