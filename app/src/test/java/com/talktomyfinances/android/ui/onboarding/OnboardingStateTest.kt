package com.talktomyfinances.android.ui.onboarding

import com.talktomyfinances.android.domain.FinancialGoal
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingStateTest {
    @Test
    fun goalStepNeedsAGoalOrOtherText() {
        val s = OnboardingState()
        assertFalse(s.canContinue)
        assertTrue(s.copy(selectedGoal = FinancialGoal.SAFETY_NET).canContinue)
        assertFalse(s.copy(otherSelected = true, otherText = "  ").canContinue)
        assertTrue(s.copy(otherSelected = true, otherText = "buy a boat").canContinue)
    }

    @Test
    fun payStepNeedsAValidIsoDate() {
        val s = OnboardingState(step = OnboardingStep.PAY_CYCLE)
        assertFalse(s.copy(nextPayDate = "").canContinue)
        assertFalse(s.copy(nextPayDate = "12/10/2026").canContinue)
        assertTrue(s.copy(nextPayDate = "2026-10-16").canContinue)
        assertFalse(s.copy(nextPayDate = "2026-10-16", saving = true).canContinue)
    }
}

