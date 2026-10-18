package com.talktomyfinances.android.ui.onboarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.window.Dialog

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
fun OnboardingDialog(
    onDismiss: () -> Unit,
    onFinished: () -> Unit = onDismiss,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    LaunchedEffect(Unit) {
        viewModel.reset(OnboardingStep.GOAL)
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            OnboardingContent(
                state = state,
                onGoal = viewModel::selectGoal,
                onOther = viewModel::selectOther,
                onOtherText = viewModel::onOtherText,
                onFrequency = viewModel::onFrequency,
                onNextPayDate = viewModel::onNextPayDate,
                onBack = viewModel::back,
                onNext = {
                    viewModel.next {
                        onFinished()
                        onDismiss()
                    }
                },
                isDialog = true
            )
        }
    }
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
    onNext: () -> Unit = {},
    isDialog: Boolean = false
) {
    Column(
        modifier = (if (isDialog) Modifier.fillMaxWidth() else Modifier.fillMaxSize())
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when (state.step) {
            OnboardingStep.GOAL -> {
                Text(stringResource(R.string.onboarding_goal_title), style = MaterialTheme.typography.headlineSmall)
                FinancialGoal.entries.forEach { goal ->
                    val isDemoGoal = goal == FinancialGoal.MINDFUL_SPENDING
                    val label = if (isDemoGoal) "${goal.label} (Recommended Demo)" else "${goal.label} (Preview)"
                    val enabled = !state.isDemo || isDemoGoal
                    RadioRow(
                        label = label,
                        selected = state.selectedGoal == goal,
                        modifier = Modifier.testTag("goal_${goal.name}"),
                        enabled = enabled
                    ) { onGoal(goal) }
                }
                RadioRow(
                    label = "${stringResource(R.string.onboarding_goal_other)} (Preview)",
                    selected = state.otherSelected,
                    modifier = Modifier.testTag("goal_OTHER"),
                    enabled = !state.isDemo,
                    onClick = onOther
                )
                if (state.otherSelected) {
                    OutlinedTextField(
                        value = state.otherText,
                        onValueChange = onOtherText,
                        label = { Text(stringResource(R.string.onboarding_goal_other_hint)) },
                        enabled = !state.isDemo,
                        modifier = Modifier.fillMaxWidth().testTag("other_text")
                    )
                }
            }

            OnboardingStep.PAY_CYCLE -> {
                Text(stringResource(R.string.onboarding_pay_title), style = MaterialTheme.typography.headlineSmall)
                PayFrequency.entries.forEach { f ->
                    val enabled = !state.isDemo || f == PayFrequency.FORTNIGHTLY
                    RadioRow(
                        label = f.label,
                        selected = state.frequency == f,
                        modifier = Modifier.testTag("freq_${f.name}"),
                        enabled = enabled
                    ) { onFrequency(f) }
                }

                PayDatePicker(
                    nextPayDate = state.nextPayDate,
                    payDateValid = state.payDateValid,
                    onNextPayDate = onNextPayDate,
                    enabled = !state.isDemo
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PayDatePicker(
    nextPayDate: String,
    payDateValid: Boolean,
    onNextPayDate: (String) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    var showDatePicker by remember { mutableStateOf(false) }

    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = runCatching {
            LocalDate.parse(nextPayDate)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        }.getOrNull() ?: System.currentTimeMillis()
    )

    Box(modifier = modifier) {
        OutlinedTextField(
            value = nextPayDate,
            onValueChange = onNextPayDate,
            label = { Text(stringResource(R.string.onboarding_next_pay_date)) },
            isError = nextPayDate.isNotEmpty() && !payDateValid,
            enabled = enabled,
            readOnly = true,
            trailingIcon = {
                IconButton(onClick = { if (enabled) showDatePicker = true }, enabled = enabled) {
                    Icon(
                        painter = painterResource(R.drawable.ic_calendar),
                        contentDescription = "Select Date"
                    )
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("pay_date")
        )

        if (enabled) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable { showDatePicker = true }
            )
        }
    }

    if (showDatePicker && enabled) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            val selectedDate = Instant.ofEpochMilli(millis)
                                .atZone(ZoneId.of("UTC"))
                                .toLocalDate()
                                .toString()
                            onNextPayDate(selectedDate)
                        }
                        showDatePicker = false
                    }
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@Composable
private fun RadioRow(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(
            text = label,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            modifier = Modifier.padding(start = 12.dp)
        )
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
