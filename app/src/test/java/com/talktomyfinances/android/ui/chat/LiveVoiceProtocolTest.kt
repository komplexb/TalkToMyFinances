package com.talktomyfinances.android.ui.chat

import com.talktomyfinances.android.data.datasource.AIChatDataSource
import com.talktomyfinances.android.data.repository.AuthRepository
import com.talktomyfinances.android.data.repository.LiveVoiceRepositoryImpl
import com.talktomyfinances.android.data.repository.StubAuditorRepository
import com.talktomyfinances.android.domain.AuditReceipt
import com.talktomyfinances.android.domain.ChatMessage
import com.talktomyfinances.android.domain.ChatRepository
import com.talktomyfinances.android.domain.FinancialGoal
import com.talktomyfinances.android.domain.FinancialQuerySpec
import com.talktomyfinances.android.domain.LiveVoiceManager
import com.talktomyfinances.android.domain.MessageSender
import com.talktomyfinances.android.domain.OnboardingProfile
import com.talktomyfinances.android.domain.ProfileStore
import com.talktomyfinances.android.domain.TargetMetric
import com.talktomyfinances.android.domain.VisualCard
import com.talktomyfinances.android.domain.VisualCardItem
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LiveVoiceProtocolTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeAuthRepository : AuthRepository {
        override val currentUser: com.google.firebase.auth.FirebaseUser? get() = null
        override val currentUserId: String? get() = "user_voice_123"
        override suspend fun createAnonymousAccount(): com.talktomyfinances.android.data.model.User {
            return com.talktomyfinances.android.data.model.User(authId = "user_voice_123")
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

        val cardFlow = MutableSharedFlow<VisualCard>(extraBufferCapacity = 16)
        override val visualCardEvents = cardFlow.asSharedFlow()

        override suspend fun startSession(): Result<Unit> = Result.success(Unit)
        override suspend fun stopSession() {}
        override fun setMuted(muted: Boolean) {}
    }

    private class FakeProfileStore : ProfileStore {
        override suspend fun save(profile: OnboardingProfile) {}
        override suspend fun load(): OnboardingProfile? = null
    }

    private class FakeAIRemoteDataSource : AIChatDataSource {
        
    override suspend fun sendMessage(
        prompt: String,
        recentMessages: List<ChatMessage>,
        previousReceipt: AuditReceipt?,
        userGoal: FinancialGoal?,
        onPartialResponse: ((com.talktomyfinances.android.domain.ChatResponseResult) -> Unit)?
    ): com.talktomyfinances.android.domain.ChatResponseResult {
        lastPassedPrompt = prompt
        lastPassedReceipt = previousReceipt
        lastPassedMessages = recentMessages
        lastPassedGoal = userGoal
        
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

        fun parseFinancialIntent_old(
            prompt: String,
            previousReceipt: AuditReceipt?,
            recentMessages: List<ChatMessage>
        ): FinancialQuerySpec = FinancialQuerySpec(targetMetric = TargetMetric.TOTAL_SUM)

        fun evaluateWithJudge_old(prose: String, verifiedAmount: Double?): String = prose

        fun generateConversationalGuidance_old(
            prompt: String,
            recentMessages: List<ChatMessage>,
            previousReceipt: AuditReceipt?,
            userGoal: FinancialGoal?
        ): String = "Guidance"
    }

    private val canonicalLivePrompt: String by lazy {
        val candidatePaths = listOf(
            java.io.File("src/main/res/xml/remote_config_defaults.xml"),
            java.io.File("app/src/main/res/xml/remote_config_defaults.xml")
        )
        val file = candidatePaths.firstOrNull { it.exists() }
            ?: throw IllegalStateException("Could not find remote_config_defaults.xml")
        val content = file.readText()
        val startTag = "<key>live_model_prompt</key>"
        val valueStartTag = "<value>"
        val valueEndTag = "</value>"
        val keyIndex = content.indexOf(startTag)
        val valStart = content.indexOf(valueStartTag, keyIndex) + valueStartTag.length
        val valEnd = content.indexOf(valueEndTag, valStart)
        content.substring(valStart, valEnd).replace("&amp;", "&").trim()
    }

    @Test
    fun voiceAssistantUsesCleanArticulateToneWithoutRegionalDrawlsOrCaricatureSlang() {
        val prompt = canonicalLivePrompt.lowercase()

        // Verifies prohibition of caricatures and regional drawls
        assertTrue(prompt.contains("strictly avoid exaggerated regional drawls") || prompt.contains("drawls"))
        assertTrue(prompt.contains("never say 'g'day'") || prompt.contains("g'day"))
        assertTrue(prompt.contains("mate"))
        assertTrue(prompt.contains("clean, natural, articulate tone") || prompt.contains("articulate"))
        assertFalse("Prompt must not instruct using slang", prompt.contains("speak like an aussie bloke"))
    }

    @Test
    fun voiceAssistantAcousticPersonaReflectsAustralianRetailConventions() {
        val prompt = canonicalLivePrompt

        // Grounded in Australian financial conventions
        assertTrue(prompt.contains("Australian financial context"))
        assertTrue(prompt.contains("\$ AUD"))
        assertTrue(prompt.contains("fortnightly pay cycles"))
    }

    @Test
    fun voiceAssistantAdheresToBrevityCapAndNoSpokenTables() {
        val prompt = canonicalLivePrompt

        // 2-3 sentence limit and no raw ledger tables
        assertTrue(prompt.contains("2 to 3 spoken sentences maximum"))
        assertTrue(prompt.contains("NEVER read long transaction lists"))
    }

    @Test
    fun voiceAssistantEnforcesProactiveVisualOffloading() {
        val prompt = canonicalLivePrompt

        // Proactively triggers render_visual_card
        assertTrue(prompt.contains("Proactively trigger the client-side tool render_visual_card"))
        assertTrue(prompt.contains("do not wait for an explicit user command"))
    }

    @Test
    fun voiceAssistantEnforcesFlexibleExpressionAndAvoidsRepetitiveCannedPhrases() {
        val prompt = canonicalLivePrompt

        // Contextual variance instead of robotic phrases
        assertTrue(prompt.contains("vary naturally and contextually"))
        assertTrue(prompt.contains("Avoid repetitive or wooden canned phrases"))
    }

    @Test
    fun voiceAssistantExplainsOutOfCatalogBoundariesConversationallyWithoutHallucinations() {
        val prompt = canonicalLivePrompt

        // Out-of-catalog boundaries (crypto, investments, unlinked accounts)
        assertTrue(prompt.contains("out-of-catalog"))
        assertTrue(prompt.contains("cryptocurrency") || prompt.contains("crypto"))
        assertTrue(prompt.contains("Never invent, estimate, or hallucinate"))
    }

    @Test
    fun visualCardChatBubblesRenderDynamicContextAwareCaptioning() = runBlocking {
        val chatRepo = FakeChatRepository()
        val authRepo = FakeAuthRepository()
        val auditorRepo = StubAuditorRepository()
        val voiceManager = FakeLiveVoiceManager()
        val profileStore = FakeProfileStore()
        val fakeAI = FakeAIRemoteDataSource(auditorRepo = auditorRepo)

        val viewModel = ChatViewModel(
            chatRepository = chatRepo,
            aiChatDataSource = fakeAI,
            auditorRepository = auditorRepo,
            authRepository = authRepo,
            liveVoiceManager = voiceManager,
            profileStore = profileStore
        )

        // Event 1: Cafe spending this week
        voiceManager.cardFlow.tryEmit(
            VisualCard(
                cardType = "category_breakdown",
                title = "Cafe Spending",
                totalAmount = 9.50,
                timeframe = "THIS_WEEK",
                items = listOf(VisualCardItem(category = "Dining", amount = 9.50))
            )
        )

        val msg1 = chatRepo.messages.last()
        assertEquals("Here is your Cafe Spending this week.", msg1.text)
        assertEquals("Cafe Spending", msg1.visualCard?.title)

        // Event 2: Takeaway spend breakdown this pay cycle
        voiceManager.cardFlow.tryEmit(
            VisualCard(
                cardType = "category_breakdown",
                title = "Takeaway Spend Breakdown",
                totalAmount = 0.00,
                timeframe = "THIS_PAY_CYCLE",
                items = emptyList()
            )
        )

        val msg2 = chatRepo.messages.last()
        assertEquals("Here is your Takeaway Spend Breakdown this pay cycle.", msg2.text)

        // Event 3: Utility bills this quarter
        voiceManager.cardFlow.tryEmit(
            VisualCard(
                cardType = "spending_trend",
                title = "Utility Bills",
                totalAmount = 105.83,
                timeframe = "THIS_QUARTER",
                items = listOf(VisualCardItem(category = "Utilities", amount = 105.83))
            )
        )

        val msg3 = chatRepo.messages.last()
        assertEquals("Here is your Utility Bills this quarter.", msg3.text)
    }
}
