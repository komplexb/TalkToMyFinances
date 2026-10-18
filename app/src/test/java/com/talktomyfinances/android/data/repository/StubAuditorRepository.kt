package com.talktomyfinances.android.data.repository

import com.talktomyfinances.android.domain.AuditReceipt
import com.talktomyfinances.android.domain.AuditorRepository
import com.talktomyfinances.android.domain.BreakdownItem
import com.talktomyfinances.android.domain.FinancialQuerySpec
import com.talktomyfinances.android.domain.MatchedTransaction
import com.talktomyfinances.android.domain.TargetMetric

class StubAuditorRepository : AuditorRepository {
    override suspend fun compute(spec: FinancialQuerySpec): Result<AuditReceipt> {
        val target = spec.targetMetric
        val merchant = spec.includedMerchants.firstOrNull() ?: "all merchants"
        val summary = when (target) {
            TargetMetric.PERIOD_AVERAGE -> "Average monthly spend across 3 active month(s) is \$84.50 (Total: \$253.50)."
            TargetMetric.TRANSACTION_COUNT -> "Found 6 matching transactions for $merchant."
            TargetMetric.TRANSACTION_LIST -> "Here are the 2 transactions totaling \$253.50 (ALL_TIME):\n• 2026-10-09: UBER *EATS — \$150.00\n• 2026-09-15: DOORDASH — \$103.50"
            else -> "Total spend calculated across matching transactions is \$253.50."
        }
        val value = if (target == TargetMetric.TRANSACTION_COUNT) 6.0 else 253.50
        val breakdown = if (spec.includedMerchants.size == 1) {
            listOf(
                BreakdownItem(
                    category = spec.includedMerchants.first(),
                    amount = value,
                    percentage = 100.0,
                    count = 2
                )
            )
        } else {
            listOf(
                BreakdownItem(category = "UBER *EATS", amount = 150.0, percentage = 59.2, count = 1),
                BreakdownItem(category = "DOORDASH", amount = 103.5, percentage = 40.8, count = 1)
            )
        }
        val transactions = listOf(
            MatchedTransaction(date = "2026-10-09", merchant = "UBER *EATS", category = "Dining", amount = 150.0),
            MatchedTransaction(date = "2026-09-15", merchant = "DOORDASH", category = "Dining", amount = 103.5)
        )
        return Result.success(
            AuditReceipt(
                summary = summary,
                value = value,
                breakdown = breakdown,
                transactions = transactions,
                spec = spec
            )
        )
    }
}

