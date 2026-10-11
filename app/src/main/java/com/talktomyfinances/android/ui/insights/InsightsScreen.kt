package com.talktomyfinances.android.ui.insights

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.talktomyfinances.android.R
import com.talktomyfinances.android.domain.InsightChip
import com.talktomyfinances.android.domain.RecencyWindow
import com.talktomyfinances.android.ui.theme.TalkToMyFinancesTheme
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

@Serializable
object InsightsRoute

@Composable
fun InsightsScreen(
    viewModel: InsightsViewModel = hiltViewModel(),
    openOnboarding: () -> Unit,
    openChat: (String?) -> Unit
) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    LaunchedEffect(state) {
        if (state is InsightsUiState.NeedsOnboarding) openOnboarding()
    }
    InsightsContent(state, onRetry = viewModel::load, openChat = openChat)
}

@Composable
fun InsightsContent(
    state: InsightsUiState,
    onRetry: () -> Unit = {},
    openChat: (String?) -> Unit = {}
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        when (state) {
            InsightsUiState.Loading, InsightsUiState.NeedsOnboarding -> LoadingSequence(openChat)
            InsightsUiState.Error -> {
                Text(stringResource(R.string.error_message))
                Button(onClick = onRetry, modifier = Modifier.testTag("retry")) {
                    Text(stringResource(R.string.store_finder_retry))
                }
            }
            is InsightsUiState.Loaded -> state.chips.forEach { ChipCard(it) { openChat(it.text) } }
        }
    }
}

@Composable
private fun LoadingSequence(openChat: (String?) -> Unit) {
    val messages = stringArrayResource(R.array.insights_loading_messages)
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            index = (index + 1) % messages.size
        }
    }
    Column(
        modifier = Modifier.fillMaxWidth().testTag("insights_loading"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        CircularProgressIndicator()
        Crossfade(targetState = index, label = "loading") { Text(messages[it]) }
        Button(onClick = { openChat(null) }, modifier = Modifier.testTag("skip_to_chat")) {
            Text(stringResource(R.string.insights_await_or_chat))
        }
    }
}

@Composable
private fun ChipCard(chip: InsightChip, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("chip")) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(chip.recency.label, style = MaterialTheme.typography.titleSmall)
            Text(chip.text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Preview
@Composable
fun InsightsScreenPreview() {
    TalkToMyFinancesTheme {
        InsightsContent(
            state = InsightsUiState.Loaded(
                chips = listOf(
                    InsightChip(
                        text = "Nice work on your cafe spending this week. You have room for a cheeky brunch!",
                        recency = RecencyWindow.THIS_WEEK
                    ),
                    InsightChip(
                        text = "You have $35 ready if you want Friday night pizza.",
                        recency = RecencyWindow.THIS_PAY_CYCLE
                    ),
                    InsightChip(
                        text = "Your utility bills came in $140 lower this quarter. Want to see why?",
                        recency = RecencyWindow.THIS_QUARTER,
                        isSerendipity = true
                    )
                )
            )
        )
    }
}


