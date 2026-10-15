package com.talktomyfinances.android.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class VisualCardType(val wireName: String, val displayLabel: String) {
    CATEGORY_BREAKDOWN("category_breakdown", "Category Breakdown"),
    SPENDING_TREND("spending_trend", "Spending Trend"),
    MONTHLY_AVERAGE("monthly_average", "Monthly Average");

    companion object {
        fun from(raw: String): VisualCardType {
            val normalized = raw.trim().lowercase().replace('-', '_').replace(' ', '_')
            return entries.find { it.wireName == normalized } ?: CATEGORY_BREAKDOWN
        }
    }
}

@Serializable
data class VisualCardItem(
    val category: String,
    val amount: Double,
    val percentage: Double? = null
)

typealias VisualCardItemData = VisualCardItem

@Serializable
data class VisualCard(
    @SerialName("card_type")
    val cardType: String = VisualCardType.CATEGORY_BREAKDOWN.wireName,
    val title: String = "Spending Breakdown",
    @SerialName("total_amount")
    val totalAmount: Double? = null,
    val items: List<VisualCardItem> = emptyList(),
    @SerialName("audit_receipt")
    val auditReceipt: AuditReceipt? = null,
    val timeframe: String? = null
) {
    val type: VisualCardType
        get() = VisualCardType.from(cardType)
}

typealias VisualCardPayload = VisualCard

