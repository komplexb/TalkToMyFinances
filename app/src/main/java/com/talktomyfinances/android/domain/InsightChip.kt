package com.talktomyfinances.android.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The 6-tier recency spectrum (PRD 3.2). */
@Serializable
enum class RecencyWindow(val label: String) {
    @SerialName("this_week") THIS_WEEK("This Week"),
    @SerialName("this_pay_cycle") THIS_PAY_CYCLE("This Pay Cycle"),
    @SerialName("last_30_days") LAST_30_DAYS("Last 30 Days"),
    @SerialName("this_quarter") THIS_QUARTER("This Quarter"),
    @SerialName("six_months") SIX_MONTHS("6 Months"),
    @SerialName("ytd") YTD("Year / YTD")
}

/** A ready-to-display Chip curated by the backend (2 goal chips + 1 serendipity chip). */
@Serializable
data class InsightChip(
    val text: String,
    val recency: RecencyWindow,
    val isSerendipity: Boolean = false,
    @SerialName("drilldown_prompt") val drilldownPrompt: String? = null
)

@Serializable
data class InsightsResult(val chips: List<InsightChip>) {
    /** True when the backend honoured the "2+1" Fixed Slot Allocation. */
    val isValidTwoPlusOne: Boolean
        get() = chips.size == 3 && chips.count { it.isSerendipity } == 1

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

        fun parse(body: String): InsightsResult = json.decodeFromString(body)
    }
}

