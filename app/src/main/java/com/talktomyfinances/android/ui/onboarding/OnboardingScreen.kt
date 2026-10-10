package com.talktomyfinances.android.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.talktomyfinances.android.R
import com.talktomyfinances.android.domain.FinancialGoal
import com.talktomyfinances.android.domain.PayFrequency
import com.talktomyfinances.android.ui.theme.TalkToMyFinancesTheme
import kotlinx.serialization.Serializable

@Serializable
object OnboardingRoute

@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel = hiltViewModel(),
    onFinished: () -> Unit
) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    OnboardingContent(
        state = state,
        onGoal = viewModel::selectGoal,
        onOther = viewModel::selectOther,
        onOtherText = viewModel::onOtherText,
        onFrequency = viewModel::onFrequency,
        onNextPayDate = viewModel::onNextPayDate,
        onBack = viewModel::back,
        onNext = { viewModel.next(onFinished) }
    )
}

@Composable
fun OnboardingContent(
    state: OnboardingState,
    onGoal: (FinancialGoal) -> Unit = {},
    onOther: () -> Unit = {},
    onOtherText: (String) -> Unit = {},
    onFrequency: (PayFrequency) -> Unit = {},
    onNextPayDate: (String) -> Unit = {},
    onBack: () -> Unit = {},
    onNext: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when (state.step) {
            OnboardingStep.GOAL -> {
                Text(stringResource(R.string.onboarding_goal_title), style = MaterialTheme.typography.headlineSmall)
                FinancialGoal.entries.forEach { goal ->
                    RadioRow(goal.label, state.selectedGoal == goal, Modifier.testTag("goal_${goal.name}")) { onGoal(goal) }
                }
                RadioRow(stringResource(R.string.onboarding_goal_other), state.otherSelected, Modifier.testTag("goal_OTHER"), onOther)
                if (state.otherSelected) {
                    OutlinedTextField(
                        value = state.otherText,
                        onValueChange = onOtherText,
                        label = { Text(stringResource(R.string.onboarding_goal_other_hint)) },
                        modifier = Modifier.fillMaxWidth().testTag("other_text")
                    )
                }
            }

            OnboardingStep.PAY_CYCLE -> {
                Text(stringResource(R.string.onboarding_pay_title), style = MaterialTheme.typography.headlineSmall)
                PayFrequency.entries.forEach { f ->
                    RadioRow(f.label, state.frequency == f, Modifier.testTag("freq_${f.name}")) { onFrequency(f) }
                }
                OutlinedTextField(
                    value = state.nextPayDate,
                    onValueChange = onNextPayDate,
                    label = { Text(stringResource(R.string.onboarding_next_pay_date)) },
                    isError = state.nextPayDate.isNotEmpty() && !state.payDateValid,
                    modifier = Modifier.fillMaxWidth().testTag("pay_date")
                )
                if (state.error) {
                    Text(stringResource(R.string.error_message), color = MaterialTheme.colorScheme.error)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.step == OnboardingStep.PAY_CYCLE) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.back_button_content_description)) }
            }
            Button(onClick = onNext, enabled = state.canContinue, modifier = Modifier.testTag("next")) {
                Text(stringResource(R.string.onboarding_next))
            }
        }
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 12.dp))
    }
}

@Preview
@Composable
fun OnboardingScreenPreview() {
    TalkToMyFinancesTheme {
        OnboardingContent(
            state = OnboardingState()
        )
    }
}


