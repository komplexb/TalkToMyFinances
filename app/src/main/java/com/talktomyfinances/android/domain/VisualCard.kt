package com.talktomyfinances.android.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class VisualCardItemData(
    val category: String,
    val amount: Double,
    val percentage: Double? = null
)

@Serializable
data class VisualCardPayload(
    @SerialName("card_type")
    val cardType: String = "category_breakdown",
    val title: String,
    @SerialName("total_amount")
    val totalAmount: Double? = null,
    val items: List<VisualCardItemData> = emptyList(),
    @SerialName("audit_receipt")
    val auditReceipt: AuditReceipt? = null,
    val timeframe: String? = null
)
