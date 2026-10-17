package com.talktomyfinances.android.domain

data class ChatResponseResult(
    val prose: String,
    val auditReceipt: AuditReceipt? = null,
    val visualCard: VisualCardPayload? = null
)
