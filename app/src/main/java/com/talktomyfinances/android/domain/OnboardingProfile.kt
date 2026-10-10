package com.talktomyfinances.android.domain

import kotlinx.serialization.Serializable

enum class PayFrequency(val label: String) { WEEKLY("Every week"), FORTNIGHTLY("Every 2 weeks"), MONTHLY("Every month") }

/** [nextPayDate] is ISO-8601 (yyyy-MM-dd). */
@Serializable
data class PayCycle(val frequency: PayFrequency, val nextPayDate: String)

data class OnboardingProfile(val goal: FinancialGoal, val payCycle: PayCycle)

/** Boundary the Insights Screen loads through. REST today (ADR 0004); swappable. */
interface InsightsRepository {
    suspend fun loadInsights(profile: OnboardingProfile): InsightsResult
}

/** Persists the onboarding profile. */
interface ProfileStore {
    suspend fun save(profile: OnboardingProfile)
    suspend fun load(): OnboardingProfile?
}

