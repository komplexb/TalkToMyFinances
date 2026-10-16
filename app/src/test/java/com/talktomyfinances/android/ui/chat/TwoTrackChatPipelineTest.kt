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
    fun trackA_executesDeterministicMathAndAttachesAuditReceipt() = runBlocking {
        val chatRepo = FakeChatRepository()
        val authRepo = FakeAuthRepository()
        val auditorRepo = StubAuditorRepository()
        val voiceManager = FakeLiveVoiceManager()
        val profileStore = FakeProfileStore()
        val fakeAI = FakeAIRemoteDataSource(
            specToReturn = FinancialQuerySpec(
                targetMetric = TargetMetric.TOTAL_SUM,
                includedCategories = listOf("Dining")
            ),
            judgeProseToReturn = "You spent $253.50 on dining."
        )

        val viewModel = ChatViewModel(
            chatRepository = chatRepo,
            aiChatDataSource = fakeAI,
            auditorRepository = auditorRepo,
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
            specToReturn = FinancialQuerySpec(
                isOutOfSpec = true,
                outOfSpecReason = "Query is an open-ended habit description."
            ),
            guidanceTextToReturn = "Try cooking an extra meal at home this week to keep your food budget balanced."
        )

        val viewModel = ChatViewModel(
            chatRepository = chatRepo,
            aiChatDataSource = fakeAI,
            auditorRepository = auditorRepo,
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
        val fakeAI = FakeAIRemoteDataSource()

        val viewModel = ChatViewModel(
            chatRepository = chatRepo,
            aiChatDataSource = fakeAI,
            auditorRepository = auditorRepo,
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
}
