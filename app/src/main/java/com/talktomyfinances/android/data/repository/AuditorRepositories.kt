package com.talktomyfinances.android.data.repository

import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.talktomyfinances.android.domain.AuditReceipt
import com.talktomyfinances.android.domain.AuditorRepository
import com.talktomyfinances.android.domain.FinancialQuerySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

class StubAuditorRepository @Inject constructor() : AuditorRepository {
    override suspend fun compute(spec: FinancialQuerySpec): Result<AuditReceipt> {
        val target = spec.targetMetric
        val merchant = spec.includedMerchants.firstOrNull() ?: "all merchants"
        val summary = when (target) {
            "MONTHLY_AVERAGE" -> "Average monthly spend across 3 active month(s) is \$84.50 (Total: \$253.50)."
            "TRANSACTION_COUNT" -> "Found 6 matching transactions for $merchant."
            else -> "Total spend calculated across matching transactions is \$253.50."
        }
        val value = if (target == "TRANSACTION_COUNT") 6.0 else 253.50
        return Result.success(AuditReceipt(summary = summary, value = value, spec = spec))
    }
}

class CloudRunAuditorRepository(private val baseUrl: String) : AuditorRepository {
    private val json = Json { 
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun compute(spec: FinancialQuerySpec): Result<AuditReceipt> =
        withContext(Dispatchers.IO) {
            try {
                val conn = (URL("${baseUrl.trimEnd('/')}/compute").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 10_000
                    readTimeout = 30_000
                    setRequestProperty("Content-Type", "application/json")
                }
                try {
                    val body = json.encodeToString(FinancialQuerySpec.serializer(), spec)
                    conn.outputStream.use { it.write(body.toByteArray()) }
                    val code = conn.responseCode
                    if (code in 200..299) {
                        val responseText = conn.inputStream.bufferedReader().readText()
                        val receipt = json.decodeFromString(AuditReceipt.serializer(), responseText)
                        Result.success(receipt)
                    } else {
                        Result.failure(IllegalStateException("Auditor /compute returned HTTP $code"))
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}

class RoutingAuditorRepository @Inject constructor(
    private val remoteConfig: FirebaseRemoteConfig,
    private val stub: StubAuditorRepository
) : AuditorRepository {
    override suspend fun compute(spec: FinancialQuerySpec): Result<AuditReceipt> {
        val baseUrl = remoteConfig.getString("auditor_base_url").ifBlank {
            remoteConfig.getString("insights_base_url")
        }.trim()

        if (baseUrl.isNotBlank()) {
            val cloudResult = CloudRunAuditorRepository(baseUrl).compute(spec)
            if (cloudResult.isSuccess) {
                return cloudResult
            }
            // Return the actual cloud failure so errors are visible rather than silently stubbed
            return cloudResult
        }

        return stub.compute(spec)
    }
}
