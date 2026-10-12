package com.talktomyfinances.android.domain

import com.google.firebase.ai.type.FunctionCallPart
import com.talktomyfinances.android.data.repository.LiveVoiceRepositoryImpl
import com.talktomyfinances.android.data.repository.StubAuditorRepository
import com.talktomyfinances.android.ui.chat.ChatUiState
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
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
            targetMetric = "MONTHLY_AVERAGE",
            includedMerchants = listOf("Uber", "Lyft"),
            excludedMerchants = listOf("Uber Eats"),
            includedCategories = listOf("Transportation"),
            temporalGrain = "MONTH",
            denominatorStrategy = "ACTIVE_PERIODS_ONLY",
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
        assertEquals("TOTAL_SUM", receipt.spec.targetMetric)
        assertEquals(listOf("Uber"), receipt.spec.includedMerchants)
        assertTrue(receipt.summary.contains("${'$'}253.50"))
    }

    @Test
    fun stubAuditorRepositoryProvidesDeterministicMath() = runBlocking {
        val stub = StubAuditorRepository()

        // 1. Total Sum
        val sumSpec = FinancialQuerySpec(targetMetric = "TOTAL_SUM")
        val sumReceipt = stub.compute(sumSpec).getOrThrow()
        assertEquals(253.50, sumReceipt.value ?: 0.0, 0.001)
        assertTrue(sumReceipt.summary.contains("${'$'}253.50"))

        // 2. Monthly Average
        val avgSpec = FinancialQuerySpec(targetMetric = "MONTHLY_AVERAGE")
        val avgReceipt = stub.compute(avgSpec).getOrThrow()
        assertEquals(253.50, avgReceipt.value ?: 0.0, 0.001)
        assertTrue(avgReceipt.summary.contains("Average monthly spend"))

        // 3. Transaction Count
        val countSpec = FinancialQuerySpec(
            targetMetric = "TRANSACTION_COUNT",
            includedMerchants = listOf("Woolworths")
        )
        val countReceipt = stub.compute(countSpec).getOrThrow()
        assertEquals(6.0, countReceipt.value ?: 0.0, 0.001)
        assertTrue(countReceipt.summary.contains("Woolworths"))
    }

    @Test
    fun chatMessageInstantiatesCorrectly() {
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
            value = 253.50
        )
        val assistantMsg = ChatMessage(
            sender = MessageSender.ASSISTANT,
            text = "You spent ${'$'}253.50 on Uber.",
            auditReceipt = receipt
        )
        assertEquals(MessageSender.ASSISTANT, assistantMsg.sender)
        assertNotNull(assistantMsg.auditReceipt)
        assertEquals(253.50, assistantMsg.auditReceipt?.value ?: 0.0, 0.001)
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

        val voiceMode = initial.copy(
            inputMode = com.talktomyfinances.android.ui.chat.ChatInputMode.VOICE,
            voiceState = VoiceState(status = VoiceSessionStatus.LISTENING)
        )
        assertEquals(com.talktomyfinances.android.ui.chat.ChatInputMode.VOICE, voiceMode.inputMode)
        assertEquals(VoiceSessionStatus.LISTENING, voiceMode.voiceState.status)
    }

    @Test
    fun voiceStateTransitionsAndDefaults() {
        val defaultState = VoiceState()
        assertEquals(VoiceSessionStatus.DISCONNECTED, defaultState.status)
        assertEquals(false, defaultState.isMuted)
        assertEquals("", defaultState.activeTranscript)
        assertNull(defaultState.errorMessage)

        val connecting = defaultState.copy(status = VoiceSessionStatus.CONNECTING)
        assertEquals(VoiceSessionStatus.CONNECTING, connecting.status)

        val listening = defaultState.copy(
            status = VoiceSessionStatus.LISTENING,
            activeTranscript = "how much on coffee"
        )
        assertEquals(VoiceSessionStatus.LISTENING, listening.status)
        assertEquals("how much on coffee", listening.activeTranscript)

        val speaking = defaultState.copy(
            status = VoiceSessionStatus.SPEAKING,
            activeTranscript = "You spent $42 on coffee this week."
        )
        assertEquals(VoiceSessionStatus.SPEAKING, speaking.status)

        val muted = defaultState.copy(isMuted = true, status = VoiceSessionStatus.MUTED)
        assertTrue(muted.isMuted)
        assertEquals(VoiceSessionStatus.MUTED, muted.status)

        val error = defaultState.copy(
            status = VoiceSessionStatus.ERROR,
            errorMessage = "Microphone permission required"
        )
        assertEquals(VoiceSessionStatus.ERROR, error.status)
        assertEquals("Microphone permission required", error.errorMessage)
    }

    @Test
    fun transcriptEventsMapToChatSenders() {
        val userTranscript = TranscriptEvent(
            sender = MessageSender.USER,
            text = "Show my restaurant spending",
            isFinal = true
        )
        assertEquals(MessageSender.USER, userTranscript.sender)
        assertEquals("Show my restaurant spending", userTranscript.text)
        assertTrue(userTranscript.isFinal)

        val assistantTranscript = TranscriptEvent(
            sender = MessageSender.ASSISTANT,
            text = "You spent $120 on restaurants this month.",
            isFinal = true
        )
        assertEquals(MessageSender.ASSISTANT, assistantTranscript.sender)
        assertEquals("You spent $120 on restaurants this month.", assistantTranscript.text)
    }

    @Test
    fun visualCardPayloadSerializesAndDeserializesRoundTrip() {
        val payload = VisualCardPayload(
            cardType = "category_breakdown",
            title = "Food & Drink Breakdown",
            totalAmount = 450.00,
            items = listOf(
                VisualCardItemData(category = "Restaurants", amount = 250.00, percentage = 55.56),
                VisualCardItemData(category = "Cafes & Coffee", amount = 120.00, percentage = 26.67),
                VisualCardItemData(category = "Fast Food", amount = 80.00, percentage = 17.78)
            ),
            auditReceipt = AuditReceipt(
                summary = "Total Food & Drink expenditure across 3 categories.",
                value = 450.00,
                spec = FinancialQuerySpec(targetMetric = "TOTAL_SUM", includedCategories = listOf("Dining", "Food"))
            ),
            timeframe = "Last 30 Days"
        )

        val encoded = json.encodeToString(VisualCardPayload.serializer(), payload)
        assertTrue(encoded.contains("\"card_type\":\"category_breakdown\""))
        assertTrue(encoded.contains("\"total_amount\":450.0"))
        assertTrue(encoded.contains("\"Restaurants\""))
        assertTrue(encoded.contains("\"Cafes & Coffee\""))

        val decoded = json.decodeFromString(VisualCardPayload.serializer(), encoded)
        assertEquals(payload.cardType, decoded.cardType)
        assertEquals(payload.title, decoded.title)
        assertEquals(payload.totalAmount, decoded.totalAmount)
        assertEquals(3, decoded.items.size)
        assertEquals(250.00, decoded.items[0].amount, 0.001)
        assertEquals(55.56, decoded.items[0].percentage ?: 0.0, 0.001)
        assertEquals("Total Food & Drink expenditure across 3 categories.", decoded.auditReceipt?.summary)
        assertEquals("Last 30 Days", decoded.timeframe)
    }

    @Test
    fun chatMessageWithVisualCardPreservedInFirestoreFormat() {
        val card = VisualCardPayload(
            cardType = "monthly_average",
            title = "Average Grocery Spend",
            totalAmount = 380.00,
            items = listOf(
                VisualCardItemData(category = "Woolworths", amount = 220.00, percentage = 57.89),
                VisualCardItemData(category = "Coles", amount = 160.00, percentage = 42.11)
            ),
            auditReceipt = AuditReceipt(
                summary = "Average spend calculated across 4 pay cycles.",
                value = 380.00
            )
        )

        val originalMessage = ChatMessage(
            sender = MessageSender.ASSISTANT,
            text = "I've popped that breakdown onto your screen so you can check it out whenever you're ready.",
            visualCard = card,
            auditReceipt = card.auditReceipt
        )

        // Simulate Firestore serialization
        val firestoreMap = mutableMapOf<String, Any>(
            "id" to originalMessage.id,
            "sender" to originalMessage.sender.name,
            "text" to originalMessage.text,
            "timestamp" to originalMessage.timestamp,
            "visualCardPayloadJson" to json.encodeToString(VisualCardPayload.serializer(), card),
            "auditReceiptJson" to json.encodeToString(AuditReceipt.serializer(), card.auditReceipt!!)
        )

        // Simulate Firestore deserialization
        val restoredCard = json.decodeFromString<VisualCardPayload>(firestoreMap["visualCardPayloadJson"] as String)
        val restoredReceipt = json.decodeFromString<AuditReceipt>(firestoreMap["auditReceiptJson"] as String)
        val restoredMessage = ChatMessage(
            id = firestoreMap["id"] as String,
            sender = MessageSender.valueOf(firestoreMap["sender"] as String),
            text = firestoreMap["text"] as String,
            auditReceipt = restoredReceipt,
            visualCard = restoredCard,
            timestamp = firestoreMap["timestamp"] as Long
        )

        assertEquals(originalMessage.id, restoredMessage.id)
        assertEquals(originalMessage.sender, restoredMessage.sender)
        assertEquals(originalMessage.text, restoredMessage.text)
        assertNotNull(restoredMessage.visualCard)
        assertEquals("Average Grocery Spend", restoredMessage.visualCard?.title)
        assertEquals(2, restoredMessage.visualCard?.items?.size)
        assertEquals(220.00, restoredMessage.visualCard?.items?.get(0)?.amount ?: 0.0, 0.001)
        assertEquals("Average spend calculated across 4 pay cycles.", restoredMessage.auditReceipt?.summary)
    }

    @Test
    fun mockToolCallInvocationParsesVisualCardPayload() {
        val toolCallArgs = buildJsonObject {
            put("card_type", "category_breakdown")
            putJsonObject("payload") {
                put("title", "Takeaway Spend Breakdown")
                put("total_amount", 185.50)
                put("timeframe", "This Month")
                putJsonArray("items") {
                    add(buildJsonObject {
                        put("category", "Uber Eats")
                        put("amount", 125.00)
                        put("percentage", 67.38)
                    })
                    add(buildJsonObject {
                        put("category", "Menulog")
                        put("amount", 60.50)
                        put("percentage", 32.62)
                    })
                }
                putJsonObject("audit_receipt") {
                    put("summary", "Takeaway spend computed deterministically.")
                    put("value", 185.50)
                    put("timeframe", "This Month")
                }
            }
        }

        val functionCall = FunctionCallPart(
            name = "render_visual_card",
            args = toolCallArgs,
            id = "call_visual_123"
        )

        val parsedPayload = LiveVoiceRepositoryImpl.parseVisualCardCall(functionCall)

        assertEquals("category_breakdown", parsedPayload.cardType)
        assertEquals("Takeaway Spend Breakdown", parsedPayload.title)
        assertEquals(185.50, parsedPayload.totalAmount ?: 0.0, 0.001)
        assertEquals("This Month", parsedPayload.timeframe)
        assertEquals(2, parsedPayload.items.size)
        assertEquals("Uber Eats", parsedPayload.items[0].category)
        assertEquals(125.00, parsedPayload.items[0].amount, 0.001)
        assertEquals(67.38, parsedPayload.items[0].percentage ?: 0.0, 0.001)
        assertNotNull(parsedPayload.auditReceipt)
        assertEquals(185.50, parsedPayload.auditReceipt?.value ?: 0.0, 0.001)
        assertEquals("Takeaway spend computed deterministically.", parsedPayload.auditReceipt?.summary)
    }

    @Test
    fun selfContainedVisualCardRequiresZeroSecondaryNetworkFetches() {
        // Issue #15 & #33: The Visual Card payload must be fully self-contained.
        // It provides all visual title, amounts, items, percentages, and audit receipts directly.
        val payload = VisualCardPayload(
            cardType = "spending_trend",
            title = "Weekend vs Weekday Spend",
            totalAmount = 620.00,
            items = listOf(
                VisualCardItemData(category = "Weekend Outflow", amount = 420.00, percentage = 67.74),
                VisualCardItemData(category = "Weekday Outflow", amount = 200.00, percentage = 32.26)
            ),
            auditReceipt = AuditReceipt(
                summary = "Weekend vs weekday calculation over synthetic ledger.",
                value = 620.00
            ),
            timeframe = "Last 14 Days"
        )

        // Assert all data necessary to construct and display the visual card is present
        assertNotNull(payload.title)
        assertNotNull(payload.totalAmount)
        assertEquals(2, payload.items.size)
        assertTrue(payload.items.all { it.amount > 0 && it.percentage != null })
        assertNotNull(payload.auditReceipt?.summary)
        assertNotNull(payload.auditReceipt?.value)
        assertEquals("Last 14 Days", payload.timeframe)
    }
}
