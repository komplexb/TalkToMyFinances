package com.talktomyfinances.android.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class TargetMetric {
    @SerialName("TOTAL_SUM") TOTAL_SUM,
    @SerialName("MONTHLY_AVERAGE") MONTHLY_AVERAGE,
    @SerialName("TRANSACTION_COUNT") TRANSACTION_COUNT,
    @SerialName("RUN_RATE") RUN_RATE
}

@Serializable
enum class TemporalGrain {
    @SerialName("WEEK") WEEK,
    @SerialName("PAY_CYCLE") PAY_CYCLE,
    @SerialName("MONTH") MONTH,
    @SerialName("QUARTER") QUARTER,
    @SerialName("YEAR") YEAR
}

@Serializable
enum class DenominatorStrategy {
    @SerialName("ACTIVE_PERIODS_ONLY") ACTIVE_PERIODS_ONLY,
    @SerialName("ALL_CALENDAR_PERIODS") ALL_CALENDAR_PERIODS
}

@Serializable
data class FinancialQuerySpec(
    @SerialName("target_metric")
    val targetMetric: TargetMetric = TargetMetric.TOTAL_SUM,

    @SerialName("included_merchants")
    val includedMerchants: List<String> = emptyList(),

    @SerialName("excluded_merchants")
    val excludedMerchants: List<String> = emptyList(),

    @SerialName("included_categories")
    val includedCategories: List<String> = emptyList(),

    @SerialName("excluded_categories")
    val excludedCategories: List<String> = emptyList(),

    @SerialName("temporal_grain")
    val temporalGrain: TemporalGrain = TemporalGrain.MONTH,

    @SerialName("denominator_strategy")
    val denominatorStrategy: DenominatorStrategy = DenominatorStrategy.ACTIVE_PERIODS_ONLY,

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
