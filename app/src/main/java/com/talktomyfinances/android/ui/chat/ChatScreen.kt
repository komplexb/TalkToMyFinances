package com.talktomyfinances.android.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.talktomyfinances.android.R
import com.talktomyfinances.android.ui.theme.TalkToMyFinancesTheme
import kotlinx.serialization.Serializable

@Serializable
data class ChatRoute(val prompt: String? = null)

/** Placeholder for the Tête-à-Tête Chat Screen (separate spec). Shows the primed prompt. */
@Composable
fun ChatScreen(prompt: String?) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text(stringResource(R.string.chat_title), style = MaterialTheme.typography.headlineSmall)
        if (prompt != null) Text(prompt, modifier = Modifier.padding(top = 16.dp))
    }
}

@Preview
@Composable
fun ChatScreenPreview() {
    TalkToMyFinancesTheme {
        ChatScreen(prompt = "How can I save money on my groceries?")
    }
}

