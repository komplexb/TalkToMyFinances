package com.talktomyfinances.android.data.datasource

import android.graphics.Bitmap
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.DownloadStatus.DownloadCompleted
import com.google.firebase.ai.DownloadStatus.DownloadFailed
import com.google.firebase.ai.DownloadStatus.DownloadInProgress
import com.google.firebase.ai.DownloadStatus.DownloadStarted
import com.google.firebase.ai.FirebaseAI
import com.google.firebase.ai.InferenceMode
import com.google.firebase.ai.InferenceSource
import com.google.firebase.ai.OnDeviceConfig
import com.google.firebase.ai.OnDeviceModelStatus.Companion.AVAILABLE
import com.google.firebase.ai.OnDeviceModelStatus.Companion.DOWNLOADABLE
import com.google.firebase.ai.OnDeviceModelStatus.Companion.DOWNLOADING
import com.google.firebase.ai.OnDeviceModelStatus.Companion.UNAVAILABLE
import com.google.firebase.ai.type.ImagePart
import com.google.firebase.ai.type.InlineDataPart
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.SpeechConfig
import com.google.firebase.ai.type.Voice
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.talktomyfinances.android.data.schema.MealSchema
import com.talktomyfinances.android.data.schema.RecipeSchema
import com.talktomyfinances.android.domain.AuditReceipt
import com.talktomyfinances.android.domain.ChatMessage
import com.talktomyfinances.android.domain.CurrencySanitizer
import com.talktomyfinances.android.domain.FinancialGoal
import com.talktomyfinances.android.domain.FinancialQuerySpec
import com.google.firebase.perf.performance
import com.google.firebase.perf.trace
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import kotlinx.serialization.json.Json
import javax.inject.Inject
import com.google.firebase.ai.type.LatLng
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.TemplateTool
import com.google.firebase.ai.type.TemplateToolConfig
import com.google.firebase.ai.type.retrievalConfig
import com.talktomyfinances.android.data.schema.StoreFinderResult
import com.talktomyfinances.android.data.schema.StoreSchema
import com.talktomyfinances.android.domain.DateWindowType
import com.talktomyfinances.android.domain.DenominatorStrategy
import com.talktomyfinances.android.domain.TargetMetric
import com.talktomyfinances.android.domain.TemporalGrain

interface AIChatDataSource {
    suspend fun parseFinancialIntent(
        prompt: String,
        previousReceipt: AuditReceipt? = null,
        recentMessages: List<ChatMessage> = emptyList()
    ): FinancialQuerySpec

    suspend fun evaluateWithJudge(prose: String, verifiedAmount: Double?): String

    suspend fun generateConversationalGuidance(
        prompt: String,
        recentMessages: List<ChatMessage> = emptyList(),
        previousReceipt: AuditReceipt? = null,
        userGoal: FinancialGoal? = null
    ): String
}

