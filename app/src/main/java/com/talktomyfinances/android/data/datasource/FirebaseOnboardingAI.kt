package com.talktomyfinances.android.data.datasource

import com.google.firebase.ai.FirebaseAI
import com.google.firebase.ai.InferenceMode
import com.google.firebase.ai.OnDeviceConfig
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.talktomyfinances.android.domain.FinancialGoal
import com.talktomyfinances.android.domain.GoalClassifier
import com.talktomyfinances.android.domain.TextRewriter
import javax.inject.Inject

/**
 * Firebase AI Logic adapters for the onboarding goal classifier (cloud) and the Hybrid Inference
 * readability rewriter (on-device preferred).
 */
@OptIn(PublicPreviewAPI::class)
class FirebaseOnboardingAI @Inject constructor(
    private val aiModel: FirebaseAI,
    private val remoteConfig: FirebaseRemoteConfig
) : GoalClassifier, TextRewriter {

    private val modelName get() = remoteConfig.getString(HYBRID_CLOUD_MODEL_KEY)

    private val cloudModel by lazy {
        aiModel.generativeModel(modelName = modelName)
    }

    private val onDeviceFirstModel by lazy {
        aiModel.generativeModel(
            modelName = modelName,
            onDeviceConfig = OnDeviceConfig(mode = InferenceMode.PREFER_ON_DEVICE)
        )
    }

    override suspend fun classify(customGoal: String): String? {
        val labels = FinancialGoal.entries.joinToString { it.label }
        val prompt = remoteConfig.getString(CLASSIFY_GOAL_PROMPT_KEY)
            .replace("{{labels}}", labels)
            .replace("{{goal}}", customGoal)
        return cloudModel.generateContent(prompt).text
    }

    override suspend fun rewrite(text: String, targetGrade: Int): String {
        val prompt = remoteConfig.getString(REWRITE_PROMPT_KEY)
            .replace("{{grade}}", targetGrade.toString())
            .replace("{{text}}", text)
        return onDeviceFirstModel.generateContent(prompt).text.orEmpty().trim()
    }

    private companion object {
        const val HYBRID_CLOUD_MODEL_KEY = "hybrid_cloud_model"
        const val CLASSIFY_GOAL_PROMPT_KEY = "classify_goal_prompt"
        const val REWRITE_PROMPT_KEY = "readability_rewrite_prompt"
    }
}

