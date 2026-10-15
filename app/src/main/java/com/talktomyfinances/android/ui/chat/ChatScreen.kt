package com.talktomyfinances.android.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.talktomyfinances.android.R
import com.talktomyfinances.android.domain.AuditReceipt
import com.talktomyfinances.android.domain.ChatMessage
import com.talktomyfinances.android.domain.MessageSender
import com.talktomyfinances.android.ui.chat.components.VisualCardView
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.talktomyfinances.android.domain.VoiceSessionStatus
import com.talktomyfinances.android.domain.VoiceState
import com.talktomyfinances.android.ui.chat.components.VoiceWaveformBar
import kotlinx.serialization.Serializable

@Serializable
data class ChatRoute(val prompt: String? = null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    prompt: String?,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.switchToVoice()
        }
    }

    LaunchedEffect(prompt) {
        viewModel.handlePrimedPrompt(prompt)
    }

    LaunchedEffect(uiState.messages.size, uiState.isLoading) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.chat_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            ChatBottomInputBar(
                inputText = uiState.inputText,
                isLoading = uiState.isLoading,
                inputMode = uiState.inputMode,
                voiceState = uiState.voiceState,
                onTextChanged = viewModel::onInputTextChanged,
                onSendMessage = { viewModel.sendMessage(uiState.inputText) },
                onSwitchToVoice = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        viewModel.switchToVoice()
                    } else {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onSwitchToText = viewModel::switchToText,
                onToggleMute = viewModel::toggleMute
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                items(uiState.messages, key = { it.id }) { message ->
                    ChatMessageItem(message = message)
                }

                if (uiState.inputMode == ChatInputMode.VOICE && uiState.voiceState.activeTranscript.isNotBlank()) {
                    item(key = "active_voice_transcript") {
                        val isUserSpeaking = uiState.voiceState.status == VoiceSessionStatus.LISTENING
                        ChatMessageItem(
                            message = ChatMessage(
                                id = "in_flight_transcript",
                                sender = if (isUserSpeaking) MessageSender.USER else MessageSender.ASSISTANT,
                                text = uiState.voiceState.activeTranscript
                            )
                        )
                    }
                }

                if (uiState.isLoading) {
                    item {
                        ThinkingIndicator()
                    }
                }
            }
        }
    }
}

@Composable
fun ChatMessageItem(message: ChatMessage) {
    val isUser = message.sender == MessageSender.USER

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Box(
            modifier = Modifier
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isUser) 16.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 16.dp
                    )
                )
                .background(
                    if (isUser) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isUser) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        message.visualCard?.let { visualCard ->
            val cardWithReceipt = if (visualCard.auditReceipt == null && message.auditReceipt != null) {
                visualCard.copy(auditReceipt = message.auditReceipt)
            } else {
                visualCard
            }
            Spacer(modifier = Modifier.height(8.dp))
            VisualCardView(visualCard = cardWithReceipt)
        } ?: message.auditReceipt?.let { receipt ->
            Spacer(modifier = Modifier.height(6.dp))
            AuditReceiptCard(receipt = receipt)
        }
    }
}

