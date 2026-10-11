package com.talktomyfinances.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.talktomyfinances.android.domain.FinancialGoal
import com.talktomyfinances.android.domain.InsightChip
import com.talktomyfinances.android.domain.RecencyWindow
import com.talktomyfinances.android.ui.insights.InsightsContent
import com.talktomyfinances.android.ui.insights.InsightsUiState
import com.talktomyfinances.android.ui.onboarding.OnboardingContent
import com.talktomyfinances.android.ui.onboarding.OnboardingState
import com.talktomyfinances.android.ui.onboarding.OnboardingStep
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Goal Selection -> Pay Cycle Input -> Insights loading state -> chips. */
class OnboardingFlowTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun goalThenPayCycleThenInsightsLoading() {
        var state by mutableStateOf(OnboardingState())
        var skippedToChat = false
        var insights by mutableStateOf<InsightsUiState?>(null)

        rule.setContent {
            val current = insights
            if (current == null) {
                OnboardingContent(
                    state = state,
                    onGoal = { state = state.copy(selectedGoal = it, otherSelected = false) },
                    onOtherText = { state = state.copy(otherText = it) },
                    onNextPayDate = { state = state.copy(nextPayDate = it) },
                    onNext = {
                        if (state.step == OnboardingStep.GOAL) state = state.copy(step = OnboardingStep.PAY_CYCLE)
                        else insights = InsightsUiState.Loading
                    }
                )
            } else {
                InsightsContent(current, openChat = { skippedToChat = true })
            }
        }

        rule.onNodeWithTag("next").assertIsNotEnabled()
        rule.onNodeWithTag("goal_${FinancialGoal.SAFETY_NET.name}").performClick()
        rule.onNodeWithTag("next").assertIsEnabled().performClick()

        rule.onNodeWithTag("next").assertIsNotEnabled()
        rule.onNodeWithTag("pay_date").performTextInput("2026-10-16")
        rule.onNodeWithTag("next").assertIsEnabled().performClick()

        rule.onNodeWithTag("insights_loading").assertExists()
        rule.onNodeWithTag("skip_to_chat").performClick()
        assertEquals(true, skippedToChat)
    }

    @Test
    fun loadedInsightsShowThreeChips() {
        val chips = listOf(
            InsightChip("a", RecencyWindow.THIS_WEEK),
            InsightChip("b", RecencyWindow.THIS_PAY_CYCLE),
            InsightChip("c", RecencyWindow.YTD, isSerendipity = true)
        )
        rule.setContent { InsightsContent(InsightsUiState.Loaded(chips)) }
        assertEquals(3, rule.onAllNodesWithTag("chip").fetchSemanticsNodes().size)
    }
}

