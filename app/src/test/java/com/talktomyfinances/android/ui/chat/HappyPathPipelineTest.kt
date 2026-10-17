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
import com.talktomyfinances.android.domain.LiveVoiceManager
import com.talktomyfinances.android.domain.MessageSender
import com.talktomyfinances.android.domain.OnboardingProfile
import com.talktomyfinances.android.domain.PayCycle
import com.talktomyfinances.android.domain.PayFrequency
import com.talktomyfinances.android.domain.ProfileStore
import com.talktomyfinances.android.domain.TargetMetric
import com.talktomyfinances.android.domain.TemporalGrain
import com.talktomyfinances.android.domain.VisualCardPayload
import com.talktomyfinances.android.domain.VoiceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HappyPathPipelineTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeAuthRepository(var userId: String? = "user_123") : AuthRepository {
        override val currentUser: com.google.firebase.auth.FirebaseUser? get() = null
        override val currentUserId: String? get() = userId
        override suspend fun createAnonymousAccount(): com.talktomyfinances.android.data.model.User {
            userId = "user_123"
            return com.talktomyfinances.android.data.model.User(authId = "user_123")
        }
    }

    private class FakeChatRepository : ChatRepository {
        val messages = mutableListOf<ChatMessage>()
        private val flow = MutableStateFlow<List<ChatMessage>>(emptyList())

        override fun getMessages(userId: String): Flow<List<ChatMessage>> = flow

        override suspend fun saveMessage(userId: String, message: ChatMessage) {
            messages.add(message)
            flow.value = messages.toList()
        }
    }

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

    private class FakeProfileStore(
        var profile: OnboardingProfile? = OnboardingProfile(
            goal = FinancialGoal.MINDFUL_SPENDING,
            payCycle = PayCycle(PayFrequency.FORTNIGHTLY, "2026-10-20")
        )
    ) : ProfileStore {
        override suspend fun save(profile: OnboardingProfile) { this.profile = profile }
        override suspend fun load(): OnboardingProfile? = profile
    }

    private class FakeAuditorRepository(
        var receiptProvider: (FinancialQuerySpec) -> AuditReceipt = { spec ->
            AuditReceipt(
                summary = "Calculated successfully.",
                value = 253.50,
                spec = spec
            )
        }
    ) : com.talktomyfinances.android.domain.AuditorRepository {
        override suspend fun compute(spec: FinancialQuerySpec): Result<AuditReceipt> {
            return Result.success(receiptProvider(spec))
        }
    }

    private class FakeAIRemoteDataSource(
        var auditorRepo: com.talktomyfinances.android.domain.AuditorRepository? = null,
        var specToReturn: FinancialQuerySpec = FinancialQuerySpec(
            targetMetric = TargetMetric.TOTAL_SUM,
            includedCategories = listOf("Dining")
        ),
        var judgeProseToReturn: String = "You spent $253.50 on dining.",
        var guidanceTextToReturn: String = "Try cooking in batches this week to trim your takeout spending."
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
            lastPassedGoal = userProfile?.goal
            lastPassedProfile = userProfile

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
    fun test_happy_path_track_a_calculation_renders_receipt_and_card() = runBlocking {
        val chatRepo = FakeChatRepository()
        val authRepo = FakeAuthRepository()
        val auditorRepo = StubAuditorRepository()
        val voiceManager = FakeLiveVoiceManager()
        val profileStore = FakeProfileStore()
        val fakeAI = FakeAIRemoteDataSource(
            auditorRepo = auditorRepo,
            specToReturn = FinancialQuerySpec(
                targetMetric = TargetMetric.PERIOD_AVERAGE,
                includedCategories = listOf("Dining"),
                temporalGrain = TemporalGrain.MONTH
            ),
            judgeProseToReturn = "You averaged $84.50 per month on dining."
        )

        val viewModel = ChatViewModel(
            chatRepository = chatRepo,
            aiChatDataSource = fakeAI,
            authRepository = authRepo,
            liveVoiceManager = voiceManager,
            profileStore = profileStore
        )

        viewModel.sendMessage("What is my average monthly dining spend?")

        assertEquals(2, chatRepo.messages.size)
        val assistantMsg = chatRepo.messages[1]

        assertEquals(MessageSender.ASSISTANT, assistantMsg.sender)
        assertEquals("You averaged $84.50 per month on dining.", assistantMsg.text)
        assertNotNull("Track A must include verified AuditReceipt", assistantMsg.auditReceipt)
        assertNotNull("Track A multi-merchant breakdown must include VisualCard payload", assistantMsg.visualCard)
        assertEquals("Dining", assistantMsg.visualCard?.title)
        assertEquals(2, assistantMsg.visualCard?.items?.size)
        assertEquals("UBER *EATS", assistantMsg.visualCard?.items?.get(0)?.category)
        assertEquals("DOORDASH", assistantMsg.visualCard?.items?.get(1)?.category)
    }

    @Test
    fun test_happy_path_track_b_out_of_spec_routes_to_conversational_guidance() = runBlocking {
        val chatRepo = FakeChatRepository()
        val authRepo = FakeAuthRepository()
        val auditorRepo = StubAuditorRepository()
        val voiceManager = FakeLiveVoiceManager()
        val profileStore = FakeProfileStore()
        val fakeAI = FakeAIRemoteDataSource(
            auditorRepo = auditorRepo,
            specToReturn = FinancialQuerySpec(
                isOutOfSpec = true,
                outOfSpecReason = "Describe my spending habits and make recommendations"
            ),
            guidanceTextToReturn = "Here are three simple adjustments to keep your spending intentional without feeling restricted."
        )

        val viewModel = ChatViewModel(
            chatRepository = chatRepo,
            aiChatDataSource = fakeAI,
            authRepository = authRepo,
            liveVoiceManager = voiceManager,
            profileStore = profileStore
        )

        viewModel.sendMessage("Describe my spending habits and make recommendations")

        assertEquals(2, chatRepo.messages.size)
        val assistantMsg = chatRepo.messages[1]

        assertEquals(MessageSender.ASSISTANT, assistantMsg.sender)
        assertEquals("Here are three simple adjustments to keep your spending intentional without feeling restricted.", assistantMsg.text)
        assertNull("Track B out-of-spec queries must not attach an AuditReceipt", assistantMsg.auditReceipt)
        assertNull("Track B out-of-spec queries must not attach a VisualCard", assistantMsg.visualCard)
        assertFalse(assistantMsg.text.contains("out_of_spec"))
        assertFalse(assistantMsg.text.contains("is_out_of_spec"))
    }

    @Test
    fun test_happy_path_multi_turn_antecedent_resolution_passes_previous_receipt() = runBlocking {
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

        // Turn 1
        fakeAI.specToReturn = FinancialQuerySpec(
            targetMetric = TargetMetric.TOTAL_SUM,
            includedCategories = listOf("Dining")
        )
        fakeAI.judgeProseToReturn = "You spent $253.50 on dining."
        viewModel.sendMessage("How much did I spend on dining?")

        val turn1Receipt = chatRepo.messages.last().auditReceipt
        assertNotNull(turn1Receipt)

        // Turn 2
        fakeAI.specToReturn = FinancialQuerySpec(
            targetMetric = TargetMetric.TOTAL_SUM,
            includedCategories = listOf("Dining"),
            dateWindow = DateWindow(DateWindowType.LAST_MONTH)
        )
        fakeAI.judgeProseToReturn = "Last month you spent $180.20 on dining."
        viewModel.sendMessage("How about that category last month?")

        assertNotNull(fakeAI.lastPassedReceipt)
        assertEquals(listOf("Dining"), fakeAI.lastPassedReceipt?.spec?.includedCategories)
    }

    @Test
    fun test_happy_path_golden_chip_cafe_drilldown_executes_verifiably_and_suppresses_single_bar_card() = runBlocking {
        val chatRepo = FakeChatRepository()
        val authRepo = FakeAuthRepository()
        val auditorRepo = FakeAuditorRepository { spec ->
            AuditReceipt(
                summary = "You spent $9.50 across 1 transaction. Window: 2026-10-12 to 2026-10-12 (THIS_WEEK).",
                value = 9.50,
                breakdown = listOf(
                    com.talktomyfinances.android.domain.BreakdownItem(
                        category = "CORNER CAFE",
                        amount = 9.50,
                        percentage = 100.0,
                        count = 1
                    )
                ),
                spec = spec
            )
        }
        val voiceManager = FakeLiveVoiceManager()
        val profileStore = FakeProfileStore()
        val fakeAI = FakeAIRemoteDataSource(
            auditorRepo = auditorRepo,
            specToReturn = FinancialQuerySpec(
                targetMetric = TargetMetric.TOTAL_SUM,
                includedMerchants = listOf("SQ *CAFE NEWTOWN", "CORNER CAFE", "BLUEBONIC COFFEE CO"),
                dateWindow = DateWindow(DateWindowType.THIS_WEEK)
            ),
            judgeProseToReturn = "You spent $9.50 on cafes this week."
        )

        val viewModel = ChatViewModel(
            chatRepository = chatRepo,
            aiChatDataSource = fakeAI,
            authRepository = authRepo,
            liveVoiceManager = voiceManager,
            profileStore = profileStore
        )

        viewModel.sendMessage("How much did I spend on cafes this week?")

        val assistantMsg = chatRepo.messages.last()
        assertEquals("You spent $9.50 on cafes this week.", assistantMsg.text)
        assertNotNull(assistantMsg.auditReceipt)
        assertEquals(9.50, assistantMsg.auditReceipt?.value ?: 0.0, 0.001)
        assertNull("Single-item breakdown should render Verified Fact receipt without a 100% single-bar VisualCard", assistantMsg.visualCard)
    }

    @Test
    fun test_happy_path_golden_chip_takeaway_drilldown_executes_verifiably_and_suppresses_empty_card() = runBlocking {
        val chatRepo = FakeChatRepository()
        val authRepo = FakeAuthRepository()
        val auditorRepo = FakeAuditorRepository { spec ->
            AuditReceipt(
                summary = "You spent $0.00 across 0 transactions. Window: 2026-10-12 to 2026-10-12 (THIS_WEEK).",
                value = 0.00,
                breakdown = emptyList(),
                spec = spec
            )
        }
        val voiceManager = FakeLiveVoiceManager()
        val profileStore = FakeProfileStore()
        val fakeAI = FakeAIRemoteDataSource(
            auditorRepo = auditorRepo,
            specToReturn = FinancialQuerySpec(
                targetMetric = TargetMetric.TOTAL_SUM,
                includedMerchants = listOf("UBER *EATS", "DOORDASH", "ALDI DELIVERY"),
                dateWindow = DateWindow(DateWindowType.THIS_WEEK)
            ),
            judgeProseToReturn = "You spent $0.00 on takeaway this pay cycle."
        )

        val viewModel = ChatViewModel(
            chatRepository = chatRepo,
            aiChatDataSource = fakeAI,
            authRepository = authRepo,
            liveVoiceManager = voiceManager,
            profileStore = profileStore
        )

        viewModel.sendMessage("How much did I spend on takeaway this pay cycle?")

        val assistantMsg = chatRepo.messages.last()
        assertEquals("You spent $0.00 on takeaway this pay cycle.", assistantMsg.text)
        assertNotNull(assistantMsg.auditReceipt)
        assertEquals(0.00, assistantMsg.auditReceipt?.value ?: 0.0, 0.001)
        assertNull("Empty breakdown ($0.00 spend) should render Verified Fact receipt without an empty VisualCard", assistantMsg.visualCard)
    }

    @Test
    fun test_happy_path_subscriptions_drilldown_populates_merchant_breakdown_and_followup_transaction_list() = runBlocking {
        val chatRepo = FakeChatRepository()
        val authRepo = FakeAuthRepository()
        val auditorRepo = FakeAuditorRepository { spec ->
            if (spec.targetMetric == TargetMetric.TRANSACTION_LIST) {
                AuditReceipt(
                    summary = "Here are the 11 transactions totaling $139.95 (2026-05-01 to 2026-10-12 (SIX_MONTHS)):\n• 2026-10-09: STREAMY HD — $15.00\n• 2026-09-15: NETFLIX — $9.99\nCounted: categories: Subscriptions. Excluded: pending transactions; internal transfers; credits, income and refunds.",
                    value = 139.95,
                    breakdown = listOf(
                        com.talktomyfinances.android.domain.BreakdownItem("STREAMY HD", 90.00, 64.3, 6),
                        com.talktomyfinances.android.domain.BreakdownItem("NETFLIX", 49.95, 35.7, 5)
                    ),
                    transactions = listOf(
                        com.talktomyfinances.android.domain.MatchedTransaction("2026-10-09", "STREAMY HD", "Subscriptions", 15.00),
                        com.talktomyfinances.android.domain.MatchedTransaction("2026-09-15", "NETFLIX", "Subscriptions", 9.99)
                    ),
                    spec = spec
                )
            } else {
                AuditReceipt(
                    summary = "You spent $139.95 across 11 transactions. Window: 2026-05-01 to 2026-10-12 (SIX_MONTHS).",
                    value = 139.95,
                    breakdown = listOf(
                        com.talktomyfinances.android.domain.BreakdownItem("STREAMY HD", 90.00, 64.3, 6),
                        com.talktomyfinances.android.domain.BreakdownItem("NETFLIX", 49.95, 35.7, 5)
                    ),
                    transactions = listOf(
                        com.talktomyfinances.android.domain.MatchedTransaction("2026-10-09", "STREAMY HD", "Subscriptions", 15.00),
                        com.talktomyfinances.android.domain.MatchedTransaction("2026-09-15", "NETFLIX", "Subscriptions", 9.99)
                    ),
                    spec = spec
                )
            }
        }
        val voiceManager = FakeLiveVoiceManager()
        val profileStore = FakeProfileStore()
        val fakeAI = FakeAIRemoteDataSource(
            auditorRepo = auditorRepo,
            specToReturn = FinancialQuerySpec(
                targetMetric = TargetMetric.TOTAL_SUM,
                includedCategories = listOf("Subscriptions"),
                dateWindow = DateWindow(DateWindowType.SIX_MONTHS)
            ),
            judgeProseToReturn = "You spent $139.95 across 11 subscription transactions over the last 6 months."
        )

        val viewModel = ChatViewModel(
            chatRepository = chatRepo,
            aiChatDataSource = fakeAI,
            authRepository = authRepo,
            liveVoiceManager = voiceManager,
            profileStore = profileStore
        )

        // Turn 1: Subscriptions total + merchant breakdown VisualCard
        viewModel.sendMessage("How much did I spend on subscriptions over the last 6 months?")
        val turn1Msg = chatRepo.messages.last()
        assertNotNull(turn1Msg.visualCard)
        assertEquals("Subscriptions", turn1Msg.visualCard?.title)
        assertEquals(2, turn1Msg.visualCard?.items?.size)
        assertEquals("STREAMY HD", turn1Msg.visualCard?.items?.get(0)?.category)
        assertEquals(90.00, turn1Msg.visualCard?.items?.get(0)?.amount ?: 0.0, 0.001)
        assertEquals(64.3, turn1Msg.visualCard?.items?.get(0)?.percentage ?: 0.0, 0.001)
        assertEquals("NETFLIX", turn1Msg.visualCard?.items?.get(1)?.category)
        assertEquals(49.95, turn1Msg.visualCard?.items?.get(1)?.amount ?: 0.0, 0.001)

        // Turn 2: Follow-up "let me see the transactions" resolves to TRANSACTION_LIST on Track A
        fakeAI.specToReturn = FinancialQuerySpec(
            targetMetric = TargetMetric.TRANSACTION_LIST,
            includedCategories = listOf("Subscriptions"),
            dateWindow = DateWindow(DateWindowType.SIX_MONTHS)
        )
        viewModel.sendMessage("let me see the transactions")

        val turn2Msg = chatRepo.messages.last()
        assertNotNull("Follow-up transaction list must stay on Track A with an AuditReceipt", turn2Msg.auditReceipt)
        assertNull("TRANSACTION_LIST should not render a duplicate bar chart VisualCard", turn2Msg.visualCard)
        assertTrue(turn2Msg.text.contains("• 2026-10-09: STREAMY HD — $15.00"))
        assertTrue(turn2Msg.text.contains("• 2026-09-15: NETFLIX — $9.99"))
    }

    @Test
    fun test_happy_path_golden_chip_utility_drilldown_executes_verifiably() = runBlocking {
        val chatRepo = FakeChatRepository()
        val authRepo = FakeAuthRepository()
        val auditorRepo = FakeAuditorRepository { spec ->
            AuditReceipt(
                summary = "You spent $105.83 across 2 transactions. Window: 2026-10-01 to 2026-10-12 (THIS_QUARTER).",
                value = 105.83,
                breakdown = listOf(
                    com.talktomyfinances.android.domain.BreakdownItem("ORIGIN ENERGY", 65.83, 62.2, 1),
                    com.talktomyfinances.android.domain.BreakdownItem("SYDNEY WATER", 40.00, 37.8, 1)
                ),
                spec = spec
            )
        }
        val voiceManager = FakeLiveVoiceManager()
        val profileStore = FakeProfileStore()
        val fakeAI = FakeAIRemoteDataSource(
            auditorRepo = auditorRepo,
            specToReturn = FinancialQuerySpec(
                targetMetric = TargetMetric.TOTAL_SUM,
                includedCategories = listOf("Utilities"),
                dateWindow = DateWindow(DateWindowType.THIS_QUARTER)
            ),
            judgeProseToReturn = "You spent $105.83 on utility bills this quarter."
        )

        val viewModel = ChatViewModel(
            chatRepository = chatRepo,
            aiChatDataSource = fakeAI,
            authRepository = authRepo,
            liveVoiceManager = voiceManager,
            profileStore = profileStore
        )

        viewModel.sendMessage("How much did I spend on utility bills this quarter?")

        val assistantMsg = chatRepo.messages.last()
        assertEquals("You spent $105.83 on utility bills this quarter.", assistantMsg.text)
        assertNotNull(assistantMsg.auditReceipt)
        assertEquals(105.83, assistantMsg.auditReceipt?.value ?: 0.0, 0.001)
        assertNotNull(assistantMsg.visualCard)
        assertEquals("Utilities", assistantMsg.visualCard?.title)
        assertEquals(2, assistantMsg.visualCard?.items?.size)
        assertEquals("ORIGIN ENERGY", assistantMsg.visualCard?.items?.get(0)?.category)
        assertEquals("SYDNEY WATER", assistantMsg.visualCard?.items?.get(1)?.category)
    }

    @Test
    fun test_happy_path_declarative_schema_covers_all_domain_enums() {
        val targetMetrics = com.talktomyfinances.android.data.datasource.AIRemoteDataSource.TARGET_METRIC_VALUES
        assertTrue(targetMetrics.contains("TRANSACTION_LIST"))
        assertEquals(TargetMetric.entries.map { it.name }, targetMetrics)

        val dateWindows = com.talktomyfinances.android.data.datasource.AIRemoteDataSource.DATE_WINDOW_TYPE_VALUES
        assertTrue(dateWindows.contains("THIS_WEEK"))
        assertTrue(dateWindows.contains("LAST_WEEK"))
        assertEquals(DateWindowType.entries.map { it.name }, dateWindows)
        assertNotNull(com.talktomyfinances.android.data.datasource.AIRemoteDataSource.FINANCIAL_QUERY_SPEC_SCHEMA)

        val doNotRules = com.talktomyfinances.android.data.datasource.AIRemoteDataSource.GUIDANCE_DO_NOT_RULES
        assertTrue(doNotRules.isNotEmpty())
        assertTrue(com.talktomyfinances.android.data.datasource.AIRemoteDataSource.formatGuidanceDoNotRulesPrompt().contains("  - "))
    }

    @Test
    fun test_happy_path_currency_formatting_preserves_standard_decimals() {
        val sanitizedZero = CurrencySanitizer.sanitize("You spent $0.0.00 on takeaway")
        assertEquals("You spent $0.00 on takeaway", sanitizedZero)

        val sanitizedCorrupted = CurrencySanitizer.sanitize("Spend was $15.5.50 on lunch")
        assertEquals("Spend was $15.50 on lunch", sanitizedCorrupted)

        val cleanText = CurrencySanitizer.sanitize("You spent $105.83 on utility bills this quarter.")
        assertEquals("You spent $105.83 on utility bills this quarter.", cleanText)
    }

    @Test
    fun test_happy_path_onboarding_demo_defaults_to_fortnightly_and_mindful_spending() {
        val demoState = com.talktomyfinances.android.ui.onboarding.OnboardingState(isDemo = true)
        assertTrue(demoState.isDemo)
        assertEquals(FinancialGoal.MINDFUL_SPENDING, demoState.selectedGoal)
        assertEquals(PayFrequency.FORTNIGHTLY, demoState.frequency)
        assertEquals("2026-10-13", demoState.nextPayDate)
        assertTrue(demoState.canContinue)
    }

    @Test
    fun test_happy_path_receipt_template_formats_headline_and_bullets() {
        val receipt = AuditReceipt(
            summary = "You spent $158.02 across 5 transactions.\n• Window: 2026-09-29 to 2026-10-12 (THIS_PAY_CYCLE)\n• Denominator: 2 active months\n• Counted: merchants: ALDI DELIVERY, DOORDASH, UBER *EATS\n• Excluded: pending transactions; internal transfers; credits, income and refunds",
            value = 158.02,
            spec = FinancialQuerySpec(
                targetMetric = TargetMetric.TOTAL_SUM,
                includedMerchants = listOf("UBER *EATS", "DOORDASH", "ALDI DELIVERY"),
                dateWindow = DateWindow(DateWindowType.THIS_PAY_CYCLE)
            )
        )
        val template = receipt.toReceiptTemplate()
        assertEquals("You spent $158.02 across 5 transactions.", template.headline)
        assertEquals(4, template.bullets.size)
        assertEquals("Window: 2026-09-29 to 2026-10-12 (THIS_PAY_CYCLE)", template.bullets[0])
        assertEquals("Denominator: 2 active months", template.bullets[1])
        assertEquals("Counted: merchants: ALDI DELIVERY, DOORDASH, UBER *EATS", template.bullets[2])
        assertEquals("Excluded: pending transactions; internal transfers; credits, income and refunds", template.bullets[3])
    }
}

