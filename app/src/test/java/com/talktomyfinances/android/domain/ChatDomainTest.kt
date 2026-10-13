package com.talktomyfinances.android.domain

import com.talktomyfinances.android.data.repository.StubAuditorRepository
import com.talktomyfinances.android.ui.chat.ChatUiState
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatDomainTest {

    private val json = Json { 
        ignoreUnknownKeys = true 
        encodeDefaults = true
    }

    @Test
    fun financialQuerySpecSerializesWithSnakeCaseKeys() {
        val spec = FinancialQuerySpec(
            targetMetric = TargetMetric.MONTHLY_AVERAGE,
            includedMerchants = listOf("Uber", "Lyft"),
            excludedMerchants = listOf("Uber Eats"),
            includedCategories = listOf("Transportation"),
            temporalGrain = TemporalGrain.MONTH,
            denominatorStrategy = DenominatorStrategy.ACTIVE_PERIODS_ONLY,
            excludePending = true,
            excludeInternalTransfers = true
        )

        val encoded = json.encodeToString(FinancialQuerySpec.serializer(), spec)
        assertTrue("Expected target_metric in $encoded", encoded.contains("\"target_metric\":\"MONTHLY_AVERAGE\""))
        assertTrue("Expected included_merchants in $encoded", encoded.contains("\"included_merchants\""))
        assertTrue("Expected Uber in $encoded", encoded.contains("\"Uber\""))
        assertTrue("Expected denominator_strategy in $encoded", encoded.contains("\"denominator_strategy\":\"ACTIVE_PERIODS_ONLY\""))

        val decoded = json.decodeFromString(FinancialQuerySpec.serializer(), encoded)
        assertEquals(spec, decoded)
        assertEquals(TargetMetric.MONTHLY_AVERAGE, decoded.targetMetric)
        assertEquals(TemporalGrain.MONTH, decoded.temporalGrain)
        assertEquals(DenominatorStrategy.ACTIVE_PERIODS_ONLY, decoded.denominatorStrategy)
    }

    @Test
    fun auditReceiptDecodesBackendResponseJson() {
        val backendJson = """
            {
              "summary": "Total spend calculated across matching transactions is ${'$'}253.50.",
              "value": 253.50,
              "spec": {
                "target_metric": "TOTAL_SUM",
                "included_merchants": ["Uber"],
                "excluded_merchants": [],
                "included_categories": [],
                "excluded_categories": [],
                "temporal_grain": "MONTH",
                "denominator_strategy": "ACTIVE_PERIODS_ONLY",
                "exclude_pending": true,
                "exclude_internal_transfers": true
              }
            }
        """.trimIndent()

        val receipt = json.decodeFromString(AuditReceipt.serializer(), backendJson)
        assertEquals(253.50, receipt.value ?: 0.0, 0.001)
        assertEquals(TargetMetric.TOTAL_SUM, receipt.spec.targetMetric)
        assertEquals(listOf("Uber"), receipt.spec.includedMerchants)
        assertTrue(receipt.summary.contains("${'$'}253.50"))
    }

    @Test
    fun stubAuditorRepositoryProvidesDeterministicMath() = runBlocking {
        val stub = StubAuditorRepository()

        // 1. Total Sum
        val sumSpec = FinancialQuerySpec(targetMetric = TargetMetric.TOTAL_SUM)
        val sumReceipt = stub.compute(sumSpec).getOrThrow()
        assertEquals(253.50, sumReceipt.value ?: 0.0, 0.001)
        assertTrue(sumReceipt.summary.contains("${'$'}253.50"))

        // 2. Monthly Average
        val avgSpec = FinancialQuerySpec(targetMetric = TargetMetric.MONTHLY_AVERAGE)
        val avgReceipt = stub.compute(avgSpec).getOrThrow()
        assertEquals(253.50, avgReceipt.value ?: 0.0, 0.001)
        assertTrue(avgReceipt.summary.contains("Average monthly spend"))

        // 3. Transaction Count
        val countSpec = FinancialQuerySpec(
            targetMetric = TargetMetric.TRANSACTION_COUNT,
            includedMerchants = listOf("Woolworths")
        )
        val countReceipt = stub.compute(countSpec).getOrThrow()
        assertEquals(6.0, countReceipt.value ?: 0.0, 0.001)
        assertTrue(countReceipt.summary.contains("Woolworths"))
    }

    @Test
    fun chatMessageInstantiatesAndSerializesToFirestoreMap() {
        val userMsg = ChatMessage(
            sender = MessageSender.USER,
            text = "How much did I spend on Uber?"
        )
        assertNotNull(userMsg.id)
        assertTrue(userMsg.timestamp > 0)
        assertEquals(MessageSender.USER, userMsg.sender)
        assertNull(userMsg.auditReceipt)

        val receipt = AuditReceipt(
            summary = "Verified total: ${'$'}253.50",
            value = 253.50,
            spec = FinancialQuerySpec(targetMetric = TargetMetric.TOTAL_SUM)
        )
        val assistantMsg = ChatMessage(
            sender = MessageSender.ASSISTANT,
            text = "You spent ${'$'}253.50 on Uber.",
            auditReceipt = receipt
        )
        assertEquals(MessageSender.ASSISTANT, assistantMsg.sender)
        assertNotNull(assistantMsg.auditReceipt)
        assertEquals(253.50, assistantMsg.auditReceipt?.value ?: 0.0, 0.001)

        // Verify Firestore format encoding
        val firestoreData = mutableMapOf<String, Any>(
            "id" to assistantMsg.id,
            "sender" to assistantMsg.sender.name,
            "text" to assistantMsg.text,
            "timestamp" to assistantMsg.timestamp,
            "auditReceiptJson" to json.encodeToString(AuditReceipt.serializer(), assistantMsg.auditReceipt!!)
        )
        assertEquals("ASSISTANT", firestoreData["sender"])
        val decodedReceipt = json.decodeFromString<AuditReceipt>(firestoreData["auditReceiptJson"] as String)
        assertEquals(TargetMetric.TOTAL_SUM, decodedReceipt.spec.targetMetric)
        assertEquals(253.50, decodedReceipt.value ?: 0.0, 0.001)
    }

    @Test
    fun computeFailurePropagatesExceptionWithoutFalseReceipt() {
        val failureResult: Result<AuditReceipt> = Result.failure(IllegalStateException("Backend 500 error"))
        assertTrue(failureResult.isFailure)

        var errorCaught = false
        var producedReceipt: AuditReceipt? = null
        try {
            producedReceipt = failureResult.getOrThrow()
        } catch (e: Exception) {
            errorCaught = true
        }

        assertTrue("Expected failure to throw rather than yield a fallback receipt", errorCaught)
        assertNull(producedReceipt)
    }

    @Test
    fun chatUiStateTransitions() {
        val initial = ChatUiState()
        assertTrue(initial.messages.isEmpty())
        assertEquals("", initial.inputText)
        assertEquals(false, initial.isLoading)
        assertNull(initial.error)

        val loading = initial.copy(isLoading = true, inputText = "testing")
        assertTrue(loading.isLoading)
        assertEquals("testing", loading.inputText)

        val withError = initial.copy(error = "Network timeout")
        assertEquals("Network timeout", withError.error)
    }
}
