package com.talktomyfinances.android.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FinancialQuerySpec(
    @SerialName("target_metric")
    val targetMetric: String = "TOTAL_SUM",

    @SerialName("included_merchants")
    val includedMerchants: List<String> = emptyList(),

    @SerialName("excluded_merchants")
    val excludedMerchants: List<String> = emptyList(),

    @SerialName("included_categories")
    val includedCategories: List<String> = emptyList(),

    @SerialName("excluded_categories")
    val excludedCategories: List<String> = emptyList(),

    @SerialName("temporal_grain")
    val temporalGrain: String = "MONTH",

    @SerialName("denominator_strategy")
    val denominatorStrategy: String = "ACTIVE_PERIODS_ONLY",

    @SerialName("exclude_pending")
    val excludePending: Boolean = true,

    @SerialName("exclude_internal_transfers")
    val excludeInternalTransfers: Boolean = true
)

@Serializable
data class AuditReceipt(
    val summary: String,
    val value: Double? = null,
    val spec: FinancialQuerySpec = FinancialQuerySpec()
)
