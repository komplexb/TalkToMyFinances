package com.talktomyfinances.android.data.repository

import android.graphics.Bitmap
import com.talktomyfinances.android.data.datasource.AIRemoteDataSource
import com.talktomyfinances.android.data.schema.MealSchema
import com.talktomyfinances.android.data.schema.RecipeSchema
import com.talktomyfinances.android.data.schema.StoreSchema
import javax.inject.Inject

class AIRepository @Inject constructor(
    private val aiRemoteDataSource: AIRemoteDataSource
) {
    suspend fun generateIngredients(image: Bitmap): String {
        return aiRemoteDataSource.generateIngredients(image)
    }

    suspend fun findStores(
        ingredients: List<String>,
        latitude: Double,
        longitude: Double,
        currentTime: String,
        dayOfWeek: String
    ): List<StoreSchema> {
        return aiRemoteDataSource.findStores(
            ingredients,
            latitude,
            longitude,
            currentTime,
            dayOfWeek
        )
    }

    suspend fun generateRecipe(ingredients: String, notes: String): RecipeSchema? {
        return aiRemoteDataSource.generateRecipe(ingredients, notes)
    }

    suspend fun generateRecipePhoto(recipeTitle: String): Bitmap? {
        return aiRemoteDataSource.generateRecipePhoto(recipeTitle)
    }

    suspend fun scanMeal(imageData: String): MealSchema? {
        return aiRemoteDataSource.scanMeal(imageData)
    }

    suspend fun craftRecipePairing(dishTitle: String, ingredients: List<String>): String {
        return aiRemoteDataSource.craftRecipePairing(dishTitle, ingredients)
    }

    suspend fun generateSpeech(text: String?): ByteArray? {
        return aiRemoteDataSource.generateSpeech(text)
    }

    suspend fun loadOnDeviceModel() {
        aiRemoteDataSource.loadOnDeviceModel()
    }
}