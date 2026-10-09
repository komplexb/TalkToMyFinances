package com.talktomyfinances.android.ui.shared

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.talktomyfinances.android.ui.theme.LightTeal
import com.talktomyfinances.android.ui.theme.Teal

@Composable
fun LoadingIndicator() {
    Box(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        CircularProgressIndicator(
            modifier = Modifier
                .width(64.dp)
                .align(Alignment.Center),
            color = Teal,
            trackColor = LightTeal,
        )
    }
}