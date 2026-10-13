package com.talktomyfinances.android.data.repository

import com.talktomyfinances.android.domain.AuditReceipt
import com.talktomyfinances.android.domain.AuditorRepository
import com.talktomyfinances.android.domain.FinancialQuerySpec
import com.talktomyfinances.android.domain.TargetMetric

class StubAuditorRepository : AuditorRepository {
    override suspend fun compute(spec: FinancialQuerySpec): Result<AuditReceipt> {
        val target = spec.targetMetric
        val merchant = spec.includedMerchants.firstOrNull() ?: "all merchants"
        val summary = when (target) {
            TargetMetric.MONTHLY_AVERAGE -> "Average monthly spend across 3 active month(s) is \$84.50 (Total: \$253.50)."
            TargetMetric.TRANSACTION_COUNT -> "Found 6 matching transactions for $merchant."
            else -> "Total spend calculated across matching transactions is \$253.50."
        }
        val value = if (target == TargetMetric.TRANSACTION_COUNT) 6.0 else 253.50
        return Result.success(AuditReceipt(summary = summary, value = value, spec = spec))
    }
}
