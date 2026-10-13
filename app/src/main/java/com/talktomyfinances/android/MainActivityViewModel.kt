package com.talktomyfinances.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.talktomyfinances.android.data.repository.AuthRepository
import com.talktomyfinances.android.domain.ProfileStore
import com.talktomyfinances.android.ui.insights.InsightsRoute
import com.talktomyfinances.android.ui.onboarding.OnboardingRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainActivityViewModel @Inject constructor(
    private val profileStore: ProfileStore,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _startDestination = MutableStateFlow<Any?>(null)
    val startDestination: StateFlow<Any?> = _startDestination.asStateFlow()

    init {
        determineStartDestination()
    }

    fun determineStartDestination() {
        viewModelScope.launch {
            try {
                if (authRepository.currentUser == null) {
                    authRepository.createAnonymousAccount()
                }
                val profile = profileStore.load()
                _startDestination.value = if (profile == null) OnboardingRoute else InsightsRoute
            } catch (e: Exception) {
                _startDestination.value = OnboardingRoute
            }
        }
    }
}
