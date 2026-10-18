package com.talktomyfinances.android.ui.chat

import com.talktomyfinances.android.data.datasource.AIChatDataSource
import com.talktomyfinances.android.data.repository.AuthRepository
import com.talktomyfinances.android.data.repository.StubAuditorRepository
import com.talktomyfinances.android.domain.AuditReceipt
import com.talktomyfinances.android.domain.ChatMessage
import com.talktomyfinances.android.domain.ChatRepository
import com.talktomyfinances.android.domain.CurrencySanitizer
import com.talktomyfinances.android.domain.DateWindow
import com.talktomyfinances.android.domain.DateWindowType
import com.talktomyfinances.android.domain.FinancialGoal
import com.talktomyfinances.android.domain.FinancialQuerySpec
import com.talktomyfinances.android.domain.InsightChip
import com.talktomyfinances.android.domain.InsightsResult
import com.talktomyfinances.android.domain.LiveVoiceManager
import com.talktomyfinances.android.domain.MessageSender
import com.talktomyfinances.android.domain.OnboardingProfile
import com.talktomyfinances.android.domain.PayCycle
import com.talktomyfinances.android.domain.PayFrequency
import com.talktomyfinances.android.domain.ProfileStore
import com.talktomyfinances.android.domain.RecencyWindow
import com.talktomyfinances.android.domain.TargetMetric
import com.talktomyfinances.android.domain.VisualCardPayload
import com.talktomyfinances.android.domain.VoiceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TwoTrackChatPipelineTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val json = Json { ignoreUnknownKeys = true }

    // Fake Auth
    private class FakeAuthRepository(var userId: String? = "user_123") : AuthRepository {
        override val currentUser: com.google.firebase.auth.FirebaseUser? get() = null
        override val currentUserId: String? get() = userId
        override suspend fun createAnonymousAccount(): com.talktomyfinances.android.data.model.User {
            userId = "user_123"
            return com.talktomyfinances.android.data.model.User(authId = "user_123")
        }
    }

    // Fake Chat Repository
    private class FakeChatRepository : ChatRepository {
        val messages = mutableListOf<ChatMessage>()
        private val flow = MutableStateFlow<List<ChatMessage>>(emptyList())

        override fun getMessages(userId: String): Flow<List<ChatMessage>> = flow

        override suspend fun saveMessage(userId: String, message: ChatMessage) {
            messages.add(message)
            flow.value = messages.toList()
        }

        override suspend fun clearMessages(userId: String) {
            messages.clear()
            flow.value = emptyList()
        }
    }

    // Fake Live Voice Manager
    private class FakeLiveVoiceManager : LiveVoiceManager {
        private val _voiceState = MutableStateFlow(VoiceState())
        override val voiceState = _voiceState.asStateFlow()

        private val _transcriptEvents = MutableSharedFlow<com.talktomyfinances.android.domain.TranscriptEvent>()
        override val transcriptEvents = _transcriptEvents.asSharedFlow()

        private val _cardEvents = MutableSharedFlow<VisualCardPayload>(extraBufferCapacity = 1)
        override val visualCardEvents = _cardEvents.asSharedFlow()

        override suspend fun startSession(): Result<Unit> = Result.success(Unit)
        override suspend fun stopSession() {}
        override fun setMuted(muted: Boolean) {}
    }

    // Fake Profile Store
    private class FakeProfileStore(var profile: OnboardingProfile? = OnboardingProfile(
        goal = FinancialGoal.MINDFUL_SPENDING,
        payCycle = PayCycle(PayFrequency.FORTNIGHTLY, "2026-10-20")
    )) : ProfileStore {
        override suspend fun save(profile: OnboardingProfile) { this.profile = profile }
        override suspend fun load(): OnboardingProfile? = profile
    }

    // Testable AI Remote Data Source Fake
    private class FakeAIRemoteDataSource(
        var auditorRepo: com.talktomyfinances.android.domain.AuditorRepository? = null,
        var specToReturn: FinancialQuerySpec = FinancialQuerySpec(
            targetMetric = TargetMetric.TOTAL_SUM,
            includedCategories = listOf("Dining")
        ),
        var judgeProseToReturn: String = "You spent $253.50 on dining.",
        var guidanceTextToReturn: String = "Try cooking in batches this week to trim your takeout spending without feeling restricted."
    ) : AIChatDataSource {
        var lastPassedPrompt: String? = null
        var lastPassedReceipt: AuditReceipt? = null
        var lastPassedMessages: List<ChatMessage> = emptyList()
        var lastPassedGoal: FinancialGoal? = null
        var lastPassedProfile: OnboardingProfile? = null

        override suspend fun sendMessage(
            prompt: String,
            recentMessages: List<ChatMessage>,
            previousReceipt: AuditReceipt?,
            userProfile: OnboardingProfile?,
            onPartialResponse: ((com.talktomyfinances.android.domain.ChatResponseResult) -> Unit)?
        ): com.talktomyfinances.android.domain.ChatResponseResult {
            lastPassedPrompt = prompt
            lastPassedReceipt = previousReceipt
            lastPassedMessages = recentMessages
            lastPassedProfile = userProfile
            lastPassedGoal = userProfile?.goal

            if (specToReturn.isOutOfSpec) {
                return com.talktomyfinances.android.domain.ChatResponseResult(prose = guidanceTextToReturn)
            }

            var receipt: AuditReceipt? = null
            if (auditorRepo != null) {
                receipt = auditorRepo?.compute(specToReturn)?.getOrNull()
            }

            var visualCard: com.talktomyfinances.android.domain.VisualCardPayload? = null
            if (receipt != null && receipt.value != null && specToReturn.targetMetric != TargetMetric.TRANSACTION_LIST && receipt.breakdown.size > 1) {
                val distinctTxCategories = receipt.transactions.map { it.category }.filter { it.isNotBlank() }.distinct()
                val title = when {
                    receipt.spec.includedCategories.isNotEmpty() -> receipt.spec.includedCategories.joinToString()
                    distinctTxCategories.isNotEmpty() -> distinctTxCategories.joinToString()
                    receipt.spec.includedMerchants.size == 1 -> receipt.spec.includedMerchants.first()
                    else -> receipt.spec.includedMerchants.firstOrNull() ?: "Spending"
                }
                visualCard = com.talktomyfinances.android.domain.VisualCardPayload(
                    cardType = com.talktomyfinances.android.domain.VisualCardType.CATEGORY_BREAKDOWN.wireName,
                    title = title,
                    totalAmount = receipt.value,
                    items = receipt.breakdown.map { com.talktomyfinances.android.domain.VisualCardItem(it.category, it.amount, it.percentage) },
                    auditReceipt = receipt,
                    timeframe = receipt.spec.dateWindow.type.name
                )
            }

            val prose = if (specToReturn.targetMetric == TargetMetric.TRANSACTION_LIST) receipt?.summary ?: "" else judgeProseToReturn

            return com.talktomyfinances.android.domain.ChatResponseResult(
                prose = prose,
                auditReceipt = receipt,
                visualCard = visualCard
            )
        }
    }

    @Test
    fun insightChipSerializesAndParsesDrilldownPrompt() {
        val chipJson = """
            {
                "text": "Nice work on your cafe spending this week.",
                "recency": "this_week",
                "isSerendipity": false,
                "drilldown_prompt": "How much did I spend on cafes this week?"
            }
        """.trimIndent()

        val chip = json.decodeFromString<InsightChip>(chipJson)
        assertEquals("Nice work on your cafe spending this week.", chip.text)
        assertEquals(RecencyWindow.THIS_WEEK, chip.recency)
        assertFalse(chip.isSerendipity)
        assertEquals("How much did I spend on cafes this week?", chip.drilldownPrompt)
    }

    @Test
    fun insightChipClickChoosesDrilldownPromptWhenAvailable() {
        val chipWithDrilldown = InsightChip(
            text = "Your utility bills came in $140 lower this quarter. Want to see why?",
            recency = RecencyWindow.THIS_QUARTER,
            isSerendipity = true,
            drilldownPrompt = "Show my utility bills this quarter"
        )
        val targetPrompt = chipWithDrilldown.drilldownPrompt ?: chipWithDrilldown.text
        assertEquals("Show my utility bills this quarter", targetPrompt)

        val chipWithoutDrilldown = InsightChip(
            text = "Total savings buffer is positive.",
            recency = RecencyWindow.YTD
        )
        val fallbackPrompt = chipWithoutDrilldown.drilldownPrompt ?: chipWithoutDrilldown.text
        assertEquals("Total savings buffer is positive.", fallbackPrompt)
    }

    @Test
    fun stubInsightsRepository_providesThreeGoldenChipsWithDrilldowns() = runBlocking {
        val repo = com.talktomyfinances.android.data.repository.StubInsightsRepository()
        val result = repo.loadInsights(
            OnboardingProfile(
                goal = FinancialGoal.MINDFUL_SPENDING,
                payCycle = PayCycle(PayFrequency.FORTNIGHTLY, "2026-10-13")
            )
        )
        assertEquals(3, result.chips.size)
        val (chip1, chip2, chip3) = result.chips

        assertEquals(RecencyWindow.THIS_WEEK, chip1.recency)
        assertEquals("How much did I spend on cafes this week?", chip1.drilldownPrompt)
        assertTrue(chip1.text.contains("cafes"))

        assertEquals(RecencyWindow.THIS_PAY_CYCLE, chip2.recency)
        assertEquals("How much did I spend on takeaway this pay cycle?", chip2.drilldownPrompt)
        assertTrue(chip2.text.contains("takeaway"))

        assertEquals(RecencyWindow.THIS_QUARTER, chip3.recency)
        assertTrue(chip3.isSerendipity)
        assertEquals("How much did I spend on utility bills this quarter?", chip3.drilldownPrompt)
        assertTrue(chip3.text.contains("utility"))
    }

    @Test
    fun trackA_executesDeterministicMathAndAttachesAuditReceipt() = runBlocking {
        val chatRepo = FakeChatRepository()
        val authRepo = FakeAuthRepository()
        val auditorRepo = StubAuditorRepository()
        val voiceManager = FakeLiveVoiceManager()
        val profileStore = FakeProfileStore()
        val fakeAI = FakeAIRemoteDataSource(
            auditorRepo = auditorRepo,
            specToReturn = FinancialQuerySpec(
                targetMetric = TargetMetric.TOTAL_SUM,
                includedCategories = listOf("Dining")
            ),
            judgeProseToReturn = "You spent $253.50 on dining."
        )

        val viewModel = ChatViewModel(
            chatRepository = chatRepo,
            aiChatDataSource = fakeAI,
            authRepository = authRepo,
            liveVoiceManager = voiceManager,
            profileStore = profileStore
        )

        viewModel.sendMessage("How much did I spend on dining?")

        // 2 messages saved: User + Assistant
        assertEquals(2, chatRepo.messages.size)
        val userMsg = chatRepo.messages[0]
        val assistantMsg = chatRepo.messages[1]

        assertEquals(MessageSender.USER, userMsg.sender)
        assertEquals("How much did I spend on dining?", userMsg.text)

        assertEquals(MessageSender.ASSISTANT, assistantMsg.sender)
        assertEquals("You spent $253.50 on dining.", assistantMsg.text)
        assertNotNull("Track A must include verified AuditReceipt", assistantMsg.auditReceipt)
        assertEquals(253.50, assistantMsg.auditReceipt?.value ?: 0.0, 0.001)
        assertNotNull("Track A must include inline visual fact data (VisualCard)", assistantMsg.visualCard)
        assertEquals(253.50, assistantMsg.visualCard?.totalAmount ?: 0.0, 0.001)
    }

    @Test
    fun trackB_executesConversationalGuidanceWithoutReceiptAndNoClassifierDumps() = runBlocking {
        val chatRepo = FakeChatRepository()
        val authRepo = FakeAuthRepository()
        val auditorRepo = StubAuditorRepository()
        val voiceManager = FakeLiveVoiceManager()
        val profileStore = FakeProfileStore()
        val fakeAI = FakeAIRemoteDataSource(
            auditorRepo = auditorRepo,
            specToReturn = FinancialQuerySpec(
                isOutOfSpec = true,
                outOfSpecReason = "Query is an open-ended habit description."
            ),
            guidanceTextToReturn = "Try cooking an extra meal at home this week to keep your food budget balanced."
        )

        val viewModel = ChatViewModel(
            chatRepository = chatRepo,
            aiChatDataSource = fakeAI,
            authRepository = authRepo,
            liveVoiceManager = voiceManager,
            profileStore = profileStore
        )

        viewModel.sendMessage("How can I spend less on takeout?")

        assertEquals(2, chatRepo.messages.size)
        val assistantMsg = chatRepo.messages[1]

        assertEquals(MessageSender.ASSISTANT, assistantMsg.sender)
        assertEquals("Try cooking an extra meal at home this week to keep your food budget balanced.", assistantMsg.text)
        assertNull("Track B must NOT include an AuditReceipt", assistantMsg.auditReceipt)

        // Verify that raw classifier strings are never leaked
        assertFalse(assistantMsg.text.contains("out_of_spec"))
        assertFalse(assistantMsg.text.contains("open-ended habit description"))
        assertEquals(FinancialGoal.MINDFUL_SPENDING, fakeAI.lastPassedGoal)
    }

    @Test
    fun multiTurnContext_resolvesAntecedentsFromPrecedingReceipt() = runBlocking {
        val chatRepo = FakeChatRepository()
        val authRepo = FakeAuthRepository()
        val auditorRepo = StubAuditorRepository()
        val voiceManager = FakeLiveVoiceManager()
        val profileStore = FakeProfileStore()
        val fakeAI = FakeAIRemoteDataSource(auditorRepo = auditorRepo)

        val viewModel = ChatViewModel(
            chatRepository = chatRepo,
            aiChatDataSource = fakeAI,
            authRepository = authRepo,
            liveVoiceManager = voiceManager,
            profileStore = profileStore
        )

        // Turn 1: Ask about dining (Track A)
        fakeAI.specToReturn = FinancialQuerySpec(
            targetMetric = TargetMetric.TOTAL_SUM,
            includedCategories = listOf("Dining")
        )
        fakeAI.judgeProseToReturn = "You spent $253.50 on dining."
        viewModel.sendMessage("How much did I spend on dining?")

        // Turn 2: Follow-up question referencing the preceding turn
        fakeAI.specToReturn = FinancialQuerySpec(
            targetMetric = TargetMetric.PERIOD_AVERAGE,
            includedCategories = listOf("Dining"),
            temporalGrain = com.talktomyfinances.android.domain.TemporalGrain.WEEK
        )
        fakeAI.judgeProseToReturn = "You spend an average of $63.38 per week on that category."
        viewModel.sendMessage("What about weekly for that category?")

        // Assert that the intent parser received the preceding receipt from Turn 1
        assertNotNull(fakeAI.lastPassedReceipt)
        assertEquals(listOf("Dining"), fakeAI.lastPassedReceipt?.spec?.includedCategories)
        assertEquals(253.50, fakeAI.lastPassedReceipt?.value ?: 0.0, 0.001)
    }

    @Test
    fun currencySanitization_preventsMultipleDecimalArtifacts() {
        assertEquals(
            "Your dining total was $0.00 this week.",
            CurrencySanitizer.sanitize("Your dining total was $0.0.00 this week.")
        )
        assertEquals(
            "Your dining total was $0.00 this week.",
            CurrencySanitizer.sanitize("Your dining total was $0.0.0 this week.")
        )
        assertEquals(
            "Your spending was $15.50 on cafes.",
            CurrencySanitizer.sanitize("Your spending was $15.5.50 on cafes.")
        )
        assertEquals(
            "Your spending was $15.50 on cafes.",
            CurrencySanitizer.sanitize("Your spending was $15.5.5 on cafes.")
        )
        assertEquals(
            "Your spending was $253.50 on dining.",
            CurrencySanitizer.sanitize("Your spending was $253.50 on dining.")
        )
    }

    @Test
    fun weeklyWindowsSerialization_supportsThisWeekAndLastWeek() {
        val thisWeekJson = """{"type":"THIS_WEEK"}"""
        val thisWeekWindow = json.decodeFromString<DateWindow>(thisWeekJson)
        assertEquals(DateWindowType.THIS_WEEK, thisWeekWindow.type)
        assertEquals("""{"type":"THIS_WEEK"}""", json.encodeToString(DateWindow.serializer(), thisWeekWindow))

        val lastWeekJson = """{"type":"LAST_WEEK"}"""
        val lastWeekWindow = json.decodeFromString<DateWindow>(lastWeekJson)
        assertEquals(DateWindowType.LAST_WEEK, lastWeekWindow.type)
        assertEquals("""{"type":"LAST_WEEK"}""", json.encodeToString(DateWindow.serializer(), lastWeekWindow))
    }

    @Test
    fun merchantBundleAndCategorySpec_parsesCorrectly() {
        val cafeQueryJson = """
            {
                "target_metric": "TOTAL_SUM",
                "included_merchants": ["SQ *CAFE NEWTOWN", "CORNER CAFE", "BLUEBONIC COFFEE CO"],
                "included_categories": [],
                "date_window": {"type": "THIS_WEEK"}
            }
        """.trimIndent()
        val cafeSpec = json.decodeFromString<FinancialQuerySpec>(cafeQueryJson)
        assertEquals(3, cafeSpec.includedMerchants.size)
        assertTrue(cafeSpec.includedMerchants.contains("SQ *CAFE NEWTOWN"))
        assertTrue(cafeSpec.includedCategories.isEmpty())
        assertEquals(DateWindowType.THIS_WEEK, cafeSpec.dateWindow.type)

        val diningQueryJson = """
            {
                "target_metric": "TOTAL_SUM",
                "included_merchants": [],
                "included_categories": ["Dining"],
                "date_window": {"type": "LAST_WEEK"}
            }
        """.trimIndent()
        val diningSpec = json.decodeFromString<FinancialQuerySpec>(diningQueryJson)
        assertTrue(diningSpec.includedMerchants.isEmpty())
        assertEquals(listOf("Dining"), diningSpec.includedCategories)
        assertEquals(DateWindowType.LAST_WEEK, diningSpec.dateWindow.type)
    }

    @Test
    fun clearChatHistory_emptiesMessagesAndResetsContext() = runBlocking {
        val chatRepo = FakeChatRepository()
        val authRepo = FakeAuthRepository()
        val auditorRepo = StubAuditorRepository()
        val voiceManager = FakeLiveVoiceManager()
        val profileStore = FakeProfileStore()
        val fakeAI = FakeAIRemoteDataSource(auditorRepo = auditorRepo)

        val viewModel = ChatViewModel(
            chatRepository = chatRepo,
            aiChatDataSource = fakeAI,
            authRepository = authRepo,
            liveVoiceManager = voiceManager,
            profileStore = profileStore
        )

        viewModel.sendMessage("How much did I spend on utility bills this quarter?")
        assertTrue(chatRepo.messages.isNotEmpty())

        viewModel.clearChatHistory()
        assertTrue("Expected chat repository messages to be cleared", chatRepo.messages.isEmpty())
    }

    @Test
    fun sanitizeProseForCardAttachment_stripsDuplicateBulletsAndTables() {
        val rawProseWithBullets = """
            Here is your dining breakdown for this month. You've spent $150 across 3 visits.
            • 2026-03-01 — CAFE: $4.50
            • 2026-03-05 — DINER: $45.50
            - 2026-03-10 — RESTAURANT: $100.00
            | Date | Merchant | Amount |
            | 2026-03-01 | CAFE | $4.50 |
            This keeps you well within your $500 monthly dining target towards your Emergency Fund.
        """.trimIndent()

        val sanitized = ChatViewModel.sanitizeProseForCardAttachment(rawProseWithBullets)

        assertFalse("Expected bullet lines to be stripped", sanitized.contains("•"))
        assertFalse("Expected dash bullet lines to be stripped", sanitized.contains("- 2026-03-10"))
        assertFalse("Expected table lines to be stripped", sanitized.contains("| Date |"))
        assertTrue("Expected intro to remain", sanitized.contains("Here is your dining breakdown"))
        assertTrue("Expected goal takeaway to remain", sanitized.contains("This keeps you well within your $500 monthly dining target"))
    }

    @Test
    fun sharedBaseToolPrompt_containsComplementaryCardPresentationRule() {
        val prompt = com.talktomyfinances.android.data.datasource.AIRemoteDataSource.buildSharedBaseToolPrompt("Emergency Fund")
        assertTrue(prompt.contains("Complementary Card Presentation Rule"))
        assertTrue(prompt.contains("NEVER output raw data tables, transaction bullet lists"))
        assertTrue(prompt.contains("Emergency Fund"))
    }

    @Test
    fun sanitizeProseForCardAttachment_completelyDropsAglEnergyTransactionBulletsAndMiddleDots_retainingOnlyCoaching() {
        val rawGeminiOutput = """
            • 2026-05-15 — AGL ENERGY: $103.43
            • 2026-04-15 — AGL ENERGY: $116.84
            • 2026-03-15 — AGL ENERGY: $116.88
            • 2026-02-15 — AGL ENERGY: $98.61
            • 2026-01-15 — AGL ENERGY: $86.86
            · 2025-12-15 — AGL ENERGY: $121.53
            · 2025-11-15 — AGL ENERGY: $131.22
            · 2025-10-15 — AGL ENERGY: $129.28

            Your bills dropped noticeably in recent months to well under $50, down from over $100 earlier in the year. Putting aside roughly $45 from each fortnightly pay into a bills buffer can help smooth out these seasonal swings and keep your spending mindful.
        """.trimIndent()

        val sanitized = ChatViewModel.sanitizeProseForCardAttachment(rawGeminiOutput)

        assertFalse("Expected standard bullets to be stripped", sanitized.contains("2026-05-15"))
        assertFalse("Expected middle dot bullets to be stripped", sanitized.contains("·"))
        assertFalse("Expected middle dot transaction date to be stripped", sanitized.contains("2025-12-15"))
        assertFalse("Expected merchant bullets to be stripped", sanitized.contains("AGL ENERGY"))
        assertEquals(
            "Your bills dropped noticeably in recent months to well under $50, down from over $100 earlier in the year. Putting aside roughly $45 from each fortnightly pay into a bills buffer can help smooth out these seasonal swings and keep your spending mindful.",
            sanitized
        )
    }
}


