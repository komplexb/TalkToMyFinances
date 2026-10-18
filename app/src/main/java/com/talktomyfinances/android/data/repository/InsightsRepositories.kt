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
import kotlin.time.Duration.Companion.milliseconds

/** Sample data used until the Cloud Run Auditor endpoint is live. */
open class StubInsightsRepository @Inject constructor() : InsightsRepository {
    override suspend fun loadInsights(profile: OnboardingProfile): InsightsResult {
        delay(3_000.milliseconds)
        return InsightsResult(
            listOf(
                InsightChip(
                    text = "Nice work on cafes this week — you're only at $9.50, so you've definitely got room for a cheeky brunch!",
                    recency = RecencyWindow.THIS_WEEK,
                    drilldownPrompt = "How much did I spend on cafes this week?"
                ),
                InsightChip(
                    text = "Zero takeaway spend since Tuesday! You've got plenty of room to celebrate Friday night with pizza or takeout.",
                    recency = RecencyWindow.THIS_PAY_CYCLE,
                    drilldownPrompt = "How much did I spend on takeaway this pay cycle?"
                ),
                InsightChip(
                    text = "Your utility bills came in $140 lower this quarter ($105.83 vs $245.82). Want to see where you saved?",
                    recency = RecencyWindow.THIS_QUARTER,
                    isSerendipity = true,
                    drilldownPrompt = "How much did I spend on utility bills this quarter?"
                )
            )
        )
    }
}

/** REST client for the Cloud Run Auditor + Curation Agent (ADR 0004). */
class CloudRunInsightsRepository(
    private val baseUrl: String,
    private val isDemo: Boolean = false
) : InsightsRepository {
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
                val body = """{"goal":"${profile.goal.name}","payFrequency":"${profile.payCycle.frequency.name}","nextPayDate":"${profile.payCycle.nextPayDate}","isDemo":$isDemo}"""
                conn.outputStream.use { it.write(body.toByteArray()) }
                check(conn.responseCode in 200..299) { "Auditor returned ${conn.responseCode}" }
                InsightsResult.parse(conn.inputStream.bufferedReader().readText())
            } finally {
                conn.disconnect()
            }
        }
}

/** Uses StubInsightsRepository (Golden Happy Path chips) when `is_demo` is true; otherwise uses Cloud Run when `insights_base_url` is set in Remote Config, falling back to the stub on failure or when blank. */
class RoutingInsightsRepository @Inject constructor(
    private val remoteConfig: FirebaseRemoteConfig?,
    private val stub: StubInsightsRepository
) : InsightsRepository {

    constructor(
        stub: StubInsightsRepository,
        urlProvider: () -> String,
        isDemoProvider: () -> Boolean = { false }
    ) : this(null, stub) {
        this.urlProvider = urlProvider
        this.isDemoProvider = isDemoProvider
    }

    internal var urlProvider: () -> String = { remoteConfig?.getString("insights_base_url").orEmpty() }
    internal var isDemoProvider: () -> Boolean = { remoteConfig?.getBoolean("is_demo") ?: false }

    override suspend fun loadInsights(profile: OnboardingProfile): InsightsResult {
        val isDemo = isDemoProvider()
        if (isDemo) return stub.loadInsights(profile)

        val url = urlProvider().trim()
        if (url.isBlank()) return stub.loadInsights(profile)

        val urlsToTry = mutableListOf(url)
        if (url.contains("127.0.0.1") || url.contains("localhost")) {
            val emulatorUrl = url.replace("127.0.0.1", "10.0.2.2").replace("localhost", "10.0.2.2")
            if (!urlsToTry.contains(emulatorUrl)) {
                urlsToTry.add(emulatorUrl)
            }
        }

        for (targetUrl in urlsToTry) {
            try {
                return CloudRunInsightsRepository(targetUrl, isDemo = isDemo).loadInsights(profile)
            } catch (e: Exception) {
                try {
                    android.util.Log.w("Insights", "Failed to load insights from $targetUrl, trying next URL or stub", e)
                } catch (_: Throwable) {
                    // Ignore log error in unmocked unit tests
                }
            }
        }
        return stub.loadInsights(profile)
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