@OptIn(PublicPreviewAPI::class)
open class AIRemoteDataSource @Inject constructor(
    private val aiModel: FirebaseAI,
    private val remoteConfig: FirebaseRemoteConfig
) : AIChatDataSource {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val hybridGenerativeModel = aiModel.generativeModel(
        modelName = remoteConfig.getString(HYBRID_CLOUD_MODEL_KEY),
        onDeviceConfig = OnDeviceConfig(mode = InferenceMode.PREFER_IN_CLOUD)
    )

    private val templateGenerativeModel = aiModel.templateGenerativeModel()

    suspend fun findStores(
        ingredients: List<String>,
        latitude: Double,
        longitude: Double,
        currentTime: String,
        dayOfWeek: String
    ): List<StoreSchema> {
        val groundingModel = aiModel.templateGenerativeModel(
            tools = listOf(TemplateTool.googleMaps()),
            toolConfig = TemplateToolConfig(
                retrievalConfig = retrievalConfig {
                    latLng = LatLng(latitude = latitude, longitude = longitude)
                    languageCode = LANGUAGE
                }
            )
        )

        return try {
            val response = groundingModel.generateContent(
                templateId = remoteConfig.getString(FIND_STORES_KEY),
                inputs = mapOf(
                    INGREDIENTS_FIELD to ingredients.joinToString(),
                    DAY_FIELD to dayOfWeek,
                    TIME_FIELD to currentTime
                )
            )

            val rawText = response.text ?: return emptyList()
            
            val cleanJson = rawText
                .replace("```json", "")
                .replace("```", "")
                .trim()

            json.decodeFromString<StoreFinderResult>(cleanJson).stores
        } catch (e: Exception) {
            Log.e(TAG, "Error finding stores with these ingredients", e)
            emptyList()
        }
    }

    suspend fun generateIngredients(image: Bitmap): String {
        // Adding a Performance Monitoring trace is completely optional. Traces can help you
        // measure how long it takes to generate ingredients on device and in cloud.
        Firebase.performance.newTrace("hybrid-inference").trace {
            val prompt = content {
                image(image)
                text(remoteConfig.getString(HYBRID_INGREDIENTS_PROMPT_KEY))
            }

            val response = hybridGenerativeModel.generateContent(prompt)

            // This is an optional function that adds an attribute to the Performance Monitoring
            // trace. It helps you identify the source of the inference.
            putAttribute(
                "inferenceSource",
                when (response.inferenceSource) {
                    InferenceSource.ON_DEVICE -> "On device"
                    else -> "In cloud"
                }
            )

            return response.text.orEmpty()
        }
    }

    suspend fun generateRecipe(ingredients: String, notes: String): RecipeSchema? {
        val response = templateGenerativeModel.generateContent(
            templateId = remoteConfig.getString(GENERATE_RECIPE_KEY),
            inputs = buildMap {
                put(INGREDIENTS_FIELD, ingredients)
                if (notes.isNotBlank()) {
                    put(NOTES_FIELD, notes)
                }
            }
        )

        return response.text?.let {
            json.decodeFromString<RecipeSchema>(it)
        }
    }

    suspend fun generateRecipePhoto(recipeTitle: String): Bitmap? {
        val response = templateGenerativeModel.generateContent(
            templateId = remoteConfig.getString(GENERATE_RECIPE_PHOTO_GEMINI_KEY),
            inputs = mapOf(RECIPE_TITLE_FIELD to recipeTitle)
        )

        return response.candidates.firstOrNull()?.content?.parts
            ?.filterIsInstance<ImagePart>()?.firstOrNull()?.image
    }

    suspend fun scanMeal(imageData: String): MealSchema? {
        val response = templateGenerativeModel.generateContent(
            templateId = remoteConfig.getString(SCAN_MEAL_KEY),
            inputs = mapOf(
                MIME_TYPE_FIELD to MIME_TYPE_VALUE,
                IMAGE_DATA_FIELD to imageData
            )
        )

        return response.text?.let {
            json.decodeFromString<MealSchema>(it)
        }
    }

    suspend fun craftRecipePairing(dishTitle: String, ingredients: List<String>): String {
        val response = templateGenerativeModel.generateContent(
            templateId = remoteConfig.getString(RECIPE_PAIRING_KEY),
            inputs = mapOf(
                RECIPE_TITLE_FIELD to dishTitle,
                INGREDIENTS_FIELD to ingredients.joinToString()
            )
        )

        return response.text.orEmpty().trim()
    }

    suspend fun generateSpeech(text: String?): ByteArray? {
        if (text.isNullOrBlank()) return null

       val model = aiModel.generativeModel(
            modelName = remoteConfig.getString(TTS_MODEL_KEY),
            generationConfig = generationConfig {
                responseModalities = listOf(ResponseModality.AUDIO)
                speechConfig = SpeechConfig(
                    voice = Voice(TTS_VOICE),
                    languageCode = TTS_LANGUAGE
                )
            }
        )

        val voiceProfile = remoteConfig.getString(VOICE_PROFILE_KEY)
        val response = model.generateContent("$voiceProfile $text")
        val part = response.candidates.firstOrNull()?.content?.parts?.firstOrNull()

        return (part as? InlineDataPart)?.inlineData
    }

    suspend fun loadOnDeviceModel() {
        when (hybridGenerativeModel.onDeviceExtension?.checkStatus()) {
            UNAVAILABLE -> {
                Log.i(TAG, "On-device model is unavailable")
            }
            DOWNLOADABLE -> {
                hybridGenerativeModel.onDeviceExtension?.download()?.collect { status ->
                    when (status) {
                        is DownloadStarted ->
                            Log.i(TAG, "Starting download - ${status.bytesToDownload}")

                        is DownloadInProgress ->
                            Log.i(TAG, "Download in progress ${status.totalBytesDownloaded} bytes downloaded")

                        is DownloadCompleted ->
                            Log.i(TAG, "On-device model download complete")

                        is DownloadFailed ->
                            Log.e(TAG, "Download failed $status")
                    }
                }
            }
            DOWNLOADING -> {
                Log.i(TAG, "On-device model is being downloaded")
            }
            AVAILABLE -> {
                Log.i(TAG, "On-device model is available")
            }
        }
    }

    override suspend fun parseFinancialIntent(
        prompt: String,
        previousReceipt: AuditReceipt?,
        recentMessages: List<ChatMessage>
    ): FinancialQuerySpec {
        val modelName = remoteConfig.getString(CHAT_AI_MODEL_KEY).ifBlank { DEFAULT_CHAT_MODEL }
        val model = aiModel.generativeModel(
            modelName = modelName,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = FINANCIAL_QUERY_SPEC_SCHEMA
            },
            systemInstruction = content {
                text(
                    "You are an Intent Parser for financial queries in personal finance. " +
                    "Map user queries into a structured FinancialQuerySpec JSON object.\n" +
                    "Semantic Mapping Rules:\n" +
                    "- target_metric: PERIOD_AVERAGE for 'average/typical spend per <period>', AVERAGE_TRANSACTION for 'average order/transaction amount', " +
                    "TRANSACTION_COUNT for 'how often/how many times', TOTAL_SUM for 'total/how much', RUN_RATE for 'if this keeps up/on track for', " +
                    "or TRANSACTION_LIST when the user asks to see the individual transactions, charges, bills, or line items (e.g. 'let me see the transactions', 'what were those charges?', 'show me the bills').\n" +
                    "- temporal_grain: default to 'MONTH' when not specified.\n" +
                    "- date_window: set type when the query names a period ('this week' -> THIS_WEEK, 'last week' -> LAST_WEEK, 'last month' -> LAST_MONTH, 'this quarter' -> THIS_QUARTER, 'last 6 months' -> SIX_MONTHS, 'so far this year' -> YTD, etc.); default to ALL_TIME otherwise. Use CUSTOM with start/end (YYYY-MM-DD) only for explicit custom ranges.\n" +
                    "- Merchant-Level Subcategory Clusters vs Broad Categories:\n" +
                    formatMerchantClustersPrompt() + "\n" +
                    "  * Broad queries for 'dining' or 'eating out' -> map to included_categories: [\"Dining\"] and empty included_merchants (aggregates across all dining, including cafes and delivery).\n" +
                    "- Resolve conversational antecedents, pronouns, and references (such as 'that category', 'those places', 'that spend', 'the transactions', 'those charges', 'my last order', 'it') using the immediately preceding verified calculation and recent dialogue context. When the user asks to see the transactions from the previous answer, inherit the previous calculation's included_categories, included_merchants, and date_window and set target_metric to TRANSACTION_LIST.\n" +
                    "- If the query is NOT a verifiable calculation or transaction listing over past transactions — e.g. describing habits, asking for advice, budgeting tips, or a 'what if' simulation — " +
                    "set is_out_of_spec to true with a one-line out_of_spec_reason."
                )
            }
        )

        val promptContent = buildString {
            if (previousReceipt != null) {
                append("Immediately preceding verified calculation:\n")
                if (previousReceipt.spec.includedCategories.isNotEmpty()) {
                    append("- Categories: ${previousReceipt.spec.includedCategories.joinToString()}\n")
                }
                if (previousReceipt.spec.includedMerchants.isNotEmpty()) {
                    append("- Merchants: ${previousReceipt.spec.includedMerchants.joinToString()}\n")
                }
                append("- Window: ${previousReceipt.spec.dateWindow.type.name}\n")
                append("- Summary: ${previousReceipt.summary}\n")
                if (previousReceipt.value != null) {
                    append("- Value: ${previousReceipt.value}\n")
                }
                appendReceiptBreakdown(previousReceipt)
            }
            appendRecentDialogueTurns(recentMessages)
            append("User Query: $prompt")
        }

        return try {
            val response = model.generateContent(promptContent)
            val raw = response.text ?: throw IllegalStateException("Empty response from intent parser model")
            val clean = raw.replace("```json", "").replace("```", "").trim()
            json.decodeFromString<FinancialQuerySpec>(clean)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse intent via Gemini: ${e.message}", e)
            throw IllegalStateException("Could not parse financial intent for query: $prompt", e)
        }
    }

    override suspend fun evaluateWithJudge(prose: String, verifiedAmount: Double?): String {
        val judgeModelName = remoteConfig.getString(JUDGE_AI_MODEL_KEY).ifBlank { DEFAULT_JUDGE_MODEL }
        val judgeModel = aiModel.generativeModel(
            modelName = judgeModelName,
            systemInstruction = content {
                text(
                    "You are a reading-level quality judge for personal finance responses. " +
                    "Ensure text adheres to Grade 6-8 reading level, uses direct active sentences, and contains zero formal financial jargon.\n" +
                    "Crucial Rules:\n" +
                    "- NEVER alter, omit, or approximate dollar amounts or counts.\n" +
                    "- Preserve standard currency notation (e.g. $0.00, $15.50, never multiple decimal points like $0.0.00).\n" +
                    "- State the answer directly in 1-2 active sentences.\n" +
                    "- Do NOT append or include exclusion clauses or technical fine print (e.g. 'while ignoring pending transfers...', 'excluding internal transfers...', 'Counted:', 'Excluded:', 'Denominator:', date window codes) in the conversational response text. Technical audit fine print is presented separately in the UI audit receipt card.\n" +
                    "- Reply with the final text only."
                )
            }
        )

        val formattedAmount = if (verifiedAmount != null) {
            String.format(java.util.Locale.US, "$%.2f", verifiedAmount)
        } else null

        val prompt = if (formattedAmount != null) {
            "Prose to judge: \"$prose\". Must preserve exact currency amount: $formattedAmount. Ensure standard currency formatting without multiple decimal points."
        } else {
            "Prose to judge: \"$prose\". Ensure standard currency formatting without multiple decimal points."
        }

        return try {
            val response = judgeModel.generateContent(prompt)
            val rewritten = response.text?.trim()
            val textToClean = if (!rewritten.isNullOrBlank()) rewritten else prose
            CurrencySanitizer.sanitize(textToClean)
        } catch (e: Exception) {
            Log.w(TAG, "Judge evaluation skipped due to error: ${e.message}")
            CurrencySanitizer.sanitize(prose)
        }
    }

    override suspend fun generateConversationalGuidance(
        prompt: String,
        recentMessages: List<ChatMessage>,
        previousReceipt: AuditReceipt?,
        userGoal: FinancialGoal?
    ): String {
        val modelName = remoteConfig.getString(CHAT_AI_MODEL_KEY).ifBlank { DEFAULT_CHAT_MODEL }
        val goalName = userGoal?.name ?: "MINDFUL_SPENDING"
        val model = aiModel.generativeModel(
            modelName = modelName,
            systemInstruction = content {
                text(
                    "You are an empathetic, practical financial coach in an Australian personal finance app. " +
                    "The user's primary goal is: $goalName. " +
                    "Respond with supportive, practical behavioral coaching in 2-3 concise, active sentences at a Grade 6-8 reading level. " +
                    "Use Australian financial context ($ AUD, fortnightly pay cycles, local retail terms). " +
                    "Ground your advice in the verified breakdown and transactions provided in the context.\n" +
                    "Do NOT:\n" +
                    formatGuidanceDoNotRulesPrompt()
                )
            }
        )

        val promptContent = buildString {
            if (previousReceipt != null) {
                append("Immediately preceding verified calculation:\n")
                append("- Summary: ${CurrencySanitizer.sanitize(previousReceipt.summary)}\n")
                if (previousReceipt.value != null) {
                    append("- Verified amount: $${String.format(java.util.Locale.US, "%.2f", previousReceipt.value)}\n")
                }
                appendReceiptBreakdown(previousReceipt)
                if (previousReceipt.transactions.isNotEmpty()) {
                    val txLines = previousReceipt.transactions.take(10).joinToString("; ") {
                        "${it.date} ${it.merchant} $${String.format(java.util.Locale.US, "%.2f", it.amount)}"
                    }
                    append("- Matched transactions: $txLines\n")
                }
            }
            appendRecentDialogueTurns(recentMessages)
            append("User message: \"$prompt\"")
        }

        return try {
            val response = model.generateContent(promptContent)
            val text = response.text?.trim()?.takeIf { it.isNotBlank() }
                ?: "I'm here to help with your spending and budget. Could you tell me a bit more about what you'd like to look at?"
            CurrencySanitizer.sanitize(text)
        } catch (e: Exception) {
            Log.e(TAG, "Conversational guidance failed: ${e.message}", e)
            "I'm here to help with your spending and budget. Could you tell me a bit more about what you'd like to look at?"
        }
    }

    private fun StringBuilder.appendReceiptBreakdown(receipt: AuditReceipt) {
        if (receipt.breakdown.isNotEmpty()) {
            val breakdownDesc = receipt.breakdown.joinToString("; ") {
                "${it.category}: $${String.format(java.util.Locale.US, "%.2f", it.amount)} (${it.percentage}%)"
            }
            append("- Breakdown: $breakdownDesc\n")
        }
    }

    private fun StringBuilder.appendRecentDialogueTurns(recentMessages: List<ChatMessage>) {
        if (recentMessages.isNotEmpty()) {
            append("Recent conversation turns:\n")
            recentMessages.takeLast(4).forEach {
                append("${it.sender.name}: ${it.text}\n")
            }
        }
    }

    companion object {
        private const val CHAT_AI_MODEL_KEY = "chat_ai_model"
        private const val JUDGE_AI_MODEL_KEY = "judge_ai_model"
        private const val DEFAULT_CHAT_MODEL = "gemini-3.8-flash"
        private const val DEFAULT_JUDGE_MODEL = "gemini-3.5-flash-lite"
        //Remote Config Keys
        private const val GENERATE_RECIPE_KEY = "generate_recipe"
        private const val GENERATE_RECIPE_PHOTO_GEMINI_KEY = "generate_recipe_photo_gemini"
        private const val SCAN_MEAL_KEY = "scan_meal"
        private const val FIND_STORES_KEY = "find_stores"
        private const val HYBRID_CLOUD_MODEL_KEY = "hybrid_cloud_model"
        private const val HYBRID_INGREDIENTS_PROMPT_KEY = "hybrid_ingredients_prompt"
        private const val RECIPE_PAIRING_KEY = "recipe_pairing"
        private const val VOICE_PROFILE_KEY = "recipe_pairing_voice_profile"
        private const val TTS_MODEL_KEY = "tts_model"

        //Template input fields
        private const val IMAGE_DATA_FIELD = "imageData"
        private const val MIME_TYPE_FIELD = "mimeType"
        private const val INGREDIENTS_FIELD = "ingredients"
        private const val NOTES_FIELD = "notes"
        private const val RECIPE_TITLE_FIELD = "recipeTitle"
        private const val DAY_FIELD = "dayOfWeek"
        private const val TIME_FIELD = "currentTime"

        //Template input values
        private const val MIME_TYPE_VALUE = "image/jpeg"

        //Grounding with Maps config
        private const val LANGUAGE = "en_US"

        //TTS config
        private const val TTS_VOICE = "Charon"
        private const val TTS_LANGUAGE = "en-US"

        //Class TAG
        private const val TAG = "AIRemoteDataSource"

        // Extensible merchant-level subcategory clusters
        val CAFE_MERCHANTS = listOf("SQ *CAFE NEWTOWN", "CORNER CAFE", "BLUEBONIC COFFEE CO")
        val TAKEAWAY_MERCHANTS = listOf("UBER *EATS", "DOORDASH", "ALDI DELIVERY")
        val FUEL_MERCHANTS = listOf("BP FUEL", "SHELL FUEL")

        val SUBCATEGORY_MERCHANT_CLUSTERS = mapOf(
            "cafes or coffee" to CAFE_MERCHANTS,
            "takeout, takeaway, or delivery" to TAKEAWAY_MERCHANTS,
            "petrol or fuel" to FUEL_MERCHANTS
        )

        val SUBCATEGORY_CLUSTER_TITLES = mapOf(
            CAFE_MERCHANTS.toSet() to "Cafes",
            TAKEAWAY_MERCHANTS.toSet() to "Takeaway",
            FUEL_MERCHANTS.toSet() to "Fuel"
        )

        fun resolveClusterTitle(merchants: List<String>): String? {
            val normalizedSet = merchants.map { it.trim().uppercase() }.toSet()
            return SUBCATEGORY_CLUSTER_TITLES[normalizedSet]
        }

        val TARGET_METRIC_VALUES: List<String> = TargetMetric.entries.map { it.name }
        val DATE_WINDOW_TYPE_VALUES: List<String> = DateWindowType.entries.map { it.name }
        val TEMPORAL_GRAIN_VALUES: List<String> = TemporalGrain.entries.map { it.name }
        val DENOMINATOR_STRATEGY_VALUES: List<String> = DenominatorStrategy.entries.map { it.name }

        val FINANCIAL_QUERY_SPEC_SCHEMA: Schema = Schema.obj(
            properties = mapOf(
                "target_metric" to Schema.enumeration(
                    values = TARGET_METRIC_VALUES,
                    description = "The primary financial calculation or transaction listing metric."
                ),
                "included_merchants" to Schema.array(
                    items = Schema.string("Canonical merchant name to include."),
                    description = "Merchants to include in the calculation."
                ),
                "excluded_merchants" to Schema.array(
                    items = Schema.string("Canonical merchant name to exclude."),
                    description = "Merchants to exclude from the calculation."
                ),
                "included_categories" to Schema.array(
                    items = Schema.string("Category name to include."),
                    description = "Categories to include in the calculation."
                ),
                "excluded_categories" to Schema.array(
                    items = Schema.string("Category name to exclude."),
                    description = "Categories to exclude from the calculation."
                ),
                "date_window" to Schema.obj(
                    properties = mapOf(
                        "type" to Schema.enumeration(
                            values = DATE_WINDOW_TYPE_VALUES,
                            description = "Temporal window preset or CUSTOM."
                        ),
                        "start" to Schema.string(
                            description = "Start date YYYY-MM-DD for CUSTOM window.",
                            nullable = true
                        ),
                        "end" to Schema.string(
                            description = "End date YYYY-MM-DD for CUSTOM window.",
                            nullable = true
                        )
                    ),
                    optionalProperties = listOf("start", "end"),
                    description = "Temporal window for the query."
                ),
                "temporal_grain" to Schema.enumeration(
                    values = TEMPORAL_GRAIN_VALUES,
                    description = "Period grain for averages or counts."
                ),
                "denominator_strategy" to Schema.enumeration(
                    values = DENOMINATOR_STRATEGY_VALUES,
                    description = "Whether to divide by active periods or all calendar periods."
                ),
                "exclude_pending" to Schema.boolean("Whether to exclude pending transactions."),
                "exclude_internal_transfers" to Schema.boolean("Whether to exclude internal transfers."),
                "is_out_of_spec" to Schema.boolean("True when the query asks for coaching/habits rather than a verifiable calculation or transaction listing."),
                "out_of_spec_reason" to Schema.string(
                    description = "One-line reason when is_out_of_spec is true.",
                    nullable = true
                )
            ),
            optionalProperties = listOf("out_of_spec_reason")
        )

        val GUIDANCE_DO_NOT_RULES = listOf(
            "Attempt unverified mental arithmetic or fabricate transaction figures",
            "Output raw classifier or system diagnostic strings (e.g. 'is_out_of_spec')",
            "Refer the user to unimplemented screens or tabs outside this chat and the Insights Screen"
        )

        fun formatGuidanceDoNotRulesPrompt(): String {
            return GUIDANCE_DO_NOT_RULES.joinToString("\n") { "  - $it" }
        }

        fun formatMerchantClustersPrompt(): String {
            return SUBCATEGORY_MERCHANT_CLUSTERS.entries.joinToString("\n") { (queryTerms, merchants) ->
                val listLiteral = merchants.joinToString(separator = "\", \"", prefix = "[\"", postfix = "\"]")
                "  * Queries for '$queryTerms' -> map to included_merchants: $listLiteral and empty included_categories."
            }
        }
    }
}