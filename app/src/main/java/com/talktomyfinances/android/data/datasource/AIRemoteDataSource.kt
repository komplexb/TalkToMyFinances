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
import com.talktomyfinances.android.domain.FinancialQuerySpec
import com.google.firebase.perf.performance
import com.google.firebase.perf.trace
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import kotlinx.serialization.json.Json
import javax.inject.Inject
import com.google.firebase.ai.type.LatLng
import com.google.firebase.ai.type.TemplateTool
import com.google.firebase.ai.type.TemplateToolConfig
import com.google.firebase.ai.type.retrievalConfig
import com.talktomyfinances.android.data.schema.StoreFinderResult
import com.talktomyfinances.android.data.schema.StoreSchema

@OptIn(PublicPreviewAPI::class)
class AIRemoteDataSource @Inject constructor(
    private val aiModel: FirebaseAI,
    private val remoteConfig: FirebaseRemoteConfig
) {
    private val json = Json { ignoreUnknownKeys = true }

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

    suspend fun parseFinancialIntent(prompt: String): FinancialQuerySpec {
        val modelName = remoteConfig.getString(CHAT_AI_MODEL_KEY).ifBlank { DEFAULT_CHAT_MODEL }
        val model = aiModel.generativeModel(
            modelName = modelName,
            systemInstruction = content {
                text(
                    "You are an Intent Parser for financial queries in personal finance. " +
                    "Map user queries into a JSON object matching this schema:\n" +
                    "{\n" +
                    "  \"target_metric\": \"MONTHLY_AVERAGE\" | \"TOTAL_SUM\" | \"TRANSACTION_COUNT\" | \"RUN_RATE\",\n" +
                    "  \"included_merchants\": [string],\n" +
                    "  \"excluded_merchants\": [string],\n" +
                    "  \"included_categories\": [string],\n" +
                    "  \"excluded_categories\": [string],\n" +
                    "  \"temporal_grain\": \"WEEK\" | \"PAY_CYCLE\" | \"MONTH\" | \"QUARTER\" | \"YEAR\",\n" +
                    "  \"denominator_strategy\": \"ACTIVE_PERIODS_ONLY\" | \"ALL_CALENDAR_PERIODS\",\n" +
                    "  \"exclude_pending\": true,\n" +
                    "  \"exclude_internal_transfers\": true\n" +
                    "}\n" +
                    "Rules: Output ONLY raw valid JSON. No markdown fences, no explanatory preamble or postscript."
                )
            }
        )

        return try {
            val response = model.generateContent(prompt)
            val raw = response.text ?: ""
            val clean = raw.replace("```json", "").replace("```", "").trim()
            json.decodeFromString<FinancialQuerySpec>(clean)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse intent via Gemini, falling back to heuristic: ${e.message}")
            fallbackIntentHeuristic(prompt)
        }
    }

    suspend fun evaluateWithJudge(prose: String, verifiedAmount: Double?): String {
        val judgeModelName = remoteConfig.getString(JUDGE_AI_MODEL_KEY).ifBlank { DEFAULT_JUDGE_MODEL }
        val judgeModel = aiModel.generativeModel(
            modelName = judgeModelName,
            systemInstruction = content {
                text(
                    "You are a reading-level quality judge for personal finance responses. " +
                    "Ensure text adheres to Grade 6-8 reading level, uses direct active sentences, and contains zero formal financial jargon. " +
                    "Crucial: NEVER alter, omit, or approximate dollar amounts or counts. " +
                    "If the input already adheres to plain English, return it unchanged. " +
                    "If it contains jargon, simplify it in 1-2 active sentences while preserving all exact numbers. " +
                    "Reply with the final text only."
                )
            }
        )

        return try {
            val prompt = if (verifiedAmount != null) {
                "Prose to judge: \"$prose\". Must preserve exact number: \$$verifiedAmount."
            } else {
                "Prose to judge: \"$prose\"."
            }
            val response = judgeModel.generateContent(prompt)
            val rewritten = response.text?.trim()
            if (!rewritten.isNullOrBlank()) rewritten else prose
        } catch (e: Exception) {
            Log.w(TAG, "Judge evaluation skipped due to error: ${e.message}")
            prose
        }
    }

    private fun fallbackIntentHeuristic(prompt: String): FinancialQuerySpec {
        val lower = prompt.lowercase()
        val isAverage = lower.contains("average") || lower.contains("typical") || lower.contains("per month")
        val isCount = lower.contains("how many times") || lower.contains("how often") || lower.contains("count")
        val targetMetric = when {
            isAverage -> "MONTHLY_AVERAGE"
            isCount -> "TRANSACTION_COUNT"
            else -> "TOTAL_SUM"
        }

        return FinancialQuerySpec(
            targetMetric = targetMetric,
            temporalGrain = "MONTH",
            denominatorStrategy = "ACTIVE_PERIODS_ONLY"
        )
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
    }
}