@Composable
fun AuditReceiptCard(receipt: AuditReceipt) {
    var isExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .padding(top = 4.dp)
            .animateContentSize()
            .clickable { isExpanded = !isExpanded },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_check),
                            contentDescription = "Verified",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                    Text(
                        text = "Verified Fact",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }

                Text(
                    text = if (isExpanded) "Hide details" else "Tap for receipt",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = receipt.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )

            receipt.value?.let { value ->
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
                ) {
                    Text(
                        text = "Verified Metric: $${String.format("%.2f", value)}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            if (isExpanded) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(8.dp))

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Audit Specification:",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = "• Target Metric: ${receipt.spec.targetMetric}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = "• Temporal Grain: ${receipt.spec.temporalGrain}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = "• Denominator Strategy: ${receipt.spec.denominatorStrategy}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    if (receipt.spec.includedMerchants.isNotEmpty()) {
                        Text(
                            text = "• Included Merchants: ${receipt.spec.includedMerchants.joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    if (receipt.spec.includedCategories.isNotEmpty()) {
                        Text(
                            text = "• Included Categories: ${receipt.spec.includedCategories.joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ThinkingIndicator() {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "Checking ledger & calculating verified facts...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun ChatBottomInputBar(
    inputText: String,
    isLoading: Boolean,
    inputMode: ChatInputMode,
    voiceState: VoiceState,
    onTextChanged: (String) -> Unit,
    onSendMessage: () -> Unit,
    onSwitchToVoice: () -> Unit,
    onSwitchToText: () -> Unit,
    onToggleMute: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding(),
        tonalElevation = 4.dp
    ) {
        AnimatedContent(
            targetState = inputMode,
            transitionSpec = {
                fadeIn() togetherWith fadeOut()
            },
            label = "bottom_bar_morph"
        ) { mode ->
            when (mode) {
                ChatInputMode.TEXT -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = onTextChanged,
                            modifier = Modifier.weight(1f),
                            placeholder = {
                                Text(
                                    text = "Ask about your spending...",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            },
                            shape = RoundedCornerShape(24.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                            ),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions.Default.copy(
                                imeAction = ImeAction.Send
                            ),
                            keyboardActions = KeyboardActions(
                                onSend = { if (inputText.isNotBlank() && !isLoading) onSendMessage() }
                            )
                        )

                        // Voice Mode Toggle Button
                        IconButton(
                            onClick = onSwitchToVoice,
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_mic),
                                contentDescription = "Voice Mode",
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        // Send Button (only when text is entered)
                        if (inputText.isNotBlank()) {
                            IconButton(
                                onClick = onSendMessage,
                                enabled = !isLoading,
                                colors = IconButtonDefaults.iconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                modifier = Modifier.size(44.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_check),
                                    contentDescription = "Send",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                ChatInputMode.VOICE -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Switch back to text button
                        IconButton(
                            onClick = onSwitchToText,
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_keyboard),
                                contentDescription = "Switch to Text",
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        // Waveform and status indicator
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(24.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    val statusText = when (voiceState.status) {
                                        VoiceSessionStatus.CONNECTING -> "Connecting to Gemini Live..."
                                        VoiceSessionStatus.CONNECTED -> "Ready"
                                        VoiceSessionStatus.LISTENING -> if (voiceState.isMuted) "Microphone Muted" else "Listening..."
                                        VoiceSessionStatus.SPEAKING -> "Gemini is speaking..."
                                        VoiceSessionStatus.MUTED -> "Muted"
                                        VoiceSessionStatus.ERROR -> voiceState.errorMessage ?: "Voice Error"
                                        VoiceSessionStatus.DISCONNECTED -> "Disconnected"
                                    }
                                    Text(
                                        text = statusText,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (voiceState.status == VoiceSessionStatus.ERROR) MaterialTheme.colorScheme.error
                                                else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (voiceState.activeTranscript.isNotBlank()) {
                                        Text(
                                            text = voiceState.activeTranscript,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                            maxLines = 1
                                        )
                                    }
                                }

                                VoiceWaveformBar(
                                    status = voiceState.status,
                                    isMuted = voiceState.isMuted
                                )
                            }
                        }

                        // Mute / Unmute Button
                        IconButton(
                            onClick = onToggleMute,
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = if (voiceState.isMuted) MaterialTheme.colorScheme.errorContainer
                                                else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (voiceState.isMuted) MaterialTheme.colorScheme.onErrorContainer
                                               else MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(
                                painter = painterResource(
                                    id = if (voiceState.isMuted) R.drawable.ic_mic_off else R.drawable.ic_mic
                                ),
                                contentDescription = if (voiceState.isMuted) "Unmute" else "Mute",
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
