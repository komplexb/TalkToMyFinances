package com.talktomyfinances.android.ui.onboarding

import androidx.lifecycle.viewModelScope
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.talktomyfinances.android.MainViewModel
import com.talktomyfinances.android.data.repository.AuthRepository
import com.talktomyfinances.android.domain.FinancialGoal
import com.talktomyfinances.android.domain.GoalResolver
import com.talktomyfinances.android.domain.OnboardingProfile
import com.talktomyfinances.android.domain.PayCycle
import com.talktomyfinances.android.domain.PayFrequency
import com.talktomyfinances.android.domain.ProfileStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

enum class OnboardingStep { GOAL, PAY_CYCLE }

data class OnboardingState(
    val step: OnboardingStep = OnboardingStep.GOAL,
    val isDemo: Boolean = false,
    val selectedGoal: FinancialGoal? = if (isDemo) FinancialGoal.MINDFUL_SPENDING else null,
    val otherSelected: Boolean = false,
    val otherText: String = "",
    val frequency: PayFrequency = PayFrequency.FORTNIGHTLY,
    val nextPayDate: String = if (isDemo) "2026-10-13" else "",
    val saving: Boolean = false,
    val error: Boolean = false
) {
    val goalValid get() = selectedGoal != null || (otherSelected && otherText.isNotBlank())
    val payDateValid get() = runCatching { LocalDate.parse(nextPayDate) }.isSuccess
    val canContinue get() = when (step) {
        OnboardingStep.GOAL -> goalValid
        OnboardingStep.PAY_CYCLE -> payDateValid && !saving
    }
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val goalResolver: GoalResolver,
    private val profileStore: ProfileStore,
    private val authRepository: AuthRepository,
    private val remoteConfig: FirebaseRemoteConfig? = null
) : MainViewModel() {
    private val isDemoMode: Boolean = runCatching {
        remoteConfig?.getBoolean("is_demo") ?: true
    }.getOrDefault(true)

    private val _state = MutableStateFlow(
        OnboardingState(
            selectedGoal = if (isDemoMode) FinancialGoal.MINDFUL_SPENDING else null,
            frequency = PayFrequency.FORTNIGHTLY,
            nextPayDate = "2026-10-13",
            isDemo = isDemoMode
        )
    )
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    fun selectGoal(goal: FinancialGoal) = _state.update { it.copy(selectedGoal = goal, otherSelected = false) }
    fun selectOther() = _state.update { it.copy(selectedGoal = null, otherSelected = true) }
    fun onOtherText(text: String) = _state.update { it.copy(otherText = text) }
    fun onFrequency(f: PayFrequency) = _state.update { it.copy(frequency = f) }
    fun onNextPayDate(date: String) = _state.update { it.copy(nextPayDate = date.take(10)) }
    fun back() = _state.update { it.copy(step = OnboardingStep.GOAL) }

    fun reset(startStep: OnboardingStep = OnboardingStep.GOAL) {
        _state.value = OnboardingState(
            step = startStep,
            selectedGoal = if (isDemoMode) FinancialGoal.MINDFUL_SPENDING else null,
            frequency = PayFrequency.FORTNIGHTLY,
            nextPayDate = "2026-10-13",
            isDemo = isDemoMode
        )
    }

    fun next(onDone: () -> Unit) {
        val s = _state.value
        if (!s.canContinue) return
        if (s.step == OnboardingStep.GOAL) {
            _state.update { it.copy(step = OnboardingStep.PAY_CYCLE) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = false) }
            try {
                if (authRepository.currentUser == null) authRepository.createAnonymousAccount()
                val goal = s.selectedGoal ?: goalResolver.resolveCustom(s.otherText)
                profileStore.save(OnboardingProfile(goal, PayCycle(s.frequency, s.nextPayDate)))
                onDone()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = true) }
            } finally {
                _state.update { it.copy(saving = false) }
            }
        }
    }
}

