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

        override suspend fun parseFinancialIntent(
            prompt: String,
            previousReceipt: AuditReceipt?,
            recentMessages: List<ChatMessage>
        ): FinancialQuerySpec {
            lastPassedPrompt = prompt
            lastPassedReceipt = previousReceipt
            lastPassedMessages = recentMessages
            return specToReturn
        }

        override suspend fun evaluateWithJudge(prose: String, verifiedAmount: Double?): String {
            return judgeProseToReturn
        }

        override suspend fun generateConversationalGuidance(
            prompt: String,
            recentMessages: List<ChatMessage>,
            previousReceipt: AuditReceipt?,
            userGoal: FinancialGoal?
        ): String {
            lastPassedPrompt = prompt
            lastPassedReceipt = previousReceipt
            lastPassedMessages = recentMessages
            lastPassedGoal = userGoal
            return guidanceTextToReturn
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
            specToReturn = FinancialQuerySpec(
                targetMetric = TargetMetric.PERIOD_AVERAGE,
                includedMerchants = listOf("UBER *EATS"),
                temporalGrain = TemporalGrain.MONTH
            ),
            judgeProseToReturn = "You averaged $35.00 per month on Uber Eats."
        )

        val viewModel = ChatViewModel(
            chatRepository = chatRepo,
            aiChatDataSource = fakeAI,
            auditorRepository = auditorRepo,
            authRepository = authRepo,
            liveVoiceManager = voiceManager,
            profileStore = profileStore
        )

        viewModel.sendMessage("What is my average uber eats monthly spend?")

        assertEquals(2, chatRepo.messages.size)
        val assistantMsg = chatRepo.messages[1]

        assertEquals(MessageSender.ASSISTANT, assistantMsg.sender)
        assertEquals("You averaged $35.00 per month on Uber Eats.", assistantMsg.text)
        assertNotNull("Track A must include verified AuditReceipt", assistantMsg.auditReceipt)
        assertNotNull("Track A must include VisualCard payload", assistantMsg.visualCard)
    }

    @Test
    fun test_happy_path_track_b_out_of_spec_routes_to_conversational_guidance() = runBlocking {
        val chatRepo = FakeChatRepository()
        val authRepo = FakeAuthRepository()
        val auditorRepo = StubAuditorRepository()
        val voiceManager = FakeLiveVoiceManager()
        val profileStore = FakeProfileStore()
        val fakeAI = FakeAIRemoteDataSource(
            specToReturn = FinancialQuerySpec(
                isOutOfSpec = true,
                outOfSpecReason = "Describe my spending habits and make recommendations"
            ),
            guidanceTextToReturn = "Here are three simple adjustments to keep your spending intentional without feeling restricted."
        )

        val viewModel = ChatViewModel(
            chatRepository = chatRepo,
            aiChatDataSource = fakeAI,
            auditorRepository = auditorRepo,
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
        val fakeAI = FakeAIRemoteDataSource()

        val viewModel = ChatViewModel(
            chatRepository = chatRepo,
            aiChatDataSource = fakeAI,
            auditorRepository = auditorRepo,
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
    fun test_happy_path_golden_chip_cafe_drilldown_executes_verifiably() = runBlocking {
        val chatRepo = FakeChatRepository()
        val authRepo = FakeAuthRepository()
        val auditorRepo = FakeAuditorRepository { spec ->
            AuditReceipt(
                summary = "You spent $9.50 across 1 transaction. Window: 2026-10-12 to 2026-10-12 (THIS_WEEK).",
                value = 9.50,
                spec = spec
            )
        }
        val voiceManager = FakeLiveVoiceManager()
        val profileStore = FakeProfileStore()
        val fakeAI = FakeAIRemoteDataSource(
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
            auditorRepository = auditorRepo,
            authRepository = authRepo,
            liveVoiceManager = voiceManager,
            profileStore = profileStore
        )

        viewModel.sendMessage("How much did I spend on cafes this week?")

        val assistantMsg = chatRepo.messages.last()
        assertEquals("You spent $9.50 on cafes this week.", assistantMsg.text)
        assertNotNull(assistantMsg.auditReceipt)
        assertEquals(9.50, assistantMsg.auditReceipt?.value ?: 0.0, 0.001)
        assertNotNull(assistantMsg.visualCard)
        assertEquals(9.50, assistantMsg.visualCard?.totalAmount ?: 0.0, 0.001)
    }

    @Test
    fun test_happy_path_golden_chip_takeaway_drilldown_executes_verifiably() = runBlocking {
        val chatRepo = FakeChatRepository()
        val authRepo = FakeAuthRepository()
        val auditorRepo = FakeAuditorRepository { spec ->
            AuditReceipt(
                summary = "You spent $0.00 across 0 transactions. Window: 2026-10-12 to 2026-10-12 (THIS_WEEK).",
                value = 0.00,
                spec = spec
            )
        }
        val voiceManager = FakeLiveVoiceManager()
        val profileStore = FakeProfileStore()
        val fakeAI = FakeAIRemoteDataSource(
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
            auditorRepository = auditorRepo,
            authRepository = authRepo,
            liveVoiceManager = voiceManager,
            profileStore = profileStore
        )

        viewModel.sendMessage("How much did I spend on takeaway this pay cycle?")

        val assistantMsg = chatRepo.messages.last()
        assertEquals("You spent $0.00 on takeaway this pay cycle.", assistantMsg.text)
        assertNotNull(assistantMsg.auditReceipt)
        assertEquals(0.00, assistantMsg.auditReceipt?.value ?: 0.0, 0.001)
        assertNotNull(assistantMsg.visualCard)
        assertEquals(0.00, assistantMsg.visualCard?.totalAmount ?: 0.0, 0.001)
    }

    @Test
    fun test_happy_path_golden_chip_utility_drilldown_executes_verifiably() = runBlocking {
        val chatRepo = FakeChatRepository()
        val authRepo = FakeAuthRepository()
        val auditorRepo = FakeAuditorRepository { spec ->
            AuditReceipt(
                summary = "You spent $105.83 across 1 transaction. Window: 2026-10-01 to 2026-10-12 (THIS_QUARTER).",
                value = 105.83,
                spec = spec
            )
        }
        val voiceManager = FakeLiveVoiceManager()
        val profileStore = FakeProfileStore()
        val fakeAI = FakeAIRemoteDataSource(
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
            auditorRepository = auditorRepo,
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
}
