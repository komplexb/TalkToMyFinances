package com.talktomyfinances.android.ui.scanMeal

import android.graphics.Bitmap
import com.talktomyfinances.android.MainViewModel
import com.talktomyfinances.android.data.repository.AIRepository
import com.talktomyfinances.android.ui.shared.toBase64
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class ScanMealViewModel @Inject constructor(
    private val aiRepository: AIRepository
) : MainViewModel() {
    private val _viewState = MutableStateFlow(ScanMealViewState())
    val viewState: StateFlow<ScanMealViewState>
        get() = _viewState.asStateFlow()

    fun onImageTaken(
        image: Bitmap?,
        showError: () -> Unit
    ) {
        if (image == null) {
            showError()
            return
        }

        launchCatching {
            _viewState.value = _viewState.value.copy(
                scanLoading = true,
                image = image,
            )

            try {
                val mealSchema = aiRepository.scanMeal(image.toBase64())

                if (mealSchema == null) {
                    showError()
                    return@launchCatching
                }

                _viewState.value = _viewState.value.copy(
                    mealSchema = mealSchema
                )
            } catch (_: Exception) {
                showError()
            } finally {
                _viewState.value = _viewState.value.copy(
                    scanLoading = false
                )
            }
        }
    }
}