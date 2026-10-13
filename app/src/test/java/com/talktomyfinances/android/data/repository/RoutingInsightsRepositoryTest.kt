package com.talktomyfinances.android.data.repository

import com.talktomyfinances.android.domain.FinancialGoal
import com.talktomyfinances.android.domain.OnboardingProfile
import com.talktomyfinances.android.domain.PayCycle
import com.talktomyfinances.android.domain.PayFrequency
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class RoutingInsightsRepositoryTest {

    private val profile = OnboardingProfile(
        goal = FinancialGoal.MINDFUL_SPENDING,
        payCycle = PayCycle(PayFrequency.FORTNIGHTLY, "2026-03-30")
    )

    private class ImmediateStubRepository : StubInsightsRepository() {
        override suspend fun loadInsights(profile: OnboardingProfile) =
            com.talktomyfinances.android.domain.InsightsResult(
                listOf(
                    com.talktomyfinances.android.domain.InsightChip(
                        text = "Stub chip",
                        recency = com.talktomyfinances.android.domain.RecencyWindow.THIS_WEEK
                    )
                )
            )
    }

    @Test
    fun fallsBackToStubWhenUrlIsBlank() = runBlocking {
        val repo = RoutingInsightsRepository(
            stub = ImmediateStubRepository(),
            urlProvider = { "" }
        )
        val result = repo.loadInsights(profile)
        assertNotNull(result)
        assertEquals("Stub chip", result.chips.first().text)
    }

    @Test
    fun fallsBackToStubWhenServerIsUnreachable() = runBlocking {
        val repo = RoutingInsightsRepository(
            stub = ImmediateStubRepository(),
            urlProvider = { "http://127.0.0.1:59999" }
        )
        val result = repo.loadInsights(profile)
        assertNotNull(result)
        assertEquals("Stub chip", result.chips.first().text)
    }
}
