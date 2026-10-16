package com.talktomyfinances.android.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class TargetMetric {
    @SerialName("TOTAL_SUM") TOTAL_SUM,
    @SerialName("AVERAGE_TRANSACTION") AVERAGE_TRANSACTION,
    @SerialName("PERIOD_AVERAGE") PERIOD_AVERAGE,
    @SerialName("TRANSACTION_COUNT") TRANSACTION_COUNT,
    @SerialName("RUN_RATE") RUN_RATE,
    @SerialName("TRANSACTION_LIST") TRANSACTION_LIST
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
enum class DateWindowType {
    @SerialName("THIS_WEEK") THIS_WEEK,
    @SerialName("LAST_WEEK") LAST_WEEK,
    @SerialName("LAST_MONTH") LAST_MONTH,
    @SerialName("LAST_30_DAYS") LAST_30_DAYS,
    @SerialName("THIS_QUARTER") THIS_QUARTER,
    @SerialName("SIX_MONTHS") SIX_MONTHS,
    @SerialName("YTD") YTD,
    @SerialName("ALL_TIME") ALL_TIME,
    @SerialName("CUSTOM") CUSTOM
}

@Serializable
data class DateWindow(
    @SerialName("type")
    val type: DateWindowType = DateWindowType.ALL_TIME,

    @SerialName("start")
    val start: String? = null, // YYYY-MM-DD (CUSTOM only)

    @SerialName("end")
    val end: String? = null // YYYY-MM-DD (CUSTOM only)
)

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

    @SerialName("date_window")
    val dateWindow: DateWindow = DateWindow(),

    @SerialName("temporal_grain")
    val temporalGrain: TemporalGrain = TemporalGrain.MONTH,

    @SerialName("denominator_strategy")
    val denominatorStrategy: DenominatorStrategy = DenominatorStrategy.ACTIVE_PERIODS_ONLY,

    @SerialName("exclude_pending")
    val excludePending: Boolean = true,

    @SerialName("exclude_internal_transfers")
    val excludeInternalTransfers: Boolean = true,

    @SerialName("is_out_of_spec")
    val isOutOfSpec: Boolean = false,

    @SerialName("out_of_spec_reason")
    val outOfSpecReason: String? = null
)

@Serializable
data class BreakdownItem(
    val category: String,
    val amount: Double,
    val percentage: Double = 0.0,
    val count: Int = 0
)

@Serializable
data class MatchedTransaction(
    val date: String,
    val merchant: String,
    val category: String,
    val amount: Double
)

@Serializable
data class AuditReceipt(
    val summary: String,
    val value: Double? = null,
    val breakdown: List<BreakdownItem> = emptyList(),
    val transactions: List<MatchedTransaction> = emptyList(),
    val spec: FinancialQuerySpec = FinancialQuerySpec()
)

