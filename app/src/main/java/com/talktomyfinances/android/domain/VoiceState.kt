package com.talktomyfinances.android.domain

enum class VoiceSessionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    LISTENING,
    SPEAKING,
    MUTED,
    ERROR
}

data class VoiceState(
    val status: VoiceSessionStatus = VoiceSessionStatus.DISCONNECTED,
    val isMuted: Boolean = false,
    val activeTranscript: String = "",
    val errorMessage: String? = null
)

data class TranscriptEvent(
    val sender: MessageSender,
    val text: String,
    val isFinal: Boolean = true
)
