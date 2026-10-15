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

class CloudRunAuditorRepository @Inject constructor(
    private val remoteConfig: FirebaseRemoteConfig
) : AuditorRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun compute(spec: FinancialQuerySpec): Result<AuditReceipt> =
        withContext(Dispatchers.IO) {
            try {
                val configuredUrl = remoteConfig.getString("auditor_base_url").ifBlank {
                    remoteConfig.getString("insights_base_url")
                }.trim()

                if (configuredUrl.isBlank()) {
                    return@withContext Result.failure(
                        IllegalStateException("auditor_base_url and insights_base_url are empty in Remote Config")
                    )
                }

                val urlsToTry = mutableListOf(configuredUrl)
                if (configuredUrl.contains("127.0.0.1") || configuredUrl.contains("localhost")) {
                    val emulatorUrl = configuredUrl.replace("127.0.0.1", "10.0.2.2").replace("localhost", "10.0.2.2")
                    if (!urlsToTry.contains(emulatorUrl)) {
                        urlsToTry.add(emulatorUrl)
                    }
                }

                var lastException: Exception? = null
                for (baseUrl in urlsToTry) {
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
                                return@withContext Result.success(receipt)
                            } else {
                                lastException = IllegalStateException("Auditor /compute returned HTTP $code")
                            }
                        } finally {
                            conn.disconnect()
                        }
                    } catch (e: Exception) {
                        lastException = e
                    }
                }
                Result.failure(lastException ?: IllegalStateException("Failed to connect to auditor endpoint"))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}
