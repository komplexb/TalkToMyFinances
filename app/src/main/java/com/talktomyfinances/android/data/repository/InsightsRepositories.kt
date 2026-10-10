package com.talktomyfinances.android.data.repository

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.talktomyfinances.android.domain.FinancialGoal
import com.talktomyfinances.android.domain.InsightChip
import com.talktomyfinances.android.domain.InsightsRepository
import com.talktomyfinances.android.domain.InsightsResult
import com.talktomyfinances.android.domain.OnboardingProfile
import com.talktomyfinances.android.domain.PayCycle
import com.talktomyfinances.android.domain.PayFrequency
import com.talktomyfinances.android.domain.ProfileStore
import com.talktomyfinances.android.domain.RecencyWindow
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

/** Sample data used until the Cloud Run Auditor endpoint is live. */
class StubInsightsRepository @Inject constructor() : InsightsRepository {
    override suspend fun loadInsights(profile: OnboardingProfile): InsightsResult {
        delay(3_000)
        return InsightsResult(
            listOf(
                InsightChip("Nice work on your cafe spending this week. You have room for a cheeky brunch!", RecencyWindow.THIS_WEEK),
                InsightChip("You have \$35 ready if you want Friday night pizza.", RecencyWindow.THIS_PAY_CYCLE),
                InsightChip("Your utility bills came in \$140 lower this quarter. Want to see why?", RecencyWindow.THIS_QUARTER, isSerendipity = true)
            )
        )
    }
}

/** REST client for the Cloud Run Auditor + Curation Agent (ADR 0004). */
class CloudRunInsightsRepository(private val baseUrl: String) : InsightsRepository {
    override suspend fun loadInsights(profile: OnboardingProfile): InsightsResult =
        withContext(Dispatchers.IO) {
            val conn = (URL("${baseUrl.trimEnd('/')}/audit").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 60_000
                setRequestProperty("Content-Type", "application/json")
            }
            try {
                val body = """{"goal":"${profile.goal.name}","payFrequency":"${profile.payCycle.frequency.name}","nextPayDate":"${profile.payCycle.nextPayDate}"}"""
                conn.outputStream.use { it.write(body.toByteArray()) }
                check(conn.responseCode in 200..299) { "Auditor returned ${conn.responseCode}" }
                InsightsResult.parse(conn.inputStream.bufferedReader().readText())
            } finally {
                conn.disconnect()
            }
        }
}

/** Uses Cloud Run when `insights_base_url` is set in Remote Config, otherwise the stub. */
class RoutingInsightsRepository @Inject constructor(
    private val remoteConfig: FirebaseRemoteConfig,
    private val stub: StubInsightsRepository
) : InsightsRepository {
    override suspend fun loadInsights(profile: OnboardingProfile): InsightsResult {
        val url = remoteConfig.getString("insights_base_url")
        val delegate = if (url.isBlank()) stub else CloudRunInsightsRepository(url)
        return delegate.loadInsights(profile)
    }
}

/** Stores the profile at `users/{uid}.onboarding` in Firestore. */
class FirestoreProfileStore @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth
) : ProfileStore {
    override suspend fun save(profile: OnboardingProfile) {
        val uid = auth.currentUser?.uid ?: return
        val data = mapOf(
            "goal" to profile.goal.name,
            "payFrequency" to profile.payCycle.frequency.name,
            "nextPayDate" to profile.payCycle.nextPayDate
        )
        firestore.collection("users").document(uid)
            .set(mapOf("onboarding" to data), com.google.firebase.firestore.SetOptions.merge())
    }

    override suspend fun load(): OnboardingProfile? {
        val uid = auth.currentUser?.uid ?: return null
        return try {
            val doc = firestore.collection("users").document(uid).get().await()
            @Suppress("UNCHECKED_CAST")
            val data = doc.get("onboarding") as? Map<String, String> ?: return null
            OnboardingProfile(
                goal = FinancialGoal.valueOf(data.getValue("goal")),
                payCycle = PayCycle(PayFrequency.valueOf(data.getValue("payFrequency")), data.getValue("nextPayDate"))
            )
        } catch (_: Exception) {
            null
        }
    }
}

