package com.talktomyfinances.android.domain

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface LiveVoiceManager {
    val voiceState: StateFlow<VoiceState>
    val transcriptEvents: SharedFlow<TranscriptEvent>
    val visualCardEvents: SharedFlow<VisualCard>

    suspend fun startSession(): Result<Unit>
    suspend fun startSession(
        recentMessages: List<ChatMessage>,
        previousReceipt: AuditReceipt?,
        userProfile: OnboardingProfile?
    ): Result<Unit> = startSession()
    suspend fun stopSession()
    fun setMuted(muted: Boolean)
}
