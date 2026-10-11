package com.talktomyfinances.android.domain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainTest {
    // --- Goal mapping ---
    @Test
    fun customGoalMapsToCategory() = runBlocking {
        val r = GoalResolver { "Debt Minimization" }
        assertEquals(FinancialGoal.DEBT_MINIMIZATION, r.resolveCustom("pay off my visa"))
    }

    @Test
    fun unmappableGoalFallsBackToMindfulSpending() = runBlocking {
        assertEquals(FinancialGoal.MINDFUL_SPENDING, GoalResolver { "Learn to juggle" }.resolveCustom("juggling"))
        assertEquals(FinancialGoal.MINDFUL_SPENDING, GoalResolver { null }.resolveCustom("x"))
        assertEquals(FinancialGoal.MINDFUL_SPENDING, GoalResolver { error("boom") }.resolveCustom("x"))
        assertEquals(FinancialGoal.MINDFUL_SPENDING, GoalResolver { "Safety Net" }.resolveCustom("  "))
    }

    // --- Backend chip payload (2+1 is enforced by the Cloud Run Curation Agent) ---
    private val payload = """
        {"chips":[
          {"text":"a","recency":"this_week"},
          {"text":"b","recency":"this_pay_cycle"},
          {"text":"c","recency":"ytd","isSerendipity":true,"extra":1}
        ]}
    """.trimIndent()

    @Test
    fun parsesBackendChipsAndRecognisesTwoPlusOne() {
        val result = InsightsResult.parse(payload)
        assertEquals(listOf(RecencyWindow.THIS_WEEK, RecencyWindow.THIS_PAY_CYCLE, RecencyWindow.YTD), result.chips.map { it.recency })
        assertTrue(result.isValidTwoPlusOne)
    }

    @Test
    fun rejectsNonTwoPlusOneShapes() {
        assertTrue(!InsightsResult(emptyList()).isValidTwoPlusOne)
        val allGoal = InsightsResult(List(3) { InsightChip("x", RecencyWindow.THIS_WEEK) })
        assertTrue(!allGoal.isValidTwoPlusOne)
    }

    // --- Readability ---
    @Test
    fun scorerSeparatesSimpleFromJargon() {
        val simple = "You spent less this week. That is good news."
        val jargon = "Discretionary expenditure demonstrated considerable deceleration relative to historical averages."
        assertTrue(ReadabilityScorer.gradeLevel(simple) <= 8.0)
        assertTrue(ReadabilityScorer.gradeLevel(jargon) > 8.0)
    }

    @Test
    fun guardRewritesJargonButLeavesSimpleTextAlone() = runBlocking {
        var calls = 0
        val guard = ReadabilityGuard({ _, _ -> calls++; "You spent less on food." })
        val simple = "You spent less this week."
        assertEquals(simple, guard.ensureReadable(simple))
        assertEquals(0, calls)

        val jargon = "Discretionary expenditure demonstrated considerable deceleration relative to historical averages."
        assertEquals("You spent less on food.", guard.ensureReadable(jargon))
        assertEquals(1, calls)
    }

    @Test
    fun guardKeepsOriginalWhenRewriterFails() = runBlocking {
        val jargon = "Discretionary expenditure demonstrated considerable deceleration relative to historical averages."
        val guard = ReadabilityGuard({ _, _ -> error("offline") })
        assertEquals(jargon, guard.ensureReadable(jargon))
    }

    @Test
    fun guardRejectsRewriteWhenNumbersAreDropped() = runBlocking {
        val jargonWithNumbers = "Discretionary expenditure demonstrated $140 deceleration."
        val rewriterDroppedNumber = "You spent less overall."
        val guard = ReadabilityGuard({ _, _ -> rewriterDroppedNumber })

        // Should reject rewrite because $140 was dropped, returning original jargon text
        assertEquals(jargonWithNumbers, guard.ensureReadable(jargonWithNumbers))
    }

    @Test
    fun guardAcceptsRewriteWhenNumbersArePreserved() = runBlocking {
        val jargonWithNumbers = "Discretionary expenditure demonstrated $140 deceleration."
        val rewriterPreservedNumber = "You saved $140 this quarter."
        val guard = ReadabilityGuard({ _, _ -> rewriterPreservedNumber })

        // Should accept rewrite because $140 is preserved
        assertEquals(rewriterPreservedNumber, guard.ensureReadable(jargonWithNumbers))
    }
}
