package com.talktomyfinances.android.domain

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class MessageSender {
    USER,
    ASSISTANT
}

@Serializable
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val sender: MessageSender = MessageSender.USER,
    val text: String = "",
    val auditReceipt: AuditReceipt? = null,
    val visualCard: VisualCardPayload? = null,
    val timestamp: Long = System.currentTimeMillis()
)
