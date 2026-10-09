package com.talktomyfinances.android.ui.generate

import android.graphics.Bitmap
import android.util.Log
import com.talktomyfinances.android.MainViewModel
import com.talktomyfinances.android.data.model.Recipe
import com.talktomyfinances.android.data.repository.AIRepository
import com.talktomyfinances.android.data.repository.AuthRepository
import com.talktomyfinances.android.data.repository.DatabaseRepository
import com.talktomyfinances.android.data.repository.StorageRepository
import com.talktomyfinances.android.data.schema.RecipeSchema
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class GenerateViewModel @Inject constructor(
    private val aiRepository: AIRepository,
    private val authRepository: AuthRepository,
    private val storageRepository: StorageRepository,
    private val databaseRepository: DatabaseRepository
) : MainViewModel() {
    private val _viewState = MutableStateFlow(GenerateViewState())
    val viewState: StateFlow<GenerateViewState>
        get() = _viewState.asStateFlow()

    init {
        loadCurrentUser()
        loadOnDeviceModel()
    }

    fun loadCurrentUser() {
        launchCatching {
            if (authRepository.currentUser == null) {
                val user = authRepository.createAnonymousAccount()
                databaseRepository.addUser(user)
            }
        }
    }

    fun loadOnDeviceModel() {
        launchCatching {
            aiRepository.loadOnDeviceModel()
        }
    }

    fun onIngredientsUpdated(ingredients: String) {
        _viewState.value = _viewState.value.copy(
            ingredients = ingredients
        )
    }

    fun onNotesUpdated(notes: String) {
        _viewState.value = _viewState.value.copy(
            notes = notes
        )
    }

    fun onImageTaken(image: Bitmap?, showError: () -> Unit) {
        if (image == null) {
            showError()
            return
        }

        launchCatching {
            _viewState.value = _viewState.value.copy(
                ingredientsLoading = true
            )

            try {
                val ingredients = aiRepository.generateIngredients(image)

                _viewState.value = _viewState.value.copy(
                    ingredients = ingredients
                )
            } catch (e: Exception) {
                Log.e("GenerateViewModel", "Error generating ingredients from image", e)
                showError()
            } finally {
                _viewState.value = _viewState.value.copy(
                    ingredientsLoading = false
                )
            }
        }
    }

    fun generateRecipe(
        openRecipeScreen: (String) -> Unit,
        showError: () -> Unit
    ) {
        launchCatching {
            _viewState.value = _viewState.value.copy(
                recipeLoading = true
            )

            try {
                val generatedRecipe = aiRepository.generateRecipe(
                    _viewState.value.ingredients,
                    _viewState.value.notes
                )

                if (generatedRecipe == null) {
                    showError()
                    return@launchCatching
                }

                val recipeImage = aiRepository.generateRecipePhoto(generatedRecipe.title)
                var recipeImageUri: String? = null

                if (recipeImage != null) {
                    recipeImageUri = storageRepository.addImage(recipeImage)
                }

                val storedRecipeId = databaseRepository.addRecipe(
                    recipe = generatedRecipe.toRecipe(
                        authorId = authRepository.currentUser?.uid.orEmpty(),
                        imageUri = recipeImageUri
                    )
                )

                openRecipeScreen(storedRecipeId)
            } catch (e: Exception) {
                Log.e("GenerateViewModel", "Error generating recipe", e)
                showError()
            } finally {
                _viewState.value = _viewState.value.copy(
                    recipeLoading = false
                )
            }
        }
    }

    private fun RecipeSchema.toRecipe(authorId: String, imageUri: String?): Recipe {
        return Recipe(
            title = title,
            instructions = instructions,
            ingredients = ingredients,
            authorId = authorId,
            tags = tags,
            prepTime = prepTime,
            cookTime = cookTime,
            servings = servings,
            imageUri = imageUri
        )
    }
}