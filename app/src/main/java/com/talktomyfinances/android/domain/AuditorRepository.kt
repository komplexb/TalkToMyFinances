package com.talktomyfinances.android.domain

interface AuditorRepository {
    suspend fun compute(spec: FinancialQuerySpec): Result<AuditReceipt>
}
